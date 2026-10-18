package com.cyberstrike.client.util;

import android.content.Context;
import android.content.Intent;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Esegue assets/bootstrap.sh dentro Termux via RUN_COMMAND.
 * Lo script è idempotente: installa proot-distro/Debian/CyberStrike se mancano,
 * poi avvia il server.
 */
public final class Bootstrap {

    private Bootstrap() {
    }

    public static String script(Context c) {
        try (InputStream in = c.getAssets().open("bootstrap.sh")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean run(Context c) {
        if (!ServerStarter.installed(c)) return false;
        String s = script(c);
        if (s == null || s.isEmpty()) return false;
        try {
            Intent i = new Intent("com.termux.RUN_COMMAND");
            i.setClassName(ServerStarter.TERMUX, "com.termux.app.RunCommandService");
            i.putExtra("com.termux.RUN_COMMAND_PATH",
                    "/data/data/com.termux/files/usr/bin/bash");
            i.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{"-lc", s});
            i.putExtra("com.termux.RUN_COMMAND_WORKDIR",
                    "/data/data/com.termux/files/home");
            i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
            i.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0");
            c.startService(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Apre Termux (o la pagina di installazione se manca). */
    public static void openTermux(Context c) {
        try {
            Intent i = c.getPackageManager().getLaunchIntentForPackage(ServerStarter.TERMUX);
            if (i == null) throw new Exception("no intent");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Exception e) {
            try {
                c.startActivity(new Intent(Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://f-droid.org/packages/com.termux/"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) {
            }
        }
    }
}
