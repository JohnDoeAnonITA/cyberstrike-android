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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renderer Markdown minimale per i messaggi della chat.
 * Supporta: **grassetto**, *corsivo*, `codice`, blocchi ```…```, titoli #,
 * liste - e 1., citazioni >, righe orizzontali, ~~barrato~~, link [testo](url)
 * e URL automatici, tabelle in pipe (rese in monospace).
 */
public final class Markdown {

    private static final int C_CODE_BG = 0xFF0E1A13;
    private static final int C_CODE_FG = 0xFF9FE8C0;
    private static final int C_LINK = 0xFF00E676;
    private static final int C_DIM = 0xFF6E8F7C;
    private static final int C_HEAD = 0xFF00E676;

    private static final Pattern UL = Pattern.compile("^([-*+])\\s+(.*)$");
    private static final Pattern OL = Pattern.compile("^(\\d+[.)])\\s+(.*)$");
    private static final Pattern TABLE_SEP = Pattern.compile("^\\|?[\\s:|-]+\\|[\\s:|-]*$");

    private static final LruCache<String, CharSequence> CACHE = new LruCache<>(24);

    private Markdown() {
    }

    /** Converte Markdown in testo formattato (con cache). */
    public static CharSequence render(String md) {
        if (md == null || md.isEmpty()) return "";
        CharSequence hit = CACHE.get(md);
        if (hit != null) return hit;
        CharSequence out = build(md);
        CACHE.put(md, out);
        return out;
    }

    private static CharSequence build(String src) {
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

            if (t.startsWith("|") && t.endsWith("|")) {
                if (TABLE_SEP.matcher(t).matches()) continue; // riga di separazione
                int st = out.length();
                out.append(t);
                int en = out.length();
                out.setSpan(new TypefaceSpan("monospace"), st, en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new ForegroundColorSpan(0xFFB8D9C6), st, en,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.append('\n');
                continue;
            }

            appendInline(out, t);
            out.append('\n');
        }

        // massimo una riga vuota consecutiva
        int n = out.length();
        while (n > 1 && out.charAt(n - 1) == '\n' && out.charAt(n - 2) == '\n') {
            out.delete(n - 1, n);
            n--;
        }
        while (n > 0 && out.charAt(n - 1) == '\n') {
            out.delete(n - 1, n);
            n--;
        }
        return out;
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
