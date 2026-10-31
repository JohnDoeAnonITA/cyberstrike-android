package com.cyberstrike.client.util;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.util.Locale;

/**
 * Colonna sonora dell'app ("I Don't Want To Set The World On Fire").
 *
 * La traccia NON viene inclusa nell'APK: viene cercata a runtime fra i file
 * audio del dispositivo (cartella Download, via MediaStore o percorso diretto).
 * Serve il permesso di lettura audio (READ_MEDIA_AUDIO su Android 13+,
 * READ_EXTERNAL_STORAGE fino ad Android 12).
 *
 * Tutto è racchiuso in try/catch: se la traccia o il permesso mancano l'app
 * continua a funzionare in silenzio.
 */
public final class Ambient {

    private static final String[] HINTS = {
            "set the world",
            "set the world on fire",
            "ink spots"
    };

    private static MediaPlayer player;
    private static Uri source;
    private static boolean resolving;

    private static final AudioManager.OnAudioFocusChangeListener FOCUS =
            new AudioManager.OnAudioFocusChangeListener() {
                @Override
                public void onAudioFocusChange(int change) {
                    if (change == AudioManager.AUDIOFOCUS_LOSS
                            || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                        pauseQuietly();
                    }
                }
            };

    private Ambient() {
    }

    // ------------------------------------------------------------- preferenza

    public static boolean enabled(Context c) {
        return Prefs.music(c);
    }

    public static void setEnabled(Context c, boolean v) {
        Prefs.setMusic(c, v);
        if (!v) {
            pause();
        }
    }

    public static void toggle(Activity a) {
        boolean on = !enabled(a);
        setEnabled(a, on);
        if (on) {
            if (!hasPermission(a)) {
                requestPermission(a);
            } else {
                resume(a);
            }
        }
    }

    // -------------------------------------------------------------- permessi

    public static String permission() {
        return Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_AUDIO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    public static boolean hasPermission(Context c) {
        if (Build.VERSION.SDK_INT < 23) return true;
        try {
            return c.checkSelfPermission(permission()) == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void requestPermission(Activity a) {
        if (Build.VERSION.SDK_INT < 23 || hasPermission(a)) return;
        try {
            a.requestPermissions(new String[]{permission()}, 5001);
        } catch (Throwable t) {
            Diag.add("music", "richiesta permesso: " + t);
        }
    }

    // -------------------------------------------------------------- riproduzione

    public static void resume(Context c) {
        if (!enabled(c)) return;
        if (!hasPermission(c)) {
            Diag.add("music", "permesso audio mancante");
            return;
        }
        try {
            if (player == null) {
                Uri u = resolve(c);
                if (u == null) {
                    Diag.add("music", "traccia non trovata in Download");
                    return;
                }
                MediaPlayer mp = new MediaPlayer();
                mp.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build());
                mp.setDataSource(c, u);
                mp.setLooping(true);
                mp.setVolume(0.5f, 0.5f);
                mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                    @Override
                    public boolean onError(MediaPlayer m, int what, int extra) {
                        Diag.add("music", "errore player " + what + "/" + extra);
                        source = null;
                        release();
                        return true;
                    }
                });
                mp.prepare();
                player = mp;
                Diag.add("music", "traccia pronta: " + u);
            }
            if (!player.isPlaying()) {
                requestFocus(c);
                player.start();
            }
        } catch (Throwable t) {
            Diag.add("music", "avvio: " + t);
            release();
        }
    }

    public static void pause() {
        pauseQuietly();
        abandonFocus();
    }

    private static void pauseQuietly() {
        try {
            if (player != null && player.isPlaying()) player.pause();
        } catch (Throwable ignored) {
        }
    }

    public static void release() {
        try {
            if (player != null) {
                player.reset();
                player.release();
            }
        } catch (Throwable ignored) {
        }
        player = null;
        abandonFocus();
    }

    private static Context audioCtx;

    private static void requestFocus(Context c) {
        try {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                am.requestAudioFocus(FOCUS, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
                audioCtx = c.getApplicationContext();
            }
        } catch (Throwable ignored) {
        }
    }

    private static void abandonFocus() {
        try {
            if (audioCtx == null) return;
            AudioManager am = (AudioManager) audioCtx.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) am.abandonAudioFocus(FOCUS);
        } catch (Throwable ignored) {
        } finally {
            audioCtx = null;
        }
    }

    // ---------------------------------------------------------------- sorgente

    private static Uri resolve(Context c) {
        if (source != null) return source;
        if (resolving) return null;
        resolving = true;
        try {
            Uri u = fromMediaStore(c);
            if (u == null) u = fromDownloadDir();
            if (u != null) source = u;
            return u;
        } catch (Throwable t) {
            Diag.add("music", "ricerca traccia: " + t);
            return null;
        } finally {
            resolving = false;
        }
    }

    private static Uri fromMediaStore(Context c) {
        for (String hint : HINTS) {
            Cursor cur = null;
            try {
                cur = c.getContentResolver().query(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        new String[]{MediaStore.Audio.Media._ID},
                        MediaStore.Audio.Media.DISPLAY_NAME + " LIKE ? AND "
                                + MediaStore.Audio.Media.SIZE + " > 100000",
                        new String[]{"%" + hint + "%"},
                        MediaStore.Audio.Media._ID + " DESC");
                if (cur != null && cur.moveToFirst()) {
                    long id = cur.getLong(0);
                    return Uri.withAppendedPath(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, String.valueOf(id));
                }
            } catch (Throwable t) {
                Diag.add("music", "mediastore: " + t);
            } finally {
                if (cur != null) {
                    try {
                        cur.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return null;
    }

    private static Uri fromDownloadDir() {
        File dir = null;
        try {
            dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        } catch (Throwable ignored) {
        }
        File alt = new File("/storage/emulated/0/Download");
        File[] files = null;
        if (dir != null && dir.isDirectory()) files = dir.listFiles();
        if ((files == null || files.length == 0) && alt.isDirectory()) files = alt.listFiles();
        if (files == null) return null;

        File best = null;
        for (File f : files) {
            if (f == null || !f.isFile() || f.length() < 100_000L) continue;
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (!n.endsWith(".mp3") && !n.endsWith(".m4a") && !n.endsWith(".ogg")) continue;
            for (String hint : HINTS) {
                if (n.contains(hint)) {
                    if (best == null || f.length() > best.length()) best = f;
                    break;
                }
            }
        }
        return best == null ? null : Uri.fromFile(best);
    }
}
