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

    // ---------------------------------------------------------- modelli

    public static void setProviderDefault(Context c, String providerID, String modelID) {
        sp(c).edit().putString("def_" + providerID, modelID).apply();
    }

    public static String providerDefault(Context c, String providerID) {
        return sp(c).getString("def_" + providerID, "");
    }

    public static java.util.Map<String, String> providerDefaults(Context c) {
        java.util.Map<String, String> m = new java.util.HashMap<>();
        for (java.util.Map.Entry<String, ?> e : sp(c).getAll().entrySet()) {
            if (e.getKey().startsWith("def_") && e.getValue() instanceof String) {
                m.put(e.getKey().substring(4), (String) e.getValue());
            }
        }
        return m;
    }

    public static void hideModel(Context c, String providerID, String modelID) {
        java.util.Set<String> set = new java.util.HashSet<>(hidden(c));
        set.add(providerID + "/" + modelID);
        sp(c).edit().putStringSet("hiddenModels", set).apply();
    }

    public static void unhideModel(Context c, String providerID, String modelID) {
        java.util.Set<String> set = new java.util.HashSet<>(hidden(c));
        set.remove(providerID + "/" + modelID);
        sp(c).edit().putStringSet("hiddenModels", set).apply();
    }

    public static void unhideAll(Context c, String providerID) {
        java.util.Set<String> set = new java.util.HashSet<>(hidden(c));
        set.removeIf(v -> v.startsWith(providerID + "/"));
        sp(c).edit().putStringSet("hiddenModels", set).apply();
    }

    public static boolean isModelHidden(Context c, String providerID, String modelID) {
        return hidden(c).contains(providerID + "/" + modelID);
    }

    private static java.util.Set<String> hidden(Context c) {
        java.util.Set<String> s = sp(c).getStringSet("hiddenModels", null);
        return s == null ? new java.util.HashSet<String>() : s;
    }

    public static boolean autostart(Context c) {
        return sp(c).getBoolean("autostart", true);
    }

    public static void setAutostart(Context c, boolean v) {
        sp(c).edit().putBoolean("autostart", v).apply();
    }
}
