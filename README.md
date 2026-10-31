# CyberStrike Android Client

> **Stato: 0.2.1 — in uso su dispositivo.**
> Client nativo per il server [CyberStrike](https://cyberstrike.io): sessioni,
> chat in streaming SSE, permessi, domande dell'agente e terminale PTY.
> Problemi? `⋮ → Diagnostica → Copia` e incolla il report.

APK **~440 KB**, senza Gradle e senza AndroidX: un modulo Java compilato a mano
con `aapt2` + `javac` + `d8`, impacchettato e firmato con `apksigner`.

L'interfaccia è in stile **PIP-BOY**: fosforo verde su vetro catodico, scanline
CRT, font monospace e — in sottofondo — *I Don't Want To Set The World On Fire*
letta dai file audio del telefono.

```
┌────────────────────────────────┐
│  App CyberStrike (Java)        │
│  ├─ Sessioni (ListView)        │
│  ├─ Chat + streaming SSE       │
│  ├─ Permessi / Domande         │
│  └─ Terminale (PTY)            │
└───────────────┬────────────────┘
                │ HTTP + SSE  →  127.0.0.1:27182
┌───────────────▼────────────────┐
│  Termux                        │
│   └─ ~/.cs-supervisor.sh       │   un solo proot, riavvio interno
│        └─ proot Debian         │
│             └─ cyberstrike     │   serve --port 27182 --hostname 127.0.0.1
└────────────────────────────────┘
```

## Cosa fa

| Funzione | Endpoint |
|---|---|
| Lista / crea / elimina sessioni | `GET/POST/DELETE /session` |
| Cronologia messaggi | `GET /session/{id}/message` |
| Invio prompt (bloccante, streaming via SSE) | `POST /session/{id}/message` |
| Streaming live di testo, reasoning e tool | `GET /event` |
| Approvazione permessi tool | `GET /permission`, `POST /permission/{id}/reply` |
| Risposta alle domande dell'agente | `GET /question`, `POST /question/{id}/reply` |
| Interruzione | `POST /session/{id}/abort` |
| Modelli e agenti | `GET /config/providers`, `GET /agent` |
| Terminale | `POST /pty` + WebSocket `/pty/{id}/connect` |

## Grafica

- **Palette fosforo**: fondo `#020A08`, testo `#93FFC6`, accento `#3CFF8F`,
  errore `#FF6A3D`. Bordi squadrati (2-3 dp), pannelli con barra accento.
- **`ui/CrtOverlay`**: scanline orizzontali, vignettatura e banda luminosa che
  scende lentamente. Presente su sessioni, chat, modelli e terminale; non
  intercetta i tocchi.
- **`ui/BootScreen`** (intro, ~5 s, un tocco la salta):
  intestazione `ROBCO INDUSTRIES (TM) TERMLINK PROTOCOL`, griglia di 16 password
  esadecimali, tre tentativi con `LIKENESS=`, `ACCESS GRANTED` e infine le righe
  di avvio di CyberStrike OS.
- **Font**: *Share Tech Mono* (SIL OFL 1.1) in
  `assets/fonts/pip.ttf`, applicato a tutte le activity da `CsApp`; le viste con
  tag `nofont` (emoji) restano sul font di sistema.
- **Icone** launcher e foreground adattivo generate come gauge circolare con
  tacche e lancetta.

## Audio

`util/Ambient.java` cerca la traccia fra i file audio del dispositivo:
`MediaStore` con `DISPLAY_NAME LIKE '%set the world%'` e `SIZE > 100 KB`,
altrimenti scansione di `Download/`. Riproduce in loop a volume 0.5, mette in
pausa quando l'app va in background (conteggio delle activity avviate in
`CsApp`) e rispetta l'audio focus.

Il brano **non è incluso nell'APK** (è protetto da copyright): viene letto dal
file locale. Serve il permesso di lettura audio — `READ_MEDIA_AUDIO` su Android
13+, `READ_EXTERNAL_STORAGE` fino ad Android 12 — richiesto una volta ~3 s dopo
l'intro. On/off da `⋮ → Musica: ON/OFF`.

## Requisiti

1. **Termux** — l'app lo scarica da GitHub Releases e apre l'installer
   (serve consentire le sorgenti sconosciute per CyberStrike).
   In alternativa: [GitHub Releases](https://github.com/termux/termux-app/releases), APK `arm64-v8a`.
   Evita la versione del Play Store, obsoleta e non compatibile con proot-distro.
2. In Termux, abilitare l'esecuzione da altre app:

   ```bash
   echo 'allow-external-apps = true' >> ~/.termux/termux.properties
   termux-reload-settings
   ```

3. ~600 MB di spazio libero per Debian + CyberStrike.

## Installazione

Scarica l'APK dall'ultima [Release](../../releases) e installalo
(serve consentire l'installazione da origini sconosciute).

Al primo avvio l'app:

1. verifica se il server risponde su `127.0.0.1:27182`;
2. se non risponde, esegue `assets/bootstrap.sh` dentro Termux:
   - installa `proot-distro` e Debian se mancano;
   - installa `curl`/`tar` nel container;
   - scarica CyberStrike se `~/.cyberstrike/bin/cyberstrike` non esiste;
   - genera la password del server in `~/.cs-server-password`;
   - scrive e avvia il supervisor `~/.cs-supervisor.sh`;
3. attende che la porta risponda (fino a 30 minuti al primo avvio).

Il log del setup è in `~/.cyberstrike-setup.log`, quello del server in
`~/.cyberstrike-server.log` (dentro Termux). Il sentinel `~/.cs-setup.done`
indica che l'installazione è già stata fatta: dai avvii successivi il bootstrap
salta installazione e verifiche.

### Supervisor e riavvii

Il server gira dentro **un solo** `proot` gestito da `~/.cs-supervisor.sh`:

```bash
while true; do cyberstrike serve --port 27182 --hostname 127.0.0.1; sleep 5; done
```

Il loop sta *dentro* il container: se il server muore viene riavviato senza
creare nuovi processi `proot`. È la contromisura al *phantom process killer* di
Android 12+, che chiude Termux (SIGKILL) quando i processi figli si accumulano.
Se il crash è immediato il supervisor aspetta con backoff 5 s → 180 s.

### Porta

L'app usa **27182** come default (non 4096, che è il default di CyberStrike e
spesso già occupato da un'istanza avviata a mano). La porta si cambia da
`⋮ → Impostazioni → Server URL`; il bootstrap avvia il server sulla porta
dell'URL configurato.

## Configurazione

Menu `⋮` → **Impostazioni**:

- **Server URL** — default `http://127.0.0.1:27182`. Può puntare a un server remoto.
- **Token** — inviato come `Authorization: Bearer …` se il server lo richiede.
- **Agente** — default `cyberstrike`.
- **Modello** — provider/modello da `/config/providers`.
- **Avvio automatico** — bootstrap all'apertura.

Altri comandi del menu `⋮`: nuova sessione, terminale, aggiorna,
**installa / avvia server** (bootstrap a mano), apri Termux, diagnostica,
richiedi permesso Termux, modelli e provider, musica on/off.

## Modelli e provider

`⋮ → Modelli e provider`:

- **scegli** il provider/modello attivo (tap sul modello)
- **aggiungi provider**: id, nome, baseURL, API key opzionale (`PUT /auth/{id}`) e **uno o più modelli**
  (selezione multipla dalla lista di `GET <baseURL>/models`, oppure righe manuali)
- **aggiungi modelli** a un provider esistente, anche più d'uno:
  - selezione multipla con filtro (checkbox per riga) dalla lista `GET <baseURL>/models`
  - inserimento manuale multiriga (`id` oppure `id = nome`, una riga per modello)
- **disattiva / riattiva** provider (`disabled_providers`, reversibile)
- **predefinito per provider** (★): usato dall'auto-selezione quando il modello attivo non è impostato
- **elimina provider/modello dal server**: modifica `/cyberstrike.json` via Termux e riavvia
  solo il server dell'app (porta 27182). L'istanza principale su 4096 non viene toccata.

Note tecniche:
- `PATCH /config` fa un **merge profondo** e scrive in `<progetto>/cyberstrike.json`;
  non esiste un endpoint di delete, da cui il percorso via file+restart.
- Le API key finiscono in `~/.local/share/cyberstrike/auth.json`.
- La lista modelli è letta direttamente dal provider (`GET {baseURL}/models`, formato OpenAI:
  `{"data":[{"id":…,"display_name":…}]}`); servono baseURL e, se richiesta, API key.

## Permessi

Le sessioni create dall'app usano di default il ruleset:

```json
[{"permission": "*", "pattern": "*", "action": "allow"}]
```

equivale ad autorizzare automaticamente `bash`, browser, `read`, `write`/`edit`,
`glob`, `grep`, `task`, `webfetch`, `skill`, ecc. — nessun prompt di conferma.

Si disattiva in `⋮ → Impostazioni → Permetti tutti i tool (allow-all)`.
Nota: il ruleset viene applicato alla **creazione** della sessione (`POST /session`);
`PATCH /session` non permette di modificare i permessi di una sessione esistente.

## Sicurezza

L'API di CyberStrike esegue comandi shell e ha, in configurazione tipica,
`"bash": { "*": "allow" }`. Di conseguenza:

- il server ascolta **solo** su `127.0.0.1` (default) e non va esposto in LAN;
- **qualsiasi app Android** con permesso INTERNET può raggiungere `127.0.0.1:27182`:
  su un dispositivo non fidato è un rischio di esecuzione comandi;
- se esponi il server (es. `--hostname 0.0.0.0`), metti un reverse proxy con TLS e
  autenticazione e configura il token nell'app.

L'app applica:
- cleartext HTTP consentito **solo** verso `127.0.0.1`/`localhost` (`network_security_config.xml`);
- nessuna navigazione web: è un client REST, non un browser.

## Build

### GitHub Actions

Push su `main` → artifact `cyberstrike-client-apk`.
Tag `v*` → build + release automatica con APK firmato. Il `versionCode` viene
derivato dal nome versione: `0.2.1 → 201`, `1.0.0 → 10000`
(`major*10000 + minor*100 + patch`), così build locali e CI restano monotone.

Per una chiave di firma stabile, imposta il secret `KEYSTORE_B64`
(base64 di un keystore JKS, alias `cyberstrike`, password `android`):

```bash
base64 -w0 ks.jks | gh secret set KEYSTORE_B64 -R <owner>/<repo>
```

### Locale (Termux/Debian, senza Gradle)

```bash
VERSION_NAME=0.2.1 bash build.sh /path/to/android.jar /path/to/build-tools/34.0.0
```

Servono `aapt2`, `zipalign`, `apksigner`, `javac`, `keytool`.
Lo script scarica una volta R8 in `.build/` (il `d8` dei build-tools 34 va in
crash su classi anonime compilate da JDK 21) e genera `cyberstrike-client.apk`.

## Struttura

```
AndroidManifest.xml
build.sh
assets/
  bootstrap.sh        # setup Termux + Debian + CyberStrike + supervisor
  fonts/pip.ttf       # Share Tech Mono (OFL 1.1) + OFL.txt
  term.html           # terminale xterm.js (solo widget)
  xterm.js/.css
res/
  layout/             # activity_*, item_*, dlg_settings
  drawable/           # pannelli, bolle, chip, input, header
  values/             # colors (palette fosforo), styles, strings
  mipmap-*/           # icone launcher
src/com/cyberstrike/client/
  CsApp.java             # font globale + ciclo di vita della musica
  MainActivity.java      # sessioni, stato server, setup, menu
  ChatActivity.java      # chat, SSE, permessi, domande, allegati
  ModelsActivity.java    # provider e modelli
  TerminalActivity.java  # PTY WebSocket
  api/CsClient.java      # REST
  api/EventStream.java   # SSE
  ui/CrtOverlay.java     # scanline + vignettatura + sweep
  ui/BootScreen.java     # intro ROBCO + hacking + avvio
  ui/MsgAdapter.java     # messaggi, bolle, immagini, tabelle
  ui/TableView.java
  util/Ambient.java      # colonna sonora da file locale
  util/Fonts.java        # font monospace dagli asset
  util/Prefs.java        # preferenze
  util/Bootstrap.java    # avvio script in Termux
```

## Changelog

- **0.2.1** — intro: schermata di hacking terminale ROBCO (password, `LIKENESS`,
  `ACCESS GRANTED`), saltabile con un tocco.
- **0.2.0** — restyle completo in stile PIP-BOY: palette fosforo, CRT overlay,
  font Share Tech Mono, icone nuove, intro ROBCO e colonna sonora.
- **0.1.27** — un solo supervisor dentro il proot (meno processi figli, riavvii
  senza nuovi `proot`); rimosso il bootstrap automatico a ogni disconnessione SSE.
- **0.1.26** — "Installa / avvia server" controlla lo stato fuori dal thread UI e
  installa subito il watchdog.
- **0.1.25** — riavvio automatico del server e pulsante **Ferma** con feedback.
- **0.1.24** — domande pendenti all'apertura, comandi bash completi, pulsante
  "torna in fondo".

## Licenza

MIT. Il font *Share Tech Mono* è distribuito con licenza SIL Open Font License
1.1 (vedi `assets/fonts/OFL.txt`).
