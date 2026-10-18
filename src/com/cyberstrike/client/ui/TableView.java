package com.cyberstrike.client.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

/**
 * Tabella Markdown disegnata con viste native: griglia, intestazione e celle,
 * nessun carattere ASCII. Se è più larga dello schermo scorre in orizzontale.
 */
public final class TableView {

    private static final int C_GRID = 0xFF17281D;
    private static final int C_HEAD_BG = 0xFF12241A;
    private static final int C_HEAD_FG = 0xFF9FE8C0;
    private static final int C_ROW_A = 0xFF0C1710;
    private static final int C_ROW_B = 0xFF0A130D;
    private static final int C_CELL_FG = 0xFFB8D9C6;
    private static final int C_KEY_FG = 0xFF88B09A;

    private TableView() {
    }

    public static View build(Context c, String[][] rows, boolean headered) {
        if (rows == null || rows.length == 0) return new View(c);
        float d = c.getResources().getDisplayMetrics().density;

        int cols = 0;
        for (String[] r : rows) cols = Math.max(cols, r.length);
        if (cols == 0) return new View(c);

        HorizontalScrollView hsv = new HorizontalScrollView(c);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.setFillViewport(false);

        TableLayout tl = new TableLayout(c);
        tl.setStretchAllColumns(false);
        tl.setShrinkAllColumns(false);
        tl.setBackground(round(C_GRID, d));
        tl.setClipToOutline(true);
        tl.setPadding(dp(1, d), dp(1, d), dp(1, d), dp(1, d));

        for (int i = 0; i < rows.length; i++) {
            boolean head = headered && i == 0;
            int rowBg = head ? C_HEAD_BG : (i % 2 == 0 ? C_ROW_A : C_ROW_B);

            TableRow tr = new TableRow(c);
            tr.setBackgroundColor(C_GRID);

            for (int j = 0; j < cols; j++) {
                TextView tv = new TextView(c);
                tv.setText(j < rows[i].length ? rows[i][j] : "");
                tv.setTextSize(head ? 11.5f : 12.5f);
                tv.setTextColor(head ? C_HEAD_FG : (j == 0 && !headered ? C_KEY_FG : C_CELL_FG));
                if (head) tv.setTypeface(Typeface.DEFAULT_BOLD);
                tv.setMaxWidth(dp(190, d));
                tv.setPadding(dp(9, d), dp(7, d), dp(9, d), dp(7, d));
                tv.setTextIsSelectable(true);
                tv.setBackgroundColor(rowBg);

                TableRow.LayoutParams lp = new TableRow.LayoutParams(
                        TableRow.LayoutParams.WRAP_CONTENT,
                        TableRow.LayoutParams.MATCH_PARENT);
                lp.rightMargin = dp(1, d);
                lp.bottomMargin = dp(1, d);
                tr.addView(tv, lp);
            }
            tl.addView(tr);
        }

        hsv.addView(tl);
        return hsv;
    }

    private static GradientDrawable round(int color, float d) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(color);
        g.setCornerRadius(dp(10, d));
        return g;
    }

    private static int dp(float v, float density) {
        return (int) (v * density);
    }
}
