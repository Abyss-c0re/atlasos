package com.titanus2.cubecontact;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

/**
 * 1.58 residual: AlarmManager dim reassert after process death.
 * 1.57 Handler guard only lived while CubeContact process was warm —
 * LMK / force-stop / package replace left late polish / old cube-ux free
 * to re-crush get-dim to 0.92. Fire apply + re-chain armAlarm (single
 * setAndAllowWhileIdle slot, LAW rearm Doze SoT).
 *
 * 1.59: onAlarmFire also starts DimGuardService sticky shell so
 * SCREEN_ON/TIME_TICK edges return after LawSeedWake post-promote stop.
 *
 * 1.60: armAlarm prefers setExactAndAllowWhileIdle (30s) so Doze cannot
 * defer reassert ~9m after process death.
 *
 * 1.61: dual exact+backup PendingIntent fire the same ACTION; pad-agent /
 * install explicit component broadcast re-wakes package when service
 * app=null (background-start DENY residual) without exclusive thrash.
 *
 * 1.62 residual: force-stop left stopped=true; non-exported DimGuard blocked
 * shell start-service; sync onReceive finished before multi-UID dim exec +
 * armAlarm + sticky start, so process died with pi_cancelled and no live
 * chain. goAsync holds process ~2.5s for full onAlarmFire; exported service
 * lets pad-agent/install am start-service unstop package.
 */
public class DimGuardReceiver extends BroadcastReceiver {
    public static final String ACTION = "com.titanus2.cubecontact.DIM_GUARD";

    @Override
    public void onReceive(Context c, Intent i) {
        if (c == null) return;
        final PendingResult pr;
        try {
            pr = goAsync();
        } catch (Exception e) {
            try {
                CubeSurfacePrefs.onAlarmFire(c);
            } catch (Exception ignored) {}
            return;
        }
        final Context app;
        try {
            Context a = c.getApplicationContext();
            app = a != null ? a : c;
        } catch (Exception e) {
            try { pr.finish(); } catch (Exception ignored) {}
            return;
        }
        // Off main so multi-UID cmd wallpaper exec cannot ANR the broadcast.
        Thread t = new Thread(() -> {
            try {
                CubeSurfacePrefs.onAlarmFire(app);
            } catch (Exception ignored) {
            } finally {
                try {
                    // Short hold so sticky DimGuardService attaches before finish.
                    Thread.sleep(400);
                } catch (Exception ignored) {}
                try {
                    pr.finish();
                } catch (Exception ignored) {}
            }
        }, "cube-dim-guard-rx");
        t.setDaemon(true);
        try {
            t.start();
        } catch (Exception e) {
            try {
                CubeSurfacePrefs.onAlarmFire(app);
            } catch (Exception ignored) {}
            try {
                pr.finish();
            } catch (Exception ignored) {}
            return;
        }
        // Failsafe finish if worker hangs (never leave goAsync forever).
        try {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try { pr.finish(); } catch (Exception ignored) {}
            }, 2500L);
        } catch (Exception ignored) {}
    }
}
