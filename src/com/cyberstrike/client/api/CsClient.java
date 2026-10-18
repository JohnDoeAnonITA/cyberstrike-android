package com.cyberstrike.client.api;

import android.net.Uri;

import com.cyberstrike.client.util.Diag;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Client REST minimale per il server CyberStrike.
 * Nessuna dipendenza esterna: HttpURLConnection + org.json.
 */
public class CsClient {

    public static class ApiException extends Exception {
        public final int code;
        public final String body;

        public ApiException(int code, String body) {
            super(code == 0 ? body : ("HTTP " + code + " — " + shorten(body)));
            this.code = code;
            this.body = body;
        }

        private static String shorten(String s) {
            if (s == null) return "";
            s = s.replaceAll("\\s+", " ").trim();
            return s.length() > 300 ? s.substring(0, 300) + "…" : s;
        }
    }

    private final String base;
    private final String token;

    public CsClient(String base, String token) {
        String b = base == null ? "" : base.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        this.base = b;
        this.token = token == null ? "" : token.trim();
    }

    public String base() {
        return base;
    }

    // ------------------------------------------------------------------ http

    private HttpURLConnection open(String method, String path, String body, int readTimeout)
            throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(6000);
        c.setReadTimeout(readTimeout);
        c.setRequestProperty("Accept", "application/json");
        if (!token.isEmpty()) {
            c.setRequestProperty("Authorization", "Bearer " + token);
        }
        if (body != null) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream os = c.getOutputStream()) {
                os.write(bytes);
            }
        }
        return c;
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[8192];
        int n;
        while ((n = r.read(buf)) >= 0) sb.append(buf, 0, n);
        return sb.toString();
    }

    public String request(String method, String path, String body, int readTimeout)
            throws ApiException {
        HttpURLConnection c = null;
        try {
            c = open(method, path, body, readTimeout);
            int code = c.getResponseCode();
            String text = readAll(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code >= 400) {
                Diag.add("http", method + " " + path + " -> " + code);
                throw new ApiException(code, text);
            }
            return text;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            Diag.add("http", method + " " + path + " -> " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
            throw new ApiException(0, e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            if (c != null) c.disconnect();
        }
    }

    public JSONObject getObj(String path, int timeout) throws ApiException {
        return parseObj(request("GET", path, null, timeout));
    }

    public JSONArray getArr(String path, int timeout) throws ApiException {
        return parseArr(request("GET", path, null, timeout));
    }

    public JSONObject postObj(String path, JSONObject body, int timeout) throws ApiException {
        return parseObj(request("POST", path, body == null ? "{}" : body.toString(), timeout));
    }

    public JSONArray postArr(String path, JSONObject body, int timeout) throws ApiException {
        return parseArr(request("POST", path, body == null ? "{}" : body.toString(), timeout));
    }

    private static JSONObject parseObj(String s) throws ApiException {
        try {
            return new JSONObject(s == null || s.isEmpty() ? "{}" : s);
        } catch (Exception e) {
            throw new ApiException(0, "JSON non valido: " + s);
        }
    }

    private static JSONArray parseArr(String s) throws ApiException {
        try {
            return new JSONArray(s == null || s.isEmpty() ? "[]" : s);
        } catch (Exception e) {
            throw new ApiException(0, "JSON non valido: " + s);
        }
    }

    public JSONObject getOrEmpty(String path) {
        try {
            return parseObj(request("GET", path, null, 15000));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    public JSONArray getOrEmptyArr(String path) {
        try {
            return parseArr(request("GET", path, null, 15000));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    // --------------------------------------------------------------- helper

    public static String dir(String directory) {
        String d = (directory == null || directory.isEmpty()) ? "/" : directory;
        return "?directory=" + Uri.encode(d);
    }

    /** true se /global/health risponde healthy. */
    public boolean healthy() {
        try {
            JSONObject o = getObj("/global/health", 4000);
            return o.optBoolean("healthy", false);
        } catch (Exception e) {
            return false;
        }
    }

    public String version() {
        try {
            return getObj("/global/health", 4000).optString("version", "");
        } catch (Exception e) {
            return "";
        }
    }

    // -------------------------------------------------------------- sessions

    public JSONArray sessions(String directory) throws ApiException {
        return getArr("/session" + dir(directory), 15000);
    }

    public JSONObject createSession(String title, String directory) throws ApiException {
        return createSession(title, directory, true);
    }

    /**
     * Crea una sessione. Con allowAll=true imposta il ruleset
     * [{permission:"*", pattern:"*", action:"allow"}]: nessun prompt di
     * autorizzazione per bash, browser, read, write, task, ecc.
     */
    public JSONObject createSession(String title, String directory, boolean allowAll)
            throws ApiException {
        JSONObject b = new JSONObject();
        try {
            if (title != null && !title.isEmpty()) b.put("title", title);
            if (allowAll) {
                JSONArray rules = new JSONArray();
                rules.put(new JSONObject()
                        .put("permission", "*")
                        .put("pattern", "*")
                        .put("action", "allow"));
                b.put("permission", rules);
            }
        } catch (Exception ignored) {
        }
        return postObj("/session" + dir(directory), b, 20000);
    }

    public void deleteSession(String id, String directory) throws ApiException {
        request("DELETE", "/session/" + id + dir(directory), null, 15000);
    }

    public JSONArray messages(String id, String directory, int limit) throws ApiException {
        return getArr("/session/" + id + "/message" + dir(directory) + "&limit=" + limit, 30000);
    }

    /** Bloccante: ritorna quando l'assistente ha finito. Aggiornamenti live via SSE. */
    public JSONObject sendMessage(String id, String text, String agent,
                                  String providerID, String modelID, String directory)
            throws ApiException {
        JSONObject part = new JSONObject();
        JSONObject body = new JSONObject();
        try {
            part.put("type", "text");
            part.put("text", text);
            body.put("parts", new JSONArray().put(part));
            if (agent != null && !agent.isEmpty()) body.put("agent", agent);
            if (providerID != null && !providerID.isEmpty() && modelID != null && !modelID.isEmpty()) {
                body.put("model", new JSONObject()
                        .put("providerID", providerID)
                        .put("modelID", modelID));
            }
        } catch (Exception e) {
            throw new ApiException(0, "payload: " + e.getMessage());
        }
        return postObj("/session/" + id + "/message" + dir(directory), body, 900000);
    }

    public void abort(String id, String directory) throws ApiException {
        request("POST", "/session/" + id + "/abort" + dir(directory), "{}", 15000);
    }

    public JSONArray agents() throws ApiException {
        return getArr("/agent", 15000);
    }

    public JSONObject providers() throws ApiException {
        return getObj("/config/providers", 15000);
    }

    public JSONObject configObj() throws ApiException {
        return getObj("/config" + dir("/"), 15000);
    }

    public void patchConfig(JSONObject patch) throws ApiException {
        request("PATCH", "/config" + dir("/"), patch.toString(), 20000);
    }

    /** Salva una API key per un provider. */
    public void setAuth(String providerID, String key) throws ApiException {
        JSONObject b = new JSONObject();
        try {
            b.put("type", "api");
            b.put("key", key);
        } catch (Exception ignored) {
        }
        request("PUT", "/auth/" + providerID, b.toString(), 20000);
    }

    /** Aggiunge (o aggiorna) un provider con uno o più modelli. */
    public void addProvider(String id, String name, String baseURL,
                            java.util.List<String[]> models) throws ApiException {
        try {
            JSONObject map = new JSONObject();
            for (String[] m : models) {
                map.put(m[0], new JSONObject().put("id", m[0]).put("name", m[1]));
            }
            JSONObject options = new JSONObject();
            if (baseURL != null && !baseURL.isEmpty()) options.put("baseURL", baseURL);

            JSONObject provider = new JSONObject()
                    .put("name", name)
                    .put("npm", "@ai-sdk/openai-compatible")
                    .put("models", map)
                    .put("options", options);

            patchConfig(new JSONObject()
                    .put("provider", new JSONObject().put(id, provider)));
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(0, "payload: " + e.getMessage());
        }
    }

    /** Aggiunge più modelli a un provider esistente (merge). */
    public void addModels(String providerID, java.util.List<String[]> models) throws ApiException {
        try {
            JSONObject map = new JSONObject();
            for (String[] m : models) {
                map.put(m[0], new JSONObject().put("id", m[0]).put("name", m[1]));
            }
            patchConfig(new JSONObject()
                    .put("provider", new JSONObject().put(providerID,
                            new JSONObject().put("models", map))));
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(0, "payload: " + e.getMessage());
        }
    }

    /** Disattiva (nasconde) una lista di provider. */
    public void setDisabledProviders(JSONArray ids) throws ApiException {
        try {
            patchConfig(new JSONObject().put("disabled_providers", ids));
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(0, "payload: " + e.getMessage());
        }
    }

    /**
     * Sceglie un provider/modello utilizzabile: preferisce i provider remoti
     * (evita Ollama/LM Studio locali che spesso sono spenti).
     */
    public String[] pickModel() {
        return pickModel(null);
    }

    /** Come pickModel(), ma rispetta il modello predefinito scelto nell'app. */
    public String[] pickModel(java.util.Map<String, String> appDefaults) {
        try {
            JSONObject pr = providers();
            JSONArray arr = pr.optJSONArray("providers");
            JSONObject defs = pr.optJSONObject("default");
            if (arr == null) return null;

            String[] localFallback = null;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject p = arr.optJSONObject(i);
                if (p == null) continue;
                String id = p.optString("id", "");
                JSONObject models = p.optJSONObject("models");
                if (id.isEmpty() || models == null || models.length() == 0) continue;

                JSONObject opt = p.optJSONObject("options");
                String base = opt == null ? "" : opt.optString("baseURL", "");
                boolean local = base.contains("localhost") || base.contains("127.0.0.1")
                        || base.contains("192.168.") || base.contains("10.0.");

                String mid = appDefaults == null ? "" : appDefaults.get(id);
                if (mid == null || mid.isEmpty()) mid = defs == null ? "" : defs.optString(id, "");
                if (mid.isEmpty()) mid = models.keys().next();

                String[] pair = new String[]{id, mid};
                if (!local) return pair;
                if (localFallback == null) localFallback = pair;
            }
            return localFallback;
        } catch (Exception e) {
            Diag.add("model", "pickModel: " + e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------ permission

    public JSONArray permissions(String directory) throws ApiException {
        return getArr("/permission" + dir(directory), 15000);
    }

    public void replyPermission(String requestID, String reply, String directory)
            throws ApiException {
        JSONObject b = new JSONObject();
        try {
            b.put("reply", reply);
        } catch (Exception ignored) {
        }
        request("POST", "/permission/" + requestID + "/reply" + dir(directory),
                b.toString(), 20000);
    }

    public JSONArray questions(String directory) throws ApiException {
        return getArr("/question" + dir(directory), 15000);
    }

    /** answers: array di array di label, nell'ordine delle domande. */
    public void replyQuestion(String requestID, JSONArray answers, String directory)
            throws ApiException {
        JSONObject b = new JSONObject();
        try {
            b.put("answers", answers);
        } catch (Exception ignored) {
        }
        request("POST", "/question/" + requestID + "/reply" + dir(directory),
                b.toString(), 20000);
    }

    // ------------------------------------------------------------------ pty

    public JSONObject createPty(String command, String[] args, String cwd, String title)
            throws ApiException {
        JSONObject b = new JSONObject();
        try {
            b.put("command", command);
            JSONArray a = new JSONArray();
            if (args != null) for (String s : args) a.put(s);
            b.put("args", a);
            b.put("cwd", cwd == null ? "/root" : cwd);
            b.put("title", title == null ? "shell" : title);
        } catch (Exception ignored) {
        }
        return postObj("/pty" + dir("/"), b, 20000);
    }

    public void deletePty(String id) {
        try {
            request("DELETE", "/pty/" + id + dir("/"), null, 10000);
        } catch (Exception ignored) {
        }
    }

    public void resizePty(String id, int cols, int rows) {
        try {
            JSONObject size = new JSONObject().put("cols", cols).put("rows", rows);
            JSONObject b = new JSONObject().put("size", size);
            request("PUT", "/pty/" + id + dir("/"), b.toString(), 10000);
        } catch (Exception ignored) {
        }
    }

    public String wsPtyUrl(String ptyID) {
        String b = base;
        if (b.startsWith("https://")) b = "wss://" + b.substring(8);
        else if (b.startsWith("http://")) b = "ws://" + b.substring(7);
        return b + "/pty/" + ptyID + "/connect";
    }
}
