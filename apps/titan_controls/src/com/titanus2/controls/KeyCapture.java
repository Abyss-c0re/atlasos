package com.titanus2.controls;

/**
 * Keys settings capture plane.
 * While a one-shot capture is armed, remaps/layouts must not fire — only
 * identify the physical key. Keys UI open alone no longer blocks layouts
 * (that made Specials/Arrows look dead while testing on the Keys screen).
 */
public final class KeyCapture {
    public interface Listener {
        void onKey(int scan, int keyCode);
    }

    private static volatile boolean uiOpen;
    private static volatile boolean armed;
    private static volatile Listener listener;

    private KeyCapture() {}

    /** Keys activity visible (UI state; does not block layouts). */
    public static void setUiOpen(boolean open) {
        uiOpen = open;
        if (!open) {
            disarm();
        }
    }

    public static boolean isUiOpen() {
        return uiOpen;
    }

    /**
     * True only while a one-shot capture is armed.
     * Layouts and remaps stay live when Keys is merely open.
     */
    public static boolean blockActions() {
        return armed;
    }

    public static void arm(Listener l) {
        listener = l;
        armed = l != null;
    }

    public static void disarm() {
        armed = false;
        listener = null;
    }

    public static boolean isArmed() {
        return armed;
    }

    /**
     * Deliver captured key on DOWN. Always disarms after one key.
     * @return true if a listener consumed the capture
     */
    public static boolean offer(int scan, int keyCode) {
        if (!armed) return false;
        Listener l = listener;
        armed = false;
        listener = null;
        if (l == null) return false;
        try {
            l.onKey(scan, keyCode);
        } catch (Exception ignored) {}
        return true;
    }
}
