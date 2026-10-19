package com.cyberstrike.client;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.view.Gravity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;

import com.cyberstrike.client.api.CsClient;
import com.cyberstrike.client.api.EventStream;
import com.cyberstrike.client.ui.ModelTreePicker;
import com.cyberstrike.client.ui.MsgAdapter;
import com.cyberstrike.client.util.Diag;
import com.cyberstrike.client.util.Prefs;
import com.cyberstrike.client.util.Ui;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
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
    private TextView scrollBottom;

    private LinearLayout chips;
    private View chipsBar;
    /** allegati in attesa: {mime, filename, dataURL} */
    private final List<String[]> pendingAttach = new ArrayList<>();

    private static final int REQ_IMAGE = 4001;
    private static final int REQ_FILE = 4002;
    private static final int MAX_ATTACH = 12 * 1024 * 1024;
    private static final int MAX_IMAGE = 1600;

    private volatile boolean running;
    private volatile boolean loadedOnce;
    private boolean uiPending;

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

        scrollBottom = (TextView) findViewById(R.id.scroll_bottom);
        scrollBottom.setOnClickListener(v -> {
            if (adapter.getCount() > 0) {
                list.smoothScrollToPosition(adapter.getCount() - 1);
            }
            scrollBottom.setVisibility(View.GONE);
        });
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
            }

            @Override
            public void onScroll(AbsListView view, int first, int visible, int total) {
                updateScrollButton();
            }
        });

        chips = (LinearLayout) findViewById(R.id.chips);
        chipsBar = findViewById(R.id.chips_bar);

        findViewById(R.id.back).setOnClickListener(v -> finish());
        findViewById(R.id.send).setOnClickListener(v -> send());
        findViewById(R.id.emoji).setOnClickListener(v -> showEmojiPicker());
        findViewById(R.id.pick_image).setOnClickListener(v -> pickMedia(true));
        findViewById(R.id.pick_file).setOnClickListener(v -> pickMedia(false));
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
        loadQuestions();
    }

    /**
     * Recupera le domande già pendenti sul server (GET /question) e le mostra.
     * Necessario perché il server non riemette "question.asked" via SSE quando
     * l'app si collega: senza questo polling una domanda creata prima della
     * connessione andrebbe persa.
     */
    private void loadQuestions() {
        io.execute(new Runnable() {
            @Override
            public void run() {
                final JSONArray arr;
                try {
                    arr = api.questions(directory);
                } catch (Exception e) {
                    return;
                }
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (arr == null) return;
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject q = arr.optJSONObject(i);
                            if (q == null) continue;
                            String sid = q.optString("sessionID", "");
                            if (!sid.isEmpty() && !sid.equals(sessionID)) continue;
                            String id = q.optString("id", "");
                            if (id.isEmpty()) continue;
                            if (askedQuestions.add(id)) {
                                askQuestion(q, 0, new JSONArray());
                            }
                        }
                    }
                });
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
                if (!r.text.isEmpty()) rows.add(r);

                for (int j = 0; parts != null && j < parts.length(); j++) {
                    JSONObject p = parts.optJSONObject(j);
                    if (p != null && "file".equals(p.optString("type"))) {
                        rows.add(fileRow(p));
                    }
                }
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
        return "✖ " + name + (msg.isEmpty() ? "" : "\n" + hint(msg));
    }

    private static String hint(String msg) {
        if (msg != null && msg.contains("Unable to connect")) {
            return msg + "\n\nSuggerimento: provider non raggiungibile. "
                    + "Scegli un altro modello con \u22ee \u2192 Modello.";
        }
        return msg;
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
            String first = "question".equals(tool) ? questionSummary(st) : firstLine(st);
            r.text = icon + " " + tool + "  ·  " + status
                    + (first.isEmpty() ? "" : "\n$ " + first);
            r.detail = toolDetail(p);
            return r;
        }
        if ("file".equals(type)) {
            return fileRow(p);
        }
        return null;
    }

    private static MsgAdapter.Row fileRow(JSONObject p) {
        String mime = p.optString("mime", "");
        MsgAdapter.Row r = new MsgAdapter.Row();
        r.id = p.optString("id");
        r.mime = mime;
        r.name = p.optString("filename", "file");
        r.url = p.optString("url", "");
        r.kind = mime.startsWith("image/") ? MsgAdapter.IMAGE : MsgAdapter.FILE;
        r.text = r.name;
        return r;
    }

    /** Riepilogo leggibile del tool question, al posto del JSON grezzo. */
    private static String questionSummary(JSONObject st) {
        if (st == null) return "";
        JSONObject in = st.optJSONObject("input");
        if (in == null) return "";
        JSONArray qs = in.optJSONArray("questions");
        if (qs == null || qs.length() == 0) return "";
        JSONObject q = qs.optJSONObject(0);
        if (q == null) return "";
        String h = q.optString("header", "");
        String qq = q.optString("question", "");
        if (h.isEmpty()) return qq;
        return qq.isEmpty() ? h : h + " — " + qq;
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

    // ------------------------------------------------------------- allegati

    private void pickMedia(boolean image) {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(image ? "image/*" : "*/*");
        try {
            startActivityForResult(Intent.createChooser(i,
                    image ? "Scegli immagine" : "Scegli file"), image ? REQ_IMAGE : REQ_FILE);
        } catch (Exception e) {
            Ui.toast(this, "Nessuna app disponibile per scegliere i file");
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if ((request != REQ_IMAGE && request != REQ_FILE) || result != RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        final Uri uri = data.getData();
        Ui.toast(this, "Preparo l'allegato…");
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final String[] att = readAttachment(uri);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            addChip(att);
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

    /** Legge un URI scelto dall'utente e lo converte in {mime, nome, dataURL}. */
    private String[] readAttachment(Uri uri) throws Exception {
        ContentResolver cr = getContentResolver();
        String mime = cr.getType(uri);
        if (mime == null || mime.isEmpty()) mime = "application/octet-stream";
        String name = displayName(cr, uri);
        if (name == null || name.isEmpty()) name = "file";

        if (mime.startsWith("image/")) {
            byte[] jpeg = compressImage(cr, uri);
            if (jpeg != null) {
                if (jpeg.length > MAX_ATTACH) {
                    throw new Exception("Immagine troppo grande (max 12 MB)");
                }
                int dot = name.lastIndexOf('.');
                if (dot > 0) name = name.substring(0, dot);
                name = name + ".jpg";
                return new String[]{"image/jpeg", name, dataUrl("image/jpeg", jpeg)};
            }
        }

        byte[] raw = readAll(cr, uri, MAX_ATTACH);
        return new String[]{mime, name, dataUrl(mime, raw)};
    }

    private static String displayName(ContentResolver cr, Uri uri) {
        Cursor c = null;
        try {
            c = cr.query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) {
                    String v = c.getString(i);
                    if (v != null && !v.isEmpty()) return v;
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        String p = uri.getLastPathSegment();
        return p == null ? "file" : p;
    }

    private static byte[] readAll(ContentResolver cr, Uri uri, int max) throws Exception {
        InputStream in = cr.openInputStream(uri);
        if (in == null) throw new Exception("Impossibile leggere il file");
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n, total = 0;
        try {
            while ((n = in.read(buf)) >= 0) {
                total += n;
                if (total > max) throw new Exception("File troppo grande (max 12 MB)");
                bos.write(buf, 0, n);
            }
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
            }
        }
        return bos.toByteArray();
    }

    /** Ridimensiona a max 1600px e comprime in JPEG (qualità 88). */
    private static byte[] compressImage(ContentResolver cr, Uri uri) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            InputStream in = cr.openInputStream(uri);
            if (in == null) return null;
            BitmapFactory.decodeStream(in, null, bounds);
            in.close();
            if (bounds.outWidth <= 0) return null;

            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= MAX_IMAGE) sample *= 2;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            InputStream in2 = cr.openInputStream(uri);
            Bitmap bmp = BitmapFactory.decodeStream(in2, null, opts);
            if (in2 != null) in2.close();
            if (bmp == null) return null;

            int w = bmp.getWidth(), h = bmp.getHeight();
            int max = Math.max(w, h);
            if (max > MAX_IMAGE) {
                float scale = (float) MAX_IMAGE / max;
                Bitmap scaled = Bitmap.createScaledBitmap(bmp,
                        Math.max(1, Math.round(w * scale)),
                        Math.max(1, Math.round(h * scale)), true);
                if (scaled != bmp) bmp.recycle();
                bmp = scaled;
            }

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, 88, bos);
            bmp.recycle();
            return bos.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String dataUrl(String mime, byte[] bytes) {
        return "data:" + mime + ";base64," + Base64.encodeToString(bytes, Base64.NO_WRAP);
    }

    private void addChip(final String[] att) {
        pendingAttach.add(att);

        final LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setBackgroundResource(R.drawable.bg_tool);
        int p = Ui.dp(this, 9);
        chip.setPadding(p, Ui.dp(this, 4), p, Ui.dp(this, 4));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, Ui.dp(this, 6), 0);
        chip.setLayoutParams(lp);

        TextView name = new TextView(this);
        name.setText(att[1]);
        name.setTextSize(12f);
        name.setTextColor(0xFFD9F7E6);
        name.setMaxWidth(Ui.dp(this, 150));
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);

        TextView del = new TextView(this);
        del.setText("✕");
        del.setTextSize(13f);
        del.setTextColor(0xFF6E8F7C);
        del.setGravity(Gravity.CENTER);
        del.setPadding(Ui.dp(this, 8), 0, 0, 0);
        del.setOnClickListener(v -> {
            pendingAttach.remove(att);
            chips.removeView(chip);
            updateChips();
        });

        chip.addView(name);
        chip.addView(del);
        chips.addView(chip);
        updateChips();
    }

    private void updateChips() {
        if (chipsBar != null) {
            chipsBar.setVisibility(chips.getChildCount() == 0 ? View.GONE : View.VISIBLE);
        }
    }

    private void clearAttachments() {
        pendingAttach.clear();
        if (chips != null) chips.removeAllViews();
        updateChips();
    }

    // ---------------------------------------------------------------- emoji

    private static final String[] EMOJI = {
            "😀", "😁", "😂", "🤣", "😊", "😍", "😎", "🤩", "😅", "😉", "🙂", "😐",
            "🤔", "😴", "😇", "🥳", "😤", "😡", "🤬", "😈", "👿", "💀", "☠️", "👻",
            "🤖", "💩", "🙈", "🙉", "🙊", "👀", "🧠", "🫡", "👍", "👎", "👌", "✌️",
            "🤞", "🤟", "🖖", "👊", "✊", "🤝", "🙏", "💪", "🫶", "👏", "🖐️", "✋",
            "❤️", "🧡", "💚", "💙", "💜", "🖤", "💔", "💯", "🔥", "⚡", "✨", "💥",
            "💫", "🎯", "🚀", "🛡️", "💻", "🖥️", "⌨️", "🖱️", "📱", "🔌", "💾", "📀",
            "🧲", "🔧", "🔨", "⚙️", "🧰", "🔍", "🔎", "🗝️", "🔒", "🔓", "🔐", "⚔️",
            "🗡️", "🏴‍☠️", "🏴", "🚩", "🎌", "🏁", "🧨", "💣", "☢️", "☣️", "⚠️", "✅",
            "❌", "✔️", "✖️", "❗", "❓", "‼️", "⁉️", "🔴", "🟠", "🟡", "🟢", "🔵",
            "🟣", "⚫", "⚪", "🌐", "🕵️", "👨‍💻", "👩‍💻", "🧑‍💻", "🥷", "🦾", "🦿", "🐍",
            "🦅", "🐺", "🦊", "🐉", "🦈", "🕷️", "🦂", "☕", "🍕", "🍺", "🍻", "🥃",
            "🍷", "🎮", "🎧", "🎵", "🎶", "📡", "📶", "🛰️", "🌍", "🌚", "🌙", "🤯"
    };

    private void showEmojiPicker() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 8);
        root.setPadding(pad, pad, pad, pad);

        LinearLayout row = null;
        for (int i = 0; i < EMOJI.length; i++) {
            if (i % 8 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                root.addView(row);
            }
            final String em = EMOJI[i];
            TextView e = new TextView(this);
            e.setText(em);
            e.setTextSize(22f);
            e.setGravity(Gravity.CENTER);
            e.setLayoutParams(new LinearLayout.LayoutParams(0, Ui.dp(this, 42), 1f));
            e.setOnClickListener(v -> insertEmoji(em));
            if (row != null) row.addView(e);
        }

        ScrollView sc = new ScrollView(this);
        sc.addView(root);
        new AlertDialog.Builder(this)
                .setTitle("Emoji")
                .setView(sc)
                .setNegativeButton("Chiudi", null)
                .show();
    }

    private void insertEmoji(String em) {
        int start = Math.max(0, input.getSelectionStart());
        int end = Math.max(start, input.getSelectionEnd());
        android.text.Editable ed = input.getText();
        if (start > ed.length()) start = ed.length();
        if (end > ed.length()) end = ed.length();
        ed.replace(start, end, em);
    }

    // ---------------------------------------------------------------- azioni

    private void send() {
        final String text = input.getText().toString().trim();
        final List<String[]> atts = new ArrayList<>(pendingAttach);
        if ((text.isEmpty() && atts.isEmpty()) || running) return;

        input.setText("");
        clearAttachments();

        MsgAdapter.Row r = new MsgAdapter.Row();
        r.kind = MsgAdapter.USER;
        r.id = "tmp_" + System.currentTimeMillis();
        r.text = text;
        if (!text.isEmpty()) adapter.add(r);

        for (String[] a : atts) {
            MsgAdapter.Row ar = new MsgAdapter.Row();
            ar.kind = a[0].startsWith("image/") ? MsgAdapter.IMAGE : MsgAdapter.FILE;
            ar.name = a[1];
            ar.mime = a[0];
            ar.url = a[2];
            adapter.add(ar);
        }
        scrollToEnd();

        setRunning(true);
        hideKeyboard();
        Diag.add("chat", "send " + text.length() + " char");

        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String provider = Prefs.provider(ChatActivity.this);
                    String model = Prefs.model(ChatActivity.this);
                    if (provider.isEmpty() || model.isEmpty()) {
                        String[] picked = api.pickModel(Prefs.providerDefaults(ChatActivity.this));
                        if (picked != null) {
                            provider = picked[0];
                            model = picked[1];
                            final String fp = picked[0], fm = picked[1];
                            ui.post(new Runnable() {
                                @Override
                                public void run() {
                                    Prefs.setModel(ChatActivity.this, fp, fm);
                                }
                            });
                            Diag.add("model", "auto " + provider + " / " + model);
                        }
                    }
                    api.sendMessage(sessionID, text, Prefs.agent(ChatActivity.this),
                            provider, model, directory, atts);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            setRunning(false);
                            Diag.add("chat", "risposta completata");
                            load();
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            setRunning(false);
                            Diag.add("chat", "errore: " + e.getMessage());
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

    /** Aggiorna la lista al massimo ~15 volte/secondo durante lo streaming. */
    private void scheduleUi() {
        if (uiPending) return;
        uiPending = true;
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                uiPending = false;
                adapter.notifyDataSetChanged();
                scrollToEndIfNear();
                updateScrollButton();
            }
        }, 60);
    }

    private void scrollToEndIfNear() {
        int count = adapter.getCount();
        if (count == 0) return;
        // segui lo stream solo se l'utente è in fondo
        if (list.getLastVisiblePosition() >= count - 4) {
            list.setSelection(count - 1);
        }
    }

    private void scrollToEnd() {
        list.post(new Runnable() {
            @Override
            public void run() {
                if (adapter.getCount() > 0) {
                    list.setSelection(adapter.getCount() - 1);
                }
                updateScrollButton();
            }
        });
    }

    /** Mostra il pulsante "torna in fondo" solo quando la lista non è in fondo. */
    private void updateScrollButton() {
        if (scrollBottom == null) return;
        int total = adapter.getCount();
        if (total == 0) {
            scrollBottom.setVisibility(View.GONE);
            return;
        }
        int last = list.getLastVisiblePosition();
        boolean atBottom = last >= total - 1;
        scrollBottom.setVisibility(atBottom ? View.GONE : View.VISIBLE);
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
                scheduleUi();
            } else {
                adapter.add(r);
            }
            if (!"tool".equals(part.optString("type"))) scrollToEnd();

        } else if ("message.part.delta".equals(type)) {
            // Il server trasmette il testo incrementale solo con questo evento:
            // l'updated completo arriva alla fine.
            String partID = props.optString("partID", "");
            String field = props.optString("field", "");
            String delta = props.optString("delta", "");
            if (partID.isEmpty() || delta.isEmpty()) return;
            if (!"text".equals(field)) return;

            MsgAdapter.Row row = adapter.find(partID);
            if (row == null) {
                row = new MsgAdapter.Row();
                row.id = partID;
                row.kind = MsgAdapter.TEXT;
                row.text = delta;
                adapter.add(row);
            } else {
                row.text = row.text + delta;
                scheduleUi();
            }
            scrollToEndIfNear();

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
            if (askedQuestions.add(props.optString("id"))) {
                askQuestion(props, 0, new JSONArray());
            }
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

    /** Domande già mostrate: il server può ripetere l'evento, la finestra no. */
    private final java.util.Set<String> askedQuestions = new java.util.HashSet<>();

    private void askQuestion(final JSONObject req, final int index, final JSONArray answers) {
        JSONArray qs = req.optJSONArray("questions");
        if (qs == null || index >= qs.length()) {
            sendAnswers(req, answers);
            return;
        }

        JSONObject q = qs.optJSONObject(index);
        if (q == null) {
            askQuestion(req, index + 1, answers);
            return;
        }

        final String header = q.optString("header", "Domanda");
        String question = q.optString("question", "");
        JSONArray opts = q.optJSONArray("options");
        final boolean multi = q.optBoolean("multiple", false);
        final boolean custom = q.optBoolean("custom", true);

        final List<String> labels = new ArrayList<>();
        final List<String> descs = new ArrayList<>();
        if (opts != null) {
            for (int i = 0; i < opts.length(); i++) {
                JSONObject o = opts.optJSONObject(i);
                if (o == null) continue;
                labels.add(o.optString("label", ""));
                descs.add(o.optString("description", ""));
            }
        }

        ScrollView sv = new ScrollView(this);
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(4), dp(18), dp(4));

        if (!question.isEmpty()) {
            TextView qt = new TextView(this);
            qt.setText(question);
            qt.setTextColor(0xFFD9F7E6);
            qt.setTextSize(14f);
            qt.setLineSpacing(dp(2), 1f);
            qt.setPadding(0, 0, 0, dp(12));
            box.addView(qt);
        }

        final AlertDialog[] holder = new AlertDialog[1];
        final boolean[] checked = new boolean[labels.size()];

        for (int i = 0; i < labels.size(); i++) {
            final int idx = i;
            LinearLayout row = optionRow(labels.get(i), descs.get(i), multi, i + 1);
            if (multi) {
                final CheckBox cb = (CheckBox) row.getTag();
                cb.setOnCheckedChangeListener((v, isChecked) -> checked[idx] = isChecked);
                row.setOnClickListener(v -> cb.setChecked(!cb.isChecked()));
            } else {
                row.setOnClickListener(v -> {
                    JSONArray one = new JSONArray();
                    one.put(labels.get(idx));
                    answers.put(one);
                    if (holder[0] != null) holder[0].dismiss();
                    askQuestion(req, index + 1, answers);
                });
            }
            box.addView(row);
        }

        if (custom) {
            LinearLayout row = optionRow("Altra risposta…", "scrivi tu", false, 0);
            row.setOnClickListener(v -> {
                if (holder[0] != null) holder[0].dismiss();
                customAnswer(req, index, answers, header);
            });
            box.addView(row);
        }

        sv.addView(box);

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(header)
                .setView(sv)
                .setNegativeButton("Rifiuta", (d, w) -> rejectQuestion(req));

        if (multi) {
            b.setPositiveButton("Invia", (d, w) -> {
                JSONArray one = new JSONArray();
                for (int i = 0; i < checked.length; i++) {
                    if (checked[i]) one.put(labels.get(i));
                }
                answers.put(one);
                askQuestion(req, index + 1, answers);
            });
        }

        holder[0] = b.create();
        holder[0].show();
    }

    /** Riga selezionabile della finestra domanda: numero (o casella) + testo + nota. */
    private LinearLayout optionRow(String label, String desc, boolean checkable, int num) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackgroundResource(R.drawable.bg_card);
        row.setPadding(dp(12), dp(9), dp(12), dp(9));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        if (checkable) {
            CheckBox cb = new CheckBox(this);
            cb.setPadding(0, 0, 0, 0);
            head.addView(cb);
            row.setTag(cb);
        } else {
            TextView n = new TextView(this);
            n.setText(num > 0 ? String.valueOf(num) : "✎");
            n.setTextColor(0xFF00E676);
            n.setTypeface(Typeface.DEFAULT_BOLD);
            n.setTextSize(num > 0 ? 13f : 15f);
            n.setMinWidth(dp(24));
            head.addView(n);
        }

        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(0xFFD9F7E6);
        l.setTextSize(14f);
        l.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(l);
        row.addView(head);

        if (desc != null && !desc.isEmpty()) {
            TextView d = new TextView(this);
            d.setText(desc);
            d.setTextColor(0xFF7FA98F);
            d.setTextSize(12f);
            d.setPadding(dp(24), dp(3), 0, 0);
            row.addView(d);
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        row.setLayoutParams(lp);
        return row;
    }

    private void sendAnswers(final JSONObject req, final JSONArray answers) {
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
    }

    private void customAnswer(JSONObject req, int index, JSONArray answers, String header) {
        final EditText et = new EditText(this);
        et.setTextColor(0xFFD9F7E6);
        et.setHint("Scrivi la risposta…");
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(dp(18), dp(4), dp(18), dp(4));
        wrap.addView(et);
        new AlertDialog.Builder(this)
                .setTitle(header)
                .setView(wrap)
                .setPositiveButton("OK", (d, w) -> {
                    JSONArray one = new JSONArray();
                    one.put(et.getText().toString());
                    answers.put(one);
                    askQuestion(req, index + 1, answers);
                })
                .setNegativeButton("Annulla", null)
                .show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
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
                JSONArray arr = null;
                try {
                    arr = api.providers().optJSONArray("providers");
                } catch (Exception ignored) {
                }
                final JSONArray fps = arr;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        ModelTreePicker.show(ChatActivity.this, fps,
                                Prefs.provider(ChatActivity.this),
                                Prefs.model(ChatActivity.this),
                                new ModelTreePicker.Listener() {
                                    @Override
                                    public void onPick(String providerID, String modelID) {
                                        Prefs.setModel(ChatActivity.this, providerID, modelID);
                                        Ui.toast(ChatActivity.this, "Modello aggiornato");
                                    }
                                });
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
