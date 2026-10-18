package com.cyberstrike.client.util;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;
import android.text.style.UnderlineSpan;
import android.util.LruCache;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renderer Markdown minimale per i messaggi della chat.
 * Supporta: **grassetto**, *corsivo*, `codice`, blocchi ```…```, titoli #,
 * liste - e 1., citazioni >, righe orizzontali, ~~barrato~~, link [testo](url)
 * e URL automatici, tabelle pipe (impaginate sulla larghezza disponibile).
 */
public final class Markdown {

    private static final int C_CODE_BG = 0xFF0E1A13;
    private static final int C_CODE_FG = 0xFF9FE8C0;
    private static final int C_LINK = 0xFF00E676;
    private static final int C_DIM = 0xFF6E8F7C;
    private static final int C_HEAD = 0xFF00E676;
    private static final int C_TABLE = 0xFFB8D9C6;

    private static final Pattern UL = Pattern.compile("^([-*+])\\s+(.*)$");
    private static final Pattern OL = Pattern.compile("^(\\d+[.)])\\s+(.*)$");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)\\]\\([^)]*\\)");
    private static final Pattern BOLD2 = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern BOLD_ = Pattern.compile("__(.+?)__");

    private static final LruCache<String, CharSequence> CACHE = new LruCache<>(24);

    private Markdown() {
    }

    /** Converte Markdown in testo formattato; cols = caratteri per riga disponibili. */
    public static CharSequence render(String md, int cols) {
        if (md == null || md.isEmpty()) return "";
        int width = Math.max(20, cols);
        String key = width + "\u0000" + md;
        CharSequence hit = CACHE.get(key);
        if (hit != null) return hit;
        CharSequence out = build(md, width);
        CACHE.put(key, out);
        return out;
    }

    public static CharSequence render(String md) {
        return render(md, 40);
    }

    // ------------------------------------------------------------------ build

    private static CharSequence build(String src, int cols) {
        String s = src.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = s.split("\n", -1);
        SpannableStringBuilder out = new SpannableStringBuilder();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String t = line.trim();

            if (t.startsWith("```")) {
                StringBuilder code = new StringBuilder();
                i++;
                while (i < lines.length && !lines[i].trim().startsWith("```")) {
                    code.append(lines[i]).append('\n');
                    i++;
                }
                appendCode(out, code.toString());
                continue;
            }

            if (t.isEmpty()) {
                out.append('\n');
                continue;
            }

            // tabella: riga corrente + riga separatrice subito dopo
            if (t.indexOf('|') >= 0 && i + 1 < lines.length
                    && isSeparator(lines[i + 1].trim())) {
                int j = i;
                List<String[]> rows = new ArrayList<>();
                while (j < lines.length) {
                    String tl = lines[j].trim();
                    if (tl.isEmpty() || tl.indexOf('|') < 0) break;
                    if (isSeparator(tl)) {
                        j++;
                        continue;
                    }
                    rows.add(splitRow(tl));
                    j++;
                }
                appendTable(out, rows, cols);
                i = j - 1;
                continue;
            }

            if (isRule(t)) {
                int st = out.length();
                out.append("─────────────");
                out.setSpan(new ForegroundColorSpan(C_DIM), st, out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.append('\n');
                continue;
            }

            int h = headingLevel(t);
            if (h > 0) {
                int st = out.length();
                appendInline(out, t.substring(h).trim());
                int en = out.length();
                out.setSpan(new StyleSpan(Typeface.BOLD), st, en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new RelativeSizeSpan(h == 1 ? 1.35f : h == 2 ? 1.2f : 1.1f),
                        st, en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new ForegroundColorSpan(C_HEAD), st, en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.append('\n');
                continue;
            }

            if (t.startsWith(">")) {
                int st = out.length();
                out.append("│ ");
                out.setSpan(new ForegroundColorSpan(C_DIM), st, out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                int qs = out.length();
                appendInline(out, t.replaceFirst("^>+\\s?", ""));
                int qe = out.length();
                out.setSpan(new StyleSpan(Typeface.ITALIC), qs, qe, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new ForegroundColorSpan(C_DIM), qs, qe, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.append('\n');
                continue;
            }

            String list = listMarker(line);
            if (list != null) {
                appendInline(out, list);
                out.append('\n');
                continue;
            }

            appendInline(out, t);
            out.append('\n');
        }

        int n = out.length();
        while (n > 0 && out.charAt(n - 1) == '\n') {
            out.delete(n - 1, n);
            n--;
        }
        return out;
    }

    // ------------------------------------------------------------------ tabelle

    private static boolean isSeparator(String t) {
        if (t.indexOf('|') < 0) return false;
        boolean dash = false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '-') {
                dash = true;
            } else if (c != '|' && c != ':' && c != ' ') {
                return false;
            }
        }
        return dash;
    }

    private static String[] splitRow(String t) {
        int from = 0, to = t.length();
        if (t.startsWith("|")) from = 1;
        if (to > from && t.endsWith("|")) to--;
        String body = t.substring(from, to);
        String[] raw = body.split("\\|", -1);
        String[] cells = new String[raw.length];
        for (int i = 0; i < raw.length; i++) cells[i] = plain(raw[i].trim());
        return cells;
    }

    /** Toglie la sintassi inline da una cella (resa in monospace). */
    private static String plain(String s) {
        if (s.isEmpty()) return s;
        String r = LINK.matcher(s).replaceAll("$1");
        r = BOLD2.matcher(r).replaceAll("$1");
        r = BOLD_.matcher(r).replaceAll("$1");
        r = r.replace("`", "").replace("~~", "");
        return r;
    }

    private static void appendTable(SpannableStringBuilder out, List<String[]> rows, int cols) {
        if (rows.isEmpty()) return;
        int n = 0;
        for (String[] r : rows) n = Math.max(n, r.length);
        if (n == 0) return;

        int[] natural = new int[n];
        for (String[] r : rows) {
            for (int c = 0; c < n; c++) natural[c] = Math.max(natural[c], cell(r, c).length());
        }

        int gap = 3; // " │ "
        int avail = Math.max(12, cols - gap * (n - 1));
        int[] w = natural.clone();
        int sum = 0;
        for (int x : w) sum += x;

        // stringi le colonne più larghe finché la tabella non entra nella riga
        int[] mins = {6, 4, 2};
        for (int mn : mins) {
            while (sum > avail) {
                int idx = widest(w, mn);
                if (idx < 0) break;
                w[idx]--;
                sum--;
            }
        }
        while (sum > avail) {
            int idx = widest(w, 1);
            if (idx < 0) break;
            w[idx]--;
            sum--;
        }

        for (int ri = 0; ri < rows.size(); ri++) {
            String[] row = rows.get(ri);
            List<List<String>> wrapped = new ArrayList<>();
            int height = 1;
            for (int c = 0; c < n; c++) {
                List<String> cell = wrap(cell(row, c), Math.max(1, w[c]));
                wrapped.add(cell);
                height = Math.max(height, cell.size());
            }

            for (int l = 0; l < height; l++) {
                int st = out.length();
                for (int c = 0; c < n; c++) {
                    if (c > 0) out.append(" │ ");
                    List<String> cell = wrapped.get(c);
                    String piece = l < cell.size() ? cell.get(l) : "";
                    if (c < n - 1) out.append(pad(piece, w[c]));
                    else out.append(clip(piece, w[c]));
                }
                int en = out.length();
                out.setSpan(new TypefaceSpan("monospace"), st, en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new ForegroundColorSpan(ri == 0 ? 0xFFE6FFF1 : C_TABLE), st, en,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new RelativeSizeSpan(0.88f), st, en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                if (ri == 0) {
                    out.setSpan(new StyleSpan(Typeface.BOLD), st, en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                out.append('\n');
            }

            if (ri == 0) {
                int st = out.length();
                for (int c = 0; c < n; c++) {
                    if (c > 0) out.append("─┼─");
                    out.append(repeat("─", Math.max(1, w[c])));
                }
                out.setSpan(new ForegroundColorSpan(C_DIM), st, out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new TypefaceSpan("monospace"), st, out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.append('\n');
            }
        }
        out.append('\n');
    }

    private static String cell(String[] row, int idx) {
        return idx < row.length ? row[idx] : "";
    }

    /** Indice della colonna più larga, solo se supera la soglia minima. */
    private static int widest(int[] w, int min) {
        int idx = -1, best = min;
        for (int i = 0; i < w.length; i++) {
            if (w[i] > best) {
                best = w[i];
                idx = i;
            }
        }
        return idx;
    }

    private static String pad(String s, int width) {
        String v = clip(s, width);
        if (v.length() >= width) return v;
        return v + repeat(" ", width - v.length());
    }

    private static String clip(String s, int width) {
        if (width <= 0) return "";
        if (s.length() <= width) return s;
        if (width == 1) return "…";
        return s.substring(0, width - 1) + "…";
    }

    private static List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        String s = text == null ? "" : text.trim();
        if (s.isEmpty()) {
            lines.add("");
            return lines;
        }
        StringBuilder cur = new StringBuilder();
        for (String word : s.split("\\s+")) {
            String rest = word;
            if (rest.length() > width) {
                // parola più larga della colonna: tronca con ellissi
                if (cur.length() > 0) {
                    lines.add(cur.toString());
                    cur.setLength(0);
                }
                lines.add(clip(rest, width));
                continue;
            }
            if (cur.length() == 0) {
                cur.append(rest);
            } else if (cur.length() + 1 + rest.length() <= width) {
                cur.append(' ').append(rest);
            } else {
                lines.add(cur.toString());
                cur.setLength(0);
                cur.append(rest);
            }
        }
        if (cur.length() > 0) lines.add(cur.toString());
        if (lines.isEmpty()) lines.add("");
        return lines;
    }

    // ------------------------------------------------------------------ blocchi

    private static void appendCode(SpannableStringBuilder out, String code) {
        int st = out.length();
        out.append(code);
        int en = out.length();
        if (en > st) {
            out.setSpan(new TypefaceSpan("monospace"), st, en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            out.setSpan(new BackgroundColorSpan(C_CODE_BG), st, en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            out.setSpan(new ForegroundColorSpan(C_CODE_FG), st, en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            out.setSpan(new RelativeSizeSpan(0.92f), st, en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        out.append('\n');
    }

    private static boolean isRule(String t) {
        if (t.length() < 3) return false;
        char c = t.charAt(0);
        if (c != '-' && c != '*' && c != '_') return false;
        for (int i = 0; i < t.length(); i++) {
            char x = t.charAt(i);
            if (x == ' ') continue;
            if (x != c) return false;
        }
        return true;
    }

    private static int headingLevel(String t) {
        int n = 0;
        while (n < t.length() && t.charAt(n) == '#' && n < 6) n++;
        if (n == 0) return 0;
        if (n < t.length() && t.charAt(n) != ' ') return 0;
        return n;
    }

    private static String listMarker(String line) {
        int indent = 0;
        while (indent < line.length() && line.charAt(indent) == ' ') indent++;
        String pad = repeat("    ", Math.min(3, indent / 2));
        String t = line.trim();

        Matcher m = UL.matcher(t);
        if (m.matches()) {
            String item = m.group(2);
            String bullet;
            if (item.startsWith("[ ] ")) {
                bullet = "☐ ";
                item = item.substring(4);
            } else if (item.length() > 4 && item.charAt(0) == '['
                    && (item.charAt(1) == 'x' || item.charAt(1) == 'X')
                    && item.substring(2, 4).equals("] ")) {
                bullet = "☑ ";
                item = item.substring(4);
            } else {
                bullet = "• ";
            }
            return pad + bullet + item;
        }

        m = OL.matcher(t);
        if (m.matches()) return pad + m.group(1) + " " + m.group(2);
        return null;
    }

    // ------------------------------------------------------------------ inline

    private static void appendInline(SpannableStringBuilder out, String s) {
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);

            if (c == '\\' && i + 1 < s.length()) {
                out.append(s.charAt(i + 1));
                i += 2;
                continue;
            }

            if (c == '`') {
                int j = s.indexOf('`', i + 1);
                if (j > i) {
                    int st = out.length();
                    out.append(s, i + 1, j);
                    int en = out.length();
                    mono(out, st, en);
                    i = j + 1;
                    continue;
                }
                out.append(c);
                i++;
                continue;
            }

            if (c == '*' || c == '~') {
                int run = 1;
                while (i + run < s.length() && s.charAt(i + run) == c) run++;
                int use = c == '~' ? 2 : Math.min(3, run);
                if (c == '~' && run < 2) {
                    out.append(c);
                    i++;
                    continue;
                }
                String delim = repeat(String.valueOf(c), use);
                int close = s.indexOf(delim, i + use);
                if (close > i + use) {
                    int st = out.length();
                    appendInline(out, s.substring(i + use, close));
                    int en = out.length();
                    if (c == '~') {
                        out.setSpan(new StrikethroughSpan(), st, en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    } else {
                        if (use >= 2) {
                            out.setSpan(new StyleSpan(Typeface.BOLD), st, en,
                                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        }
                        if (use == 1 || use >= 3) {
                            out.setSpan(new StyleSpan(Typeface.ITALIC), st, en,
                                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        }
                    }
                    i = close + use;
                    continue;
                }
                out.append(c);
                i++;
                continue;
            }

            if (c == '_' && (i == 0 || !Character.isLetterOrDigit(s.charAt(i - 1)))) {
                boolean dbl = s.startsWith("__", i) && i + 2 < s.length();
                String delim = dbl ? "__" : "_";
                int close = s.indexOf(delim, i + delim.length());
                if (close > i + delim.length()) {
                    int st = out.length();
                    appendInline(out, s.substring(i + delim.length(), close));
                    int en = out.length();
                    out.setSpan(new StyleSpan(dbl ? Typeface.BOLD : Typeface.ITALIC), st, en,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = close + delim.length();
                    continue;
                }
            }

            if (c == '[') {
                int rb = s.indexOf(']', i + 1);
                if (rb > 0 && rb + 1 < s.length() && s.charAt(rb + 1) == '(') {
                    int rp = s.indexOf(')', rb + 2);
                    if (rp > rb) {
                        String label = s.substring(i + 1, rb);
                        String url = s.substring(rb + 2, rp).trim();
                        int st = out.length();
                        appendInline(out, label);
                        int en = out.length();
                        if (!url.isEmpty()) {
                            out.setSpan(new URLSpan(url), st, en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                            out.setSpan(new UnderlineSpan(), st, en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                            out.setSpan(new ForegroundColorSpan(C_LINK), st, en,
                                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        }
                        i = rp + 1;
                        continue;
                    }
                }
            }

            if (c == 'h' && (s.startsWith("http://", i) || s.startsWith("https://", i))) {
                int j = i;
                while (j < s.length() && !Character.isWhitespace(s.charAt(j))
                        && s.charAt(j) != ')' && s.charAt(j) != ']'
                        && s.charAt(j) != '>' && s.charAt(j) != '"') {
                    j++;
                }
                int st = out.length();
                out.append(s, i, j);
                int en = out.length();
                out.setSpan(new URLSpan(s.substring(i, j)), st, en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new ForegroundColorSpan(C_LINK), st, en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                i = j;
                continue;
            }

            out.append(c);
            i++;
        }
    }

    private static void mono(SpannableStringBuilder out, int st, int en) {
        if (en <= st) return;
        out.setSpan(new TypefaceSpan("monospace"), st, en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new BackgroundColorSpan(C_CODE_BG), st, en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new ForegroundColorSpan(C_CODE_FG), st, en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static String repeat(String s, int n) {
        StringBuilder b = new StringBuilder(s.length() * n);
        for (int i = 0; i < n; i++) b.append(s);
        return b.toString();
    }
}
