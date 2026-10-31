package com.cyberstrike.client;

import android.app.Application;
import android.os.Bundle;
import android.app.Activity;

import com.cyberstrike.client.util.Ambient;
import com.cyberstrike.client.util.Fonts;

/**
 * Applicazione: applica il font PIP-OS a tutte le activity appena create e
 * gestisce la colonna sonora in base al ciclo di vita (suona solo mentre
 * l'app è in primo piano, su qualunque schermata).
 */
public class CsApp extends Application {

    private int started;

    @Override
    public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(Activity a, Bundle b) {
                Fonts.apply(a.findViewById(android.R.id.content));
            }

            @Override
            public void onActivityStarted(Activity a) {
                started++;
                if (started == 1) Ambient.resume(a);
            }

            @Override
            public void onActivityStopped(Activity a) {
                started = Math.max(0, started - 1);
                if (started == 0) Ambient.pause();
            }

            @Override
            public void onActivityResumed(Activity a) {
            }

            @Override
            public void onActivityPaused(Activity a) {
            }

            @Override
            public void onActivitySaveInstanceState(Activity a, Bundle b) {
            }

            @Override
            public void onActivityDestroyed(Activity a) {
            }
        });
    }
}
