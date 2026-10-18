package com.cyberstrike.client.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.cyberstrike.client.util.Ui;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Selettore modello ad albero: provider espandibili (▸/▾) con i modelli annidati,
 * campo di ricerca e segno di spunta sul modello in uso.
 */
public final class ModelTreePicker {

    public interface Listener {
        void onPick(String providerID, String modelID);
    }

    private static final int TYPE_PROVIDER = 0;
    private static final int TYPE_MODEL = 1;
    private static final int TYPE_EMPTY = 2;

    private static final int C_BG = 0xFF0A0F0C;
    private static final int C_HEAD = 0xFF0E1A13;
    private static final int C_GREEN = 0xFF00E676;
    private static final int C_TEXT = 0xFFD9F7E6;
    private static final int C_DIM = 0xFF6E8F7C;

    private ModelTreePicker() {
    }

    // ------------------------------------------------------------- modelli dati

    private static class Model {
        String id;
        String name;
    }

    private static class Provider {
        String id;
        String name;
        final List<Model> models = new ArrayList<>();
    }

    private static class Row {
        int type;
        Provider provider;
        Model model;
        String label;
        boolean current;
    }

    // ------------------------------------------------------------------- entry

    public static void show(final Context c, JSONArray providers,
                            final String currentProvider, final String currentModel,
                            final Listener listener) {
        final List<Provider> ps = parse(providers);
        if (ps.isEmpty()) {
            Ui.toast(c, "Nessun modello disponibile");
            return;
        }

        final Set<String> expanded = new HashSet<>();
        if (currentProvider != null && !currentProvider.isEmpty()) expanded.add(currentProvider);
        if (ps.size() <= 3) for (Provider p : ps) expanded.add(p.id);

        final LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(c, 12);
        box.setPadding(pad, pad, pad, 0);
        box.setBackgroundColor(C_BG);

        final EditText search = new EditText(c);
        search.setHint("Cerca provider o modello");
        search.setSingleLine(true);
        search.setTextSize(15f);
        search.setTextColor(C_TEXT);
        search.setHintTextColor(0xFF3F5A4A);
        box.addView(search);

        final ListView list = new ListView(c);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setBackgroundColor(C_BG);
        int height = (int) (c.getResources().getDisplayMetrics().heightPixels * 0.62f);
        box.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, height));

        final AlertDialog[] holder = new AlertDialog[1];
        final RowAdapter adapter = new RowAdapter(c,
                buildRows(ps, expanded, "", currentProvider, currentModel));
        list.setAdapter(adapter);

        list.setOnItemClickListener((parent, view, position, id) -> {
            Row r = (Row) adapter.getItem(position);
            if (r.type == TYPE_PROVIDER) {
                if (!expanded.remove(r.provider.id)) expanded.add(r.provider.id);
                adapter.setRows(buildRows(ps, expanded, search.getText().toString(),
                        currentProvider, currentModel));
            } else if (r.type == TYPE_MODEL) {
                if (holder[0] != null) holder[0].dismiss();
                listener.onPick(r.provider.id, r.model.id);
            }
        });

        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int d) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int d) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                adapter.setRows(buildRows(ps, expanded, s.toString(),
                        currentProvider, currentModel));
            }
        });

        holder[0] = new AlertDialog.Builder(c)
                .setTitle("Modello")
                .setView(box)
                .setNegativeButton("Annulla", null)
                .create();
        holder[0].show();
        if (holder[0].getWindow() != null) {
            holder[0].getWindow().setBackgroundDrawable(new ColorDrawable(C_BG));
        }
    }

    // --------------------------------------------------------------- parsing

    private static List<Provider> parse(JSONArray arr) {
        List<Provider> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject p = arr.optJSONObject(i);
            if (p == null) continue;
            Provider pr = new Provider();
            pr.id = p.optString("id", "");
            if (pr.id.isEmpty()) continue;
            pr.name = p.optString("name", pr.id);
            if (pr.name.isEmpty()) pr.name = pr.id;

            JSONObject models = p.optJSONObject("models");
            if (models != null) {
                Iterator<String> it = models.keys();
                while (it.hasNext()) {
                    String mid = it.next();
                    JSONObject mo = models.optJSONObject(mid);
                    Model m = new Model();
                    m.id = mid;
                    m.name = mo == null ? mid : mo.optString("name", mid);
                    if (m.name.isEmpty()) m.name = mid;
                    pr.models.add(m);
                }
            }
            if (pr.models.isEmpty()) continue;
            Collections.sort(pr.models, new Comparator<Model>() {
                @Override
                public int compare(Model a, Model b) {
                    int r = a.name.compareToIgnoreCase(b.name);
                    return r != 0 ? r : a.id.compareToIgnoreCase(b.id);
                }
            });
            out.add(pr);
        }
        Collections.sort(out, new Comparator<Provider>() {
            @Override
            public int compare(Provider a, Provider b) {
                int r = a.name.compareToIgnoreCase(b.name);
                return r != 0 ? r : a.id.compareToIgnoreCase(b.id);
            }
        });
        return out;
    }

    // ------------------------------------------------------------ righe visibili

    private static List<Row> buildRows(List<Provider> ps, Set<String> expanded, String query,
                                       String currentProvider, String currentModel) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<Row> rows = new ArrayList<>();
        for (Provider p : ps) {
            boolean pMatch = !q.isEmpty()
                    && (p.name.toLowerCase(Locale.ROOT).contains(q)
                    || p.id.toLowerCase(Locale.ROOT).contains(q));

            List<Row> models = new ArrayList<>();
            for (Model m : p.models) {
                boolean ok = q.isEmpty() || pMatch
                        || m.name.toLowerCase(Locale.ROOT).contains(q)
                        || m.id.toLowerCase(Locale.ROOT).contains(q);
                if (!ok) continue;
                Row r = new Row();
                r.type = TYPE_MODEL;
                r.provider = p;
                r.model = m;
                r.current = p.id.equals(currentProvider) && m.id.equals(currentModel);
                models.add(r);
            }
            if (models.isEmpty()) continue;

            Row head = new Row();
            head.type = TYPE_PROVIDER;
            head.provider = p;
            head.label = (q.isEmpty() && !expanded.contains(p.id) ? "▸ " : "▾ ")
                    + p.name + "  ·  " + p.models.size();
            rows.add(head);

            // in ricerca i risultati sono sempre visibili; altrimenti solo se espanso
            if (!q.isEmpty() || expanded.contains(p.id)) rows.addAll(models);
        }

        if (rows.isEmpty()) {
            Row empty = new Row();
            empty.type = TYPE_EMPTY;
            empty.label = "Nessun risultato";
            rows.add(empty);
        }
        return rows;
    }

    // ------------------------------------------------------------------ adapter

    private static class Holder {
        TextView title;
        TextView sub;
    }

    private static class RowAdapter extends BaseAdapter {

        private final Context c;
        private List<Row> rows;

        RowAdapter(Context c, List<Row> rows) {
            this.c = c;
            this.rows = rows;
        }

        void setRows(List<Row> rows) {
            this.rows = rows;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Holder h;
            if (convertView == null) {
                LinearLayout ll = new LinearLayout(c);
                ll.setOrientation(LinearLayout.VERTICAL);

                TextView title = new TextView(c);
                title.setTextSize(15f);
                TextView sub = new TextView(c);
                sub.setTextSize(11f);
                sub.setTextColor(C_DIM);

                ll.addView(title, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                ll.addView(sub, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

                h = new Holder();
                h.title = title;
                h.sub = sub;
                ll.setTag(h);
                convertView = ll;
            } else {
                h = (Holder) convertView.getTag();
            }

            Row r = rows.get(position);
            int left = Ui.dp(c, 12);
            int right = Ui.dp(c, 12);

            if (r.type == TYPE_PROVIDER) {
                h.title.setText(r.label);
                h.title.setTextColor(C_GREEN);
                h.title.setTypeface(null, android.graphics.Typeface.BOLD);
                h.sub.setVisibility(View.GONE);
                convertView.setBackgroundColor(C_HEAD);
                convertView.setPadding(left, Ui.dp(c, 12), right, Ui.dp(c, 10));
            } else if (r.type == TYPE_MODEL) {
                h.title.setText((r.current ? "✓ " : "") + r.model.name);
                h.title.setTextColor(r.current ? C_GREEN : C_TEXT);
                h.title.setTypeface(null, r.current
                        ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
                String sub = r.model.id.equals(r.model.name) ? "" : r.model.id;
                h.sub.setText(sub);
                h.sub.setVisibility(sub.isEmpty() ? View.GONE : View.VISIBLE);
                convertView.setBackgroundColor(C_BG);
                convertView.setPadding(Ui.dp(c, 26), Ui.dp(c, 9), right, Ui.dp(c, 9));
            } else {
                h.title.setText(r.label);
                h.title.setTextColor(C_DIM);
                h.title.setTypeface(null, android.graphics.Typeface.NORMAL);
                h.sub.setVisibility(View.GONE);
                convertView.setBackgroundColor(C_BG);
                convertView.setPadding(left, Ui.dp(c, 18), right, Ui.dp(c, 18));
            }
            return convertView;
        }
    }
}
