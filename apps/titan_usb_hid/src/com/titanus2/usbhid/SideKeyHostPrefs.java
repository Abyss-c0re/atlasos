package com.titanus2.usbhid;

import android.content.Context;
import android.content.SharedPreferences;
import com.titanus2.api.Titan2ApiContract;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Customizable host-side remaps for Titan physical side keys while HID is live.
 * <p>
 * Action strings are the Controls/API catalog:
 * {@code mouse:scroll_up}, {@code mouse:left}, {@code host:up},
 * {@code host:ctrl+c}, {@code none}, etc. Applied as a temp keymap layer on
 * top of {@link Titan2ApiContract#LAYER_HID_SESSION} silence so phone chrome
 * stays off and host actions fire through a11y → REMOTE_INPUT.
 */
public final class SideKeyHostPrefs {
    private static final String PREFS = "hid_side_host_keys";
    private static final String P = "slot_";

    /** Curated cycle list for settings tiles (not the full catalog). */
    public static final String[][] QUICK_ACTIONS = new String[][] {
        { "none", "None" },
        { Titan2ApiContract.ACT_MOUSE_SCROLL_UP, "Scroll up" },
        { Titan2ApiContract.ACT_MOUSE_SCROLL_DOWN, "Scroll down" },
        { Titan2ApiContract.ACT_MOUSE_LEFT, "Left click" },
        { Titan2ApiContract.ACT_MOUSE_RIGHT, "Right click" },
        { Titan2ApiContract.ACT_MOUSE_MIDDLE, "Middle click" },
        { "host:up", "Arrow up" },
        { "host:down", "Arrow down" },
        { "host:left", "Arrow left" },
        { "host:right", "Arrow right" },
        { "host:pageup", "Page up" },
        { "host:pagedown", "Page down" },
        { "host:esc", "Escape" },
        { "host:enter", "Enter" },
        { "host:tab", "Tab" },
        { "host:backspace", "Backspace" },
        { "host:ctrl+c", "Ctrl+C" },
        { "host:ctrl+v", "Ctrl+V" },
        { "host:ctrl+z", "Ctrl+Z" },
        { "host:alt+tab", "Alt+Tab" },
    };

    private SideKeyHostPrefs() {}

    private static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String get(Context c, String slot) {
        String def = defaultFor(slot);
        String v = p(c).getString(P + slot, def);
        return v == null || v.isEmpty() ? def : v;
    }

    public static void set(Context c, String slot, String action) {
        if (slot == null) return;
        if (action == null || action.isEmpty()) action = "none";
        p(c).edit().putString(P + slot, action).apply();
    }

    /** Product defaults: top scroll up, bottom scroll down (short only). */
    private static String defaultFor(String slot) {
        if (Titan2ApiContract.SLOT_SIDE2_SHORT.equals(slot)) {
            return Titan2ApiContract.ACT_MOUSE_SCROLL_UP;
        }
        if (Titan2ApiContract.SLOT_SIDE_SHORT.equals(slot)) {
            return Titan2ApiContract.ACT_MOUSE_SCROLL_DOWN;
        }
        return "none";
    }

    public static String labelOf(String action) {
        if (action == null || action.isEmpty() || "none".equals(action)) return "None";
        for (String[] row : QUICK_ACTIONS) {
            if (row[0].equals(action)) return row[1];
        }
        if (action.startsWith("host:")) return action.substring(5);
        if (action.startsWith("mouse:")) return action.substring(6);
        return action;
    }

    public static String nextQuick(String current) {
        int idx = 0;
        for (int i = 0; i < QUICK_ACTIONS.length; i++) {
            if (QUICK_ACTIONS[i][0].equals(current)) {
                idx = i;
                break;
            }
        }
        return QUICK_ACTIONS[(idx + 1) % QUICK_ACTIONS.length][0];
    }

    /** Slot → action map for {@link Titan2Client#pushTempKeyMap}. */
    public static Map<String, String> buildLayerMap(Context c) {
        Map<String, String> m = new LinkedHashMap<>();
        putIfBound(m, c, Titan2ApiContract.SLOT_SIDE_SHORT);
        putIfBound(m, c, Titan2ApiContract.SLOT_SIDE_LONG);
        putIfBound(m, c, Titan2ApiContract.SLOT_SIDE_DOUBLE);
        putIfBound(m, c, Titan2ApiContract.SLOT_SIDE2_SHORT);
        putIfBound(m, c, Titan2ApiContract.SLOT_SIDE2_LONG);
        putIfBound(m, c, Titan2ApiContract.SLOT_SIDE2_DOUBLE);
        return m;
    }

    private static void putIfBound(Map<String, String> m, Context c, String slot) {
        String a = get(c, slot);
        if (a != null && !a.isEmpty() && !"default".equals(a)) {
            m.put(slot, a);
        }
    }
}
