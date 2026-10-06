package com.titanus2.usbhid;

/**
 * Process-wide neckband codec ({@code libnbremote.so}).
 * A missing library leaves classic HID unchanged.
 */
public final class NeckbandNative {
    public static final boolean LOADED;
    static {
        boolean ok = false;
        try {
            System.loadLibrary("nbremote");
            ok = true;
        } catch (UnsatisfiedLinkError e) {
            android.util.Log.w("NeckbandNative", "nbremote missing", e);
        }
        LOADED = ok;
    }

    private NeckbandNative() {}

    public static native void reset();
    public static native void setGain(int pct);
    public static native byte[] auth();
    public static native byte[] feed(int buttons, int dx, int dy, int wheel);
    public static native byte[] takeHover();
    public static native boolean hoverPending();
    public static native byte[] scrollEnd();
    public static native byte[] cancel();
    public static native byte[] keyEdge(int mod, int usage, boolean press);
    public static native byte[] kbdReport(byte[] report);
    public static native byte[] releaseKeys();
    public static native byte[] text(byte[] utf8);
}
