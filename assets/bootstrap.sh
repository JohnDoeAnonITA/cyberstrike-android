#!/data/data/com.termux/files/usr/bin/bash
# ---------------------------------------------------------------------------
# CyberStrike bootstrap per Termux — idempotente.
# Eseguito dall'app via RUN_COMMAND. Installa proot-distro + Debian + CyberStrike
# se mancanti, poi avvia il server su 127.0.0.1:27182.
# ---------------------------------------------------------------------------
set -u

PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
export PATH="$PREFIX/bin:$PATH"
LOG="$HOME/.cyberstrike-setup.log"
DONE="$HOME/.cs-setup.done"
FAIL="$HOME/.cs-setup.failed"
PORT="${CS_PORT:-27182}"

exec >>"$LOG" 2>&1
rm -f "$FAIL"
echo
echo "=== $(date '+%F %T') bootstrap start ==="

wake() { command -v termux-wake-lock >/dev/null 2>&1 && termux-wake-lock; }
unwake() { command -v termux-wake-unlock >/dev/null 2>&1 && termux-wake-unlock; }
wake
trap 'unwake' EXIT

port_open() {
  (exec 3<>"/dev/tcp/127.0.0.1/$PORT") >/dev/null 2>&1 || return 1
  exec 3<&- 3>&-
  return 0
}

notify() {
  command -v termux-notification >/dev/null 2>&1 && \
    termux-notification --title "CyberStrike" --content "$1" >/dev/null 2>&1 || true
}

fail() {
  echo "!! FAILED: $*"
  notify "Setup fallito: $*"
  echo "$(date '+%F %T') $*" > "$FAIL"
  exit 1
}

# --- 1..4. prepara l'ambiente SOLO se non è già stato fatto ----------------
# Dopo il primo setup con successo esiste "$DONE": saltiamo installazione e
# verifiche pesanti (che avviano proot) ad ogni avvio, per non generare troppi
# processi figli (Android 12+ li killa con SIGKILL — "phantom process killer").
if [ -f "$DONE" ]; then
  echo "-- ambiente già preparato (salto installazione/verifiche)"
else

# --- 1. proot-distro -------------------------------------------------------
if ! command -v proot-distro >/dev/null 2>&1; then
  echo "-- installo proot-distro"
  yes | pkg install -y proot-distro || fail "pkg install proot-distro"
fi

# --- 2. Debian -------------------------------------------------------------
ROOTFS="$PREFIX/var/lib/proot-distro/containers/debian/rootfs"
if [ ! -d "$ROOTFS/etc" ]; then
  echo "-- installo Debian (qualche minuto)"
  proot-distro install debian || fail "proot-distro install debian"
fi

# --- 3. dipendenze dentro Debian ------------------------------------------
echo "-- verifico dipendenze nel container"
proot-distro login debian -- bash -lc '
set -e
export DEBIAN_FRONTEND=noninteractive
if ! command -v curl >/dev/null 2>&1 || ! command -v tar >/dev/null 2>&1; then
  apt-get update -qq
  apt-get install -y -qq curl ca-certificates tar
fi
' || fail "dipendenze Debian"

# --- 4. CyberStrike --------------------------------------------------------
echo "-- verifico CyberStrike"
proot-distro login debian -- test -x /root/.cyberstrike/bin/cyberstrike
if [ $? -ne 0 ]; then
  echo "-- installo CyberStrike (download ~200 MB)"
  proot-distro login debian -- bash -lc '
set -e
curl -fsSL https://cyberstrike.io/install.sh -o /tmp/cs-install.sh
bash /tmp/cs-install.sh
rm -f /tmp/cs-install.sh
test -x /root/.cyberstrike/bin/cyberstrike
  ' || fail "installer CyberStrike"
fi
fi

# --- 5. avvio server -------------------------------------------------------
# --- 5. password del server -----------------------------------------------
# CYBERSTRIKE_SERVER_PASSWORD è obbligatoria: senza, il server esce subito.
# proot-distro login non carica ~/.bashrc, quindi va passata esplicitamente.
PW_FILE="$HOME/.cs-server-password"
if [ ! -s "$PW_FILE" ]; then
  # password casuale dedicata all'app: generata una volta e persistita.
  # NON viene mai riusata la password dell'utente (~/.bashrc).
  PW=$(head -c 96 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 24)
  printf '%s' "$PW" > "$PW_FILE"
  chmod 600 "$PW_FILE"
  echo "-- password server generata e salvata in $PW_FILE"
fi
PW=$(cat "$PW_FILE")

# --- restart esplicito (dopo modifiche alla config fatte dall'app) ----------
if [ "${CS_RESTART:-0}" = "1" ]; then
  P=$(pgrep -f "^/root/.cyberstrike/bin/cyberstrike serve --port $PORT" | head -1)
  if [ -n "$P" ]; then
    echo "-- restart: termino il server (pid $P)"
    kill "$P" 2>/dev/null || true
    for i in $(seq 1 15); do port_open || break; sleep 1; done
  fi
fi

# --- 6. supervisor: UN solo proot con loop di riavvio interno --------------
# I riavvii del server avvengono DENTRO lo stesso proot: non si creano nuovi
# proot ad ogni crash (evita l'accumulo di processi figli → SIGKILL di Android).
WATCH="$HOME/.cs-supervisor.sh"
PIDF="$HOME/.cs-supervisor.pid"

cat > "$WATCH" <<WEOF
#!/data/data/com.termux/files/usr/bin/bash
export PATH="$PREFIX/bin:\$PATH"
PORT="$PORT"
PW="$PW"
LOG="\$HOME/.cyberstrike-server.log"
PF="\$HOME/.cs-supervisor.pid"
echo \$\$ > "\$PF"
command -v termux-wake-lock >/dev/null 2>&1 && termux-wake-lock
delay=5
while true; do
  if (exec 3<>"/dev/tcp/127.0.0.1/\$PORT") >/dev/null 2>&1; then
    exec 3<&- 3>&- 2>/dev/null
    sleep 10
    continue
  fi
  echo "=== \$(date '+%F %T') supervisor: server giù, avvio/riavvio (attesa \${delay}s) ===" >> "\$LOG"
  start=\$(date +%s)
  proot-distro login debian -- env CYBERSTRIKE_SERVER_PASSWORD="\$PW" CS_PORT="\$PORT" \\
    bash -c 'while true; do /root/.cyberstrike/bin/cyberstrike serve --port "\$CS_PORT" --hostname 127.0.0.1; sleep 5; done' \\
    >> "\$LOG" 2>&1
  ran=\$(( \$(date +%s) - start ))
  if [ "\$ran" -ge 60 ]; then delay=5; else
    delay=\$(( delay * 2 )); [ "\$delay" -gt 180 ] && delay=180
  fi
  sleep "\$delay"
done
WEOF
chmod +x "$WATCH"

# avvia il supervisor se non è già in esecuzione (setsid: sopravvive alla
# chiusura della sessione RUN_COMMAND)
if [ -f "$PIDF" ] && kill -0 "$(cat "$PIDF" 2>/dev/null)" 2>/dev/null; then
  echo "-- supervisor già attivo (pid $(cat "$PIDF"))"
else
  if command -v setsid >/dev/null 2>&1; then
    setsid "$WATCH" >/dev/null 2>&1 < /dev/null &
  else
    nohup "$WATCH" >/dev/null 2>&1 &
  fi
  disown 2>/dev/null || true
  echo "-- supervisor avviato"
fi

# attende che il server sia raggiungibile
for i in $(seq 1 60); do
  port_open && break
  sleep 1
done
if ! port_open; then
  echo "-- ultime righe del server:"
  tail -5 "$HOME/.cyberstrike-server.log"
  fail "il server non risponde su :$PORT"
fi

echo "$(date '+%F %T') bootstrap OK"
notify "Server pronto su 127.0.0.1:$PORT"
touch "$DONE"
echo "=== $(date '+%F %T') bootstrap end ==="
exit 0
