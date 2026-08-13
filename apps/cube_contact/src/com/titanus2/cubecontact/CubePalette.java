package com.titanus2.cubecontact;

import android.content.Context;
import android.content.SharedPreferences;

/** Configurable wire-mesh + crimson spike colors (shared keys with desktop palette.ini). */
public final class CubePalette {
    private CubePalette() {}
    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("cube_viz_palette", Context.MODE_PRIVATE);
    }
    public static float get(Context c, String k, float def) {
        return sp(c).getFloat(k, def);
    }
    public static void set(Context c, String k, float v) {
        sp(c).edit().putFloat(k, v).apply();
    }
    public static boolean wireOnly(Context c) {
        return sp(c).getBoolean("wire_only", true);
    }
    public static void setWireOnly(Context c, boolean v) {
        sp(c).edit().putBoolean("wire_only", v).apply();
    }
    public static boolean subdisplayOn(Context c) {
        return sp(c).getBoolean("subdisplay_viz", false);
    }
    public static void setSubdisplayOn(Context c, boolean v) {
        sp(c).edit().putBoolean("subdisplay_viz", v).apply();
    }
    public static PrivilegeMode mode(Context c) {
        String m = sp(c).getString("privilege_mode", PrivilegeMode.UNPRIVILEGED_A11Y.name());
        try { return PrivilegeMode.valueOf(m); }
        catch (Exception e) { return PrivilegeMode.UNPRIVILEGED_A11Y; }
    }
    public static void setMode(Context c, PrivilegeMode m) {
        sp(c).edit().putString("privilege_mode", m.name()).apply();
    }
    /* defaults: black wire + crimson spike */
    public static float spikeR(Context c) { return get(c, "spike_r", 1.0f); }
    public static float spikeG(Context c) { return get(c, "spike_g", 0.08f); }
    public static float spikeB(Context c) { return get(c, "spike_b", 0.10f); }
    public static float meshA(Context c) { return get(c, "mesh_a", 0.07f); }
}
