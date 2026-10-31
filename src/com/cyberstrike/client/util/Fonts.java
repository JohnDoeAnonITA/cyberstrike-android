package com.cyberstrike.client.util;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/**
 * Font monospace stile terminale (Share Tech Mono, licenza OFL) caricato dagli
 * asset. Se il file manca si ricade sul monospace di sistema.
 *
 * Le viste con tag "nofont" (emoji) restano sul font di sistema, così i glifi
 * emoji non diventano quadratini.
 */
public final class Fonts {

    public static final String ASSET = "fonts/pip.ttf";

    private static Typeface regular;
    private static Typeface bold;
    private static boolean failed;

    private Fonts() {
    }

    public static Typeface mono(Context c) {
        if (regular == null && !failed) {
            try {
                regular = Typeface.createFromAsset(c.getAssets(), ASSET);
            } catch (Throwable t) {
                failed = true;
            }
            if (regular == null) regular = Typeface.MONOSPACE;
        }
        return regular == null ? Typeface.MONOSPACE : regular;
    }

    public static Typeface bold(Context c) {
        if (bold == null) bold = Typeface.create(mono(c), Typeface.BOLD);
        return bold;
    }

    /** Applica il font a tutte le TextView (ricorsivo). */
    public static void apply(View root) {
        if (root == null) return;
        if (root instanceof TextView) {
            TextView tv = (TextView) root;
            if ("nofont".equals(tv.getTag())) return;
            Typeface cur = tv.getTypeface();
            boolean isBold = cur != null && cur.isBold();
            tv.setTypeface(isBold ? bold(tv.getContext()) : mono(tv.getContext()));
            if (tv.getLetterSpacing() == 0f) {
                // intestazioni e bottoni respirano meglio con un filo di spaziatura
                float size = tv.getTextSize();
                if (size >= 13f) tv.setLetterSpacing(0.02f);
            }
            return;
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                apply(g.getChildAt(i));
            }
        }
    }
}
