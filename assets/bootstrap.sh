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

if port_open; then
  echo "-- server già in ascolto su :$PORT"
else
  echo "-- avvio cyberstrike serve su :$PORT"
  nohup proot-distro login debian -- env CYBERSTRIKE_SERVER_PASSWORD="$PW" \
    /root/.cyberstrike/bin/cyberstrike serve --port "$PORT" --hostname 127.0.0.1 \
    >>"$HOME/.cyberstrike-server.log" 2>&1 &
  disown 2>/dev/null || true

  for i in $(seq 1 60); do
    port_open && break
    sleep 1
  done
  if ! port_open; then
    echo "-- ultime righe del server:"
    tail -5 "$HOME/.cyberstrike-server.log"
    fail "il server non risponde su :$PORT"
  fi
fi

echo "$(date '+%F %T') bootstrap OK"
notify "Server pronto su 127.0.0.1:$PORT"
touch "$DONE"
echo "=== $(date '+%F %T') bootstrap end ==="
exit 0
