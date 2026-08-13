package com.titanus2.cubecontact;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Public API surface for lattice source (pad-agent, peers, host tools).
 * <pre>
 * adb shell am broadcast -a com.titanus2.cubecontact.SET_MATRIX_SOURCE \
 *   --es plane rear --es source KERNEL
 * adb shell am broadcast -a com.titanus2.cubecontact.SET_MATRIX_SOURCE \
 *   --es plane front --es source CUSTOM --es seed "12345678..."
 * </pre>
 */
public final class MatrixSourceApi {
    public static final String ACTION_SET = "com.titanus2.cubecontact.SET_MATRIX_SOURCE";
    public static final String ACTION_GET = "com.titanus2.cubecontact.GET_MATRIX_SOURCE";
    public static final String EXTRA_PLANE = "plane";
    public static final String EXTRA_SOURCE = "source";
    public static final String EXTRA_SEED = "seed";
    public static final String EXTRA_AUTO_SPIN = "auto_spin";

    private static final String TAG = "MatrixSourceApi";

    private MatrixSourceApi() {}

    public static void apply(Context c, Intent intent) {
        if (c == null || intent == null) return;
        String plane = intent.getStringExtra(EXTRA_PLANE);
        if (plane == null) plane = CubePlanePrefs.PLANE_FRONT;
        String src = intent.getStringExtra(EXTRA_SOURCE);
        if (src != null && !src.isEmpty()) {
            CubePlanePrefs.setSource(c, plane, MatrixSource.fromKey(src));
        }
        if (intent.hasExtra(EXTRA_SEED)) {
            CubePlanePrefs.setCustomSeed(c, plane, intent.getStringExtra(EXTRA_SEED));
            if (src == null || src.isEmpty()) {
                CubePlanePrefs.setSource(c, plane, MatrixSource.CUSTOM);
            }
        }
        if (intent.hasExtra(EXTRA_AUTO_SPIN)) {
            CubePlanePrefs.setAutoSpin(c, plane, intent.getBooleanExtra(EXTRA_AUTO_SPIN, true));
        }
        Log.i(TAG, "applied plane=" + plane
            + " source=" + CubePlanePrefs.source(c, plane).name());
        // Nudge live views
        try {
            Intent poke = new Intent(ACTION_GET);
            poke.setPackage(c.getPackageName());
            poke.putExtra(EXTRA_PLANE, plane);
            poke.putExtra(EXTRA_SOURCE, CubePlanePrefs.source(c, plane).name());
            c.sendBroadcast(poke);
        } catch (Exception ignored) {}
    }

    public static final class Receiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            if (ACTION_SET.equals(intent.getAction())) {
                apply(context, intent);
            }
        }
    }
}
