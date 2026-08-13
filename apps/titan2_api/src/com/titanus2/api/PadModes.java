package com.titanus2.api;

/** Pad mode helpers shared by Controls, HID, and third parties. */
public final class PadModes {
    private PadModes() {}

    public static String normalize(String mode) {
        if (mode == null) return Titan2ApiContract.MODE_OFF;
        mode = mode.trim().toLowerCase();
        if (Titan2ApiContract.MODE_TRACKPAD.equals(mode)
                || Titan2ApiContract.MODE_MOUSE.equals(mode)
                || Titan2ApiContract.MODE_OFF.equals(mode)) {
            return mode;
        }
        if ("1".equals(mode) || "true".equals(mode) || "on".equals(mode)) {
            return Titan2ApiContract.MODE_MOUSE;
        }
        return Titan2ApiContract.MODE_OFF;
    }

    public static String shortLabel(String mode) {
        mode = normalize(mode);
        if (Titan2ApiContract.MODE_TRACKPAD.equals(mode)) return "Trackpad";
        if (Titan2ApiContract.MODE_MOUSE.equals(mode)) return "Mouse";
        return "Off";
    }

    public static boolean isActive(String mode) {
        mode = normalize(mode);
        return Titan2ApiContract.MODE_TRACKPAD.equals(mode)
                || Titan2ApiContract.MODE_MOUSE.equals(mode);
    }
}
