package com.titanus2.cubecontact;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * 1.59 residual: 1.58 AlarmManager chain + process guard fixed LMK/force-stop
 * while a PendingIntent survived, but LawSeedWakeService still {@code stop}s
 * after LAW promote — so SCREEN_ON / TIME_TICK multi-UID dim edges died with
 * the seed shell. Late polish / old /system cube-ux then re-crush get-dim to
 * 0.92 until the next ~3m alarm (or pad-agent loop). Permanent sticky shell:
 * never stops on promote; reasserts dim on screen-on + minute tick + idle-exit;
 * START_STICKY so soft kills restart without exclusive thrash / FGS.
 *
 * 1.60: onStartCommand arms exact dim chain (Doze ~9m setAndAllowWhileIdle hole
 * after process death when hung pad-agent never lands denser belt).
 *
 * 1.61 residual: under background-start DENY, sticky restart left
 * {@code app=null} with no live PendingIntent when armAlarm never re-ran.
 * onDestroy / onTaskRemoved re-arm dual exact+backup alarms so LMK leaves a
 * fireable chain without FGS notification thrash.
 *
 * 1.62 residual: service was non-exported — shell {@code am start-service}
 * after force-stop DENY'd ("not exported"); install health poke was false-OK
 * and stopped=true never cleared. Manifest exports this service (dim-only)
 * so pad-agent / install can unstop + restart sticky without open UI.
 *
 * 1.72 residual: 1.71 closed LawSeedWake sticky TCP under thermal, but this
 * shell still TIME_TICK every minute + armAlarm 30s exact under thermal severe
 * → 5× wallpaper Runtime.exec + CPU wake reheat residual. Receiver skips
 * TIME_TICK when {@link CubeStability#allowDimTick} is false; armAlarm uses
 * thermal-parked intervals (10m/15m). SCREEN_ON still reasserts mild dim once
 * (intent=result when human wakes display). Cool path unchanged.
 *
 * 1.74 residual: 1.73 parked delayed belt under thermal, but onStartCommand
 * still multi-UID applied (5× Runtime.exec) on every sticky restart under heat
 * + armGuard Handler reassert thrash. Under thermal: skip onStart apply + skip
 * armGuard (allowDimGuard); still arm parked alarms + register SCREEN_ON.
 *
 * 1.75 residual: dim parks also follow load≥8 ({@link CubeStability#isDimHeat})
 * so onStart/TIME_TICK do not Runtime.exec multi-UID under cool-lab load heat
 * before thermal SEVERE; apply() settings-only when allowDimMultiUid false.
 *
 * 1.76 residual: 1.75 parks + apply() settings-only still left onStart full
 * skip (allowDimGuard) and TIME_TICK early-return under dim heat → wallpaper_dim
 * plane unstamped mid-heat (same class as pad-agent 2.111 full skip before
 * 2.112 settings-only). Always apply() (settings-only under heat); park only
 * multi-UID Runtime.exec + armGuard Handler thrash. Cool path unchanged.
 */
public class DimGuardService extends Service {
    private static final Object LOCK = new Object();
    private static boolean sRegistered;
    private BroadcastReceiver mDim;

    /** Start sticky dim shell (idempotent). */
    public static void start(Context c) {
        if (c == null) return;
        try {
            Context app = c.getApplicationContext() != null ? c.getApplicationContext() : c;
            Intent i = new Intent(app, DimGuardService.class);
            app.startService(i);
        } catch (Exception ignored) {}
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            // 1.76: always stamp dim plane (settings-only under heat via
            // allowDimMultiUid). 1.74/1.75 full skip left plane unstamped on
            // sticky restart mid-heat; armGuard still parks Handler under heat.
            CubeSurfacePrefs.apply(this);
            CubeSurfacePrefs.armGuard(this);
            CubeSurfacePrefs.armAlarm(this);
        } catch (Exception ignored) {}
        registerDim();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        // 1.61: leave dual PendingIntent chain before process death.
        try { CubeSurfacePrefs.armAlarm(this); } catch (Exception ignored) {}
        unregisterDim();
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        try { CubeSurfacePrefs.armAlarm(this); } catch (Exception ignored) {}
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void registerDim() {
        synchronized (LOCK) {
            if (sRegistered) return;
            if (mDim == null) {
                mDim = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context c, Intent i) {
                        if (c == null) return;
                        final String action = i != null ? i.getAction() : null;
                        if (PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED.equals(action)) {
                            try {
                                PowerManager pm = (PowerManager)
                                    c.getSystemService(Context.POWER_SERVICE);
                                if (pm != null && pm.isDeviceIdleMode()) return;
                            } catch (Exception ignored) {}
                        }
                        final Context ctx = c.getApplicationContext() != null
                            ? c.getApplicationContext() : c;
                        // 1.72 residual: under thermal severe TIME_TICK every
                        // minute forked 5× wallpaper shell (cool-lab reheat).
                        // 1.76: under dim heat still settings-only apply (align
                        // pad-agent 2.112) — full skip left plane unstamped;
                        // park only multi-UID + armGuard/armAlarm rearm thrash.
                        if (Intent.ACTION_TIME_TICK.equals(action)) {
                            try {
                                if (!CubeStability.allowDimTick(ctx)) {
                                    CubeSurfacePrefs.apply(ctx);
                                    return;
                                }
                            } catch (Exception ignored) {}
                        }
                        try {
                            CubeSurfacePrefs.apply(ctx);
                            CubeSurfacePrefs.armGuard(ctx);
                            CubeSurfacePrefs.armAlarm(ctx);
                        } catch (Exception ignored) {}
                    }
                };
            }
            try {
                IntentFilter f = new IntentFilter();
                f.addAction(Intent.ACTION_SCREEN_ON);
                f.addAction(Intent.ACTION_TIME_TICK);
                if (Build.VERSION.SDK_INT >= 23) {
                    f.addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED);
                }
                registerReceiver(mDim, f);
                sRegistered = true;
            } catch (Exception ignored) {
                sRegistered = false;
            }
        }
    }

    private void unregisterDim() {
        synchronized (LOCK) {
            if (!sRegistered || mDim == null) {
                sRegistered = false;
                return;
            }
            try {
                unregisterReceiver(mDim);
            } catch (Exception ignored) {}
            sRegistered = false;
        }
    }
}
