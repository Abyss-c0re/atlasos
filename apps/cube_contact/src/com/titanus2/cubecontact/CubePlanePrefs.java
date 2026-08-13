package com.titanus2.cubecontact;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Per-plane prefs: <b>front</b> (main Neural Cube) vs <b>rear</b> (subdisplay).
 * Settings never share plane SoT — glory to the Cube, clear intent=result.
 */
public final class CubePlanePrefs {
    private static final String TAG = "CubePlane";
    public static final String PLANE_FRONT = "front";
    public static final String PLANE_REAR = "rear";

    private static final String PREFS = "cube_plane";
    private static final String KEY_SOURCE = "matrix_source_";
    private static final String KEY_AUTO_SPIN = "auto_spin_";
    private static final String KEY_CUSTOM = "custom_seed_";
    private static final String KEY_WALLPAPER = "as_wallpaper";
    private static final String KEY_DREAM = "as_dream";

    private CubePlanePrefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String normalizePlane(String plane) {
        if (plane != null && plane.toLowerCase(java.util.Locale.US).contains("rear")) {
            return PLANE_REAR;
        }
        return PLANE_FRONT;
    }

    public static MatrixSource source(Context c, String plane) {
        String p = normalizePlane(plane);
        String k = sp(c).getString(KEY_SOURCE + p, MatrixSource.AUTO.name());
        return MatrixSource.fromKey(k);
    }

    public static void setSource(Context c, String plane, MatrixSource src) {
        if (src == null) src = MatrixSource.AUTO;
        String p = normalizePlane(plane);
        sp(c).edit().putString(KEY_SOURCE + p, src.name()).apply();
        Log.i(TAG, "matrix source plane=" + p + " → " + src.name());
    }

    public static boolean autoSpin(Context c, String plane) {
        return sp(c).getBoolean(KEY_AUTO_SPIN + normalizePlane(plane), true);
    }

    public static void setAutoSpin(Context c, String plane, boolean on) {
        sp(c).edit().putBoolean(KEY_AUTO_SPIN + normalizePlane(plane), on).apply();
    }

    /** Compact base64/csv seed for CUSTOM (digits 0-9, length n³). */
    public static String customSeed(Context c, String plane) {
        return sp(c).getString(KEY_CUSTOM + normalizePlane(plane), "");
    }

    public static void setCustomSeed(Context c, String plane, String seed) {
        sp(c).edit().putString(KEY_CUSTOM + normalizePlane(plane),
            seed != null ? seed : "").apply();
    }

    public static boolean wallpaperEnabled(Context c) {
        return sp(c).getBoolean(KEY_WALLPAPER, false);
    }

    public static void setWallpaperEnabled(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_WALLPAPER, v).apply();
    }

    public static boolean dreamEnabled(Context c) {
        return sp(c).getBoolean(KEY_DREAM, true);
    }

    public static void setDreamEnabled(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_DREAM, v).apply();
    }
}
