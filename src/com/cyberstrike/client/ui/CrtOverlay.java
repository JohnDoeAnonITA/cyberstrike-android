package com.cyberstrike.client.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.View;

/**
 * Effetto vetro catodico sopra la UI: scanline, vignettatura e una banda
 * luminosa che scorre lentamente verso il basso.
 *
 * Non intercetta i tocchi (non è clickable): la FrameLayout continua a
 * consegnare gli eventi alle viste sottostanti.
 */
public class CrtOverlay extends View {

    private static final long FRAME_MS = 80;

    private final Paint linePaint = new Paint();
    private final Paint vignettePaint = new Paint();
    private final Paint sweepPaint = new Paint();

    private final Handler handler = new Handler(Looper.getMainLooper());

    private Bitmap lineBitmap;
    private RadialGradient vignette;
    private LinearGradient sweepGradient;

    private int viewW, viewH;
    private float sweepY;
    private boolean running;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            sweepY += viewH * 0.011f;
            if (sweepY > viewH) sweepY = -viewH * 0.2f;
            invalidate();
            handler.postDelayed(this, FRAME_MS);
        }
    };

    public CrtOverlay(Context c) {
        super(c);
        init();
    }

    public CrtOverlay(Context c, AttributeSet a) {
        super(c, a);
        init();
    }

    private void init() {
        setWillNotDraw(false);
        setClickable(false);
        setLongClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        linePaint.setStyle(Paint.Style.FILL);
        vignettePaint.setStyle(Paint.Style.FILL);
        sweepPaint.setStyle(Paint.Style.FILL);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w <= 0 || h <= 0) return;
        viewW = w;
        viewH = h;

        float density = getResources().getDisplayMetrics().density;
        int period = Math.max(3, Math.round(3f * density));

        lineBitmap = Bitmap.createBitmap(1, period, Bitmap.Config.ARGB_8888);
        Canvas bc = new Canvas(lineBitmap);
        bc.drawColor(0x00000000);
        Paint p = new Paint();
        p.setColor(0x5E000000);
        bc.drawRect(0, period - 1, 1, period, p);
        linePaint.setShader(new BitmapShader(lineBitmap,
                Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));

        vignette = new RadialGradient(w / 2f, h / 2f, Math.max(w, h) * 0.80f,
                new int[]{0x00000000, 0x00000000, 0x88000000},
                new float[]{0f, 0.58f, 1f},
                Shader.TileMode.CLAMP);
        vignettePaint.setShader(vignette);

        sweepGradient = new LinearGradient(0, 0, 0, Math.max(28f, h * 0.16f),
                new int[]{0x00000000, 0x14B4FFD6, 0x00000000},
                new float[]{0f, 0.5f, 1f},
                Shader.TileMode.CLAMP);
        sweepPaint.setShader(sweepGradient);
        sweepY = -h * 0.2f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (lineBitmap == null || viewW <= 0 || viewH <= 0) return;

        canvas.drawRect(0, 0, viewW, viewH, linePaint);
        canvas.drawRect(0, 0, viewW, viewH, vignettePaint);

        if (sweepGradient != null) {
            canvas.save();
            canvas.translate(0, sweepY);
            canvas.drawRect(0, 0, viewW, Math.max(28f, viewH * 0.16f), sweepPaint);
            canvas.restore();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        resume();
    }

    @Override
    protected void onDetachedFromWindow() {
        pause();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) resume();
        else pause();
    }

    private void resume() {
        if (running) return;
        running = true;
        handler.postDelayed(tick, FRAME_MS);
    }

    private void pause() {
        running = false;
        handler.removeCallbacks(tick);
    }
}
