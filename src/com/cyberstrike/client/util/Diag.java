package com.cyberstrike.client.util;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * Log diagnostico in-app (ring buffer) + logcat.
 * Non richiede android:debuggable: serve a capire cosa fa l'app sul dispositivo.
 */
public final class Diag {

    private static final int MAX = 500;
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("HH:mm:ss", Locale.ITALY);

    private Diag() {
    }

    public static synchronized void add(String tag, String msg) {
        String line = FMT.format(new Date()) + " [" + tag + "] " + msg;
        LINES.addLast(line);
        while (LINES.size() > MAX) LINES.removeFirst();
        android.util.Log.d("CyberStrike", line);
    }

    public static synchronized String dump() {
        if (LINES.isEmpty()) return "Nessun evento registrato.";
        StringBuilder sb = new StringBuilder();
        for (String l : LINES) sb.append(l).append('\n');
        return sb.toString();
    }

    public static synchronized void clear() {
        LINES.clear();
    }
}
