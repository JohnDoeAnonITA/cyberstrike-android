package com.cyberstrike.client;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.Spinner;
import android.widget.TextView;

import com.cyberstrike.client.api.CsClient;
import com.cyberstrike.client.api.EventStream;
import com.cyberstrike.client.util.Bootstrap;
import com.cyberstrike.client.util.Diag;
import com.cyberstrike.client.util.Prefs;
import com.cyberstrike.client.util.ServerStarter;
import com.cyberstrike.client.util.Ui;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final String TERMUX_PERM = "com.termux.permission.RUN_COMMAND";
    private static final int REQ_TERMUX_PERM = 1001;

    private final ExecutorService io = Executors.newCachedThreadPool();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private CsClient api;
    private EventStream events;
    private SessionAdapter adapter;

    private TextView status;
    private View dot;
    private ListView list;
    private View empty;

    private volatile boolean booting;
    private volatile boolean online;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        status = (TextView) findViewById(R.id.status);
        dot = findViewById(R.id.dot);
        list = (ListView) findViewById(R.id.list);
        empty = findViewById(R.id.empty);

        adapter = new SessionAdapter();
        list.setAdapter(adapter);

        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                open(adapter.items.get(pos));
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) {
                confirmDelete(adapter.items.get(pos));
                return true;
            }
        });

        findViewById(R.id.fab).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                newSession();
            }
        });

        View.OnClickListener retry = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boot();
            }
        };
        status.setOnClickListener(retry);
        dot.setOnClickListener(retry);

        findViewById(R.id.overflow).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMenu(v);
            }
        });

        api = new CsClient(Prefs.base(this), Prefs.token(this));
        boot();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_TERMUX_PERM) return;
        Prefs.setPermAsked(this, true);
        boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        Diag.add("perm", "esito " + TERMUX_PERM + " = " + granted);
        if (granted) {
            boot();
        } else {
            Ui.toast(this, "Permesso negato: avvia il server a mano da Termux");
            onOffline();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        api = new CsClient(Prefs.base(this), Prefs.token(this));
        if (!booting) {
            refresh();
            startEvents();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopEvents();
    }

    // ------------------------------------------------------------- connessione

    private void boot() {
        if (booting) return;

        final boolean local = Bootstrap.isLocal(Prefs.base(this));

        // RUN_COMMAND è un permesso "dangerous" di Termux: serve solo per il
        // server locale, non per un server remoto.
        if (local && ServerStarter.installed(this)
                && checkSelfPermission(TERMUX_PERM) != PackageManager.PERMISSION_GRANTED) {
            if (!Prefs.permAsked(this)) {
                Diag.add("perm", "richiesta " + TERMUX_PERM);
                requestPermissions(new String[]{TERMUX_PERM}, REQ_TERMUX_PERM);
                return;
            }
            Diag.add("perm", TERMUX_PERM + " non concesso (già chiesto)");
        }

        booting = true;
        setStatus(getString(R.string.connecting), false);

        io.execute(new Runnable() {
            @Override
            public void run() {
                if (api.healthy()) {
                    Diag.add("boot", "health OK su " + Prefs.base(MainActivity.this));
                    done(true);
                    return;
                }
                Diag.add("boot", "health KO su " + Prefs.base(MainActivity.this));

                if (!Bootstrap.isLocal(Prefs.base(MainActivity.this))) {
                    // server remoto: l'app è solo un client, Termux non serve
                    Diag.add("boot", "server remoto non raggiungibile");
                    done(false);
                    return;
                }

                if (!ServerStarter.installed(MainActivity.this)) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            booting = false;
                            onOffline();
                            showTermuxMissing();
                        }
                    });
                    return;
                }

                if (!Prefs.autostart(MainActivity.this)) {
                    done(false);
                    return;
                }

                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        setStatus("Preparazione ambiente…", false);
                    }
                });

                if (!Bootstrap.run(MainActivity.this)) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            booting = false;
                            onOffline();
                            String why = Bootstrap.lastError == null
                                    ? "motivo sconosciuto" : Bootstrap.lastError;
                            showSetupHint("Termux ha rifiutato il comando.\n\n"
                                    + "Causa: " + why + "\n\n"
                                    + "Verifica in Termux: allow-external-apps = true in "
                                    + "~/.termux/termux.properties, poi termux-reload-settings.\n"
                                    + "Verifica anche il permesso RUN_COMMAND nelle "
                                    + "impostazioni dell'app.");
                        }
                    });
                    return;
                }

                // Primo avvio: proot + Debian + download ~200 MB. Fino a 30 minuti.
                long start = System.currentTimeMillis();
                for (int i = 0; i < 600; i++) {
                    sleep(3000);
                    if (api.healthy()) {
                        done(true);
                        return;
                    }
                    final long secs = (System.currentTimeMillis() - start) / 1000L;
                    if (i % 5 == 0) {
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                setStatus("Installazione… " + (secs / 60) + "m " + (secs % 60) + "s",
                                        false);
                            }
                        });
                    }
                    // dopo 5 minuti senza segnali: suggerimento (una sola volta)
                    if (i == 100) {
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                showSetupHint("Nessuna risposta dopo 5 minuti.\n\n"
                                        + "Se è il primo avvio il download può essere lento. "
                                        + "Altrimenti verifica in Termux:\n"
                                        + "• allow-external-apps = true\n"
                                        + "• log: ~/.cyberstrike-setup.log");
                            }
                        });
                    }
                }
                done(false);
            }

            private void done(final boolean online) {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        booting = false;
                        if (online) onOnline();
                        else onOffline();
                    }
                });
            }
        });
    }

    private void showTermuxMissing() {
        new AlertDialog.Builder(this)
                .setTitle("Termux richiesto")
                .setMessage("CyberStrike gira dentro Termux.\n\n"
                        + "1. Installa Termux da F-Droid\n"
                        + "2. Apri Termux una volta e abilita l'esecuzione da altre app:\n"
                        + "   ~/.termux/termux.properties → allow-external-apps = true\n"
                        + "   poi esegui: termux-reload-settings\n\n"
                        + "3. Torna qui e tocca lo stato per riprovare.\n\n"
                        + "In alternativa puoi usare un server CyberStrike remoto: "
                        + "⋮ → Impostazioni → Server URL.")
                .setPositiveButton("F-Droid", (d, w) ->
                        openUrl("https://f-droid.org/packages/com.termux/"))
                .setNeutralButton("Apri Termux", (d, w) -> Bootstrap.openTermux(this))
                .setNegativeButton("Chiudi", null)
                .show();
    }

    private void showDiagnostics() {
        new AlertDialog.Builder(this)
                .setTitle("Diagnostica")
                .setMessage(Diag.dump())
                .setPositiveButton("Copia", (d, w) -> {
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(
                            android.content.ClipData.newPlainText("CyberStrike", Diag.dump()));
                    Ui.toast(this, "Log copiato");
                })
                .setNeutralButton("Pulisci", (d, w) -> {
                    Diag.clear();
                    Ui.toast(this, "Log pulito");
                })
                .setNegativeButton("Chiudi", null)
                .show();
    }

    private void showSetupHint(String msg) {
        new AlertDialog.Builder(this)
                .setTitle("Setup CyberStrike")
                .setMessage(msg)
                .setPositiveButton("Apri Termux", (d, w) -> Bootstrap.openTermux(this))
                .setNegativeButton("OK", null)
                .show();
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)));
        } catch (Exception e) {
            Ui.toast(this, "Nessun browser disponibile");
        }
    }

    private void onOnline() {
        online = true;
        findViewById(R.id.fab).setAlpha(1f);
        String v = api.version();
        setStatus(getString(R.string.online)
                + (v.isEmpty() ? "" : "  ·  v" + v)
                + "  ·  " + hostOf(Prefs.base(this)), true);
        refresh();
        startEvents();
        ensureModel();
    }

    /** Se non c'è un modello salvato, ne sceglie uno raggiungibile. */
    private void ensureModel() {
        if (!Prefs.model(this).isEmpty()) return;
        io.execute(new Runnable() {
            @Override
            public void run() {
                final String[] p = api.pickModel(Prefs.providerDefaults(MainActivity.this));
                if (p == null) return;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        Prefs.setModel(MainActivity.this, p[0], p[1]);
                        Diag.add("model", "default " + p[0] + " / " + p[1]);
                    }
                });
            }
        });
    }

    private void onOffline() {
        online = false;
        findViewById(R.id.fab).setAlpha(0.35f);
        setStatus(getString(R.string.offline) + "  ·  " + hostOf(Prefs.base(this)), false);
        Ui.toast(this, "Server non raggiungibile. Tocca lo stato per riprovare, "
                + "oppure usa ⋮ → Installa / avvia.");
    }

    /** host:porta dell'URL configurato, per l'header. */
    private static String hostOf(String base) {
        try {
            android.net.Uri u = android.net.Uri.parse(base);
            String a = u.getAuthority();
            return a == null ? base : a;
        } catch (Exception e) {
            return base;
        }
    }

    private void setStatus(String text, boolean online) {
        Diag.add("boot", text);
        status.setText(text);
        dot.setBackgroundResource(online ? R.drawable.dot_online : R.drawable.dot_offline);
    }

    // ---------------------------------------------------------------- sessioni

    private void refresh() {
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONArray arr = api.sessions("/");
                    final List<JSONObject> items = new ArrayList<>();
                    for (int i = 0; i < arr.length(); i++) items.add(arr.getJSONObject(i));
                    Collections.sort(items, new Comparator<JSONObject>() {
                        @Override
                        public int compare(JSONObject a, JSONObject b) {
                            return Long.compare(updated(b), updated(a));
                        }
                    });
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            adapter.set(items);
                        }
                    });
                } catch (Exception e) {
                    // offline: lo stato in testa lo dice già
                }
            }
        });
    }

    private static long updated(JSONObject s) {
        JSONObject t = s.optJSONObject("time");
        return t == null ? 0 : t.optLong("updated", 0);
    }

    private void newSession() {
        if (!online) {
            Ui.toast(this, "Server non connesso — attendi l'avvio o tocca lo stato");
            return;
        }
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final JSONObject s = api.createSession("", "/", Prefs.allowAll(MainActivity.this));
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            open(s);
                        }
                    });
                } catch (Exception e) {
                    final String msg = e.getMessage();
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            Ui.toast(MainActivity.this, "Errore: " + msg);
                        }
                    });
                }
            }
        });
    }

    private void open(JSONObject s) {
        Intent i = new Intent(this, ChatActivity.class);
        i.putExtra("id", s.optString("id"));
        i.putExtra("title", s.optString("title"));
        i.putExtra("directory", s.optString("directory", "/"));
        startActivity(i);
    }

    private void confirmDelete(final JSONObject s) {
        final String title = displayTitle(s);
        new AlertDialog.Builder(this)
                .setTitle("Eliminare la sessione?")
                .setMessage(title)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    io.execute(() -> {
                        try {
                            api.deleteSession(s.optString("id"), s.optString("directory", "/"));
                        } catch (Exception ignored) {
                        }
                        ui.post(MainActivity.this::refresh);
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private static String displayTitle(JSONObject s) {
        String t = s.optString("title", "");
        if (t.isEmpty()) t = s.optString("slug", "Sessione");
        return t;
    }

    // ------------------------------------------------------------------ setup

    private void setupServer() {
        if (!ServerStarter.installed(this)) {
            showTermuxMissing();
            return;
        }
        if (api.healthy()) {
            Ui.toast(this, "Server già attivo");
            return;
        }
        Prefs.setAutostart(this, true);
        boot();
    }

    // ------------------------------------------------------------------ menu

    private void showMenu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, "Nuova sessione");
        m.getMenu().add(0, 2, 1, "Terminale");
        m.getMenu().add(0, 3, 2, "Aggiorna");
        m.getMenu().add(0, 4, 3, "Installa / avvia server");
        m.getMenu().add(0, 5, 4, "Apri Termux");
        m.getMenu().add(0, 6, 5, "Impostazioni");
        m.getMenu().add(0, 7, 6, "Diagnostica");
        m.getMenu().add(0, 8, 7, "Richiedi permesso Termux");
        m.getMenu().add(0, 9, 8, "Modelli e provider");
        m.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    newSession();
                    return true;
                case 2:
                    startActivity(new Intent(this, TerminalActivity.class));
                    return true;
                case 3:
                    refresh();
                    return true;
                case 4:
                    setupServer();
                    return true;
                case 5:
                    Bootstrap.openTermux(this);
                    return true;
                case 6:
                    showSettings();
                    return true;
                case 7:
                    showDiagnostics();
                    return true;
                case 8:
                    Prefs.setPermAsked(this, false);
                    boot();
                    return true;
                case 9:
                    startActivity(new Intent(this, ModelsActivity.class));
                    return true;
            }
            return false;
        });
        m.show();
    }

    // ------------------------------------------------------------ impostazioni

    private void showSettings() {
        io.execute(new Runnable() {
            @Override
            public void run() {
                final List<String> agents = new ArrayList<>();
                final List<String> labels = new ArrayList<>();
                final List<String[]> pairs = new ArrayList<>();

                try {
                    JSONArray ag = api.agents();
                    for (int i = 0; i < ag.length(); i++) {
                        JSONObject a = ag.getJSONObject(i);
                        if (a.optBoolean("hidden", false)) continue;
                        if ("subagent".equals(a.optString("mode", ""))) continue;
                        agents.add(a.optString("name"));
                    }
                } catch (Exception ignored) {
                }

                try {
                    JSONArray arr = api.providers().optJSONArray("providers");
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject p = arr.getJSONObject(i);
                            String pid = p.optString("id");
                            String pname = p.optString("name", pid);
                            JSONObject models = p.optJSONObject("models");
                            if (models == null) continue;
                            Iterator<String> it = models.keys();
                            while (it.hasNext()) {
                                String mid = it.next();
                                JSONObject mo = models.optJSONObject(mid);
                                String mname = mo == null ? mid : mo.optString("name", mid);
                                labels.add(pname + " · " + mname);
                                pairs.add(new String[]{pid, mid});
                            }
                        }
                    }
                } catch (Exception ignored) {
                }

                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        buildSettings(agents, labels, pairs);
                    }
                });
            }
        });
    }

    private void buildSettings(List<String> agents, List<String> labels, List<String[]> pairs) {
        View v = getLayoutInflater().inflate(R.layout.dlg_settings, null);
        EditText url = (EditText) v.findViewById(R.id.url);
        EditText token = (EditText) v.findViewById(R.id.token);
        Spinner agent = (Spinner) v.findViewById(R.id.agent);
        Spinner model = (Spinner) v.findViewById(R.id.model);

        url.setText(Prefs.base(this));
        token.setText(Prefs.token(this));

        if (!agents.isEmpty()) {
            agent.setAdapter(new ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_dropdown_item, agents));
            int idx = agents.indexOf(Prefs.agent(this));
            agent.setSelection(Math.max(0, idx));
        }

        if (!labels.isEmpty()) {
            model.setAdapter(new ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_dropdown_item, labels));
            String curP = Prefs.provider(this), curM = Prefs.model(this);
            for (int i = 0; i < pairs.size(); i++) {
                if (pairs.get(i)[0].equals(curP) && pairs.get(i)[1].equals(curM)) {
                    model.setSelection(i);
                    break;
                }
            }
        }

        LinearLayout root = (LinearLayout) ((ViewGroup) v).getChildAt(0);

        final CheckBox allowAll = new CheckBox(this);
        allowAll.setText("Permetti tutti i tool (allow-all)");
        allowAll.setTextColor(0xFFD9F7E6);
        allowAll.setChecked(Prefs.allowAll(this));
        root.addView(allowAll);

        final CheckBox wvDebug = new CheckBox(this);
        wvDebug.setText("Debug WebView terminale (chrome://inspect)");
        wvDebug.setTextColor(0xFFD9F7E6);
        wvDebug.setChecked(Prefs.webviewDebug(this));
        root.addView(wvDebug);

        final CheckBox auto = new CheckBox(this);
        auto.setText("Avvia il server da Termux all'apertura");
        auto.setTextColor(0xFFD9F7E6);
        auto.setChecked(Prefs.autostart(this));
        root.addView(auto);

        new AlertDialog.Builder(this)
                .setTitle("Impostazioni")
                .setView(v)
                .setPositiveButton("Salva", (d, w) -> {
                    String u = url.getText().toString().trim();
                    if (u.isEmpty()) u = "http://127.0.0.1:27182";
                    Prefs.setBase(this, u);
                    Prefs.setToken(this, token.getText().toString().trim());
                    Prefs.setAutostart(this, auto.isChecked());
                    Prefs.setAllowAll(this, allowAll.isChecked());
                    Prefs.setWebviewDebug(this, wvDebug.isChecked());
                    if (!agents.isEmpty()) {
                        Prefs.setAgent(this, agents.get(agent.getSelectedItemPosition()));
                    }
                    if (!pairs.isEmpty()) {
                        String[] p = pairs.get(model.getSelectedItemPosition());
                        Prefs.setModel(this, p[0], p[1]);
                    }
                    api = new CsClient(Prefs.base(this), Prefs.token(this));
                    stopEvents();
                    boot();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ------------------------------------------------------------------ eventi

    private void startEvents() {
        if (events != null) return;
        events = new EventStream(Prefs.base(this), Prefs.token(this), "/",
                new EventStream.Listener() {
                    @Override
                    public void onEvent(String type, JSONObject properties) {
                        if (type.startsWith("session.") || type.equals("server.connected")) {
                            refresh();
                        }
                    }

                    @Override
                    public void onConnection(boolean connected) {
                        if (connected) {
                            setStatus(getString(R.string.online), true);
                        }
                    }
                });
        events.start();
    }

    private void stopEvents() {
        if (events != null) {
            events.stop();
            events = null;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    // ----------------------------------------------------------------- adapter

    private class SessionAdapter extends BaseAdapter {
        final List<JSONObject> items = new ArrayList<>();

        void set(List<JSONObject> l) {
            items.clear();
            items.addAll(l);
            notifyDataSetChanged();
            empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int i) {
            return items.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int pos, View cv, ViewGroup parent) {
            if (cv == null) {
                cv = getLayoutInflater().inflate(R.layout.item_session, parent, false);
            }
            JSONObject o = items.get(pos);
            TextView t = (TextView) cv.findViewById(R.id.title);
            TextView s = (TextView) cv.findViewById(R.id.sub);
            t.setText(displayTitle(o));
            s.setText(Ui.ago(updated(o)) + "  ·  " + o.optString("directory", "/"));
            return cv;
        }
    }
}
