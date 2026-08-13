package com.titanus2.controls.subdisplay;

import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;

/**
 * Launch / dismiss Cube Contact rear lattice when Sub display mode = Cube.
 * Launch is throttled — repeated startActivity on every service tick steals
 * focus and makes the hardware keyboard thrash.
 */
public final class SubDisplayCubeBridge {
    private static final String TAG = "SubDisplayCube";
    public static final String CUBE_PKG = SubDisplayPrefs.CUBE_PKG;
    public static final String REAR_ACTIVITY = "com.titanus2.cubecontact.RearCubeActivity";
    public static final String ACTION_DISMISS = "com.titanus2.cubecontact.REAR_DISMISS";
    /** Min gap between launch attempts (ms). Tick/watchdog only re-assert power. */
    private static final long RELAUNCH_MS = 45_000L;
    private static volatile long sLastLaunchElapsed;
    private static volatile boolean sWanted;

    private SubDisplayCubeBridge() {}

    /** First show or after dismiss; safe to call often. */
    public static void show(Context c) {
        show(c, false);
    }

    /**
     * @param force true when user just selected Cube mode (always launch once).
     */
    public static void show(Context c, boolean force) {
        if (c == null || !SubDisplayPrefs.cubeAppInstalled(c)) return;
        sWanted = true;
        Context app = c.getApplicationContext();
        // Sacred: strip Face/AOD chrome before (and when throttling) cube launch.
        try {
            SubDisplayFaceOverlay.hide(app);
            SubDisplayFaceActivity.dismiss(app);
        } catch (Exception ignored) {}
        try {
            android.provider.Settings.Secure.putInt(app.getContentResolver(),
                SubDisplayContract.KEY_SUPPRESS_SYSUI_AOD, 1);
            android.provider.Settings.Secure.putInt(app.getContentResolver(),
                "doze_always_on", 0);
            android.provider.Settings.Global.putString(app.getContentResolver(),
                "titan2_sub_mode", "cube");
            android.provider.Settings.Secure.putString(app.getContentResolver(),
                "titan2_sub_mode", "cube");
            // Live digitizer for cube gestures (pad-agent associates sub_touch→rear).
            android.provider.Settings.Global.putString(app.getContentResolver(),
                "titan2_subtouch_inhibit", "0");
            android.provider.Settings.Global.putString(app.getContentResolver(),
                "titan2_subtouch_assoc", "pending");
            try {
                java.io.File f = new java.io.File("/data/local/tmp/titan2_sub_mode");
                java.io.FileWriter w = new java.io.FileWriter(f);
                w.write("cube");
                w.close();
            } catch (Exception ignored2) {}
            try {
                SubDisplayService.applySubtouchPolicy(app);
            } catch (Exception ignored2) {}
        } catch (Exception ignored) {}
        long now = SystemClock.elapsedRealtime();
        if (!force && sLastLaunchElapsed > 0 && (now - sLastLaunchElapsed) < RELAUNCH_MS) {
            return;
        }
        // Never startActivity while main is asleep — wakes shared power group.
        try {
            android.os.PowerManager pm =
                (android.os.PowerManager) app.getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isInteractive() && !force) {
                Log.i(TAG, "skip launch: main asleep (no dual-light)");
                return;
            }
            if (pm != null && !pm.isInteractive() && force) {
                // Forced but main asleep (e.g. boot edge): still refuse — user
                // must open cube while interactive once; HW rear keeps glowing.
                Log.w(TAG, "refuse force launch while main asleep");
                return;
            }
        } catch (Exception ignored) {}
        Display rear = SubDisplayHelper.findRear(app);
        if (rear == null) {
            Log.w(TAG, "no rear display");
            return;
        }
        try {
            Intent i = new Intent();
            i.setComponent(new ComponentName(CUBE_PKG, REAR_ACTIVITY));
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                | Intent.FLAG_ACTIVITY_NO_ANIMATION
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
            // Show over keyguard on rear; do not request main wake.
            if (Build.VERSION.SDK_INT < 27) {
                // legacy: activity itself sets SHOW_WHEN_LOCKED
            }
            if (Build.VERSION.SDK_INT >= 26) {
                ActivityOptions opts = ActivityOptions.makeBasic();
                opts.setLaunchDisplayId(rear.getDisplayId());
                app.startActivity(i, opts.toBundle());
                Log.i(TAG, "launch cube rear displayId=" + rear.getDisplayId()
                    + (force ? " force" : "") + " (no clocks)");
            } else {
                app.startActivity(i);
            }
            sLastLaunchElapsed = now;
        } catch (Exception e) {
            Log.w(TAG, "show: " + e.getMessage());
        }
    }

    public static void dismiss(Context c) {
        sWanted = false;
        sLastLaunchElapsed = 0;
        if (c == null) return;
        try {
            Intent i = new Intent(ACTION_DISMISS);
            i.setPackage(CUBE_PKG);
            c.getApplicationContext().sendBroadcast(i);
        } catch (Exception e) {
            Log.w(TAG, "dismiss: " + e.getMessage());
        }
    }

    public static boolean isWanted() { return sWanted; }
}
