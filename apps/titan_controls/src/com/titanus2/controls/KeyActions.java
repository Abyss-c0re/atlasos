package com.titanus2.controls;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.input.InputManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.telecom.TelecomManager;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.widget.Toast;
import com.titanus2.controls.subdisplay.SubDisplayPrefs;
import com.titanus2.controls.subdisplay.SubDisplayService;
import java.lang.reflect.Method;

/**
 * Executes mapped actions for programmable keys.
 * <p>
 * Prefer <b>privileged</b> paths (priv-app {@code INJECT_EVENTS}, StatusBarManager)
 * so screen-off remaps from pad-agent / {@link KeyFireReceiver} work without
 * Accessibility. A11y {@code performGlobalAction} is used only as a fallback.
 */
public final class KeyActions {
    private static boolean torchOn = false;

    private KeyActions() {}

    public static void run(Context ctx, String action) {
        if (action == null || KeyMapPrefs.ACT_DEFAULT.equals(action)) return;
        if (KeyMapPrefs.ACT_NONE.equals(action)) return;
        AccessibilityService svc = (ctx instanceof AccessibilityService)
            ? (AccessibilityService) ctx : TrackpadAccessService.get();
        Context app = ctx.getApplicationContext();
        if (app != null) ctx = app;

        try {
            if (action.startsWith(KeyMapPrefs.ACT_APP_PREFIX)) {
                openAppOrActivity(ctx, action.substring(KeyMapPrefs.ACT_APP_PREFIX.length()));
                return;
            }
            if (KeyMapPrefs.isLayoutAction(action)) {
                HostLayoutController.applyAction(ctx, action);
                return;
            }
            if (KeyMapPrefs.isComputerAction(action)) {
                computerInput(ctx, action);
                return;
            }
            if (action.startsWith(KeyMapPrefs.ACT_KEYCODE_PREFIX)) {
                int code = Integer.parseInt(action.substring(KeyMapPrefs.ACT_KEYCODE_PREFIX.length()).trim());
                // Sideload lacks INJECT_EVENTS → emitLayoutKeyCode falls back to pad-agent
                emitLayoutKeyCode(ctx, code);
                return;
            }
            if (action.startsWith(KeyMapPrefs.ACT_SCAN_PREFIX)) {
                // best-effort: map known scans to keycodes
                int scan = Integer.parseInt(action.substring(KeyMapPrefs.ACT_SCAN_PREFIX.length()).trim());
                injectKeyCode(ctx, scanToKeyCode(scan));
                return;
            }
            switch (action) {
                case KeyMapPrefs.ACT_HOME:
                    // Prefer a11y global Home. `input keyevent 3` / inject HOME
                    // is a no-op on this GSI (shade stays, launcher never homes).
                    if (svc != null) {
                        global(ctx, svc, AccessibilityService.GLOBAL_ACTION_HOME);
                    } else if (!injectKeyCode(ctx, KeyEvent.KEYCODE_HOME)) {
                        statusBar(ctx, "collapsePanels");
                    }
                    break;
                case KeyMapPrefs.ACT_BACK:
                    if (!injectKeyCode(ctx, KeyEvent.KEYCODE_BACK)) {
                        global(ctx, svc, AccessibilityService.GLOBAL_ACTION_BACK);
                    }
                    break;
                case KeyMapPrefs.ACT_RECENTS:
                    // Never inject KEYCODE_APP_SWITCH: Quickstep quick-switch.
                    // Prefer a11y GLOBAL_ACTION_RECENTS. Do not also call
                    // toggleRecentApps (dual fire opens then closes overview).
                    if (svc != null) {
                        global(ctx, svc, AccessibilityService.GLOBAL_ACTION_RECENTS);
                    } else {
                        statusBar(ctx, "toggleRecentApps");
                    }
                    break;
                case KeyMapPrefs.ACT_NOTIFICATIONS:
                    if (!statusBar(ctx, "expandNotificationsPanel")) {
                        global(ctx, svc, AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS);
                    }
                    break;
                case KeyMapPrefs.ACT_QUICK_SETTINGS:
                    if (!statusBar(ctx, "expandSettingsPanel")) {
                        global(ctx, svc, AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS);
                    }
                    break;
                case KeyMapPrefs.ACT_POWER_DIALOG:
                    if (!statusBar(ctx, "expandNotificationsPanel")) {
                        // fall through to a11y power dialog if available
                    }
                    global(ctx, svc, AccessibilityService.GLOBAL_ACTION_POWER_DIALOG);
                    if (svc == null) {
                        injectKeyCode(ctx, KeyEvent.KEYCODE_POWER);
                    }
                    break;
                case KeyMapPrefs.ACT_ASSIST:
                case KeyMapPrefs.ACT_VOICE:
                    Intent assist = new Intent(Intent.ACTION_VOICE_COMMAND);
                    assist.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        ctx.startActivity(assist);
                    } catch (Exception e) {
                        Intent a2 = new Intent(Intent.ACTION_ASSIST);
                        a2.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        try { ctx.startActivity(a2); } catch (Exception ignored) {
                            if (isInteractive(ctx)) toast(ctx, "No assistant");
                        }
                    }
                    break;
                case KeyMapPrefs.ACT_FLASHLIGHT:
                    toggleTorch(ctx);
                    break;
                case KeyMapPrefs.ACT_CAMERA:
                    Intent cam = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA);
                    cam.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        ctx.startActivity(cam);
                    } catch (Exception e) {
                        if (isInteractive(ctx)) toast(ctx, "No camera");
                    }
                    break;
                case KeyMapPrefs.ACT_SCREENSHOT:
                    if (Build.VERSION.SDK_INT >= 28 && svc != null) {
                        svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT);
                    } else if (!injectKeyCode(ctx, KeyEvent.KEYCODE_SYSRQ)
                            && !statusBar(ctx, "expandNotificationsPanel")) {
                        if (isInteractive(ctx)) toast(ctx, "Screenshot unavailable");
                    }
                    break;
                case KeyMapPrefs.ACT_SUBDISPLAY:
                    SubDisplayService.toggle(ctx);
                    break;
                case KeyMapPrefs.ACT_SUB_APPS:
                case "sub_apps":
                case "rear_apps":
                case "open_rear":
                    openOnRearDisplay(ctx);
                    break;
                case KeyMapPrefs.ACT_ANSWER_CALL:
                case "answer":
                case "accept_call":
                    telecom(ctx, true);
                    break;
                case KeyMapPrefs.ACT_END_CALL:
                case "hangup":
                case "hang_up":
                case "reject_call":
                    telecom(ctx, false);
                    break;
                case KeyMapPrefs.ACT_MUTE:
                case "mute":
                case "mic_mute":
                    toggleMute(ctx);
                    break;
                default:
                    // Lab only — product path stays silent on bad action strings.
                    if (isInteractive(ctx) && DebugPrefs.unknownActionToasts(ctx)) {
                        toast(ctx, "Unknown: " + action);
                    }
            }
        } catch (Exception e) {
            if (isInteractive(ctx) && DebugPrefs.unknownActionToasts(ctx)) {
                toast(ctx, "Action failed: " + e.getMessage());
            }
        }
    }

    private static void global(Context ctx, AccessibilityService svc, int action) {
        if (svc != null) {
            svc.performGlobalAction(action);
            return;
        }
        // No a11y: map common globals to inject / StatusBar (already tried by callers)
    }

    /**
     * OEM-ish “open something on rear”: apps mode (digitizer + FGS) + rear home on display 2.
     * 15.5: shared SoT via {@link SubDisplayService#applyMode} + launch path
     * (applyApps-only left prefs/mode face and blank rear after 15.4).
     * 15.6: {@link SubDisplayService#launchRearHome} secondary launcher (not Settings-only).
     */
    private static void openOnRearDisplay(Context ctx) {
        try {
            SubDisplayService.applyMode(ctx, SubDisplayPrefs.Mode.APPS);
        } catch (Exception ignored) {}
        try {
            SubDisplayService.launchRearHome(ctx);
        } catch (Exception e) {
            if (isInteractive(ctx)) toast(ctx, "Rear launch failed");
        }
    }

    /** Answer (true) or end (false) via TelecomManager when permitted. */
    private static void telecom(Context ctx, boolean answer) {
        try {
            TelecomManager tm = (TelecomManager) ctx.getSystemService(Context.TELECOM_SERVICE);
            if (tm == null) {
                if (isInteractive(ctx)) toast(ctx, "No telephony");
                return;
            }
            if (answer) {
                if (Build.VERSION.SDK_INT >= 26) {
                    tm.acceptRingingCall();
                } else {
                    injectKeyCode(ctx, KeyEvent.KEYCODE_CALL);
                }
            } else {
                if (Build.VERSION.SDK_INT >= 28) {
                    tm.endCall();
                } else {
                    injectKeyCode(ctx, KeyEvent.KEYCODE_ENDCALL);
                }
            }
        } catch (SecurityException se) {
            // Fallback key inject — works when InCallUI is focused
            injectKeyCode(ctx, answer ? KeyEvent.KEYCODE_CALL : KeyEvent.KEYCODE_ENDCALL);
        } catch (Exception e) {
            injectKeyCode(ctx, answer ? KeyEvent.KEYCODE_CALL : KeyEvent.KEYCODE_ENDCALL);
        }
    }

    /**
     * OEM space-bar mute parity: during a call toggle mic mute; otherwise
     * ringer silent ↔ normal (never leave vibrate as the only path).
     */
    private static void toggleMute(Context ctx) {
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            int mode = am.getMode();
            if (mode == AudioManager.MODE_IN_CALL
                    || mode == AudioManager.MODE_IN_COMMUNICATION) {
                am.setMicrophoneMute(!am.isMicrophoneMute());
                return;
            }
            int ringer = am.getRingerMode();
            if (ringer == AudioManager.RINGER_MODE_SILENT
                    || ringer == AudioManager.RINGER_MODE_VIBRATE) {
                am.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
            } else {
                am.setRingerMode(AudioManager.RINGER_MODE_SILENT);
            }
        } catch (Exception e) {
            if (isInteractive(ctx)) toast(ctx, "Mute unavailable");
        }
    }

    /** StatusBarManager privileged APIs (expand panels, recents). */
    private static boolean statusBar(Context ctx, String method) {
        try {
            Object sb = ctx.getSystemService("statusbar");
            if (sb == null) return false;
            Method m = sb.getClass().getMethod(method);
            m.invoke(sb);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Computer input for remote desktop / USB keyboard apps.
     * <ol>
     *   <li>Local inject (Moonlight and similar see phone keys/clicks)</li>
     *   <li>When HID session is live: host-only path (queue / REMOTE_INPUT)</li>
     *   <li>Control-plane stamp for pollers</li>
     * </ol>
     * <p>
     * <b>12.26 unity:</b> when HID session is live, use {@link #hostRemoteOnly}
     * only (no local inject + broadcast dual).
     * <p>
     * <b>13.34 B2 residual:</b> when session is off, phone inject only — never
     * {@code REMOTE_INPUT}. Bench: session-off broadcast woke
     * {@code HostMouseReceiver}, stamped {@code titan2_last_host_hid}, and could
     * seed remote_q for the next exclusive Start (dual host+phone).
     */
    private static void computerInput(Context ctx, String action) {
        if (action == null) return;
        // Exclusive / live HID owns host keyboard — never inject+broadcast both.
        if (HostLayoutController.isHidSessionLive(ctx) && KeyMapPrefs.isHostAction(action)) {
            hostRemoteOnly(ctx, action);
            return;
        }
        if (KeyMapPrefs.isMouseAction(action)) {
            int wheel = mouseWheelFromAction(action);
            if (wheel != 0) {
                if (HostLayoutController.isHidSessionLive(ctx)) {
                    broadcastRemote(ctx, action,
                        com.titanus2.api.Titan2ApiContract.KIND_MOUSE,
                        0, false, 0, 0, 0, wheel);
                    stampRemote(ctx, action);
                    return;
                }
                // Session off: phone page scroll (no host REMOTE_INPUT).
                injectPhoneScroll(ctx, wheel);
                stampRemote(ctx, action);
                return;
            }
            int buttons = mouseButtonsFromAction(action);
            if (HostLayoutController.isHidSessionLive(ctx)) {
                // HID owns mouse — remote only (no local click + PC click).
                broadcastRemote(ctx, action,
                    com.titanus2.api.Titan2ApiContract.KIND_MOUSE,
                    buttons, true, 0, 0, 0, 0);
                stampRemote(ctx, action);
                return;
            }
            // Session off: phone click only (no REMOTE_INPUT cold-start).
            injectMouseTap(ctx, buttons);
            stampRemote(ctx, action);
            return;
        }
        if (KeyMapPrefs.isHostAction(action)) {
            String spec = action.substring(KeyMapPrefs.ACT_HOST_PREFIX.length()).trim();
            HostChord chord = parseHostChord(spec);
            if (chord != null) {
                if (chord.keyCode > 0 && chord.keyCode != KeyEvent.KEYCODE_UNKNOWN) {
                    // INJECT_EVENTS often denied for user installs → pad-agent keyevent
                    // 12.68: clear agent queue when inject lands (same as hostLocalOnly)
                    if (injectKeyChord(ctx, chord.meta, chord.keyCode)) {
                        clearAgentKeyQueue(ctx);
                    } else {
                        queueAgentKeyChord(ctx, chord.meta, chord.keyCode);
                    }
                }
                // Session off (live branch returned above): phone only.
                stampRemote(ctx, action);
            }
        }
    }

    /**
     * Mask physical HW-keyboard modifiers that should merge into layout emits
     * (Shift/Ctrl/Alt/Meta/Sym). Layout chords OR this with their own meta so
     * Ctrl+Shift+arrow / Ctrl+! match non-layout typing.
     */
    public static int usefulMeta(int meta) {
        if (meta == 0) return 0;
        return meta & (
            KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON | KeyEvent.META_CTRL_RIGHT_ON
                | KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON
                | KeyEvent.META_SHIFT_RIGHT_ON
                | KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON | KeyEvent.META_ALT_RIGHT_ON
                | KeyEvent.META_META_ON | KeyEvent.META_META_LEFT_ON | KeyEvent.META_META_RIGHT_ON
                | KeyEvent.META_SYM_ON
        );
    }

    /** HID keyboard mod byte from Android meta (bit0 Ctrl, bit1 Shift, bit2 Alt, bit3 Meta). */
    public static int metaToHidMod(int meta) {
        int hidMod = 0;
        if ((meta & KeyEvent.META_CTRL_ON) != 0) hidMod |= 0x01;
        if ((meta & KeyEvent.META_SHIFT_ON) != 0) hidMod |= 0x02;
        if ((meta & KeyEvent.META_ALT_ON) != 0) hidMod |= 0x04;
        if ((meta & KeyEvent.META_META_ON) != 0) hidMod |= 0x08;
        return hidMod;
    }

    /**
     * Forward a host: chord to HID/PC only — no local key inject.
     * Used by host-layout specials when an HID session owns the remote keyboard.
     * <p>
     * Single path: queue file (preferred when HID usage known) <b>or</b>
     * one broadcast to HostMouseReceiver — never both, never multi-send.
     */
    public static void hostRemoteOnly(Context ctx, String action) {
        hostRemoteOnly(ctx, action, 0);
    }

    /**
     * @param extraMeta physical modifier state from the HW key event (merged into chord)
     */
    public static void hostRemoteOnly(Context ctx, String action, int extraMeta) {
        if (action == null || !KeyMapPrefs.isHostAction(action)) return;
        String spec = action.substring(KeyMapPrefs.ACT_HOST_PREFIX.length()).trim();
        HostChord chord = parseHostChord(spec);
        if (chord == null) return;
        int meta = chord.meta | usefulMeta(extraMeta);
        // 12.24: ONE host path only.
        // Old: broadcast (keyTap+multi-drain) AND queueHidKey AND pokeHidDrain
        // → N glyphs per press. Prefer queue+single drain when HID usage known;
        // else broadcast alone (HostMouseReceiver keyTap once).
        stampRemote(ctx, action);
        try {
            HostLayoutController.ensureKeysPausedForExclusiveSpecials(ctx);
        } catch (Exception ignored) {}
        try {
            android.provider.Settings.Global.putString(ctx.getContentResolver(),
                "titan2_last_host_special", action);
        } catch (Exception ignored) {}
        // Never leave agent keyevent queue live while exclusive host owns glyphs
        try { clearAgentKeyQueue(ctx); } catch (Exception ignored) {}
        if (chord.hidUsage > 0) {
            queueHidKey(metaToHidMod(meta), chord.hidUsage);
            pokeHidDrain(ctx);
            // 13.52: one delayed re-poke — exclusive FGS may arm after first DRAIN
            // (first Sym glyph lost / host silent). Never triple (multi-glyph).
            try {
                final Context app = ctx.getApplicationContext();
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                    () -> {
                        try { pokeHidDrain(app); } catch (Exception ignored) {}
                    }, 24L);
            } catch (Exception ignored) {}
        } else {
            broadcastRemote(ctx, action,
                com.titanus2.api.Titan2ApiContract.KIND_KEY,
                0, false, meta, chord.keyCode, chord.hidUsage);
        }
    }

    /**
     * B2 11.52 / 12.12: seed world-writable Specials queues from Controls before
     * first glyph — HID FGS may not have run ensureSpecialsQueues yet (layout
     * hold mid-session / post-wipe rootless). Best-effort; SELinux may deny.
     * Public for {@link RootlessPlane} boot/a11y seed path.
     */
    public static void ensureSpecialsQueuesLocal() {
        String[] paths = new String[]{
            "/data/local/tmp/titan2_remote_hid.q",
            "/data/local/tmp/titan2_hid_hw.out",
            "/data/local/tmp/titan2_hid_remote_q",
            "/data/local/tmp/titan2_hid.inj",
            "/data/misc/titan2/titan2_remote_hid.q",
            "/data/misc/titan2/titan2_hid_hw.out",
            "/data/misc/titan2/titan2_hid_remote_q",
            "/data/misc/titan2/titan2_hid.inj",
        };
        for (String path : paths) {
            try {
                java.io.File f = new java.io.File(path);
                java.io.File parent = f.getParentFile();
                if (parent != null && !parent.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    parent.mkdirs();
                }
                if (!f.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    f.createNewFile();
                }
                //noinspection ResultOfMethodCallIgnored
                f.setReadable(true, false);
                //noinspection ResultOfMethodCallIgnored
                f.setWritable(true, false);
                try {
                    android.system.Os.chmod(f.getAbsolutePath(), 0666);
                } catch (Throwable ignored) {}
            } catch (Exception ignored) {}
        }
    }

    /**
     * Best-effort: tell Titan USB HID FGS to drain remote_q / hw.out now.
     * Package-visible so {@link HostLayoutController} can arm keys_pause without
     * waiting for the 4–12 ms exclusive drain loop (B2 Specials first glyph).
     * <p>
     * 13.31: never cold-start HID when session is off and queues are empty.
     * Lab bench showed zombie {@code DRAIN} ServiceRecords (app=null,
     * startForeground DENIED) for 17m after phone inject / clearHostMods pokes.
     */
    static void pokeHidDrain(Context ctx) {
        if (ctx == null) return;
        ensureSpecialsQueuesLocal();
        if (!hidDrainWanted(ctx)) return;
        final Context app = ctx.getApplicationContext();
        Runnable once = () -> {
            try {
                android.content.Intent i = new android.content.Intent(
                    "com.titanus2.usbhid.DRAIN");
                i.setClassName("com.titanus2.usbhid",
                    "com.titanus2.usbhid.HidSessionService");
                i.setPackage("com.titanus2.usbhid");
                // startService only — never startForegroundService for DRAIN
                // (half-session / zombie FGS when HID idle; 1.82 stopSelf if
                // !running). Specials drain when FGS is already live.
                try {
                    app.startService(i);
                } catch (Exception ignored) {}
            } catch (Exception ignored) {}
        };
        // 12.20: single kick only. Triple startService (0/40/90ms) multi-drained
        // queues and contributed multi-glyph spam on host/phone races.
        once.run();
    }

    /**
     * True when exclusive HID is live or Specials bytes are waiting — only then
     * is a DRAIN startService justified.
     */
    private static boolean hidDrainWanted(Context ctx) {
        try {
            if (HostLayoutController.isHidExclusiveLiveFast(ctx)) return true;
        } catch (Exception ignored) {}
        try {
            String s = android.provider.Settings.Global.getString(
                ctx.getContentResolver(), "titan2_usb_hid_session");
            if (s != null) {
                s = s.trim();
                if ("1".equals(s) || "true".equalsIgnoreCase(s) || "on".equalsIgnoreCase(s)) {
                    return true;
                }
            }
        } catch (Exception ignored) {}
        try {
            String g = android.provider.Settings.Global.getString(
                ctx.getContentResolver(), "titan2_usb_hid_grab");
            if ("1".equals(g != null ? g.trim() : "")) return true;
        } catch (Exception ignored) {}
        // Bytes waiting for host Specials / soft Type
        String[] q = new String[]{
            "/data/local/tmp/titan2_hid_remote_q",
            "/data/local/tmp/titan2_remote_hid.q",
            "/data/misc/titan2/titan2_hid_remote_q",
            "/data/misc/titan2/titan2_remote_hid.q",
            "/data/local/tmp/titan2_hid_hw.out",
            "/data/misc/titan2/titan2_hid_hw.out",
        };
        for (String path : q) {
            try {
                java.io.File f = new java.io.File(path);
                if (f.isFile() && f.length() > 0) return true;
            } catch (Exception ignored) {}
        }
        return false;
    }

    /**
     * Append press+release HID keyboard records for the HID FGS to drain.
     * Format matches {@code HidControl.key}: type=0x01, mod, usage, press.
     */
    private static void queueHidKey(int mod, int hidUsage) {
        byte m = (byte) (mod & 0xff);
        byte u = (byte) (hidUsage & 0xff);
        byte[] press = new byte[]{0x01, m, u, 1};
        byte[] release = new byte[]{0x01, 0, u, 0};
        // Clear soft mods after key (sticky Shift fix)
        appendRemoteQueue(press);
        appendRemoteQueue(release);
        if ((mod & 0x01) != 0) appendRemoteQueue(new byte[]{0x01, 0, (byte) 0xe0, 0});
        if ((mod & 0x02) != 0) appendRemoteQueue(new byte[]{0x01, 0, (byte) 0xe1, 0});
        if ((mod & 0x04) != 0) appendRemoteQueue(new byte[]{0x01, 0, (byte) 0xe2, 0});
        if ((mod & 0x08) != 0) appendRemoteQueue(new byte[]{0x01, 0, (byte) 0xe3, 0});
    }

    /**
     * 12.85: release host Alt/Ctrl/Shift leftovers (Sym used to stream as Right
     * Alt under exclusive inject). Call when arming inject specials.
     */
    public static void clearHostKeyboardMods(Context ctx) {
        // HID usage: LeftCtrl e0, LShift e1, LAlt e2, LGui e3, RCtrl e4, RShift e5, RAlt e6, RGui e7
        for (int u : new int[]{0xe0, 0xe1, 0xe2, 0xe3, 0xe4, 0xe5, 0xe6, 0xe7}) {
            appendRemoteQueue(new byte[]{0x01, 0, (byte) u, 0});
        }
        // empty all-keys-up
        appendRemoteQueue(new byte[]{0x01, 0, 0, 0});
        try { pokeHidDrain(ctx); } catch (Exception ignored) {}
    }

    private static void appendRemoteQueue(byte[] rec) {
        if (rec == null || rec.length < 4) return;
        // 12.23: ONE queue only. Writing remote_q + hw.out + inj on every path
        // made FGS drain the same key N times (user: 1 press → 5 glyphs).
        // 12.75: prefer world tmp first. Controls cannot write HID CE; old order
        // tried CE (fail) then tmp, while FGS drain preferred CE first — stale CE
        // bytes were emitted and tmp (fresh Specials) cleared without host type.
        String[] paths = new String[]{
            "/data/local/tmp/titan2_remote_hid.q",
            "/data/misc/titan2/titan2_remote_hid.q",
            "/data/user/0/com.titanus2.usbhid/files/titan2_remote_hid.q",
            "/data/data/com.titanus2.usbhid/files/titan2_remote_hid.q",
        };
        boolean wrote = false;
        for (String path : paths) {
            try {
                java.io.File f = new java.io.File(path);
                java.io.File parent = f.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(f, true)) {
                    out.write(rec, 0, 4);
                }
                //noinspection ResultOfMethodCallIgnored
                f.setReadable(true, false);
                //noinspection ResultOfMethodCallIgnored
                f.setWritable(true, false);
                try {
                    android.system.Os.chmod(f.getAbsolutePath(), 0666);
                } catch (Throwable ignored) {}
                wrote = true;
                break;
            } catch (Exception ignored) {}
        }
        // DGRAM only if no file write (bridge may listen without CE access)
        if (wrote) return;
        try {
            android.net.LocalSocket s = new android.net.LocalSocket();
            s.connect(new android.net.LocalSocketAddress(
                "titan2_hid", android.net.LocalSocketAddress.Namespace.ABSTRACT));
            s.getOutputStream().write(rec, 0, 4);
            s.getOutputStream().flush();
            s.close();
        } catch (Exception ignored) {}
    }

    /**
     * Local phone inject for a host: chord (no REMOTE_INPUT).
     * Used by host-layout when HID is off and specials type into the phone.
     * @return true only if a real key inject was accepted (not agent-queue alone)
     */
    public static boolean hostLocalOnly(Context ctx, String action) {
        return hostLocalOnly(ctx, action, 0);
    }

    /**
     * @param extraMeta physical HW modifiers merged into the chord (Shift/Ctrl/…)
     * @return true only if INJECT_EVENTS chord was accepted. Agent queue is always
     *         best-effort side effect — writing the queue file alone used to return
     *         true with no pad-agent → Specials U/P empty while layout stayed "on".
     */
    public static boolean hostLocalOnly(Context ctx, String action, int extraMeta) {
        if (action == null || !KeyMapPrefs.isHostAction(action)) return false;
        String spec = action.substring(KeyMapPrefs.ACT_HOST_PREFIX.length()).trim();
        HostChord chord = parseHostChord(spec);
        if (chord == null) return false;
        if (chord.keyCode <= 0 || chord.keyCode == KeyEvent.KEYCODE_UNKNOWN) return false;
        int meta = chord.meta | usefulMeta(extraMeta);
        // 12.21: inject OR agent queue — never both (dual keyevent = multi-glyph).
        // Priv-app INJECT_EVENTS first; only if denied, pad-agent input keyevent.
        // 12.67: on inject success drop stale agent queue (prior inject-fail residue
        // re-fired on next drain → Termux multi-glyph / multi-print thrash).
        if (injectKeyChord(ctx, meta, chord.keyCode)) {
            clearAgentKeyQueue(ctx);
            return true;
        }
        // Exclusive HID owns host — never pad-agent dual-fire into phone.
        try {
            if (HostLayoutController.isHidExclusiveLive(ctx)) {
                clearAgentKeyQueue(ctx);
                return false;
            }
        } catch (Exception ignored) {}
        // 12.94/12.95: agent keyevent / keycombination multi-fires Termux
        // specials (user: 1 press → 5 glyphs). Never agent for specials chords.
        clearAgentKeyQueue(ctx);
        return false;
    }

    /**
     * 12.95: phone specials — INJECT_EVENTS only, never pad-agent queue.
     * @return true if inject accepted
     */
    public static boolean hostLocalInjectOnly(Context ctx, String action, int extraMeta) {
        if (action == null || !KeyMapPrefs.isHostAction(action)) return false;
        String spec = action.substring(KeyMapPrefs.ACT_HOST_PREFIX.length()).trim();
        HostChord chord = parseHostChord(spec);
        if (chord == null) return false;
        if (chord.keyCode <= 0 || chord.keyCode == KeyEvent.KEYCODE_UNKNOWN) return false;
        int meta = chord.meta | usefulMeta(extraMeta);
        clearAgentKeyQueue(ctx);
        if (injectKeyChord(ctx, meta, chord.keyCode)) {
            clearAgentKeyQueue(ctx);
            return true;
        }
        return false;
    }

    /** Public wrapper for specials phone fallback (no agent). */
    public static boolean injectKeyChordPublic(Context ctx, int meta, int keyCode) {
        return injectKeyChord(ctx, meta, keyCode);
    }

    private static int mouseButtonsFromAction(String action) {
        if (KeyMapPrefs.ACT_MOUSE_RIGHT.equals(action)) return 2;
        if (KeyMapPrefs.ACT_MOUSE_MIDDLE.equals(action)) return 4;
        if (KeyMapPrefs.ACT_MOUSE_LEFT.equals(action)) return 1;
        try {
            String rest = action.substring(KeyMapPrefs.ACT_MOUSE_PREFIX.length()).trim();
            if ("left".equals(rest)) return 1;
            if ("right".equals(rest)) return 2;
            if ("middle".equals(rest)) return 4;
            if (rest.startsWith("scroll") || rest.startsWith("wheel")) return 0;
            return Integer.parseInt(rest);
        } catch (Exception e) {
            return 1;
        }
    }

    /**
     * Wheel notches for mouse:scroll_* / mouse:wheel:±N. Positive = up (away),
     * negative = down (toward user). 0 = not a wheel action.
     */
    static int mouseWheelFromAction(String action) {
        if (action == null || !KeyMapPrefs.isMouseAction(action)) return 0;
        if (KeyMapPrefs.ACT_MOUSE_SCROLL_UP.equals(action)) return 1;
        if (KeyMapPrefs.ACT_MOUSE_SCROLL_DOWN.equals(action)) return -1;
        if (action.startsWith(KeyMapPrefs.ACT_MOUSE_WHEEL_PREFIX)) {
            String n = action.substring(KeyMapPrefs.ACT_MOUSE_WHEEL_PREFIX.length()).trim();
            try {
                int v = Integer.parseInt(n.replace("+", ""));
                if (v == 0) return 0;
                if (v > 15) v = 15;
                if (v < -15) v = -15;
                return v;
            } catch (Exception e) {
                return 0;
            }
        }
        String rest = action.substring(KeyMapPrefs.ACT_MOUSE_PREFIX.length()).trim();
        if ("scroll_up".equals(rest) || "scroll-up".equals(rest) || "wheel_up".equals(rest)) {
            return 1;
        }
        if ("scroll_down".equals(rest) || "scroll-down".equals(rest)
                || "wheel_down".equals(rest)) {
            return -1;
        }
        return 0;
    }

    /** Phone-only scroll when HID is off (page keys — reliable without a11y gestures). */
    private static void injectPhoneScroll(Context ctx, int wheel) {
        if (wheel == 0) return;
        int code = wheel > 0 ? KeyEvent.KEYCODE_PAGE_UP : KeyEvent.KEYCODE_PAGE_DOWN;
        int n = Math.min(15, Math.abs(wheel));
        for (int i = 0; i < n; i++) {
            injectKeyCode(ctx, code);
        }
    }

    private static final class HostChord {
        final int meta;     // Android meta state
        final int keyCode;
        final int hidUsage;
        HostChord(int meta, int keyCode, int hidUsage) {
            this.meta = meta; this.keyCode = keyCode; this.hidUsage = hidUsage;
        }
    }

    /** Parse host:ctrl+f1 / host:esc / host:shift+0 / host:up / host:alt+tab */
    private static HostChord parseHostChord(String spec) {
        if (spec == null || spec.isEmpty()) return null;
        String[] parts = spec.toLowerCase().split("\\+");
        int meta = 0;
        int keyCode = 0;
        int hidUsage = 0;
        for (String raw : parts) {
            String p = raw.trim();
            if (p.isEmpty()) continue;
            switch (p) {
                case "ctrl":
                case "control":
                    meta |= KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON;
                    break;
                case "shift":
                    meta |= KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON;
                    break;
                case "alt":
                    meta |= KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON;
                    break;
                case "meta":
                case "win":
                case "cmd":
                    meta |= KeyEvent.META_META_ON | KeyEvent.META_META_LEFT_ON;
                    break;
                case "esc":
                case "escape":
                    keyCode = KeyEvent.KEYCODE_ESCAPE;
                    hidUsage = 0x29;
                    break;
                case "tab":
                    keyCode = KeyEvent.KEYCODE_TAB;
                    hidUsage = 0x2b;
                    break;
                case "enter":
                case "return":
                    keyCode = KeyEvent.KEYCODE_ENTER;
                    hidUsage = 0x28;
                    break;
                case "space":
                    keyCode = KeyEvent.KEYCODE_SPACE;
                    hidUsage = 0x2c;
                    break;
                case "backspace":
                case "bksp":
                case "bs":
                    keyCode = KeyEvent.KEYCODE_DEL;
                    hidUsage = 0x2a;
                    break;
                case "delete":
                case "del":
                    keyCode = KeyEvent.KEYCODE_FORWARD_DEL;
                    hidUsage = 0x4c;
                    break;
                case "up":
                    keyCode = KeyEvent.KEYCODE_DPAD_UP;
                    hidUsage = 0x52;
                    break;
                case "down":
                    keyCode = KeyEvent.KEYCODE_DPAD_DOWN;
                    hidUsage = 0x51;
                    break;
                case "left":
                    keyCode = KeyEvent.KEYCODE_DPAD_LEFT;
                    hidUsage = 0x50;
                    break;
                case "right":
                    keyCode = KeyEvent.KEYCODE_DPAD_RIGHT;
                    hidUsage = 0x4f;
                    break;
                case "home":
                    keyCode = KeyEvent.KEYCODE_MOVE_HOME;
                    hidUsage = 0x4a;
                    break;
                case "end":
                    keyCode = KeyEvent.KEYCODE_MOVE_END;
                    hidUsage = 0x4d;
                    break;
                case "pageup":
                case "pgup":
                    keyCode = KeyEvent.KEYCODE_PAGE_UP;
                    hidUsage = 0x4b;
                    break;
                case "pagedown":
                case "pgdn":
                    keyCode = KeyEvent.KEYCODE_PAGE_DOWN;
                    hidUsage = 0x4e;
                    break;
                case "minus":
                case "-":
                    keyCode = KeyEvent.KEYCODE_MINUS;
                    hidUsage = 0x2d;
                    break;
                case "equal":
                case "=":
                    keyCode = KeyEvent.KEYCODE_EQUALS;
                    hidUsage = 0x2e;
                    break;
                case "period":
                case "dot":
                case ".":
                    keyCode = KeyEvent.KEYCODE_PERIOD;
                    hidUsage = 0x37;
                    break;
                case "comma":
                case ",":
                    keyCode = KeyEvent.KEYCODE_COMMA;
                    hidUsage = 0x36;
                    break;
                case "slash":
                case "/":
                    keyCode = KeyEvent.KEYCODE_SLASH;
                    hidUsage = 0x38;
                    break;
                case "semicolon":
                    keyCode = KeyEvent.KEYCODE_SEMICOLON;
                    hidUsage = 0x33;
                    break;
                case "quote":
                case "apostrophe":
                    keyCode = KeyEvent.KEYCODE_APOSTROPHE;
                    hidUsage = 0x34;
                    break;
                case "grave":
                    keyCode = KeyEvent.KEYCODE_GRAVE;
                    hidUsage = 0x35;
                    break;
                case "lbracket":
                    keyCode = KeyEvent.KEYCODE_LEFT_BRACKET;
                    hidUsage = 0x2f;
                    break;
                case "rbracket":
                    keyCode = KeyEvent.KEYCODE_RIGHT_BRACKET;
                    hidUsage = 0x30;
                    break;
                case "backslash":
                    keyCode = KeyEvent.KEYCODE_BACKSLASH;
                    hidUsage = 0x31;
                    break;
                default:
                    if (p.startsWith("f") && p.length() >= 2) {
                        try {
                            int n = Integer.parseInt(p.substring(1));
                            if (n >= 1 && n <= 12) {
                                keyCode = KeyEvent.KEYCODE_F1 + (n - 1);
                                hidUsage = 0x3a + (n - 1);
                            }
                        } catch (NumberFormatException ignored) {}
                    } else if (p.length() == 1) {
                        char c = p.charAt(0);
                        if (c >= 'a' && c <= 'z') {
                            keyCode = KeyEvent.KEYCODE_A + (c - 'a');
                            hidUsage = 0x04 + (c - 'a');
                        } else if (c >= '0' && c <= '9') {
                            if (c == '0') {
                                keyCode = KeyEvent.KEYCODE_0;
                                hidUsage = 0x27;
                            } else {
                                keyCode = KeyEvent.KEYCODE_1 + (c - '1');
                                hidUsage = 0x1e + (c - '1');
                            }
                        }
                    }
                    break;
            }
        }
        if (keyCode <= 0 && hidUsage <= 0) return null;
        if (keyCode <= 0) keyCode = KeyEvent.KEYCODE_UNKNOWN;
        return new HostChord(meta, keyCode, hidUsage);
    }

    /**
     * @return true if the letter/key DOWN was accepted (INJECT_EVENTS).
     * 12.68: key UP is best-effort only. Requiring UP success used to return
     * false after the glyph already landed → callers queued pad-agent → dual
     * fire (Termux multi-glyph residual).
     */
    private static boolean injectKeyChord(Context ctx, int meta, int keyCode) {
        if (keyCode <= 0 || keyCode == KeyEvent.KEYCODE_UNKNOWN) return false;
        try {
            long now = SystemClock.uptimeMillis();
            InputManager im = (InputManager) ctx.getSystemService(Context.INPUT_SERVICE);
            Method m = InputManager.class.getMethod("injectInputEvent",
                android.view.InputEvent.class, int.class);
            final int fl = FLAG_INJECTED;
            // ASYNC (0) — do not use WAIT_FOR_RESULT: false negatives on a11y
            // thread re-pass physical keys and double-type when inject actually works.
            final int mode = 0;
            // 12.92: ONE down/up pair with meta bits on the KeyEvent only.
            // Separate Shift/Ctrl/Alt downs re-entered a11y (Termux 5× glyphs)
            // and double-applied modifiers for specials shift+digit chords.
            boolean keyDownOk = invokeInject(m, im, new KeyEvent(now, now,
                KeyEvent.ACTION_DOWN, keyCode, 0, meta,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, fl, InputDevice.SOURCE_KEYBOARD), mode);
            invokeInject(m, im, new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, fl, InputDevice.SOURCE_KEYBOARD), mode);
            return keyDownOk;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean invokeInject(Method m, InputManager im, KeyEvent ev, int mode)
            throws Exception {
        Object r = m.invoke(im, ev, mode);
        if (r instanceof Boolean) return (Boolean) r;
        return true; // older stubs
    }

    /** Best-effort local mouse button for apps that stream phone input (Moonlight). */
    private static void injectMouseTap(Context ctx, int buttons) {
        try {
            InputManager im = (InputManager) ctx.getSystemService(Context.INPUT_SERVICE);
            Method m = InputManager.class.getMethod("injectInputEvent",
                android.view.InputEvent.class, int.class);
            long now = SystemClock.uptimeMillis();
            int toolType = android.view.MotionEvent.TOOL_TYPE_MOUSE;
            // Center of default display — remote apps often ignore coords for button-only
            android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            float x = dm.widthPixels / 2f;
            float y = dm.heightPixels / 2f;
            int androidButtons = 0;
            if ((buttons & 1) != 0) androidButtons |= android.view.MotionEvent.BUTTON_PRIMARY;
            if ((buttons & 2) != 0) androidButtons |= android.view.MotionEvent.BUTTON_SECONDARY;
            if ((buttons & 4) != 0) androidButtons |= android.view.MotionEvent.BUTTON_TERTIARY;
            android.view.MotionEvent.PointerProperties pp = new android.view.MotionEvent.PointerProperties();
            pp.id = 0;
            pp.toolType = toolType;
            android.view.MotionEvent.PointerCoords pc = new android.view.MotionEvent.PointerCoords();
            pc.x = x;
            pc.y = y;
            pc.pressure = 1f;
            pc.size = 1f;
            int downAction = android.view.MotionEvent.ACTION_DOWN;
            int upAction = android.view.MotionEvent.ACTION_UP;
            if (Build.VERSION.SDK_INT >= 23) {
                // Prefer button press/release when available
                try {
                    android.view.MotionEvent down = android.view.MotionEvent.obtain(
                        now, now, android.view.MotionEvent.ACTION_BUTTON_PRESS,
                        1, new android.view.MotionEvent.PointerProperties[]{pp},
                        new android.view.MotionEvent.PointerCoords[]{pc},
                        0, androidButtons, 1f, 1f, 0, 0,
                        InputDevice.SOURCE_MOUSE, 0);
                    android.view.MotionEvent up = android.view.MotionEvent.obtain(
                        now, now + 20, android.view.MotionEvent.ACTION_BUTTON_RELEASE,
                        1, new android.view.MotionEvent.PointerProperties[]{pp},
                        new android.view.MotionEvent.PointerCoords[]{pc},
                        0, 0, 1f, 1f, 0, 0,
                        InputDevice.SOURCE_MOUSE, 0);
                    m.invoke(im, down, 0);
                    m.invoke(im, up, 0);
                    down.recycle();
                    up.recycle();
                    return;
                } catch (Exception ignored) {}
            }
            android.view.MotionEvent down = android.view.MotionEvent.obtain(
                now, now, downAction, 1,
                new android.view.MotionEvent.PointerProperties[]{pp},
                new android.view.MotionEvent.PointerCoords[]{pc},
                0, androidButtons, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0);
            android.view.MotionEvent up = android.view.MotionEvent.obtain(
                now, now + 20, upAction, 1,
                new android.view.MotionEvent.PointerProperties[]{pp},
                new android.view.MotionEvent.PointerCoords[]{pc},
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0);
            m.invoke(im, down, 0);
            m.invoke(im, up, 0);
            down.recycle();
            up.recycle();
        } catch (Exception ignored) {}
    }

    private static void broadcastRemote(Context ctx, String action, String kind,
                                        int buttons, boolean tap,
                                        int meta, int keyCode, int hidUsage) {
        broadcastRemote(ctx, action, kind, buttons, tap, meta, keyCode, hidUsage, 0);
    }

    private static void broadcastRemote(Context ctx, String action, String kind,
                                        int buttons, boolean tap,
                                        int meta, int keyCode, int hidUsage,
                                        int wheel) {
        Intent i = new Intent(com.titanus2.api.Titan2ApiContract.ACTION_REMOTE_INPUT);
        i.putExtra(com.titanus2.api.Titan2ApiContract.EXTRA_REMOTE_ACTION, action);
        i.putExtra(com.titanus2.api.Titan2ApiContract.EXTRA_KIND, kind);
        i.putExtra(com.titanus2.api.Titan2ApiContract.EXTRA_MOUSE_BUTTONS, buttons);
        i.putExtra(com.titanus2.api.Titan2ApiContract.EXTRA_MOUSE_TAP, tap);
        i.putExtra(com.titanus2.api.Titan2ApiContract.EXTRA_MOUSE_WHEEL, wheel);
        // meta for consumers: pack as simple bitmask 1=ctrl 2=shift 4=alt 8=meta
        int mods = 0;
        if ((meta & KeyEvent.META_CTRL_ON) != 0) mods |= 1;
        if ((meta & KeyEvent.META_SHIFT_ON) != 0) mods |= 2;
        if ((meta & KeyEvent.META_ALT_ON) != 0) mods |= 4;
        if ((meta & KeyEvent.META_META_ON) != 0) mods |= 8;
        i.putExtra(com.titanus2.api.Titan2ApiContract.EXTRA_MODIFIERS, mods);
        i.putExtra(com.titanus2.api.Titan2ApiContract.EXTRA_KEYCODE, keyCode);
        i.putExtra(com.titanus2.api.Titan2ApiContract.EXTRA_HID_USAGE, hidUsage);
        // 12.28: ONE delivery only. Old path sent REMOTE_INPUT up to 4× (explicit
        // +perm, explicit, implicit +perm, implicit) plus legacy HOST_MOUSE for
        // mouse — HostMouseReceiver has no de-dupe → N keyTap / N mouse clicks.
        // HID is the sole computer-input sink; explicit component is enough.
        try {
            Intent hid = new Intent(i);
            hid.setPackage("com.titanus2.usbhid");
            hid.setClassName("com.titanus2.usbhid",
                "com.titanus2.usbhid.HostMouseReceiver");
            try {
                ctx.sendBroadcast(hid, com.titanus2.api.Titan2ApiContract.PERMISSION_USE);
            } catch (Exception e1) {
                // Permission string may be unknown to non-priv caller — still once.
                ctx.sendBroadcast(hid);
            }
        } catch (Exception ignored) {}
    }

    private static void stampRemote(Context ctx, String action) {
        try {
            com.titanus2.api.ControlPlane.put(ctx,
                com.titanus2.api.Titan2ApiContract.FILE_REMOTE_INPUT, action);
        } catch (Exception ignored) {}
    }

    private static void openAppOrActivity(Context ctx, String spec) {
        Intent i;
        if (spec.contains("/")) {
            String[] p = spec.split("/", 2);
            i = new Intent(Intent.ACTION_MAIN);
            i.setComponent(new ComponentName(p[0], p[1]));
        } else {
            i = ctx.getPackageManager().getLaunchIntentForPackage(spec);
        }
        if (i == null) {
            toast(ctx, "App not found");
            return;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(i);
        } catch (Exception e) {
            toast(ctx, "Cannot start: " + e.getMessage());
        }
    }

    private static int scanToKeyCode(int scan) {
        switch (KeyMapPrefs.canonicalizeScan(scan)) {
            // Sides must NOT map to APP_SWITCH/HOME — that re-fired Recents/Home
            // and made "side · bottom" look hardcoded as Home while remapping.
            case KeyMapPrefs.SCAN_SIDE_FUNC:
            case KeyMapPrefs.SCAN_SIDE_FUNC2:
                return KeyEvent.KEYCODE_UNKNOWN;
            case KeyMapPrefs.SCAN_APP_SWITCH: return KeyEvent.KEYCODE_APP_SWITCH;
            case KeyMapPrefs.SCAN_BACK: return KeyEvent.KEYCODE_BACK;
            // Free Alt is real ALT_LEFT (menus). Sym/specials own ALT_RIGHT + ralt KCM.
            case KeyMapPrefs.SCAN_ALT: return KeyEvent.KEYCODE_ALT_LEFT;
            case KeyMapPrefs.SCAN_FN: return KeyEvent.KEYCODE_CTRL_LEFT;
            case KeyMapPrefs.SCAN_SYM: return KeyEvent.KEYCODE_SYM;
            default: return KeyEvent.KEYCODE_UNKNOWN;
        }
    }

    /**
     * Inject a single Android keycode (DOWN+UP). Public for magic layers.
     * @return true if inject succeeded (priv-app INJECT_EVENTS)
     */
    /** @hide KeyEvent.FLAG_INJECTED — marks events so a11y filter ignores them. */
    private static final int FLAG_INJECTED = 0x01000000;

    public static boolean injectKeyCode(Context ctx, int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN || keyCode <= 0) {
            return false;
        }
        try {
            long now = SystemClock.uptimeMillis();
            // FLAG_INJECTED + VIRTUAL_KEYBOARD so TrackpadAccessService never
            // re-handles our soft inject (Specials flood bug).
            KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0,
                0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                FLAG_INJECTED, InputDevice.SOURCE_KEYBOARD);
            KeyEvent up = new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0,
                0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                FLAG_INJECTED, InputDevice.SOURCE_KEYBOARD);
            InputManager im = (InputManager) ctx.getSystemService(Context.INPUT_SERVICE);
            Method m = InputManager.class.getMethod("injectInputEvent",
                android.view.InputEvent.class, int.class);
            final int mode = 0;
            // 12.68: DOWN owns delivery. UP is best-effort — UP false used to
            // queue pad-agent after the key already landed (multi-glyph).
            if (!invokeInject(m, im, down, mode)) return false;
            invokeInject(m, im, up, mode);
            return true;
        } catch (Exception e) {
            AccessibilityService svc = TrackpadAccessService.get();
            if (svc != null) {
                if (keyCode == KeyEvent.KEYCODE_HOME) {
                    svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_APP_SWITCH) {
                    // Prefer overview global action over statusbar no-op (same as ACT_RECENTS).
                    svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS);
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Emit one Android keycode for sticky layouts / Termux / HID phone path.
     * Prefer INJECT_EVENTS; else single-line agent queue (no append spam).
     */
    public static boolean emitLayoutKeyCode(Context ctx, int keyCode) {
        return emitLayoutKeyCode(ctx, keyCode, 0);
    }

    /**
     * Same as {@link #emitLayoutKeyCode(Context, int)} with physical modifiers
     * (e.g. Shift+layout-arrow → select, Ctrl+specials glyph).
     * @return true only if INJECT_EVENTS accepted. Agent queue is best-effort
     *         side effect (writing the file is not delivery when Magisk/agent is gone).
     */
    public static boolean emitLayoutKeyCode(Context ctx, int keyCode, int extraMeta) {
        if (keyCode <= 0 || keyCode == KeyEvent.KEYCODE_UNKNOWN) return false;
        int meta = usefulMeta(extraMeta);
        // 12.21: inject OR agent — never both (multi-glyph)
        // 12.67: clear agent queue after successful inject (stale dual fire)
        if (meta != 0) {
            if (injectKeyChord(ctx, meta, keyCode)) {
                clearAgentKeyQueue(ctx);
                return true;
            }
            queueAgentKeyChord(ctx, meta, keyCode);
            return false;
        }
        if (injectKeyCode(ctx, keyCode)) {
            clearAgentKeyQueue(ctx);
            return true;
        }
        queueAgentKeyCode(ctx, keyCode);
        return false;
    }

    /**
     * Queue key(s) for pad-agent root {@code input}. Single code or
     * {@code c CODE...} for {@code input keycombination} (shift+digit, etc.).
     */
    public static boolean queueAgentKeyChord(Context ctx, int meta, int keyCode) {
        if (keyCode <= 0 || keyCode == KeyEvent.KEYCODE_UNKNOWN) return false;
        StringBuilder sb = new StringBuilder();
        if ((meta & KeyEvent.META_CTRL_ON) != 0) sb.append(KeyEvent.KEYCODE_CTRL_LEFT).append(' ');
        if ((meta & KeyEvent.META_ALT_ON) != 0) sb.append(KeyEvent.KEYCODE_ALT_LEFT).append(' ');
        if ((meta & KeyEvent.META_SHIFT_ON) != 0) sb.append(KeyEvent.KEYCODE_SHIFT_LEFT).append(' ');
        if ((meta & KeyEvent.META_META_ON) != 0) sb.append(KeyEvent.KEYCODE_META_LEFT).append(' ');
        if (sb.length() > 0) {
            sb.insert(0, "c ");
            sb.append(keyCode);
        } else {
            sb.append(keyCode);
        }
        return writeAgentKeyQueue(ctx, sb.toString());
    }

    /**
     * Queue a single Android keycode for pad-agent root {@code input keyevent}.
     * Overwrites the queue file (never APPEND) so hold/repeat cannot spam a backlog.
     */
    public static boolean queueAgentKeyCode(Context ctx, int keyCode) {
        if (keyCode <= 0 || keyCode == KeyEvent.KEYCODE_UNKNOWN) return false;
        return writeAgentKeyQueue(ctx, Integer.toString(keyCode));
    }

    /**
     * 12.31: drop pending pad-agent keyevents when layout force-off / hold ends.
     * Stale queue lines re-fire as multi-glyph on next agent poll.
     */
    public static void clearAgentKeyQueue(Context ctx) {
        // 13.73: tmp first; OS plane only when AgentBridge SELinux backoff allows
        // (system_data_file residual denied write → avc flood; pad-agent 2.25 relabels).
        java.util.ArrayList<String> paths = new java.util.ArrayList<>(3);
        paths.add("/data/local/tmp/titan2_keycode_inject");
        if (AgentBridge.osCtrlAllowed()) {
            paths.add("/data/misc/titan2/titan2_keycode_inject");
        }
        for (String path : paths) {
            try {
                java.io.File f = new java.io.File(path);
                if (!f.isFile()) continue;
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(f, false)) {
                    // truncate
                }
            } catch (Exception e) {
                if (path != null && path.startsWith(AgentBridge.OS_CTRL)) {
                    AgentBridge.noteOsCtrlDenied();
                }
            }
        }
        if (ctx != null) {
            try {
                java.io.FileOutputStream fos = ctx.openFileOutput(
                    "titan2_keycode_inject", Context.MODE_PRIVATE);
                fos.close();
            } catch (Exception ignored) {}
        }
        try {
            java.io.File wake = new java.io.File("/data/local/tmp/titan2_keycode_wake");
            if (wake.isFile()) {
                //noinspection ResultOfMethodCallIgnored
                wake.delete();
            }
        } catch (Exception ignored) {}
    }

    /**
     * 12.32: truncate HID specials/remote queues on layout end so FGS drain
     * cannot re-emit leftover exclusive Specials after hold release.
     * Truncate only (keep world-writable file for next arm).
     */
    public static void clearRemoteHidQueues(Context ctx) {
        String[] names = new String[]{
            "titan2_remote_hid.q",
            "titan2_hid_remote_q",
            "titan2_hid_hw.out",
            "titan2_hid.inj",
        };
        String[] dirs = new String[]{
            "/data/local/tmp",
            "/data/misc/titan2",
            "/data/user/0/com.titanus2.usbhid/files",
            "/data/data/com.titanus2.usbhid/files",
        };
        for (String dir : dirs) {
            for (String name : names) {
                try {
                    java.io.File f = new java.io.File(dir, name);
                    if (!f.isFile()) continue;
                    try (java.io.FileOutputStream fos = new java.io.FileOutputStream(f, false)) {
                        // truncate
                    }
                } catch (Exception ignored) {}
            }
        }
        // Controls may have written CE remote path via hostRemoteOnly prefer list
        if (ctx != null) {
            try {
                java.io.File base = ctx.getFilesDir();
                if (base != null) {
                    for (String name : names) {
                        try {
                            java.io.File f = new java.io.File(base, name);
                            if (!f.isFile()) continue;
                            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(f, false)) {
                                // truncate
                            }
                        } catch (Exception ignored) {}
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    private static boolean writeAgentKeyQueue(Context ctx, String line) {
        if (line == null || line.isEmpty()) return false;
        byte[] data = (line + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        // 12.27: ONE world path only — /data/local/tmp first.
        // 12.25 CE-first left staged/rootless pad-agent unable to read the queue
        // (shell cannot open app CE) while Magisk dual-file fan-out multi-glyph
        // was already fixed by pad-agent 1.42 first-file drain. Prefer tmp so
        // inject-fail → agent path works on lab_rootless without dual writes.
        // 13.73: skip setattr/chmod on tmp/OS plane (SELinux setattr flood only);
        // skip OS plane while AgentBridge backoff (system_data_file write deny).
        // pad-agent 2.25 relabels OS plane → shell_data_file so misc writes work.
        boolean wrote = false;
        java.util.ArrayList<String> paths = new java.util.ArrayList<>(2);
        paths.add("/data/local/tmp/titan2_keycode_inject");
        if (AgentBridge.osCtrlAllowed()) {
            paths.add("/data/misc/titan2/titan2_keycode_inject");
        }
        for (String path : paths) {
            try {
                java.io.File f = new java.io.File(path);
                java.io.File parent = f.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(f, false)) {
                    fos.write(data);
                }
                // World mode already set by pad-agent seed; setattr denied on
                // shell_data_file / system_data_file — do not flood avc.
                wrote = true;
                break;
            } catch (Exception e) {
                if (path != null && path.startsWith(AgentBridge.OS_CTRL)) {
                    AgentBridge.noteOsCtrlDenied();
                }
            }
        }
        if (!wrote && ctx != null) {
            try {
                java.io.FileOutputStream fos = ctx.openFileOutput(
                    "titan2_keycode_inject", Context.MODE_PRIVATE);
                fos.write(data);
                fos.close();
                wrote = true;
            } catch (Exception ignored) {}
        }
        try {
            java.io.File wake = new java.io.File("/data/local/tmp/titan2_keycode_wake");
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(wake, false)) {
                fos.write('1');
            }
        } catch (Exception ignored) {}
        return wrote;
    }

    /**
     * 12.98 phone specials: ONE glyph. SoT (locked — do not re-open architectures):
     * <ul>
     *   <li><b>Termux / terminal</b> → pad-agent {@code t } input text only
     *       (13.49+). SET_TEXT no-ops; PASTE false-successes; keycombination
     *       leaves Shift sticky (multi-glyph). Detect via pkg + terminal_view id.</li>
     *   <li><b>Normal editables</b> → a11y SET_TEXT / PASTE once (never dual with keys).</li>
     * </ul>
     * HostLayoutController may call {@link #injectSpecialsGlyphKeyFallback} if this
     * returns false. Fail quiet rather than multi-fire.
     */
    public static boolean injectSpecialsGlyphPhone(Context ctx, char ch) {
        clearAgentKeyQueue(ctx);
        String s = String.valueOf(ch);
        // Terminal / stream first: never SET_TEXT/PASTE (false success / multi-glyph).
        // 13.56: Moonlight/Limelight same class as Termux — no real EditText; exclusive
        // HID works via bridge, phone mode must use pad-agent t / key path only.
        if (isAgentTextSpecialsForeground(ctx)) {
            if (injectSpecialsGlyphTerminal(ctx, s)) return true;
            // Detect hit but queue write failed — do not fall through to PASTE.
            return false;
        }
        // Strict path: input-focus only + never treat hint as real text (QA Settings Search…)
        if (injectCharOne(ctx, s, true)) return true;
        // 13.17: second pass after caret collapse — some SearchViews ignore SET_TEXT once
        if (injectSpecialsGlyphPasteOnly(ctx, s)) return true;
        // 13.40: loose editable walk (some apps keep FOCUS_INPUT off the EditText)
        if (injectCharOne(ctx, s, false)) return true;
        return false;
    }

    /**
     * 13.40: one FLAG_INJECTED US chord when a11y SET_TEXT/PASTE cannot land.
     * Public for HostLayoutController phone fallback (not dual with a11y success).
     */
    public static boolean injectSpecialsGlyphKeyFallback(Context ctx, char ch) {
        return injectTerminalGlyphKeyFallback(ctx, ch);
    }

    private static boolean isTerminalForeground() {
        return isTerminalForeground(null);
    }

    /**
     * 13.47/13.54: lastPkg can lag (WINDOW_STATE not yet). Sniff active a11y root
     * package <b>and</b> com.termux:id/terminal_view (class is often plain View).
     * False-neg → PASTE/SET_TEXT path → false success / multi-glyph residual.
     */
    private static boolean isTerminalForeground(Context ctx) {
        String pkg = TrackpadAccessService.foregroundPkg();
        if (isTerminalPackage(pkg)) return true;
        AccessibilityService svc = (ctx instanceof AccessibilityService)
            ? (AccessibilityService) ctx : TrackpadAccessService.get();
        if (svc == null) return false;
        try {
            android.view.accessibility.AccessibilityNodeInfo root =
                svc.getRootInActiveWindow();
            if (root == null) return false;
            CharSequence p = root.getPackageName();
            boolean termPkg = isTerminalPackage(p != null ? p.toString() : null);
            boolean termView = hasTermuxTerminalViewId(root);
            root.recycle();
            return termPkg || termView;
        } catch (Exception e) {
            return false;
        }
    }

    /** True when active window exposes Termux terminal_view (id, not class). */
    private static boolean hasTermuxTerminalViewId(
            android.view.accessibility.AccessibilityNodeInfo root) {
        if (root == null) return false;
        try {
            java.util.List<android.view.accessibility.AccessibilityNodeInfo> byId =
                root.findAccessibilityNodeInfosByViewId("com.termux:id/terminal_view");
            if (byId == null || byId.isEmpty()) return false;
            for (int i = 0; i < byId.size(); i++) {
                try { byId.get(i).recycle(); } catch (Exception ignored) {}
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isTerminalPackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        String low = pkg.toLowerCase(java.util.Locale.US);
        // Atlas is the product terminal (com.titanus2.atlas) — not "termux" in the name.
        return low.contains("termux") || low.contains("terminal")
            || low.contains("jackpal.androidterm") || low.contains("com.termoneplus")
            || low.contains("com.titanus2.atlas") || low.equals("com.titanus2.atlas");
    }

    /**
     * Packages that need pad-agent text/key inject for phone Sym specials (no
     * usable EditText). Includes terminals + game-stream clients (Moonlight).
     */
    private static boolean isAgentTextSpecialsPackage(String pkg) {
        if (isTerminalPackage(pkg)) return true;
        if (pkg == null || pkg.isEmpty()) return false;
        String low = pkg.toLowerCase(java.util.Locale.US);
        return low.contains("limelight") || low.contains("moonlight")
            || low.contains("com.limelight")
            || low.contains("scrcpy") || low.contains("com.genymobile");
    }

    /** Termux/Moonlight-class foreground → agent {@code t } / key SoT only. */
    public static boolean isAgentTextSpecialsForegroundPublic(Context ctx) {
        return isAgentTextSpecialsForeground(ctx);
    }

    private static boolean isAgentTextSpecialsForeground(Context ctx) {
        if (isTerminalForeground(ctx)) return true;
        String pkg = TrackpadAccessService.foregroundPkg();
        if (isAgentTextSpecialsPackage(pkg)) return true;
        AccessibilityService svc = (ctx instanceof AccessibilityService)
            ? (AccessibilityService) ctx : TrackpadAccessService.get();
        if (svc == null) return false;
        try {
            android.view.accessibility.AccessibilityNodeInfo root =
                svc.getRootInActiveWindow();
            if (root == null) return false;
            CharSequence p = root.getPackageName();
            boolean hit = isAgentTextSpecialsPackage(
                p != null ? p.toString() : null);
            root.recycle();
            return hit;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Termux / terminal: no standard EditText.
     * 13.47 lab proof:
     * <ul>
     *   <li>{@code injectInputEvent} → Permission Denial (adb-updated priv-app)</li>
     *   <li>a11y PASTE often returns true with <b>no</b> glyph (false success)</li>
     *   <li>pad-agent {@code titan2_keycode_inject} → {@code input keyevent} works</li>
     * </ul>
     * So: try inject, then always fall through to agent queue. No PASTE.
     */
    private static boolean injectSpecialsGlyphTerminal(Context ctx, String ch) {
        if (ctx == null || ch == null || ch.isEmpty()) return false;
        // 13.56: any agent-text package (Termux + Moonlight), not terminal-only
        if (!isAgentTextSpecialsForeground(ctx)) return false;
        return injectTerminalGlyphKeyFallback(ctx, ch.charAt(0));
    }

    /**
     * Queue one printable glyph for pad-agent {@code input text} ({@code t …}).
     * Prefer this for Termux specials — keycombination leaves Shift sticky.
     */
    public static boolean queueAgentText(Context ctx, String text) {
        if (text == null || text.isEmpty()) return false;
        String one = text.replace("\r", "").replace("\n", "");
        if (one.isEmpty()) return false;
        // One BMP / short glyph only — never paste whole clipboards via agent
        if (one.length() > 4) one = one.substring(0, 1);
        return writeAgentKeyQueue(ctx, "t " + one);
    }

    /**
     * Termux: pad-agent {@code input text} first (agent 2.02+ {@code t } lines).
     * Lab: injectInputEvent denied; keycombination Shift sticky → 8 becomes *.
     */
    private static boolean injectTerminalGlyphKeyFallback(Context ctx, char ch) {
        if (ctx == null || ch == 0) return false;
        try {
            // Preferred: literal glyph via agent input text
            if (queueAgentText(ctx, String.valueOf(ch))) return true;
            String host = HostLayoutController.hostChordForChar(ch);
            if (host != null && !host.isEmpty()) {
                HostChord chord = parseHostChord(host);
                if (chord != null && chord.keyCode > 0
                        && queueAgentKeyChord(ctx, chord.meta, chord.keyCode)) {
                    return true;
                }
            }
            int kc = HostLayoutController.specialsKeyCode(ch);
            if (kc > 0 && queueAgentKeyCode(ctx, kc)) return true;
            // Last resort: INJECT_EVENTS if platform grants it
            if (host != null && !host.isEmpty()) {
                HostChord chord = parseHostChord(host);
                if (chord != null && chord.keyCode > 0
                        && injectKeyChord(ctx, chord.meta, chord.keyCode)) {
                    return true;
                }
            }
            if (kc > 0 && injectKeyCode(ctx, kc)) return true;
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private static android.view.accessibility.AccessibilityNodeInfo findTerminalPasteTarget(
            AccessibilityService svc) {
        try {
            android.view.accessibility.AccessibilityNodeInfo root = svc.getRootInActiveWindow();
            if (root == null) return null;
            // 13.47: Termux exposes terminal_view as android.view.View (not TerminalView).
            try {
                java.util.List<android.view.accessibility.AccessibilityNodeInfo> byId =
                    root.findAccessibilityNodeInfosByViewId("com.termux:id/terminal_view");
                if (byId != null && !byId.isEmpty()) {
                    android.view.accessibility.AccessibilityNodeInfo hit = byId.get(0);
                    for (int i = 1; i < byId.size(); i++) {
                        try { byId.get(i).recycle(); } catch (Exception ignored) {}
                    }
                    root.recycle();
                    return hit;
                }
            } catch (Exception ignored) {}
            android.view.accessibility.AccessibilityNodeInfo focus = root.findFocus(
                android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT);
            if (focus == null) {
                focus = root.findFocus(
                    android.view.accessibility.AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
            }
            if (focus != null && (isTerminalNode(focus) || nodeAcceptsPaste(focus))) {
                root.recycle();
                return focus;
            }
            if (focus != null) focus.recycle();
            android.view.accessibility.AccessibilityNodeInfo found =
                findTerminalViewRecursive(root, 0);
            root.recycle();
            return found;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isTerminalClassName(CharSequence cn) {
        if (cn == null) return false;
        String c = cn.toString();
        return c.contains("Terminal") || c.contains("Emulator")
            || c.contains("TermView") || c.contains("Console")
            || c.contains("termux.view") || c.contains("TerminalView");
    }

    /** Termux: view id terminal_view often class=android.view.View. */
    private static boolean isTerminalNode(
            android.view.accessibility.AccessibilityNodeInfo n) {
        if (n == null) return false;
        if (isTerminalClassName(n.getClassName())) return true;
        try {
            String vid = n.getViewIdResourceName();
            if (vid != null) {
                String v = vid.toLowerCase(java.util.Locale.US);
                if (v.contains("terminal_view") || v.endsWith(":id/terminal")
                        || v.contains("emulator_view")) {
                    return true;
                }
            }
            CharSequence pkg = n.getPackageName();
            if (isTerminalPackage(pkg != null ? pkg.toString() : null)
                    && n.isFocused()) {
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static boolean nodeAcceptsPaste(
            android.view.accessibility.AccessibilityNodeInfo n) {
        if (n == null) return false;
        if (n.isEditable() || n.isPassword()) return true;
        try {
            if (isTerminalNode(n)) return true;
            if (n.getActionList() != null) {
                for (android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction a
                        : n.getActionList()) {
                    if (a != null && a.getId()
                            == android.view.accessibility.AccessibilityNodeInfo.ACTION_PASTE) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static android.view.accessibility.AccessibilityNodeInfo findTerminalViewRecursive(
            android.view.accessibility.AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 16) return null;
        try {
            // 13.47: accept terminal_view View nodes (not only *Terminal* class / editable)
            if (isTerminalNode(n) || nodeAcceptsPaste(n)) {
                if (isTerminalNode(n) || n.isEditable()) {
                    return android.view.accessibility.AccessibilityNodeInfo.obtain(n);
                }
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                android.view.accessibility.AccessibilityNodeInfo c = n.getChild(i);
                if (c == null) continue;
                android.view.accessibility.AccessibilityNodeInfo f =
                    findTerminalViewRecursive(c, depth + 1);
                c.recycle();
                if (f != null) return f;
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * 13.17: PASTE one glyph into input-focused field after forcing caret 0,0.
     * Used when SET_TEXT fails on empty/hint Search… (still no hint commit).
     * 13.18: pin caret after SET_TEXT; restore clipboard after PASTE.
     */
    private static boolean injectSpecialsGlyphPasteOnly(Context ctx, String ch) {
        if (ctx == null || ch == null || ch.isEmpty()) return false;
        AccessibilityService svc = (ctx instanceof AccessibilityService)
            ? (AccessibilityService) ctx : TrackpadAccessService.get();
        if (svc == null) return false;
        android.view.accessibility.AccessibilityNodeInfo focus = null;
        try {
            focus = findInputFocusedEditable(svc);
            if (focus == null) return false;
            // Collapse any bogus full-hint selection so PASTE does not replace hint as content
            setCaret(focus, 0);
            // If still showing hint, SET_TEXT to the glyph alone first (empty base)
            if (isShowingHint(focus) || realEditableText(focus).isEmpty()) {
                android.os.Bundle args = new android.os.Bundle();
                args.putCharSequence(
                    android.view.accessibility.AccessibilityNodeInfo
                        .ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, ch);
                if (focus.performAction(
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                    // 13.18: pin end so next Sym letter appends (not replaces)
                    pinCaretEnd(focus, ch.length());
                    focus.recycle();
                    return true;
                }
            }
            boolean ok = pasteGlyphPreserveClip(ctx, focus, ch);
            if (ok) {
                String now = realEditableText(focus);
                pinCaretEnd(focus, now.length());
            }
            focus.recycle();
            return ok;
        } catch (Exception e) {
            if (focus != null) try { focus.recycle(); } catch (Exception ignored) {}
            return false;
        }
    }

    /**
     * Single printable glyph into focused field. Prefer PASTE for large buffers
     * (Termux); SET_TEXT only for short editables so we never rewrite a whole
     * terminal scrollback (felt like multi-print / thrash).
     */
    public static boolean injectCharOne(Context ctx, String ch) {
        return injectCharOne(ctx, ch, false);
    }

    /**
     * @param strictFocus true for Sym inject: only FOCUS_INPUT editable, no tree
     *                    walk, strip hint text (empty Search… fields).
     */
    public static boolean injectCharOne(Context ctx, String ch, boolean strictFocus) {
        if (ch == null || ch.isEmpty()) return false;
        // One BMP glyph only
        if (ch.length() > 2) ch = ch.substring(0, 1);
        AccessibilityService svc = (ctx instanceof AccessibilityService)
            ? (AccessibilityService) ctx : TrackpadAccessService.get();
        if (svc == null) return false;
        try {
            android.view.accessibility.AccessibilityNodeInfo focus = strictFocus
                ? findInputFocusedEditable(svc) : findEditableNode(svc);
            if (focus == null) return false;
            // 13.16: empty field showing hint — getText() often returns the hint
            // ("Search…"); SET_TEXT then commits the hint as real content. WTF.
            String base = realEditableText(focus);
            boolean showingHint = isShowingHint(focus);
            // Termux / large buffers: PASTE single char only (never SET_TEXT whole)
            // Never PASTE into a pure-hint empty field first — SET_TEXT with glyph only
            final boolean large = !showingHint && base.length() > 64;
            if (large) {
                if (pasteGlyphPreserveClip(ctx, focus, ch)) {
                    pinCaretEnd(focus, base.length() + ch.length());
                    focus.recycle();
                    clearAgentKeyQueue(ctx);
                    return true;
                }
            }
            // Short field: SET_TEXT insert at caret (base already hint-stripped)
            if (!large || showingHint || base.isEmpty()) {
                int start = focus.getTextSelectionStart();
                int end = focus.getTextSelectionEnd();
                if (showingHint || base.isEmpty()) {
                    start = 0;
                    end = 0;
                } else if (strictFocus && base.length() > 0
                        && start == 0 && end == base.length()) {
                    // 13.18: residual full-select after prior SET_TEXT without caret
                    // pin — next glyph would replace the whole field. Append.
                    start = base.length();
                    end = base.length();
                }
                String next;
                if (start >= 0 && end >= 0 && start <= base.length() && end <= base.length()) {
                    next = base.substring(0, start) + ch + base.substring(Math.max(start, end));
                } else {
                    next = base + ch;
                }
                // Never write a string that still starts with the hint prefix by mistake
                CharSequence hintCs = safeHint(focus);
                if (hintCs != null && !hintCs.toString().isEmpty()
                        && next.startsWith(hintCs.toString())
                        && (showingHint || base.isEmpty())) {
                    next = ch;
                }
                android.os.Bundle args = new android.os.Bundle();
                args.putCharSequence(
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    next);
                if (focus.performAction(
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                    // 13.18: SearchView often leaves 0..len selected → next glyph replaces
                    pinCaretEnd(focus, next.length());
                    focus.recycle();
                    clearAgentKeyQueue(ctx);
                    return true;
                }
            }
            // Last resort: caret at 0 + PASTE glyph (hint fields: selection was full hint)
            if (showingHint || base.isEmpty()) {
                setCaret(focus, 0);
            }
            if (pasteGlyphPreserveClip(ctx, focus, ch)) {
                String now = realEditableText(focus);
                pinCaretEnd(focus, now.isEmpty() ? ch.length() : now.length());
                focus.recycle();
                clearAgentKeyQueue(ctx);
                return true;
            }
            focus.recycle();
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 13.18: after SET_TEXT, many SearchView/EditText a11y nodes leave the whole
     * string selected — the next Sym letter would replace instead of append.
     */
    private static void pinCaretEnd(
            android.view.accessibility.AccessibilityNodeInfo focus, int len) {
        if (focus == null || len < 0) return;
        setCaret(focus, len);
    }

    private static void setCaret(
            android.view.accessibility.AccessibilityNodeInfo focus, int pos) {
        if (focus == null || pos < 0) return;
        try {
            android.os.Bundle sel = new android.os.Bundle();
            sel.putInt(android.view.accessibility.AccessibilityNodeInfo
                .ACTION_ARGUMENT_SELECTION_START_INT, pos);
            sel.putInt(android.view.accessibility.AccessibilityNodeInfo
                .ACTION_ARGUMENT_SELECTION_END_INT, pos);
            focus.performAction(
                android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_SELECTION, sel);
        } catch (Exception ignored) {}
    }

    /**
     * PASTE one glyph without permanently clobbering the user clipboard.
     * 13.24: restore previous clip after a short delay — ACTION_PASTE often
     * reads the clipboard asynchronously (Termux PASTE no-op when restored
     * in finally before the terminal consumed the glyph).
     */
    private static boolean pasteGlyphPreserveClip(
            Context ctx, android.view.accessibility.AccessibilityNodeInfo focus, String ch) {
        if (ctx == null || focus == null || ch == null || ch.isEmpty()) return false;
        final android.content.ClipboardManager cm = (android.content.ClipboardManager)
            ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return false;
        android.content.ClipData prev = null;
        try {
            if (cm.hasPrimaryClip()) prev = cm.getPrimaryClip();
        } catch (Exception ignored) {}
        final android.content.ClipData prevFinal = prev;
        final Context app = ctx.getApplicationContext() != null
            ? ctx.getApplicationContext() : ctx;
        try {
            cm.setPrimaryClip(android.content.ClipData.newPlainText("t2", ch));
            boolean ok = focus.performAction(
                android.view.accessibility.AccessibilityNodeInfo.ACTION_PASTE);
            // Also try FOCUS_ACCESSIBILITY paste target if first failed
            if (!ok) {
                // some TerminalView builds need a click first
                try { focus.performAction(
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS); }
                catch (Exception ignored) {}
                ok = focus.performAction(
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_PASTE);
            }
            scheduleClipboardRestore(app, cm, prevFinal);
            return ok;
        } catch (Exception e) {
            scheduleClipboardRestore(app, cm, prevFinal);
            return false;
        }
    }

    private static void scheduleClipboardRestore(
            Context ctx,
            final android.content.ClipboardManager cm,
            final android.content.ClipData prev) {
        if (cm == null) return;
        try {
            android.os.Handler h = new android.os.Handler(ctx.getMainLooper());
            h.postDelayed(new Runnable() {
                @Override public void run() {
                    try {
                        if (prev != null) {
                            cm.setPrimaryClip(prev);
                        } else if (Build.VERSION.SDK_INT >= 28) {
                            cm.clearPrimaryClip();
                        } else {
                            cm.setPrimaryClip(
                                android.content.ClipData.newPlainText("", ""));
                        }
                    } catch (Exception ignored) {}
                }
            }, 250L);
        } catch (Exception e) {
            // last resort immediate restore
            try {
                if (prev != null) cm.setPrimaryClip(prev);
            } catch (Exception ignored) {}
        }
    }

    /**
     * Text actually typed by the user — never the placeholder hint.
     */
    private static String realEditableText(
            android.view.accessibility.AccessibilityNodeInfo focus) {
        if (focus == null) return "";
        if (isShowingHint(focus)) return "";
        CharSequence cur = focus.getText();
        String base = cur == null ? "" : cur.toString();
        CharSequence hint = safeHint(focus);
        if (hint != null && !hint.toString().isEmpty() && base.equals(hint.toString())) {
            return "";
        }
        return base;
    }

    private static boolean isShowingHint(
            android.view.accessibility.AccessibilityNodeInfo focus) {
        if (focus == null) return false;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26 && focus.isShowingHintText()) {
                return true;
            }
        } catch (Exception ignored) {}
        CharSequence cur = focus.getText();
        CharSequence hint = safeHint(focus);
        if (hint == null || hint.length() == 0) return false;
        if (cur == null || cur.length() == 0) return true;
        return cur.toString().equals(hint.toString());
    }

    private static CharSequence safeHint(
            android.view.accessibility.AccessibilityNodeInfo focus) {
        try {
            return focus.getHintText();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Inject a printable character without {@code INJECT_EVENTS} (LOS A16 makes
     * that permission signature-only). Uses focused editable node:
     * SET_TEXT append, then clipboard PASTE.
     * @return true if text was delivered
     */
    public static boolean injectChar(Context ctx, String ch) {
        return injectCharOne(ctx, ch, false);
    }

    /**
     * Sym inject only: real input focus + editable. No a11y-focus-only, no tree
     * walk (those hit Search… bars and commit hints as real text).
     */
    private static android.view.accessibility.AccessibilityNodeInfo findInputFocusedEditable(
            AccessibilityService svc) {
        try {
            android.view.accessibility.AccessibilityNodeInfo root = svc.getRootInActiveWindow();
            if (root == null) return null;
            android.view.accessibility.AccessibilityNodeInfo focus = root.findFocus(
                android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT);
            if (focus == null) {
                root.recycle();
                return null;
            }
            if (isInjectableTextField(focus)) {
                root.recycle();
                return focus;
            }
            // SearchView: focus on parent — dig one editable child (not full tree walk)
            android.view.accessibility.AccessibilityNodeInfo child = findEditableChildShallow(focus, 0);
            focus.recycle();
            root.recycle();
            return child;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isInjectableTextField(
            android.view.accessibility.AccessibilityNodeInfo n) {
        if (n == null) return false;
        if (n.isEditable() || n.isPassword()) return true;
        try {
            if (n.getActionList() != null) {
                for (android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction a
                        : n.getActionList()) {
                    if (a != null && a.getId()
                            == android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    /** Depth-limited dig for SearchView → EditText (max depth 3). */
    private static android.view.accessibility.AccessibilityNodeInfo findEditableChildShallow(
            android.view.accessibility.AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 3) return null;
        try {
            for (int i = 0; i < n.getChildCount(); i++) {
                android.view.accessibility.AccessibilityNodeInfo c = n.getChild(i);
                if (c == null) continue;
                if (isInjectableTextField(c)) {
                    return c; // caller owns
                }
                android.view.accessibility.AccessibilityNodeInfo deep =
                    findEditableChildShallow(c, depth + 1);
                if (deep != null) {
                    c.recycle();
                    return deep;
                }
                c.recycle();
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static android.view.accessibility.AccessibilityNodeInfo findEditableNode(
            AccessibilityService svc) {
        try {
            android.view.accessibility.AccessibilityNodeInfo root = svc.getRootInActiveWindow();
            if (root == null) return null;
            android.view.accessibility.AccessibilityNodeInfo focus = root.findFocus(
                android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT);
            if (focus == null) {
                focus = root.findFocus(
                    android.view.accessibility.AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
            }
            if (focus != null && (focus.isEditable() || focus.isPassword()
                    || focus.getActionList().toString().contains("SET_TEXT"))) {
                root.recycle();
                return focus;
            }
            if (focus != null) focus.recycle();
            // Walk for first editable (legacy injectChar only — not Sym specials)
            android.view.accessibility.AccessibilityNodeInfo found = findEditableRecursive(root);
            root.recycle();
            return found;
        } catch (Exception e) {
            return null;
        }
    }

    private static android.view.accessibility.AccessibilityNodeInfo findEditableRecursive(
            android.view.accessibility.AccessibilityNodeInfo n) {
        if (n == null) return null;
        try {
            if (n.isEditable() || n.isPassword()) {
                return android.view.accessibility.AccessibilityNodeInfo.obtain(n);
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                android.view.accessibility.AccessibilityNodeInfo c = n.getChild(i);
                if (c == null) continue;
                android.view.accessibility.AccessibilityNodeInfo f = findEditableRecursive(c);
                c.recycle();
                if (f != null) return f;
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** Apply magic layer output (keycode or character). Single owner: HID live → host only. */
    public static void injectLayerOut(Context ctx, MagicLayers.Out out) {
        if (out == null) return;
        if (out.isChar()) {
            String host = HostLayoutController.hostChordForChar(out.ch.charAt(0));
            if (host != null && HostLayoutController.isHidSessionLive(ctx)) {
                hostRemoteOnly(ctx, host);
            } else if (host != null) {
                // Phone-only: inject glyph (host chord may need shift; injectChar is safer)
                injectChar(ctx, out.ch);
            } else {
                injectChar(ctx, out.ch);
            }
        } else {
            injectKeyCode(ctx, out.keyCode);
        }
    }

    public static void toggleTorch(Context ctx) {
        // Impulse snap — same breath as QS tile (no belt poll wait).
        boolean next = !TorchTileService.isOn(ctx);
        torchOn = next;
        TorchTileService.applyTorch(ctx, next);
        if (isInteractive(ctx)) {
            toast(ctx, next ? "Flashlight ON" : "Flashlight OFF");
        }
    }

    private static boolean isInteractive(Context ctx) {
        try {
            android.os.PowerManager pm = (android.os.PowerManager)
                ctx.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isInteractive();
        } catch (Exception e) {
            return true;
        }
    }

    private static void toast(Context ctx, String m) {
        try {
            Toast.makeText(ctx.getApplicationContext(), m, Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {}
    }
}
