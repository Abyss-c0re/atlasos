package com.titanus2.controls;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.service.quicksettings.TileService;
import android.view.Surface;
import android.view.WindowManager;
import java.io.File;

/**
 * Shared pad mode: off | trackpad | mouse + follow-orientation.
 * Control plane is shared with Titan USB HID ({@code /data/misc/titan2}
 * + {@code /data/local/tmp}). Used by MainActivity and the Quick Settings tile.
 */
public final class PadModeController {
    public static final String OFF = "off";
    public static final String TRACKPAD = "trackpad";
    public static final String MOUSE = "mouse";
    /** Broadcast when mode changes (UI + tile listeners). */
    public static final String ACTION_MODE = "com.titanus2.controls.action.PAD_MODE";
    public static final String EXTRA_MODE = "mode";

    private PadModeController() {}

    public static String normalize(String mode) {
        if (mode == null) return OFF;
        mode = mode.trim().toLowerCase();
        if (TRACKPAD.equals(mode) || MOUSE.equals(mode) || OFF.equals(mode)) return mode;
        return OFF;
    }

    /**
     * Desired pad mode from the shared control plane.
     * Prefers OS plane + tmp (cross-app) over app-private copies so HID and
     * Controls always show the same mode. Does not use pad_status — that file
     * can lag or race while touchpadd is still starting/stopping.
     */
    public static String getMode(Context ctx) {
        String m = normalize(readSharedMode(ctx));
        return (m == null || m.isEmpty()) ? OFF : m;
    }

    /**
     * Keep HW pad inhibit + input surface coherent with pad mode.
     * <p>
     * 14.8: Off stamps inhibit=1 + surface=none (stale surface=hw residual).
     * 14.9: also restamp on <b>idempotent</b> setMode(off) — cool land / QS
     * re-tap when mode was already off used to skip plane and leave surface=hw
     * (palm still moved cursor until a full off→on→off cycle).
     * 15.0: rear never pointer — always subtouch_inhibit=1 and collapse
     * surface=sub|both → hw|none (stale rear dual-cursor residual).
     */
    public static void stampPadSurfacePlane(Context ctx, boolean modOn) {
        if (ctx == null) return;
        try {
            if (modOn) {
                AgentBridge.put(ctx, "titan2_hw_pad_inhibit", "0");
                AgentBridge.put(ctx, "titan2_input_surface", "hw");
                android.provider.Settings.Global.putString(
                    ctx.getContentResolver(), "titan2_hw_pad_inhibit", "0");
                android.provider.Settings.Global.putString(
                    ctx.getContentResolver(), "titan2_input_surface", "hw");
            } else {
                AgentBridge.put(ctx, "titan2_hw_pad_inhibit", "1");
                AgentBridge.put(ctx, "titan2_input_surface", "none");
                android.provider.Settings.Global.putString(
                    ctx.getContentResolver(), "titan2_hw_pad_inhibit", "1");
                android.provider.Settings.Global.putString(
                    ctx.getContentResolver(), "titan2_input_surface", "none");
            }
            // 15.0 product lock: rear sub_touch is display-only, never a second cursor.
            AgentBridge.put(ctx, AgentBridge.SUBTOUCH_INHIBIT, "1");
            android.provider.Settings.Global.putString(
                ctx.getContentResolver(), "titan2_subtouch_inhibit", "1");
        } catch (Exception ignored) {}
    }

    /** Apply mode; returns true if control write succeeded. Notifies tile + listeners. */
    public static boolean setMode(Context ctx, String mode) {
        mode = normalize(mode);
        boolean modOn = MOUSE.equals(mode) || TRACKPAD.equals(mode);
        // Idempotent: HID prepareDriverPad used to re-set mouse every push and
        // bump epoch → service regrab thrash → USB gadget flap (host reconnect).
        if (mode.equals(getMode(ctx))) {
            // Still re-mirror plane → Settings.Global (11.32+) when files already
            // match — unrooted pad-agent / bench need titan2_pad_mode in Global
            // without thrashing file mtimes (AgentBridge same-value skip).
            try {
                AgentBridge.put(ctx, AgentBridge.PAD_MODE, mode);
                AgentBridge.put(ctx, AgentBridge.LEGACY_PAD, MOUSE.equals(mode) ? "1" : "0");
            } catch (Exception ignored) {}
            // 14.9: restamp inhibit+surface even when mode already matches
            // (cool land / heal left surface=hw with mode=off residual).
            stampPadSurfacePlane(ctx, modOn);
            // Product: pad modes only — no text-caret daemon. Off kills touchpadd.
            try {
                AgentBridge.put(ctx, AgentBridge.PAD_TOP_ROW_CURSOR, "0");
                AgentBridge.put(ctx, AgentBridge.PAD_TOP_ROW_ONLY, "0");
            } catch (Exception ignored) {}
            if (modOn) {
                try { publishRotation(ctx); } catch (Exception ignored) {}
                ensureTouchpaddProcess(ctx);
            } else {
                stopTouchpaddProcess(ctx);
            }
            return true;
        }
        boolean ok = AgentBridge.put(ctx, AgentBridge.PAD_MODE, mode);
        AgentBridge.put(ctx, AgentBridge.LEGACY_PAD, MOUSE.equals(mode) ? "1" : "0");
        TrackpadPrefs prefs = new TrackpadPrefs(ctx);
        prefs.setMode(modOn ? TrackpadPrefs.MODE_GLOBAL : TrackpadPrefs.MODE_OFF);
        // Keep orientation stamp fresh so agent/hid_bridge see current rotation.
        stampPadSurfacePlane(ctx, modOn);
        // Always clear caret planes — product is Off | Trackpad | Mouse only.
        try {
            AgentBridge.put(ctx, AgentBridge.PAD_TOP_ROW_CURSOR, "0");
            AgentBridge.put(ctx, AgentBridge.PAD_TOP_ROW_ONLY, "0");
        } catch (Exception ignored) {}
        if (modOn) {
            publishRotation(ctx);
            // Trackpad = native ABS (no touchpadd). Mouse = titan2-touchpadd REL.
            if (MOUSE.equals(mode)) {
                ensureTouchpaddProcess(ctx);
            } else {
                // Trackpad: kill module so native pad is sole owner (no dual ABS/REL).
                stopTouchpaddProcess(ctx);
            }
        } else {
            stopTouchpaddProcess(ctx);
        }
        // Framework epoch + regrab signal so HID re-intercepts quickly
        try {
            Titan2CoreService.notifyPadModeChanged(ctx, mode);
        } catch (Exception ignored) {}
        notifyModeChanged(ctx, mode);
        // CubalC free-flow: wake pad-agent out of heat 2s park NOW (impulse).
        try {
            ImpulseSnap.wakePadAgent();
        } catch (Exception ignored) {}
        // Continuous rotation publisher for mouse/trackpad follow-orient
        PadOrientationService.sync(ctx);
        return ok;
    }

    /** @see #ensureTouchpaddProcess(Context) */
    public static void ensureTouchpaddProcess() {
        Context c = null;
        try { c = TrackpadAccessService.get(); } catch (Exception ignored) {}
        ensureTouchpaddProcess(c);
    }

    /**
     * Best-effort: start /system/bin/titan2-touchpadd if not running (B8).
     * <p>
     * 13.51: while exclusive HID is live, <b>do not</b> start/restart touchpadd
     * from Controls — HID owns the process; dual start fights hid_bridge.
     */
    public static void ensureTouchpaddProcess(Context ctx) {
        try {
            if (HostLayoutController.isHidExclusiveLiveFast(ctx)) return;
        } catch (Exception ignored) {}
        try {
            // Prefer Magisk module binary (B8 pack) over hybrid /system/bin;
            // 12.53: also /data/local/tmp staged binary (rootless land path).
            // 12.59: if multiple pids, kill extras (dual spawn heat / dual owner).
            Process p = Runtime.getRuntime().exec(new String[]{
                "sh", "-c",
                "TP=/system/bin/titan2-touchpadd; "
                    + "[ -x /data/adb/modules/titan2_touchpadd/system/bin/titan2-touchpadd ] && "
                    + "TP=/data/adb/modules/titan2_touchpadd/system/bin/titan2-touchpadd; "
                    + "[ -x /data/local/tmp/titan2-touchpadd ] && "
                    + "TP=/data/local/tmp/titan2-touchpadd; "
                    + "pids=$(pidof titan2-touchpadd 2>/dev/null); "
                    + "n=$(echo $pids | wc -w); "
                    + "if [ \"${n:-0}\" -gt 1 ] 2>/dev/null; then "
                    + "  keep=$(echo $pids | awk '{print $1}'); "
                    + "  for p in $pids; do [ \"$p\" = \"$keep\" ] || kill -9 $p 2>/dev/null; done; "
                    + "fi; "
                    + "CLICK=$(cat /data/misc/titan2/titan2_pad_click 2>/dev/null || cat /data/local/tmp/titan2_pad_click 2>/dev/null); "
                    + "CLICK=${CLICK:-1}; case \"$CLICK\" in 0|false|off) CLICK=0;; *) CLICK=1;; esac; "
                    // 15.27: product mouse only — never wait on start; caret always off.
                    + "if ! pidof titan2-touchpadd >/dev/null 2>&1; then "
                    + "  if [ -x \"$TP\" ]; then "
                    + "    LOGCAT_OUTPUT=true KEYBOARD_FEATURES=false "
                    + "    TAP_TO_CLICK=\"$CLICK\" TEXT_CARET_NAV=0 TOP_ROW_CURSOR=0 TOP_ROW_ONLY=0 "
                    + "    PAD_SURFACE=hw "
                    + "    \"$TP\" >>/data/local/tmp/titan2_touchpadd.log 2>&1 & "
                    + "  fi; "
                    + "  start titan2-touchpadd 2>/dev/null; "
                    + "fi; "
                    + "echo ok"
            });
            p.waitFor();
        } catch (Exception ignored) {}
    }

    /** @see #stopTouchpaddProcess(Context) */
    public static void stopTouchpaddProcess() {
        Context c = null;
        try { c = TrackpadAccessService.get(); } catch (Exception ignored) {}
        stopTouchpaddProcess(c);
    }

    /**
     * B8 11.91: stop orphan titan2-touchpadd when pad mode is off.
     * 13.51: never kill while exclusive HID is live — HID owns the pad process.
     */
    public static void stopTouchpaddProcess(Context ctx) {
        try {
            if (HostLayoutController.isHidExclusiveLiveFast(ctx)) return;
        } catch (Exception ignored) {}
        try {
            // B8 11.92: su only on ALLOW_ROOT lab builds. Release must never
            // invoke Magisk (prompt/trace) even if the user installed Magisk later.
            // 12.71: kill -9 residual pids after killall (heat residual — orphan
            // touchpadd kept TitanKey/CPU when pad plane said off).
            // 13.82: multi-pass process kill (touchpadd Magisk 1.11 SoT). Single
            // killall left dual Magisk wave trees re-spawning dual TitanKey.
            // 13.84: never ps -A under heat (1.65 residual; touchpadd Magisk 1.12 SoT).
            // Peer service kill = /proc cmdline only (full ps -A freezes load1≥14).
            // ALLOW_ROOT also prunes Magisk touchpadd service roots + ppid=1 orphans.
            // 15.27: instant kill — no multi-pass sleep loops (Off must be <100ms).
            // 15.34: NEVER tr '\\0' on cmdline (toybox tr @100% residual — pad-agent
            // 2.127 / 2.108 SoT). Match service path via grep -a -F only.
            // 15.35: NEVER full /proc/[0-9]* walk (pad-agent 2.119 pgrep SoT;
            // ~3k tasks under sticky load≈13 → pad-off Magisk prune reheats).
            // Peer + orphan = pgrep -f candidates then confirm cmdline/ppid=1.
            final String tpStop =
                "stop titan2-touchpadd 2>/dev/null; "
                    + "killall -9 titan2-touchpadd 2>/dev/null; "
                    + "for p in $(pidof titan2-touchpadd 2>/dev/null); do kill -9 $p 2>/dev/null; done";
            final String tpSvc =
                "n=0; for p in $(pgrep -f titan2_touchpadd/service 2>/dev/null) "
                    + "$(pgrep -f titan2-touchpadd/service 2>/dev/null); do "
                    + "case \"$p\" in ''|*[!0-9]*) continue ;; esac; "
                    + "grep -a -F -q titan2_touchpadd/service /proc/$p/cmdline 2>/dev/null "
                    + "|| grep -a -F -q titan2-touchpadd/service /proc/$p/cmdline 2>/dev/null "
                    + "|| continue; "
                    + "kill -9 $p 2>/dev/null || true; n=$((n+1)); "
                    + "[ \"$n\" -ge 32 ] 2>/dev/null && break; done; "
                    + "n=0; for p in $(pgrep -f titan2_touchpadd/service 2>/dev/null) "
                    + "$(pgrep -f titan2-touchpadd/service 2>/dev/null); do "
                    + "case \"$p\" in ''|*[!0-9]*) continue ;; esac; "
                    + "comm=`cat /proc/$p/comm 2>/dev/null` || continue; "
                    + "[ \"$comm\" = \"sh\" ] || continue; "
                    + "st=`cat /proc/$p/stat 2>/dev/null` || continue; "
                    + "rest=${st##*) }; set -- $rest; [ \"$2\" = \"1\" ] || continue; "
                    + "grep -a -F -q titan2_touchpadd/service /proc/$p/cmdline 2>/dev/null "
                    + "|| grep -a -F -q titan2-touchpadd/service /proc/$p/cmdline 2>/dev/null "
                    + "|| continue; "
                    + "kill -9 $p 2>/dev/null || true; n=$((n+1)); "
                    + "[ \"$n\" -ge 32 ] 2>/dev/null && break; done";
            String suBlock = BuildConfig.ALLOW_ROOT
                ? "if command -v su >/dev/null 2>&1; then "
                    + "  su -c '" + tpSvc + "; " + tpStop + "; true' 2>/dev/null; "
                    + "fi; "
                : "";
            Process p = Runtime.getRuntime().exec(new String[]{
                "sh", "-c",
                suBlock
                    + tpStop + "; "
                    + "pidof titan2-touchpadd >/dev/null 2>&1 && echo alive || echo dead"
            });
            p.waitFor();
        } catch (Exception ignored) {}
    }

    /** Push mode to QS tile + registered listeners (HID / hub UI). */
    public static void notifyModeChanged(Context ctx, String mode) {
        mode = normalize(mode);
        try {
            Intent out = new Intent(ACTION_MODE);
            out.putExtra(EXTRA_MODE, mode);
            out.setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
            ctx.sendBroadcast(out);
        } catch (Exception ignored) {}
        try {
            TileService.requestListeningState(ctx,
                new ComponentName(ctx, PadModeTileService.class));
        } catch (Exception ignored) {}
    }

    /**
     * Newest non-empty pad mode across shared + app paths.
     * Shared paths (OS_CTRL, tmp) win on equal mtime so cross-app writers are visible.
     */
    private static String readSharedMode(Context ctx) {
        long bestMt = -1;
        String best = null;
        // Shared first (higher priority on equal mtime via >= after private would lose).
        // 13.41: tmp first; skip OS plane during SELinux backoff.
        java.util.ArrayList<File> shared = new java.util.ArrayList<>(2);
        shared.add(new File("/data/local/tmp", AgentBridge.PAD_MODE));
        if (AgentBridge.osCtrlAllowed()) {
            shared.add(new File(AgentBridge.OS_CTRL, AgentBridge.PAD_MODE));
        }
        for (File f : shared) {
            String v = readModeFile(f);
            if (v == null) {
                if (f != null && f.getPath() != null
                        && f.getPath().startsWith(AgentBridge.OS_CTRL)) {
                    AgentBridge.noteOsCtrlDenied();
                }
                continue;
            }
            long mt = f.lastModified();
            if (mt >= bestMt) {
                bestMt = mt;
                best = v;
            }
        }
        // App-private only if strictly newer than shared
        for (File f : AgentBridge.targets(ctx, AgentBridge.PAD_MODE)) {
            if (f == null) continue;
            String path = f.getAbsolutePath();
            if (path.startsWith(AgentBridge.OS_CTRL)
                    || path.startsWith("/data/local/tmp")) {
                continue; // already counted
            }
            String v = readModeFile(f);
            if (v == null) continue;
            long mt = f.lastModified();
            if (mt > bestMt) {
                bestMt = mt;
                best = v;
            }
        }
        if (best != null) return best;
        return AgentBridge.get(ctx, AgentBridge.PAD_MODE, OFF);
    }

    private static String readModeFile(File f) {
        if (f == null || !f.isFile()) return null;
        String v = AgentBridge.readLine(f.getPath());
        if (v == null) return null;
        v = v.trim();
        if (v.isEmpty()) return null;
        return normalize(v);
    }



    public static boolean isFollowOrient(Context ctx) {
        return "1".equals(AgentBridge.get(ctx, AgentBridge.PAD_FOLLOW_ORIENT, "0"));
    }

    /**
     * Top key-row strip → Android <b>text caret</b> (KEY_LEFT/RIGHT for the
     * blinking insert point while typing). Not the mouse pointer.
     * Product default on. Works with pad Off / Mouse / Trackpad (agent path).
     */
    public static boolean isTopRowCursor(Context ctx) {
        return !"0".equals(AgentBridge.get(ctx, AgentBridge.PAD_TOP_ROW_CURSOR, "1"));
    }

    /**
     * Publish text-caret plane and restart touchpadd when env must change.
     * Exclusive HID owns the process — plane only; HID re-reads on ensure.
     */
    public static boolean setTopRowCursor(Context ctx, boolean on) {
        String want = on ? "1" : "0";
        String prev = AgentBridge.get(ctx, AgentBridge.PAD_TOP_ROW_CURSOR, "1");
        if (prev == null || prev.isEmpty()) prev = "1";
        boolean ok = AgentBridge.put(ctx, AgentBridge.PAD_TOP_ROW_CURSOR, want);
        if (!want.equals(prev.trim())) {
            String mode = getMode(ctx);
            if (!on) {
                try { AgentBridge.put(ctx, AgentBridge.PAD_TOP_ROW_ONLY, "0"); } catch (Exception ignored) {}
            } else if (OFF.equals(mode)) {
                // Pad off + caret on → exclusive strip daemon
                try { AgentBridge.put(ctx, AgentBridge.PAD_TOP_ROW_ONLY, "1"); } catch (Exception ignored) {}
            } else {
                try { AgentBridge.put(ctx, AgentBridge.PAD_TOP_ROW_ONLY, "0"); } catch (Exception ignored) {}
            }
            restartTouchpaddForEnv(ctx);
        }
        return ok;
    }

    /**
     * Standalone top-row only (no lower pad). Forced off while mouse/trackpad on
     * so we never dual-own the capacitive surface.
     */
    public static boolean isTopRowOnly(Context ctx) {
        String mode = getMode(ctx);
        if (MOUSE.equals(mode) || TRACKPAD.equals(mode)) return false;
        return "1".equals(AgentBridge.get(ctx, AgentBridge.PAD_TOP_ROW_ONLY, "0"));
    }

    public static boolean setTopRowOnly(Context ctx, boolean on) {
        String mode = getMode(ctx);
        if (on && (MOUSE.equals(mode) || TRACKPAD.equals(mode))) {
            // Edge: cannot exclusive-grab strip while mouse/trackpad owns pad
            on = false;
        }
        if (on) {
            // Standalone strip requires top-row cursor semantics
            try { AgentBridge.put(ctx, AgentBridge.PAD_TOP_ROW_CURSOR, "1"); } catch (Exception ignored) {}
        }
        String want = on ? "1" : "0";
        String prev = AgentBridge.get(ctx, AgentBridge.PAD_TOP_ROW_ONLY, "0");
        if (prev == null || prev.isEmpty()) prev = "0";
        boolean ok = AgentBridge.put(ctx, AgentBridge.PAD_TOP_ROW_ONLY, want);
        if (!want.equals(prev.trim())) {
            restartTouchpaddForEnv(ctx);
        }
        return ok;
    }

    /** Tap-to-click plane (mouse mode / HID temporary pad). Restarts daemon on change. */
    public static boolean setTapToClick(Context ctx, boolean on) {
        String want = on ? "1" : "0";
        String prev = AgentBridge.get(ctx, AgentBridge.PAD_CLICK, "1");
        if (prev == null || prev.isEmpty()) prev = "1";
        boolean ok = AgentBridge.put(ctx, AgentBridge.PAD_CLICK, want);
        if (!want.equals(prev.trim())) {
            restartTouchpaddForEnv(ctx);
        }
        return ok;
    }

    public static boolean isTapToClick(Context ctx) {
        return !"0".equals(AgentBridge.get(ctx, AgentBridge.PAD_CLICK, "1"));
    }

    /**
     * Env-driven touchpadd knobs (click / top-row) need process restart.
     * Exclusive HID owns the daemon — leave it; plane is enough for next HID ensure.
     */
    public static void restartTouchpaddForEnv(Context ctx) {
        try {
            if (HostLayoutController.isHidExclusiveLiveFast(ctx)) return;
        } catch (Exception ignored) {}
        String mode = getMode(ctx);
        // Mouse always wants daemon; Off/Trackpad want it when text-caret nav is on
        boolean wantDaemon = MOUSE.equals(mode)
            || isTopRowCursor(ctx)
            || isTopRowOnly(ctx);
        if (!wantDaemon) {
            if (OFF.equals(mode)) stopTouchpaddProcess(ctx);
            return;
        }
        stopTouchpaddProcess(ctx);
        // Brief yield so kill lands before spawn (rootless shell)
        try { Thread.sleep(40); } catch (InterruptedException ignored) {}
        ensureTouchpaddProcess(ctx);
    }

    /** Shared setting: pad axes follow display rotation (Controls + HID). */
    public static boolean setFollowOrient(Context ctx, boolean on) {
        TrackpadPrefs prefs = new TrackpadPrefs(ctx);
        String want = on ? "1" : "0";
        String prev = AgentBridge.get(ctx, AgentBridge.PAD_FOLLOW_ORIENT, "0");
        prefs.setFollowOrient(on);
        // AgentBridge.put skips mtime bump when value unchanged (pad thrash fix).
        boolean ok = AgentBridge.put(ctx, AgentBridge.PAD_FOLLOW_ORIENT, want);
        if (on) publishRotation(ctx);
        // Only re-stamp pad mode when follow actually changed — otherwise every
        // UI refresh killed/restarted touchpadd and recentered the cursor.
        if (!want.equals(prev == null ? "0" : prev.trim())) {
            String mode = getMode(ctx);
            if (TRACKPAD.equals(mode) || MOUSE.equals(mode)) {
                AgentBridge.put(ctx, AgentBridge.PAD_MODE, mode);
            }
        }
        PadOrientationService.sync(ctx);
        return ok;
    }

    /** Write Surface rotation 0..3 for agent / hid_bridge / orient-rel. */
    public static void publishRotation(Context ctx) {
        int r = 0;
        try {
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null && wm.getDefaultDisplay() != null) {
                r = wm.getDefaultDisplay().getRotation();
            }
        } catch (Exception ignored) {}
        if (r < Surface.ROTATION_0 || r > Surface.ROTATION_270) r = 0;
        AgentBridge.put(ctx, AgentBridge.PAD_ROTATION, String.valueOf(r));
        // Always mirror tmp — orient-rel may only see shell-writable plane
        try {
            java.io.File tmp = new java.io.File("/data/local/tmp/titan2_pad_rotation");
            byte[] data = String.valueOf(r).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            java.io.FileOutputStream fos = new java.io.FileOutputStream(tmp);
            fos.write(data);
            fos.close();
            //noinspection ResultOfMethodCallIgnored
            tmp.setReadable(true, false);
            //noinspection ResultOfMethodCallIgnored
            tmp.setWritable(true, false);
        } catch (Exception ignored) {}
    }

    /** Cycle off → trackpad → mouse → off. Returns new mode. */
    public static String cycle(Context ctx) {
        String cur = getMode(ctx);
        String next;
        if (OFF.equals(cur)) next = TRACKPAD;
        else if (TRACKPAD.equals(cur)) next = MOUSE;
        else next = OFF;
        setMode(ctx, next);
        return next;
    }

    public static String shortLabel(String mode) {
        if (TRACKPAD.equals(mode)) return "Trackpad";
        if (MOUSE.equals(mode)) return "Mouse";
        return "Off";
    }

    public static String longLabel(String mode) {
        if (TRACKPAD.equals(mode)) return "Pad · Trackpad";
        if (MOUSE.equals(mode)) return "Pad · Mouse";
        return "Pad · Off";
    }

    public static String description(String mode) {
        if (TRACKPAD.equals(mode)) return "trackpad";
        if (MOUSE.equals(mode)) return "mouse";
        return "off";
    }
}
