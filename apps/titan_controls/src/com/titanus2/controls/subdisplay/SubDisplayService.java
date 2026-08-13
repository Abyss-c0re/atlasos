package com.titanus2.controls.subdisplay;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import com.titanus2.controls.AgentBridge;
import com.titanus2.controls.InputSurfaceController;

/**
 * Rear clock while locked or sleeping (incl. DT2W lockscreen).
 * Turns off only after full unlock (USER_PRESENT). Main SCREEN_ON alone does not kill rear.
 * Optional: "Show while phone sleeps" — if off, rear stays dark when screen is fully off.
 */
public class SubDisplayService extends Service {
    public static final String ACTION_START = "com.titanus2.controls.subdisplay.START";
    public static final String ACTION_STOP = "com.titanus2.controls.subdisplay.STOP";
    public static final String ACTION_TOGGLE = "com.titanus2.controls.subdisplay.TOGGLE";
    public static final String ACTION_REFRESH = "com.titanus2.controls.subdisplay.REFRESH";
    public static final String ACTION_WAKE = "com.titanus2.controls.subdisplay.WAKE";
    public static final String ACTION_APPLY = "com.titanus2.controls.subdisplay.APPLY";

    private static final String TAG = "SubDisplayService";
    private static final String CH = "subdisplay";
    private static final long TICK_MS = 2000L;
    private static final long WATCHDOG_MS = 8000L;
    /**
     * 15.33 cube-asleep-park: main glass off + cube ON already steady — 2s tick /
     * 8s watchdog re-stamped plane + kickHardware + SELinux /data/adb spam
     * (Controls ~3% under sticky load). Sparse re-assert only.
     */
    private static final long TICK_CUBE_ASLEEP_MS = 15_000L;
    private static final long WATCHDOG_CUBE_ASLEEP_MS = 60_000L;

    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::tickRear;
    private final Runnable watchdog = this::watchdogTick;
    /** Cancel on SCREEN_OFF so delayed screen-on work cannot re-light main after sleep. */
    private final Runnable onScreenOnEdge = () -> {
        // 15.55: floor main glass only when already interactive — never wakeup.
        SubDisplayPower.healMainGlass(SubDisplayService.this, "screen-on");
        if (!isRearMode()) return;
        applySubtouchPolicy(SubDisplayService.this);
        ensureRear("screen-on", true);
        applySubtouchPolicy(SubDisplayService.this);
    };
    private final Runnable onScreenOnLate = () -> {
        if (!SubDisplayPower.isMainInteractive(SubDisplayService.this)) {
            // Sleep won — do not re-assert rear via DisplayManager path.
            Log.d(TAG, "screen-on-late aborted (main asleep)");
            return;
        }
        SubDisplayPower.healMainGlass(SubDisplayService.this, "screen-on-late");
        if (!isRearMode()) return;
        ensureRear("screen-on-late", false);
        applySubtouchPolicy(SubDisplayService.this);
    };
    private final Runnable onScreenOffEdge = () -> {
        if (!isRearMode()) return;
        ensureRear("screen-off", false);
    };
    private final Runnable onScreenOffLate = () -> {
        if (!isRearMode()) return;
        // Main must stay asleep: ensureRear routes cube/face to HW-only when !interactive.
        ensureRear("screen-off-late", false);
    };
    private final Runnable onScreenOffLate2 = () -> {
        if (!isRearMode()) return;
        ensureRear("screen-off-late2", false);
    };
    private boolean screenRx;
    /** ElapsedRealtime of last rear "activity" (start / wake / user prefs change). */
    private long rearActiveAt;
    private boolean rearIdle;

    private final BroadcastReceiver screenRxr = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String a = intent != null ? intent.getAction() : "?";
            Log.i(TAG, "main event " + a);
            if (Intent.ACTION_USER_PRESENT.equals(a)) {
                // Mode ON = user wants rear lit (Settings / side key). Do not
                // force power off on unlock — that left toggle "On" with a dark
                // panel (release regression 2026-07-20).
                SubDisplayPower.healMainGlass(SubDisplayService.this, "unlocked");
                if (!isRearMode()) return;
                h.post(() -> ensureRear("unlocked", true));
                h.postDelayed(() -> ensureRear("unlocked-late", false), 400);
                return;
            }
            if (Intent.ACTION_SCREEN_ON.equals(a)) {
                // Cancel any pending sleep-edge work so we do not race.
                h.removeCallbacks(onScreenOffEdge);
                h.removeCallbacks(onScreenOffLate);
                h.removeCallbacks(onScreenOffLate2);
                // Main DT2W / power-key wake (framework already interactive).
                // Heal main glass; re-assert rear only if rear mode is live.
                // Digitizer policy is mode-aware (cube/apps → rear only).
                h.post(onScreenOnEdge);
                h.postDelayed(onScreenOnLate, 800);
                return;
            }
            if (Intent.ACTION_SCREEN_OFF.equals(a)) {
                // Cancel wake heals — delayed screen-on must not re-light main.
                h.removeCallbacks(onScreenOnEdge);
                h.removeCallbacks(onScreenOnLate);
                // Main glass off: cube/apps keep rear if keepRearWhenOff (cube forces it).
                // NEVER force-launch activity after sleep — startActivity
                // re-wakes the shared power group and lights main (user: lie).
                // 15.55: ensureRear asleep → wakeRearHardwareOnly only.
                h.post(onScreenOffEdge);
                h.postDelayed(onScreenOffLate, 500);
                h.postDelayed(onScreenOffLate2, 1500);
            }
        }
    };

    public static void applyMode(Context c, SubDisplayPrefs.Mode mode) {
        if (mode == SubDisplayPrefs.Mode.STOCK) mode = SubDisplayPrefs.Mode.CUSTOM;
        // Cube mode requires Cube Contact installed (ROM or user APK).
        if (mode == SubDisplayPrefs.Mode.CUBE && !SubDisplayPrefs.cubeAppInstalled(c)) {
            Log.w(TAG, "cube mode requested but package missing — face");
            mode = SubDisplayPrefs.Mode.CUSTOM;
        }
        SubDisplayPrefs.Mode prev = SubDisplayPrefs.getMode(c);
        // Face clock only on CUSTOM; cube/apps/off hide clock overlay.
        if (mode != SubDisplayPrefs.Mode.CUSTOM) {
            SubDisplayFaceOverlay.hide(c);
            SubDisplayFaceActivity.dismiss(c);
        }
        if (mode != SubDisplayPrefs.Mode.CUBE) {
            SubDisplayCubeBridge.dismiss(c);
        }
        // Stamp mode before leave-apps dismiss so launcher onDestroy belt no-ops.
        SubDisplayPrefs.setMode(c, mode);
        // 15.13/15.14: leave Apps → finish rear home + forceOwn tile stack under Face.
        if (prev == SubDisplayPrefs.Mode.APPS && mode != SubDisplayPrefs.Mode.APPS) {
            try {
                cancelRearHomeBelt();
                SubDisplayLauncherActivity.dismiss(c);
            } catch (Exception e) {
                Log.w(TAG, "leave-apps dismiss: " + e.getMessage());
            }
        }
        SubDisplaySystemUi.apply(c);
        if (mode == SubDisplayPrefs.Mode.APPS) {
            // 15.4: apps-on-rear plane + digitizer + associate (pad-agent 2.67 SoT).
            // 15.5: also keep FGS/ensureRear (applyApps-only left panel without service).
            SubDisplayPower.applyApps(c);
            applySubtouchPolicy(c);
            cmd(c, ACTION_APPLY);
            return;
        }
        if (mode == SubDisplayPrefs.Mode.OFF) {
            cmd(c, ACTION_STOP);
            return;
        }
        if (mode == SubDisplayPrefs.Mode.CUBE) {
            // First-class rear cube: no face/launcher chrome, rear power only,
            // digitizer bound to display 2, keep lit while main locked/off.
            try { SubDisplayLauncherActivity.dismiss(c); } catch (Exception ignored) {}
            SubDisplayFaceOverlay.hide(c);
            SubDisplayFaceActivity.dismiss(c);
            SubDisplayPrefs.setKeepRearWhenOff(c, true); // cube owns rear independently
            // Power first (plane + APPLY stamp for pad-agent ioctl edge).
            SubDisplayPower.invalidatePowerState();
            SubDisplayPower.apply(c, true, Math.max(1, SubDisplayPrefs.getBrightnessPct(c)), true);
            applySubtouchPolicy(c);
            SubDisplayCubeBridge.show(c, true);
            cmd(c, ACTION_APPLY);
            // Re-stamp after ~300ms so pad-agent edge cannot miss a race with
            // face dismiss / startActivity (user residual: Cube selected, rear dark).
            try {
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    try {
                        SubDisplayPower.apply(c, true,
                            Math.max(1, SubDisplayPrefs.getBrightnessPct(c)), true);
                        applySubtouchPolicy(c);
                        cmd(c, ACTION_WAKE);
                    } catch (Exception e) {
                        Log.w(TAG, "cube re-power: " + e.getMessage());
                    }
                }, 300);
            } catch (Exception ignored) {}
            return;
        }
        cmd(c, ACTION_APPLY);
    }

    public static void start(Context c) {
        SubDisplayPrefs.Mode prev = SubDisplayPrefs.getMode(c);
        // Never clobber Cube (or Apps) into Face — boot restore used to call
        // start() and paint TitanRearFace over the sacred lattice.
        if (prev == SubDisplayPrefs.Mode.CUBE) {
            applyMode(c, SubDisplayPrefs.Mode.CUBE);
            return;
        }
        if (prev == SubDisplayPrefs.Mode.APPS) {
            // Side toggle Face while Apps was live — clear rear home first.
            try { SubDisplayLauncherActivity.dismiss(c); } catch (Exception ignored) {}
            try { clearRearAppsStack(c); } catch (Exception ignored) {}
        }
        SubDisplayPrefs.setMode(c, SubDisplayPrefs.Mode.CUSTOM);
        cmd(c, ACTION_START);
    }

    /**
     * Boot / reconnect: re-apply whatever mode is already selected.
     * Does not force Face. Cube stays cube; no clock chrome.
     */
    public static void restore(Context c) {
        if (c == null) return;
        SubDisplayPrefs.Mode m = SubDisplayPrefs.getMode(c);
        if (m == SubDisplayPrefs.Mode.OFF) return;
        if (m == SubDisplayPrefs.Mode.STOCK) m = SubDisplayPrefs.Mode.CUSTOM;
        applyMode(c, m);
    }

    public static void stop(Context c) {
        SubDisplayFaceOverlay.hide(c);
        SubDisplayFaceActivity.dismiss(c);
        SubDisplayCubeBridge.dismiss(c);
        SubDisplayPrefs.setMode(c, SubDisplayPrefs.Mode.OFF);
        // 15.13: Off after Apps must kill torch + rear home task.
        try { SubDisplayLauncherActivity.dismiss(c); } catch (Exception ignored) {}
        cmd(c, ACTION_STOP);
    }

    public static void toggle(Context c) {
        // Side-key / quick toggle: face On ↔ Off (never silent flip into apps).
        SubDisplayPrefs.Mode m = SubDisplayPrefs.getMode(c);
        if (m == SubDisplayPrefs.Mode.CUSTOM || m == SubDisplayPrefs.Mode.STOCK) stop(c);
        else if (m == SubDisplayPrefs.Mode.APPS) stop(c);
        else start(c);
    }

    public static void refresh(Context c) { cmd(c, ACTION_REFRESH); }
    public static void nudgeWake(Context c) {
        SubDisplayPrefs.Mode m = SubDisplayPrefs.getMode(c);
        if (m == SubDisplayPrefs.Mode.CUSTOM || m == SubDisplayPrefs.Mode.APPS
                || m == SubDisplayPrefs.Mode.CUBE) {
            cmd(c, ACTION_WAKE);
        }
    }
    public static void userActivity(Context c) {
        // Bump rear active period (independent of main)
        SubDisplayPrefs.Mode m = SubDisplayPrefs.getMode(c);
        if (m == SubDisplayPrefs.Mode.CUSTOM || m == SubDisplayPrefs.Mode.APPS
                || m == SubDisplayPrefs.Mode.CUBE) {
            cmd(c, ACTION_WAKE);
        }
    }

    /**
     * Publish rear-touch policy for current mode.
     * <p>
     * Face/off: park {@code sub_touch} (never second cursor). Apps <b>and Cube</b>
     * mode: live digitizer + associate to rear display only (pad-agent 2.67 SoT)
     * so touch rotates/interacts on the subdisplay without duplicating to main OS.
     * Never set input_surface=sub (that path is a second main cursor).
     */
    public static void applySubtouchPolicy(Context c) {
        if (c == null) return;
        SubDisplayPrefs.Mode mode = SubDisplayPrefs.getMode(c);
        boolean apps = mode == SubDisplayPrefs.Mode.APPS;
        boolean cube = mode == SubDisplayPrefs.Mode.CUBE;
        // Live digitizer for independent rear content (apps launcher OR OpenGL cube)
        boolean rearTouchLive = apps || cube;
        AgentBridge.put(c, AgentBridge.SUBTOUCH_INHIBIT, rearTouchLive ? "0" : "1");
        String sm = mode == SubDisplayPrefs.Mode.OFF ? "off"
            : (apps ? "apps" : (cube ? "cube" : "face"));
        AgentBridge.put(c, AgentBridge.SUB_MODE, sm);
        try {
            android.provider.Settings.Global.putString(
                c.getContentResolver(), "titan2_subtouch_inhibit", rearTouchLive ? "0" : "1");
            android.provider.Settings.Global.putString(
                c.getContentResolver(), "titan2_sub_mode", sm);
        } catch (Exception ignored) {}
        try {
            // Collapse stale surface=sub|both (rear never pad pointer surface).
            AgentBridge.put(c, "titan2_input_surface",
                InputSurfaceController.normalize(
                    AgentBridge.get(c, "titan2_input_surface", "none")));
        } catch (Exception ignored) {}
        if (rearTouchLive) {
            // Bind sub_touch → rear uniqueId so InputDispatcher targets display 2 only.
            String err = SubDisplayInput.associateSubTouchToRear(c);
            if (err != null) Log.d(TAG, sm + " associate: " + err);
            // Plane stamp for pad-agent edge apply (idc + service call 43)
            try {
                android.provider.Settings.Global.putString(
                    c.getContentResolver(), "titan2_subtouch_assoc", "pending");
            } catch (Exception ignored) {}
            AgentBridge.put(c, "titan2_subtouch_assoc", "pending");
        } else {
            SubDisplayInput.clearAssociation(c);
            SubDisplayPower.forceInhibitSubTouch();
        }
    }

    /**
     * User-intent: open rear Apps home (secondary launcher). Shared by Apps
     * mode UI, keyboard L, side-key “Open on rear”, and FGS APPLY re-home.
     * Does not re-enter applyMode.
     * <p>
     * 15.5 residual after 15.4: mode stamped digitizer but left rear blank
     * until a separate key action — intent≠result (Settings-only).
     * <p>
     * 15.6 residual after 15.5: Settings alone is not a launcher — OEM
     * SecondaryLauncher parity via {@link SubDisplayLauncherActivity} tiles.
     * <p>
     * 15.7 residual after 15.6: plane APPLY / Esc finish left blank digitizer
     * while mode stayed Apps — singleTask CLEAR_TOP re-home + APPLY edge.
     * <p>
     * 15.8 residual after 15.7: tile apps via app-context NEW_TASK left blank
     * rear after Back — launcher same-task stack when host is on display 2.
     * <p>
     * 15.9 residual after 15.8: setLaunchDisplayId same-task still sibling-task
     * blank — inherit host display first; NEW_TASK Settings gets home-under.
     * <p>
     * 15.10 residual after 15.9: Clock/Calc {@code singleTask}+own affinity ignore
     * inherit same-task — Back finishes their task and leaves blank rear
     * ({@code canHostTasks=false}). Occupancy watch re-homes when display 2
     * has no visible top activity while mode stays Apps.
     * <p>
     * 15.11 residual after 15.10: blank-watch re-homed mid forceOwn cold-start
     * (visible root with null topActivity + empty window &gt;900ms). Launch grace
     * + treat any visible rear root as occupied.
     * <p>
     * 15.12 residual after 15.11: Files generic VIEW+MIME chooser + Launcher
     * trampoline blank; home/tile start without power re-assert left dark panel.
     * Explicit tile targets + applyApps before launch.
     * <p>
     * 15.13 residual after 15.12: leave Apps (Face/Off) left rear home + torch
     * live under Face; Clock tile SHOW_ALARMS → HandleApiCalls blank. Dismiss
     * on mode leave + explicit DeskClock/Calculator components.
     * <p>
     * 15.14 residual after 15.13: leave Apps home-only dismiss left forceOwn
     * tiles (Files/Clock/Camera) on display 2 under Face. clearRearAppsStack
     * removes rear root tasks (keeps Settings UI + Face + system home).
     */
    public static void launchRearHome(Context c) {
        if (c == null) return;
        try {
            // 15.12: power + digitizer first so home is not dark under mode=Apps.
            if (SubDisplayPrefs.getMode(c) == SubDisplayPrefs.Mode.APPS) {
                try {
                    SubDisplayPower.applyApps(c);
                    applySubtouchPolicy(c);
                } catch (Exception pe) {
                    Log.d(TAG, "rear home power: " + pe.getMessage());
                }
            }
            noteAppsRearLaunch();
            SubDisplayLauncherActivity.launch(c);
            Log.i(TAG, "launch rear Apps home");
        } catch (Exception e) {
            Log.w(TAG, "rear home: " + e.getMessage());
            launchRearSettings(c);
        }
    }

    private static final Handler sHomeBelt =
        new Handler(Looper.getMainLooper());
    private static Runnable sHomeBeltRun;
    /** ElapsedRealtime when rear first looked empty in Apps mode (0 = occupied). */
    private long appsBlankSince;
    /**
     * 15.11: last successful rear activity start (home or tile). Blank-watch
     * must not re-home during forceOwn cold-start empty window.
     */
    private static volatile long sLastAppsRearLaunchElapsed;
    /** Grace after {@link #noteAppsRearLaunch} before blank empty can accumulate. */
    private static final long APPS_REAR_LAUNCH_GRACE_MS = 2500L;

    /**
     * 15.11: call from every successful rear start (tiles + home) so occupancy
     * re-home waits out cold-start of singleTask Clock/Calc/Camera.
     */
    public static void noteAppsRearLaunch() {
        sLastAppsRearLaunchElapsed = SystemClock.elapsedRealtime();
        // 15.20: entering Apps rear again — stop demoting Clock on main.
        cancelLeaveFocusBelt();
    }

    /**
     * One-shot re-home after launcher destroy while mode stays Apps (15.9).
     * Debounced so thrash destroy/create does not stack launches.
     */
    public static void scheduleRearHomeBelt(Context c, long delayMs) {
        if (c == null) return;
        if (SubDisplayPrefs.getMode(c) != SubDisplayPrefs.Mode.APPS) return;
        final Context app = c.getApplicationContext() != null ? c.getApplicationContext() : c;
        if (sHomeBeltRun != null) sHomeBelt.removeCallbacks(sHomeBeltRun);
        sHomeBeltRun = () -> {
            sHomeBeltRun = null;
            if (SubDisplayPrefs.getMode(app) != SubDisplayPrefs.Mode.APPS) return;
            try {
                Log.i(TAG, "rear home belt (mode still Apps after launcher destroy)");
                launchRearHome(app);
            } catch (Exception e) {
                Log.w(TAG, "home belt: " + e.getMessage());
            }
        };
        sHomeBelt.postDelayed(sHomeBeltRun, Math.max(0L, delayMs));
    }

    /** Cancel pending re-home (leave Apps / Off). */
    public static void cancelRearHomeBelt() {
        if (sHomeBeltRun != null) {
            sHomeBelt.removeCallbacks(sHomeBeltRun);
            sHomeBeltRun = null;
        }
    }

    /**
     * 15.14–15.18: leave Apps residual after 15.13 home-only dismiss — forceOwn
     * tile tasks (DocumentsUI Files, DeskClock, Calculator, Aperture) stayed on
     * display 2 under Face.
     * <p>
     * 15.15 residual after 15.14: blind FORCE_STOP of every known tile package
     * murdered main-display Clock/Files/Calc even when never opened on rear.
     * Session-track + shell/runningTasks discover rear packages; prefer
     * {@code am stack remove} / {@code am task remove} (display-scoped).
     * <p>
     * 15.16 residual after 15.15: session-tracked packages were still
     * FORCE_STOPped package-wide when stack remove failed or when the tile had
     * already left rear (main Clock/Files die after earlier rear open). Now:
     * collect rear <b>taskIds</b> → shell task remove → re-probe rear → FORCE_STOP
     * only packages <b>still confirmed on rear</b> (never track-only murder).
     * <p>
     * 15.17 residual after 15.16: singleTask Clock/Calc <b>move</b> their one
     * task onto rear (same taskId as main). {@code am task remove} / FORCE_STOP
     * killed that task → main Clock gone. Prefer
     * {@code am display move-stack STACK → display 0}; never FORCE_STOP
     * {@link SubDisplayLauncherActivity#isSharedSingleTaskTilePackage}.
     * <p>
     * 15.18 residual after 15.17: move-stack / pull-main left shared singleTask
     * Clock focused on main (intent=leave→Face, result=main yanked to Clock).
     * Capture main front <b>before</b> leave; after any move restore that front
     * so the process lives on main but focus is unchanged.
     * <p>
     * 15.19 residual after 15.18: async pull/startActivity can re-front Clock
     * after restore returns. Post-settle re-probe main focus; demote shared
     * singleTask thieves and re-restore pre-leave front (intent=result).
     * <p>
     * 15.20 residual after 15.19: single 280+180ms sleep verify still loses to
     * later AM scheduling (second-wave REORDER_TO_FRONT). Handler leave-focus
     * belt re-probes at 400/900/1600ms without longer binder-thread sleep.
     * <p>
     * 15.21 residual after 15.20: demote last-resort HOME flashed launcher when
     * move-to-back denied; third-wave REORDER after 1600ms; shell restore
     * without REORDER_TO_FRONT created parallel activities. Pin
     * setFocusedTask + no-HOME demote on belt + 2800/4200ms passes +
     * {@code am start -f 0x20000}.
     * <p>
     * 15.22 residual after 15.21: pinMainFocus failed closed when ATM
     * setFocusedTask denied / taskId missing (no REORDER pin fallback);
     * demote only hit {@code now.taskId} while sibling shared singleTask
     * (Calc after Clock) stayed sticky; pre-leave taskId went stale after
     * process death. Rebind live want.taskId each pass + demote all main
     * shared thieves + pin falls through to REORDER restore.
     */
    public static void clearRearAppsStack(Context c) {
        if (c == null) return;
        cancelRearHomeBelt();
        cancelLeaveFocusBelt();
        Context app = c.getApplicationContext() != null ? c.getApplicationContext() : c;
        int rearId = 2;
        try {
            android.view.Display rear = SubDisplayHelper.findRear(app);
            if (rear != null) rearId = rear.getDisplayId();
        } catch (Exception ignored) {}
        // 15.18: snapshot main focus before move-stack steals it.
        MainFront mainFront = captureMainFront(app);
        // Session track (process-death prefs) — allowlist / priority only; not
        // a sole FORCE_STOP reason (15.16).
        java.util.Set<String> tracked = new java.util.LinkedHashSet<>();
        try {
            tracked.addAll(SubDisplayLauncherActivity.takeRearTilePackages(app));
        } catch (Exception e) {
            try {
                tracked.addAll(SubDisplayLauncherActivity.takeRearTilePackages());
            } catch (Exception ignored) {}
        }
        java.util.Set<String> rearPkgs = new java.util.LinkedHashSet<>();
        java.util.Set<Integer> rearTaskIds = new java.util.LinkedHashSet<>();
        // 15.15/15.16: discover packages + taskIds currently on rear.
        try {
            discoverRearPkgsViaRunningTasks(app, rearId, rearPkgs, rearTaskIds, tracked);
        } catch (Exception e) {
            Log.w(TAG, "clearRearAppsStack runningTasks: " + e.getMessage());
        }
        java.util.List<int[]> rearStacks = new java.util.ArrayList<>(); // [stackId]
        java.util.Map<Integer, String> stackPkg = new java.util.HashMap<>();
        try {
            discoverRearStacksViaShell(rearId, rearStacks, stackPkg, rearPkgs, rearTaskIds);
        } catch (Exception e) {
            Log.w(TAG, "clearRearAppsStack shell list: " + e.getMessage());
        }
        int removed = 0;
        int shellRm = 0;
        int taskRm = 0;
        int moved = 0;
        // 15.17: move known/tracked tile stacks to main FIRST (singleTask preserve).
        for (int[] st : rearStacks) {
            if (st == null || st.length < 1) continue;
            int stackId = st[0];
            String pkg = stackPkg.get(stackId);
            if (pkg != null) {
                try {
                    android.content.ComponentName fake =
                        new android.content.ComponentName(pkg, "x");
                    if (keepRearTaskOnLeave(fake)) continue;
                } catch (Exception ignored) {}
            }
            boolean tile = pkg != null
                && (SubDisplayLauncherActivity.isKnownRearTilePackage(pkg)
                    || tracked.contains(pkg)
                    || SubDisplayLauncherActivity.isSharedSingleTaskTilePackage(pkg));
            // Always try move for tile stacks; also try when pkg unknown but stack
            // is rear standard (pull may fail harmlessly).
            if (tile || pkg == null) {
                if (atmMoveRootTaskToMain(stackId, rearId)
                        || shellMoveStackToMain(stackId, rearId)) {
                    moved++;
                    removed++;
                    Log.i(TAG, "clearRearAppsStack move-stack id=" + stackId
                        + " →0 pkg=" + pkg);
                    continue;
                }
            }
            // Multi-instance forceOwn: stack remove after move fails.
            // Never stack-remove shared singleTask (would kill main Clock).
            if (pkg != null
                    && SubDisplayLauncherActivity.isSharedSingleTaskTilePackage(pkg)) {
                Log.w(TAG, "clearRearAppsStack skip remove shared singleTask stack="
                    + stackId + " pkg=" + pkg);
                // App-uid am start is denied (shell package residual) — Intent pull.
                if (appPullPackageToMain(app, pkg)) {
                    moved++;
                    removed++;
                    Log.i(TAG, "clearRearAppsStack app-pull-main pkg=" + pkg);
                }
                continue;
            }
            if (shellStackRemove(stackId)) {
                shellRm++;
                removed++;
                Log.i(TAG, "clearRearAppsStack shell stack remove id=" + stackId
                    + " pkg=" + pkg);
            }
        }
        // 15.16/15.17: per-task remove only after move — never murder shared singleTask.
        for (Integer tid : new java.util.ArrayList<>(rearTaskIds)) {
            if (tid == null || tid < 0) continue;
            // Prefer move-stack when stack id == task id (common RootTask layout).
            if (atmMoveRootTaskToMain(tid, rearId)
                    || shellMoveStackToMain(tid, rearId)) {
                moved++;
                removed++;
                Log.i(TAG, "clearRearAppsStack move-task-as-stack id=" + tid + " →0");
                continue;
            }
            // RootTask id often equals taskId — stackPkg key is stack/task id.
            String pkgForTid = stackPkg.get(tid);
            if (pkgForTid != null
                    && SubDisplayLauncherActivity.isSharedSingleTaskTilePackage(
                        pkgForTid)) {
                Log.w(TAG, "clearRearAppsStack skip task remove shared singleTask tid="
                    + tid + " pkg=" + pkgForTid);
                // Last pull: start package on display 0 (singleTask relocates).
                // 15.18: app Intent first — ProcessBuilder am is shell-uid denied.
                if (appPullPackageToMain(app, pkgForTid)
                        || shellPullPackageToMain(pkgForTid)) {
                    moved++;
                    removed++;
                    Log.i(TAG, "clearRearAppsStack pull-main pkg=" + pkgForTid);
                }
                continue;
            }
            // Unknown pkg for tid: if ONLY shared singleTask pkgs are on rear,
            // skip blind remove (safer than murdering main Clock). Multi-instance
            // DocumentsUI still removes when mapped or when non-shared rear pkgs.
            if (pkgForTid == null && !rearPkgs.isEmpty()) {
                boolean anyNonShared = false;
                for (String p : rearPkgs) {
                    if (!SubDisplayLauncherActivity.isSharedSingleTaskTilePackage(p)
                            && SubDisplayLauncherActivity.isKnownRearTilePackage(p)) {
                        anyNonShared = true;
                        break;
                    }
                }
                if (!anyNonShared) {
                    Log.w(TAG, "clearRearAppsStack skip task remove tid=" + tid
                        + " (rear only shared singleTask; move already tried)");
                    continue;
                }
            }
            boolean ok = shellTaskRemove(tid);
            if (!ok) ok = finishOwnAppTask(app, tid);
            if (ok) {
                taskRm++;
                removed++;
                Log.i(TAG, "clearRearAppsStack task remove id=" + tid);
            }
        }
        try {
            // Best-effort ATM (greylist/exemption trains).
            Object atm = Class.forName("android.app.ActivityTaskManager")
                .getMethod("getService")
                .invoke(null);
            if (atm != null) {
                @SuppressWarnings("unchecked")
                java.util.List<Object> infos = (java.util.List<Object>) atm.getClass()
                    .getMethod("getAllRootTaskInfos")
                    .invoke(atm);
                java.lang.reflect.Method removeTask = null;
                java.lang.reflect.Method moveRootTaskToDisplay = null;
                try {
                    removeTask = atm.getClass().getMethod("removeTask", int.class);
                } catch (NoSuchMethodException ignored) {}
                try {
                    moveRootTaskToDisplay = atm.getClass()
                        .getMethod("moveRootTaskToDisplay", int.class, int.class);
                } catch (NoSuchMethodException ignored) {}
                if (infos != null) {
                    for (Object info : infos) {
                        if (info == null) continue;
                        Class<?> cl = info.getClass();
                        int did;
                        try {
                            did = cl.getField("displayId").getInt(info);
                        } catch (Exception e) {
                            try {
                                Object v = cl.getMethod("getDisplayId").invoke(info);
                                if (v instanceof Integer) did = (Integer) v;
                                else continue;
                            } catch (Exception e2) {
                                continue;
                            }
                        }
                        if (did != rearId) continue;
                        int activityType = 1;
                        try {
                            activityType = cl.getField("activityType").getInt(info);
                        } catch (Exception e) {
                            try {
                                Object v = cl.getMethod("getActivityType").invoke(info);
                                if (v instanceof Integer) activityType = (Integer) v;
                            } catch (Exception ignored) {}
                        }
                        if (activityType == 2 || activityType == 3) continue;
                        android.content.ComponentName top = null;
                        try {
                            Object t = cl.getField("topActivity").get(info);
                            if (t instanceof android.content.ComponentName) {
                                top = (android.content.ComponentName) t;
                            }
                        } catch (Exception e) {
                            try {
                                Object t = cl.getMethod("getTopActivity").invoke(info);
                                if (t instanceof android.content.ComponentName) {
                                    top = (android.content.ComponentName) t;
                                }
                            } catch (Exception ignored) {}
                        }
                        if (top != null && keepRearTaskOnLeave(top)) continue;
                        if (top != null && top.getPackageName() != null) {
                            String p = top.getPackageName();
                            // only add known tile / already-tracked — never random.
                            if (SubDisplayLauncherActivity.isKnownRearTilePackage(p)
                                    || tracked.contains(p)) {
                                rearPkgs.add(p);
                            }
                        }
                        int taskId = -1;
                        try {
                            taskId = cl.getField("taskId").getInt(info);
                        } catch (Exception e) {
                            try {
                                Object v = cl.getMethod("getTaskId").invoke(info);
                                if (v instanceof Integer) taskId = (Integer) v;
                            } catch (Exception ignored) {}
                        }
                        if (taskId < 0) continue;
                        String topPkg = top != null ? top.getPackageName() : null;
                        boolean shared = topPkg != null
                            && SubDisplayLauncherActivity
                                .isSharedSingleTaskTilePackage(topPkg);
                        // 15.17/15.18: ATM move to main before any remove — verify
                        // not still on rear (bare invoke residual claimed ok).
                        boolean ok = false;
                        if (atmMoveRootTaskToMain(taskId, rearId)) {
                            ok = true;
                            moved++;
                            Log.i(TAG, "clearRearAppsStack ATM move taskId="
                                + taskId + " →0 top="
                                + (top != null ? top.flattenToShortString() : "?"));
                        }
                        if (!ok && shellMoveStackToMain(taskId, rearId)) {
                            ok = true;
                            moved++;
                        }
                        if (!ok && shared) {
                            if (appPullPackageToMain(app, topPkg)
                                    || shellPullPackageToMain(topPkg)) {
                                ok = true;
                                moved++;
                            }
                            // Never removeTask/FORCE_STOP shared singleTask.
                            if (ok) {
                                removed++;
                            }
                            continue;
                        }
                        if (!ok && removeTask != null) {
                            try {
                                Object r = removeTask.invoke(atm, taskId);
                                ok = !(r instanceof Boolean) || Boolean.TRUE.equals(r);
                            } catch (Exception e) {
                                Log.w(TAG, "removeTask " + taskId + ": " + e.getMessage());
                            }
                        }
                        if (!ok) {
                            ok = shellTaskRemove(taskId);
                        }
                        if (!ok) {
                            ok = finishOwnAppTask(app, taskId);
                        }
                        if (ok) {
                            removed++;
                            taskRm++;
                            Log.i(TAG, "clearRearAppsStack remove taskId=" + taskId
                                + " top=" + (top != null ? top.flattenToShortString() : "?"));
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "clearRearAppsStack ATM: " + e.getMessage());
        }
        // 15.16/15.17: re-probe rear — FORCE_STOP only multi-instance still-rear;
        // never shared singleTask (15.17 residual).
        java.util.Set<String> stillRear = new java.util.LinkedHashSet<>();
        java.util.Set<Integer> stillTasks = new java.util.LinkedHashSet<>();
        try {
            discoverRearPkgsViaRunningTasks(app, rearId, stillRear, stillTasks, tracked);
        } catch (Exception e) {
            Log.w(TAG, "clearRearAppsStack re-probe tasks: " + e.getMessage());
        }
        try {
            java.util.List<int[]> rs2 = new java.util.ArrayList<>();
            java.util.Map<Integer, String> sp2 = new java.util.HashMap<>();
            discoverRearStacksViaShell(rearId, rs2, sp2, stillRear, stillTasks);
            // 15.17 second-pass move for stacks still on rear after ATM path.
            for (int[] st : rs2) {
                if (st == null || st.length < 1) continue;
                int sid = st[0];
                String p = sp2.get(sid);
                if (p != null) {
                    try {
                        android.content.ComponentName fake =
                            new android.content.ComponentName(p, "x");
                        if (keepRearTaskOnLeave(fake)) continue;
                    } catch (Exception ignored) {}
                }
                if (atmMoveRootTaskToMain(sid, rearId)
                        || shellMoveStackToMain(sid, rearId)) {
                    moved++;
                    removed++;
                    stillRear.remove(p);
                    Log.i(TAG, "clearRearAppsStack re-pass move id=" + sid
                        + " →0 pkg=" + p);
                } else if (p != null
                        && SubDisplayLauncherActivity.isSharedSingleTaskTilePackage(p)
                        && (appPullPackageToMain(app, p)
                            || shellPullPackageToMain(p))) {
                    moved++;
                    stillRear.remove(p);
                    Log.i(TAG, "clearRearAppsStack re-pass pull-main pkg=" + p);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "clearRearAppsStack re-probe shell: " + e.getMessage());
        }
        int stopped = 0;
        for (String pkg : new java.util.ArrayList<>(stillRear)) {
            if (pkg == null || pkg.isEmpty()) continue;
            if ("com.titanus2.controls".equals(pkg)) continue;
            if ("com.android.settings".equals(pkg)) continue;
            if ("com.android.systemui".equals(pkg)) continue;
            if ("com.android.launcher3".equals(pkg)) continue;
            // Allowlist: known tile packages only (tracked foreign Settings/etc skip).
            if (!SubDisplayLauncherActivity.isKnownRearTilePackage(pkg)) {
                Log.d(TAG, "clearRearAppsStack skip non-tile " + pkg);
                continue;
            }
            // 15.17: never FORCE_STOP shared singleTask (main Clock residual).
            if (SubDisplayLauncherActivity.isSharedSingleTaskTilePackage(pkg)) {
                Log.w(TAG, "clearRearAppsStack skip force-stop shared singleTask "
                    + pkg);
                if (appPullPackageToMain(app, pkg) || shellPullPackageToMain(pkg)) {
                    moved++;
                    Log.i(TAG, "clearRearAppsStack final pull-main pkg=" + pkg);
                }
                continue;
            }
            if (forceStopPackage(app, pkg)) {
                stopped++;
                Log.i(TAG, "clearRearAppsStack force-stop still-rear " + pkg);
            }
        }
        // Own AppTasks: finish any remaining SubDisplayLauncher stacks.
        try {
            android.app.ActivityManager am = (android.app.ActivityManager)
                app.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null && Build.VERSION.SDK_INT >= 21) {
                for (android.app.ActivityManager.AppTask at : am.getAppTasks()) {
                    try {
                        android.app.ActivityManager.RecentTaskInfo ti = at.getTaskInfo();
                        if (ti == null) continue;
                        android.content.ComponentName base = ti.baseActivity != null
                            ? ti.baseActivity : ti.topActivity;
                        if (base == null) continue;
                        String cls = base.getClassName();
                        if (cls != null && cls.contains("SubDisplayLauncherActivity")) {
                            at.finishAndRemoveTask();
                            removed++;
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "clearRearAppsStack appTasks: " + e.getMessage());
        }
        // 15.18–15.22: move/pull left shared singleTask focused on main —
        // restore, short settle verify, rebind+pin focus, then Handler
        // leave-focus belt for second/third-wave async REORDER_TO_FRONT.
        int focusRestored = 0;
        int focusVerified = 0;
        int focusBelt = 0;
        int focusPinned = 0;
        int focusRebound = 0;
        if (moved > 0 && mainFront != null && mainFront.hasTarget()) {
            if (appRestoreMainFront(app, mainFront)
                    || shellRestoreMainFront(mainFront)) {
                focusRestored = 1;
                Log.i(TAG, "clearRearAppsStack restore main front taskId="
                    + mainFront.taskId + " cmp=" + mainFront.component
                    + " pkg=" + mainFront.pkg);
            } else {
                Log.w(TAG, "clearRearAppsStack restore main front failed taskId="
                    + mainFront.taskId + " cmp=" + mainFront.component);
            }
            // 15.22: rebind live taskId after first restore (stale pin residual).
            if (rebindMainFrontTaskId(app, mainFront)) {
                focusRebound = 1;
            }
            // 15.19: settle + re-probe. Clock pull REORDER_TO_FRONT can land
            // after restore's startActivity; demote thief and re-restore.
            try {
                Thread.sleep(280);
            } catch (InterruptedException ignored) {}
            MainFront now = captureMainFront(app);
            if (mainFocusStolenByMovedTile(mainFront, now)) {
                Log.w(TAG, "clearRearAppsStack focus-steal after restore now="
                    + (now != null ? now.pkg : "null")
                    + " want=" + mainFront.pkg);
                // 15.22: demote all main shared thieves (not only now.taskId).
                demoteSharedThievesOnMain(app, mainFront);
                // 15.21/15.22: pin want (REORDER fallback when ATM denied).
                pinMainFocus(app, mainFront);
                if (appRestoreMainFront(app, mainFront)
                        || shellRestoreMainFront(mainFront)) {
                    focusRestored = 1;
                    rebindMainFrontTaskId(app, mainFront);
                    pinMainFocus(app, mainFront);
                    try {
                        Thread.sleep(180);
                    } catch (InterruptedException ignored) {}
                    MainFront again = captureMainFront(app);
                    if (!mainFocusStolenByMovedTile(mainFront, again)) {
                        focusVerified = 1;
                    } else {
                        Log.w(TAG, "clearRearAppsStack re-restore still stolen pkg="
                            + (again != null ? again.pkg : "null"));
                    }
                }
            } else {
                focusVerified = 1;
                Log.i(TAG, "clearRearAppsStack focus verify ok pkg="
                    + (now != null ? now.pkg : "null"));
            }
            // 15.21/15.22: always pin pre-leave front after leave-move (belt
            // still owns late async waves). Rebind before pin.
            rebindMainFrontTaskId(app, mainFront);
            if (pinMainFocus(app, mainFront)) {
                focusPinned = 1;
            }
            // 15.20–15.22: always arm leave-focus belt when we moved singleTask
            // tiles — second/third-wave async can steal after short sleep.
            scheduleLeaveFocusBelt(app, mainFront);
            focusBelt = 1;
        }
        Log.i(TAG, "clearRearAppsStack done displayId=" + rearId
            + " removed=" + removed + " moved=" + moved + " shellRm=" + shellRm
            + " taskRm=" + taskRm + " forceStop=" + stopped
            + " focusRestored=" + focusRestored
            + " focusVerified=" + focusVerified
            + " focusPinned=" + focusPinned
            + " focusRebound=" + focusRebound
            + " focusBelt=" + focusBelt
            + " rearBefore=" + rearPkgs.size()
            + " stillRear=" + stillRear.size() + " tracked=" + tracked.size());
    }

    /**
     * 15.20–15.22: multi-pass main-focus guard after leave-move. 15.19 single
     * settle still lost to AM second-wave REORDER_TO_FRONT of Clock/Calc.
     * 15.21: no HOME demote (launcher flash residual); pin + longer belt.
     * 15.22: rebind live taskId + demote all shared thieves + REORDER pin.
     */
    private static final Handler sLeaveFocusBelt =
        new Handler(Looper.getMainLooper());
    private static Runnable sLeaveFocusBeltRun;
    /** 15.21: late passes cover third-wave after 15.20 1600ms end. */
    private static final long[] LEAVE_FOCUS_BELT_MS =
        {400L, 900L, 1600L, 2800L, 4200L};

    /** Cancel pending leave-focus belt (Apps re-enter / new leave). */
    public static void cancelLeaveFocusBelt() {
        if (sLeaveFocusBeltRun != null) {
            sLeaveFocusBelt.removeCallbacks(sLeaveFocusBeltRun);
            sLeaveFocusBeltRun = null;
        }
    }

    /**
     * 15.20–15.22: schedule delayed pin+demote+restore passes. Pre-leave
     * package/component is immutable; taskId is rebound each pass (15.22).
     */
    private static void scheduleLeaveFocusBelt(Context app, MainFront want) {
        if (app == null || want == null || !want.hasTarget()) return;
        cancelLeaveFocusBelt();
        final Context ctx = app.getApplicationContext() != null
            ? app.getApplicationContext() : app;
        final MainFront target = want;
        final int[] pass = {0};
        sLeaveFocusBeltRun = new Runnable() {
            @Override
            public void run() {
                int i = pass[0];
                if (i >= LEAVE_FOCUS_BELT_MS.length) {
                    sLeaveFocusBeltRun = null;
                    return;
                }
                // Stop guarding once user returned to Apps mode (rear home live).
                try {
                    if (SubDisplayPrefs.getMode(ctx) == SubDisplayPrefs.Mode.APPS) {
                        Log.i(TAG, "leave-focus belt abort (mode Apps again)");
                        sLeaveFocusBeltRun = null;
                        return;
                    }
                } catch (Exception ignored) {}
                // 15.22: refresh want.taskId before pin (stale after death/reorder).
                rebindMainFrontTaskId(ctx, target);
                MainFront now = captureMainFront(ctx);
                if (mainFocusStolenByMovedTile(target, now)) {
                    Log.w(TAG, "leave-focus belt pass=" + i + " steal pkg="
                        + (now != null ? now.pkg : "null")
                        + " want=" + target.pkg);
                    // 15.22: demote all main shared thieves (sibling Clock+Calc).
                    demoteSharedThievesOnMain(ctx, target);
                    // 15.21/15.22: pin want (REORDER fallback); never HOME-demote.
                    pinMainFocus(ctx, target);
                    if (appRestoreMainFront(ctx, target)
                            || shellRestoreMainFront(target)) {
                        rebindMainFrontTaskId(ctx, target);
                        pinMainFocus(ctx, target);
                        Log.i(TAG, "leave-focus belt restore pass=" + i
                            + " cmp=" + target.component + " pkg=" + target.pkg
                            + " taskId=" + target.taskId);
                    }
                } else {
                    // Re-pin healthy front so late REORDER is less sticky.
                    pinMainFocus(ctx, target);
                    Log.i(TAG, "leave-focus belt pass=" + i + " ok pkg="
                        + (now != null ? now.pkg : "null")
                        + " wantTask=" + target.taskId);
                }
                pass[0] = i + 1;
                if (pass[0] < LEAVE_FOCUS_BELT_MS.length) {
                    long next = LEAVE_FOCUS_BELT_MS[pass[0]];
                    long prev = LEAVE_FOCUS_BELT_MS[i];
                    long delay = Math.max(50L, next - prev);
                    sLeaveFocusBelt.postDelayed(this, delay);
                } else {
                    sLeaveFocusBeltRun = null;
                    Log.i(TAG, "leave-focus belt done");
                }
            }
        };
        sLeaveFocusBelt.postDelayed(sLeaveFocusBeltRun, LEAVE_FOCUS_BELT_MS[0]);
        Log.i(TAG, "leave-focus belt armed delays=400/900/1600/2800/4200 want="
            + want.pkg + " cmp=" + want.component);
    }

    /**
     * 15.19: true when main focus is a shared singleTask tile (Clock/Calc/…)
     * that is not the pre-leave front — leave Apps intent≠result residual.
     */
    private static boolean mainFocusStolenByMovedTile(MainFront want, MainFront now) {
        if (now == null || now.pkg == null || now.pkg.isEmpty()) return false;
        if (now.home) return false;
        if (!SubDisplayLauncherActivity.isSharedSingleTaskTilePackage(now.pkg)) {
            return false;
        }
        if (want != null && want.pkg != null && want.pkg.equals(now.pkg)) {
            // Pre-leave was already that tile (rare; singleTask rear open).
            return false;
        }
        return true;
    }

    /**
     * 15.19: send a main-display task to the back without killing it
     * (Clock/Calc process must survive for next rear open).
     * 15.21: {@code allowHome=false} on leave-belt — HOME flash residual left
     * launcher when move-to-back was denied and restore raced.
     */
    private static boolean demoteMainTask(int taskId) {
        return demoteMainTask(taskId, /*allowHome*/ true);
    }

    private static boolean demoteMainTask(int taskId, boolean allowHome) {
        if (taskId <= 0) return false;
        // Shell first (works rootless when shell has MOVE_TASK).
        String err = shellOut(new String[]{"/system/bin/am", "task",
            "move-to-back", String.valueOf(taskId)}, 3000);
        if (err != null && !shellStartFailed(err)
                && !err.toLowerCase().contains("unknown")
                && !err.toLowerCase().contains("error")) {
            Log.i(TAG, "demoteMainTask shell taskId=" + taskId
                + " out=" + err.trim());
            return true;
        }
        try {
            Object atm = Class.forName("android.app.ActivityTaskManager")
                .getMethod("getService")
                .invoke(null);
            if (atm != null) {
                try {
                    atm.getClass()
                        .getMethod("moveTaskToBack", int.class, boolean.class)
                        .invoke(atm, taskId, true);
                    Log.i(TAG, "demoteMainTask ATM taskId=" + taskId);
                    return true;
                } catch (NoSuchMethodException ns) {
                    try {
                        atm.getClass().getMethod("moveTaskToBack", int.class)
                            .invoke(atm, taskId);
                        Log.i(TAG, "demoteMainTask ATM1 taskId=" + taskId);
                        return true;
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "demoteMainTask: " + e.getMessage());
        }
        // Last resort HOME only when caller allows (not leave-belt 15.21).
        if (!allowHome) {
            Log.d(TAG, "demoteMainTask skip HOME taskId=" + taskId);
            return false;
        }
        String homeErr = shellOut(new String[]{"/system/bin/am", "start",
            "--display", "0",
            "-a", "android.intent.action.MAIN",
            "-c", "android.intent.category.HOME"}, 3000);
        if (!shellStartFailed(homeErr)) {
            Log.i(TAG, "demoteMainTask via HOME taskId=" + taskId);
            return true;
        }
        return false;
    }

    /**
     * 15.21/15.22: pin pre-leave main front so late REORDER_TO_FRONT of
     * Clock/Calc is less sticky. Prefer ATM setFocusedTask / moveTaskToFront;
     * no HOME. 15.22 residual: when taskId missing or ATM/shell denied, fall
     * through to REORDER restore (pin was fail-closed no-op after 15.21).
     */
    private static boolean pinMainFocus(Context app, MainFront want) {
        if (want == null || !want.hasTarget()) return false;
        if (want.taskId > 0) {
            try {
                Object atm = Class.forName("android.app.ActivityTaskManager")
                    .getMethod("getService")
                    .invoke(null);
                if (atm != null) {
                    try {
                        atm.getClass().getMethod("setFocusedTask", int.class)
                            .invoke(atm, want.taskId);
                        Log.i(TAG, "pinMainFocus setFocusedTask taskId="
                            + want.taskId);
                        return true;
                    } catch (NoSuchMethodException ns) {
                        try {
                            atm.getClass()
                                .getMethod("moveTaskToFront", int.class, int.class)
                                .invoke(atm, want.taskId, 0);
                            Log.i(TAG, "pinMainFocus moveTaskToFront taskId="
                                + want.taskId);
                            return true;
                        } catch (Exception ignored) {}
                    } catch (Exception e) {
                        Log.d(TAG, "pinMainFocus setFocused: " + e.getMessage());
                        try {
                            atm.getClass()
                                .getMethod("moveTaskToFront", int.class, int.class)
                                .invoke(atm, want.taskId, 0);
                            Log.i(TAG, "pinMainFocus moveTaskToFront taskId="
                                + want.taskId);
                            return true;
                        } catch (Exception ignored) {}
                    }
                }
            } catch (Exception e) {
                Log.d(TAG, "pinMainFocus: " + e.getMessage());
            }
            // Shell focus (AOSP toybox/am may ignore unknown subcommands).
            String ferr = shellOut(new String[]{"/system/bin/am", "task",
                "focus", String.valueOf(want.taskId)}, 2000);
            if (ferr != null && !shellStartFailed(ferr)
                    && !ferr.toLowerCase().contains("unknown")
                    && !ferr.toLowerCase().contains("error")
                    && !ferr.toLowerCase().contains("usage")) {
                Log.i(TAG, "pinMainFocus shell focus taskId=" + want.taskId
                    + " out=" + ferr.trim());
                return true;
            }
        }
        // 15.22: REORDER pin fallback (ATM denied / taskId≤0 residual).
        if (app != null && appRestoreMainFront(app, want)) {
            Log.i(TAG, "pinMainFocus REORDER app cmp=" + want.component
                + " pkg=" + want.pkg);
            return true;
        }
        if (shellRestoreMainFront(want)) {
            Log.i(TAG, "pinMainFocus REORDER shell cmp=" + want.component
                + " pkg=" + want.pkg);
            return true;
        }
        return false;
    }

    /**
     * 15.22: refresh {@link MainFront#taskId} from live main-display tasks for
     * want.pkg / component. Pre-leave taskId goes stale after process death or
     * REORDER recreate — setFocusedTask on dead id is a silent no-op.
     */
    private static boolean rebindMainFrontTaskId(Context app, MainFront want) {
        if (want == null || !want.hasTarget()) return false;
        if (want.home) return false;
        String wantPkg = want.pkg;
        String wantCls = null;
        if (want.component != null) {
            int slash = want.component.indexOf('/');
            if (slash > 0) {
                if (wantPkg == null || wantPkg.isEmpty()) {
                    wantPkg = want.component.substring(0, slash);
                }
                wantCls = want.component.substring(slash + 1);
                // Drop leading '.' relative class only if already absolute.
                int sp = wantCls.indexOf(' ');
                if (sp > 0) wantCls = wantCls.substring(0, sp);
            }
        }
        if (wantPkg == null || wantPkg.isEmpty()) return false;
        int oldId = want.taskId;
        // Prefer runningTasks (display-scoped).
        if (app != null) {
            android.app.ActivityManager am = (android.app.ActivityManager)
                app.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                try {
                    //noinspection deprecation
                    java.util.List<android.app.ActivityManager.RunningTaskInfo> tasks =
                        am.getRunningTasks(48);
                    if (tasks != null) {
                        for (android.app.ActivityManager.RunningTaskInfo ti : tasks) {
                            if (ti == null) continue;
                            int did = runningTaskDisplayId(ti);
                            if (did != 0 && did != -1) continue;
                            android.content.ComponentName top = ti.topActivity != null
                                ? ti.topActivity : ti.baseActivity;
                            if (top == null) continue;
                            if (!wantPkg.equals(top.getPackageName())) continue;
                            if (wantCls != null && top.getClassName() != null
                                    && !wantCls.equals(top.getClassName())
                                    && !top.getClassName().endsWith(wantCls)) {
                                // Prefer exact cls match when known; keep scanning.
                                continue;
                            }
                            if (ti.taskId > 0) {
                                want.taskId = ti.taskId;
                                if (want.component == null && top.getClassName() != null) {
                                    want.component = wantPkg + "/" + top.getClassName();
                                }
                                if (want.taskId != oldId) {
                                    Log.i(TAG, "rebindMainFrontTaskId tasks "
                                        + oldId + "→" + want.taskId
                                        + " pkg=" + wantPkg);
                                }
                                return want.taskId != oldId || oldId > 0;
                            }
                        }
                        // Second pass: pkg-only (cls mismatch residual).
                        for (android.app.ActivityManager.RunningTaskInfo ti : tasks) {
                            if (ti == null || ti.taskId <= 0) continue;
                            int did = runningTaskDisplayId(ti);
                            if (did != 0 && did != -1) continue;
                            android.content.ComponentName top = ti.topActivity != null
                                ? ti.topActivity : ti.baseActivity;
                            if (top == null || !wantPkg.equals(top.getPackageName())) {
                                continue;
                            }
                            want.taskId = ti.taskId;
                            if (want.component == null && top.getClassName() != null) {
                                want.component = wantPkg + "/" + top.getClassName();
                            }
                            if (want.taskId != oldId) {
                                Log.i(TAG, "rebindMainFrontTaskId tasks-pkg "
                                    + oldId + "→" + want.taskId
                                    + " pkg=" + wantPkg);
                            }
                            return true;
                        }
                    }
                } catch (Exception e) {
                    Log.d(TAG, "rebindMainFrontTaskId tasks: " + e.getMessage());
                }
            }
        }
        // Shell stack list fallback.
        String list = shellOut(new String[]{"/system/bin/am", "stack", "list"}, 3000);
        if (list == null || list.isEmpty()) return oldId > 0;
        String[] blocks = list.split("RootTask id=");
        for (int bi = 1; bi < blocks.length; bi++) {
            String block = blocks[bi];
            if (block == null || !block.contains(wantPkg)) continue;
            int displayId = -1;
            try {
                int d0 = block.indexOf("displayId=");
                if (d0 >= 0) {
                    int d1 = d0 + 10;
                    while (d1 < block.length()
                            && Character.isDigit(block.charAt(d1))) {
                        d1++;
                    }
                    displayId = Integer.parseInt(block.substring(d0 + 10, d1));
                }
            } catch (Exception ignored) {}
            if (displayId != 0) continue;
            if (block.contains("mActivityType=home")
                    || block.contains("mActivityType=recents")) {
                continue;
            }
            try {
                int t0 = block.indexOf("taskId=");
                if (t0 < 0) continue;
                int te = t0 + 7;
                while (te < block.length()
                        && Character.isDigit(block.charAt(te))) {
                    te++;
                }
                if (te > t0 + 7) {
                    int tid = Integer.parseInt(block.substring(t0 + 7, te));
                    if (tid > 0) {
                        want.taskId = tid;
                        if (want.taskId != oldId) {
                            Log.i(TAG, "rebindMainFrontTaskId shell "
                                + oldId + "→" + want.taskId
                                + " pkg=" + wantPkg);
                        }
                        return true;
                    }
                }
            } catch (Exception ignored) {}
        }
        return oldId > 0;
    }

    /**
     * 15.22: demote every main-display shared singleTask tile that is not the
     * pre-leave front. 15.21 only demoted {@code now.taskId} — sibling Calc
     * after Clock steal stayed sticky under late REORDER.
     */
    private static int demoteSharedThievesOnMain(Context app, MainFront want) {
        int n = 0;
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        String wantPkg = want != null ? want.pkg : null;
        if (app != null) {
            android.app.ActivityManager am = (android.app.ActivityManager)
                app.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                try {
                    //noinspection deprecation
                    java.util.List<android.app.ActivityManager.RunningTaskInfo> tasks =
                        am.getRunningTasks(48);
                    if (tasks != null) {
                        for (android.app.ActivityManager.RunningTaskInfo ti : tasks) {
                            if (ti == null || ti.taskId <= 0) continue;
                            int did = runningTaskDisplayId(ti);
                            if (did != 0 && did != -1) continue;
                            android.content.ComponentName top = ti.topActivity != null
                                ? ti.topActivity : ti.baseActivity;
                            if (top == null) continue;
                            String pkg = top.getPackageName();
                            if (pkg == null) continue;
                            if (!SubDisplayLauncherActivity
                                    .isSharedSingleTaskTilePackage(pkg)) {
                                continue;
                            }
                            if (wantPkg != null && wantPkg.equals(pkg)) continue;
                            if (want != null && want.taskId > 0
                                    && ti.taskId == want.taskId) {
                                continue;
                            }
                            if (!seen.add(ti.taskId)) continue;
                            if (demoteMainTask(ti.taskId, /*allowHome*/ false)) {
                                n++;
                                Log.i(TAG, "demoteSharedThieves tasks taskId="
                                    + ti.taskId + " pkg=" + pkg);
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.d(TAG, "demoteSharedThieves tasks: " + e.getMessage());
                }
            }
        }
        // Shell: any standard RootTask on display 0 whose pkg is shared singleTask.
        String list = shellOut(new String[]{"/system/bin/am", "stack", "list"}, 3000);
        if (list != null && !list.isEmpty()) {
            String[] blocks = list.split("RootTask id=");
            for (int bi = 1; bi < blocks.length; bi++) {
                String block = blocks[bi];
                if (block == null || block.isEmpty()) continue;
                int displayId = -1;
                try {
                    int d0 = block.indexOf("displayId=");
                    if (d0 >= 0) {
                        int d1 = d0 + 10;
                        while (d1 < block.length()
                                && Character.isDigit(block.charAt(d1))) {
                            d1++;
                        }
                        displayId = Integer.parseInt(block.substring(d0 + 10, d1));
                    }
                } catch (Exception ignored) {}
                if (displayId != 0) continue;
                if (block.contains("mActivityType=home")
                        || block.contains("mActivityType=recents")) {
                    continue;
                }
                String pkg = null;
                int tid = -1;
                try {
                    int t0 = block.indexOf("taskId=");
                    if (t0 >= 0) {
                        int te = t0 + 7;
                        while (te < block.length()
                                && Character.isDigit(block.charAt(te))) {
                            te++;
                        }
                        if (te > t0 + 7) {
                            tid = Integer.parseInt(block.substring(t0 + 7, te));
                        }
                        int colon = block.indexOf(':', t0);
                        if (colon > 0) {
                            int end = block.indexOf(' ', colon + 2);
                            int nl2 = block.indexOf('\n', colon);
                            if (end < 0 || (nl2 > 0 && nl2 < end)) end = nl2;
                            if (end < 0) end = Math.min(block.length(), colon + 120);
                            String rest = block.substring(colon + 1, end).trim();
                            int slash = rest.indexOf('/');
                            if (slash > 0) {
                                pkg = rest.substring(0, slash).trim();
                            } else if (rest.contains(".")) {
                                pkg = rest;
                                int sp = pkg.indexOf(' ');
                                if (sp > 0) pkg = pkg.substring(0, sp);
                            }
                        }
                    }
                } catch (Exception ignored) {}
                if (pkg == null || tid <= 0) continue;
                if (!SubDisplayLauncherActivity.isSharedSingleTaskTilePackage(pkg)) {
                    continue;
                }
                if (wantPkg != null && wantPkg.equals(pkg)) continue;
                if (want != null && want.taskId > 0 && tid == want.taskId) continue;
                if (!seen.add(tid)) continue;
                if (demoteMainTask(tid, /*allowHome*/ false)) {
                    n++;
                    Log.i(TAG, "demoteSharedThieves shell taskId=" + tid
                        + " pkg=" + pkg);
                }
            }
        }
        if (n > 0) {
            Log.i(TAG, "demoteSharedThievesOnMain n=" + n + " want=" + wantPkg);
        }
        return n;
    }

    /** 15.18: main-display focus snapshot before leave-move. */
    private static final class MainFront {
        int taskId = -1;
        /** pkg/cls for {@code am start -n} (no ComponentInfo wrapper). */
        String component;
        String pkg;
        boolean home;

        boolean hasTarget() {
            return taskId > 0 || (component != null && !component.isEmpty()) || home;
        }
    }

    /**
     * 15.18: main-display focus snapshot. Prefer {@code getRunningTasks}
     * (displayId via toString — same SoT as rear discover); fallback
     * {@code am stack list}. Skip SystemUI / unknown / recents.
     */
    private static MainFront captureMainFront(Context app) {
        MainFront out = captureMainFrontViaRunningTasks(app);
        if (out != null && out.hasTarget()) {
            Log.i(TAG, "captureMainFront tasks taskId=" + out.taskId
                + " cmp=" + out.component + " pkg=" + out.pkg
                + " home=" + out.home);
            return out;
        }
        out = captureMainFrontViaShell();
        Log.i(TAG, "captureMainFront shell taskId=" + out.taskId
            + " cmp=" + out.component + " pkg=" + out.pkg
            + " home=" + out.home);
        return out;
    }

    private static MainFront captureMainFrontViaRunningTasks(Context app) {
        MainFront out = new MainFront();
        if (app == null) return out;
        android.app.ActivityManager am = (android.app.ActivityManager)
            app.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return out;
        java.util.List<android.app.ActivityManager.RunningTaskInfo> tasks;
        try {
            //noinspection deprecation
            tasks = am.getRunningTasks(32);
        } catch (Exception e) {
            return out;
        }
        if (tasks == null) return out;
        for (android.app.ActivityManager.RunningTaskInfo ti : tasks) {
            if (ti == null) continue;
            int did = runningTaskDisplayId(ti);
            // Main is 0; -1 unknown skip (rear uses sw182 heuristic → 2).
            if (did != 0) continue;
            android.content.ComponentName top = ti.topActivity != null
                ? ti.topActivity : ti.baseActivity;
            if (top == null) continue;
            String pkg = top.getPackageName();
            String cls = top.getClassName();
            if (pkg == null) continue;
            if ("com.android.systemui".equals(pkg)) continue;
            if ("android".equals(pkg)) continue;
            boolean home = "com.android.launcher3".equals(pkg)
                || (cls != null && cls.contains("Launcher"));
            boolean recents = cls != null && cls.contains("RecentsActivity");
            if (recents) continue;
            out.taskId = ti.taskId > 0 ? ti.taskId : -1;
            out.pkg = pkg;
            if (cls != null) out.component = pkg + "/" + cls;
            out.home = home;
            return out;
        }
        return out;
    }

    private static MainFront captureMainFrontViaShell() {
        MainFront out = new MainFront();
        String list = shellOut(new String[]{"/system/bin/am", "stack", "list"}, 3000);
        if (list == null || list.isEmpty()) {
            list = shellOut(new String[]{"/system/bin/sh", "-c",
                "/system/bin/am stack list"}, 3000);
        }
        if (list == null || list.isEmpty()) {
            Log.w(TAG, "captureMainFront: empty stack list");
            return out;
        }
        MainFront firstStandard = null;
        MainFront firstVisibleStandard = null;
        MainFront home = null;
        String[] blocks = list.split("RootTask id=");
        for (int bi = 1; bi < blocks.length; bi++) {
            String block = blocks[bi];
            if (block == null || block.isEmpty()) continue;
            int stackId = -1;
            int displayId = -1;
            try {
                int sp = block.indexOf(' ');
                String idPart = sp > 0 ? block.substring(0, sp) : block;
                int nl = idPart.indexOf('\n');
                if (nl > 0) idPart = idPart.substring(0, nl);
                stackId = Integer.parseInt(idPart.trim());
            } catch (Exception ignored) {}
            try {
                int d0 = block.indexOf("displayId=");
                if (d0 >= 0) {
                    int d1 = d0 + 10;
                    while (d1 < block.length()
                            && Character.isDigit(block.charAt(d1))) {
                        d1++;
                    }
                    displayId = Integer.parseInt(block.substring(d0 + 10, d1));
                }
            } catch (Exception ignored) {}
            if (displayId != 0 || stackId < 0) continue;
            // Prefer the RootTask's own activityType token near winConfig.
            boolean isHome = block.contains("mActivityType=home");
            boolean isRecents = block.contains("mActivityType=recents");
            boolean isStandard = block.contains("mActivityType=standard");
            if (!isHome && !isRecents && !isStandard) {
                // Accept unknown standard-ish if it has a real package line.
                isStandard = block.contains("taskId=") && block.contains("/");
            }
            int taskId = -1;
            String pkg = null;
            String component = null;
            try {
                int t0 = block.indexOf("taskId=");
                if (t0 >= 0) {
                    int te = t0 + 7;
                    while (te < block.length()
                            && Character.isDigit(block.charAt(te))) {
                        te++;
                    }
                    if (te > t0 + 7) {
                        taskId = Integer.parseInt(block.substring(t0 + 7, te));
                    }
                    int colon = block.indexOf(':', t0);
                    if (colon > 0) {
                        int end = block.indexOf(' ', colon + 2);
                        int nl2 = block.indexOf('\n', colon);
                        if (end < 0 || (nl2 > 0 && nl2 < end)) end = nl2;
                        if (end < 0) end = Math.min(block.length(), colon + 120);
                        String rest = block.substring(colon + 1, end).trim();
                        if (!rest.isEmpty() && !"unknown".equals(rest)
                                && rest.contains(".")) {
                            int slash = rest.indexOf('/');
                            if (slash > 0) {
                                pkg = rest.substring(0, slash).trim();
                                String cls = rest.substring(slash + 1).trim();
                                int sp2 = cls.indexOf(' ');
                                if (sp2 > 0) cls = cls.substring(0, sp2);
                                if (!cls.isEmpty()) {
                                    component = pkg + "/" + cls;
                                }
                            } else {
                                pkg = rest;
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
            if (component == null) {
                try {
                    int ci = block.indexOf("topActivity=ComponentInfo{");
                    if (ci >= 0) {
                        int s = ci + "topActivity=ComponentInfo{".length();
                        int e = block.indexOf('}', s);
                        if (e > s) {
                            String flat = block.substring(s, e).trim();
                            int slash = flat.indexOf('/');
                            if (slash > 0) {
                                pkg = flat.substring(0, slash);
                                component = pkg + "/" + flat.substring(slash + 1);
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
            if (pkg != null) {
                if ("com.android.systemui".equals(pkg)) continue;
                if ("android".equals(pkg)) continue;
            }
            if (isRecents) continue;
            // Need a package or home marker.
            if (pkg == null && !isHome) continue;
            MainFront cand = new MainFront();
            cand.taskId = taskId > 0 ? taskId : stackId;
            cand.component = component;
            cand.pkg = pkg;
            cand.home = isHome || "com.android.launcher3".equals(pkg);
            boolean visible = block.contains("visible=true");
            if (cand.home && home == null) {
                home = cand;
            }
            if (!cand.home && (isStandard || pkg != null)) {
                if (firstStandard == null) firstStandard = cand;
                if (visible && firstVisibleStandard == null) {
                    firstVisibleStandard = cand;
                }
            }
        }
        if (firstVisibleStandard != null) {
            out = firstVisibleStandard;
        } else if (firstStandard != null) {
            out = firstStandard;
        } else if (home != null) {
            out = home;
        }
        return out;
    }

    /**
     * 15.18: bring pre-leave main front back after move-stack / pull-main.
     * Lab: {@code am start --display 0 -n pkg/cls} reorders existing task
     * without murdering the relocated Clock process. Trust non-error shell
     * start (no second list race → accidental HOME).
     */
    private static boolean shellRestoreMainFront(MainFront front) {
        if (front == null || !front.hasTarget()) return false;
        // Prefer component start (reorder-to-front of existing task).
        // 15.21: -f 0x20000 = FLAG_ACTIVITY_REORDER_TO_FRONT (no parallel act).
        if (front.component != null && !front.component.isEmpty()
                && !front.home) {
            String err = shellOut(new String[]{"/system/bin/am", "start",
                "--display", "0",
                "-n", front.component,
                "-f", "0x20000"}, 4000);
            if (!shellStartFailed(err)) {
                Log.i(TAG, "shellRestoreMainFront -n ok cmp=" + front.component
                    + " out=" + (err != null ? err.trim() : ""));
                return true;
            }
            // Older am may reject -f; bare -n fallback.
            err = shellOut(new String[]{"/system/bin/am", "start",
                "--display", "0",
                "-n", front.component}, 4000);
            if (!shellStartFailed(err)) {
                Log.i(TAG, "shellRestoreMainFront -n bare ok cmp="
                    + front.component
                    + " out=" + (err != null ? err.trim() : ""));
                return true;
            }
            Log.w(TAG, "shellRestoreMainFront -n: "
                + (err != null ? err.trim() : "null"));
        }
        // ATM moveTaskToFront when greylist allows.
        if (front.taskId > 0) {
            try {
                Object atm = Class.forName("android.app.ActivityTaskManager")
                    .getMethod("getService")
                    .invoke(null);
                if (atm != null) {
                    try {
                        atm.getClass()
                            .getMethod("moveTaskToFront", int.class, int.class)
                            .invoke(atm, front.taskId, 0);
                        Log.i(TAG, "shellRestoreMainFront ATM taskId="
                            + front.taskId);
                        return true;
                    } catch (NoSuchMethodException ns) {
                        try {
                            atm.getClass()
                                .getMethod("moveTaskToFront", int.class, int.class,
                                    android.os.Bundle.class)
                                .invoke(atm, front.taskId, 0, null);
                            Log.i(TAG, "shellRestoreMainFront ATM3 taskId="
                                + front.taskId);
                            return true;
                        } catch (Exception ignored) {}
                    }
                }
            } catch (Exception e) {
                Log.d(TAG, "moveTaskToFront: " + e.getMessage());
            }
        }
        // Home fallback when pre-leave main was launcher only.
        if (front.home
                || "com.android.launcher3".equals(front.pkg)
                || (front.component != null
                    && front.component.contains("launcher3"))) {
            String err = shellOut(new String[]{"/system/bin/am", "start",
                "--display", "0",
                "-a", "android.intent.action.MAIN",
                "-c", "android.intent.category.HOME"}, 4000);
            if (shellStartFailed(err)) {
                Log.w(TAG, "shellRestoreMainFront HOME: "
                    + (err != null ? err.trim() : "null"));
                return false;
            }
            return true;
        }
        // Package MAIN/LAUNCHER last resort.
        if (front.pkg != null && !front.pkg.isEmpty()) {
            String err = shellOut(new String[]{"/system/bin/am", "start",
                "--display", "0",
                "-a", "android.intent.action.MAIN",
                "-c", "android.intent.category.LAUNCHER",
                front.pkg}, 4000);
            if (shellStartFailed(err)) {
                Log.w(TAG, "shellRestoreMainFront pkg: "
                    + (err != null ? err.trim() : "null"));
                return false;
            }
            return true;
        }
        return false;
    }

    /** True when am start output looks like a hard failure (not Warning:). */
    private static boolean shellStartFailed(String err) {
        if (err == null || err.isEmpty()) return false;
        String low = err.toLowerCase();
        return low.contains("exception") || low.contains("error:")
            || low.contains("does not exist") || low.contains("unable")
            || low.contains("permission denial") || low.contains("security exception")
            || low.contains("not found");
    }

    /**
     * 15.15/15.16: {@link android.app.ActivityManager#getRunningTasks} + displayId
     * (REAL_GET_TASKS). Collects rear packages <b>and taskIds</b> for scoped remove.
     */
    private static void discoverRearPkgsViaRunningTasks(
            Context app, int rearId,
            java.util.Set<String> rearPkgs,
            java.util.Set<Integer> rearTaskIds,
            java.util.Set<String> tracked) {
        android.app.ActivityManager am = (android.app.ActivityManager)
            app.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return;
        java.util.List<android.app.ActivityManager.RunningTaskInfo> tasks = null;
        try {
            //noinspection deprecation
            tasks = am.getRunningTasks(64);
        } catch (SecurityException se) {
            Log.w(TAG, "getRunningTasks: " + se.getMessage());
            return;
        } catch (Exception e) {
            Log.w(TAG, "getRunningTasks: " + e.getMessage());
            return;
        }
        if (tasks == null || tasks.isEmpty()) return;
        int hit = 0;
        for (android.app.ActivityManager.RunningTaskInfo ti : tasks) {
            if (ti == null) continue;
            int did = runningTaskDisplayId(ti);
            if (did != rearId) continue;
            android.content.ComponentName top = ti.topActivity != null
                ? ti.topActivity : ti.baseActivity;
            if (top != null && keepRearTaskOnLeave(top)) continue;
            String pkg = null;
            if (top != null) pkg = top.getPackageName();
            if (pkg == null && ti.baseIntent != null) {
                try { pkg = ti.baseIntent.getPackage(); } catch (Exception ignored) {}
                if (pkg == null && ti.baseIntent.getComponent() != null) {
                    pkg = ti.baseIntent.getComponent().getPackageName();
                }
            }
            if (pkg == null || pkg.isEmpty()) continue;
            if (SubDisplayLauncherActivity.isKnownRearTilePackage(pkg)
                    || (tracked != null && tracked.contains(pkg))) {
                rearPkgs.add(pkg);
                int tid = ti.taskId;
                if (tid <= 0 && Build.VERSION.SDK_INT >= 29) {
                    try { tid = ti.taskId; } catch (Exception ignored) {}
                }
                if (tid > 0) rearTaskIds.add(tid);
                hit++;
                Log.i(TAG, "clearRearAppsStack runningTask rear pkg=" + pkg
                    + " taskId=" + tid + " displayId=" + did);
            }
        }
        Log.i(TAG, "clearRearAppsStack runningTasks n=" + tasks.size()
            + " rearHits=" + hit);
    }

    /**
     * 15.15: displayId is hidden API on TaskInfo (TargetSdk 34 blocked). Parse
     * public {@link Object#toString()} which still embeds {@code displayId=N}
     * (reflection residual rearHits=0).
     */
    private static int runningTaskDisplayId(
            android.app.ActivityManager.RunningTaskInfo ti) {
        if (ti == null) return -1;
        try {
            // Best-effort reflection first (greylist trains).
            try {
                return ti.getClass().getField("displayId").getInt(ti);
            } catch (Throwable ignored) {}
            try {
                Object v = ti.getClass().getMethod("getDisplayId").invoke(ti);
                if (v instanceof Integer) return (Integer) v;
            } catch (Throwable ignored) {}
            String s = String.valueOf(ti);
            int i = s.indexOf("displayId=");
            if (i >= 0) {
                int j = i + 10;
                int k = j;
                while (k < s.length() && Character.isDigit(s.charAt(k))) k++;
                if (k > j) return Integer.parseInt(s.substring(j, k));
            }
            // Configuration window bounds heuristic: rear is ~410×502 vs 1440.
            try {
                java.lang.reflect.Field cf = ti.getClass().getField("configuration");
                Object conf = cf.get(ti);
                if (conf != null) {
                    String cs = conf.toString();
                    // sw182dp / w182dp is rear; sw768 is main
                    if (cs.contains("sw182") || cs.contains("w182dp")) return 2;
                    if (cs.contains("sw768") || cs.contains("w768dp")) return 0;
                }
            } catch (Throwable ignored) {}
        } catch (Exception ignored) {}
        return -1;
    }

    /**
     * 15.15/15.16: parse {@code am stack list} for RootTasks on rear display.
     * Adds known tile packages + taskIds; records stack ids for stack remove.
     */
    private static void discoverRearStacksViaShell(
            int rearId,
            java.util.List<int[]> rearStacks,
            java.util.Map<Integer, String> stackPkg,
            java.util.Set<String> rearPkgs,
            java.util.Set<Integer> rearTaskIds) {
        String out = shellOut(new String[]{"/system/bin/am", "stack", "list"}, 4000);
        if (out == null || out.isEmpty()) {
            out = shellOut(new String[]{"/system/bin/sh", "-c",
                "/system/bin/am stack list"}, 4000);
        }
        if (out == null || out.isEmpty()) {
            Log.w(TAG, "clearRearAppsStack shell list empty (app-uid am?)");
            return;
        }
        Log.i(TAG, "clearRearAppsStack shell list bytes=" + out.length());
        // Block-wise parse: RootTask id=N … displayId=D … taskId=…: pkg/…
        String[] blocks = out.split("RootTask id=");
        for (int bi = 1; bi < blocks.length; bi++) {
            String block = blocks[bi];
            if (block == null || block.isEmpty()) continue;
            int curStack = -1;
            int curDisplay = -1;
            try {
                int sp = block.indexOf(' ');
                String idPart = sp > 0 ? block.substring(0, sp) : block;
                // id may be followed by \n without space
                int nl = idPart.indexOf('\n');
                if (nl > 0) idPart = idPart.substring(0, nl);
                curStack = Integer.parseInt(idPart.trim());
            } catch (Exception ignored) {}
            try {
                int d0 = block.indexOf("displayId=");
                if (d0 >= 0) {
                    int d1 = d0 + 10;
                    while (d1 < block.length()
                            && Character.isDigit(block.charAt(d1))) {
                        d1++;
                    }
                    curDisplay = Integer.parseInt(block.substring(d0 + 10, d1));
                }
            } catch (Exception ignored) {}
            if (curDisplay != rearId || curStack < 0) continue;
            rearStacks.add(new int[]{curStack});
            // Collect package names + taskIds in this rear block
            int idx = 0;
            while (idx < block.length()) {
                int t0 = block.indexOf("taskId=", idx);
                if (t0 < 0) break;
                int tid = -1;
                try {
                    int te = t0 + 7;
                    while (te < block.length()
                            && Character.isDigit(block.charAt(te))) {
                        te++;
                    }
                    if (te > t0 + 7) {
                        tid = Integer.parseInt(block.substring(t0 + 7, te));
                    }
                } catch (Exception ignored) {}
                int colon = block.indexOf(':', t0);
                if (colon < 0) break;
                int end = block.indexOf('\n', colon);
                if (end < 0) end = block.length();
                String rest = block.substring(colon + 1, end).trim();
                // "com.android.documentsui/… bounds=…" or ComponentInfo
                String pkg = rest;
                int slash = rest.indexOf('/');
                if (slash > 0) pkg = rest.substring(0, slash).trim();
                int space = pkg.indexOf(' ');
                if (space > 0) pkg = pkg.substring(0, space).trim();
                idx = end + 1;
                if (pkg.isEmpty() || !pkg.contains(".")) continue;
                stackPkg.put(curStack, pkg);
                if (SubDisplayLauncherActivity.isKnownRearTilePackage(pkg)
                        || rearPkgs.contains(pkg)) {
                    rearPkgs.add(pkg);
                    if (tid > 0) rearTaskIds.add(tid);
                    Log.i(TAG, "clearRearAppsStack shell rear pkg=" + pkg
                        + " stack=" + curStack + " taskId=" + tid);
                }
            }
            // Fallback: known tile package string present in this rear block only
            // (never whole-list raw hits — that murdered main Clock after 15.14).
            for (String k : SubDisplayLauncherActivity.knownRearTilePackages()) {
                if (block.contains(k)) {
                    rearPkgs.add(k);
                    if (!stackPkg.containsKey(curStack)) stackPkg.put(curStack, k);
                    Log.i(TAG, "clearRearAppsStack shell rear contain pkg=" + k
                        + " stack=" + curStack);
                }
            }
        }
        // No whole-output last resort: am stack list always embeds main-display
        // Clock/Files package names even when rear only hosts Face/home.
    }

    /**
     * 15.18: ATM {@code moveRootTaskToDisplay} with post-list verify.
     * Prefer ServiceManager binder (ActivityTaskManager.getService is
     * hiddenapi-blocked for TargetSdk 34 priv_app). Bare shell am often
     * denied under app uid.
     */
    private static boolean atmMoveRootTaskToMain(int taskOrStackId, int rearId) {
        if (taskOrStackId < 0) return false;
        // Only act if still on rear.
        String before = shellOut(new String[]{"/system/bin/am", "stack", "list"}, 3000);
        if (before != null && !stackBlockOnDisplay(before, taskOrStackId, rearId)) {
            // Not on rear already.
            return true;
        }
        Object atm = resolveActivityTaskManager();
        if (atm == null) {
            Log.w(TAG, "atmMoveRootTaskToMain: no ATM binder");
            return false;
        }
        try {
            java.lang.reflect.Method m = null;
            try {
                m = atm.getClass()
                    .getMethod("moveRootTaskToDisplay", int.class, int.class);
            } catch (NoSuchMethodException ignored) {}
            if (m == null) {
                // Some trains: moveTaskToDisplay(taskId, displayId, onTop)
                try {
                    m = atm.getClass().getMethod("moveTaskToDisplay",
                        int.class, int.class, boolean.class);
                    m.invoke(atm, taskOrStackId, 0, true);
                    m = null; // already invoked
                } catch (NoSuchMethodException ignored) {}
            }
            if (m != null) {
                m.invoke(atm, taskOrStackId, 0);
            }
        } catch (Exception e) {
            Log.w(TAG, "atmMoveRootTaskToMain " + taskOrStackId + ": "
                + e.getMessage());
            return false;
        }
        try {
            Thread.sleep(80);
        } catch (InterruptedException ignored) {}
        String after = shellOut(new String[]{"/system/bin/am", "stack", "list"}, 3000);
        if (after == null) return false;
        boolean stillRear = stackBlockOnDisplay(after, taskOrStackId, rearId);
        if (stillRear) {
            Log.w(TAG, "atmMoveRootTaskToMain " + taskOrStackId
                + ": still on display " + rearId);
            return false;
        }
        Log.i(TAG, "atmMoveRootTaskToMain id=" + taskOrStackId + " →0 ok");
        return true;
    }

    /** Binder for IActivityTaskManager — greylist ServiceManager first. */
    private static Object resolveActivityTaskManager() {
        // 1) ServiceManager.getService("activity_task") + Stub.asInterface
        try {
            Object sm = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class)
                .invoke(null, "activity_task");
            if (sm != null) {
                Class<?> stub = Class.forName(
                    "android.app.IActivityTaskManager$Stub");
                Object atm = stub.getMethod("asInterface",
                    android.os.IBinder.class).invoke(null, sm);
                if (atm != null) return atm;
            }
        } catch (Exception e) {
            Log.d(TAG, "ATM ServiceManager: " + e.getMessage());
        }
        // 2) ActivityTaskManager.getService (often hiddenapi blocked)
        try {
            return Class.forName("android.app.ActivityTaskManager")
                .getMethod("getService")
                .invoke(null);
        } catch (Exception e) {
            Log.d(TAG, "ATM getService: " + e.getMessage());
        }
        return null;
    }

    /**
     * 15.18: relocate shared singleTask tile onto main via app Context Intent
     * (ProcessBuilder {@code am start} is SecurityException under app uid —
     * shell package residual). Post-list verifies package left rear.
     */
    private static boolean appPullPackageToMain(Context app, String pkg) {
        if (app == null || pkg == null || pkg.isEmpty()) return false;
        String component = sharedSingleTaskComponent(pkg);
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
            | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        if (component != null) {
            int slash = component.indexOf('/');
            if (slash > 0) {
                i.setClassName(component.substring(0, slash),
                    component.substring(slash + 1));
            } else {
                i.setPackage(pkg);
            }
        } else {
            Intent launch = app.getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch != null) i = launch;
            else i.setPackage(pkg);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        }
        try {
            android.app.ActivityOptions opts = android.app.ActivityOptions.makeBasic();
            try {
                opts.getClass().getMethod("setLaunchDisplayId", int.class)
                    .invoke(opts, 0);
                app.startActivity(i, opts.toBundle());
            } catch (Exception e) {
                app.startActivity(i);
            }
        } catch (Exception e) {
            Log.w(TAG, "appPullPackageToMain " + pkg + ": " + e.getMessage());
            return false;
        }
        // Verify package no longer on rear (singleTask relocate).
        // Activity start is async — settle then verify package left rear.
        try {
            Thread.sleep(350);
        } catch (InterruptedException ignored) {}
        String after = shellOut(new String[]{"/system/bin/am", "stack", "list"}, 3000);
        if (after == null || after.isEmpty()) {
            // No list: cannot claim success (false ok left Clock on rear).
            Log.w(TAG, "appPullPackageToMain " + pkg + ": no post-list");
            return false;
        }
        // Check both common rear ids (lab 2; some trains 1).
        if (packageOnDisplay(after, pkg, 2) || packageOnDisplay(after, pkg, 1)) {
            Log.w(TAG, "appPullPackageToMain " + pkg + ": still on rear");
            return false;
        }
        Log.i(TAG, "appPullPackageToMain " + pkg + " ok");
        return true;
    }

    /** Component string pkg/cls for known shared singleTask tiles. */
    private static String sharedSingleTaskComponent(String pkg) {
        if (pkg == null) return null;
        if ("com.android.deskclock".equals(pkg)
                || "com.google.android.deskclock".equals(pkg)) {
            return pkg + "/com.android.deskclock.DeskClock";
        }
        if ("com.android.calculator2".equals(pkg)
                || "com.google.android.calculator".equals(pkg)
                || "org.lineageos.calculator".equals(pkg)) {
            return pkg + "/com.android.calculator2.Calculator";
        }
        if ("org.lineageos.aperture".equals(pkg)) {
            return pkg + "/org.lineageos.aperture.CameraActivity";
        }
        if ("com.android.camera2".equals(pkg) || "com.android.camera".equals(pkg)) {
            return pkg + "/com.android.camera.CameraLauncher";
        }
        return null;
    }

    /** True when am stack list shows package on displayId (rear=2). */
    private static boolean packageOnDisplay(String list, String pkg, int displayId) {
        if (list == null || pkg == null || pkg.isEmpty()) return false;
        String[] blocks = list.split("RootTask id=");
        for (int bi = 1; bi < blocks.length; bi++) {
            String block = blocks[bi];
            if (block == null || !block.contains(pkg)) continue;
            try {
                int d0 = block.indexOf("displayId=");
                if (d0 < 0) continue;
                int d1 = d0 + 10;
                while (d1 < block.length() && Character.isDigit(block.charAt(d1))) {
                    d1++;
                }
                int did = Integer.parseInt(block.substring(d0 + 10, d1));
                if (did == displayId) return true;
            } catch (Exception ignored) {}
        }
        return false;
    }

    /**
     * 15.18: restore main front via app Context (am start shell-uid denied).
     */
    private static boolean appRestoreMainFront(Context app, MainFront front) {
        if (app == null || front == null || !front.hasTarget()) return false;
        if (front.home
                || "com.android.launcher3".equals(front.pkg)) {
            try {
                Intent home = new Intent(Intent.ACTION_MAIN);
                home.addCategory(Intent.CATEGORY_HOME);
                home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                android.app.ActivityOptions opts = android.app.ActivityOptions.makeBasic();
                try {
                    opts.getClass().getMethod("setLaunchDisplayId", int.class)
                        .invoke(opts, 0);
                    app.startActivity(home, opts.toBundle());
                } catch (Exception e) {
                    app.startActivity(home);
                }
                return true;
            } catch (Exception e) {
                Log.w(TAG, "appRestoreMainFront HOME: " + e.getMessage());
                return false;
            }
        }
        if (front.component != null && !front.component.isEmpty()) {
            try {
                Intent i = new Intent(Intent.ACTION_MAIN);
                i.addCategory(Intent.CATEGORY_LAUNCHER);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                int slash = front.component.indexOf('/');
                if (slash > 0) {
                    i.setClassName(front.component.substring(0, slash),
                        front.component.substring(slash + 1));
                } else if (front.pkg != null) {
                    i.setPackage(front.pkg);
                } else {
                    return false;
                }
                android.app.ActivityOptions opts = android.app.ActivityOptions.makeBasic();
                try {
                    opts.getClass().getMethod("setLaunchDisplayId", int.class)
                        .invoke(opts, 0);
                    app.startActivity(i, opts.toBundle());
                } catch (Exception e) {
                    app.startActivity(i);
                }
                Log.i(TAG, "appRestoreMainFront cmp=" + front.component);
                return true;
            } catch (Exception e) {
                Log.w(TAG, "appRestoreMainFront: " + e.getMessage());
            }
        }
        if (front.pkg != null && !front.pkg.isEmpty()) {
            try {
                Intent i = app.getPackageManager().getLaunchIntentForPackage(front.pkg);
                if (i == null) return false;
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                app.startActivity(i);
                return true;
            } catch (Exception e) {
                Log.w(TAG, "appRestoreMainFront pkg: " + e.getMessage());
            }
        }
        return false;
    }

    /**
     * 15.17: move RootTask from rear to main display 0 — preserves singleTask
     * Clock/Calc process (task remove / FORCE_STOP residual after 15.16).
     * Returns true when post-list shows stack is no longer on {@code rearId}.
     */
    private static boolean shellMoveStackToMain(int stackId, int rearId) {
        if (stackId < 0) return false;
        String marker = "RootTask id=" + stackId + " ";
        String before = shellOut(new String[]{"/system/bin/am", "stack", "list"}, 3000);
        if (before == null || !before.contains(marker)) {
            // Already gone or list denied.
            return before != null && !before.contains(marker);
        }
        // Only act if this stack is still on rear (avoid moving main stacks).
        if (!stackBlockOnDisplay(before, stackId, rearId)) {
            return true; // not on rear — already clear for leave
        }
        String err = shellOut(new String[]{"/system/bin/am", "display", "move-stack",
            String.valueOf(stackId), "0"}, 3000);
        if (err != null) {
            String low = err.toLowerCase();
            if (low.contains("exception") || low.contains("error:")
                    || low.contains("security") || low.contains("permission")
                    || low.contains("denied") || low.contains("unknown")) {
                Log.w(TAG, "shell move-stack " + stackId + "→0: " + err.trim());
                return false;
            }
        }
        String after = shellOut(new String[]{"/system/bin/am", "stack", "list"}, 3000);
        if (after == null) {
            Log.w(TAG, "shell move-stack " + stackId + ": post-list empty");
            return false;
        }
        // Success: stack gone entirely, or present but not on rear.
        boolean stillRear = stackBlockOnDisplay(after, stackId, rearId);
        if (stillRear) {
            Log.w(TAG, "shell move-stack " + stackId + ": still on display " + rearId);
            return false;
        }
        return true;
    }

    /**
     * True when {@code am stack list} text has RootTask id=stackId on displayId.
     */
    private static boolean stackBlockOnDisplay(String list, int stackId, int displayId) {
        if (list == null || list.isEmpty() || stackId < 0) return false;
        String[] blocks = list.split("RootTask id=");
        String want = String.valueOf(stackId);
        for (int bi = 1; bi < blocks.length; bi++) {
            String block = blocks[bi];
            if (block == null || block.isEmpty()) continue;
            try {
                int sp = block.indexOf(' ');
                String idPart = sp > 0 ? block.substring(0, sp) : block;
                int nl = idPart.indexOf('\n');
                if (nl > 0) idPart = idPart.substring(0, nl);
                if (!want.equals(idPart.trim())) continue;
            } catch (Exception e) {
                continue;
            }
            try {
                int d0 = block.indexOf("displayId=");
                if (d0 < 0) return false;
                int d1 = d0 + 10;
                while (d1 < block.length() && Character.isDigit(block.charAt(d1))) {
                    d1++;
                }
                int did = Integer.parseInt(block.substring(d0 + 10, d1));
                return did == displayId;
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }

    /**
     * 15.17: pull shared singleTask package onto main via start --display 0.
     * Lab proof: singleTask DeskClock relocates the same task off rear.
     */
    private static boolean shellPullPackageToMain(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        String component = null;
        if ("com.android.deskclock".equals(pkg)
                || "com.google.android.deskclock".equals(pkg)) {
            component = pkg + "/com.android.deskclock.DeskClock";
        } else if ("com.android.calculator2".equals(pkg)) {
            component = pkg + "/com.android.calculator2.Calculator";
        } else if ("com.google.android.calculator".equals(pkg)) {
            component = pkg + "/com.android.calculator2.Calculator";
        } else if ("org.lineageos.calculator".equals(pkg)) {
            component = pkg + "/com.android.calculator2.Calculator";
        } else if ("org.lineageos.aperture".equals(pkg)) {
            component = pkg + "/org.lineageos.aperture.CameraActivity";
        } else if ("com.android.camera2".equals(pkg) || "com.android.camera".equals(pkg)) {
            component = pkg + "/com.android.camera.CameraLauncher";
        }
        if (component == null) {
            // Generic MAIN/LAUNCHER best-effort
            String err = shellOut(new String[]{"/system/bin/am", "start",
                "--display", "0",
                "-a", "android.intent.action.MAIN",
                "-c", "android.intent.category.LAUNCHER",
                pkg}, 4000);
            if (err != null && err.toLowerCase().contains("error")) {
                Log.w(TAG, "shell pull-main " + pkg + ": " + err.trim());
                return false;
            }
            return true;
        }
        String err = shellOut(new String[]{"/system/bin/am", "start",
            "--display", "0",
            "-n", component}, 4000);
        if (err != null) {
            String low = err.toLowerCase();
            if (low.contains("exception") || low.contains("error:")
                    || low.contains("does not exist") || low.contains("unable")) {
                Log.w(TAG, "shell pull-main " + component + ": " + err.trim());
                return false;
            }
        }
        return true;
    }

    /**
     * 15.15: display-scoped remove — main-display same package survives.
     * Only returns true when post-list confirms the stack is gone (never claim
     * success on empty/denied shell — that skipped FORCE_STOP residual).
     */
    private static boolean shellStackRemove(int stackId) {
        if (stackId < 0) return false;
        String marker = "RootTask id=" + stackId + " ";
        String before = shellOut(new String[]{"/system/bin/am", "stack", "list"}, 3000);
        if (before == null || !before.contains(marker)) {
            // Already gone or list denied — do not claim success.
            return before != null && !before.contains(marker);
        }
        String err = shellOut(new String[]{"/system/bin/am", "stack", "remove",
            String.valueOf(stackId)}, 3000);
        if (err != null) {
            String low = err.toLowerCase();
            if (low.contains("exception") || low.contains("error:")
                    || low.contains("security") || low.contains("permission")
                    || low.contains("denied")) {
                Log.w(TAG, "shell stack remove " + stackId + ": " + err.trim());
                return false;
            }
        }
        String after = shellOut(new String[]{"/system/bin/am", "stack", "list"}, 3000);
        if (after == null) {
            Log.w(TAG, "shell stack remove " + stackId + ": post-list empty");
            return false;
        }
        boolean gone = !after.contains(marker);
        if (!gone) {
            Log.w(TAG, "shell stack remove " + stackId + ": still present");
        }
        return gone;
    }

    /**
     * 15.16: display-scoped single-task remove — main-display same package lives.
     * {@code am task remove} is preferred over FORCE_STOP when stack remove fails.
     */
    private static boolean shellTaskRemove(int taskId) {
        if (taskId < 0) return false;
        String marker = "taskId=" + taskId;
        String before = shellOut(new String[]{"/system/bin/am", "stack", "list"}, 3000);
        if (before == null) return false;
        if (!before.contains(marker)) {
            // Already gone.
            return true;
        }
        String err = shellOut(new String[]{"/system/bin/am", "task", "remove",
            String.valueOf(taskId)}, 3000);
        if (err != null) {
            String low = err.toLowerCase();
            if (low.contains("exception") || low.contains("error:")
                    || low.contains("security") || low.contains("permission")
                    || low.contains("denied") || low.contains("not found")
                    || low.contains("unknown")) {
                // Try alternate: am stack remove-task (older trains)
                String err2 = shellOut(new String[]{"/system/bin/am", "stack",
                    "remove-task", String.valueOf(taskId)}, 3000);
                if (err2 != null) {
                    String low2 = err2.toLowerCase();
                    if (low2.contains("exception") || low2.contains("error:")
                            || low2.contains("security") || low2.contains("permission")
                            || low2.contains("denied") || low2.contains("unknown")) {
                        Log.w(TAG, "shell task remove " + taskId + ": " + err.trim());
                        return false;
                    }
                }
            }
        }
        String after = shellOut(new String[]{"/system/bin/am", "stack", "list"}, 3000);
        if (after == null) {
            Log.w(TAG, "shell task remove " + taskId + ": post-list empty");
            return false;
        }
        boolean gone = !after.contains(marker);
        if (!gone) {
            Log.w(TAG, "shell task remove " + taskId + ": still present");
        }
        return gone;
    }

    private static String shellOut(String[] cmd, int timeoutMs) {
        Process p = null;
        try {
            p = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start();
            final Process proc = p;
            final StringBuilder sb = new StringBuilder();
            Thread t = new Thread(() -> {
                try (java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(proc.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (sb.length() < 64_000) sb.append(line).append('\n');
                    }
                } catch (Exception ignored) {}
            }, "t2-shell-out");
            t.setDaemon(true);
            t.start();
            boolean done = p.waitFor(Math.max(500, timeoutMs),
                java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!done) {
                try { p.destroyForcibly(); } catch (Exception ignored) {}
            }
            try { t.join(500); } catch (Exception ignored) {}
            return sb.toString();
        } catch (Exception e) {
            Log.d(TAG, "shellOut: " + e.getMessage());
            return null;
        } finally {
            if (p != null) {
                try { p.destroy(); } catch (Exception ignored) {}
            }
        }
    }

    private static boolean finishOwnAppTask(Context app, int taskId) {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager)
                app.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null || Build.VERSION.SDK_INT < 21) return false;
            for (android.app.ActivityManager.AppTask at : am.getAppTasks()) {
                try {
                    android.app.ActivityManager.RecentTaskInfo ti = at.getTaskInfo();
                    if (ti == null) continue;
                    int id = ti.id;
                    if (Build.VERSION.SDK_INT >= 29 && ti.taskId > 0) id = ti.taskId;
                    if (id == taskId) {
                        at.finishAndRemoveTask();
                        return true;
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return false;
    }

    /** FORCE_STOP_PACKAGES — priv-app granted (leave rear tile residual). */
    private static boolean forceStopPackage(Context app, String pkg) {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager)
                app.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return false;
            java.lang.reflect.Method m = android.app.ActivityManager.class
                .getMethod("forceStopPackage", String.class);
            m.invoke(am, pkg);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "forceStopPackage " + pkg + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Tasks that must stay on rear when leaving Apps (Settings hub, Face, shell).
     */
    private static boolean keepRearTaskOnLeave(android.content.ComponentName top) {
        if (top == null) return true;
        String pkg = top.getPackageName();
        String cls = top.getClassName();
        if (pkg == null) return true;
        if ("com.android.systemui".equals(pkg)) return true;
        if ("com.android.launcher3".equals(pkg)) return true;
        if ("com.titanus2.controls".equals(pkg)) {
            // Keep Sub display Settings UI (mode switch on rear or main) + Face.
            // Launcher home is already finished via dismiss live instance.
            if (cls != null) {
                if (cls.contains("SubDisplayActivity")) return true;
                if (cls.contains("SubDisplayFaceActivity")) return true;
                if (cls.contains("MainActivity")) return true;
            }
            // SubDisplayLauncherActivity and other controls on rear → remove.
            return false;
        }
        return false;
    }

    /**
     * 15.10/15.11: true if display 2 has a <b>visible</b> root task (top may be
     * null mid cold-start — still occupied); false if empty; null if probe
     * unavailable (do not thrash re-home).
     */
    static Boolean rearDisplayOccupied(Context c) {
        if (c == null) return null;
        try {
            Object atm = Class.forName("android.app.ActivityTaskManager")
                .getMethod("getService")
                .invoke(null);
            if (atm == null) return null;
            @SuppressWarnings("unchecked")
            java.util.List<Object> infos = (java.util.List<Object>) atm.getClass()
                .getMethod("getAllRootTaskInfos")
                .invoke(atm);
            if (infos == null) return null;
            int rearId = 2;
            try {
                android.view.Display rear = SubDisplayHelper.findRear(c);
                if (rear != null) rearId = rear.getDisplayId();
            } catch (Exception ignored) {}
            for (Object info : infos) {
                if (info == null) continue;
                Class<?> cl = info.getClass();
                int did;
                try {
                    did = cl.getField("displayId").getInt(info);
                } catch (Exception e) {
                    try {
                        Object v = cl.getMethod("getDisplayId").invoke(info);
                        if (v instanceof Integer) did = (Integer) v;
                        else continue;
                    } catch (Exception e2) {
                        continue;
                    }
                }
                if (did != rearId) continue;
                boolean visible = true;
                try {
                    visible = cl.getField("visible").getBoolean(info);
                } catch (Exception e) {
                    try {
                        Object v = cl.getMethod("isVisible").invoke(info);
                        if (v instanceof Boolean) visible = (Boolean) v;
                    } catch (Exception ignored) {}
                }
                if (!visible) continue;
                // 15.11: any visible rear root = occupied (null topActivity during
                // forceOwn cold-start was false-empty after 15.10 → re-home thrash).
                return true;
            }
            return Boolean.FALSE;
        } catch (Exception e) {
            Log.d(TAG, "rear occupied probe: " + e.getMessage());
            return null;
        }
    }

    /**
     * 15.10/15.11: while Apps mode wants the rear lit, re-home if display 2 stays
     * empty ≥900ms <b>and</b> outside launch grace (singleTask Back blank after
     * 15.9; cold-start thrash residual after 15.10).
     */
    private void maybeRehomeIfRearBlank(String why) {
        if (!isAppsMode()) {
            appsBlankSince = 0L;
            return;
        }
        if (!shouldShowRear()) {
            appsBlankSince = 0L;
            return;
        }
        long now = SystemClock.elapsedRealtime();
        // 15.11: forceOwn Clock/Calc/Camera cold-start empties display 2 briefly.
        if (sLastAppsRearLaunchElapsed > 0L
                && now - sLastAppsRearLaunchElapsed < APPS_REAR_LAUNCH_GRACE_MS) {
            appsBlankSince = 0L;
            return;
        }
        Boolean occupied = rearDisplayOccupied(this);
        if (occupied == null) return;
        if (occupied) {
            appsBlankSince = 0L;
            return;
        }
        if (appsBlankSince == 0L) {
            appsBlankSince = now;
            return;
        }
        if (now - appsBlankSince < 900L) return;
        // One re-home; keep stamp so next tick waits another 900ms if still empty.
        appsBlankSince = now;
        Log.i(TAG, "apps rear blank → re-home (" + why + ")");
        try {
            launchRearHome(this);
        } catch (Exception e) {
            Log.w(TAG, "blank re-home: " + e.getMessage());
        }
    }

    /**
     * Open Settings on rear (display 2). Used by launcher Settings tile and
     * as fallback if home launch fails. Prefer {@link #launchRearHome} for
     * Apps mode intent=result.
     * <p>
     * 15.9: when caller is not rear home (main Sub display UI), stage home
     * first then Settings so Back returns tiles (blank residual after NEW_TASK
     * Settings alone on display 2).
     */
    public static void launchRearSettings(Context c) {
        if (c == null) return;
        Context app = c.getApplicationContext() != null ? c.getApplicationContext() : c;
        // Same-task from rear home Activity — inherit path in startOnRear (15.9).
        if (c instanceof android.app.Activity) {
            try {
                android.view.Display hd = ((android.app.Activity) c).getDisplay();
                int hostId = hd != null ? hd.getDisplayId() : android.view.Display.DEFAULT_DISPLAY;
                int rearId = 2;
                try {
                    android.view.Display rear = SubDisplayHelper.findRear(app);
                    if (rear != null) rearId = rear.getDisplayId();
                } catch (Exception ignored) {}
                if (hostId == rearId) {
                    Intent i = new Intent(Settings.ACTION_SETTINGS);
                    if (SubDisplayLauncherActivity.startOnRear(c, i)) {
                        Log.i(TAG, "launch Settings same-task rear");
                        return;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "rear Settings same-task: " + e.getMessage());
            }
        }
        // App-context / main-display: home under then NEW_TASK Settings (15.9).
        try { launchRearHome(app); } catch (Exception ignored) {}
        final Context appFinal = app;
        sHomeBelt.postDelayed(() -> {
            Intent i = new Intent(Settings.ACTION_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (SubDisplayLauncherActivity.startOnRear(appFinal, i)) {
                Log.i(TAG, "launch Settings on rear (home-under)");
                return;
            }
            try {
                appFinal.startActivity(i);
                Log.w(TAG, "rear Settings fell back to default display");
            } catch (Exception e) {
                Log.w(TAG, "Settings launch failed: " + e.getMessage());
            }
        }, 350L);
    }

    private static void forceSubtouchInhibitShell(Context c) {
        applySubtouchPolicy(c);
    }

    private static void cmd(Context c, String action) {
        Intent i = new Intent(c, SubDisplayService.class);
        i.setAction(action);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception e) {
            Log.w(TAG, action + " " + e.getMessage());
        }
    }

    private boolean isRearMode() {
        SubDisplayPrefs.Mode m = SubDisplayPrefs.getMode(this);
        return m == SubDisplayPrefs.Mode.CUSTOM
            || m == SubDisplayPrefs.Mode.STOCK
            || m == SubDisplayPrefs.Mode.APPS
            || m == SubDisplayPrefs.Mode.CUBE;
    }

    private boolean isFaceMode() {
        SubDisplayPrefs.Mode m = SubDisplayPrefs.getMode(this);
        return m == SubDisplayPrefs.Mode.CUSTOM || m == SubDisplayPrefs.Mode.STOCK;
    }

    private boolean isCubeMode() {
        return SubDisplayPrefs.getMode(this) == SubDisplayPrefs.Mode.CUBE;
    }

    private boolean isAppsMode() {
        return SubDisplayPrefs.getMode(this) == SubDisplayPrefs.Mode.APPS;
    }

    @Override public void onCreate() {
        super.onCreate();
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_USER_PRESENT);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(screenRxr, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(screenRxr, f);
            }
            screenRx = true;
        } catch (Exception e) {
            Log.w(TAG, "rx " + e.getMessage());
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String act = intent != null && intent.getAction() != null
            ? intent.getAction() : ACTION_APPLY;
        if (ACTION_TOGGLE.equals(act)) {
            if (SubDisplayPrefs.isOn(this)
                && SubDisplayPrefs.getMode(this) != SubDisplayPrefs.Mode.OFF) {
                enterOff();
                return START_NOT_STICKY;
            }
            SubDisplayPrefs.setMode(this, SubDisplayPrefs.Mode.CUSTOM);
            act = ACTION_START;
        }
        if (ACTION_STOP.equals(act)) {
            enterOff();
            return START_NOT_STICKY;
        }

        SubDisplayPrefs.Mode mode = SubDisplayPrefs.getMode(this);
        if (mode == SubDisplayPrefs.Mode.STOCK) {
            SubDisplayPrefs.setMode(this, SubDisplayPrefs.Mode.CUSTOM);
            mode = SubDisplayPrefs.Mode.CUSTOM;
        }
        if (mode == SubDisplayPrefs.Mode.OFF && ACTION_START.equals(act)) {
            SubDisplayPrefs.setMode(this, SubDisplayPrefs.Mode.CUSTOM);
            mode = SubDisplayPrefs.Mode.CUSTOM;
        }
        if (mode == SubDisplayPrefs.Mode.OFF) {
            enterOff();
            return START_NOT_STICKY;
        }
        // 15.4: never clobber APPS/CUBE → CUSTOM on every APPLY/WAKE/REFRESH.
        if (mode != SubDisplayPrefs.Mode.APPS
                && mode != SubDisplayPrefs.Mode.CUSTOM
                && mode != SubDisplayPrefs.Mode.CUBE) {
            SubDisplayPrefs.setMode(this, SubDisplayPrefs.Mode.CUSTOM);
            mode = SubDisplayPrefs.Mode.CUSTOM;
        }

        startFg();
        applySubtouchPolicy(this);
        SubDisplaySystemUi.apply(this);

        boolean bump = ACTION_START.equals(act) || ACTION_WAKE.equals(act)
            || ACTION_APPLY.equals(act) || ACTION_REFRESH.equals(act);
        ensureRear("start:" + act, bump);

        h.removeCallbacks(tick);
        h.postDelayed(tick, TICK_MS);
        h.removeCallbacks(watchdog);
        h.postDelayed(watchdog, WATCHDOG_MS);

        return START_STICKY;
    }

    private void tickRear() {
        if (!isRearMode() || SubDisplayPrefs.getMode(this) == SubDisplayPrefs.Mode.OFF) {
            return;
        }
        ensureRear("tick", false);
        h.postDelayed(tick, tickIntervalMs());
    }

    private void watchdogTick() {
        if (!isRearMode() || SubDisplayPrefs.getMode(this) == SubDisplayPrefs.Mode.OFF) {
            return;
        }
        // Re-assert hardware only — do not treat as user activity
        ensureRear("watchdog", false);
        h.postDelayed(watchdog, watchdogIntervalMs());
    }

    /** 15.33: cube + main asleep → sparse tick (steady rear already lit). */
    private long tickIntervalMs() {
        if (isCubeMainAsleepSteady()) return TICK_CUBE_ASLEEP_MS;
        return TICK_MS;
    }

    private long watchdogIntervalMs() {
        if (isCubeMainAsleepSteady()) return WATCHDOG_CUBE_ASLEEP_MS;
        return WATCHDOG_MS;
    }

    private boolean isCubeMainAsleepSteady() {
        try {
            SubDisplayPrefs.Mode m = SubDisplayPrefs.getMode(this);
            if (m != SubDisplayPrefs.Mode.CUBE && !SubDisplayPrefs.cubeOwnsRear(this)) {
                return false;
            }
            if (isMainInteractive()) return false;
            return SubDisplayPower.isPowerKnownOn();
        } catch (Exception e) {
            return false;
        }
    }

    /** Power/face off while mode stays ON only when policy says so (sleep prefs). */
    private void rearOffForMainUse(String why) {
        if (!isRearMode()) return;
        SubDisplayPower.invalidatePowerState();
        SubDisplayPower.apply(this, false);
        SubDisplayFaceOverlay.hide(this);
        rearIdle = false;
        Log.i(TAG, "rear off (" + why + ")");
        startFg();
    }

    /**
     * Mode CUSTOM/STOCK means rear is wanted. Sleep can still darken it via
     * {@link SubDisplayPrefs#keepRearWhenOff}. Unlock must not cancel manual On /
     * side-key toggle (2026-07-20 release regression).
     */
    private boolean shouldShowRear() {
        // Asleep (screen off): optional "show while phone sleeps".
        if (!isMainInteractive()) return SubDisplayPrefs.keepRearWhenOff(this);
        // Interactive (locked or unlocked): mode On → rear On.
        return true;
    }

    private boolean isMainInteractive() {
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            return pm != null && pm.isInteractive();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isKeyguardLocked() {
        try {
            KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
            if (km == null) return true;
            if (km.isKeyguardLocked()) return true;
            // isDeviceLocked covers secure lock after boot
            if (Build.VERSION.SDK_INT >= 22 && km.isDeviceLocked()) return true;
            return false;
        } catch (Exception e) {
            return true; // fail open for rear AoD / DT2W
        }
    }

    /**
     * @param bumpActive true = reset rear idle timer (sleep start / DT2W)
     */
    private void ensureRear(String why, boolean bumpActive) {
        SubDisplayPrefs.Mode mode = SubDisplayPrefs.getMode(this);
        if (mode == SubDisplayPrefs.Mode.STOCK) {
            SubDisplayPrefs.setMode(this, SubDisplayPrefs.Mode.CUSTOM);
            mode = SubDisplayPrefs.Mode.CUSTOM;
        }
        if (mode != SubDisplayPrefs.Mode.CUSTOM
                && mode != SubDisplayPrefs.Mode.APPS
                && mode != SubDisplayPrefs.Mode.CUBE) return;

        // Cube owns rear: prefs OR plane tokens (sacred — no Face/AOD clocks).
        if (mode == SubDisplayPrefs.Mode.CUBE || SubDisplayPrefs.cubeOwnsRear(this)) {
            if (mode != SubDisplayPrefs.Mode.CUBE) {
                // Heal prefs race that left mode=custom while cube plane is live.
                SubDisplayPrefs.setMode(this, SubDisplayPrefs.Mode.CUBE);
                mode = SubDisplayPrefs.Mode.CUBE;
            }
            // Cube always wants rear when mode On (ignore sticky keepRearWhenOff=false).
            if (!isMainInteractive() && !SubDisplayPrefs.keepRearWhenOff(this)) {
                SubDisplayPrefs.setKeepRearWhenOff(this, true);
            }
            if (!shouldShowRear()) {
                // Only darken rear — never dismiss cube ownership of the plane.
                SubDisplayPower.invalidatePowerState();
                SubDisplayPower.sleepDisplayOnly(this, "cube/no-show");
                SubDisplayFaceOverlay.hide(this);
                SubDisplayFaceActivity.dismiss(this);
                applySubtouchPolicy(this);
                startFg();
                return;
            }
            if (bumpActive || rearActiveAt == 0) {
                rearActiveAt = SystemClock.elapsedRealtime();
                rearIdle = false;
            }
            int bri = Math.max(1, SubDisplayPrefs.getBrightnessPct(this));
            // Cube is always-bright resident: no face idle dim-to-off.
            rearIdle = false;
            // 15.33: only edge transitions invalidate power cache. tick/watchdog
            // used to clear lastHw every pass → wakeRearHardwareOnly always
            // re-stamped APPLY + kickHardware under mainAsleep cube thrash.
            boolean edgeInvalidate = why != null
                && (why.contains("screen") || why.contains("late")
                    || why.contains("DT2W") || why.contains("start")
                    || why.contains("APPLY") || why.contains("unlocked"));
            if (edgeInvalidate) {
                SubDisplayPower.invalidatePowerState();
            }

            boolean mainAwake = isMainInteractive();
            if (!mainAwake) {
                int wantHw = Math.max(1, Math.min(255, Math.round(bri * 2.55f)));
                // Steady park: rear already ON at target hw — skip Face hide /
                // SystemUI / associate / plane stamp every 2s (lab residual).
                if (!edgeInvalidate && SubDisplayPower.isSteadyOn(wantHw)) {
                    Log.d(TAG, "cube rear steady park mainAsleep (" + why + ")");
                    startFg();
                    return;
                }
                // Sacred: kill Face overlay + face activity on edge / first light.
                SubDisplayFaceOverlay.hide(this);
                SubDisplayFaceActivity.dismiss(this);
                try {
                    SubDisplaySystemUi.apply(this);
                } catch (Exception ignored) {}
                // Main asleep: hardware rear only — no DisplayManager, no startActivity.
                SubDisplayPower.wakeRearHardwareOnly(this, why != null ? why : "cube/asleep");
                applySubtouchPolicy(this);
                // Keep cube process if already on display 2; never force launch
                // (would wake main). Soft re-bind only when already wanted.
                if (SubDisplayCubeBridge.isWanted()) {
                    SubDisplayCubeBridge.show(this, false);
                }
                Log.i(TAG, "cube rear HW-only mainAsleep (" + why + ")");
                startFg();
                return;
            }

            // Sacred: kill Face overlay + face activity every main-awake tick.
            SubDisplayFaceOverlay.hide(this);
            SubDisplayFaceActivity.dismiss(this);
            // Suppress SystemUI ambient / secondary keyguard clock tokens.
            try {
                SubDisplaySystemUi.apply(this);
            } catch (Exception ignored) {}

            // Main interactive: rear panel power via normal path; do not
            // force-launch on every screen-on/tick (steals focus + dual light).
            SubDisplayPower.wakeDisplayOnly(this, why != null ? why : "cube");
            applySubtouchPolicy(this);
            boolean forceLaunch = bumpActive
                && why != null
                && (why.startsWith("start:")
                    || why.contains("unlocked")
                    || why.contains("APPLY")
                    || why.contains("subdisplay.START"));
            // Explicit user Cube mode / APPLY only — not screen-off/on residual.
            if (forceLaunch || !SubDisplayCubeBridge.isWanted()) {
                SubDisplayCubeBridge.show(this, forceLaunch || !SubDisplayCubeBridge.isWanted());
            }
            if (forceLaunch || (why != null && why.contains("watchdog"))) {
                Log.i(TAG, "cube rear bri=" + bri + "% mainAwake=true (" + why + ")");
            }
            startFg();
            return;
        }

        // Apps mode: panel + digitizer, no face clock overlay.
        if (mode == SubDisplayPrefs.Mode.APPS) {
            if (!shouldShowRear()) {
                SubDisplayPower.invalidatePowerState();
                SubDisplayPower.apply(this, false);
                SubDisplayFaceOverlay.hide(this);
                applySubtouchPolicy(this);
                Log.i(TAG, "apps rear off (" + why + "/no-show)");
                startFg();
                return;
            }
            if (bumpActive || rearActiveAt == 0) {
                rearActiveAt = SystemClock.elapsedRealtime();
                rearIdle = false;
            }
            int bri = Math.max(1, SubDisplayPrefs.getBrightnessPct(this));
            if (why != null && (why.contains("screen") || why.contains("watchdog")
                    || why.contains("late") || why.contains("DT2W") || why.contains("start"))) {
                SubDisplayPower.invalidatePowerState();
            }
            SubDisplayFaceOverlay.hide(this);
            // 15.55: main asleep → HW-only (no DisplayManager / main power group).
            if (!isMainInteractive()) {
                SubDisplayPower.wakeRearHardwareOnly(this,
                    why != null ? why : "apps/asleep");
            } else {
                SubDisplayPower.apply(this, true, bri, true, /*appsMode*/ true);
            }
            applySubtouchPolicy(this);
            // 15.7: APPLY/START edge re-homes (plane stamp left blank digitizer after
            // 15.6). Never unconditional tick re-home — CLEAR_TOP thrash.
            if (bumpActive && why != null
                    && (why.contains("subdisplay.APPLY") || why.contains("subdisplay.START"))) {
                h.post(() -> {
                    try { launchRearHome(SubDisplayService.this); }
                    catch (Exception e) { Log.w(TAG, "apps re-home: " + e.getMessage()); }
                });
            }
            // 15.10: occupancy blank watch (singleTask Clock/Calc Back residual).
            maybeRehomeIfRearBlank(why != null ? why : "apps");
            Log.i(TAG, "apps rear bri=" + bri + "% (" + why + ")");
            startFg();
            return;
        }

        if (!shouldShowRear()) {
            rearOffForMainUse(why != null ? why + "/no-show" : "no-show");
            return;
        }

        if (bumpActive || rearActiveAt == 0) {
            rearActiveAt = SystemClock.elapsedRealtime();
            rearIdle = false;
        }

        int active = Math.max(1, SubDisplayPrefs.getBrightnessPct(this));
        int idle = Math.max(0, SubDisplayPrefs.getDimBrightnessPct(this));
        int timeoutSec = SubDisplayPrefs.getTimeoutSec(this);
        int bri = active;
        if (timeoutSec > 0) {
            long ageSec = (SystemClock.elapsedRealtime() - rearActiveAt) / 1000L;
            if (ageSec >= timeoutSec) {
                rearIdle = true;
                bri = idle;
            } else {
                rearIdle = false;
                bri = active;
            }
        } else {
            rearIdle = false;
            bri = active;
        }

        if (rearIdle && bri <= 0) {
            SubDisplayPower.invalidatePowerState();
            SubDisplayPower.apply(this, false);
            SubDisplayFaceOverlay.hide(this);
            Log.i(TAG, "rear idle powered off (" + why + ")");
            startFg();
            return;
        }

        if (why != null && (why.contains("screen") || why.contains("watchdog")
                || why.contains("late") || why.contains("DT2W") || why.contains("start"))) {
            SubDisplayPower.invalidatePowerState();
        }

        // Panel + backlight. When main is asleep: hardware rear only
        // (15.55 — DisplayManager.setBrightness re-wakes main power group and
        // breaks sleep; never bare PowerManager.wakeUp() either).
        if (!isMainInteractive()) {
            SubDisplayPower.wakeRearHardwareOnly(this,
                why != null ? why : "ensureRear/face-asleep");
        } else {
            SubDisplayPower.apply(this, true, Math.max(1, bri), true);
        }
        // Face path only — refuse if cube plane took ownership mid-tick.
        if (SubDisplayPrefs.cubeOwnsRear(this)) {
            SubDisplayFaceOverlay.hide(this);
            SubDisplayFaceActivity.dismiss(this);
            SubDisplayPrefs.setMode(this, SubDisplayPrefs.Mode.CUBE);
            applySubtouchPolicy(this);
            SubDisplayCubeBridge.show(this, bumpActive);
            startFg();
            return;
        }
        SubDisplayFaceOverlay.show(this);
        applySubtouchPolicy(this);
        Log.i(TAG, "rear bri=" + bri + "% idle=" + rearIdle
            + " locked=" + isKeyguardLocked()
            + " interactive=" + isMainInteractive()
            + " (" + why + ")");
        startFg();
    }

    private void enterOff() {
        h.removeCallbacksAndMessages(null);
        SubDisplayPrefs.setMode(this, SubDisplayPrefs.Mode.OFF);
        SubDisplayFaceOverlay.hide(this);
        SubDisplayFaceActivity.dismiss(this);
        SubDisplayCubeBridge.dismiss(this);
        // 15.13: ACTION_STOP path must also clear rear Apps home + torch.
        try { SubDisplayLauncherActivity.dismiss(this); } catch (Exception ignored) {}
        SubDisplayPower.invalidatePowerState();
        SubDisplayPower.apply(this, false);
        // Power off does not rewrite rear-touch prefs; plane keeps product
        // inhibit (default) or trackpad-on until user toggles (FB-DISP-1).
        applySubtouchPolicy(this);
        stopForeground(true);
        stopSelf();
        Log.i(TAG, "OFF");
    }

    private void startFg() {
        Intent open = new Intent(this, SubDisplayActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CH, "Rear clock",
                NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(this, CH)
            : new Notification.Builder(this);
        int to = SubDisplayPrefs.getTimeoutSec(this);
        String idle = to <= 0 ? "always bright when shown" : ("idle " + to + "s");
        SubDisplayPrefs.Mode m = SubDisplayPrefs.getMode(this);
        String modeLine = m == SubDisplayPrefs.Mode.CUBE
            ? "Cube · rear lattice (no clock)"
            : m == SubDisplayPrefs.Mode.APPS
            ? ("Apps · " + idle)
            : ("Face · " + idle);
        Notification n = b.setContentTitle("Rear display")
            .setContentText(modeLine)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build();
        startForeground(42, n);
    }

    @Override public void onDestroy() {
        h.removeCallbacksAndMessages(null);
        if (screenRx) {
            try { unregisterReceiver(screenRxr); } catch (Exception ignored) {}
            screenRx = false;
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
