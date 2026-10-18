# CyberStrike Android Client

Client Android **nativo** (REST, non WebView) per il server [CyberStrike](https://cyberstrike.io).
APK ~150 KB, senza Gradle, senza dipendenze AndroidX.

```
┌──────────────────────────────┐
│  App CyberStrike (Java)      │
│  ├─ Sessioni (ListView)      │
│  ├─ Chat + streaming SSE     │
│  ├─ Permessi / Domande       │
│  └─ Terminale (PTY)          │
└───────────┬──────────────────┘
            │ HTTP + SSE  →  127.0.0.1:4096
┌───────────▼──────────────────┐
│ Termux → proot Debian        │
│   cyberstrike web            │
└──────────────────────────────┘
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

## Requisiti

1. **Termux** da [F-Droid](https://f-droid.org/packages/com.termux/) (la versione del Play Store è obsoleta).
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

1. verifica se il server risponde su `127.0.0.1:4096`;
2. se non risponde, esegue `assets/bootstrap.sh` dentro Termux:
   - installa `proot-distro` se manca;
   - installa Debian se manca;
   - installa `curl`/`tar` nel container;
   - scarica e installa CyberStrike se `~/.cyberstrike/bin/cyberstrike` non esiste;
   - avvia `cyberstrike web` su `127.0.0.1:4096`;
3. attende che `/global/health` risponda (fino a 30 minuti al primo avvio).

Il log dell'installazione è in `~/.cyberstrike-setup.log` (dentro Termux).

## Configurazione

Menu `⋮` → **Impostazioni**:

- **Server URL** — default `http://127.0.0.1:4096`. Può puntare a un server remoto.
- **Token** — inviato come `Authorization: Bearer …` se il server lo richiede.
- **Agente** — default `cyberstrike`.
- **Modello** — provider/modello da `/config/providers`.
- **Avvio automatico** — bootstrap all'apertura.

## Sicurezza

L'API di CyberStrike esegue comandi shell e ha, in configurazione tipica,
`"bash": { "*": "allow" }`. Di conseguenza:

- il server ascolta **solo** su `127.0.0.1` (default) e non va esposto in LAN;
- **qualsiasi app Android** con permesso INTERNET può raggiungere `127.0.0.1:4096`:
  su un dispositivo non fidato è un rischio di esecuzione comandi;
- se esponi il server (es. `--hostname 0.0.0.0`), metti un reverse proxy con TLS e
  autenticazione e configura il token nell'app.

L'app applica:
- cleartext HTTP consentito **solo** verso `127.0.0.1`/`localhost` (`network_security_config.xml`);
- nessuna navigazione web: è un client REST, non un browser.

## Build

### GitHub Actions

Push su `main` → artifact `cyberstrike-client-apk`.
Tag `v*` → build + release automatica con APK firmato.

Per una chiave di firma stabile, imposta il secret `KEYSTORE_B64`
(base64 di un keystore JKS, alias `cyberstrike`, password `android`):

```bash
base64 -w0 ks.jks | gh secret set KEYSTORE_B64 -R <owner>/<repo>
```

### Locale (Termux/Debian, senza Gradle)

```bash
bash build.sh /path/to/android.jar /path/to/build-tools/34.0.0
```

Servono `aapt2`, `zipalign`, `apksigner`, `javac`, `keytool`.
Lo script scarica una volta R8 in `.build/` (il `d8` dei build-tools 34 va in
crash su classi anonime compilate da JDK 21).

## Struttura

```
AndroidManifest.xml
build.sh
assets/
  bootstrap.sh      # setup Termux + Debian + CyberStrike
  term.html         # terminale xterm.js (solo widget)
  xterm.js/.css
res/                # layout, temi, icone
src/com/cyberstrike/client/
  MainActivity.java      # sessioni, stato server, setup
  ChatActivity.java      # chat, SSE, permessi, domande
  TerminalActivity.java  # PTY WebSocket
  api/CsClient.java      # REST
  api/EventStream.java   # SSE
  ui/MsgAdapter.java
  util/                  # prefs, bootstrap, termux
```

## Licenza

MIT
