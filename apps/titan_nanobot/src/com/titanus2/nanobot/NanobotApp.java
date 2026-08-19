package com.titanus2.nanobot;

import android.app.Application;

/** Runs CE-wipe reconcile before any Activity can rehydrate a leftover Grok session. */
public final class NanobotApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        AppWipe.reconcile(this);
    }
}
