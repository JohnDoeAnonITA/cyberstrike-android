package com.cyberstrike.client.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.View;

/**
 * Schermata di avvio in stile terminale ROBCO: le righe vengono "battute"
 * carattere per carattere, poi il pannello sfuma e si toglie di mezzo.
 */
public class BootScreen extends View {

    private static final String[] LINES = {
            "ROBCO INDUSTRIES (TM) TERMLINK PROTOCOL",
            "",
            "CYBERSTRIKE OS  v0.2",
            "> MOUNT /root/.cyberstrike ......... OK",
            "> LINK 127.0.0.1:27182 ............. OK",
            "> AGENT CORE ....................... READY",
            "",
            "WELCOME, VAULT DWELLER."
    };

    /** caratteri battuti al secondo */
    private static final float CPS = 150f;
    private static final long HOLD_MS = 650;
    private static final long FADE_MS = 380;
    private static final long FRAME_MS = 33;

    private final Paint bg = new Paint();
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler handler = new Handler(Looper.getMainLooper());

    private int totalChars;
    private long startAt;
    private boolean started;
    private boolean done;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!started || done) return;
            long elapsed = System.currentTimeMillis() - startAt;
            float typed = elapsed * CPS / 1000f;
            invalidate();
            if (typed > totalChars + HOLD_MS * CPS / 1000f) {
                finish();
                return;
            }
            handler.postDelayed(this, FRAME_MS);
        }
    };

    public BootScreen(Context c) {
        super(c);
        init();
    }

    public BootScreen(Context c, AttributeSet a) {
        super(c, a);
        init();
    }

    private void init() {
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        bg.setColor(Color.parseColor("#020A08"));
        bg.setStyle(Paint.Style.FILL);
        text.setColor(Color.parseColor("#93FFC6"));
        text.setTypeface(com.cyberstrike.client.util.Fonts.mono(getContext()));
        for (String l : LINES) totalChars += l.length();
    }

    /** Avvia l'animazione; se è già stata mostrata una volta non rifà nulla. */
    public void start() {
        if (started || done) return;
        started = true;
        setVisibility(VISIBLE);
        startAt = System.currentTimeMillis();
        handler.post(tick);
    }

    private void finish() {
        if (done) return;
        done = true;
        handler.removeCallbacks(tick);
        animate().alpha(0f).setDuration(FADE_MS).withEndAction(new Runnable() {
            @Override
            public void run() {
                setVisibility(GONE);
            }
        }).start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawRect(0, 0, getWidth(), getHeight(), bg);

        float density = getResources().getDisplayMetrics().density;
        text.setTextSize(11f * getResources().getDisplayMetrics().scaledDensity);

        float left = 22f * density;
        float lineH = text.getTextSize() * 1.55f;
        float top = Math.max(60f * density, getHeight() * 0.28f);

        int budget = started
                ? (int) ((System.currentTimeMillis() - startAt) * CPS / 1000f)
                : 0;

        int used = 0;
        for (int i = 0; i < LINES.length; i++) {
            String line = LINES[i];
            int take = Math.max(0, Math.min(line.length(), budget - used));
            used += line.length();
            if (take > 0) {
                canvas.drawText(line.substring(0, take), left, top + i * lineH, text);
            }
            if (budget > used) {
                // cursore lampeggiante sull'ultima riga visibile
                boolean blink = (System.currentTimeMillis() / 400) % 2 == 0;
                if (blink && i == lastVisibleLine(budget)) {
                    float w = text.measureText(line.substring(0, take));
                    canvas.drawRect(left + w + 2, top + i * lineH - text.getTextSize() * 0.8f,
                            left + w + 2 + text.getTextSize() * 0.55f, top + i * lineH + 2, text);
                }
            }
        }
    }

    private int lastVisibleLine(int budget) {
        int used = 0;
        for (int i = 0; i < LINES.length; i++) {
            used += LINES[i].length();
            if (budget <= used) return i;
        }
        return LINES.length - 1;
    }

    @Override
    protected void onDetachedFromWindow() {
        handler.removeCallbacks(tick);
        super.onDetachedFromWindow();
    }
}
