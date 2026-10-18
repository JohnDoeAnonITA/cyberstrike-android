package com.cyberstrike.client.api;

import android.os.Handler;
import android.os.Looper;

import com.cyberstrike.client.util.Diag;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Lettore SSE di GET /event con riconnessione automatica.
 * Gli eventi arrivano già sul thread principale.
 */
public class EventStream {

    public interface Listener {
        void onEvent(String type, JSONObject properties);

        void onConnection(boolean connected);
    }

    private final String base;
    private final String token;
    private final String directory;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile boolean running;
    private volatile HttpURLConnection current;
    private Thread thread;

    public EventStream(String base, String token, String directory, Listener listener) {
        String b = base == null ? "" : base.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        this.base = b;
        this.token = token == null ? "" : token.trim();
        this.directory = directory;
        this.listener = listener;
    }

    public void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::loop, "cs-events");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        HttpURLConnection c = current;
        if (c != null) {
            try {
                c.disconnect();
            } catch (Exception ignored) {
            }
        }
        if (thread != null) thread.interrupt();
        thread = null;
    }

    private void loop() {
        while (running) {
            HttpURLConnection c = null;
            try {
                String url = base + "/event" + CsClient.dir(directory);
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setRequestProperty("Accept", "text/event-stream");
                c.setRequestProperty("Cache-Control", "no-cache");
                c.setConnectTimeout(6000);
                c.setReadTimeout(0);
                if (!token.isEmpty()) {
                    c.setRequestProperty("Authorization", "Bearer " + token);
                }
                current = c;
                int code = c.getResponseCode();
                if (code >= 400) throw new Exception("HTTP " + code);

                Diag.add("sse", "connected");
                main.post(() -> listener.onConnection(true));

                BufferedReader r = new BufferedReader(
                        new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder data = new StringBuilder();
                String line;
                while (running && (line = r.readLine()) != null) {
                    if (line.isEmpty()) {
                        if (data.length() > 0) {
                            dispatch(data.toString());
                            data.setLength(0);
                        }
                    } else if (line.startsWith("data:")) {
                        data.append(line.substring(5).trim());
                    }
                    // righe che iniziano con ':' sono keepalive
                }
            } catch (Exception ex) {
                Diag.add("sse", "disconnected: " + ex.getClass().getSimpleName());
            } finally {
                current = null;
                if (c != null) c.disconnect();
            }

            if (running) {
                main.post(() -> listener.onConnection(false));
                try {
                    Thread.sleep(2500);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    private void dispatch(String json) {
        try {
            JSONObject o = new JSONObject(json);
            final String type = o.optString("type", "");
            if (type.isEmpty()) return;
            JSONObject p = o.optJSONObject("properties");
            final JSONObject props = p == null ? new JSONObject() : p;
            main.post(() -> listener.onEvent(type, props));
        } catch (Exception ignored) {
        }
    }
}
