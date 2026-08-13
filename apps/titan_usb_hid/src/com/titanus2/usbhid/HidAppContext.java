package com.titanus2.usbhid;

import android.content.Context;

/** Process-wide application context for static helpers (LED bump, etc.). */
public final class HidAppContext {
    private static volatile Context app;

    private HidAppContext() {}

    public static void init(Context ctx) {
        if (ctx != null) app = ctx.getApplicationContext();
    }

    public static Context get() {
        return app;
    }
}
