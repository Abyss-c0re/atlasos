package com.titanus2.usbhid;

import android.content.Context;
import android.content.SharedPreferences;
import com.titanus2.api.ControlPlane;
import com.titanus2.api.Titan2ApiContract;

/**
 * Configurable trackpad gestures for HID / mouse mode.
 * Plane files are hot-read by titan2-touchpadd (no restart).
 */
public final class PadGesturePrefs {
    private static final String PREFS = "hid_pad_gestures";

    private PadGesturePrefs() {}

    private static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean tapClick(Context c) {
        return p(c).getBoolean("tap_click", true);
    }

    public static boolean longClick(Context c) {
        return p(c).getBoolean("long_click", true);
    }

    public static boolean scroll(Context c) {
        return p(c).getBoolean("scroll", true);
    }

    /** classic | latch | off */
    public static String dblTap(Context c) {
        String v = p(c).getString("dbltap", Titan2ApiContract.PAD_DBLTAP_CLASSIC);
        if (v == null || v.isEmpty()) return Titan2ApiContract.PAD_DBLTAP_CLASSIC;
        return v;
    }

    public static void setTapClick(Context c, boolean on) {
        p(c).edit().putBoolean("tap_click", on).apply();
        publish(c);
    }

    public static void setLongClick(Context c, boolean on) {
        p(c).edit().putBoolean("long_click", on).apply();
        publish(c);
    }

    public static void setScroll(Context c, boolean on) {
        p(c).edit().putBoolean("scroll", on).apply();
        publish(c);
    }

    public static void setDblTap(Context c, String mode) {
        if (mode == null) mode = Titan2ApiContract.PAD_DBLTAP_CLASSIC;
        p(c).edit().putString("dbltap", mode).apply();
        publish(c);
    }

    public static String nextDblTap(String cur) {
        if (Titan2ApiContract.PAD_DBLTAP_CLASSIC.equals(cur)) {
            return Titan2ApiContract.PAD_DBLTAP_LATCH;
        }
        if (Titan2ApiContract.PAD_DBLTAP_LATCH.equals(cur)) {
            return Titan2ApiContract.PAD_DBLTAP_OFF;
        }
        return Titan2ApiContract.PAD_DBLTAP_CLASSIC;
    }

    public static String dblTapLabel(String mode) {
        if (Titan2ApiContract.PAD_DBLTAP_LATCH.equals(mode)) {
            return "Double-tap · latch hold";
        }
        if (Titan2ApiContract.PAD_DBLTAP_OFF.equals(mode)) {
            return "Double-tap · off";
        }
        return "Double-tap · classic drag";
    }

    /** Write plane for touchpadd + Global. */
    public static void publish(Context c) {
        if (c == null) return;
        try {
            ControlPlane.put(c, Titan2ApiContract.FILE_PAD_TAP_CLICK,
                tapClick(c) ? "1" : "0");
            ControlPlane.put(c, Titan2ApiContract.FILE_PAD_LONG_CLICK,
                longClick(c) ? "1" : "0");
            ControlPlane.put(c, Titan2ApiContract.FILE_PAD_SCROLL,
                scroll(c) ? "1" : "0");
            ControlPlane.put(c, Titan2ApiContract.FILE_PAD_DBLTAP, dblTap(c));
        } catch (Exception ignored) {}
    }
}
