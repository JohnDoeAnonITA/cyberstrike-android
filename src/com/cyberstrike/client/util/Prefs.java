package com.cyberstrike.client.util;

import android.content.Context;
import android.content.SharedPreferences;

public final class Prefs {

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("cyberstrike", Context.MODE_PRIVATE);
    }

    public static String base(Context c) {
        return sp(c).getString("base", "http://127.0.0.1:27182");
    }

    public static void setBase(Context c, String v) {
        sp(c).edit().putString("base", v).apply();
    }

    public static String token(Context c) {
        return sp(c).getString("token", "");
    }

    public static void setToken(Context c, String v) {
        sp(c).edit().putString("token", v).apply();
    }

    public static String agent(Context c) {
        return sp(c).getString("agent", "cyberstrike");
    }

    public static void setAgent(Context c, String v) {
        sp(c).edit().putString("agent", v).apply();
    }

    public static String provider(Context c) {
        return sp(c).getString("provider", "");
    }

    public static String model(Context c) {
        return sp(c).getString("model", "");
    }

    public static void setModel(Context c, String providerID, String modelID) {
        sp(c).edit().putString("provider", providerID).putString("model", modelID).apply();
    }

    /** Permessi allow-all sulle nuove sessioni (default: attivo). */
    public static boolean allowAll(Context c) {
        return sp(c).getBoolean("allowAll", true);
    }

    public static void setAllowAll(Context c, boolean v) {
        sp(c).edit().putBoolean("allowAll", v).apply();
    }

    /** true se il permesso RUN_COMMAND è già stato chiesto all'utente. */
    public static boolean permAsked(Context c) {
        return sp(c).getBoolean("permAsked", false);
    }

    public static void setPermAsked(Context c, boolean v) {
        sp(c).edit().putBoolean("permAsked", v).apply();
    }

    /** Abilita chrome://inspect sulla WebView del terminale (default: off). */
    public static boolean webviewDebug(Context c) {
        return sp(c).getBoolean("webviewDebug", false);
    }

    public static void setWebviewDebug(Context c, boolean v) {
        sp(c).edit().putBoolean("webviewDebug", v).apply();
    }

    public static boolean autostart(Context c) {
        return sp(c).getBoolean("autostart", true);
    }

    public static void setAutostart(Context c, boolean v) {
        sp(c).edit().putBoolean("autostart", v).apply();
    }
}
