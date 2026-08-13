package com.titanus2.controls.subdisplay;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal secondary launcher on rear (display 2) — Apps mode home.
 * <p>
 * HW_REIMPLEMENT §6 / SUB_DISPLAY_INDEPENDENT: OEM SecondaryLauncher parity
 * without Agui. Dense Material / DeviceDefault, keyboard-first, short labels.
 * Not Cube chrome (PRODUCT_UX).
 * <p>
 * Keys (no modifiers): 1–6 tiles · S Settings · H/Home re-home · Esc stay
 * (15.7: Esc finish left blank digitizer while mode=Apps).
 * <p>
 * 15.8: tile apps stack on this task (Activity start, no NEW_TASK) so Back
 * returns home — app-context NEW_TASK left blank rear after Settings/etc.
 * <p>
 * 15.9: same-task prefer <b>inherit host display</b> (no setLaunchDisplayId)
 * first — opts+displayId often forced a separate rear task so Back left blank
 * even after 15.8 strip-NEW_TASK. Home under NEW_TASK Settings (service).
 * Torch off on destroy. D-pad focus first tile (keyboard-first).
 * <p>
 * 15.10: strip RESET/CLEAR_TASK on same-task; service blank-watch re-homes when
 * singleTask Clock/Calc leave display 2 empty (canHostTasks=false residual).
 * <p>
 * 15.11: noteAppsRearLaunch grace + visible-null-top occupancy (15.10 blank-watch
 * re-homed mid cold-start of forceOwn tiles). Camera → Aperture package;
 * Clock drops Etar (calendar) mis-route.
 * <p>
 * 15.12: Files never generic VIEW+MIME (system chooser / main residual); explicit
 * DocumentsUI FilesActivity + Aperture CameraActivity components (Launcher
 * trampoline often leaves sibling task / blank after Back). Tile + home starts
 * re-assert apps rear power + digitizer before startActivity.
 * <p>
 * 15.13: leave Apps → {@link #dismiss} finishes rear home (torch + stack under
 * Face residual after mode Off/Face). Clock/Calc use explicit DeskClock /
 * Calculator components — SHOW_ALARMS resolved to HandleApiCalls (blank/finish).
 * <p>
 * 15.14: leave Apps residual after 15.13 — forceOwn tiles (Files/Clock/Camera)
 * stayed on display 2 under Face (home-only dismiss). {@link #dismiss} also
 * {@link SubDisplayService#clearRearAppsStack} + registered dismiss broadcast.
 * <p>
 * 15.15: leave-scoped residual after 15.14 — blind FORCE_STOP of every known
 * tile package murdered main-display Clock/Files/Calc. Track + persist rear
 * tile pkgs; clear only packages confirmed on rear (or session-tracked).
 * <p>
 * 15.16: leave-task residual after 15.15 — session-track alone still FORCE_STOP
 * package-wide when stack remove failed (main Clock dies after earlier rear
 * open). Prefer {@code am task remove} + re-probe; FORCE_STOP only still-rear.
 * <p>
 * 15.17: leave-move residual after 15.16 — singleTask Clock/Calc <b>move</b> their
 * one task to rear; task remove / FORCE_STOP killed that same task (main Clock
 * gone). Prefer {@code am display move-stack → display 0}; never FORCE_STOP
 * shared singleTask tile packages.
 * <p>
 * 15.18: leave-focus residual after 15.17 — move-stack / pull-main left Clock
 * focused on main (intent=Face, result=main yanked to Clock). Capture main
 * front before leave; restore after move so the process lives but main focus
 * is unchanged.
 * <p>
 * 15.19: leave-verify residual after 15.18 — async pull/start can re-front
 * Clock after restore; post-settle verify demotes shared singleTask thieves.
 * 15.20: leave-belt residual after 15.19 — second-wave AM REORDER after short
 * settle; Handler multi-pass demote+restore at 400/900/1600ms.
 * 15.21: leave-pin residual after 15.20 — belt demote HOME fallback flashed
 * launcher; late third-wave after 1600ms; pin setFocusedTask + no-HOME demote
 * + longer belt + REORDER shell restore.
 * 15.22: leave-rebind residual after 15.21 — pin fail-closed without REORDER
 * fallback; demote only top taskId; stale pre-leave taskId after death.
 * Rebind live want.taskId + demote all main shared thieves + REORDER pin.
 */
public class SubDisplayLauncherActivity extends Activity {
    private static final String TAG = "SubDisplayLauncher";
    private static final String ACTION_DISMISS =
        "com.titanus2.controls.subdisplay.REAR_APPS_DISMISS";
    /** 15.15: survive Controls process death between tile open and leave Apps. */
    private static final String PREF_REAR_TILE = "titan2_rear_tile_pkgs";
    private static final String PREF_KEY_PKGS = "pkgs";

    /** Live rear home instance (singleTask) — for mode-leave dismiss. */
    private static volatile SubDisplayLauncherActivity sInstance;

    /**
     * 15.14/15.15: packages started on rear via tiles (forceOwn NEW_TASK).
     * Leave Apps clears these (stack remove preferred; scoped FORCE_STOP).
     */
    private static final Set<String> sRearTilePkgs = ConcurrentHashMap.newKeySet();

    private TextView clockLine;
    private TextView hintLine;
    private TextView torchTile;
    private TextView firstTile;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final List<Tile> tiles = new ArrayList<>();
    private boolean torchOn;
    private String torchCameraId;
    private boolean dismissRx;

    private final BroadcastReceiver dismissRxr = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            if (ACTION_DISMISS.equals(intent.getAction())) {
                try {
                    forceTorchOff();
                    finishAndRemoveTaskSafe();
                } catch (Exception e) {
                    Log.w(TAG, "dismiss rx: " + e.getMessage());
                }
            }
        }
    };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            paintClock();
            h.postDelayed(this, 30_000L);
        }
    };

    private static final class Tile {
        final String label;
        final Runnable run;
        Tile(String label, Runnable run) {
            this.label = label;
            this.run = run;
        }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sInstance = this;
        try {
            if (Build.VERSION.SDK_INT >= 27) {
                setShowWhenLocked(true);
                setTurnScreenOn(true);
            }
        } catch (Exception ignored) {}
        // 15.14: register dismiss so leave-Apps works when sInstance race / dual process.
        try {
            IntentFilter f = new IntentFilter(ACTION_DISMISS);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(dismissRxr, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(dismissRxr, f);
            }
            dismissRx = true;
        } catch (Exception e) {
            Log.w(TAG, "dismiss rx reg: " + e.getMessage());
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(10), dp(12), dp(10));
        // Night OLED rear: black canvas; tiles use theme attrs (no Cube chrome).
        root.setBackgroundColor(Color.BLACK);

        clockLine = new TextView(this);
        clockLine.setTextColor(0xFF00E5FF);
        clockLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        clockLine.setTypeface(android.graphics.Typeface.MONOSPACE);
        clockLine.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(clockLine, lp(-1, -2, 0, 0, 0, 8));

        hintLine = new TextView(this);
        hintLine.setTextColor(0xFF008899);
        hintLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        hintLine.setTypeface(android.graphics.Typeface.MONOSPACE);
        hintLine.setGravity(Gravity.CENTER_HORIZONTAL);
        hintLine.setText("Apps · 1–6 · S Settings · H home");
        root.addView(hintLine, lp(-1, -2, 0, 0, 0, 10));

        buildTiles();
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        root.addView(grid, lp(-1, -2, 0, 0, 0, 0));

        for (int row = 0; row < 3; row++) {
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER);
            for (int col = 0; col < 2; col++) {
                int idx = row * 2 + col;
                if (idx >= tiles.size()) break;
                Tile t = tiles.get(idx);
                TextView btn = tileButton((idx + 1) + "  " + t.label, t.run);
                if (idx == 0) firstTile = btn;
                if ("Torch".equals(t.label) || (t.label != null && t.label.startsWith("Torch"))) {
                    torchTile = btn;
                }
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(72), 1f);
                p.setMargins(dp(4), dp(4), dp(4), dp(4));
                r.addView(btn, p);
            }
            grid.addView(r, lp(-1, -2, 0, 0, 0, 0));
        }

        setContentView(root);
        paintClock();
        paintTorchTile();
        // Keyboard-first: seed focus so D-pad moves across tiles (PRODUCT_UX).
        if (firstTile != null) {
            firstTile.setFocusableInTouchMode(true);
            firstTile.requestFocus();
        }
    }

    private void buildTiles() {
        tiles.clear();
        tiles.add(new Tile("Settings", this::openSettings));
        // 15.11: deskclock only — org.lineageos.etar is Calendar (mis-route residual).
        // 15.13: explicit DeskClock (SHOW_ALARMS → HandleApiCalls blank residual).
        tiles.add(new Tile("Clock", this::openClock));
        tiles.add(new Tile("Calc", this::openCalc));
        tiles.add(new Tile("Camera", this::openCamera));
        // 15.12: never ACTION_VIEW */MIME — system Resolver (main chooser residual).
        // Prefer FilesActivity over Launcher trampoline.
        tiles.add(new Tile("Files", this::openFiles));
        tiles.add(new Tile("Torch", this::toggleTorch));
    }

    /** AlarmClock is API; avoid extra import noise if missing on compile — use string. */
    private static final class AlarmClock {
        static final String ACTION_SHOW_ALARMS = "android.intent.action.SHOW_ALARMS";
    }

    private TextView tileButton(String label, Runnable action) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setGravity(Gravity.CENTER);
        tv.setTextColor(0xFFE0E0E0);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setBackgroundColor(0xFF121212);
        tv.setPadding(dp(6), dp(10), dp(6), dp(10));
        tv.setFocusable(true);
        tv.setClickable(true);
        tv.setOnClickListener(v -> {
            try { action.run(); } catch (Exception e) {
                Log.w(TAG, "tile: " + e.getMessage());
            }
        });
        tv.setOnFocusChangeListener((v, has) ->
            v.setBackgroundColor(has ? 0xFF1A3A40 : 0xFF121212));
        return tv;
    }

    private void paintClock() {
        if (clockLine == null) return;
        String t = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
        String d = new SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(new Date());
        clockLine.setText(t + "\n" + d);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // singleTask re-home: re-assert digitizer + clock (15.7 blank residual).
        paintClock();
        paintTorchTile();
        try { SubDisplayService.applySubtouchPolicy(this); } catch (Exception ignored) {}
    }

    @Override
    protected void onResume() {
        super.onResume();
        paintClock();
        paintTorchTile();
        h.removeCallbacks(tick);
        h.postDelayed(tick, 30_000L);
        // Re-assert digitizer while user is in rear apps home.
        try { SubDisplayService.applySubtouchPolicy(this); } catch (Exception ignored) {}
        if (firstTile != null && !firstTile.hasFocus()) {
            firstTile.requestFocus();
        }
    }

    @Override
    protected void onPause() {
        h.removeCallbacks(tick);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacks(tick);
        if (dismissRx) {
            try { unregisterReceiver(dismissRxr); } catch (Exception ignored) {}
            dismissRx = false;
        }
        if (sInstance == this) sInstance = null;
        // Intent=result: torch must not stay on after Apps home dies.
        forceTorchOff();
        super.onDestroy();
        // System killed home while mode stays Apps (NEW_TASK blank residual after
        // 15.8) — re-home once. User Off/Face sets mode first so this no-ops
        // (15.13 leave-apps dismiss).
        if (SubDisplayPrefs.getMode(this) == SubDisplayPrefs.Mode.APPS
                && !isChangingConfigurations()) {
            try {
                SubDisplayService.scheduleRearHomeBelt(getApplicationContext(), 400L);
            } catch (Exception ignored) {}
        }
    }

    /**
     * 15.13–15.18: leave Apps mode (Face/Off) — finish rear home + forceOwn
     * tile tasks so torch dies and Face is not under Files/Clock.
     * 15.17: move-stack to main for singleTask tiles; never FORCE_STOP shared
     * Clock/Calc; task-remove only after move fails.
     * 15.18: restore pre-leave main focus after move (no Clock yank).
     * Mode must be stamped non-APPS first so {@link #onDestroy} belt no-ops.
     */
    public static void dismiss(Context c) {
        SubDisplayLauncherActivity live = sInstance;
        if (live != null) {
            try {
                live.forceTorchOff();
                live.finishAndRemoveTaskSafe();
            } catch (Exception e) {
                Log.w(TAG, "dismiss live: " + e.getMessage());
            }
        }
        if (c == null) return;
        Context app = c.getApplicationContext() != null ? c.getApplicationContext() : c;
        try {
            app.sendBroadcast(new Intent(ACTION_DISMISS).setPackage(app.getPackageName()));
        } catch (Exception ignored) {}
        // 15.14: home-only dismiss left DocumentsUI/DeskClock on display 2 under Face.
        try {
            SubDisplayService.clearRearAppsStack(app);
        } catch (Exception e) {
            Log.w(TAG, "clear rear stack: " + e.getMessage());
        }
    }

    private void finishAndRemoveTaskSafe() {
        try {
            if (Build.VERSION.SDK_INT >= 21) finishAndRemoveTask();
            else finish();
        } catch (Exception e) {
            try { finish(); } catch (Exception ignored) {}
        }
    }

    private void forceTorchOff() {
        if (!torchOn && torchCameraId == null) return;
        try {
            CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            if (cm != null && torchCameraId != null) {
                cm.setTorchMode(torchCameraId, false);
            }
        } catch (Exception ignored) {}
        torchOn = false;
        try { paintTorchTile(); } catch (Exception ignored) {}
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event != null && event.getAction() == KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 0
                && !event.isAltPressed() && !event.isCtrlPressed()
                && !event.isMetaPressed() && !event.isShiftPressed()) {
            int kc = event.getKeyCode();
            if (kc == KeyEvent.KEYCODE_ESCAPE || kc == KeyEvent.KEYCODE_BACK) {
                // 15.7: stay as Apps home root — Esc/Back finish left blank
                // digitizer while mode stayed Apps (intent≠result).
                if (SubDisplayPrefs.getMode(this) == SubDisplayPrefs.Mode.APPS) {
                    paintClock();
                    try { SubDisplayService.applySubtouchPolicy(this); } catch (Exception ignored) {}
                    return true;
                }
                finish();
                return true;
            }
            if (kc == KeyEvent.KEYCODE_S) {
                openSettings();
                return true;
            }
            if (kc == KeyEvent.KEYCODE_R || kc == KeyEvent.KEYCODE_H
                    || kc == KeyEvent.KEYCODE_HOME) {
                // Re-home: already here; re-assert policy + clock.
                paintClock();
                paintTorchTile();
                try { SubDisplayService.applySubtouchPolicy(this); } catch (Exception ignored) {}
                return true;
            }
            int n = -1;
            if (kc >= KeyEvent.KEYCODE_1 && kc <= KeyEvent.KEYCODE_6) {
                n = kc - KeyEvent.KEYCODE_1;
            } else if (kc >= KeyEvent.KEYCODE_NUMPAD_1 && kc <= KeyEvent.KEYCODE_NUMPAD_6) {
                n = kc - KeyEvent.KEYCODE_NUMPAD_1;
            }
            if (n >= 0 && n < tiles.size()) {
                try { tiles.get(n).run.run(); } catch (Exception e) {
                    Log.w(TAG, "key tile: " + e.getMessage());
                }
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void openSettings() {
        // Same-task stack (15.8) — no NEW_TASK; Back returns to home.
        Intent i = new Intent(Settings.ACTION_SETTINGS);
        startOnRear(this, i);
    }

    /**
     * 15.13: DeskClock UI on rear. SHOW_ALARMS resolves to HandleApiCalls which
     * often finishes immediately (blank rear residual after Clock tile).
     */
    private void openClock() {
        if (openComponents(new String[][]{
                {"com.android.deskclock", "com.android.deskclock.DeskClock"},
                {"com.google.android.deskclock", "com.android.deskclock.DeskClock"},
                {"com.google.android.deskclock",
                    "com.google.android.deskclock.DeskClock"}
            })) {
            return;
        }
        // Package launcher only — never bare SHOW_ALARMS (HandleApiCalls residual).
        openPackageOr(
            new String[]{
                "com.android.deskclock",
                "com.google.android.deskclock"
            },
            null);
        // Last resort: SHOW_ALARMS with component pinned away from HandleApiCalls.
        try {
            Intent alarms = new Intent(AlarmClock.ACTION_SHOW_ALARMS);
            PackageManager pm = getPackageManager();
            List<ResolveInfo> ri = pm.queryIntentActivities(alarms, 0);
            if (ri != null) {
                for (ResolveInfo r : ri) {
                    if (r == null || r.activityInfo == null) continue;
                    String name = r.activityInfo.name;
                    if (name == null || name.contains("HandleApiCalls")) continue;
                    alarms.setComponent(new ComponentName(
                        r.activityInfo.packageName, name));
                    startOnRear(this, alarms);
                    return;
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "openClock alarms: " + e.getMessage());
        }
    }

    /** 15.13: explicit Calculator (selector residual parity with Files 15.12). */
    private void openCalc() {
        if (openComponents(new String[][]{
                {"com.android.calculator2", "com.android.calculator2.Calculator"},
                {"com.google.android.calculator",
                    "com.google.android.calculator.Calculator"},
                {"org.lineageos.calculator", "org.lineageos.calculator.Calculator"}
            })) {
            return;
        }
        openPackageOr(
            new String[]{
                "com.android.calculator2",
                "com.google.android.calculator",
                "org.lineageos.calculator"
            },
            Intent.makeMainSelectorActivity(
                Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALCULATOR));
    }

    private void openCamera() {
        // 15.11/15.12: package-first Aperture CameraActivity (not only Launcher
        // trampoline). STILL_IMAGE_CAMERA alone often lands on main.
        if (openComponents(new String[][]{
                {"org.lineageos.aperture", "org.lineageos.aperture.CameraActivity"},
                {"org.lineageos.aperture", "org.lineageos.aperture.CameraLauncher"},
                {"com.android.camera2", "com.android.camera.CameraLauncher"},
                {"com.android.camera2", "com.android.camera.CameraActivity"},
                {"com.android.camera", "com.android.camera.Camera"}
            })) {
            return;
        }
        openPackageOr(
            new String[]{
                "org.lineageos.aperture",
                "com.android.camera2",
                "com.android.camera"
            },
            new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA));
    }

    /**
     * 15.12: DocumentsUI Files on rear. Explicit FilesActivity first — package
     * LauncherActivity trampolines a sibling task; bare VIEW+MIME opens Resolver.
     */
    private void openFiles() {
        if (openComponents(new String[][]{
                {"com.android.documentsui", "com.android.documentsui.files.FilesActivity"},
                {"com.google.android.documentsui",
                    "com.google.android.documentsui.files.FilesActivity"},
                {"com.android.documentsui", "com.android.documentsui.LauncherActivity"},
                {"com.google.android.documentsui",
                    "com.google.android.documentsui.LauncherActivity"}
            })) {
            return;
        }
        Intent files = Intent.makeMainSelectorActivity(
            Intent.ACTION_MAIN, Intent.CATEGORY_APP_FILES);
        openPackageOr(
            new String[]{
                "com.android.documentsui",
                "com.google.android.documentsui"
            },
            files);
    }

    /**
     * Try explicit component names in order (enabled only). Avoids Launcher
     * trampolines and main-display choosers (15.12).
     */
    private boolean openComponents(String[][] pkgCls) {
        if (pkgCls == null) return false;
        PackageManager pm = getPackageManager();
        for (String[] pair : pkgCls) {
            if (pair == null || pair.length < 2) continue;
            try {
                ComponentName cn = new ComponentName(pair[0], pair[1]);
                try {
                    pm.getActivityInfo(cn, 0);
                } catch (PackageManager.NameNotFoundException e) {
                    continue;
                }
                Intent i = new Intent(Intent.ACTION_MAIN);
                i.setComponent(cn);
                i.addCategory(Intent.CATEGORY_DEFAULT);
                if (startOnRear(this, i)) return true;
            } catch (Exception e) {
                Log.d(TAG, "openComponents " + pair[0] + ": " + e.getMessage());
            }
        }
        return false;
    }

    private void openPackageOr(String[] pkgs, Intent fallback) {
        PackageManager pm = getPackageManager();
        for (String pkg : pkgs) {
            try {
                Intent launch = pm.getLaunchIntentForPackage(pkg);
                if (launch != null) {
                    // Strip launcher NEW_TASK from getLaunchIntent so child
                    // stacks on rear home (15.8 blank residual after Back).
                    if (startOnRear(this, launch)) return;
                }
            } catch (Exception ignored) {}
        }
        if (fallback != null) {
            // Prefer MAIN launcher if selector; never leave bare VIEW for chooser.
            try {
                List<ResolveInfo> ri = pm.queryIntentActivities(fallback, 0);
                if (ri != null && !ri.isEmpty()) {
                    // Skip system ResolverActivity — not a real tile target.
                    for (ResolveInfo r : ri) {
                        if (r == null || r.activityInfo == null) continue;
                        String p = r.activityInfo.packageName;
                        if (p == null || "android".equals(p)
                                || p.startsWith("com.android.internal")) {
                            continue;
                        }
                        fallback.setComponent(new ComponentName(
                            p, r.activityInfo.name));
                        break;
                    }
                }
            } catch (Exception ignored) {}
            if (fallback.getComponent() == null
                    && Intent.ACTION_VIEW.equals(fallback.getAction())) {
                Log.w(TAG, "openPackageOr: skip VIEW without component (chooser residual)");
                return;
            }
            startOnRear(this, fallback);
        }
    }

    private void toggleTorch() {
        CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        if (cm == null) return;
        try {
            if (torchCameraId == null) {
                for (String id : cm.getCameraIdList()) {
                    CameraCharacteristics ch = cm.getCameraCharacteristics(id);
                    Boolean flash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                    Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
                    if (flash != null && flash
                            && facing != null
                            && facing == CameraCharacteristics.LENS_FACING_BACK) {
                        torchCameraId = id;
                        break;
                    }
                }
            }
            if (torchCameraId == null) return;
            torchOn = !torchOn;
            cm.setTorchMode(torchCameraId, torchOn);
            paintTorchTile();
        } catch (CameraAccessException e) {
            Log.w(TAG, "torch: " + e.getMessage());
        } catch (Exception e) {
            Log.w(TAG, "torch: " + e.getMessage());
        }
    }

    private void paintTorchTile() {
        if (torchTile == null) return;
        // Short mono fact only (PRODUCT_UX labels).
        torchTile.setText(torchOn ? "6  Torch on" : "6  Torch");
        torchTile.setTextColor(torchOn ? 0xFF00E5FF : 0xFFE0E0E0);
    }

    /**
     * Start activity on rear display (id 2 or SubDisplayHelper.findRear).
     * <p>
     * 15.8 SoT: when {@code c} is the home {@link Activity}, start <b>same-task</b>
     * (strip NEW_TASK) so Back returns to tiles. App-context always needs
     * NEW_TASK (service re-home / Settings fallback) — separate task path.
     * <p>
     * 15.9 SoT: same-task prefers <b>inherit host display</b> (no
     * {@code setLaunchDisplayId}) first. Opts+displayId on multi-display often
     * created a sibling task so Back left blank rear even with NEW_TASK stripped.
     * <p>
     * 15.10: same-task also strips RESET/CLEAR_TASK (getLaunchIntent residual).
     * Targets with {@code singleTask}/{@code singleInstance} force their own
     * task — use NEW_TASK+displayId and rely on service blank-watch re-home.
     * <p>
     * 15.11: every successful start notes {@link SubDisplayService#noteAppsRearLaunch}
     * so blank-watch does not re-home during forceOwn cold-start empty window.
     * <p>
     * 15.12: re-assert Apps rear power + digitizer before start so cold tile
     * open is not blank panel (face→apps residual / sleep BL drop).
     *
     * @return true if launched without throw
     */
    static boolean startOnRear(Context c, Intent i) {
        if (c == null || i == null) return false;
        Context app = c.getApplicationContext() != null ? c.getApplicationContext() : c;
        ensureAppsRearPower(app);
        int id = 2;
        try {
            Display rear = SubDisplayHelper.findRear(app);
            if (rear != null) id = rear.getDisplayId();
        } catch (Exception ignored) {}
        Activity host = (c instanceof Activity) ? (Activity) c : null;
        // Same-task only when home is already on the rear display. Main-display
        // callers (SubDisplayActivity Open Settings) must NEW_TASK + launchDisplayId.
        boolean sameTask = false;
        if (host != null && !host.isFinishing()) {
            try {
                Display hd = host.getDisplay();
                int hostId = hd != null ? hd.getDisplayId() : Display.DEFAULT_DISPLAY;
                sameTask = (hostId == id);
            } catch (Exception e) {
                sameTask = false;
            }
        }
        // 15.10: singleTask/singleInstance (Clock/Calc) ignore same-task stack.
        boolean forcesOwnTask = launchForcesOwnTask(app, i);
        Intent launch = new Intent(i);
        if (sameTask && !forcesOwnTask) {
            // Stack on rear home — Back = home (intent=result). getLaunchIntent
            // and callers often set NEW_TASK; that forced blank rear after 15.7.
            // 15.10: also strip RESET/CLEAR_TASK (launcher residual).
            launch.setFlags(launch.getFlags()
                & ~Intent.FLAG_ACTIVITY_NEW_TASK
                & ~Intent.FLAG_ACTIVITY_NEW_DOCUMENT
                & ~Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                & ~Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                & ~Intent.FLAG_ACTIVITY_CLEAR_TASK);
        } else {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            sameTask = false; // own-task / main-display path
        }
        Bundle optsBundle = null;
        try {
            ActivityOptions opts = ActivityOptions.makeBasic();
            Method setDisp = ActivityOptions.class.getMethod("setLaunchDisplayId", int.class);
            setDisp.invoke(opts, id);
            optsBundle = opts.toBundle();
        } catch (Exception e) {
            Log.w(TAG, "setLaunchDisplayId: " + e.getMessage());
        }
        try {
            if (sameTask) {
                // 15.9: inherit display from host Activity already on rear —
                // do not force setLaunchDisplayId (sibling-task blank residual).
                try {
                    host.startActivity(launch);
                    noteLaunchOk(app, launch);
                    Log.i(TAG, "startOnRear inherit sameTask displayId=" + id
                        + " " + launch.getAction());
                    return true;
                } catch (Exception inheritEx) {
                    Log.w(TAG, "startOnRear inherit: " + inheritEx.getMessage());
                    host.startActivity(launch, optsBundle);
                    noteLaunchOk(app, launch);
                    Log.i(TAG, "startOnRear opts sameTask displayId=" + id
                        + " " + launch.getAction());
                    return true;
                }
            } else {
                app.startActivity(launch, optsBundle);
            }
            noteLaunchOk(app, launch);
            Log.i(TAG, "startOnRear displayId=" + id
                + " sameTask=" + sameTask
                + " forceOwn=" + forcesOwnTask
                + " " + launch.getAction());
            return true;
        } catch (Exception e) {
            Log.w(TAG, "startOnRear: " + e.getMessage());
            // Some system activities refuse same-task — one NEW_TASK retry on rear.
            if (host != null && !host.isFinishing()) {
                try {
                    Intent retry = new Intent(i);
                    retry.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ActivityOptions opts = ActivityOptions.makeBasic();
                    Method setDisp = ActivityOptions.class.getMethod(
                        "setLaunchDisplayId", int.class);
                    setDisp.invoke(opts, id);
                    host.startActivity(retry, opts.toBundle());
                    noteLaunchOk(app, retry);
                    Log.i(TAG, "startOnRear NEW_TASK retry displayId=" + id);
                    return true;
                } catch (Exception eRetry) {
                    Log.w(TAG, "startOnRear retry: " + eRetry.getMessage());
                }
            }
            try {
                Intent bare = new Intent(i);
                bare.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (optsBundle != null) {
                    app.startActivity(bare, optsBundle);
                } else {
                    app.startActivity(bare);
                }
                noteLaunchOk(app, bare);
                return true;
            } catch (Exception e2) {
                Log.w(TAG, "start fallback: " + e2.getMessage());
                return false;
            }
        }
    }

    /** 15.11: arm blank-watch grace after any successful rear start. */
    private static void noteLaunchOk(Context c, Intent launched) {
        try {
            SubDisplayService.noteAppsRearLaunch();
        } catch (Exception ignored) {}
        try {
            trackRearTilePackage(c, launched);
        } catch (Exception ignored) {}
    }

    /** Record non-Controls packages started on rear (leave Apps clear SoT). */
    private static void trackRearTilePackage(Context c, Intent launched) {
        if (launched == null) return;
        String pkg = null;
        ComponentName cn = launched.getComponent();
        if (cn != null) pkg = cn.getPackageName();
        if (pkg == null || pkg.isEmpty()) {
            try { pkg = launched.getPackage(); } catch (Exception ignored) {}
        }
        if (pkg == null || pkg.isEmpty()) return;
        if ("com.titanus2.controls".equals(pkg)) return;
        if ("android".equals(pkg) || "com.android.systemui".equals(pkg)) return;
        sRearTilePkgs.add(pkg);
        persistRearTilePackage(c, pkg);
        Log.i(TAG, "rear tile pkg track " + pkg + " n=" + sRearTilePkgs.size());
    }

    /** 15.15: persist tracked rear tile package (process-death residual). */
    private static void persistRearTilePackage(Context c, String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        try {
            Context app = c != null
                ? (c.getApplicationContext() != null ? c.getApplicationContext() : c)
                : (sInstance != null ? sInstance.getApplicationContext() : null);
            if (app == null) return;
            android.content.SharedPreferences sp =
                app.getSharedPreferences(PREF_REAR_TILE, Context.MODE_PRIVATE);
            Set<String> cur = new java.util.HashSet<>();
            Set<String> old = sp.getStringSet(PREF_KEY_PKGS, null);
            if (old != null) cur.addAll(old);
            cur.add(pkg);
            sp.edit().putStringSet(PREF_KEY_PKGS, cur).apply();
        } catch (Exception e) {
            Log.d(TAG, "persist rear tile: " + e.getMessage());
        }
    }

    /**
     * Snapshot + clear tracked rear tile packages (leave Apps).
     * 15.15: merge in-memory + SharedPreferences (process-death residual).
     */
    static Set<String> takeRearTilePackages(Context c) {
        Set<String> out = ConcurrentHashMap.newKeySet();
        out.addAll(sRearTilePkgs);
        sRearTilePkgs.clear();
        try {
            Context app = c != null
                ? (c.getApplicationContext() != null ? c.getApplicationContext() : c)
                : null;
            if (app != null) {
                android.content.SharedPreferences sp =
                    app.getSharedPreferences(PREF_REAR_TILE, Context.MODE_PRIVATE);
                Set<String> saved = sp.getStringSet(PREF_KEY_PKGS, null);
                if (saved != null) out.addAll(saved);
                sp.edit().remove(PREF_KEY_PKGS).apply();
            }
        } catch (Exception e) {
            Log.d(TAG, "take rear tile prefs: " + e.getMessage());
        }
        return out;
    }

    /** @deprecated use {@link #takeRearTilePackages(Context)} — no prefs merge. */
    static Set<String> takeRearTilePackages() {
        Set<String> out = ConcurrentHashMap.newKeySet();
        out.addAll(sRearTilePkgs);
        sRearTilePkgs.clear();
        return out;
    }

    /**
     * Known forceOwn tile packages — 15.15 allowlist for shell-discovered rear
     * packages only (not a blind FORCE_STOP list; 15.14 residual).
     */
    static String[] knownRearTilePackages() {
        return new String[] {
            "com.android.documentsui",
            "com.google.android.documentsui",
            "com.android.deskclock",
            "com.google.android.deskclock",
            "com.android.calculator2",
            "com.google.android.calculator",
            "org.lineageos.calculator",
            "org.lineageos.aperture",
            "com.android.camera2",
            "com.android.camera",
        };
    }

    static boolean isKnownRearTilePackage(String pkg) {
        if (pkg == null) return false;
        for (String k : knownRearTilePackages()) {
            if (k.equals(pkg)) return true;
        }
        return false;
    }

    /**
     * 15.17: singleTask (or singleInstance) forceOwn tiles share <b>one</b> task
     * with main — opening on rear <b>moves</b> that task. Leave must move-stack
     * back to display 0; FORCE_STOP / task remove murders the user's main app.
     */
    static boolean isSharedSingleTaskTilePackage(String pkg) {
        if (pkg == null) return false;
        return "com.android.deskclock".equals(pkg)
            || "com.google.android.deskclock".equals(pkg)
            || "com.android.calculator2".equals(pkg)
            || "com.google.android.calculator".equals(pkg)
            || "org.lineageos.calculator".equals(pkg)
            || "org.lineageos.aperture".equals(pkg)
            || "com.android.camera2".equals(pkg)
            || "com.android.camera".equals(pkg);
    }

    /**
     * 15.12: when mode is Apps, re-stamp plane power + digitizer before a rear
     * activity start (sleep / pad-agent lag left panel dark under live task).
     */
    private static void ensureAppsRearPower(Context app) {
        if (app == null) return;
        try {
            if (SubDisplayPrefs.getMode(app) != SubDisplayPrefs.Mode.APPS) return;
            SubDisplayPower.applyApps(app);
            SubDisplayService.applySubtouchPolicy(app);
        } catch (Exception e) {
            Log.d(TAG, "ensureAppsRearPower: " + e.getMessage());
        }
    }

    /** True when the resolved target uses singleTask/singleInstance launchMode. */
    private static boolean launchForcesOwnTask(Context app, Intent i) {
        try {
            PackageManager pm = app.getPackageManager();
            ResolveInfo ri = pm.resolveActivity(i, 0);
            if (ri == null || ri.activityInfo == null) return false;
            int lm = ri.activityInfo.launchMode;
            return lm == ActivityInfo.LAUNCH_SINGLE_TASK
                || lm == ActivityInfo.LAUNCH_SINGLE_INSTANCE;
        } catch (Exception e) {
            return false;
        }
    }

    private int dp(int v) {
        float d = getResources().getDisplayMetrics().density;
        return Math.round(v * d);
    }

    private static LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }

    /**
     * Launch this home on rear. Shared SoT for Apps mode, key L, side open-on-rear,
     * and service APPLY re-home (15.7 singleTask CLEAR_TOP — no blank stack).
     */
    public static void launch(Context c) {
        if (c == null) return;
        Context app = c.getApplicationContext() != null ? c.getApplicationContext() : c;
        Intent i = new Intent(app, SubDisplayLauncherActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
            | Intent.FLAG_ACTIVITY_CLEAR_TOP
            | Intent.FLAG_ACTIVITY_SINGLE_TOP
            | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startOnRear(app, i);
    }
}
