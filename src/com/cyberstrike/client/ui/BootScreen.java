package com.cyberstrike.client.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.View;

import com.cyberstrike.client.util.Fonts;

/**
 * Intro in stile terminale ROBCO: intestazione TERMLINK, griglia di password
 * esadecimali, tentativi con "LIKENESS", ACCESS GRANTED e infine l'avvio di
 * CyberStrike OS.
 *
 * Le righe compaiono secondo una timeline (ms); il tocco salta l'animazione.
 */
public class BootScreen extends View {

    private static final long END_MS = 5200;
    private static final long FADE_MS = 380;

    private static final int C_TEXT = Color.parseColor("#93FFC6");
    private static final int C_DIM = Color.parseColor("#46B485");
    private static final int C_FAINT = Color.parseColor("#1F6B4E");
    private static final int C_HOT = Color.parseColor("#3CFF8F");

    private static final String[] WORDS = {
            "AMPLE", "CRATER", "DEFECT", "ECHOED", "FUSION", "GADGET", "HUMBLE", "INJECT",
            "JACKET", "KETTLE", "LIQUID", "MALICE", "NERVES", "OBJECT", "PACKET", "VAULT"
    };
    private static final String[] ADDR = {
            "0xF4A1", "0x22B7", "0x9C20", "0x0E55", "0x71D3", "0xB08E", "0x5A62", "0xC4F0",
            "0x1D9B", "0x8E27", "0x3C64", "0xDA15", "0x6B80", "0x0F3D", "0xA7E2", "0x4C19"
    };

    private static final class Row {
        final long at;
        final String text;
        final int color;
        final boolean instant;

        Row(long at, String text, int color, boolean instant) {
            this.at = at;
            this.text = text;
            this.color = color;
            this.instant = instant;
        }
    }

    private final Row[] rows;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bg = new Paint();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private long startAt;
    private boolean started;
    private boolean done;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!started || done) return;
            invalidate();
            if (System.currentTimeMillis() - startAt > END_MS) {
                finish();
                return;
            }
            handler.postDelayed(this, 40);
        }
    };

    public BootScreen(Context c) {
        super(c);
        rows = build();
        init();
    }

    public BootScreen(Context c, AttributeSet a) {
        super(c, a);
        rows = build();
        init();
    }

    private static Row[] build() {
        Row[] r = new Row[26];
        int i = 0;
        r[i++] = new Row(0, "ROBCO INDUSTRIES (TM) TERMLINK PROTOCOL", C_TEXT, false);
        r[i++] = new Row(150, "ENTER PASSWORD NOW", C_TEXT, false);
        r[i++] = new Row(330, "", C_FAINT, true);
        r[i++] = new Row(380, "4 ATTEMPT(S) LEFT :  [ ][ ][ ][ ]", C_DIM, false);

        for (int line = 0; line < 8; line++) {
            String left = ADDR[line] + "  " + WORDS[line];
            String right = ADDR[line + 8] + "  " + WORDS[line + 8];
            r[i++] = new Row(440 + line * 18L, pad(left) + right, C_DIM, true);
        }

        r[i++] = new Row(900, "> AMPLE", C_TEXT, false);
        r[i++] = new Row(1350, "LIKENESS= 4", C_FAINT, false);
        r[i++] = new Row(1700, "> CRATER", C_TEXT, false);
        r[i++] = new Row(2150, "LIKENESS= 2", C_FAINT, false);
        r[i++] = new Row(2500, "> VAULT", C_TEXT, false);
        r[i++] = new Row(2950, "ACCESS GRANTED", C_HOT, false);

        r[i++] = new Row(3300, "", C_FAINT, true);
        r[i++] = new Row(3400, "> MOUNT /root/.cyberstrike ........ OK", C_TEXT, false);
        r[i++] = new Row(3650, "> LINK 127.0.0.1:27182 ............ OK", C_TEXT, false);
        r[i++] = new Row(3900, "> AGENT CORE ...................... READY", C_TEXT, false);
        r[i++] = new Row(4150, "", C_FAINT, true);
        r[i++] = new Row(4250, "WELCOME, VAULT DWELLER.", C_HOT, false);

        Row[] out = new Row[i];
        System.arraycopy(r, 0, out, 0, i);
        return out;
    }

    /** colonna sinistra larga 16 caratteri, così le due colonne si allineano. */
    private static String pad(String s) {
        StringBuilder b = new StringBuilder(s);
        while (b.length() < 17) b.append(' ');
        return b.toString();
    }

    private void init() {
        setWillNotDraw(false);
        setClickable(true);
        setFocusable(true);
        setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        bg.setColor(Color.parseColor("#020A08"));
        bg.setStyle(Paint.Style.FILL);
        paint.setTypeface(Fonts.mono(getContext()));
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
        int w = getWidth();
        int h = getHeight();
        canvas.drawRect(0, 0, w, h, bg);

        float density = getResources().getDisplayMetrics().density;
        float scaled = getResources().getDisplayMetrics().scaledDensity;

        float left = 18f * density;
        float avail = w - left * 2;

        // la riga più lunga è di ~40 caratteri: il font si adatta alla larghezza
        float sizePx = 11.5f * scaled;
        paint.setTextSize(sizePx);
        float needed = paint.measureText("0") * 40.5f;
        if (needed > avail && needed > 0) {
            sizePx = sizePx * (avail / needed);
            paint.setTextSize(sizePx);
        }

        float lineH = sizePx * 1.42f;
        float total = lineH * rows.length;
        float top = Math.max(20f * density, (h - total) * 0.42f);

        long now = started ? System.currentTimeMillis() - startAt : 0;
        int last = -1;

        for (int i = 0; i < rows.length; i++) {
            Row r = rows[i];
            if (!started || now < r.at) break;
            last = i;

            paint.setColor(r.color);
            String text = r.text;
            if (!r.instant && !text.isEmpty()) {
                int chars = (int) ((now - r.at) * 0.30f);
                if (chars < text.length()) text = text.substring(0, Math.max(0, chars));
            }
            canvas.drawText(text, left, top + i * lineH, paint);
        }

        // cursore lampeggiante in fondo all'ultima riga mostrata
        if (last >= 0 && (System.currentTimeMillis() / 380) % 2 == 0) {
            Row r = rows[last];
            String full = r.text;
            String shown = full;
            if (!r.instant && !full.isEmpty()) {
                int chars = (int) ((now - r.at) * 0.30f);
                if (chars < full.length()) shown = full.substring(0, Math.max(0, chars));
            }
            float cx = left + paint.measureText(shown) + 2f;
            float cy = top + last * lineH;
            paint.setColor(C_HOT);
            canvas.drawRect(cx, cy - sizePx * 0.82f, cx + sizePx * 0.5f, cy + 2f, paint);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        handler.removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
