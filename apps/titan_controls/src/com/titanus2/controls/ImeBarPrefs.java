package com.titanus2.controls;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.om.OverlayManager;
import android.os.Process;
import android.os.UserHandle;
import android.provider.Settings;

/**
 * Hide the IME navigation bar (the strip InputMethodService draws over apps).
 * <p>
 * Tweaks and the main page share {@code titan2_tweaks/ime_switcher_hidden}.
 * The root helper {@code titan2-ime-bar.sh} reads that and enables the
 * product overlay. This class also writes {@code titan2_hide_ime_bar} and
 * tries the overlay directly when the platform allows it.
 * <p>
 * Does not rewrite {@code enabled_input_methods}. The user's keyboards stay.
 */
public final class ImeBarPrefs {
    public static final String PREF = "titan2_tweaks";
    /** Legacy key written by the Hide keyboard switcher row. */
    public static final String KEY = "ime_switcher_hidden";
    public static final String GLOBAL = "titan2_hide_ime_bar";

    private static final String STATIC_OVERLAY = "com.titanus2.overlay.imenavbar";
    private static final String LIVE_OVERLAY = "com.android.shell:TitanNoImeNavBar";

    private ImeBarPrefs() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** True when the IME bar should stay off. Default is hide. */
    public static boolean hide(Context ctx) {
        if (ctx == null) return true;
        SharedPreferences p = prefs(ctx);
        if (p.contains(KEY)) return p.getBoolean(KEY, true);
        try {
            int g = Settings.Global.getInt(ctx.getContentResolver(), GLOBAL, -1);
            if (g == 0) return false;
            if (g == 1) return true;
        } catch (Exception ignored) {}
        return true;
    }

    public static void setHide(Context ctx, boolean hide) {
        if (ctx == null) return;
        prefs(ctx).edit().putBoolean(KEY, hide).commit();
        try {
            Settings.Global.putInt(ctx.getContentResolver(), GLOBAL, hide ? 1 : 0);
        } catch (Exception ignored) {}
        applyOverlay(ctx, hide);
    }

    /** Boot / unlock: re-apply the stored choice. */
    public static void applyStored(Context ctx) {
        if (ctx == null) return;
        setHide(ctx, hide(ctx));
    }

    private static void applyOverlay(Context ctx, boolean hide) {
        try {
            OverlayManager om = ctx.getSystemService(OverlayManager.class);
            if (om == null) return;
            UserHandle user = UserHandle.getUserHandleForUid(Process.myUid());
            setEnabled(om, STATIC_OVERLAY, hide, user);
            int colon = LIVE_OVERLAY.indexOf(':');
            if (colon > 0) {
                Class<?> idClass = Class.forName("android.content.om.OverlayIdentifier");
                Object id = idClass.getConstructor(String.class, String.class).newInstance(
                        LIVE_OVERLAY.substring(0, colon),
                        LIVE_OVERLAY.substring(colon + 1));
                om.getClass()
                        .getMethod("setEnabled", idClass, boolean.class, UserHandle.class)
                        .invoke(om, id, hide, user);
            }
        } catch (Throwable ignored) {}
    }

    /** setEnabled is a hidden platform method. The shell helper is the fallback. */
    private static void setEnabled(OverlayManager om, String name, boolean hide, UserHandle user) {
        try {
            om.getClass()
                    .getMethod("setEnabled", String.class, boolean.class, UserHandle.class)
                    .invoke(om, name, hide, user);
        } catch (Throwable ignored) {}
    }
}
