package com.cyberstrike.client;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import com.cyberstrike.client.api.CsClient;
import com.cyberstrike.client.util.Bootstrap;
import com.cyberstrike.client.util.Diag;
import com.cyberstrike.client.util.ModelList;
import com.cyberstrike.client.util.Prefs;
import com.cyberstrike.client.util.Ui;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Gestione provider e modelli:
 *  - scegli provider/modello attivo
 *  - aggiungi provider (con API key e primo modello)
 *  - aggiungi modello a un provider
 *  - disattiva / riattiva provider
 *  - elimina provider o modello dal server (modifica /cyberstrike.json + restart)
 *  - imposta il modello predefinito di un provider (usato dall'auto-selezione)
 */
public class ModelsActivity extends Activity {

    private static final int K_HEADER = 0, K_PROVIDER = 1, K_MODEL = 2, K_DISABLED = 3, K_HIDDEN = 4;

    private final ExecutorService io = Executors.newCachedThreadPool();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private CsClient api;
    private RowAdapter adapter;
    private TextView sub;

    private final Set<String> expanded = new HashSet<>();
    private final List<String[]> activeProviders = new ArrayList<>(); // {id, name}
    private JSONArray disabled = new JSONArray();
    private String pendingProvider;
    private String pendingModel;
    private final java.util.Map<String, Set<String>> modelIndex = new java.util.HashMap<>();
    private final java.util.Map<String, String> providerBase = new java.util.HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_models);

        api = new CsClient(Prefs.base(this), Prefs.token(this));
        sub = (TextView) findViewById(R.id.sub);
        ListView list = (ListView) findViewById(R.id.list);

        adapter = new RowAdapter();
        list.setAdapter(adapter);

        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                onRow(adapter.items.get(pos));
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) {
                onRowLong(adapter.items.get(pos));
                return true;
            }
        });

        findViewById(R.id.back).setOnClickListener(v -> finish());
        findViewById(R.id.overflow).setOnClickListener(this::menu);

        load();
    }

    // ------------------------------------------------------------- caricamento

    private void load() {
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final JSONObject prov = api.providers();
                    final JSONObject cfg = api.configObj();
                    final JSONArray dis = cfg.optJSONArray("disabled_providers");
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            build(prov, dis == null ? new JSONArray() : dis);
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            Ui.toast(ModelsActivity.this, e.getMessage());
                        }
                    });
                }
            }
        });
    }

    private void build(JSONObject prov, JSONArray dis) {
        disabled = dis;
        activeProviders.clear();
        modelIndex.clear();
        providerBase.clear();

        final List<Row> rows = new ArrayList<>();
        final String ap = Prefs.provider(this);
        final String am = Prefs.model(this);

        Row head = new Row();
        head.kind = K_HEADER;
        head.title = am.isEmpty()
                ? "Nessun modello selezionato"
                : "Attivo: " + ap + " · " + am;
        rows.add(head);

        JSONArray arr = prov.optJSONArray("providers");
        int total = 0;
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject p = arr.optJSONObject(i);
                if (p == null) continue;
                String id = p.optString("id");
                String name = p.optString("name", id);
                JSONObject opt = p.optJSONObject("options");
                String base = opt == null ? "" : opt.optString("baseURL", "");
                JSONObject models = p.optJSONObject("models");
                if (models == null || id.isEmpty()) continue;

                activeProviders.add(new String[]{id, name});
                total++;
                Set<String> mids = new HashSet<>();
                Iterator<String> all = models.keys();
                while (all.hasNext()) mids.add(all.next());
                modelIndex.put(id, mids);
                providerBase.put(id, base);

                Row pr = new Row();
                pr.kind = K_PROVIDER;
                pr.providerID = id;
                pr.title = (expanded.contains(id) ? "▾  " : "▸  ") + name;
                pr.sub = (base.isEmpty() ? "nessun baseURL" : base)
                        + "  ·  " + models.length() + " modelli";
                rows.add(pr);

                if (!expanded.contains(id)) continue;

                int hiddenCount = 0;
                Iterator<String> it = models.keys();
                while (it.hasNext()) {
                    String mid = it.next();
                    if (Prefs.isModelHidden(this, id, mid)) {
                        hiddenCount++;
                        continue;
                    }
                    boolean active = id.equals(ap) && mid.equals(am);
                    String def = Prefs.providerDefault(this, id);

                    Row mr = new Row();
                    mr.kind = K_MODEL;
                    mr.providerID = id;
                    mr.modelID = mid;
                    mr.title = (active ? "●  " : "○  ") + mid
                            + (mid.equals(def) ? "   ★" : "");
                    mr.sub = active ? "in uso" : (mid.equals(def) ? "predefinito di " + name : "");
                    rows.add(mr);
                }
                if (hiddenCount > 0) {
                    Row hr = new Row();
                    hr.kind = K_HIDDEN;
                    hr.providerID = id;
                    hr.title = hiddenCount + " modelli nascosti — tocca per mostrarli";
                    rows.add(hr);
                }
            }
        }

        if (dis.length() > 0) {
            Row h = new Row();
            h.kind = K_HEADER;
            h.title = "Disattivati";
            rows.add(h);
            for (int i = 0; i < dis.length(); i++) {
                Row r = new Row();
                r.kind = K_DISABLED;
                r.providerID = dis.optString(i);
                r.title = "◌  " + r.providerID;
                r.sub = "tocca per riattivare";
                rows.add(r);
            }
        }

        sub.setText(total + " provider attivi  ·  " + dis.length() + " disattivati");
        adapter.set(rows);
        reportPendingDelete();
    }

    // ---------------------------------------------------------------- azioni

    /** Dopo un delete: dice se è sparito o se è definito nella config base. */
    private void reportPendingDelete() {
        if (pendingProvider == null) return;
        String pid = pendingProvider, mid = pendingModel;
        pendingProvider = null;
        pendingModel = null;

        if (mid != null) {
            Set<String> set = modelIndex.get(pid);
            if (set != null && set.contains(mid)) {
                Ui.toast(this, "Modello ancora presente: è nella config base "
                        + "(~/.config/cyberstrike). Usa Nascondi.");
            } else {
                Ui.toast(this, "Modello eliminato");
            }
        } else {
            if (modelIndex.containsKey(pid)) {
                Ui.toast(this, "Provider ancora presente: è nella config base "
                        + "(~/.config/cyberstrike). Usa Disattiva.");
            } else {
                Ui.toast(this, "Provider eliminato");
            }
        }
    }

    private void onRow(Row r) {
        switch (r.kind) {
            case K_PROVIDER:
                if (expanded.contains(r.providerID)) expanded.remove(r.providerID);
                else expanded.add(r.providerID);
                load();
                break;

            case K_MODEL:
                Prefs.setModel(this, r.providerID, r.modelID);
                // se il provider non ha un predefinito, lo imposta al primo scelto
                if (Prefs.providerDefault(this, r.providerID).isEmpty()) {
                    Prefs.setProviderDefault(this, r.providerID, r.modelID);
                }
                Ui.toast(this, "Attivo: " + r.providerID + " · " + r.modelID);
                load();
                break;

            case K_DISABLED:
                reactivate(r.providerID);
                break;

            case K_HIDDEN:
                Prefs.unhideAll(this, r.providerID);
                load();
                break;
        }
    }

    private void onRowLong(Row r) {
        if (r.kind == K_PROVIDER) {
            new AlertDialog.Builder(this)
                    .setTitle(r.providerID)
                    .setItems(new String[]{"Disattiva provider", "Elimina dal server"}, (d, w) -> {
                        if (w == 0) disable(r.providerID);
                        else confirmDeleteProvider(r.providerID);
                    })
                    .show();

        } else if (r.kind == K_MODEL) {
            new AlertDialog.Builder(this)
                    .setTitle(r.providerID + " · " + r.modelID)
                    .setItems(new String[]{
                            "Imposta come predefinito del provider",
                            "Nascondi dal selettore",
                            "Elimina dal server"}, (d, w) -> {
                        if (w == 0) {
                            Prefs.setProviderDefault(this, r.providerID, r.modelID);
                            Ui.toast(this, "Predefinito aggiornato");
                            load();
                        } else if (w == 1) {
                            Prefs.hideModel(this, r.providerID, r.modelID);
                            load();
                        } else {
                            confirmDeleteModel(r.providerID, r.modelID);
                        }
                    })
                    .show();
        }
    }

    private void disable(final String providerID) {
        final JSONArray next = new JSONArray();
        for (int i = 0; i < disabled.length(); i++) next.put(disabled.optString(i));
        next.put(providerID);
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    api.setDisabledProviders(next);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            Ui.toast(ModelsActivity.this, "Provider disattivato");
                            load();
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            Ui.toast(ModelsActivity.this, e.getMessage());
                        }
                    });
                }
            }
        });
    }

    private void reactivate(final String providerID) {
        final JSONArray next = new JSONArray();
        for (int i = 0; i < disabled.length(); i++) {
            String id = disabled.optString(i);
            if (!id.equals(providerID)) next.put(id);
        }
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    api.setDisabledProviders(next);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            Ui.toast(ModelsActivity.this, "Provider riattivato");
                            load();
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            Ui.toast(ModelsActivity.this, e.getMessage());
                        }
                    });
                }
            }
        });
    }

    private void confirmDeleteProvider(final String providerID) {
        new AlertDialog.Builder(this)
                .setTitle("Eliminare " + providerID + "?")
                .setMessage("Verrà rimosso da /cyberstrike.json e il server dell'app "
                        + "verrà riavviato. L'istanza principale (4096) non viene toccata.")
                .setPositiveButton("Elimina", (d, w) -> {
                    pendingProvider = providerID;
                    pendingModel = null;
                    deleteOnServer("d.get('provider',{}).pop('" + providerID + "',None)");
                })
                .setNegativeButton("Annulla", null)
                .show();
    }

    private void confirmDeleteModel(final String providerID, final String modelID) {
        new AlertDialog.Builder(this)
                .setTitle("Eliminare " + modelID + "?")
                .setMessage("Verrà rimosso dal provider " + providerID
                        + " e il server dell'app verrà riavviato.")
                .setPositiveButton("Elimina", (d, w) -> {
                    pendingProvider = providerID;
                    pendingModel = modelID;
                    deleteOnServer("d.get('provider',{}).get('" + providerID
                            + "',{}).get('models',{}).pop('" + modelID + "',None)");
                })
                .setNegativeButton("Annulla", null)
                .show();
    }

    /** Modifica /cyberstrike.json via Termux e riavvia solo il server dell'app. */
    private void deleteOnServer(String pyExpr) {
        String prelude = "export CS_RESTART=1\n"
                + "proot-distro login debian -- python3 -c \"import json;p='/cyberstrike.json';"
                + "d=json.load(open(p));" + pyExpr + ";json.dump(d,open(p,'w'),indent=2)\"";
        Diag.add("models", "delete: " + pyExpr);

        if (!Bootstrap.run(this, prelude)) {
            Ui.toast(this, "Termux non disponibile");
            return;
        }
        Ui.toast(this, "Riavvio del server…");
        io.execute(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < 40; i++) {
                    sleep(1500);
                    if (api.healthy()) break;
                }
                api = new CsClient(Prefs.base(ModelsActivity.this),
                        Prefs.token(ModelsActivity.this));
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        Ui.toast(ModelsActivity.this, "Fatto");
                        load();
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------------ menu

    private void menu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, "Nuovo provider");
        m.getMenu().add(0, 2, 1, "Nuovo modello");
        m.getMenu().add(0, 3, 2, "Aggiorna");
        m.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    addProviderDialog();
                    return true;
                case 2:
                    addModelDialog();
                    return true;
                case 3:
                    load();
                    return true;
            }
            return false;
        });
        m.show();
    }

    private interface Pick {
        void on(String[] model);
    }

    private void fetchModels(final Button btn, final String base, final String key,
                             final Pick cb) {
        final String label = btn.getText().toString();
        btn.setEnabled(false);
        btn.setText("Carico…");
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<String[]> models = ModelList.fetch(base, key);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            btn.setEnabled(true);
                            btn.setText(label);
                            showModelPicker(models, cb);
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            btn.setEnabled(true);
                            btn.setText(label);
                            Ui.toast(ModelsActivity.this, e.getMessage());
                        }
                    });
                }
            }
        });
    }

    private void showModelPicker(List<String[]> models, final Pick cb) {
        String[] labels = new String[models.size()];
        for (int i = 0; i < models.size(); i++) {
            String id = models.get(i)[0];
            String name = models.get(i)[1];
            labels[i] = name.equals(id) ? id : (name + "\n" + id);
        }
        new AlertDialog.Builder(this)
                .setTitle("Modelli disponibili (" + models.size() + ")")
                .setItems(labels, (d, w) -> cb.on(models.get(w)))
                .setNegativeButton("Annulla", null)
                .show();
    }

    private EditText field(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextColor(0xFFD9F7E6);
        e.setHintTextColor(0xFF3F5A4A);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        return e;
    }

    private void addProviderDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 20);
        box.setPadding(p, p, p, p);

        final EditText id = field("id (es. openrouter)");
        final EditText name = field("nome (es. OpenRouter)");
        final EditText url = field("baseURL (https://…/v1)");
        final EditText key = field("API key (opzionale)");
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        final EditText mid = field("id modello (es. anthropic/claude-sonnet-4)");
        final EditText mname = field("nome modello");

        box.addView(id);
        box.addView(name);
        box.addView(url);
        box.addView(key);
        box.addView(mid);
        box.addView(mname);

        final Button load = new Button(this);
        load.setText("Carica modelli da baseURL");
        box.addView(load);
        load.setOnClickListener(v -> {
            String b = url.getText().toString().trim();
            String k = key.getText().toString().trim();
            if (!b.startsWith("http")) {
                Ui.toast(this, "Inserisci prima il baseURL");
                return;
            }
            fetchModels(load, b, k, picked -> {
                mid.setText(picked[0]);
                mname.setText(picked[1]);
            });
        });

        ScrollView sc = new ScrollView(this);
        sc.addView(box);

        new AlertDialog.Builder(this)
                .setTitle("Nuovo provider")
                .setView(sc)
                .setPositiveButton("Aggiungi", (d, w) -> {
                    String pid = id.getText().toString().trim();
                    String pname = name.getText().toString().trim();
                    String base = url.getText().toString().trim();
                    String apiKey = key.getText().toString().trim();
                    String modelID = mid.getText().toString().trim();
                    String modelName = mname.getText().toString().trim();

                    if (!pid.matches("[A-Za-z0-9._-]{1,40}")) {
                        Ui.toast(this, "id non valido (lettere, numeri, . _ -)");
                        return;
                    }
                    if (!base.startsWith("http")) {
                        Ui.toast(this, "baseURL deve iniziare con http");
                        return;
                    }
                    if (modelID.isEmpty()) {
                        Ui.toast(this, "serve almeno un modello");
                        return;
                    }
                    if (pname.isEmpty()) pname = pid;
                    if (modelName.isEmpty()) modelName = modelID;

                    final String fpid = pid, fpname = pname, fbase = base, fkey = apiKey;
                    final String fmid = modelID, fmname = modelName;

                    io.execute(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                api.addProvider(fpid, fpname, fbase, fmid, fmname);
                                if (!fkey.isEmpty()) api.setAuth(fpid, fkey);
                                Diag.add("models", "provider aggiunto " + fpid);
                                ui.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        expanded.add(fpid);
                                        Ui.toast(ModelsActivity.this, "Provider aggiunto");
                                        load();
                                    }
                                });
                            } catch (final Exception e) {
                                ui.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        Ui.toast(ModelsActivity.this, e.getMessage());
                                    }
                                });
                            }
                        }
                    });
                })
                .setNegativeButton("Annulla", null)
                .show();
    }

    private void addModelDialog() {
        if (activeProviders.isEmpty()) {
            Ui.toast(this, "Nessun provider attivo");
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 20);
        box.setPadding(pad, pad, pad, pad);

        final Spinner sp = new Spinner(this);
        List<String> names = new ArrayList<>();
        for (String[] p : activeProviders) names.add(p[1] + " (" + p[0] + ")");
        sp.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));

        final EditText mid = field("id modello (manuale)");
        final EditText mname = field("nome modello");
        final EditText key = field("API key (solo per caricare la lista)");
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        box.addView(sp);
        box.addView(mid);
        box.addView(mname);
        box.addView(key);

        final Button load = new Button(this);
        load.setText("Carica modelli dal provider");
        box.addView(load);
        load.setOnClickListener(v -> {
            String pid = activeProviders.get(sp.getSelectedItemPosition())[0];
            String base = providerBase.get(pid);
            if (base == null || base.isEmpty()) {
                Ui.toast(this, "Questo provider non ha un baseURL");
                return;
            }
            fetchModels(load, base, key.getText().toString().trim(), picked -> {
                mid.setText(picked[0]);
                mname.setText(picked[1]);
            });
        });

        new AlertDialog.Builder(this)
                .setTitle("Nuovo modello")
                .setView(box)
                .setPositiveButton("Aggiungi", (d, w) -> {
                    String pid = activeProviders.get(sp.getSelectedItemPosition())[0];
                    String id = mid.getText().toString().trim();
                    String name = mname.getText().toString().trim();
                    if (id.isEmpty()) {
                        Ui.toast(this, "id modello obbligatorio");
                        return;
                    }
                    if (name.isEmpty()) name = id;

                    final String fpid = pid, fid = id, fname = name;
                    io.execute(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                api.addModel(fpid, fid, fname);
                                Diag.add("models", "modello aggiunto " + fpid + "/" + fid);
                                ui.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        expanded.add(fpid);
                                        Ui.toast(ModelsActivity.this, "Modello aggiunto");
                                        load();
                                    }
                                });
                            } catch (final Exception e) {
                                ui.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        Ui.toast(ModelsActivity.this, e.getMessage());
                                    }
                                });
                            }
                        }
                    });
                })
                .setNegativeButton("Annulla", null)
                .show();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    // ------------------------------------------------------------------ righe

    private static class Row {
        int kind;
        String title = "";
        String sub = "";
        String providerID = "";
        String modelID = "";
    }

    private class RowAdapter extends BaseAdapter {
        final List<Row> items = new ArrayList<>();

        void set(List<Row> r) {
            items.clear();
            items.addAll(r);
            notifyDataSetChanged();
        }

        public int getCount() {
            return items.size();
        }

        public Object getItem(int i) {
            return items.get(i);
        }

        public long getItemId(int i) {
            return i;
        }

        public View getView(int pos, View cv, ViewGroup parent) {
            if (cv == null) {
                cv = getLayoutInflater().inflate(R.layout.item_model_row, parent, false);
            }
            Row r = items.get(pos);
            TextView t = (TextView) cv.findViewById(R.id.title);
            TextView s = (TextView) cv.findViewById(R.id.sub);

            t.setText(r.title);
            s.setText(r.sub);
            s.setVisibility(r.sub.isEmpty() ? View.GONE : View.VISIBLE);

            cv.setPadding(Ui.dp(ModelsActivity.this, 4), Ui.dp(ModelsActivity.this, 6),
                    Ui.dp(ModelsActivity.this, 4), Ui.dp(ModelsActivity.this, 6));

            switch (r.kind) {
                case K_HEADER:
                    t.setTextSize(12f);
                    t.setTextColor(0xFF6E8F7C);
                    s.setVisibility(View.GONE);
                    break;
                case K_PROVIDER:
                    t.setTextSize(15f);
                    t.setTextColor(0xFFD9F7E6);
                    t.setTypeface(null, android.graphics.Typeface.BOLD);
                    break;
                case K_MODEL:
                    cv.setPadding(Ui.dp(ModelsActivity.this, 20), Ui.dp(ModelsActivity.this, 5),
                            Ui.dp(ModelsActivity.this, 4), Ui.dp(ModelsActivity.this, 5));
                    t.setTextSize(14f);
                    t.setTextColor(0xFFD9F7E6);
                    t.setTypeface(null, android.graphics.Typeface.NORMAL);
                    break;
                case K_DISABLED:
                    t.setTextSize(14f);
                    t.setTextColor(0xFF3F5A4A);
                    break;
                default:
                    t.setTextSize(12f);
                    t.setTextColor(0xFF3F5A4A);
                    s.setVisibility(View.GONE);
                    break;
            }
            return cv;
        }
    }
}
