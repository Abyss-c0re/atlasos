package com.titanus2.controls;

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.util.Log;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Camera / microphone privacy helpers (PRIVACY_POLICY §2.2).
 * <p>
 * <b>Product (13.95+): block access only — never force-stop the requesting app.</b>
 * Stock {@code cameratoggle}/{@code mictoggle} set AppOps restrictions; hybrid
 * init belt {@code titan2-sensor-privacy} revokes {@code /dev/video*} and capture
 * PCM nodes. The app stays alive; capture fails closed (black / silence / error).
 * <p>
 * <b>13.97-cam-resync:</b> Secure plane written first (snappy hub/QS); SPM
 * reflection runs in background. Hub allow clears {@code titan2_private_mode}.
 * Belt may bounce {@code cameraserver} on privacy edge so HAL resyncs nodes
 * (apps are not force-stopped).
 * <p>
 * <b>13.98-fail-closed-stock:</b> reassert never clears Secure when framework
 * privacy is unreadable (null query). Stock cameratoggle ON must re-mirror
 * Secure=1 so hybrid belt revokes nodes (v9 Secure=0 win residual). Belt 1.4
 * also ORs dumpsys ON independent of Secure=0.
 * <p>
 * <b>13.99-fail-closed-allow:</b> hub allow must not write Secure=0 before
 * framework privacy is OFF. v9 belt treats Secure=0 as permanent allow and
 * skips dumpsys — {@code /dev/video*} opened while SPM still ENABLED (fail-open
 * residual after 13.98). Block path still writes Secure first (snappy revoke).
 * <p>
 * Historical force-stop belt removed: it looked like random crashes and fought
 * the stock QS chrome. Do not reintroduce {@code forceStopPackage} here.
 */
public final class SensorPrivacyEnforcer {
    private static final String TAG = "SensorPrivacy";

    /** Matches {@link android.hardware.SensorPrivacyManager.Sensors#MICROPHONE}. */
    public static final int SENSOR_MICROPHONE = 1;
    /** Matches {@link android.hardware.SensorPrivacyManager.Sensors#CAMERA}. */
    public static final int SENSOR_CAMERA = 2;

    public static final String PREFS = "titan2_sensor_privacy";
    public static final String KEY_CAMERA_BLOCKED = "camera_blocked";
    public static final String KEY_MIC_BLOCKED = "mic_blocked";

    /** Settings.Secure plane — 1 = blocked (privacy on). Station/agents read these. */
    public static final String SECURE_CAMERA = "titan2_sensor_privacy_camera";
    public static final String SECURE_MIC = "titan2_sensor_privacy_microphone";

    public static final String ACTION_CHANGED = "com.titanus2.controls.SENSOR_PRIVACY_CHANGED";
    public static final String EXTRA_SENSOR = "sensor";
    public static final String EXTRA_BLOCKED = "blocked";

    /** Packages we never touch even in legacy scan helpers. */
    private static final String[] NEVER_FORCE_STOP = {
        "android",
        "com.android.systemui",
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.bluetooth",
        "com.titanus2.controls",
    };

    /** 13.95: stock toggle observer only (no app force-stop). */
    private static final AtomicBoolean STOCK_HOOK_INSTALLED = new AtomicBoolean(false);
    private static volatile Object stockHookListener;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile long lastEnforceCamElapsed;
    private static volatile long lastEnforceMicElapsed;
    /** Free-flow: do not 1.5s-debounce human privacy toggles. */
    private static final long ENFORCE_MIN_MS = 50L;
    private static volatile long lastReassertCamElapsed;
    private static volatile long lastReassertMicElapsed;
    private static final long REASSERT_MIN_MS = 80L;

    private SensorPrivacyEnforcer() {}

    public static boolean isCameraBlocked(Context ctx) {
        return isBlocked(ctx, SENSOR_CAMERA);
    }

    public static boolean isMicBlocked(Context ctx) {
        return isBlocked(ctx, SENSOR_MICROPHONE);
    }

    /**
     * Live privacy state for UI + kill path.
     * <p>
     * <b>13.94:</b> stock {@code cameratoggle}/{@code mictoggle} are SoT.
     * Never treat stale prefs/Secure alone as blocked — that force-stopped
     * camera apps every 2s while framework privacy was OFF (cam "broken",
     * QS tile looked dead). Prefer framework toggle → global AppOps canary.
     */
    public static boolean isBlocked(Context ctx, int sensor) {
        if (ctx == null) return false;
        Boolean live = queryToggleEnabled(ctx, sensor);
        if (live != null) return live;
        // Secure plane (set first on toggle) — snappy hub UI without SPM.
        if (sensor == SENSOR_CAMERA) return readSecureBlocked(ctx, SECURE_CAMERA);
        if (sensor == SENSOR_MICROPHONE) return readSecureBlocked(ctx, SECURE_MIC);
        return false;
    }

    /**
     * True only when stock sensor privacy is ON and readable.
     * Unreadable → false (do not guess).
     */
    public static boolean isFrameworkPrivacyOn(Context ctx, int sensor) {
        if (ctx == null) return false;
        Boolean live = queryToggleEnabled(ctx, sensor);
        return live != null && live;
    }

    private static void clearStaleBlockedPlane(Context ctx, int sensor) {
        try {
            SharedPreferences p = prefs(ctx);
            if (sensor == SENSOR_CAMERA) {
                if (p.getBoolean(KEY_CAMERA_BLOCKED, false)
                        || readSecureBlocked(ctx, SECURE_CAMERA)) {
                    persist(ctx, SENSOR_CAMERA, false);
                    Log.i(TAG, "cleared stale camera blocked plane (framework off)");
                }
            } else if (sensor == SENSOR_MICROPHONE) {
                if (p.getBoolean(KEY_MIC_BLOCKED, false)
                        || readSecureBlocked(ctx, SECURE_MIC)) {
                    persist(ctx, SENSOR_MICROPHONE, false);
                    Log.i(TAG, "cleared stale mic blocked plane (framework off)");
                }
            }
        } catch (Throwable ignored) {}
    }

    /** Secure plane 1 = blocked (fail-closed); missing/0 = allow. */
    private static boolean readSecureBlocked(Context ctx, String key) {
        try {
            return Settings.Secure.getInt(ctx.getContentResolver(), key, 0) == 1;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * @param blocked true = sensor privacy ON (capture must fail closed)
     * @return true if at least one enforcement layer reported success
     */
    public static boolean setBlocked(Context ctx, int sensor, boolean blocked) {
        if (ctx == null) return false;
        if (sensor != SENSOR_CAMERA && sensor != SENSOR_MICROPHONE) return false;
        Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;

        final Context appF = app;
        final int sensorF = sensor;
        final boolean blockedF = blocked;

        if (blockedF) {
            // Secure plane FIRST — snappy UI / belt desire.
            persist(app, sensor, true);
            broadcast(app, sensor, true);
            // CubalC free-flow: Linux nodes NOW (not wait INTERVAL_S for belt).
            ImpulseSnap.wakeSensorBelt();
            if (sensorF == SENSOR_CAMERA) {
                ImpulseSnap.suAsync(
                        "for n in /dev/video*; do [ -e \"$n\" ] && chmod 000 \"$n\" 2>/dev/null; done; "
                                + "for n in /dev/video*; do fuser -k \"$n\" 2>/dev/null; done; "
                                + "for n in /dev/video*; do [ -e \"$n\" ] && chmod 000 \"$n\" 2>/dev/null; done; true");
            } else {
                ImpulseSnap.micBlock();
            }
            // Background: SPM + AppOps + all-sensor pulse — apps must fail closed.
            new Thread(() -> {
                boolean ok = setTogglePrivacy(appF, sensorF, true);
                boolean ao = setAppOpsSensorIgnored(appF, sensorF, true);
                if (sensorF == SENSOR_CAMERA) {
                    pulseAllSensorPrivacyBlock(appF);
                    ImpulseSnap.camBlock(); // re-assert after fuser
                }
                Log.i(TAG, "setBlocked sensor=" + sensorName(sensorF)
                    + " blocked=true spm=" + ok + " appops=" + ao
                    + " (impulse + Secure + SPM + AppOps fail-closed)");
            }, "titan2-sp-set").start();
            return true;
        }

        // 13.99 fail-closed allow: never clear Secure until framework is OFF.
        // v9 belt Secure=0 win left /dev/video* open while SPM still ENABLED.
        // Prefs track hub intent; Secure stays 1 until confirm (hub may snap).
        SharedPreferences.Editor ed = prefs(app).edit();
        if (sensor == SENSOR_CAMERA) {
            ed.putBoolean(KEY_CAMERA_BLOCKED, false);
        } else {
            ed.putBoolean(KEY_MIC_BLOCKED, false);
        }
        ed.apply();
        // Do not broadcast allow yet — UI reads Secure; fail-closed until SPM OFF.

        ImpulseSnap.wakeSensorBelt();
        new Thread(() -> {
            boolean ok = setTogglePrivacy(appF, sensorF, false);
            setAppOpsSensorIgnored(appF, sensorF, false);
            if (ok) {
                setAllSensorPrivacy(appF, false);
            }
            // Re-query: stock QS may still hold privacy ON even if reflection "ok".
            Boolean fw = queryToggleEnabled(appF, sensorF);
            if (fw != null && fw) {
                // Framework still blocked — keep Secure=1 (constitution fail-closed).
                writeSecure(appF, sensorF == SENSOR_CAMERA ? SECURE_CAMERA : SECURE_MIC, true);
                SharedPreferences.Editor keep = prefs(appF).edit();
                if (sensorF == SENSOR_CAMERA) {
                    keep.putBoolean(KEY_CAMERA_BLOCKED, true);
                } else {
                    keep.putBoolean(KEY_MIC_BLOCKED, true);
                }
                keep.apply();
                broadcast(appF, sensorF, true);
                Log.w(TAG, "setBlocked allow refused (framework still ON) sensor="
                    + sensorName(sensorF) + " keep Secure=1");
                return;
            }
            if (!ok && fw == null) {
                // HERESY residual: clearing Secure on null query fail-opened silicon
                // while dumpsys still state_type=1 (framework privacy ON).
                // Constitution §2: unreadable → fail closed. Keep Secure=1.
                writeSecure(appF, sensorF == SENSOR_CAMERA ? SECURE_CAMERA : SECURE_MIC, true);
                SharedPreferences.Editor keep = prefs(appF).edit();
                if (sensorF == SENSOR_CAMERA) {
                    keep.putBoolean(KEY_CAMERA_BLOCKED, true);
                } else {
                    keep.putBoolean(KEY_MIC_BLOCKED, true);
                }
                keep.apply();
                broadcast(appF, sensorF, true);
                Log.w(TAG, "setBlocked allow REFUSED (null query fail-closed) sensor="
                    + sensorName(sensorF) + " keep Secure=1");
                return;
            }
            // Framework OFF — clear Secure + impulse restore nodes.
            writeSecure(appF, sensorF == SENSOR_CAMERA ? SECURE_CAMERA : SECURE_MIC, false);
            try {
                Settings.Secure.putInt(appF.getContentResolver(),
                    "titan2_private_mode", 0);
            } catch (Throwable ignored) {}
            if (sensorF == SENSOR_CAMERA) ImpulseSnap.camAllow();
            else ImpulseSnap.micAllow();
            broadcast(appF, sensorF, false);
            Log.i(TAG, "setBlocked sensor=" + sensorName(sensorF)
                + " blocked=false ok=" + ok + " fw=" + fw + " (impulse allow)");
        }, "titan2-sp-set").start();
        return true;
    }

    /** Boot / package-replace: arm stock fail-closed; heal stale plane. */
    public static void restore(Context ctx) {
        if (ctx == null) return;
        Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        // Always arm stock QS fail-closed hook (idempotent).
        installStockToggleHook(app);
        // 13.94: do NOT re-apply prefs as setBlocked(true) — that fought stock
        // cameratoggle and left Secure=1 forever while force-stopping cams.
        // 13.98: mirror framework only when readable; null query never clears
        // Secure (fail-open residual while dumpsys still ENABLED).
        Boolean camFw = queryToggleEnabled(app, SENSOR_CAMERA);
        Boolean micFw = queryToggleEnabled(app, SENSOR_MICROPHONE);
        if (camFw != null && camFw) {
            onFrameworkPrivacyEnabled(app, SENSOR_CAMERA);
            writeSecure(app, SECURE_CAMERA, true);
        } else if (camFw != null) {
            clearStaleBlockedPlane(app, SENSOR_CAMERA);
            writeSecure(app, SECURE_CAMERA, false);
        }
        if (micFw != null && micFw) {
            onFrameworkPrivacyEnabled(app, SENSOR_MICROPHONE);
            writeSecure(app, SECURE_MIC, true);
        } else if (micFw != null) {
            clearStaleBlockedPlane(app, SENSOR_MICROPHONE);
            writeSecure(app, SECURE_MIC, false);
        }
        Log.i(TAG, "restore framework cam=" + camFw + " mic=" + micFw);
    }

    /**
     * Observe stock {@code cameratoggle}/{@code mictoggle} so Secure plane stays
     * coherent. Does <b>not</b> force-stop apps — access block only (13.95).
     * Hybrid root belt revokes device nodes when privacy is ON.
     */
    public static boolean installStockToggleHook(Context ctx) {
        if (ctx == null) return false;
        final Context app = ctx.getApplicationContext() != null
            ? ctx.getApplicationContext() : ctx;
        if (STOCK_HOOK_INSTALLED.get() && stockHookListener != null) {
            return true;
        }
        Object spm = sensorPrivacyManager(app);
        if (spm == null) {
            Log.w(TAG, "stock hook: no SensorPrivacyManager");
            return false;
        }
        try {
            Class<?> listenerCl = Class.forName(
                "android.hardware.SensorPrivacyManager$OnSensorPrivacyChangedListener");
            Object listener = Proxy.newProxyInstance(
                listenerCl.getClassLoader(),
                new Class<?>[] { listenerCl },
                new StockPrivacyInvocationHandler(app));
            // Prefer addSensorPrivacyListener(Executor, Listener) / (Listener) / per-sensor.
            boolean added = false;
            Throwable last = null;
            for (Method m : spm.getClass().getMethods()) {
                if (!"addSensorPrivacyListener".equals(m.getName())) continue;
                Class<?>[] pt = m.getParameterTypes();
                try {
                    if (pt.length == 2
                            && java.util.concurrent.Executor.class.isAssignableFrom(pt[0])
                            && listenerCl.isAssignableFrom(pt[1])) {
                        m.invoke(spm, app.getMainExecutor(), listener);
                        added = true;
                        break;
                    }
                    if (pt.length == 1 && listenerCl.isAssignableFrom(pt[0])) {
                        m.invoke(spm, listener);
                        added = true;
                        break;
                    }
                    if (pt.length == 2
                            && (pt[0] == int.class || pt[0] == Integer.class)
                            && listenerCl.isAssignableFrom(pt[1])) {
                        m.invoke(spm, SENSOR_CAMERA, listener);
                        m.invoke(spm, SENSOR_MICROPHONE, listener);
                        added = true;
                        break;
                    }
                    if (pt.length == 3
                            && (pt[0] == int.class || pt[0] == Integer.class)
                            && java.util.concurrent.Executor.class.isAssignableFrom(pt[1])
                            && listenerCl.isAssignableFrom(pt[2])) {
                        m.invoke(spm, SENSOR_CAMERA, app.getMainExecutor(), listener);
                        m.invoke(spm, SENSOR_MICROPHONE, app.getMainExecutor(), listener);
                        added = true;
                        break;
                    }
                } catch (Throwable t) {
                    last = t;
                    // try next overload
                }
            }
            if (!added) {
                // AppOps fallback: privacy-on sets OP_CAMERA/RECORD_AUDIO to ignored.
                // Works with GET_APP_OPS_STATS when OBSERVE_SENSOR_PRIVACY is denied.
                if (installAppOpsPrivacyWatch(app)) {
                    STOCK_HOOK_INSTALLED.set(true);
                    Log.i(TAG, "stock fail-closed via AppOps watch"
                        + (last != null ? " (SPM add: " + rootMsg(last) + ")" : ""));
                    MAIN.post(() -> enforceIfFrameworkBlocked(app));
                    return true;
                }
                Log.w(TAG, "stock hook add failed: " + rootMsg(last));
                return false;
            }
            stockHookListener = listener;
            STOCK_HOOK_INSTALLED.set(true);
            Log.i(TAG, "stock cameratoggle/mictoggle fail-closed hook installed (SPM)");
            // Catch already-blocked state after install (stock left privacy on).
            MAIN.post(() -> enforceIfFrameworkBlocked(app));
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "stock hook: " + rootMsg(t));
            // Last-chance AppOps path
            try {
                if (installAppOpsPrivacyWatch(app)) {
                    STOCK_HOOK_INSTALLED.set(true);
                    Log.i(TAG, "stock fail-closed via AppOps watch after SPM error");
                    return true;
                }
            } catch (Throwable ignored) {}
            return false;
        }
    }

    private static String rootMsg(Throwable t) {
        if (t == null) return "null";
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        String m = c.getMessage();
        return c.getClass().getSimpleName() + (m != null ? (": " + m) : "");
    }

    /**
     * 13.93 fail-closed: stock QS sets AppOps CAMERA/MIC to IGNORED (dialog) but
     * leaves open Camera2 sessions streaming. We do <b>not</b> need
     * MANAGE_SENSOR_PRIVACY may be denied rootless; plane sync only (no kill).
     * When any package notes camera/mic and mode is IGNORED → force-stop that
     * package immediately (closes live feed under unlock dialog).
     */
    private static boolean installAppOpsPrivacyWatch(Context app) {
        try {
            final AppOpsManager aom = app.getSystemService(AppOpsManager.class);
            if (aom == null) return false;
            AppOpsManager.OnOpChangedListener cam = (op, packageName) ->
                MAIN.post(() -> failClosedKillIfIgnored(app, AppOpsManager.OPSTR_CAMERA,
                    packageName, SENSOR_CAMERA));
            AppOpsManager.OnOpChangedListener mic = (op, packageName) ->
                MAIN.post(() -> failClosedKillIfIgnored(app, AppOpsManager.OPSTR_RECORD_AUDIO,
                    packageName, SENSOR_MICROPHONE));
            aom.startWatchingMode(AppOpsManager.OPSTR_CAMERA, null, cam);
            aom.startWatchingMode(AppOpsManager.OPSTR_RECORD_AUDIO, null, mic);
            // API 30+: when camera becomes *active*, kill if privacy ignored
            try {
                if (Build.VERSION.SDK_INT >= 30) {
                    Method swa = AppOpsManager.class.getMethod(
                        "startWatchingActive",
                        String[].class,
                        java.util.concurrent.Executor.class,
                        Class.forName("android.app.AppOpsManager$OnOpActiveChangedListener"));
                    Class<?> activeCl = Class.forName(
                        "android.app.AppOpsManager$OnOpActiveChangedListener");
                    Object activeCam = Proxy.newProxyInstance(
                        activeCl.getClassLoader(),
                        new Class<?>[] { activeCl },
                        (proxy, method, args) -> {
                            if ("onOpActiveChanged".equals(method.getName())
                                    && args != null && args.length >= 4
                                    && Boolean.TRUE.equals(args[3])) {
                                String pkg = args[2] instanceof String
                                    ? (String) args[2] : null;
                                MAIN.post(() -> failClosedKillIfIgnored(app,
                                    AppOpsManager.OPSTR_CAMERA, pkg, SENSOR_CAMERA));
                            }
                            return null;
                        });
                    swa.invoke(aom, (Object) new String[] { AppOpsManager.OPSTR_CAMERA },
                        app.getMainExecutor(), activeCam);
                    Log.i(TAG, "AppOps active watch installed for CAMERA");
                }
            } catch (Throwable t) {
                Log.w(TAG, "startWatchingActive: " + rootMsg(t));
            }
            stockHookListener = new Object[] { cam, mic };
            // Only sweep when SPM is readable and privacy is ON
            MAIN.post(() -> {
                if (isFrameworkPrivacyOn(app, SENSOR_CAMERA)) {
                    killAllIgnoredSensorUsers(app, SENSOR_CAMERA);
                }
                if (isFrameworkPrivacyOn(app, SENSOR_MICROPHONE)) {
                    killAllIgnoredSensorUsers(app, SENSOR_MICROPHONE);
                }
            });
            // 13.94.2: no 2s kill loop. Without readable SPM, hybrid root belt
            // owns fail-closed; AppOps OnOpChanged still fires on real edges.
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "AppOps privacy watch: " + rootMsg(t));
            return false;
        }
    }

    /**
     * Privacy dialog case: AppOps / stock toggle flipped ON for package.
     * 13.95: persist plane only — never force-stop the requesting app.
     */
    private static void failClosedKillIfIgnored(
            Context app, String opstr, String packageName, int sensor) {
        if (packageName == null || packageName.isEmpty()) {
            // Global flip — sweep all
            killAllIgnoredSensorUsers(app, sensor);
            return;
        }
        if (shouldNeverForceStop(packageName)) return;
        // Prefer SPM when readable. AppOps IGNORED alone is unreliable for
        // updated apps (false positives). Only kill on confirmed privacy-on.
        if (!isFrameworkPrivacyOn(app, sensor)) {
            return;
        }
        // Access block only — never force-stop the requesting app (13.95).
        persist(app, sensor, true);
        Log.i(TAG, "privacy ON (block access, no app kill) pkg=" + packageName
            + " sensor=" + sensorName(sensor));
    }

    private static boolean isPackageOpIgnored(Context ctx, String opstr, String pkg) {
        try {
            AppOpsManager aom = ctx.getSystemService(AppOpsManager.class);
            if (aom == null) return false;
            int uid = ctx.getPackageManager().getApplicationInfo(pkg, 0).uid;
            int mode = aom.unsafeCheckOpNoThrow(opstr, uid, pkg);
            // MODE_IGNORED only — stock sensor privacy sets global IGNORED.
            // Do NOT treat MODE_ERRORED as privacy-on: missing GET_APP_OPS_STATS
            // / hidden-API denials return ERRORED and false-fired the kill belt
            // with privacy OFF (cam apps murdered; QS looked dead).
            return mode == AppOpsManager.MODE_IGNORED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** @deprecated 13.95 no-op — we block access, never force-stop apps. */
    private static void killAllIgnoredSensorUsers(Context ctx, int sensor) {
        // intentionally empty
    }

    private static boolean packageInstalled(Context ctx, String pkg) {
        try {
            ctx.getPackageManager().getApplicationInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean packageImportanceVisible(Context ctx, String pkg) {
        try {
            ActivityManager am = ctx.getSystemService(ActivityManager.class);
            if (am == null) return false;
            Method getImp = ActivityManager.class.getMethod(
                "getPackageImportance", String.class);
            Object impObj = getImp.invoke(am, pkg);
            int imp = impObj instanceof Integer ? (Integer) impObj : 1000;
            return imp > 0 && imp <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isOpActivelyUsingPkg(Object packageOps) {
        try {
            Method getOps = packageOps.getClass().getMethod("getOps");
            Object opsObj = getOps.invoke(packageOps);
            if (!(opsObj instanceof List)) return false;
            for (Object op : (List<?>) opsObj) {
                if (isOpActivelyUsing(op)) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /** @deprecated 13.95 no-op — block access only, never kill apps. */
    private static void forceStopOne(Context ctx, String pkg, String opstr) {
        // intentionally empty
    }

    private static boolean isOpIgnored(Context ctx, String opstr) {
        if (AppOpsManager.OPSTR_CAMERA.equals(opstr)) {
            return isFrameworkPrivacyOn(ctx, SENSOR_CAMERA);
        }
        if (AppOpsManager.OPSTR_RECORD_AUDIO.equals(opstr)) {
            return isFrameworkPrivacyOn(ctx, SENSOR_MICROPHONE);
        }
        return false;
    }

    /** Sync Secure plane when framework privacy is already ON (no app kill). */
    public static void enforceIfFrameworkBlocked(Context ctx) {
        if (ctx == null) return;
        Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        Boolean cam = queryToggleEnabled(app, SENSOR_CAMERA);
        Boolean mic = queryToggleEnabled(app, SENSOR_MICROPHONE);
        if (cam != null && cam) {
            onFrameworkPrivacyEnabled(app, SENSOR_CAMERA);
        }
        if (mic != null && mic) {
            onFrameworkPrivacyEnabled(app, SENSOR_MICROPHONE);
        }
    }

    /**
     * Keep Secure plane in sync with stock privacy. Never force-stops apps.
     * Node revoke is hybrid belt only.
     * <p>
     * 13.98: only clear Secure when framework explicitly reports OFF. Unreadable
     * (null) must not wipe Secure=1 — that left belt fail-open under stock QS ON.
     */
    public static boolean reassertBlockedSensors(Context ctx) {
        if (ctx == null) return false;
        Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        try { installStockToggleHook(app); } catch (Exception ignored) {}
        boolean did = false;
        Boolean cam = queryToggleEnabled(app, SENSOR_CAMERA);
        Boolean mic = queryToggleEnabled(app, SENSOR_MICROPHONE);
        // Framework ON → Secure must be 1 (heal desync heresy).
        // Framework OFF (explicit) → clear Secure. Null query → do not clear.
        if (cam != null && cam) {
            did |= reassertOne(app, SENSOR_CAMERA);
            writeSecure(app, SECURE_CAMERA, true);
        } else if (cam != null) {
            clearStaleBlockedPlane(app, SENSOR_CAMERA);
        } else if (readSecureBlocked(app, SECURE_CAMERA)) {
            // Unreadable SPM but Secure says blocked — keep fail-closed, re-impulse.
            ImpulseSnap.wakeSensorBelt();
            ImpulseSnap.camBlock();
            Log.i(TAG, "reassert cam: null query, keep Secure=1 fail-closed");
        }
        if (mic != null && mic) {
            did |= reassertOne(app, SENSOR_MICROPHONE);
            writeSecure(app, SECURE_MIC, true);
        } else if (mic != null) {
            clearStaleBlockedPlane(app, SENSOR_MICROPHONE);
        } else if (readSecureBlocked(app, SECURE_MIC)) {
            ImpulseSnap.wakeSensorBelt();
            ImpulseSnap.micBlock();
            Log.i(TAG, "reassert mic: null query, keep Secure=1 fail-closed");
        }
        return did;
    }

    private static boolean reassertOne(Context app, int sensor) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (sensor == SENSOR_CAMERA) {
            if (now - lastReassertCamElapsed < REASSERT_MIN_MS) return false;
            lastReassertCamElapsed = now;
        } else {
            if (now - lastReassertMicElapsed < REASSERT_MIN_MS) return false;
            lastReassertMicElapsed = now;
        }
        persist(app, sensor, true);
        Log.i(TAG, "reassert privacy plane sensor=" + sensorName(sensor)
            + " (access-block only)");
        return true;
    }

    /**
     * Stock privacy turned ON — update plane only. Apps stay running;
     * capture is blocked by AppOps + hybrid node belt.
     */
    static void onFrameworkPrivacyEnabled(Context ctx, int sensor) {
        if (ctx == null) return;
        if (sensor != SENSOR_CAMERA && sensor != SENSOR_MICROPHONE) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (sensor == SENSOR_CAMERA) {
            if (now - lastEnforceCamElapsed < ENFORCE_MIN_MS) return;
            lastEnforceCamElapsed = now;
        } else {
            if (now - lastEnforceMicElapsed < ENFORCE_MIN_MS) return;
            lastEnforceMicElapsed = now;
        }
        Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        persist(app, sensor, true);
        broadcast(app, sensor, true);
        Log.i(TAG, "framework privacy-on (block access, no app kill) sensor="
            + sensorName(sensor));
    }

    static void onFrameworkPrivacyDisabled(Context ctx, int sensor) {
        if (ctx == null) return;
        if (sensor != SENSOR_CAMERA && sensor != SENSOR_MICROPHONE) return;
        Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        persist(app, sensor, false);
        broadcast(app, sensor, false);
        Log.i(TAG, "framework privacy-off sensor=" + sensorName(sensor));
    }

    private static final class StockPrivacyInvocationHandler implements InvocationHandler {
        private final Context app;

        StockPrivacyInvocationHandler(Context app) {
            this.app = app;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            if ("equals".equals(name)) {
                return proxy == (args != null && args.length > 0 ? args[0] : null);
            }
            if ("hashCode".equals(name)) {
                return System.identityHashCode(proxy);
            }
            if ("toString".equals(name)) {
                return "SensorPrivacyEnforcer.StockHook";
            }
            // onSensorPrivacyChanged(int sensor, boolean enabled)
            // onSensorPrivacyChanged(SensorPrivacyChangedParams params)
            try {
                if ("onSensorPrivacyChanged".equals(name) && args != null) {
                    if (args.length >= 2
                            && args[0] instanceof Integer
                            && args[1] instanceof Boolean) {
                        final int sensor = (Integer) args[0];
                        final boolean enabled = (Boolean) args[1];
                        MAIN.post(() -> {
                            if (enabled) {
                                onFrameworkPrivacyEnabled(app, sensor);
                            } else {
                                onFrameworkPrivacyDisabled(app, sensor);
                            }
                        });
                    } else if (args.length >= 1 && args[0] != null) {
                        // Params object: getSensor() + isEnabled() / getEnabled()
                        Object params = args[0];
                        int sensor = -1;
                        Boolean enabled = null;
                        try {
                            Method gs = params.getClass().getMethod("getSensor");
                            Object s = gs.invoke(params);
                            if (s instanceof Integer) sensor = (Integer) s;
                        } catch (Throwable ignored) {}
                        try {
                            Method ge = params.getClass().getMethod("isEnabled");
                            Object e = ge.invoke(params);
                            if (e instanceof Boolean) enabled = (Boolean) e;
                        } catch (Throwable ignored) {}
                        if (enabled == null) {
                            try {
                                Method ge = params.getClass().getMethod("getEnabled");
                                Object e = ge.invoke(params);
                                if (e instanceof Boolean) enabled = (Boolean) e;
                            } catch (Throwable ignored) {}
                        }
                        if (sensor > 0 && enabled != null) {
                            final int sFinal = sensor;
                            final boolean en = enabled;
                            MAIN.post(() -> {
                                if (en) {
                                    onFrameworkPrivacyEnabled(app, sFinal);
                                } else {
                                    onFrameworkPrivacyDisabled(app, sFinal);
                                }
                            });
                        }
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "stock hook invoke: " + t.getMessage());
            }
            return null;
        }
    }

    public static String sensorName(int sensor) {
        if (sensor == SENSOR_CAMERA) return "camera";
        if (sensor == SENSOR_MICROPHONE) return "microphone";
        return "sensor_" + sensor;
    }

    // --- internals -----------------------------------------------------------

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void persist(Context ctx, int sensor, boolean blocked) {
        SharedPreferences.Editor ed = prefs(ctx).edit();
        if (sensor == SENSOR_CAMERA) {
            ed.putBoolean(KEY_CAMERA_BLOCKED, blocked);
            writeSecure(ctx, SECURE_CAMERA, blocked);
        } else if (sensor == SENSOR_MICROPHONE) {
            ed.putBoolean(KEY_MIC_BLOCKED, blocked);
            writeSecure(ctx, SECURE_MIC, blocked);
        }
        ed.apply();
    }

    private static void writeSecure(Context ctx, String key, boolean blocked) {
        try {
            Settings.Secure.putInt(ctx.getContentResolver(), key, blocked ? 1 : 0);
        } catch (Exception e) {
            Log.w(TAG, "secure " + key + ": " + e.getMessage());
        }
    }

    private static void broadcast(Context ctx, int sensor, boolean blocked) {
        try {
            Intent i = new Intent(ACTION_CHANGED);
            i.setPackage(ctx.getPackageName());
            i.putExtra(EXTRA_SENSOR, sensor);
            i.putExtra(EXTRA_BLOCKED, blocked);
            ctx.sendBroadcast(i);
        } catch (Exception ignored) {}
    }

    private static void reassertRemaining(Context ctx) {
        SharedPreferences p = prefs(ctx);
        boolean cam = p.getBoolean(KEY_CAMERA_BLOCKED, false);
        boolean mic = p.getBoolean(KEY_MIC_BLOCKED, false);
        if (cam) setTogglePrivacy(ctx, SENSOR_CAMERA, true);
        if (mic) setTogglePrivacy(ctx, SENSOR_MICROPHONE, true);
        // Legacy all-sensor only when both blocked — drives CameraService.blockAllClients
        setAllSensorPrivacy(ctx, cam && mic);
    }

    /** @return privacy-enabled (blocked), or null if unreadable */
    private static Boolean queryToggleEnabled(Context ctx, int sensor) {
        Object spm = sensorPrivacyManager(ctx);
        if (spm == null) return null;
        try {
            // isToggleSensorPrivacyEnabled(int sensor) — API 31+
            Method m = spm.getClass().getMethod("isToggleSensorPrivacyEnabled", int.class);
            Object r = m.invoke(spm, sensor);
            if (r instanceof Boolean) return (Boolean) r;
        } catch (Throwable t) {
            try {
                Method m = spm.getClass().getMethod("isSensorPrivacyEnabled", int.class);
                Object r = m.invoke(spm, sensor);
                if (r instanceof Boolean) return (Boolean) r;
            } catch (Throwable t2) {
                Log.w(TAG, "query: " + t2.getMessage());
            }
        }
        return null;
    }

    private static boolean setTogglePrivacy(Context ctx, int sensor, boolean enablePrivacy) {
        Object spm = sensorPrivacyManager(ctx);
        if (spm == null) {
            Log.w(TAG, "no SensorPrivacyManager");
            return false;
        }
        // Prefer setSensorPrivacy(source, sensor, enable) with QS_TILE source = 1
        try {
            Method m = spm.getClass().getMethod(
                "setSensorPrivacy", int.class, int.class, boolean.class);
            // Sources.QS_TILE = 1, Sources.SETTINGS = 2, Sources.OTHER = 5 (AOSP)
            m.invoke(spm, 2 /* SETTINGS */, sensor, enablePrivacy);
            return true;
        } catch (Throwable t) {
            // fall through
        }
        try {
            Method m = spm.getClass().getMethod(
                "setSensorPrivacy", int.class, boolean.class);
            m.invoke(spm, sensor, enablePrivacy);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "setTogglePrivacy: " + t.getMessage());
            return false;
        }
    }

    /**
     * Fail-closed AppOps: when privacy ON, CAMERA / RECORD_AUDIO → MODE_IGNORED
     * for the calling model (requires priv-app). Complements SPM + node belt.
     */
    private static boolean setAppOpsSensorIgnored(Context ctx, int sensor, boolean ignored) {
        try {
            AppOpsManager aom = ctx.getSystemService(AppOpsManager.class);
            if (aom == null) return false;
            String op = sensor == SENSOR_CAMERA
                ? AppOpsManager.OPSTR_CAMERA : AppOpsManager.OPSTR_RECORD_AUDIO;
            int mode = ignored ? AppOpsManager.MODE_IGNORED : AppOpsManager.MODE_ALLOWED;
            // setUidMode(op, uid, mode) — best-effort for system_server path
            try {
                Method sum = AppOpsManager.class.getMethod(
                    "setUidMode", String.class, int.class, int.class);
                sum.invoke(aom, op, Process.myUid(), mode);
            } catch (Throwable ignoredEx) {}
            // setMode(code, uid, package, mode) for self as canary
            try {
                Method sm = AppOpsManager.class.getMethod(
                    "setMode", String.class, int.class, String.class, int.class);
                sm.invoke(aom, op, Process.myUid(), ctx.getPackageName(), mode);
            } catch (Throwable ignoredEx) {}
            // Global restriction API when present (stock sensor privacy path)
            try {
                Method sgr = AppOpsManager.class.getMethod(
                    "setUserRestrictionForUser",
                    String.class, boolean.class, android.os.IBinder.class,
                    String[].class, int.class);
                android.os.IBinder token = new android.os.Binder();
                sgr.invoke(aom, op, ignored, token, null, 0);
                return true;
            } catch (Throwable t) {
                Log.w(TAG, "setUserRestriction: " + t.getMessage());
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "setAppOpsSensorIgnored: " + t.getMessage());
            return false;
        }
    }

    private static boolean setAllSensorPrivacy(Context ctx, boolean enable) {
        Object spm = sensorPrivacyManager(ctx);
        if (spm == null) return false;
        try {
            Method m = spm.getClass().getMethod("setAllSensorPrivacy", boolean.class);
            m.invoke(spm, enable);
            return true;
        } catch (Throwable t) {
            try {
                // Legacy binder name on older managers
                Method m = spm.getClass().getMethod("setSensorPrivacy", boolean.class);
                m.invoke(spm, enable);
                return true;
            } catch (Throwable t2) {
                Log.w(TAG, "setAllSensorPrivacy: " + t2.getMessage());
                return false;
            }
        }
    }

    /**
     * Pulse all-sensor privacy so CameraService.blockAllClients() runs, then clear
     * all-sensor if the user only blocked one sensor. Call after per-sensor ON.
     */
    private static boolean pulseAllSensorPrivacyBlock(Context ctx) {
        boolean on = setAllSensorPrivacy(ctx, true);
        // Leave all-sensor on only if mic is also blocked; otherwise clear so
        // mic stays usable when only camera is killed.
        boolean micAlso = prefs(ctx).getBoolean(KEY_MIC_BLOCKED, false);
        if (!micAlso) {
            // Small yield not available without sleep; framework async is fine.
            setAllSensorPrivacy(ctx, false);
        }
        return on;
    }

    private static Object sensorPrivacyManager(Context ctx) {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                Object spm = ctx.getSystemService("sensor_privacy");
                if (spm != null) return spm;
            }
        } catch (Throwable ignored) {}
        try {
            Class<?> c = Class.forName("android.hardware.SensorPrivacyManager");
            Method get = Context.class.getMethod("getSystemService", Class.class);
            return get.invoke(ctx, c);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Force-stop packages actively using camera/mic AppOps.
     * PackageOps/OpEntry are @hide — pure reflection so public SDK javac works.
     */
    /** @deprecated 13.95 no-op — block access only, never force-stop apps. */
    private static boolean forceStopRunningSensorUsers(Context ctx, int sensor) {
        return true;
    }

    /** @param op AppOpsManager.OpEntry instance (hidden API) */
    private static boolean isOpActivelyUsing(Object op) {
        if (op == null) return false;
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                Method isRunning = op.getClass().getMethod("isRunning");
                Object r = isRunning.invoke(op);
                if (r instanceof Boolean && (Boolean) r) return true;
            }
        } catch (Throwable ignored) {}
        // Prefer last-access clocks (getTime is often hours-stale while
        // duration/lastAccess still show a live open under the unlock dialog).
        long last = lastOpAccessMs(op);
        if (last > 0 && (System.currentTimeMillis() - last) < 120_000L) {
            return true;
        }
        // Fallback: recent access + mode
        try {
            Method getTime = op.getClass().getMethod("getTime");
            Method getMode = op.getClass().getMethod("getMode");
            Object tObj = getTime.invoke(op);
            Object mObj = getMode.invoke(op);
            long t = tObj instanceof Long ? (Long) tObj
                : tObj instanceof Integer ? ((Integer) tObj).longValue() : 0L;
            int mode = mObj instanceof Integer ? (Integer) mObj : AppOpsManager.MODE_ALLOWED;
            if (mode == AppOpsManager.MODE_IGNORED || mode == AppOpsManager.MODE_ERRORED) {
                return t > 0 && (System.currentTimeMillis() - t) < 30_000L;
            }
            if (mode == AppOpsManager.MODE_ALLOWED || mode == AppOpsManager.MODE_FOREGROUND) {
                return t > 0 && (System.currentTimeMillis() - t) < 120_000L;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /** Best-effort last camera/mic access millis from OpEntry reflection. */
    private static long lastOpAccessMs(Object op) {
        if (op == null) return 0L;
        // API 30+: getLastAccessTime(long flags) — OP_FLAGS_ALL ≈ 0x1f / 0x3f
        try {
            Method m = op.getClass().getMethod("getLastAccessTime", long.class);
            for (long flags : new long[] { 0x3fL, 0x1fL, 0x7L, 0x1L }) {
                Object r = m.invoke(op, flags);
                if (r instanceof Long && (Long) r > 0L) return (Long) r;
            }
        } catch (Throwable ignored) {}
        try {
            Method m = op.getClass().getMethod("getLastAccessForegroundTime", long.class);
            Object r = m.invoke(op, 0x3fL);
            if (r instanceof Long && (Long) r > 0L) return (Long) r;
        } catch (Throwable ignored) {}
        try {
            Method m = op.getClass().getMethod("getDuration");
            Object d = m.invoke(op);
            // duration alone is not a clock — pair with getTime when short live
            if (d instanceof Long && (Long) d > 0L && (Long) d < 600_000L) {
                Method getTime = op.getClass().getMethod("getTime");
                Object tObj = getTime.invoke(op);
                if (tObj instanceof Long && (Long) tObj > 0L) {
                    // Some GSI builds leave getTime at first-ever access; if
                    // duration is non-zero, treat as currently/recently active.
                    return System.currentTimeMillis();
                }
            }
        } catch (Throwable ignored) {}
        return 0L;
    }

    /**
     * Packages with recent AppOps access for camera/mic even when isRunning is
     * false (unlock-dialog residual). Used by force-stop belt.
     */
    private static Set<String> packagesWithRecentSensorOps(Context ctx, int sensor) {
        Set<String> out = new HashSet<>();
        String opstr = sensor == SENSOR_CAMERA
            ? AppOpsManager.OPSTR_CAMERA
            : AppOpsManager.OPSTR_RECORD_AUDIO;
        AppOpsManager aom;
        try {
            aom = ctx.getSystemService(AppOpsManager.class);
        } catch (Exception e) {
            return out;
        }
        if (aom == null) return out;
        try {
            Method getPkgs = AppOpsManager.class.getMethod(
                "getPackagesForOps", String[].class);
            Object list = getPkgs.invoke(aom, (Object) new String[]{opstr});
            if (!(list instanceof List)) return out;
            long now = System.currentTimeMillis();
            for (Object po : (List<?>) list) {
                if (po == null) continue;
                Method getPkg = po.getClass().getMethod("getPackageName");
                Object pkgObj = getPkg.invoke(po);
                String pkg = pkgObj instanceof String ? (String) pkgObj : null;
                if (pkg == null || shouldNeverForceStop(pkg)) continue;
                Method getOps = po.getClass().getMethod("getOps");
                Object opsObj = getOps.invoke(po);
                if (!(opsObj instanceof List)) continue;
                for (Object op : (List<?>) opsObj) {
                    if (op == null) continue;
                    long last = lastOpAccessMs(op);
                    if (last > 0 && (now - last) < 180_000L) {
                        out.add(pkg);
                        break;
                    }
                    // FOREGROUND mode without a reliable clock still kill
                    try {
                        Method getMode = op.getClass().getMethod("getMode");
                        Object mObj = getMode.invoke(op);
                        int mode = mObj instanceof Integer
                            ? (Integer) mObj : AppOpsManager.MODE_DEFAULT;
                        if (mode == AppOpsManager.MODE_FOREGROUND) {
                            out.add(pkg);
                            break;
                        }
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "packagesWithRecentSensorOps: " + t.getMessage());
        }
        return out;
    }

    private static Set<String> runningPackagesWithPermission(Context ctx, int sensor) {
        Set<String> out = new HashSet<>();
        String perm = sensor == SENSOR_CAMERA
            ? android.Manifest.permission.CAMERA
            : android.Manifest.permission.RECORD_AUDIO;
        PackageManager pm = ctx.getPackageManager();
        ActivityManager am = ctx.getSystemService(ActivityManager.class);
        if (am == null || pm == null) return out;
        int selfUid = Process.myUid();

        // Path 1: running process list (often self-only for non-system apps).
        List<ActivityManager.RunningAppProcessInfo> procs = null;
        try {
            procs = am.getRunningAppProcesses();
        } catch (Exception ignored) {}
        if (procs != null) {
            for (ActivityManager.RunningAppProcessInfo p : procs) {
                if (p == null || p.pkgList == null) continue;
                if (p.uid == selfUid) continue;
                if (p.importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE) {
                    continue; // skip cached
                }
                for (String pkg : p.pkgList) {
                    if (pkg == null || shouldNeverForceStop(pkg)) continue;
                    try {
                        if (pm.checkPermission(perm, pkg)
                                == PackageManager.PERMISSION_GRANTED) {
                            if (p.importance
                                    <= ActivityManager.RunningAppProcessInfo
                                    .IMPORTANCE_VISIBLE) {
                                out.add(pkg);
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }
        }

        // Path 2 (13.92): package importance scan — getRunningAppProcesses is
        // restricted for updated priv-apps on GSI; getPackageImportance still
        // works with QUERY_ALL_PACKAGES + FORCE_STOP. Kill visible/fg camera apps.
        try {
            Method getImp = ActivityManager.class.getMethod(
                "getPackageImportance", String.class);
            java.util.List<android.content.pm.ApplicationInfo> apps =
                pm.getInstalledApplications(0);
            for (android.content.pm.ApplicationInfo ai : apps) {
                if (ai == null || ai.packageName == null) continue;
                if (ai.uid == selfUid) continue;
                if (shouldNeverForceStop(ai.packageName)) continue;
                if (pm.checkPermission(perm, ai.packageName)
                        != PackageManager.PERMISSION_GRANTED) {
                    continue;
                }
                try {
                    Object impObj = getImp.invoke(am, ai.packageName);
                    int imp = impObj instanceof Integer
                        ? (Integer) impObj
                        : ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE;
                    if (imp <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
                            && imp > 0) {
                        out.add(ai.packageName);
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            Log.w(TAG, "packageImportance scan: " + t.getMessage());
        }
        return out;
    }

    private static boolean shouldNeverForceStop(String pkg) {
        if (pkg == null) return true;
        for (String n : NEVER_FORCE_STOP) {
            if (n.equals(pkg)) return true;
        }
        if (pkg.startsWith("com.android.providers.")) return true;
        if (pkg.equals("system") || pkg.equals("com.android.shell")) return true;
        return false;
    }
}
