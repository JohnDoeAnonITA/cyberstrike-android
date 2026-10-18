package com.cyberstrike.client.util;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Legge i modelli disponibili da un endpoint OpenAI-compatibile: GET <baseURL>/models.
 * Supporta le forme {data:[...]}, {models:[...]} e array puro.
 */
public final class ModelList {

    private ModelList() {
    }

    /** @return lista di {id, nome} */
    public static List<String[]> fetch(String baseURL, String apiKey) throws Exception {
        String base = baseURL == null ? "" : baseURL.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (!base.startsWith("http")) throw new Exception("baseURL non valido");
        String url = base.endsWith("/models") ? base : base + "/models";

        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        c.setRequestProperty("Accept", "application/json");
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            c.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
        }

        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String body = read(in);
        c.disconnect();

        if (code >= 400) {
            throw new Exception("HTTP " + code + ": " + shorten(body));
        }
        List<String[]> out = parse(body);
        if (out.isEmpty()) throw new Exception("Nessun modello nella risposta");
        return out;
    }

    static List<String[]> parse(String body) {
        List<String[]> out = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(body);
            JSONArray arr = root.optJSONArray("data");
            if (arr == null) arr = root.optJSONArray("models");
            if (arr != null) collect(arr, out);
        } catch (Exception e) {
            try {
                collect(new JSONArray(body), out);
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private static void collect(JSONArray arr, List<String[]> out) {
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String id = o.optString("id", o.optString("model", o.optString("name", "")));
            if (id.isEmpty()) continue;
            String name = o.optString("display_name", o.optString("name", id));
            if (name.isEmpty()) name = id;
            out.add(new String[]{id, name});
        }
    }

    private static String read(InputStream in) throws Exception {
        if (in == null) return "";
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[8192];
        int n;
        while ((n = r.read(buf)) >= 0) sb.append(buf, 0, n);
        return sb.toString();
    }

    private static String shorten(String s) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
