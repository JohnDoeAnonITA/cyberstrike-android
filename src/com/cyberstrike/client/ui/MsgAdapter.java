package com.cyberstrike.client.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.text.method.LinkMovementMethod;
import android.widget.TextView;

import com.cyberstrike.client.R;
import com.cyberstrike.client.util.Markdown;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MsgAdapter extends BaseAdapter {

    public static final int USER = 0;
    public static final int TEXT = 1;
    public static final int TOOL = 2;
    public static final int REASONING = 3;
    public static final int ERROR = 4;
    public static final int INFO = 5;
    public static final int IMAGE = 6;
    public static final int FILE = 7;

    public static class Row {
        public String id = "";
        public int kind;
        public String text = "";
        public String detail = "";
        public String mime = "";
        public String name = "";
        public String url = "";
    }

    public interface OnToolClick {
        void onTool(Row row);
    }

    private final Context ctx;
    private final List<Row> items = new ArrayList<>();
    private final OnToolClick listener;
    /** caratteri monospace che entrano nella bolla (per impaginare le tabelle). */
    private final int mdCols;

    private static final ExecutorService IMG_IO = Executors.newFixedThreadPool(2);
    private static final LruCache<String, Bitmap> IMG_CACHE = new LruCache<>(8);

    public MsgAdapter(Context c, OnToolClick l) {
        this.ctx = c;
        this.listener = l;
        this.mdCols = computeCols(c);
    }

    private static int computeCols(Context c) {
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        float density = dm.density <= 0 ? 1f : dm.density;
        float availDp = Math.min(dm.widthPixels / density - 24f, 340f) - 24f;
        int cols = (int) (availDp / (14f * 0.6f));
        return Math.max(24, Math.min(80, cols));
    }

    public List<Row> items() {
        return items;
    }

    public void set(List<Row> rows) {
        items.clear();
        items.addAll(rows);
        notifyDataSetChanged();
    }

    public void add(Row r) {
        items.add(r);
        notifyDataSetChanged();
    }

    public Row find(String id) {
        if (id == null) return null;
        for (Row r : items) {
            if (id.equals(r.id)) return r;
        }
        return null;
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public Object getItem(int i) {
        return items.get(i);
    }

    @Override
    public long getItemId(int i) {
        return i;
    }

    @Override
    public View getView(int pos, View cv, ViewGroup parent) {
        if (cv == null) {
            cv = View.inflate(ctx, R.layout.item_message, null);
        }
        final Row r = items.get(pos);
        TextView t = (TextView) cv.findViewById(R.id.body);
        ImageView img = (ImageView) cv.findViewById(R.id.img);

        t.setText(r.text);
        t.setMovementMethod(null);
        t.setTypeface(Typeface.DEFAULT);
        t.setTextSize(14f);
        t.setVisibility(View.VISIBLE);
        setGravity(t, Gravity.START);
        if (img != null) {
            img.setTag(null);
            img.setVisibility(View.GONE);
            img.setImageDrawable(null);
        }

        switch (r.kind) {
            case USER:
                t.setBackgroundResource(R.drawable.bg_bubble_user);
                t.setTextColor(0xFFD9F7E6);
                setGravity(t, Gravity.END);
                t.setOnClickListener(null);
                break;

            case TEXT:
                t.setBackgroundResource(R.drawable.bg_bubble_assistant);
                t.setTextColor(0xFFD9F7E6);
                t.setText(Markdown.render(r.text, mdCols));
                t.setMovementMethod(LinkMovementMethod.getInstance());
                t.setOnClickListener(null);
                break;

            case TOOL:
                t.setBackgroundResource(R.drawable.bg_tool);
                t.setTextColor(0xFF7FA98F);
                t.setTextSize(12f);
                t.setTypeface(Typeface.MONOSPACE);
                t.setOnClickListener(v -> {
                    if (listener != null) listener.onTool(r);
                });
                break;

            case REASONING:
                t.setBackground(null);
                t.setTextColor(0xFF3F5A4A);
                t.setTextSize(12f);
                t.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.ITALIC));
                t.setOnClickListener(null);
                break;

            case ERROR:
                t.setBackgroundResource(R.drawable.bg_tool);
                t.setTextColor(0xFFFF3B4E);
                t.setTextSize(13f);
                t.setTypeface(Typeface.MONOSPACE);
                t.setOnClickListener(null);
                break;

            case IMAGE:
                t.setText(r.name.isEmpty() ? "immagine" : r.name);
                t.setTextSize(11f);
                t.setTextColor(0xFF6E8F7C);
                t.setBackgroundResource(R.drawable.bg_bubble_assistant);
                t.setOnClickListener(null);
                if (img != null && r.url != null && !r.url.isEmpty()) {
                    img.setVisibility(View.VISIBLE);
                    bindImage(img, r.url);
                }
                break;

            case FILE:
                t.setBackgroundResource(R.drawable.bg_tool);
                t.setTextColor(0xFF7FA98F);
                t.setTextSize(12f);
                t.setTypeface(Typeface.MONOSPACE);
                t.setText("📎 " + (r.name.isEmpty() ? "file" : r.name)
                        + (r.mime.isEmpty() ? "" : "\n" + r.mime));
                t.setOnClickListener(null);
                break;

            default:
                t.setBackground(null);
                t.setTextColor(0xFF3F5A4A);
                t.setTextSize(12f);
                t.setOnClickListener(null);
                break;
        }
        return cv;
    }

    // --------------------------------------------------------------- immagini

    private void bindImage(final ImageView iv, final String url) {
        final String key = cacheKey(url);
        Bitmap cached = IMG_CACHE.get(key);
        if (cached != null) {
            iv.setImageBitmap(cached);
            return;
        }
        iv.setTag(url);
        iv.setImageDrawable(null);
        IMG_IO.execute(new Runnable() {
            @Override
            public void run() {
                final Bitmap bmp = decodeDataUrl(url);
                if (bmp == null) return;
                IMG_CACHE.put(key, bmp);
                iv.post(new Runnable() {
                    @Override
                    public void run() {
                        if (url.equals(iv.getTag())) iv.setImageBitmap(bmp);
                    }
                });
            }
        });
    }

    private static String cacheKey(String url) {
        return Integer.toHexString(url.hashCode()) + ":" + url.length();
    }

    /** Decodifica un data URL base64 riducendo i pixel per non saturare la memoria. */
    private static Bitmap decodeDataUrl(String url) {
        try {
            if (url == null || !url.startsWith("data:")) return null;
            int comma = url.indexOf(',');
            if (comma <= 5) return null;
            String meta = url.substring(5, comma);
            if (!meta.contains("base64")) return null;
            byte[] raw = Base64.decode(url.substring(comma + 1), Base64.DEFAULT);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(raw, 0, raw.length, bounds);

            int sample = 1;
            int maxW = 900;
            while (bounds.outWidth > 0 && bounds.outWidth / sample > maxW) sample *= 2;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = Math.max(1, sample);
            return BitmapFactory.decodeByteArray(raw, 0, raw.length, opts);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void setGravity(TextView t, int g) {
        ViewGroup.LayoutParams lp = t.getLayoutParams();
        if (lp instanceof android.widget.LinearLayout.LayoutParams) {
            ((android.widget.LinearLayout.LayoutParams) lp).gravity = g;
            t.setLayoutParams(lp);
        }
        t.setGravity(g | Gravity.CENTER_VERTICAL);
    }
}
