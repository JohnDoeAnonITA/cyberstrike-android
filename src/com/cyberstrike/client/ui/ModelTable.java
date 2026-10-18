package com.cyberstrike.client.ui;

import android.content.Context;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.cyberstrike.client.util.Ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Editor tabellare dei modelli: colonne ID | NOME, una riga per modello.
 * Sostituisce il campo multiriga con sintassi "id = nome".
 */
public class ModelTable extends LinearLayout {

    private static class Ref {
        final View view;
        final EditText id;
        final EditText name;

        Ref(View view, EditText id, EditText name) {
            this.view = view;
            this.id = id;
            this.name = name;
        }
    }

    private final List<Ref> rows = new ArrayList<>();
    private final Button more;

    public ModelTable(Context c) {
        super(c);
        setOrientation(VERTICAL);

        // intestazione tabella
        LinearLayout head = new LinearLayout(c);
        head.setOrientation(HORIZONTAL);
        head.setPadding(0, Ui.dp(c, 4), 0, Ui.dp(c, 2));
        head.addView(label(c, "ID"), new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        head.addView(label(c, "NOME"), new LayoutParams(0, LayoutParams.WRAP_CONTENT, 0.9f));
        head.addView(label(c, ""), new LayoutParams(Ui.dp(c, 34), LayoutParams.WRAP_CONTENT));
        addView(head);

        more = new Button(c);
        more.setText("+ riga");
        more.setAllCaps(false);
        more.setOnClickListener(v -> addRow("", ""));

        addRow("", "");
    }

    private static TextView label(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(11f);
        t.setTextColor(0xFF6E8F7C);
        t.setPadding(0, 0, Ui.dp(c, 6), 0);
        return t;
    }

    private EditText field(Context c, String hint, String value) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setText(value);
        e.setSingleLine(true);
        e.setTextSize(14f);
        e.setTextColor(0xFFD9F7E6);
        e.setHintTextColor(0xFF3F5A4A);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        return e;
    }

    public void addRow(String modelID, String name) {
        final LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        final EditText fid = field(getContext(), "id", modelID);
        final EditText fname = field(getContext(), "nome", name);
        fname.setInputType(InputType.TYPE_CLASS_TEXT);

        TextView del = new TextView(getContext());
        del.setText("✕");
        del.setTextSize(16f);
        del.setTextColor(0xFF6E8F7C);
        del.setGravity(Gravity.CENTER);
        del.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                for (int i = 0; i < rows.size(); i++) {
                    if (rows.get(i).view == row) {
                        rows.remove(i);
                        break;
                    }
                }
                removeView(row);
                if (rows.isEmpty()) addRow("", "");
            }
        });

        row.addView(fid, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        row.addView(fname, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 0.9f));
        row.addView(del, new LayoutParams(Ui.dp(getContext(), 34), LayoutParams.WRAP_CONTENT));

        addView(row);
        rows.add(new Ref(row, fid, fname));

        // tieni "+ riga" sempre in fondo
        removeView(more);
        addView(more);
    }

    /** Aggiunge più modelli (es. dalla lista del provider). */
    public void addModels(List<String[]> models) {
        if (models == null || models.isEmpty()) return;
        // riusa la prima riga se è vuota
        for (int i = 0; i < rows.size(); i++) {
            Ref r = rows.get(i);
            if (r.id.getText().toString().trim().isEmpty()) {
                r.id.setText(models.get(0)[0]);
                r.name.setText(models.get(0)[1].equals(models.get(0)[0]) ? "" : models.get(0)[1]);
                for (int k = 1; k < models.size(); k++) {
                    String[] m = models.get(k);
                    addRow(m[0], m[1].equals(m[0]) ? "" : m[1]);
                }
                return;
            }
        }
        for (String[] m : models) {
            addRow(m[0], m[1].equals(m[0]) ? "" : m[1]);
        }
    }

    /** Svuota la tabella lasciando una riga vuota pronta all'uso. */
    public void reset() {
        for (Ref r : new ArrayList<>(rows)) removeView(r.view);
        rows.clear();
        addRow("", "");
    }

    /** Modelli validi inseriti (id non vuoto). */
    public List<String[]> getModels() {
        List<String[]> out = new ArrayList<>();
        for (Ref r : rows) {
            String id = r.id.getText().toString().trim();
            if (id.isEmpty()) continue;
            String name = r.name.getText().toString().trim();
            if (name.isEmpty()) name = id;
            out.add(new String[]{id, name});
        }
        return out;
    }
}
