package com.titanus2.controls.subdisplay;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;

import com.titanus2.controls.AgentBridge;
import com.titanus2.controls.BuildConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Physical rear panel power + hard rear-touch park.
 * DT2W dual-light must not leave {@code sub_touch} as a second touchscreen.
 * <p>
 * Dual-display sleep/wake invariant (15.55+):
 * <ul>
 *   <li>While main is <em>asleep</em>: rear only via {@link #wakeRearHardwareOnly} —
 *       never DisplayManager / startActivity (re-lights main power group).</li>
 *   <li>On real main {@code SCREEN_ON}: {@link #healMainGlass} floors main brightness
 *       only — never {@code cmd power wakeup}, never long wakelocks, never fight
 *       short-press power sleep.</li>
 *   <li>Sleep stays framework-owned (power key / timeout). We do not re-arm wake.</li>
 *   <li>15.60 lag kill: SF lists rear as <b>Follower</b> of main Pacesetter with
 *       {@code powerMode=On} even when BL=0 → dual {@code mtk_crtc} thrash
 *       (~5/s, loadavg 15 idle). Park rear via
 *       {@link #setRearHwcPowerMode} POWER_MODE_OFF, not brightness alone.</li>
 * </ul>
 */
public final class SubDisplayPower {
    private static final String TAG = "SubDisplayPower";
    public static final String BL_SYSFS =
        "/sys/devices/platform/mtk-leds1/leds/lcd-backlight1/brightness";
    public static final String BL_CLASS = "/sys/class/leds/lcd-backlight1/brightness";
    private static final String ADB_SEED = "/data/adb/titan2";
    /** Below this float main can look “off” in a dark lab after dual-display thrash. */
    private static final float MAIN_BRIGHT_FLOOR = 0.22f;
    /** Min gap between main-glass heals (SCREEN_ON spam / dual receivers). */
    private static final long MAIN_HEAL_MIN_GAP_MS = 1500L;

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean lastPower = new AtomicBoolean(false);
    private static final AtomicInteger lastHw = new AtomicInteger(-1);
    private static final AtomicBoolean powerKnown = new AtomicBoolean(false);
    /** priv_app SELinux denials on /data/adb — never re-mkdir every tick (15.33). */
    private static final AtomicBoolean adbSeedDenied = new AtomicBoolean(false);
    private static final AtomicLong lastMainHealElapsed = new AtomicLong(0L);

    private SubDisplayPower() {}

    /**
     * True when rear power is already known ON at matching hw — cube tick/watchdog
     * can skip re-stamp + kickHardware (Controls 15.33 cube-asleep-park).
     */
    public static boolean isSteadyOn(int hw) {
        return powerKnown.get() && lastPower.get() && lastHw.get() == hw;
    }

    /** Last known hw (or -1 if unknown). */
    public static int lastKnownHw() {
        return lastHw.get();
    }

    public static boolean isPowerKnownOn() {
        return powerKnown.get() && lastPower.get();
    }

    public static void apply(Context ctx, boolean on) {
        apply(ctx, on, on ? SubDisplayPrefs.getBrightnessPct(ctx) : 0, true);
    }

    public static void applyBrightness(Context ctx, int pct) {
        // Preserve apps/cube digitizer plane (bri step must not clobber sub_mode).
        apply(ctx, true, Math.max(0, Math.min(100, pct)), false);
    }

    /**
     * Power rear panel. Digitizer policy follows current {@link SubDisplayPrefs.Mode}:
     * Apps/Cube = live sub_touch associated to rear only; Face = parked.
     */
    public static void apply(Context ctx, boolean on, int briPct, boolean touchPower) {
        boolean rearLive = false;
        String modeName = "face";
        try {
            SubDisplayPrefs.Mode m = SubDisplayPrefs.getMode(ctx);
            if (m == SubDisplayPrefs.Mode.APPS) {
                rearLive = true;
                modeName = "apps";
            } else if (m == SubDisplayPrefs.Mode.CUBE) {
                rearLive = true;
                modeName = "cube";
            } else if (m == SubDisplayPrefs.Mode.OFF) {
                modeName = "off";
            } else {
                modeName = "face";
            }
        } catch (Exception ignored) {}
        apply(ctx, on, briPct, touchPower, rearLive, modeName);
    }

    /** @deprecated use {@link #apply(Context, boolean, int, boolean)} — preserves cube/apps */
    @Deprecated
    public static void apply(Context ctx, boolean on, int briPct, boolean touchPower,
                             boolean appsMode) {
        apply(ctx, on, briPct, touchPower, appsMode,
            appsMode ? "apps" : (on ? "face" : "off"));
    }

    /**
     * @param rearTouchLive when true, rear digitizer live + associated to rear display only
     *                      (apps launcher or cube GL). Face keeps digitizer parked.
     * @param modeName      plane token: off|face|apps|cube
     */
    public static void apply(Context ctx, boolean on, int briPct, boolean touchPower,
                             boolean rearTouchLive, String modeName) {
        Context app = ctx.getApplicationContext();
        float bri = Math.max(0f, Math.min(1f, briPct / 100f));
        if (!on) bri = 0f;

        String mode = !on ? "off" : (modeName != null ? modeName : "face");
        boolean live = rearTouchLive && on;
        AgentBridge.put(app, AgentBridge.SUBDISPLAY_ON, on ? "1" : "0");
        AgentBridge.put(app, AgentBridge.SUBDISPLAY_BRI,
            String.format(java.util.Locale.US, "%.2f", bri));
        if (on) AgentBridge.put(app, AgentBridge.SUBDISPLAY_ID, "2");
        AgentBridge.put(app, AgentBridge.SUB_MODE, mode);
        // Face/off: park digitizer. Apps/cube: live + associate rear only.
        AgentBridge.put(app, AgentBridge.SUBTOUCH_INHIBIT, live ? "0" : "1");
        // Always bump apply stamp so pad-agent re-ioctls even if On→On same bri.
        AgentBridge.put(app, AgentBridge.SUBDISPLAY_APPLY,
            String.valueOf(System.currentTimeMillis()));
        try {
            android.provider.Settings.Global.putString(
                app.getContentResolver(), "titan2_sub_mode", mode);
            android.provider.Settings.Global.putString(
                app.getContentResolver(), "titan2_subtouch_inhibit",
                live ? "0" : "1");
        } catch (Exception ignored) {}
        seedAdbPlane(on ? "1" : "0",
            String.format(java.util.Locale.US, "%.2f", bri), mode);

        final boolean fOn = on;
        final float fBri = bri;
        final boolean fPower = touchPower;
        final boolean fLive = live;
        final String fMode = mode;
        EXEC.execute(() -> {
            if (fPower) {
                lastPower.set(fOn);
                powerKnown.set(true);
                Log.i(TAG, "panel request -> " + fOn + " mode=" + fMode
                    + " digitizer=" + (fLive ? "rear" : "parked"));
            }
            int hw = fOn ? Math.round(fBri * 255f) : 0;
            if (hw < 0) hw = 0;
            if (hw > 255) hw = 255;
            // Always set brightness + kick (was skipped when lastHw matched → dark residual)
            setBrightness(app, fBri, hw);
            lastHw.set(hw);
            kickHardware(fOn, hw);
            if (fLive) {
                String err = SubDisplayInput.associateSubTouchToRear(app);
                if (err != null) Log.w(TAG, "associate: " + err);
                // Un-inhibit sub_touch sysfs so InputReader delivers to rear
                forceLiveSubTouch();
            } else {
                SubDisplayInput.clearAssociation(app);
                forceInhibitSubTouch();
            }
        });
    }

    /** Power rear for independent apps (Key action / lab). */
    public static void applyApps(Context ctx) {
        apply(ctx, true, Math.max(1, SubDisplayPrefs.getBrightnessPct(ctx)), true, true);
    }

    private static void seedAdbPlane(String on, String bri, String mode) {
        // Rootless priv_app: /data/adb is adb_data_file — getattr/mkdir denied every
        // cube tick left log spam + binder thrash (15.33). Fail once, skip forever
        // until process restart (Magisk path only when dir already exists + writable).
        if (adbSeedDenied.get()) return;
        try {
            File dir = new File(ADB_SEED);
            if (!dir.isDirectory()) {
                if (!dir.mkdirs()) {
                    adbSeedDenied.set(true);
                    return;
                }
            }
            if (!dir.canWrite()) {
                adbSeedDenied.set(true);
                return;
            }
            writeFile(new File(dir, AgentBridge.SUBDISPLAY_ON), on);
            writeFile(new File(dir, AgentBridge.SUBDISPLAY_BRI), bri);
            writeFile(new File(dir, AgentBridge.SUB_MODE), mode != null ? mode : "face");
            writeFile(new File(dir, AgentBridge.SUBTOUCH_INHIBIT),
                ("apps".equals(mode) || "cube".equals(mode)) ? "0" : "1");
            writeFile(new File(dir, AgentBridge.SUBDISPLAY_APPLY),
                String.valueOf(System.currentTimeMillis()));
        } catch (SecurityException se) {
            adbSeedDenied.set(true);
        } catch (Exception ignored) {}
    }

    private static void writeFile(File f, String body) {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
            //noinspection ResultOfMethodCallIgnored
            f.setReadable(true, false);
            //noinspection ResultOfMethodCallIgnored
            f.setWritable(true, false);
        } catch (Exception ignored) {}
    }

    private static void kickHardware(boolean on, int hw) {
        // Default: allow framework display-2 brightness only when main is interactive
        // (asleep + cmd display --id 2 can poke the shared power group → re-wake main).
        kickHardware(on, hw, /*allowFrameworkDisplay*/ true);
    }

    /**
     * @param allowFrameworkDisplay when false (main asleep / HW-only path), never
     *                              call {@code cmd display set-brightness} — sysfs
     *                              + subpanel_bl + pad-agent plane only.
     */
    private static void kickHardware(boolean on, int hw, boolean allowFrameworkDisplay) {
        String en = on ? "1" : "0";
        String bl = String.valueOf(Math.max(0, Math.min(255, hw)));
        boolean hwOk = false;
        // Prefer direct system bin (init-labeled phhsu_exec may allow shell on some builds).
        if (tryShell(new String[]{"/system/bin/titan2-subpanel-bl", en})) {
            Log.i(TAG, "kickHardware bin ok on=" + on + " hw=" + hw);
            tryShell(new String[]{"sh", "-c",
                "echo " + bl + " > /sys/class/leds/lcd-backlight1/brightness 2>/dev/null; "
                    + "echo " + bl + " > /sys/devices/platform/mtk-leds1/leds/lcd-backlight1/brightness 2>/dev/null; true"});
            hwOk = true;
        } else {
            String script =
                "ON=" + en + "; HW=" + bl + "; "
                    + "BIN=/system/bin/titan2-subpanel-bl; "
                    + "[ -x /data/adb/modules/titan2_subdisplay/subpanel_bl ] && "
                    + "BIN=/data/adb/modules/titan2_subdisplay/subpanel_bl; "
                    + "[ -x /data/local/tmp/subpanel_bl ] && BIN=/data/local/tmp/subpanel_bl; "
                    + "[ -x \"$BIN\" ] && \"$BIN\" \"$ON\" >/dev/null 2>&1; "
                    + "echo \"$HW\" > /sys/class/leds/lcd-backlight1/brightness 2>/dev/null; "
                    + "echo \"$HW\" > /sys/devices/platform/mtk-leds1/leds/lcd-backlight1/brightness 2>/dev/null; "
                    + "true";
            if (tryShell(new String[]{"su", "-c", script})) {
                Log.i(TAG, "kickHardware su ok on=" + on + " hw=" + hw);
                hwOk = true;
            } else if (BuildConfig.ALLOW_ROOT
                    && tryShell(new String[]{"su", "0", "sh", "-c", script})) {
                Log.i(TAG, "kickHardware su0 ok on=" + on + " hw=" + hw);
                hwOk = true;
            }
        }
        if (!hwOk) {
            // Rootless product: plane + SUBDISPLAY_APPLY already stamped — pad-agent
            // apply_subdisplay owns ioctl. Framework brightness only when allowed.
            if (allowFrameworkDisplay) {
                tryShell(new String[]{"cmd", "display", "set-brightness",
                    on ? String.format(Locale.US, "%.2f", hw / 255f) : "0",
                    "--id", "2"});
                Log.i(TAG, "kickHardware plane/cmd path on=" + on + " hw=" + hw
                    + " (pad-agent edge applies Agold ioctl)");
            } else {
                // Sysfs best-effort without su (may fail; pad-agent edge still owns).
                tryShell(new String[]{"sh", "-c",
                    "echo " + bl + " > /sys/class/leds/lcd-backlight1/brightness 2>/dev/null; "
                        + "echo " + bl + " > /sys/devices/platform/mtk-leds1/leds/lcd-backlight1/brightness 2>/dev/null; true"});
                Log.i(TAG, "kickHardware plane/sysfs-only on=" + on + " hw=" + hw
                    + " (no framework display; sleep-safe)");
            }
        }
        // 15.60: HWC powerMode for rear Follower — BL alone does not stop dual CRTC thrash.
        setRearHwcPowerMode(on);
    }

    /**
     * SurfaceFlinger physical power for rear (Follower of main Pacesetter).
     * {@code POWER_MODE_OFF=0}, {@code POWER_MODE_NORMAL=2}.
     * Lab 2026-08-11: rear powerMode=On with BL=0 → loadavg thrash / delayed wake.
     */
    public static void setRearHwcPowerMode(boolean on) {
        try {
            Class<?> sc = Class.forName("android.view.SurfaceControl");
            Method getIds = sc.getMethod("getPhysicalDisplayIds");
            Object raw = getIds.invoke(null);
            if (!(raw instanceof long[])) {
                Log.w(TAG, "setRearHwcPowerMode: no physical ids");
                return;
            }
            long[] ids = (long[]) raw;
            if (ids.length < 2) {
                Log.w(TAG, "setRearHwcPowerMode: only " + ids.length + " physical");
                return;
            }
            // Second physical display is rear (HWC port 3 / logical display 2).
            long rearPhys = ids[1];
            Method getToken = sc.getMethod("getPhysicalDisplayToken", long.class);
            Object tokenObj = getToken.invoke(null, rearPhys);
            if (!(tokenObj instanceof IBinder)) {
                Log.w(TAG, "setRearHwcPowerMode: no token for " + rearPhys);
                return;
            }
            Method setMode = sc.getMethod("setDisplayPowerMode", IBinder.class, int.class);
            // SurfaceControl.POWER_MODE_OFF=0, NORMAL=2
            int mode = on ? 2 : 0;
            setMode.invoke(null, tokenObj, mode);
            Log.i(TAG, "setRearHwcPowerMode on=" + on + " phys=" + Long.toHexString(rearPhys)
                + " mode=" + mode);
        } catch (Throwable t) {
            Log.w(TAG, "setRearHwcPowerMode: " + t.getMessage());
        }
    }

    /**
     * Cool / lag park: rear HWC OFF + Agold ioctl off + digitizer inhibit.
     * Call from cool-park / emergency lag without turning rear "product on".
     */
    public static void parkRearForCool(Context ctx) {
        if (ctx != null) {
            Context app = ctx.getApplicationContext();
            AgentBridge.put(app, AgentBridge.SUBDISPLAY_ON, "0");
            AgentBridge.put(app, AgentBridge.SUB_MODE, "off");
            AgentBridge.put(app, AgentBridge.SUBTOUCH_INHIBIT, "1");
            AgentBridge.put(app, AgentBridge.SUBDISPLAY_APPLY,
                String.valueOf(System.currentTimeMillis()));
            seedAdbPlane("0", "0.00", "off");
        }
        lastPower.set(false);
        powerKnown.set(true);
        lastHw.set(0);
        kickHardware(false, 0, /*allowFrameworkDisplay*/ true);
        forceInhibitSubTouch();
        Log.i(TAG, "parkRearForCool");
    }

    /**
     * Hard-park rear digitizer so DT2W dual-light cannot make display 2 a
     * touchscreen. Product: rear is face-only.
     */
    public static void forceInhibitSubTouch() {
        String script =
            "echo 1 > /data/local/tmp/titan2_subtouch_inhibit 2>/dev/null; "
                + "chmod 666 /data/local/tmp/titan2_subtouch_inhibit 2>/dev/null; "
                + "settings put global titan2_subtouch_inhibit 1 2>/dev/null; "
                + "for d in /sys/class/input/input*; do "
                + "  n=$(cat \"$d/name\" 2>/dev/null) || continue; "
                + "  [ \"$n\" = sub_touch ] || continue; "
                + "  echo 1 > \"$d/inhibited\" 2>/dev/null || "
                + "  echo 1 > \"$d/device/inhibited\" 2>/dev/null; "
                + "done; "
                + "for d in /sys/class/input/input*; do "
                + "  n=$(cat \"$d/name\" 2>/dev/null) || continue; "
                + "  case \"$n\" in sub_touch|touchPad) "
                + "    [ -f \"$d/device/wake_gesture\" ] && echo 0 > \"$d/device/wake_gesture\" 2>/dev/null; "
                + "    [ -f \"$d/wake_gesture\" ] && echo 0 > \"$d/wake_gesture\" 2>/dev/null; "
                + "  ;; esac; "
                + "done; true";
        if (tryShell(new String[]{"su", "-c", script})) {
            Log.i(TAG, "forceInhibitSubTouch su ok");
            return;
        }
        if (BuildConfig.ALLOW_ROOT) {
            tryShell(new String[]{"su", "0", "sh", "-c", script});
        }
    }

    /**
     * Un-park rear digitizer for apps/cube (associate still required for display-2).
     * Does not enable input_surface=sub (that would inject a main cursor).
     */
    public static void forceLiveSubTouch() {
        String script =
            "echo 0 > /data/local/tmp/titan2_subtouch_inhibit 2>/dev/null; "
                + "chmod 666 /data/local/tmp/titan2_subtouch_inhibit 2>/dev/null; "
                + "settings put global titan2_subtouch_inhibit 0 2>/dev/null; "
                + "for d in /sys/class/input/input*; do "
                + "  n=$(cat \"$d/name\" 2>/dev/null) || continue; "
                + "  [ \"$n\" = sub_touch ] || continue; "
                + "  echo 0 > \"$d/inhibited\" 2>/dev/null || "
                + "  echo 0 > \"$d/device/inhibited\" 2>/dev/null; "
                + "done; true";
        if (tryShell(new String[]{"su", "-c", script})) {
            Log.i(TAG, "forceLiveSubTouch su ok");
            return;
        }
        if (BuildConfig.ALLOW_ROOT) {
            tryShell(new String[]{"su", "0", "sh", "-c", script});
        }
        // Best-effort without root: plane files only (pad-agent belt applies)
        try {
            File f = new File("/data/local/tmp/titan2_subtouch_inhibit");
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write("0".getBytes(StandardCharsets.UTF_8));
            }
            //noinspection ResultOfMethodCallIgnored
            f.setReadable(true, false);
            //noinspection ResultOfMethodCallIgnored
            f.setWritable(true, false);
        } catch (Exception ignored) {}
    }

    private static boolean tryShell(String[] cmd) {
        try {
            Process p = Runtime.getRuntime().exec(cmd);
            if (!p.waitFor(2500, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    public static void invalidatePowerState() {
        powerKnown.set(false);
        lastHw.set(-1);
    }

    private static void setBrightness(Context ctx, float logical, int hw) {
        try {
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            if (dm != null) {
                Display rear = SubDisplayHelper.findRear(ctx);
                int id = rear != null ? rear.getDisplayId() : 2;
                Method m = DisplayManager.class.getMethod("setBrightness", int.class, float.class);
                // Off path must be real 0 — max(0.01) kept rear "on" for SF follower thrash.
                float L = hw <= 0 ? 0f : Math.max(0.01f, logical);
                m.invoke(dm, id, L);
            }
        } catch (Exception e) {
            Log.d(TAG, "setBrightness: " + e.getMessage());
        }
        AgentBridge.put(ctx, AgentBridge.SUBDISPLAY_BRI,
            String.format(java.util.Locale.US, "%.2f", hw / 255f));
    }

    /**
     * Light rear when main is interactive. If main is already asleep, routes to
     * {@link #wakeRearHardwareOnly} so DisplayManager cannot re-wake the main
     * power group (sleep must stay intact).
     */
    public static void wakeDisplayOnly(Context ctx, String reason) {
        if (!isMainInteractive(ctx)) {
            wakeRearHardwareOnly(ctx, reason != null ? reason + "/asleep-hw" : "asleep-hw");
            return;
        }
        // Preserve apps/cube digitizer plane (4-arg apply reads SubDisplayPrefs).
        apply(ctx, true, Math.max(1, SubDisplayPrefs.getBrightnessPct(ctx)), true);
        Log.i(TAG, "wakeDisplayOnly panel-only (" + reason + ")");
    }

    /**
     * Cube / face while main is asleep: light rear via ioctl/sysfs only.
     * Never DisplayManager.setBrightness / startActivity side-effects — those
     * re-wake the shared power group and light the main glass (lab residual).
     * <p>
     * 15.33 cube-asleep-park: tick/watchdog re-entered every 2s with full plane
     * APPLY stamp + seedAdb + kickHardware while rear already ON (Controls ~3%
     * + avc /data/adb spam under sticky load). Steady ON+same hw → no-op leave;
     * edge reasons (screen/start/DT2W/APPLY/unlocked) still force re-assert.
     */
    public static void wakeRearHardwareOnly(Context ctx, String reason) {
        Context app = ctx != null ? ctx.getApplicationContext() : null;
        int pct = app != null
            ? Math.max(1, SubDisplayPrefs.getBrightnessPct(app)) : 50;
        int hw = Math.max(1, Math.min(255, Math.round(pct * 2.55f)));
        boolean force = reason != null
            && (reason.contains("screen") || reason.contains("start")
                || reason.contains("DT2W") || reason.contains("APPLY")
                || reason.contains("unlocked") || reason.contains("late")
                || reason.contains("force"));
        // Steady park: already ON at matching hw — skip re-stamp thrash.
        if (!force && isSteadyOn(hw)) {
            Log.d(TAG, "wakeRearHardwareOnly steady park hw=" + hw
                + " (" + reason + ")");
            return;
        }
        // Plane stamp only — no framework display APIs. Edge re-assert bumps
        // APPLY so pad-agent edge re-ioctls promptly (dark residual closed).
        if (app != null) {
            String modeTok = "cube";
            try {
                SubDisplayPrefs.Mode m = SubDisplayPrefs.getMode(app);
                if (m == SubDisplayPrefs.Mode.APPS) modeTok = "apps";
                else if (m == SubDisplayPrefs.Mode.CUSTOM
                        || m == SubDisplayPrefs.Mode.STOCK) modeTok = "face";
            } catch (Exception ignored) {}
            String bri = String.format(Locale.US, "%.2f", hw / 255f);
            AgentBridge.put(app, AgentBridge.SUBDISPLAY_ON, "1");
            AgentBridge.put(app, AgentBridge.SUBDISPLAY_BRI, bri);
            AgentBridge.put(app, AgentBridge.SUB_MODE, modeTok);
            // Face: park digitizer. Cube/apps: live rear only.
            boolean live = "cube".equals(modeTok) || "apps".equals(modeTok);
            AgentBridge.put(app, AgentBridge.SUBTOUCH_INHIBIT, live ? "0" : "1");
            AgentBridge.put(app, AgentBridge.SUBDISPLAY_ID, "2");
            AgentBridge.put(app, AgentBridge.SUBDISPLAY_APPLY,
                String.valueOf(System.currentTimeMillis()));
            seedAdbPlane("1", bri, modeTok);
        }
        lastPower.set(true);
        powerKnown.set(true);
        lastHw.set(hw);
        // Sleep-safe: no cmd display — that can re-wake main power group.
        kickHardware(true, hw, /*allowFrameworkDisplay*/ false);
        Log.i(TAG, "wakeRearHardwareOnly hw=" + hw + " (" + reason + ")");
    }

    /**
     * After a real main {@code SCREEN_ON}: if main glass is still unreadable
     * (dual-display thrash left brt≈0 / RBC), floor brightness on display 0.
     * <p>
     * <b>Sleep-safe:</b> only runs when {@link PowerManager#isInteractive()} is
     * already true. Never calls {@code cmd power wakeup}, never acquires a
     * display wakelock, never touches stay-on / timeout. Power-button sleep
     * remains framework-owned.
     */
    public static void healMainGlass(Context ctx, String reason) {
        if (ctx == null) return;
        final Context app = ctx.getApplicationContext();
        long now = SystemClock.elapsedRealtime();
        long prev = lastMainHealElapsed.get();
        if (now - prev < MAIN_HEAL_MIN_GAP_MS) {
            Log.d(TAG, "healMainGlass throttle (" + reason + ")");
            return;
        }
        lastMainHealElapsed.set(now);
        EXEC.execute(() -> {
            try {
                if (!isMainInteractive(app)) {
                    // Do not force wake — user / framework chose sleep.
                    Log.d(TAG, "healMainGlass skip non-interactive (" + reason + ")");
                    return;
                }
                float want = MAIN_BRIGHT_FLOOR;
                try {
                    int sys = Settings.System.getInt(
                        app.getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS, 128);
                    float fromSys = Math.max(0f, Math.min(1f, sys / 255f));
                    // Prefer user slider when it is already brighter than floor.
                    want = Math.max(MAIN_BRIGHT_FLOOR, fromSys);
                } catch (Exception ignored) {}
                float cur = -1f;
                try {
                    Process p = Runtime.getRuntime().exec(new String[]{
                        "cmd", "display", "get-brightness", "--id", "0"
                    });
                    p.waitFor(800, TimeUnit.MILLISECONDS);
                    // Best-effort parse optional; floor anyway if low.
                } catch (Exception ignored) {}
                setMainBrightness(app, want);
                // id-scoped only — bare set-brightness hits default, still ok,
                // but always pin --id 0 so rear never receives this.
                tryShell(new String[]{
                    "cmd", "display", "set-brightness",
                    String.format(Locale.US, "%.3f", want),
                    "--id", "0"
                });
                Log.i(TAG, "healMainGlass want=" + want
                    + " curProbe=" + cur + " (" + reason + ")");
            } catch (Exception e) {
                Log.w(TAG, "healMainGlass: " + e.getMessage());
            }
        });
    }

    private static void setMainBrightness(Context ctx, float logical) {
        try {
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            if (dm == null) return;
            Method m = DisplayManager.class.getMethod(
                "setBrightness", int.class, float.class);
            float L = Math.max(MAIN_BRIGHT_FLOOR, Math.min(1f, logical));
            m.invoke(dm, Display.DEFAULT_DISPLAY, L);
        } catch (Exception e) {
            Log.d(TAG, "setMainBrightness: " + e.getMessage());
        }
    }

    public static boolean isMainInteractive(Context ctx) {
        try {
            if (ctx == null) return false;
            PowerManager pm = (PowerManager) ctx.getApplicationContext()
                .getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isInteractive();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Best-effort: request device sleep so main glass goes dark.
     * Rear stays lit only via {@link #wakeRearHardwareOnly} after this.
     * <p>
     * Do not call from tick/watchdog — human / explicit policy only.
     * Uses plain {@code cmd power sleep} (no --disable-wakelocks) so normal
     * partial locks (calls, alarm) still behave; framework owns the rest.
     */
    public static void requestMainSleep(String reason) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{
                "cmd", "power", "sleep"
            });
            p.waitFor(1500, TimeUnit.MILLISECONDS);
            Log.i(TAG, "requestMainSleep cmd ok (" + reason + ")");
            return;
        } catch (Exception e) {
            Log.d(TAG, "requestMainSleep cmd: " + e.getMessage());
        }
        tryShell(new String[]{"su", "-c", "cmd power sleep"});
        Log.i(TAG, "requestMainSleep su (" + reason + ")");
    }

    public static void sleepDisplayOnly(Context ctx, String reason) {
        apply(ctx, false);
        forceInhibitSubTouch();
        Log.i(TAG, "sleepDisplayOnly (" + reason + ")");
    }
}
