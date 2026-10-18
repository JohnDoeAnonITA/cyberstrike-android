package com.cyberstrike.client;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;

import com.cyberstrike.client.api.CsClient;
import com.cyberstrike.client.api.EventStream;
import com.cyberstrike.client.ui.MsgAdapter;
import com.cyberstrike.client.util.Prefs;
import com.cyberstrike.client.util.Ui;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ChatActivity extends Activity {

    private final ExecutorService io = Executors.newCachedThreadPool();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private CsClient api;
    private EventStream events;
    private MsgAdapter adapter;

    private String sessionID;
    private String directory = "/";
    private String title = "";

    private ListView list;
    private EditText input;
    private TextView stop;
    private TextView sub;

    private volatile boolean running;
    private volatile boolean loadedOnce;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        sessionID = getIntent().getStringExtra("id");
        directory = getIntent().getStringExtra("directory");
        if (directory == null || directory.isEmpty()) directory = "/";
        title = getIntent().getStringExtra("title");
        if (title == null) title = "";

        api = new CsClient(Prefs.base(this), Prefs.token(this));

        ((TextView) findViewById(R.id.title)).setText(title.isEmpty() ? "Sessione" : title);
        sub = (TextView) findViewById(R.id.sub);
        list = (ListView) findViewById(R.id.list);
        input = (EditText) findViewById(R.id.input);
        stop = (TextView) findViewById(R.id.stop);

        adapter = new MsgAdapter(this, new MsgAdapter.OnToolClick() {
            @Override
            public void onTool(MsgAdapter.Row row) {
                new AlertDialog.Builder(ChatActivity.this)
                        .setTitle("Dettaglio tool")
                        .setMessage(row.detail.isEmpty() ? row.text : row.detail)
                        .setPositiveButton("Chiudi", null)
                        .show();
            }
        });
        list.setAdapter(adapter);

        findViewById(R.id.back).setOnClickListener(v -> finish());
        findViewById(R.id.send).setOnClickListener(v -> send());
        stop.setOnClickListener(v -> abort());
        findViewById(R.id.overflow).setOnClickListener(this::showMenu);

        setRunning(false);
        load();
        startEvents();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (events != null) events.stop();
    }

    // ------------------------------------------------------------ caricamento

    private void load() {
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONArray arr = api.messages(sessionID, directory, 200);
                    final List<MsgAdapter.Row> rows = buildRows(arr);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            adapter.set(rows);
                            loadedOnce = true;
                            scrollToEnd();
                            sub.setText(adapter.getCount() + " elementi");
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            Ui.toast(ChatActivity.this, e.getMessage());
                        }
                    });
                }
            }
        });
    }

    private List<MsgAdapter.Row> buildRows(JSONArray arr) {
        List<MsgAdapter.Row> rows = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject m = arr.optJSONObject(i);
            if (m == null) continue;
            JSONObject info = m.optJSONObject("info");
            JSONArray parts = m.optJSONArray("parts");
            String role = info == null ? "" : info.optString("role", "");

            if ("user".equals(role)) {
                StringBuilder sb = new StringBuilder();
                for (int j = 0; parts != null && j < parts.length(); j++) {
                    JSONObject p = parts.optJSONObject(j);
                    if (p != null && "text".equals(p.optString("type"))) {
                        if (sb.length() > 0) sb.append('\n');
                        sb.append(p.optString("text", ""));
                    }
                }
                MsgAdapter.Row r = new MsgAdapter.Row();
                r.kind = MsgAdapter.USER;
                r.id = info.optString("id");
                r.text = sb.toString();
                rows.add(r);
                continue;
            }

            JSONObject err = info == null ? null : info.optJSONObject("error");
            if (err != null) {
                MsgAdapter.Row r = new MsgAdapter.Row();
                r.kind = MsgAdapter.ERROR;
                r.id = "err_" + i;
                r.text = errorText(err);
                rows.add(r);
            }

            for (int j = 0; parts != null && j < parts.length(); j++) {
                JSONObject p = parts.optJSONObject(j);
                if (p == null) continue;
                MsgAdapter.Row r = rowForPart(p);
                if (r != null) rows.add(r);
            }
        }
        return rows;
    }

    private static String errorText(JSONObject err) {
        String name = err.optString("name", "Errore");
        JSONObject data = err.optJSONObject("data");
        String msg = data == null ? "" : data.optString("message", "");
        return "✖ " + name + (msg.isEmpty() ? "" : "\n" + msg);
    }

    private MsgAdapter.Row rowForPart(JSONObject p) {
        String type = p.optString("type", "");
        MsgAdapter.Row r = new MsgAdapter.Row();
        r.id = p.optString("id");

        if ("text".equals(type)) {
            r.kind = MsgAdapter.TEXT;
            r.text = p.optString("text", "");
            return r.text.isEmpty() ? null : r;
        }
        if ("reasoning".equals(type)) {
            r.kind = MsgAdapter.REASONING;
            r.text = p.optString("text", "");
            return r.text.isEmpty() ? null : r;
        }
        if ("tool".equals(type)) {
            r.kind = MsgAdapter.TOOL;
            String tool = p.optString("tool", "tool");
            JSONObject st = p.optJSONObject("state");
            String status = st == null ? "" : st.optString("status", "");
            String icon = "running".equals(status) ? "⏳"
                    : "completed".equals(status) ? "✔"
                    : "error".equals(status) ? "✖" : "•";
            String first = firstLine(st);
            r.text = icon + " " + tool + "  ·  " + status
                    + (first.isEmpty() ? "" : "\n$ " + Ui.ellipsize(first, 160));
            r.detail = toolDetail(p);
            return r;
        }
        if ("file".equals(type)) {
            r.kind = MsgAdapter.INFO;
            r.text = "📎 " + p.optString("filename", p.optString("mime", "file"));
            return r;
        }
        return null;
    }

    private static String firstLine(JSONObject st) {
        if (st == null) return "";
        JSONObject in = st.optJSONObject("input");
        if (in == null) return "";
        String cmd = in.optString("command", "");
        if (!cmd.isEmpty()) return cmd;
        String path = in.optString("filePath", in.optString("path", ""));
        if (!path.isEmpty()) return path;
        String desc = in.optString("description", "");
        if (!desc.isEmpty()) return desc;
        return in.toString();
    }

    private static String toolDetail(JSONObject p) {
        StringBuilder d = new StringBuilder();
        JSONObject st = p.optJSONObject("state");
        if (st == null) return d.toString();
        JSONObject in = st.optJSONObject("input");
        if (in != null) {
            d.append("INPUT\n").append(pretty(in)).append("\n\n");
        }
        String out = st.optString("output", "");
        if (!out.isEmpty()) {
            if (out.length() > 20000) out = out.substring(0, 20000) + "\n…(troncato)";
            d.append("OUTPUT\n").append(out).append("\n\n");
        }
        String err = st.optString("error", "");
        if (!err.isEmpty()) d.append("ERROR\n").append(err);
        return d.toString().trim();
    }

    private static String pretty(JSONObject o) {
        try {
            return o.toString(2);
        } catch (Exception e) {
            return o.toString();
        }
    }

    // ---------------------------------------------------------------- azioni

    private void send() {
        final String text = input.getText().toString().trim();
        if (text.isEmpty() || running) return;

        input.setText("");
        MsgAdapter.Row r = new MsgAdapter.Row();
        r.kind = MsgAdapter.USER;
        r.id = "tmp_" + System.currentTimeMillis();
        r.text = text;
        adapter.add(r);
        scrollToEnd();

        setRunning(true);
        hideKeyboard();

        final String agent = Prefs.agent(this);
        final String provider = Prefs.provider(this);
        final String model = Prefs.model(this);

        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    api.sendMessage(sessionID, text, agent, provider, model, directory);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            setRunning(false);
                            load();
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            setRunning(false);
                            MsgAdapter.Row err = new MsgAdapter.Row();
                            err.kind = MsgAdapter.ERROR;
                            err.text = "✖ " + e.getMessage();
                            adapter.add(err);
                            scrollToEnd();
                        }
                    });
                }
            }
        });
    }

    private void abort() {
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    api.abort(sessionID, directory);
                } catch (Exception ignored) {
                }
            }
        });
    }

    private void setRunning(boolean v) {
        running = v;
        stop.setVisibility(v ? View.VISIBLE : View.GONE);
    }

    private void hideKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        } catch (Exception ignored) {
        }
    }

    private void scrollToEnd() {
        list.post(new Runnable() {
            @Override
            public void run() {
                if (adapter.getCount() > 0) {
                    list.setSelection(adapter.getCount() - 1);
                }
            }
        });
    }

    // ----------------------------------------------------------------- eventi

    private void startEvents() {
        if (events != null) return;
        events = new EventStream(Prefs.base(this), Prefs.token(this), directory,
                new EventStream.Listener() {
                    @Override
                    public void onEvent(String type, JSONObject props) {
                        handle(type, props);
                    }

                    @Override
                    public void onConnection(boolean connected) {
                        if (connected && loadedOnce) load();
                    }
                });
        events.start();
    }

    private void handle(String type, JSONObject props) {
        String sid = props.optString("sessionID", "");
        if (!sid.isEmpty() && !sid.equals(sessionID)) return;

        if ("message.part.updated".equals(type)) {
            JSONObject part = props.optJSONObject("part");
            if (part == null) return;
            if (!sessionID.equals(part.optString("sessionID"))) return;

            String id = part.optString("id");
            MsgAdapter.Row r = rowForPart(part);
            if (r == null) return;

            MsgAdapter.Row existing = adapter.find(id);
            if (existing != null) {
                existing.text = r.text;
                existing.detail = r.detail;
                existing.kind = r.kind;
                adapter.notifyDataSetChanged();
            } else {
                adapter.add(r);
            }
            if (!"tool".equals(part.optString("type"))) scrollToEnd();

        } else if ("session.idle".equals(type)) {
            setRunning(false);

        } else if ("session.error".equals(type)) {
            JSONObject err = props.optJSONObject("error");
            if (err != null) {
                MsgAdapter.Row row = new MsgAdapter.Row();
                row.kind = MsgAdapter.ERROR;
                row.text = errorText(err);
                adapter.add(row);
                scrollToEnd();
            }

        } else if ("permission.asked".equals(type)) {
            askPermission(props);

        } else if ("question.asked".equals(type)) {
            askQuestion(props, 0, new JSONArray());
        }
    }

    private void askPermission(JSONObject req) {
        final String id = req.optString("id");
        String perm = req.optString("permission", "permesso");
        JSONArray patterns = req.optJSONArray("patterns");
        StringBuilder msg = new StringBuilder();
        if (patterns != null) {
            for (int i = 0; i < patterns.length(); i++) {
                if (msg.length() > 0) msg.append('\n');
                msg.append("• ").append(patterns.optString(i));
            }
        }
        if (msg.length() == 0) msg.append("Richiesta di autorizzazione");

        new AlertDialog.Builder(this)
                .setTitle("Permesso: " + perm)
                .setMessage(msg.toString())
                .setPositiveButton("Consenti", (d, w) -> replyPermission(id, "once"))
                .setNeutralButton("Sempre", (d, w) -> replyPermission(id, "always"))
                .setNegativeButton("Nega", (d, w) -> replyPermission(id, "reject"))
                .show();
    }

    private void replyPermission(final String id, final String reply) {
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    api.replyPermission(id, reply, directory);
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            Ui.toast(ChatActivity.this, e.getMessage());
                        }
                    });
                }
            }
        });
    }

    private void askQuestion(final JSONObject req, final int index, final JSONArray answers) {
        JSONArray qs = req.optJSONArray("questions");
        if (qs == null || index >= qs.length()) {
            io.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        api.replyQuestion(req.optString("id"), answers, directory);
                    } catch (final Exception e) {
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                Ui.toast(ChatActivity.this, e.getMessage());
                            }
                        });
                    }
                }
            });
            return;
        }

        JSONObject q = qs.optJSONObject(index);
        String header = q.optString("header", "Domanda");
        String question = q.optString("question", "");
        JSONArray opts = q.optJSONArray("options");
        final boolean multi = q.optBoolean("multiple", false);
        final boolean custom = q.optBoolean("custom", true);

        final List<String> labels = new ArrayList<>();
        if (opts != null) {
            for (int i = 0; i < opts.length(); i++) {
                JSONObject o = opts.optJSONObject(i);
                if (o != null) labels.add(o.optString("label"));
            }
        }

        if (multi) {
            final boolean[] checked = new boolean[labels.size()];
            new AlertDialog.Builder(this)
                    .setTitle(header)
                    .setMessage(question)
                    .setMultiChoiceItems(labels.toArray(new String[0]), checked,
                            (d, which, isChecked) -> checked[which] = isChecked)
                    .setPositiveButton("OK", (d, w) -> {
                        JSONArray one = new JSONArray();
                        for (int i = 0; i < checked.length; i++) {
                            if (checked[i]) one.put(labels.get(i));
                        }
                        answers.put(one);
                        askQuestion(req, index + 1, answers);
                    })
                    .setNegativeButton("Reject", (d, w) -> rejectQuestion(req))
                    .show();
            return;
        }

        final String[] items = labels.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle(header)
                .setMessage(question)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        JSONArray one = new JSONArray();
                        one.put(labels.get(which));
                        answers.put(one);
                        askQuestion(req, index + 1, answers);
                    }
                })
                .setPositiveButton(custom ? "Altro…" : null, (d, w) -> customAnswer(req, index, answers))
                .setNegativeButton("Reject", (d, w) -> rejectQuestion(req))
                .show();
    }

    private void customAnswer(JSONObject req, int index, JSONArray answers) {
        final EditText et = new EditText(this);
        et.setTextColor(0xFFE6EAF0);
        et.setHint("Risposta");
        new AlertDialog.Builder(this)
                .setTitle("Risposta")
                .setView(et)
                .setPositiveButton("OK", (d, w) -> {
                    JSONArray one = new JSONArray();
                    one.put(et.getText().toString());
                    answers.put(one);
                    askQuestion(req, index + 1, answers);
                })
                .setNegativeButton("Annulla", null)
                .show();
    }

    private void rejectQuestion(final JSONObject req) {
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    api.request("POST", "/question/" + req.optString("id") + "/reject"
                                    + CsClient.dir(directory), "{}", 15000);
                } catch (Exception ignored) {
                }
            }
        });
    }

    // ------------------------------------------------------------------- menu

    private void showMenu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, "Modello");
        m.getMenu().add(0, 2, 1, "Agente");
        m.getMenu().add(0, 3, 2, "Terminale");
        m.getMenu().add(0, 4, 3, "Aggiorna");
        m.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    pickModel();
                    return true;
                case 2:
                    pickAgent();
                    return true;
                case 3:
                    startActivity(new Intent(this, TerminalActivity.class));
                    return true;
                case 4:
                    load();
                    return true;
            }
            return false;
        });
        m.show();
    }

    private void pickModel() {
        io.execute(new Runnable() {
            @Override
            public void run() {
                final List<String> labels = new ArrayList<>();
                final List<String[]> pairs = new ArrayList<>();
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
                                labels.add(pname + " · " + (mo == null ? mid : mo.optString("name", mid)));
                                pairs.add(new String[]{pid, mid});
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        new AlertDialog.Builder(ChatActivity.this)
                                .setTitle("Modello")
                                .setItems(labels.toArray(new String[0]), (d, w) -> {
                                    String[] p = pairs.get(w);
                                    Prefs.setModel(ChatActivity.this, p[0], p[1]);
                                    Ui.toast(ChatActivity.this, "Modello aggiornato");
                                })
                                .show();
                    }
                });
            }
        });
    }

    private void pickAgent() {
        io.execute(new Runnable() {
            @Override
            public void run() {
                final List<String> names = new ArrayList<>();
                try {
                    JSONArray arr = api.agents();
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject a = arr.getJSONObject(i);
                        if (a.optBoolean("hidden", false)) continue;
                        if ("subagent".equals(a.optString("mode", ""))) continue;
                        names.add(a.optString("name"));
                    }
                } catch (Exception ignored) {
                }
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        new AlertDialog.Builder(ChatActivity.this)
                                .setTitle("Agente")
                                .setItems(names.toArray(new String[0]), (d, w) -> {
                                    Prefs.setAgent(ChatActivity.this, names.get(w));
                                    Ui.toast(ChatActivity.this, "Agente: " + names.get(w));
                                })
                                .show();
                    }
                });
            }
        });
    }
}
