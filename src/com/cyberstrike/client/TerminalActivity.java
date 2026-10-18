package com.cyberstrike.client;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.TextView;

import com.cyberstrike.client.api.CsClient;
import com.cyberstrike.client.util.Prefs;
import com.cyberstrike.client.util.Ui;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Terminale embedded: PTY del server CyberStrike (WebSocket) + xterm.js.
 * Tutto il resto dell'app è nativo; qui la WebView fa solo da emulatore.
 */
public class TerminalActivity extends Activity {

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private CsClient api;
    private WebView web;
    private TextView title;
    private volatile String ptyID;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_terminal);

        api = new CsClient(Prefs.base(this), Prefs.token(this));
        title = (TextView) findViewById(R.id.title);
        web = (WebView) findViewById(R.id.web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportZoom(false);
        web.setBackgroundColor(0xFF000000);
        web.addJavascriptInterface(new Bridge(), "Bridge");

        findViewById(R.id.back).setOnClickListener(v -> finish());
        findViewById(R.id.restart).setOnClickListener(v -> {
            killPty();
            start();
        });

        start();
    }

    private void start() {
        title.setText("Terminale · avvio…");
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject p = api.createPty("bash", new String[]{"-l"}, "/root", "shell");
                    final String id = p.optString("id");
                    if (id.isEmpty()) throw new Exception("nessun id PTY");
                    ptyID = id;
                    final String ws = api.wsPtyUrl(id);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            title.setText("Terminale");
                            web.loadUrl("file:///android_asset/term.html?ws=" + Uri.encode(ws));
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            title.setText("Terminale");
                            Ui.toast(TerminalActivity.this, "PTY: " + e.getMessage());
                        }
                    });
                }
            }
        });
    }

    private void killPty() {
        final String id = ptyID;
        ptyID = null;
        if (id != null) {
            io.execute(new Runnable() {
                @Override
                public void run() {
                    api.deletePty(id);
                }
            });
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        killPty();
        try {
            web.destroy();
        } catch (Exception ignored) {
        }
    }

    public class Bridge {
        @JavascriptInterface
        public void resize(final int cols, final int rows) {
            final String id = ptyID;
            if (id == null || cols <= 0 || rows <= 0) return;
            io.execute(new Runnable() {
                @Override
                public void run() {
                    api.resizePty(id, cols, rows);
                }
            });
        }
    }
}
