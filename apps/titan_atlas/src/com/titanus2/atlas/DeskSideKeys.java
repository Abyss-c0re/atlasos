package com.titanus2.atlas;

import android.content.Context;

import com.titanus2.api.Titan2ApiContract;
import com.titanus2.api.Titan2Client;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Side keys are a Titan Controls per-app profile for Atlas.
 * While the desk is open that profile is pushed as
 * {@link Titan2ApiContract#LAYER_ATLAS_DESK}. Other apps keep their maps.
 */
public final class DeskSideKeys {
    public static final String[][] CHOICES = {
        { Titan2ApiContract.ACT_MOUSE_SCROLL_UP, "Scroll up" },
        { Titan2ApiContract.ACT_MOUSE_SCROLL_DOWN, "Scroll down" },
        { "none", "None" },
        { Titan2ApiContract.ACT_MOUSE_LEFT, "Left click" },
        { Titan2ApiContract.ACT_MOUSE_RIGHT, "Right click" },
        { Titan2ApiContract.ACT_MOUSE_MIDDLE, "Middle click" },
    };

    private DeskSideKeys() {}

    /** Seed the Atlas profile once. Does not rewrite the global key map. */
    public static void ensureScrollDefault(Context c) {
        Titan2Client api = client(c);
        api.ensureKeymapProfile(Titan2ApiContract.ATLAS_PKG, "Atlas desk");
        Map<String, String> have = api.getProfileMap(Titan2ApiContract.ATLAS_PKG);
        if (!have.containsKey(Titan2ApiContract.SLOT_SIDE2_SHORT)) {
            api.setKeyAction(Titan2ApiContract.SLOT_SIDE2_SHORT,
                Titan2ApiContract.ACT_MOUSE_SCROLL_UP, Titan2ApiContract.ATLAS_PKG);
        }
        if (!have.containsKey(Titan2ApiContract.SLOT_SIDE_SHORT)) {
            api.setKeyAction(Titan2ApiContract.SLOT_SIDE_SHORT,
                Titan2ApiContract.ACT_MOUSE_SCROLL_DOWN, Titan2ApiContract.ATLAS_PKG);
        }
    }

    /** Desk is in front. Controls applies the Atlas profile above other apps. */
    public static void publish(Context c) {
        ensureScrollDefault(c);
        Titan2Client api = client(c);
        Map<String, String> map = new LinkedHashMap<>();
        map.put(Titan2ApiContract.SLOT_SIDE2_SHORT,
            action(c, Titan2ApiContract.SLOT_SIDE2_SHORT));
        map.put(Titan2ApiContract.SLOT_SIDE_SHORT,
            action(c, Titan2ApiContract.SLOT_SIDE_SHORT));
        api.pushTempKeyMap(Titan2ApiContract.LAYER_ATLAS_DESK, map);
    }

    public static void release(Context c) {
        client(c).popTempKeyMap(Titan2ApiContract.LAYER_ATLAS_DESK);
    }

    public static String action(Context c, String slot) {
        Map<String, String> map = client(c).getProfileMap(Titan2ApiContract.ATLAS_PKG);
        String v = map.get(slot);
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

    public static boolean set(Context c, String slot, String action) {
        Titan2Client api = client(c);
        api.ensureKeymapProfile(Titan2ApiContract.ATLAS_PKG, "Atlas desk");
        return api.setKeyAction(slot, action, Titan2ApiContract.ATLAS_PKG);
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

    private static Titan2Client client(Context c) {
        return new Titan2Client(c);
    }
}
