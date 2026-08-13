package com.titanus2.controls.subdisplay;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.util.DisplayMetrics;
import android.view.Display;

public final class SubDisplayHelper {
    public static final int REAR_W = 410;
    public static final int REAR_H = 502;

    private SubDisplayHelper() {}

    public static Display findRear(Context ctx) {
        DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
        if (dm == null) return null;
        Display best = null;
        for (Display d : dm.getDisplays()) {
            if (d.getDisplayId() == Display.DEFAULT_DISPLAY) continue;
            DisplayMetrics m = new DisplayMetrics();
            try {
                d.getRealMetrics(m);
            } catch (Exception e) {
                d.getMetrics(m);
            }
            int w = Math.min(m.widthPixels, m.heightPixels);
            int h = Math.max(m.widthPixels, m.heightPixels);
            // exact Titan rear
            if (w == REAR_W && h == REAR_H) return d;
            // fallback: smallest internal presentation display
            if (w > 0 && h > 0 && w <= 600 && h <= 700) {
                if (best == null) best = d;
            }
        }
        return best;
    }

    public static String describe(Context ctx) {
        Display d = findRear(ctx);
        if (d == null) return "rear: not found";
        DisplayMetrics m = new DisplayMetrics();
        try { d.getRealMetrics(m); } catch (Exception e) { d.getMetrics(m); }
        return "rear id=" + d.getDisplayId()
            + " " + m.widthPixels + "x" + m.heightPixels
            + " state=" + d.getState();
    }
}
