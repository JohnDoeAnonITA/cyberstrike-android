package com.cyberstrike.client.util;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

/**
 * Avvia il server CyberStrike dentro Termux tramite RUN_COMMAND.
 * Richiede in Termux: allow-external-apps = true  e lo script ~/.cs-start.sh.
 */
public final class ServerStarter {

    public static final String TERMUX = "com.termux";
    private static final String SERVICE = "com.termux.app.RunCommandService";
    private static final String ACTION = "com.termux.RUN_COMMAND";

    private ServerStarter() {
    }

    public static boolean installed(Context c) {
        try {
            c.getPackageManager().getPackageInfo(TERMUX, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Lancia ~/.cs-start.sh in background dentro Termux. */
    public static boolean start(Context c) {
        if (!installed(c)) return false;
        try {
            Intent i = new Intent(ACTION);
            i.setClassName(TERMUX, SERVICE);
            i.putExtra("com.termux.RUN_COMMAND_PATH",
                    "/data/data/com.termux/files/usr/bin/bash");
            i.putExtra("com.termux.RUN_COMMAND_ARGUMENTS",
                    new String[]{"-lc", "~/.cs-start.sh"});
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
}
