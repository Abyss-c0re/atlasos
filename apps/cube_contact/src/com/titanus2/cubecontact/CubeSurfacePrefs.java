package com.titanus2.cubecontact;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;

/**
 * Cyberdeck surface SoT for CubeContact (1.53–1.61 residual).
 *
 * 1.53 fixed front lattice soft-IME + opaque GL + hybrid cube-ux dim 0.15 source.
 * 1.54 sealed Sensors/Privilege/Rear soft-IME + stamped Settings wallpaper_dim=0.15.
 *
 * Residual 1.55: Settings.System wallpaper_dim_amount=0.15 while live
 * {@code cmd wallpaper get-dim-amount} stayed 0.92. AOSP wallpaper dim is the
 * MAX of mUidToDimAmount — old /system cube-ux as root left UID=0 at 0.92;
 * shell/app {@code set-dim-amount} alone only stamps the caller UID, so the
 * gray crush stayed. apply() now dim-with-uid 0/1000/2000 + set-dim-amount,
 * then mirrors Settings + Secure show_ime_with_hard_keyboard=0. Delayed belt
 * beats late cube-ux boot / polish without flash.
 *
 * Residual 1.56: 1.55 multi-UID clear + 100s belt fixed install/open race, but
 * rootless hybrid still runs old /system cube-ux (set-dim 0.92 as UID 0) at
 * boot and polish may re-run it after the 100s belt ends → gray crush returns
 * while LAW seed shell is still live. Longer belt (through cube-ux 90s wave)
 * + LawSeedWake SCREEN_ON/TIME_TICK reassert (sticky shell) keep live dim 0.15
 * without flash. Tip cube-ux waves also re-assert multi-UID dim.
 *
 * Residual 1.57: 1.56 finite belt ends at 300s and LawSeedWake stops once LAW
 * is promoted — late polish / second cube-ux / Settings theme re-run after
 * that re-crushed gray with no wake shell. Permanent 120s guard + pad-agent
 * cool_idle root multi-UID reassert keep dim mild for process life.
 *
 * Residual 1.58: 1.57 Handler guard dies with the process (LMK / force-stop /
 * package replace). After promote + process death, late polish / theme / old
 * system cube-ux re-crush with no Java shell. AlarmManager chain (Doze-safe
 * setAndAllowWhileIdle, LAW rearm SoT) + pad-agent main-loop dim belt survive
 * process death; process guard still covers while package is warm.
 *
 * Residual 1.59: 1.58 alarm + guard still left a post-promote hole —
 * LawSeedWakeService stopSelf after LAW promote killed SCREEN_ON/TIME_TICK
 * dim edges. Soft process death then waited up to ALARM_MS before reassert.
 * DimGuardService sticky shell never stops on promote; ALARM_MS tightened to
 * 60s; pad-agent denser main-loop belt covers force-stop (alarm cancelled).
 *
 * Residual 1.60: 1.59 sticky + setAndAllowWhileIdle still left a Doze hole —
 * after force-stop / LMK, allow-while-idle is deferred ~9m while old /system
 * cube-ux can re-crush gray; hung pad-agent never hot_reloads denser belt.
 * Prefer setExactAndAllowWhileIdle (SCHEDULE_EXACT_ALARM) + ALARM_MS 30s;
 * clear Process.myUid() so app-UID residual cannot hold MAX high; wait short
 * on dim exec so multi-UID clear lands before return.
 *
 * Residual 1.61: 1.60 exact chain still left gray after DimGuard process death
 * under background-start DENY (service app=null, no PendingIntent left when
 * armAlarm never re-ran; already-tip install skipped start-service). Dual
 * alarm: exact 30s + inexact backup 90s (separate request codes); onDestroy
 * re-arm so LMK leaves a live PendingIntent; pad-agent / install poke
 * DimGuardReceiver to re-wake package without exclusive thrash.
 *
 * Residual 1.62: force-stop left package stopped=true; non-exported DimGuard
 * made shell am start-service DENY (install health false-OK); sync broadcast
 * did not leave live dual PendingIntent (pi_cancelled). Export DimGuard
 * service+receiver + goAsync hold + pad-agent start-service so force-stop
 * recovery unstops package without exclusive thrash / open UI.
 *
 * Residual 1.72: 1.71 closed LawSeedWake sticky TCP under thermal, but this
 * path still setExact every 30s + TIME_TICK multi-UID wallpaper shell (5×
 * Runtime.exec) + 120s Handler guard under thermal severe → CPU wake + fork
 * reheat residual on cool lab. Intervals + TIME_TICK gated via CubeStability
 * dimAlarmMs / dimBackupAlarmMs / dimGuardMs / allowDimTick. Cool path unchanged
 * (30s exact + TIME_TICK for gray crush intent=result).
 *
 * 1.73 residual: 1.72 parked TIME_TICK + 30s exact, but applyWithBelt still
 * posted the 6-slot multi-UID wallpaper belt (~30 shell forks / 5m) on every
 * process start under thermal severe. allowDimBelt false → single apply +
 * parked alarm/guard + DimGuard sticky only (no delayed belt posts).
 *
 * 1.74 residual: 1.73 parked BELT_MS, but armGuard still posted process-lifetime
 * Handler wallpaper forks under thermal + DimGuard onStart re-applied 5× shell
 * on every sticky restart. allowDimGuard false → skip Handler guard under heat
 * (parked alarm + SCREEN_ON only; no onStart apply thrash).
 *
 * 1.75 residual: 1.74 parks used PowerManager thermal SEVERE only; cool-lab
 * reheat often loadavg ≥8 (agent/wallpaper thrash) before thermal climbs —
 * host cool_park / pad-agent / cube-ux already load-gated, but apply() still
 * always Runtime.exec multi-UID. allowDimMultiUid / isDimHeat false →
 * settings-only stamp under load heat (no 5× dim-with-uid); cool path full
 * multi-UID for gray crush intent=result (night OLED).
 *
 * 1.76 residual: 1.75 settings-only apply() still left DimGuard onStart full
 * skip + TIME_TICK early-return under dim heat (allowDimGuard/allowDimTick) →
 * plane unstamped mid-heat. DimGuard always calls apply() under heat; park is
 * multi-UID Runtime.exec + Handler/arm rearm only (pad-agent 2.112 SoT).
 */
public final class CubeSurfacePrefs {
    /**
     * Product wallpaper dim (was 0.92 crush → 0.15 mild → 0 pure black).
     * Multi-UID still stamped so a residual root 0.92 cannot MAX-win.
     * Rear cube SF is pure black; dim 0 removes the main-plane gray wash.
     */
    public static final float WALLPAPER_DIM = 0f;
    private static final String DIM_STR = "0";

    /**
     * Boot race belt: cover cube-ux 15/45/90s waves + late polish.
     * 1.55 stopped at 100s — polish / second cube-ux after that re-crushed.
     */
    private static final long[] BELT_MS = new long[] {
        3_000L, 18_000L, 50_000L, 100_000L, 180_000L, 300_000L
    };
    /**
     * 1.57 cool default process-lifetime guard interval.
     * 1.72: live interval = {@link CubeStability#dimGuardMs} (10m under heat).
     */
    private static final long GUARD_MS = 120_000L;
    /**
     * 1.58–1.61 cool default AlarmManager chain interval.
     * 1.58 used 180s; 1.59 60s interactive. 1.60/1.61 30s exact when permitted —
     * Doze still defers non-exact allow-while-idle (~9m); DimGuard TIME_TICK
     * + pad-agent denser belt cover while warm; exact chain covers death.
     * 1.72: live interval = {@link CubeStability#dimAlarmMs} (10m under heat).
     */
    private static final long ALARM_MS = 30_000L;
    /**
     * 1.61 cool default backup inexact chain.
     * 1.72: live interval = {@link CubeStability#dimBackupAlarmMs} (15m under heat).
     */
    private static final long BACKUP_ALARM_MS = 90_000L;
    /** Single PendingIntent request code — exact chain replaces previous. */
    private static final int ALARM_REQ = 0x44494D; // "DIM"
    /** Backup inexact request code — 1.61 dual-chain residual. */
    private static final int BACKUP_ALARM_REQ = 0x44494E; // "DIN"
    private static boolean sBeltArmed;
    private static boolean sGuardArmed;

    private CubeSurfacePrefs() {}

    /**
     * Stamp live multi-UID dim + Settings mirror + HW-only soft IME.
     * 1.75: under dim heat (thermal SEVERE or load≥8) skip Runtime.exec
     * multi-UID (cool-lab reheat residual after shell load park); settings +
     * IME only. Cool path full multi-UID clear (MAX of mUidToDimAmount).
     */
    public static void apply(Context c) {
        if (c == null) return;
        boolean multiUid = true;
        try {
            multiUid = CubeStability.allowDimMultiUid(c);
        } catch (Exception ignored) {}
        if (multiUid) {
            // Live visual SoT: MAX of UID dims. Clear root/system/shell + self residual.
            execQuiet("cmd", "wallpaper", "dim-with-uid", "0", DIM_STR);
            execQuiet("cmd", "wallpaper", "dim-with-uid", "1000", DIM_STR);
            execQuiet("cmd", "wallpaper", "dim-with-uid", "2000", DIM_STR);
            try {
                // 1.60: app UID can hold MAX high if only set-dim was used elsewhere.
                execQuiet("cmd", "wallpaper", "dim-with-uid",
                    String.valueOf(Process.myUid()), DIM_STR);
            } catch (Exception ignored) {}
            execQuiet("cmd", "wallpaper", "set-dim-amount", DIM_STR);
        }
        try {
            Settings.System.putFloat(
                c.getContentResolver(), "wallpaper_dim_amount", WALLPAPER_DIM);
        } catch (Exception ignored) {}
        // FB-HID-2 / IME flicker: do NOT force show_ime=0 while exclusive HID
        // grab is live (Controls temp-allows soft IME). Blind 0 thrash vs pad-agent
        // / ImeHwPrefs put=1 restarts LatinIME insets → panel flicker on lab/scrcpy.
        // Idle: only write 0 if currently non-zero.
        try {
            boolean exclusive = hidExclusiveGrabLive(c);
            if (!exclusive) {
                int cur = Settings.Secure.getInt(
                    c.getContentResolver(), "show_ime_with_hard_keyboard", 0);
                if (cur != 0) {
                    Settings.Secure.putInt(
                        c.getContentResolver(), "show_ime_with_hard_keyboard", 0);
                }
            }
        } catch (Exception ignored) {}
    }

    /**
     * Match pad-agent / ImeHwPrefs FB-HID-2: session+grab live → exclusive.
     * Reads global/tmp flags only (no Controls dependency from CubeContact).
     */
    private static boolean hidExclusiveGrabLive(Context c) {
        if (c == null) return false;
        try {
            String s = Settings.Global.getString(
                c.getContentResolver(), "titan2_usb_hid_session");
            String g = Settings.Global.getString(
                c.getContentResolver(), "titan2_usb_hid_grab");
            if (s == null) s = "";
            if (g == null) g = "";
            s = s.trim();
            g = g.trim();
            boolean session = "1".equals(s) || "true".equalsIgnoreCase(s)
                || "on".equalsIgnoreCase(s);
            boolean grab = "1".equals(g) || "true".equalsIgnoreCase(g)
                || "on".equalsIgnoreCase(g);
            return session && grab;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * apply() now + delayed belt so old /system cube-ux (UID 0 → 0.92) that
     * races after BOOT_COMPLETED cannot leave live dim crush after first open.
     * 1.56: belt through 300s covers cube-ux 90s wave + late polish residual.
     * 1.57: also arm process-lifetime 120s guard (belt/end + LawSeedWake stop).
     * 1.58: also arm AlarmManager chain (process-death residual).
     * 1.59: also start DimGuardService sticky shell (post-promote residual).
     * 1.60: exact alarm chain when permitted (Doze ~9m residual after 1.59).
     * 1.61: dual exact+backup alarm + sticky (process death app=null residual).
     * 1.73: under thermal severe skip delayed BELT_MS posts (single apply +
     * parked guard/alarm + DimGuard sticky only — no 5m shell-fork wave).
     * 1.74: under thermal also skip armGuard Handler (allowDimGuard); DimGuard
     * sticky still registers SCREEN_ON without onStart apply thrash.
     */
    public static void applyWithBelt(Context c) {
        if (c == null) return;
        final Context app = c.getApplicationContext() != null ? c.getApplicationContext() : c;
        apply(app);
        boolean beltOk = true;
        try {
            beltOk = CubeStability.allowDimBelt(app);
        } catch (Exception ignored) {}
        // Under thermal: skip delayed BELT_MS (leave sBeltArmed false so a later
        // cool applyWithBelt in-process can still arm the gray-crush race belt).
        if (beltOk && !sBeltArmed) {
            sBeltArmed = true;
            try {
                Handler h = new Handler(Looper.getMainLooper());
                for (long delay : BELT_MS) {
                    h.postDelayed(() -> {
                        try { apply(app); } catch (Exception ignored) {}
                    }, delay);
                }
            } catch (Exception ignored) {
                sBeltArmed = false;
            }
        }
        armGuard(app);
        armAlarm(app);
        try { DimGuardService.start(app); } catch (Exception ignored) {}
    }

    /**
     * 1.57: cheap multi-UID reassert every {@link #GUARD_MS} for process life.
     * Survives finite belt end and LawSeedWake stop-after-promote residual.
     * 1.72 residual: under thermal severe GUARD_MS 120s still forked wallpaper
     * shell every 2m — use {@link CubeStability#dimGuardMs} (10m under heat).
     * 1.74 residual: even 10m park left Handler reassert under sticky DimGuard
     * process keep — {@link CubeStability#allowDimGuard} false skips posts under
     * thermal (leave sGuardArmed false so cool path can arm later in-process).
     */
    public static void armGuard(Context c) {
        if (c == null || sGuardArmed) return;
        final Context app = c.getApplicationContext() != null ? c.getApplicationContext() : c;
        try {
            if (!CubeStability.allowDimGuard(app)) {
                android.util.Log.i("CubeStability", "no dim guard (thermal)");
                return;
            }
        } catch (Exception ignored) {}
        sGuardArmed = true;
        try {
            final Handler h = new Handler(Looper.getMainLooper());
            final Runnable tick = new Runnable() {
                @Override
                public void run() {
                    try {
                        // Mid-session heat: stop reassert thrash; leave sGuardArmed
                        // false so cool later can re-arm (intent=result).
                        if (!CubeStability.allowDimGuard(app)) {
                            sGuardArmed = false;
                            return;
                        }
                    } catch (Exception ignored) {}
                    try { apply(app); } catch (Exception ignored) {}
                    long delay = GUARD_MS;
                    try {
                        delay = CubeStability.dimGuardMs(app);
                        if (delay < GUARD_MS) delay = GUARD_MS;
                    } catch (Exception ignored) {}
                    try { h.postDelayed(this, delay); } catch (Exception ignored) {}
                }
            };
            long first = GUARD_MS;
            try {
                first = CubeStability.dimGuardMs(app);
                if (first < GUARD_MS) first = GUARD_MS;
            } catch (Exception ignored) {}
            h.postDelayed(tick, first);
        } catch (Exception ignored) {
            sGuardArmed = false;
        }
    }

    /**
     * 1.58–1.61: chain Doze-safe alarms that re-apply multi-UID dim and
     * re-arm themselves. Survives process death (LMK / force-stop / package
     * replace) after 1.57 Handler guard dies.
     * 1.60: prefer setExactAndAllowWhileIdle when SCHEDULE_EXACT_ALARM allowed.
     * 1.61: also arm inexact backup (BACKUP_ALARM_MS) so exact denial / process
     * death before re-arm still leaves a live PendingIntent slot.
     */
    public static void armAlarm(Context c) {
        if (c == null) return;
        final Context app;
        try {
            Context a = c.getApplicationContext();
            app = a != null ? a : c;
        } catch (Exception e) {
            return;
        }
        AlarmManager am;
        try {
            am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        } catch (Exception e) {
            return;
        }
        if (am == null) return;
        armExactSlot(app, am);
        armBackupSlot(app, am);
    }

    private static void armExactSlot(Context app, AlarmManager am) {
        PendingIntent pi = dimPending(app, ALARM_REQ);
        if (pi == null) return;
        // 1.72 residual: under thermal severe ALARM_MS 30s exact woke SoC +
        // 5× wallpaper shell every fire. dimAlarmMs parks to 10m under heat.
        long interval = ALARM_MS;
        try {
            interval = CubeStability.dimAlarmMs(app);
            if (interval < ALARM_MS) interval = ALARM_MS;
        } catch (Exception ignored) {}
        long when = SystemClock.elapsedRealtime() + interval;
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                boolean exactOk = true;
                try {
                    exactOk = am.canScheduleExactAlarms();
                } catch (Exception ignored) {
                    exactOk = true;
                }
                // Under thermal severe prefer inexact allow-while-idle (no
                // setExact CPU wake thrash); cool path keeps exact for gray crush.
                boolean preferExact = true;
                try {
                    preferExact = CubeStability.allowDimTick(app);
                } catch (Exception ignored) {}
                if (exactOk && preferExact) {
                    try {
                        am.setExactAndAllowWhileIdle(
                            AlarmManager.ELAPSED_REALTIME_WAKEUP, when, pi);
                        return;
                    } catch (SecurityException ignored) {
                        // Fall through to non-exact.
                    } catch (Exception ignored) {
                        // Fall through.
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= 23) {
                try {
                    if (Build.VERSION.SDK_INT < 31) {
                        boolean preferExact = true;
                        try {
                            preferExact = CubeStability.allowDimTick(app);
                        } catch (Exception ignored) {}
                        if (preferExact) {
                            am.setExactAndAllowWhileIdle(
                                AlarmManager.ELAPSED_REALTIME_WAKEUP, when, pi);
                            return;
                        }
                    }
                } catch (Exception ignored) {}
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, when, pi);
            } else {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, when, pi);
            }
        } catch (Exception ignored) {
            // Cool lab / OEM restrict — fail closed; backup + DimGuard + pad-agent.
        }
    }

    /**
     * 1.61: inexact backup so exact-slot death still reasserts within ~90s.
     * 1.72: under thermal severe BACKUP_ALARM_MS 90s still thrash — dimBackupAlarmMs.
     */
    private static void armBackupSlot(Context app, AlarmManager am) {
        PendingIntent pi = dimPending(app, BACKUP_ALARM_REQ);
        if (pi == null) return;
        long interval = BACKUP_ALARM_MS;
        try {
            interval = CubeStability.dimBackupAlarmMs(app);
            if (interval < BACKUP_ALARM_MS) interval = BACKUP_ALARM_MS;
        } catch (Exception ignored) {}
        long when = SystemClock.elapsedRealtime() + interval;
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, when, pi);
            } else {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, when, pi);
            }
        } catch (Exception ignored) {}
    }

    /** DimGuardReceiver: apply then re-chain + sticky shell (process-death residual). */
    static void onAlarmFire(Context c) {
        if (c == null) return;
        final Context app;
        try {
            Context a = c.getApplicationContext();
            app = a != null ? a : c;
        } catch (Exception e) {
            return;
        }
        try { apply(app); } catch (Exception ignored) {}
        try { armGuard(app); } catch (Exception ignored) {}
        try { armAlarm(app); } catch (Exception ignored) {}
        try { DimGuardService.start(app); } catch (Exception ignored) {}
    }

    private static PendingIntent dimPending(Context app, int req) {
        try {
            Intent i = new Intent(app, DimGuardReceiver.class);
            i.setAction(DimGuardReceiver.ACTION);
            i.setPackage(app.getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            return PendingIntent.getBroadcast(app, req, i, flags);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 1.60: wait briefly so multi-UID dim actually lands before return.
     * 1.59 fire-and-forget Runtime.exec left races (return before cmd runs).
     */
    private static void execQuiet(String... cmd) {
        if (cmd == null || cmd.length == 0) return;
        java.lang.Process p = null;
        try {
            p = Runtime.getRuntime().exec(cmd);
            // Short join — never block UI / TIME_TICK path multi-second.
            final java.lang.Process waitP = p;
            Thread t = new Thread(() -> {
                try {
                    waitP.waitFor();
                } catch (Exception ignored) {}
            }, "cube-dim-exec");
            t.setDaemon(true);
            t.start();
            t.join(400);
            if (t.isAlive()) {
                try { p.destroy(); } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {
            if (p != null) {
                try { p.destroy(); } catch (Exception ignored2) {}
            }
        }
    }
}
