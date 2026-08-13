package com.titanus2.controls;

import android.content.Context;

public final class TrackpadPolicy {
    private TrackpadPolicy() {}

    public static String apply(Context ctx, String foregroundPackage) {
        TrackpadPrefs prefs = new TrackpadPrefs(ctx);
        String mode = prefs.getMode();
        boolean wantOn;
        if (TrackpadPrefs.MODE_GLOBAL.equals(mode)) {
            wantOn = true;
        } else if (TrackpadPrefs.MODE_WHITELIST.equals(mode)) {
            wantOn = prefs.isWhitelisted(foregroundPackage);
        } else {
            wantOn = false;
        }
        return TrackpadHardware.setTrackpadEnabled(ctx, wantOn);
    }

    public static String applyStatic(Context ctx) {
        TrackpadPrefs prefs = new TrackpadPrefs(ctx);
        if (TrackpadPrefs.MODE_WHITELIST.equals(prefs.getMode())) {
            return TrackpadHardware.setTrackpadEnabled(ctx, false);
        }
        return apply(ctx, null);
    }
}
