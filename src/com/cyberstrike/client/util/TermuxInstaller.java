package com.cyberstrike.client.util;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import com.cyberstrike.client.ApkProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Scarica l'APK di Termux dalla release GitHub corretta per l'ABI del
 * dispositivo e lancia l'installer di sistema.
 */
public final class TermuxInstaller {

    private static final String API =
            "https://api.github.com/repos/termux/termux-app/releases/latest";
    private static final String RELEASES =
            "https://github.com/termux/termux-app/releases";

    public interface Progress {
        void onProgress(int percent);
    }

    private TermuxInstaller() {
    }

    /** Indica se l'app può installare APK (sorgenti sconosciute). */
    public static boolean canInstall(Context c) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return c.getPackageManager().canRequestPackageInstalls();
        }
        return true;
    }

    public static void openUnknownSources(Context c) {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + c.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Exception e) {
            try {
                c.startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) {
            }
        }
    }

    public static String releasesPage() {
        return RELEASES;
    }

    /** Risolve l'URL dell'APK adatto all'ABI del dispositivo. */
    public static String resolveApkUrl() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(API).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "CyberStrike-Android");

        int code = c.getResponseCode();
        if (code >= 400) throw new Exception("GitHub API: HTTP " + code);

        StringBuilder sb = new StringBuilder();
        try (InputStream in = c.getInputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) sb.append(new String(buf, 0, n, "UTF-8"));
        } finally {
            c.disconnect();
        }

        JSONObject o = new JSONObject(sb.toString());
        JSONArray assets = o.optJSONArray("assets");
        if (assets == null) throw new Exception("nessun asset nella release");

        String abi = Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0
                ? Build.SUPPORTED_ABIS[0] : "arm64-v8a";
        String want;
        if (abi.contains("arm64")) want = "arm64-v8a";
        else if (abi.contains("armeabi")) want = "armeabi-v7a";
        else if (abi.contains("x86_64")) want = "x86_64";
        else if (abi.contains("x86")) want = "x86";
        else want = "universal";

        String fallback = null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.optJSONObject(i);
            if (a == null) continue;
            String name = a.optString("name", "");
            String url = a.optString("browser_download_url", "");
            if (!name.endsWith(".apk") || url.isEmpty()) continue;
            if (name.contains(want)) return url;
            if (fallback == null && name.contains("universal")) fallback = url;
        }
        if (fallback != null) return fallback;
        throw new Exception("APK " + want + " non trovato nella release");
    }

    /** Scarica l'APK nella cartella privata dell'app. */
    public static File download(Context c, String url, Progress p) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "CyberStrike-Android");

        int code = conn.getResponseCode();
        if (code >= 400) throw new Exception("download: HTTP " + code);

        int total = conn.getContentLength();
        File dir = c.getExternalFilesDir(null);
        if (dir == null) dir = c.getFilesDir();
        File out = new File(dir, "termux.apk");
        File tmp = new File(dir, "termux.apk.part");

        try (InputStream in = conn.getInputStream();
             FileOutputStream fos = new FileOutputStream(tmp)) {
            byte[] buf = new byte[64 * 1024];
            long done = 0;
            int n;
            int lastPct = -1;
            while ((n = in.read(buf)) >= 0) {
                fos.write(buf, 0, n);
                done += n;
                if (total > 0) {
                    int pct = (int) (done * 100 / total);
                    if (pct != lastPct) {
                        lastPct = pct;
                        p.onProgress(pct);
                    }
                }
            }
        } finally {
            conn.disconnect();
        }

        if (out.exists() && !out.delete()) throw new Exception("impossibile sostituire l'APK");
        if (!tmp.renameTo(out)) throw new Exception("salvataggio APK fallito");
        return out;
    }

    /** Lancia l'installer di sistema sull'APK scaricato. */
    public static void install(Context c, File apk) {
        Uri uri = Uri.parse("content://" + ApkProvider.AUTHORITY + "/" + apk.getName());
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
    }
}
