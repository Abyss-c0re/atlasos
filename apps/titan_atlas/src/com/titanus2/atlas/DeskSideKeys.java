package com.titanus2.atlas;

import android.content.Context;
import android.content.SharedPreferences;

import com.titanus2.api.Titan2ApiContract;
import com.titanus2.api.Titan2Client;

/**
 * Side keys go through Titan Controls. A short press is the scroll wheel
 * until the user picks something else: top scrolls up, bottom scrolls down.
 */
public final class DeskSideKeys {
    private static final String PREFS = "atlas_side_keys";
    private static final String DONE = "scroll_defaulted";

    public static final String[][] CHOICES = {
        { Titan2ApiContract.ACT_MOUSE_SCROLL_UP, "Scroll up" },
        { Titan2ApiContract.ACT_MOUSE_SCROLL_DOWN, "Scroll down" },
        { "none", "None" },
        { Titan2ApiContract.ACT_MOUSE_LEFT, "Left click" },
        { Titan2ApiContract.ACT_MOUSE_RIGHT, "Right click" },
        { Titan2ApiContract.ACT_MOUSE_MIDDLE, "Middle click" },
    };

    private DeskSideKeys() {}

    public static void ensureScrollDefault(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (p.getBoolean(DONE, false)) return;
        Titan2Client api = new Titan2Client(c);
        api.setKeyAction(Titan2ApiContract.SLOT_SIDE2_SHORT,
            Titan2ApiContract.ACT_MOUSE_SCROLL_UP);
        api.setKeyAction(Titan2ApiContract.SLOT_SIDE_SHORT,
            Titan2ApiContract.ACT_MOUSE_SCROLL_DOWN);
        p.edit().putBoolean(DONE, true).apply();
    }

    public static String action(Context c, String slot) {
        String v = new Titan2Client(c).getKeyAction(slot);
        if (v == null || v.isEmpty() || "default".equals(v)) {
            if (Titan2ApiContract.SLOT_SIDE2_SHORT.equals(slot)) {
                return Titan2ApiContract.ACT_MOUSE_SCROLL_UP;
            }
            if (Titan2ApiContract.SLOT_SIDE_SHORT.equals(slot)) {
                return Titan2ApiContract.ACT_MOUSE_SCROLL_DOWN;
            }
            return "none";
        }
        return v;
    }

    public static void set(Context c, String slot, String action) {
        new Titan2Client(c).setKeyAction(slot, action);
    }

    public static String label(String action) {
        for (String[] row : CHOICES) {
            if (row[0].equals(action)) return row[1];
        }
        return action == null ? "None" : action;
    }

    public static String next(String action) {
        int i = 0;
        for (int n = 0; n < CHOICES.length; n++) {
            if (CHOICES[n][0].equals(action)) {
                i = n;
                break;
            }
        }
        return CHOICES[(i + 1) % CHOICES.length][0];
    }
}
