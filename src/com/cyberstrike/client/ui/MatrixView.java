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

import java.util.Random;

/**
 * Sfondo "matrix" (verde su nero) dietro la lista sessioni.
 * Semplice e leggero: ~24 colonne, 11 fps, nessuna bitmap di appoggio.
 */
public class MatrixView extends View {

    private static final String CHARS =
            "01ｱｲｳｴｵｶｷｸｹｺｻｼｽｾｿﾀﾁﾂﾃﾄﾅﾆﾇﾈﾉ"
            + "0123456789ABCDEF<>[]{}/\\$#@%&*";
    private static final int TRAIL = 10;
    private static final long FRAME_MS = 90;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random rnd = new Random();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final int bgColor = Color.parseColor("#050806");
    private final int glyphColor = Color.parseColor("#00E676");

    private int cols, rows;
    private float cell;
    private float[] y;
    private float[] speed;
    private char[][] glyphs;
    private boolean running;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            step();
            invalidate();
            handler.postDelayed(this, FRAME_MS);
        }
    };

    public MatrixView(Context c) {
        super(c);
        init();
    }

    public MatrixView(Context c, AttributeSet a) {
        super(c, a);
        init();
    }

    private void init() {
        paint.setTypeface(Typeface.MONOSPACE);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        cell = 18f * getResources().getDisplayMetrics().scaledDensity / 1.0f;
        cell = Math.max(24f, cell);
        paint.setTextSize(cell * 0.9f);

        cols = Math.max(1, (int) (w / cell));
        rows = Math.max(1, (int) (h / cell) + 1);

        y = new float[cols];
        speed = new float[cols];
        glyphs = new char[cols][TRAIL];

        for (int i = 0; i < cols; i++) {
            y[i] = rnd.nextInt(rows + 1) - rows;
            speed[i] = 0.25f + rnd.nextFloat() * 0.85f;
            randomize(i);
        }
    }

    private void randomize(int col) {
        for (int k = 0; k < TRAIL; k++) {
            glyphs[col][k] = CHARS.charAt(rnd.nextInt(CHARS.length()));
        }
    }

    private void step() {
        if (y == null) return;
        for (int i = 0; i < cols; i++) {
            y[i] += speed[i];
            if (y[i] - TRAIL > rows) {
                y[i] = -rnd.nextInt(Math.max(1, rows / 2));
                speed[i] = 0.25f + rnd.nextFloat() * 0.85f;
                randomize(i);
            }
            if (rnd.nextInt(6) == 0) {
                glyphs[i][rnd.nextInt(TRAIL)] = CHARS.charAt(rnd.nextInt(CHARS.length()));
            }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(bgColor);
        if (y == null) return;

        for (int i = 0; i < cols; i++) {
            int head = (int) y[i];
            for (int k = 0; k < TRAIL; k++) {
                int row = head - k;
                if (row < 0 || row > rows) continue;
                int alpha = k == 0 ? 190 : Math.max(18, 170 - k * 17);
                paint.setColor((alpha << 24) | (glyphColor & 0x00FFFFFF));
                canvas.drawText(String.valueOf(glyphs[i][k]), i * cell, row * cell, paint);
            }
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
