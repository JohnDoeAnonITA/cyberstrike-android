package com.cyberstrike.client.util;

import android.content.Context;
import android.util.TypedValue;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class Ui {

    private Ui() {
    }

    public static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()));
    }

    public static void toast(Context c, String msg) {
        Toast.makeText(c, msg == null ? "" : msg, Toast.LENGTH_SHORT).show();
    }

    public static String ago(long millis) {
        if (millis <= 0) return "";
        long d = System.currentTimeMillis() - millis;
        if (d < 60_000L) return "ora";
        if (d < 3_600_000L) return (d / 60_000L) + "m";
        if (d < 86_400_000L) return (d / 3_600_000L) + "h";
        if (d < 7 * 86_400_000L) return (d / 86_400_000L) + "g";
        return new SimpleDateFormat("dd/MM", Locale.ITALY).format(new Date(millis));
    }

    public static String clock(long millis) {
        return new SimpleDateFormat("HH:mm", Locale.ITALY).format(new Date(millis));
    }

    public static String ellipsize(String s, int max) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
