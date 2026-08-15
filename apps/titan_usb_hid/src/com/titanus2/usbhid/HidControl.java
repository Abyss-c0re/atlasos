package com.titanus2.usbhid;

import android.content.Context;
import android.content.Intent;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Looper;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Session control + soft inject.
 *
 * <p><b>Rootless hybrid (default):</b> Bluetooth HID + USB gadget via in-ROM
 * {@code titan2-usb-hid} init service (runs as root). Soft pad/keys go through
 * {@link BluetoothHidClient} and/or inject file drained by {@code hid_bridge}.
 * App never needs {@code su}; it writes OS plane {@code /data/misc/titan2}
 * (when SELinux allows) plus app-private copies. Never creates user-visible
 * {@code /sdcard/titan2}.
 *
 * <p><b>Magisk module (optional):</b> same control protocol under
 * {@code /data/adb/titan2} if the system stack is absent.
 */
public final class HidControl {
    public static final String ON = "titan2_usb_hid_on";
    public static final String SESSION = "titan2_usb_hid_session";
    public static final String MOUSE = "titan2_usb_hid_mouse";
    public static final String GRAB = "titan2_usb_hid_grab";
    public static final String TYPING_MS = "titan2_usb_hid_typing_ms";
    public static final String SPEED = "titan2_usb_hid_speed";
    public static final String ACCEL = "titan2_usb_hid_accel";
    /** Physical TitanKey → host (0 = soft inject only; Type tab). */
    public static final String KEYS = "titan2_usb_hid_keys";
    /**
     * 1 = phone has active text field / IME — bridge releases TitanKey + pad to
     * Android and stops host redirect until 0. Soft inject still works.
     */
    public static final String LOCAL_INPUT = "titan2_usb_hid_local_input";
    /**
     * 1 = user opted into Screen off OK (FGS + PARTIAL wake while display off).
     * Plane mirror for pad-agent / lab; in-memory {@link #screenOffOk} is the hot path.
     */
    public static final String SCREEN_OFF = "titan2_usb_hid_screen_off";
    public static final int DEFAULT_TYPING_MS = 600;
    public static final int DEFAULT_SPEED_PCT = 100;
    /** Soft typeText / keyTap rate (100% = base hold/gap). */
    public static final int DEFAULT_TYPE_SPEED_PCT = 100;
    public static final int DEFAULT_ACCEL = 0;
    /** Bitmask: bit0=USB, bit1=Bluetooth */
    public static final int TRANSPORT_USB = 1;
    public static final int TRANSPORT_BT = 2;
    public static final int TRANSPORT_BOTH = 3;
    public static final String USB_EN = "titan2_usb_hid_usb";
    public static final String BT_EN = "titan2_usb_hid_bt";
    public static final String HW_OUT = "titan2_hid_hw.out";
    private static volatile int transportMask = TRANSPORT_BT;
    private static volatile boolean screenOffOk = false;
    /**
     * Secure {@code show_ime_with_hard_keyboard} value before exclusive forced
     * soft IME on. Restored on exclusive stop so Controls ImeHwPrefs still owns
     * the product default outside HID sessions.
     */
    private static volatile int softImeSavedShowWithHw = -1;
    private static volatile boolean softImeExclusiveArmed = false;
    /**
     * Type tab / soft compose: phys redirect off, never run dumpsys local-input
     * probes. Set from UI before any FGS work so session-on Type stays snappy.
     */
    private static volatile boolean softCompose = false;
    /** 25–400; higher = shorter key hold/gap in typeText. */
    private static volatile int typeSpeedPct = DEFAULT_TYPE_SPEED_PCT;
    /** Soft Type / inject: snappy defaults (hosts still see press/release). */
    private static final int BASE_KEY_HOLD_MS = 22;
    private static final int BASE_KEY_GAP_MS = 6;
    public static final String INJ_NAME = "titan2_hid.inj";
    /** Abstract unix DGRAM name (matches hid_bridge {@code @titan2_hid}). */
    public static final String INJ_SOCK_ABS = "titan2_hid";
    /** Filesystem DGRAM path (matches hid_bridge default). */
    public static final String INJ_SOCK_FS = "/data/local/tmp/titan2_hid.sock";
    private static final String MAGISK_CTRL = "/data/adb/titan2";
    /** OS control plane (init titan2-ctrl.rc); not MediaStore / Files root. */
    public static final String OS_CTRL = "/data/misc/titan2";

    private static final AtomicReference<File[]> INJ_FILES = new AtomicReference<>();
    private static final Object SOCK_LOCK = new Object();
    private static LocalSocket sockAbs;
    private static LocalSocket sockFs;
    /** Root mirrors never block the UI thread (Type tab was freezing on su+fsync). */
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "titan-hid-io");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    private HidControl() {}

    /** Call once from Application/Activity with context. */
    public static void init(Context ctx) {
        List<File> list = new ArrayList<>();
        File dir = ctx.getFilesDir();
        if (dir != null) {
            if (!dir.exists()) dir.mkdirs();
            list.add(new File(dir, INJ_NAME));
        }
        try {
            File ext = ctx.getExternalFilesDir(null);
            if (ext != null) {
                if (!ext.exists()) ext.mkdirs();
                list.add(new File(ext, INJ_NAME));
            }
        } catch (Exception ignored) {}
        // Shared planes — bridge drains these without app-data CE races.
        // May fail SELinux for the app; socket inject is the primary path.
        list.add(new File("/data/local/tmp", INJ_NAME));
        try {
            File os = osCtrlDir();
            if (os != null) list.add(new File(os, INJ_NAME));
        } catch (Exception ignored) {}
        for (File f : list) {
            try {
                File parent = f.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                if (!f.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    f.createNewFile();
                }
                //noinspection ResultOfMethodCallIgnored
                f.setReadable(true, false);
                //noinspection ResultOfMethodCallIgnored
                f.setWritable(true, false);
            } catch (Exception ignored) {}
        }
        INJ_FILES.set(list.toArray(new File[0]));
        // Optional: world-chmod so Magisk bridge can drain (root only)
        if (Root.available()) {
            StringBuilder sb = new StringBuilder(
                "mkdir -p " + OS_CTRL + " /data/local/tmp; chmod 777 " + OS_CTRL
                + " 2>/dev/null; chmod 666");
            for (File f : list) sb.append(" '").append(f.getAbsolutePath()).append("'");
            sb.append(" ").append(OS_CTRL).append("/").append(INJ_NAME)
              .append(" /data/local/tmp/").append(INJ_NAME)
              .append(" 2>/dev/null; true");
            Root.runSu(sb.toString());
        }
        // Ensure hw.out for BT drain path
        try {
            File hw = new File(ctx.getFilesDir(), HW_OUT);
            if (!hw.exists()) hw.createNewFile();
            //noinspection ResultOfMethodCallIgnored
            hw.setReadable(true, false);
            //noinspection ResultOfMethodCallIgnored
            hw.setWritable(true, false);
        } catch (Exception ignored) {}
    }

    private static File osCtrlDir() {
        try {
            File dir = new File(OS_CTRL);
            if (!dir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            }
            //noinspection ResultOfMethodCallIgnored
            dir.setReadable(true, false);
            //noinspection ResultOfMethodCallIgnored
            dir.setWritable(true, false);
            //noinspection ResultOfMethodCallIgnored
            dir.setExecutable(true, false);
            return dir.exists() ? dir : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Persist a control flag for the in-ROM service (newest-mtime wins).
     * <p>App-owned paths write synchronously (service prefers them). OS plane
     * tries without root; Magisk mirror is <b>async</b> so Type/Pad UI never
     * freezes on {@code su} or fsync.
     */
    public static boolean write(Context ctx, String name, String value) {
        boolean ok = false;
        String safe = value == null ? "" : value.replace("'", "").replace("\n", "").replace("\r", "");
        byte[] data = safe.getBytes(StandardCharsets.UTF_8);
        long now = System.currentTimeMillis();
        // 1) App-private first — Magisk service.sh reads these before OS plane
        try {
            File dir = ctx.getFilesDir();
            if (dir != null) {
                if (!dir.exists()) dir.mkdirs();
                if (writeFile(new File(dir, name), data, now)) ok = true;
            }
        } catch (Exception ignored) {}
        // 2) App external
        try {
            File ext = ctx.getExternalFilesDir(null);
            if (ext != null) {
                if (!ext.exists()) ext.mkdirs();
                if (writeFile(new File(ext, name), data, now)) ok = true;
            }
        } catch (Exception ignored) {}
        // 3) OS control plane (pre-seeded 0666 by titan2-ctrl-seed; rewrite ok)
        try {
            File os = osCtrlDir();
            if (os != null) {
                if (writeFile(new File(os, name), data, now)) ok = true;
            }
        } catch (Exception ignored) {}
        // 4) /data/local/tmp — service + pad-agent always poll here
        try {
            if (writeFile(new File("/data/local/tmp", name), data, now)) ok = true;
        } catch (Exception ignored) {}
        // 4b) Settings.Global mirror (bench + Controls 11.03 parity; tmp SELinux-deny)
        if (isGlobalPlaneName(name)) {
            try {
                android.provider.Settings.Global.putString(
                    ctx.getContentResolver(), name, safe);
            } catch (Exception ignored) {}
        }
        // 5) Optional Magisk/root mirror (async; never required for hybrid)
        if (Root.available()) {
            final String n = name;
            final String v = safe;
            Runnable mirror = () -> Root.runSu(
                "mkdir -p " + OS_CTRL + " " + MAGISK_CTRL + " /data/local/tmp 2>/dev/null; "
                + "printf '%s' '" + v + "' > " + OS_CTRL + "/" + n + "; "
                + "printf '%s' '" + v + "' > " + MAGISK_CTRL + "/" + n + "; "
                + "printf '%s' '" + v + "' > /data/local/tmp/" + n + "; "
                + "chmod 666 " + OS_CTRL + "/" + n + " /data/local/tmp/" + n
                + " 2>/dev/null; true"
            );
            if (Looper.myLooper() == Looper.getMainLooper()) {
                IO.execute(mirror);
            } else {
                mirror.run();
            }
        }
        return ok;
    }

    /** Plane names mirrored to Settings.Global for unrooted lab/bench (Controls 11.03). */
    private static boolean isGlobalPlaneName(String name) {
        if (name == null) return false;
        switch (name) {
            case SESSION:
            case KEYS:
            case GRAB:
            case MOUSE:
            case LOCAL_INPUT:
            case ON:
                return true;
            default:
                return name.startsWith("titan2_usb_hid_")
                    || name.startsWith("titan2_host_")
                    || "titan2_pad_cursor_pause".equals(name)
                    || "titan2_pad_mode".equals(name);
        }
    }

    private static boolean writeFile(File f, byte[] data, long mtimeMs) {
        try {
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try (FileOutputStream fos = new FileOutputStream(f, false)) {
                fos.write(data);
                // No fsync — control flags are polled; sync made Type unusable.
            }
            //noinspection ResultOfMethodCallIgnored
            f.setReadable(true, false);
            //noinspection ResultOfMethodCallIgnored
            f.setWritable(true, false);
            //noinspection ResultOfMethodCallIgnored
            f.setLastModified(mtimeMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static void setTransport(int mask) {
        transportMask = mask & TRANSPORT_BOTH;
        if (transportMask == 0) transportMask = TRANSPORT_BT;
        // Drop USB only when neither hybrid stack nor Magisk/hidg is present
        if ((transportMask & TRANSPORT_USB) != 0 && !Root.usbGadgetAvailable()) {
            transportMask &= ~TRANSPORT_USB;
            if (transportMask == 0) transportMask = TRANSPORT_BT;
        }
    }

    public static int getTransport() { return transportMask; }
    public static boolean useUsb() { return (transportMask & TRANSPORT_USB) != 0; }
    public static boolean useBt() { return (transportMask & TRANSPORT_BT) != 0; }

    public static void setScreenOffOk(boolean ok) {
        screenOffOk = ok;
    }

    /**
     * Persist Screen off OK to prefs plane + OS control files so FGS restart
     * and pad-agent see the same policy after process death.
     */
    public static void setScreenOffOk(Context ctx, boolean ok) {
        screenOffOk = ok;
        if (ctx == null) return;
        try {
            ctx.getApplicationContext()
                .getSharedPreferences("usb_hid", Context.MODE_PRIVATE)
                .edit().putBoolean("screen_off", ok).apply();
        } catch (Exception ignored) {}
        try {
            write(ctx, SCREEN_OFF, ok ? "1" : "0");
        } catch (Exception ignored) {}
    }

    public static boolean isScreenOffOk() { return screenOffOk; }

    /** Type tab open: soft inject only — no phys redirect, no dumpsys pause. */
    public static void setSoftCompose(boolean on) { softCompose = on; }
    public static boolean isSoftCompose() { return softCompose; }

    /**
     * Lab wireless ADB is <b>host policy</b> ({@code scripts/host/arm_wireless_adb.sh}),
     * not a product HID feature. Kept as no-op so old call sites compile; do not
     * re-introduce pad-agent enable_wireless_adb from the app (adbd thrash / crashes).
     */
    public static void ensureWirelessAdbBackup(Context ctx) {
        // intentionally empty
    }

    public static void setSession(Context ctx, boolean on, boolean mouse, boolean grab) {
        setSession(ctx, on, mouse, grab, true, useUsb(), useBt());
    }

    public static void setSession(Context ctx, boolean on, boolean mouse, boolean grab,
                                  boolean usb, boolean bt) {
        setSession(ctx, on, mouse, grab, true, usb, bt);
    }

    /**
     * @param keys physical TitanKey redirect (false = phone keeps keyboard; soft type still works)
     * @param mouse physical pad/touchpadd redirect (false = software pad only)
     */
    public static void setSession(Context ctx, boolean on, boolean mouse, boolean grab,
                                  boolean keys, boolean usb, boolean bt) {
        // Only update transport mask while starting/arming a live session.
        // Callers that pass usb=false,bt=false to mean "session off" must not
        // clobber Link prefs (that left both enables at 0 and broke Start).
        // Never arm exclusive/phys session while CE locked (password screen after reboot).
        if (on && !isUserUnlocked(ctx)) {
            android.util.Log.w("TitanUsbHid", "setSession ignored — user not unlocked");
            on = false;
        }
        if (on) {
            // 1.70 B2: truncate stale specials queues then re-seed empty files so
            // exclusive START never re-emits leftover glyphs from a prior session.
            try { flushSpecialsQueues(ctx); } catch (Exception ignored) {}
            if (usb) transportMask |= TRANSPORT_USB; else transportMask &= ~TRANSPORT_USB;
            if (bt) transportMask |= TRANSPORT_BT; else transportMask &= ~TRANSPORT_BT;
            if (transportMask == 0) transportMask = TRANSPORT_BT;
            // Phys session (Pad/Keys/exclusive): hard-clear Type softCompose so
            // the bridge never sees session=1 with grab/keys/mouse=0.
            if (mouse || grab || keys) {
                setSoftCompose(false);
            }
        } else {
            mouse = false;
            grab = false;
            keys = false;
            // 1.71 B2: session STOP flushes leftover specials so next Start is clean
            try { flushSpecialsQueues(ctx); } catch (Exception ignored) {}
            clearHostLayoutKeysPause(ctx);
        }
        final boolean keysWanted = keys;
        // 1.85: phys Start must clear sticky inject/keys_pause left by phone Sym
        // inject residual — otherwise bridge never opens TitanKey for host USB kb.
        if (on && keysWanted) {
            try { clearInjectAndKeysPause(ctx); } catch (Exception ignored) {}
        }
        // 1.88 B2 exclusive Start: also clear Controls Sym inject hold + queues so
        // host specials cannot dual with phone residual (human exclusive feel-test).
        if (on && grab) {
            try { clearInjectAndKeysPause(ctx); } catch (Exception ignored) {}
            try { notifyControlsClearPhoneInject(ctx); } catch (Exception ignored) {}
            // Exclusive specials = in-bridge map (hid_bridge 0.16.14+ forces
            // inject-mode map in-memory when grab). 2.13: never clobber plane
            // specials_method to inject — that undid FB-IN-1 product kcm after
            // every exclusive Stop (device left on inject forever).
            // 2.06: do NOT request swap_hid_stack here — heat residual.
        }
        // Layout specials sticky under exclusive: keys=0 so a11y remaps host glyphs.
        // Inject-method exclusive specials are mapped in hid_bridge (keep keys=1).
        boolean layoutSpecialsPause = false;
        try {
            layoutSpecialsPause = isHostLayoutKeysPaused(ctx) && isExclusiveLayoutActive(ctx);
        } catch (Exception ignored) {}
        if (on && grab && keys && layoutSpecialsPause) {
            keys = false;
        } else if (on && grab && keysWanted) {
            // Layout off / inject method — never leave sticky pause killing TitanKey.
            try { clearInjectAndKeysPause(ctx); } catch (Exception ignored) {}
            try { clearHostLayoutKeysPause(ctx); } catch (Exception ignored) {}
            keys = true;
        }
        boolean wantUsb = on && useUsb();
        write(ctx, ON, wantUsb ? "1" : "0");
        write(ctx, SESSION, on ? "1" : "0");
        write(ctx, MOUSE, mouse ? "1" : "0");
        write(ctx, GRAB, grab ? "1" : "0");
        write(ctx, KEYS, keys ? "1" : "0");
        write(ctx, USB_EN, useUsb() ? "1" : "0");
        write(ctx, BT_EN, useBt() ? "1" : "0");
        write(ctx, "titan2_usb_hid_hw_out", useBt() ? "1" : "0");
        if (on && (grab || mouse || keys)) {
            // FB-HID-2: exclusive grab → enable soft IME on phone so local
            // typing remains possible while HW letters go to host. Share/soft
            // Type leave IME policy alone (no dual-type of phys keys).
            if (grab) {
                showSoftImeForExclusive(ctx);
            } else {
                restoreSoftImeAfterExclusive(ctx);
            }
            write(ctx, LOCAL_INPUT, "0");
            try { write(ctx, "titan2_pad_cursor_pause", "0"); } catch (Exception ignored) {}
            // FB-IN-1 / 2.13: product default kcm; seed if empty only.
            // Exclusive Start must not force inject (plane is phone-path SoT;
            // exclusive map lives in-bridge when grab — see hid_bridge 0.16.14).
            try {
                String sm = readPlaneAny(ctx, "titan2_specials_method");
                if (sm == null || sm.isEmpty() || "null".equalsIgnoreCase(sm)) {
                    write(ctx, "titan2_specials_method", "kcm");
                }
            } catch (Exception ignored) {}
        } else if (!on) {
            restoreSoftImeAfterExclusive(ctx);
        }
        if (on && grab) {
            try {
                if (seedKeysPauseIfLayoutOn(ctx, true)) {
                    // layout on — keys already forced 0 by seed
                } else if (keysWanted) {
                    write(ctx, KEYS, "1");
                }
            } catch (Exception ignored) {}
        }
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            sp.getMethod("set", String.class, String.class)
                .invoke(null, "sys.titanus2.usb_hid.session", on ? "1" : "0");
        } catch (Throwable ignored) {}
    }

    /**
     * FGS contract while a phys session is live (not Type soft).
     * One-shot plane write — safe to call from drain (throttled by caller).
     */
    public static void reassertPhysContract(Context ctx, boolean grab, boolean mouse,
                                            boolean keys) {
        if (ctx == null) return;
        if (softCompose && !grab && !mouse && !keys) return;
        setSoftCompose(false);
        write(ctx, SESSION, "1");
        write(ctx, LOCAL_INPUT, "0");
        write(ctx, GRAB, grab ? "1" : "0");
        write(ctx, MOUSE, mouse ? "1" : "0");
        boolean pause = grab && isHostLayoutKeysPaused(ctx);
        write(ctx, KEYS, (keys && !pause) ? "1" : "0");
        // FB-HID-2: re-arm soft IME while exclusive (pad-agent LatinIME heal
        // used to force show_ime_with_hard_keyboard=0 every tick).
        if (grab) {
            showSoftImeForExclusive(ctx);
        }
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            sp.getMethod("set", String.class, String.class)
                .invoke(null, "sys.titanus2.usb_hid.session", "1");
        } catch (Throwable ignored) {}
    }

    /**
     * FB-HID-2: exclusive grab steals TitanKey from the phone — allow the soft
     * IME panel while a hardware keyboard is still "attached" so the user can
     * type into phone editors. Does <b>not</b> arm {@link #LOCAL_INPUT} (that
     * would dual-type phys keys to phone + host). Soft IME is touch-only.
     */
    /**
     * FB-HID-2: exclusive grab → temp allow soft IME with HW keyboard present.
     * Idempotent / re-entrant so pad-agent heal races can be corrected.
     */
    /** True after first unlock (CE available). False on lock-screen / reboot password. */
    public static boolean isUserUnlocked(Context ctx) {
        if (ctx == null) return false;
        try {
            android.os.UserManager um =
                (android.os.UserManager) ctx.getSystemService(Context.USER_SERVICE);
            if (um != null && !um.isUserUnlocked()) return false;
        } catch (Throwable ignored) {}
        return true;
    }

    public static void showSoftImeForExclusive(Context ctx) {
        if (ctx == null) return;
        try {
            if (!softImeExclusiveArmed) {
                int cur = 0;
                try {
                    cur = android.provider.Settings.Secure.getInt(
                        ctx.getContentResolver(), "show_ime_with_hard_keyboard", 0);
                } catch (Exception ignored) {}
                // Product default is 0; if already 1 from a prior exclusive arm, keep 0 restore
                softImeSavedShowWithHw = (cur == 1) ? 0 : cur;
                softImeExclusiveArmed = true;
            }
            int now = 0;
            try {
                now = android.provider.Settings.Secure.getInt(
                    ctx.getContentResolver(), "show_ime_with_hard_keyboard", 0);
            } catch (Exception ignored) {}
            if (now != 1) {
                android.provider.Settings.Secure.putInt(ctx.getContentResolver(),
                    "show_ime_with_hard_keyboard", 1);
            }
            // Soft panel appears when an editor is focused (HW keyboard still
            // "present" for InputManager). Plane stamp so pad-agent FB-HID-2
            // reassert does not force show_ime=0 while exclusive.
            try { write(ctx, "titan2_usb_hid_soft_ime", "1"); } catch (Exception ignored) {}
        } catch (Exception ignored) {}
    }

    /**
     * Restore Secure soft-IME-with-HW after exclusive ends (or demote to share).
     * Does not rewrite Controls {@code ImeHwPrefs} SharedPreferences.
     */
    public static void restoreSoftImeAfterExclusive(Context ctx) {
        if (ctx == null) {
            softImeExclusiveArmed = false;
            softImeSavedShowWithHw = -1;
            return;
        }
        int restore = softImeExclusiveArmed ? softImeSavedShowWithHw : 0;
        softImeExclusiveArmed = false;
        softImeSavedShowWithHw = -1;
        if (restore < 0) restore = 0;
        try {
            android.provider.Settings.Secure.putInt(ctx.getContentResolver(),
                "show_ime_with_hard_keyboard", restore);
        } catch (Exception ignored) {}
        try { write(ctx, "titan2_usb_hid_soft_ime", "0"); } catch (Exception ignored) {}
    }

    /** Fast path: app files only (no su, no OS plane). Service prefers these. */
    private static boolean writeAppOnly(Context ctx, String name, String value) {
        boolean ok = false;
        String safe = value == null ? "" : value.replace("'", "").replace("\n", "").replace("\r", "");
        byte[] data = safe.getBytes(StandardCharsets.UTF_8);
        long now = System.currentTimeMillis();
        try {
            File dir = ctx.getFilesDir();
            if (dir != null) {
                if (!dir.exists()) dir.mkdirs();
                if (writeFile(new File(dir, name), data, now)) ok = true;
            }
        } catch (Exception ignored) {}
        try {
            File ext = ctx.getExternalFilesDir(null);
            if (ext != null) {
                if (!ext.exists()) ext.mkdirs();
                if (writeFile(new File(ext, name), data, now)) ok = true;
            }
        } catch (Exception ignored) {}
        return ok;
    }

    /** Persist Link transport bits without changing session on/off. */
    public static void writeTransportEnables(Context ctx) {
        if (transportMask == 0) transportMask = TRANSPORT_BT;
        write(ctx, USB_EN, useUsb() ? "1" : "0");
        write(ctx, BT_EN, useBt() ? "1" : "0");
        write(ctx, "titan2_usb_hid_hw_out", useBt() ? "1" : "0");
    }

    public static void setTypingGuardMs(Context ctx, int ms) {
        if (ms < 0) ms = 0;
        if (ms > 5000) ms = 5000;
        write(ctx, TYPING_MS, String.valueOf(ms));
    }

    /**
     * Pause/resume physical key + pad redirect while the phone has a focused
     * editor (share mode: type on phone; exclusive: same escape hatch).
     * Bridge polls this hot; does not restart the session.
     */
    public static void setLocalInputPause(Context ctx, boolean pause) {
        write(ctx, LOCAL_INPUT, pause ? "1" : "0");
    }

    /**
     * True if app-private or OS plane says pause. Matches bridge OR semantics
     * so Magisk backup detection is visible without root in the app.
     */
    public static boolean isLocalInputPaused(Context ctx) {
        if ("1".equals(readLocal(ctx, LOCAL_INPUT))) return true;
        // OS / tmp may be world-readable (hybrid + Magisk); try without su first
        try {
            File os = new File(OS_CTRL, LOCAL_INPUT);
            if (os.isFile()) {
                String s = new String(java.nio.file.Files.readAllBytes(os.toPath()),
                    StandardCharsets.UTF_8).trim();
                if ("1".equals(s)) return true;
            }
        } catch (Exception ignored) {}
        try {
            File tmp = new File("/data/local/tmp/" + LOCAL_INPUT);
            if (tmp.isFile()) {
                String s = new String(java.nio.file.Files.readAllBytes(tmp.toPath()),
                    StandardCharsets.UTF_8).trim();
                if ("1".equals(s)) return true;
            }
        } catch (Exception ignored) {}
        if (Root.available() && "1".equals(readCtrl(LOCAL_INPUT))) return true;
        return false;
    }

    public static void setSpeedPct(Context ctx, int pct) {
        if (pct < 25) pct = 25;
        if (pct > 400) pct = 400;
        write(ctx, SPEED, String.valueOf(pct));
    }

    /** Soft keyboard type rate (payload / keyTap). In-process only. */
    public static void setTypeSpeedPct(int pct) {
        if (pct < 25) pct = 25;
        if (pct > 400) pct = 400;
        typeSpeedPct = pct;
    }

    public static int getTypeSpeedPct() {
        return typeSpeedPct;
    }

    public static void setAccel(Context ctx, int level) {
        if (level < 0) level = 0;
        if (level > 3) level = 3;
        write(ctx, ACCEL, String.valueOf(level));
    }

    public static boolean isSessionOn(Context ctx) {
        // Soft compose / Type: never block UI with su cat of SESSION
        if (softCompose) {
            String v = readLocal(ctx, SESSION);
            if (v == null || v.isEmpty()) return true; // assume live while composing
            return "1".equals(v) || "true".equalsIgnoreCase(v) || "on".equalsIgnoreCase(v);
        }
        // Files + Settings.Global (0.97) — not app-private alone (cross-app arm).
        String v = readPlaneAny(ctx, SESSION);
        if ((v == null || v.isEmpty()) && Root.available()) v = readCtrl(SESSION);
        return "1".equals(v) || "true".equalsIgnoreCase(v) || "on".equalsIgnoreCase(v);
    }

    private static String readCtrl(String name) {
        if (!Root.available()) return null;
        try {
            Process p = Runtime.getRuntime().exec(new String[]{
                "su", "-c", "cat " + OS_CTRL + "/" + name + " 2>/dev/null"
            });
            if (!p.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return null;
            }
            java.io.InputStream in = p.getInputStream();
            byte[] b = new byte[32];
            int n = in.read(b);
            if (n <= 0) return null;
            return new String(b, 0, n, StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return null;
        }
    }

    private static String readLocal(Context ctx, String name) {
        try {
            File f = new File(ctx.getFilesDir(), name);
            if (!f.isFile()) return null;
            byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
            return new String(b, StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return null;
        }
    }

    public static final String PAD_RESTORE = "titan2_usb_hid_pad_restore";

    public static void savePadRestore(Context ctx, String mode) {
        if (mode == null || mode.isEmpty()) mode = PadModeClient.OFF;
        write(ctx, PAD_RESTORE, PadModeClient.normalize(mode));
    }

    public static boolean hasPadRestore(Context ctx) {
        String v = peekPadRestore(ctx);
        return v != null && !v.isEmpty();
    }

    public static String peekPadRestore(Context ctx) {
        String v = readLocal(ctx, PAD_RESTORE);
        if (v == null && Root.available()) v = readCtrl(PAD_RESTORE);
        if (v == null || v.isEmpty()) return null;
        return PadModeClient.normalize(v);
    }

    public static String takePadRestore(Context ctx) {
        String v = peekPadRestore(ctx);
        write(ctx, PAD_RESTORE, "");
        return v;
    }

    public static void endSessionAndRestore(Context ctx) {
        endSession(ctx);
        String restore = takePadRestore(ctx);
        if (restore != null) {
            PadModeClient.set(ctx, restore);
            // B8 1.26/1.41: exclusive often kills touchpadd — resurrect only if
            // restored mode wants pad; stop orphan if restore is off.
            String m = PadModeClient.normalize(restore);
            if (PadModeClient.MOUSE.equals(m) || PadModeClient.TRACKPAD.equals(m)) {
                // INPROC_PARK: plane write is enough. Never killall/restart.
                try { ensureTouchpaddAlive(); } catch (Exception ignored) {}
            }
            // off: leave daemon up; it parks. Kill is heresy (ABS residual + delay).
        } else {
            // B8 1.51: exclusive START via FGS-only / missing restore left
            // pad_mode=mouse + titan2-touchpadd running with session=0 (bench
            // heat residual, pad steals TitanKey, Specials dual-type risk).
            // Always dual-write off (tmp + Global) — lab saw tmp=mouse Global=off.
            try { PadModeClient.set(ctx, PadModeClient.OFF); } catch (Exception ignored) {}
            try { write(ctx, "titan2_pad_mode", "off"); } catch (Exception ignored) {}
            try {
                if (ctx != null) {
                    android.provider.Settings.Global.putString(
                        ctx.getContentResolver(), "titan2_pad_mode", "off");
                }
            } catch (Exception ignored) {}
            // off restore: do not kill touchpadd (INPROC_PARK).
        }
    }

    /** No-op. Killing the pad daemon is the slow HID→phone switch. */
    public static void stopTouchpaddAlive() {
        /* keep process; plane files own emit */
    }

    public static boolean isGrabOn(Context ctx) {
        // Prefer shared plane (tmp/Global) so Controls heal and FGS DRAIN agree.
        String v = readPlaneAny(ctx, GRAB);
        if (v == null || v.isEmpty()) {
            // B2 1.19: empty grab with session=0 is idle/share — not exclusive.
            // Old default true made DRAIN treat Stop as exclusive (softCompose
            // / dual-type ghosts after Type park). Session live + missing grab
            // still defaults exclusive (START race before grab write lands).
            String sess = readPlaneAny(ctx, SESSION);
            return "1".equals(sess) || "true".equalsIgnoreCase(sess);
        }
        return "1".equals(v) || "true".equalsIgnoreCase(v) || "on".equalsIgnoreCase(v);
    }

    /**
     * Explicit grab=1 on shared plane (empty is false). Used by FGS recover so
     * session=1 with missing grab file does not resurrect exclusive ghost (1.39).
     */
    public static boolean isGrabPlaneExplicit(Context ctx) {
        String v = readPlaneAny(ctx, GRAB);
        if (v == null || v.isEmpty()) return false;
        return "1".equals(v.trim()) || "true".equalsIgnoreCase(v.trim())
            || "on".equalsIgnoreCase(v.trim());
    }

    /** Physical TitanKey redirect flag (1 = bridge opens keys). */
    public static String readSessionKeys(Context ctx) {
        String v = readPlaneAny(ctx, KEYS);
        if (v == null) v = readLocal(ctx, KEYS);
        return v == null ? "" : v;
    }

    public static boolean isKeysOn(Context ctx) {
        // B2 1.20: shared plane + session-aware empty default (match isGrabOn).
        String v = readPlaneAny(ctx, KEYS);
        if (v == null || v.isEmpty()) {
            String sess = readPlaneAny(ctx, SESSION);
            return "1".equals(sess) || "true".equalsIgnoreCase(sess);
        }
        return "1".equals(v) || "true".equalsIgnoreCase(v) || "on".equalsIgnoreCase(v);
    }

    /** Physical pad redirect flag (1 = touchpadd/driver mouse to host). */
    public static boolean isMouseOn(Context ctx) {
        String v = readPlaneAny(ctx, MOUSE);
        if (v == null || v.isEmpty()) {
            String sess = readPlaneAny(ctx, SESSION);
            return "1".equals(sess) || "true".equalsIgnoreCase(sess);
        }
        return "1".equals(v) || "true".equalsIgnoreCase(v) || "on".equalsIgnoreCase(v);
    }

    /**
     * Prepare driver pad for USB exclusive path. No-op when rootless
     * (soft pad does not need touchpadd grab).
     */
    public static void prepareDriverPad(Context ctx) {
        // Always plane-first set mouse (no PadModeClient.get RPC). Idempotent
        // setPadMode skips mtime thrash when already mouse (1.83).
        try {
            PadModeClient.set(ctx, PadModeClient.MOUSE);
        } catch (Exception ignored) {}
        try {
            write(ctx, "titan2_pad_mode", "mouse");
            // Keep shared input-surface plane from blocking HW pad during HID.
            // If rear trackpad was on → both; else hw only.
            String surf = readPlaneAny(ctx, "titan2_input_surface");
            if (surf == null) surf = "";
            surf = surf.trim().toLowerCase();
            String next = ("sub".equals(surf) || "both".equals(surf)
                || "rear".equals(surf)) ? "both" : "hw";
            write(ctx, "titan2_input_surface", next);
            write(ctx, "titan2_hw_pad_inhibit", "0");
            if (ctx != null) {
                android.provider.Settings.Global.putString(
                    ctx.getContentResolver(), "titan2_pad_mode", "mouse");
                android.provider.Settings.Global.putString(
                    ctx.getContentResolver(), "titan2_input_surface", next);
                android.provider.Settings.Global.putString(
                    ctx.getContentResolver(), "titan2_hw_pad_inhibit", "0");
            }
        } catch (Exception ignored) {}
        if (!Root.available()) return;
        Root.runSu(
            "mkdir -p " + OS_CTRL + " /data/local/tmp; " +
            "printf mouse > " + OS_CTRL + "/titan2_pad_mode; " +
            "printf mouse > /data/local/tmp/titan2_pad_mode; " +
            "printf 0 > " + OS_CTRL + "/titan2_hw_pad_inhibit; " +
            "printf 0 > /data/local/tmp/titan2_hw_pad_inhibit; " +
            "cur=$(cat /data/local/tmp/titan2_input_surface 2>/dev/null); " +
            "case \"$cur\" in sub|both|rear) s=both;; *) s=hw;; esac; " +
            "printf %s \"$s\" > " + OS_CTRL + "/titan2_input_surface; " +
            "printf %s \"$s\" > /data/local/tmp/titan2_input_surface; " +
            "printf 1 > " + OS_CTRL + "/titan2_touchpad_enabled; " +
            "printf 1 > " + OS_CTRL + "/titan2_pad_click; " +
            "for d in /sys/class/input/input*; do " +
            "  n=$(cat \"$d/name\" 2>/dev/null); " +
            "  [ \"$n\" = touchPad ] || continue; " +
            "  echo 0 > \"$d/inhibited\" 2>/dev/null; " +
            "  echo 0 > \"$d/device/inhibited\" 2>/dev/null; " +
            "done; " +
            "IDC=/system/usr/idc/touchPad.idc; " +
            "SRC=/system/usr/idc/touchPad.ignore.idc; " +
            "[ -f /system/etc/titan2_idc/touchPad.ignore.idc ] && " +
            "SRC=/system/etc/titan2_idc/touchPad.ignore.idc; " +
            "if [ -f \"$SRC\" ]; then " +
            "  mkdir -p /data/local/tmp/titan2_idc; " +
            "  cp \"$SRC\" /data/local/tmp/titan2_idc/touchPad.idc; " +
            "  cp \"$SRC\" \"$IDC\" 2>/dev/null || " +
            "    mount --bind /data/local/tmp/titan2_idc/touchPad.idc \"$IDC\" 2>/dev/null; " +
            "fi; " +
            // B8 1.13: prefer Magisk module touchpadd (same as Controls 11.59)
            "if ! pidof titan2-touchpadd >/dev/null 2>&1; then " +
            "  TP=/system/bin/titan2-touchpadd; " +
            "  [ -x /data/adb/modules/titan2_touchpadd/system/bin/titan2-touchpadd ] && " +
            "    TP=/data/adb/modules/titan2_touchpadd/system/bin/titan2-touchpadd; " +
            "  if [ -x \"$TP\" ]; then " +
            "    LOGCAT_OUTPUT=true KEYBOARD_FEATURES=false TAP_TO_CLICK=1 " +
            "      \"$TP\" >>/data/local/tmp/titan2_touchpadd.log 2>&1 & " +
            "  fi; " +
            "fi; true"
        );
    }

    /** @deprecated use {@link #prepareDriverPad} */
    public static void assertExclusivePad(Context ctx) {
        prepareDriverPad(ctx);
    }

    /**
     * B8 1.26: resurrect titan2-touchpadd after exclusive thrash without
     * forcing pad mode to mouse (trackpad restore path).
     */
    public static void ensureTouchpaddAlive() {
        if (!Root.available()) return;
        Root.runSu(
            "if ! pidof titan2-touchpadd >/dev/null 2>&1; then " +
            "  TP=/system/bin/titan2-touchpadd; " +
            "  [ -x /data/adb/modules/titan2_touchpadd/system/bin/titan2-touchpadd ] && " +
            "    TP=/data/adb/modules/titan2_touchpadd/system/bin/titan2-touchpadd; " +
            "  if [ -x \"$TP\" ]; then " +
            "    LOGCAT_OUTPUT=true KEYBOARD_FEATURES=false TAP_TO_CLICK=1 " +
            "      \"$TP\" >>/data/local/tmp/titan2_touchpadd.log 2>&1 & " +
            "  fi; " +
            "fi; true"
        );
    }

    public static void endSession(Context ctx) {
        // B6 1.98: USB gadget path has no BluetoothHidClient button baseline —
        // emit pure button-up + empty kbd while inject plane is still live so
        // Snapdragon/USB hosts do not keep stuck click after session=0.
        try { releaseHostInputBeforePlaneDown(); } catch (Exception ignored) {}
        // Full phys plane off with session — never leave keys/mouse/grab sticky
        // at 1 while session=0 (lab saw dual-type / agent plane lies).
        write(ctx, SESSION, "0");
        write(ctx, ON, "0");
        write(ctx, MOUSE, "0");
        write(ctx, KEYS, "0");
        write(ctx, GRAB, "0");
        write(ctx, LOCAL_INPUT, "0");
        // FB-HID-2: drop exclusive soft-IME arm so phone Secure returns to product
        try { restoreSoftImeAfterExclusive(ctx); } catch (Exception ignored) {}
        // B2 1.14: Type softCompose sticky after Stop blocked next exclusive Specials
        setSoftCompose(false);
        // B2 1.06: clear exclusive Specials pause when session dies so the next
        // Start is not stuck with keys_pause=1 while layout is already off.
        clearHostLayoutKeysPause(ctx);
        // B2 1.22: exclusive thrash can leave pad cursor freeze stuck after Stop
        try { write(ctx, "titan2_pad_cursor_pause", "0"); } catch (Exception ignored) {}
        // B2 1.29: clear session sysprop so Controls corroboration treats HID off
        // while MainActivity process can still be alive after Stop.
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            sp.getMethod("set", String.class, String.class)
                .invoke(null, "sys.titanus2.usb_hid.session", "0");
        } catch (Throwable ignored) {}
        // 1.73: endSession used to only ensureSpecialsQueues (seed empty files)
        // without truncating — leftover remote_q/hw.out re-emitted as multi-glyph
        // on next exclusive Start (setSession off path flushes; endSession did not).
        try { flushSpecialsQueues(ctx); } catch (Exception ignored) {}
    }

    /**
     * Host-safety: no FGS ⇒ never leave USB as pure keyboard/mouse.
     * Ghost session=1 after force-stop / process death dual-typed the lab PC.
     * Clears plane and best-effort restores mtp,adb (enable_hid off).
     */
    public static void forceHostSafeIdle(Context ctx) {
        setSoftCompose(false);
        endSession(ctx);
        // 1.61: re-seed queues after endSession so rootless Specials stay armed
        try { ensureSpecialsQueues(ctx); } catch (Exception ignored) {}
        try {
            if (ctx != null) {
                android.content.ContentResolver cr = ctx.getContentResolver();
                android.provider.Settings.Global.putString(cr, SESSION, "0");
                android.provider.Settings.Global.putString(cr, ON, "0");
                android.provider.Settings.Global.putString(cr, GRAB, "0");
                android.provider.Settings.Global.putString(cr, KEYS, "0");
                android.provider.Settings.Global.putString(cr, MOUSE, "0");
                android.provider.Settings.Global.putString(
                    cr, "titan2_host_layout_keys_pause", "0");
                // Idle host layout off — ghost specials must not block phone typing
                String lay = android.provider.Settings.Global.getString(cr, "titan2_host_layout");
                if (lay == null || lay.isEmpty()) {
                    android.provider.Settings.Global.putString(cr, "titan2_host_layout", "off");
                }
            }
        } catch (Exception ignored) {}
        // Best-effort gadget restore — privileged / Magisk path may succeed.
        // TERM-wait-KILL: bridge 0.16.2+ needs ~200ms for empty kbd/mouse report
        // before SIGKILL, else boot-protocol hosts keep sticky Shift/Alt.
        final String cmd =
            "killall hid_bridge 2>/dev/null; "
            + "i=0; while pidof hid_bridge >/dev/null 2>&1 && [ $i -lt 20 ]; do "
            + "  sleep 0.01; i=$((i+1)); done; "
            + "killall -9 hid_bridge 2>/dev/null; "
            + "for EN in /system/etc/titan2_usb_hid/enable_hid.sh "
            + "/data/adb/modules/titan2_usb_hid/enable_hid.sh; do "
            + "  [ -x \"$EN\" ] && sh \"$EN\" off && break; "
            + "done; "
            + "setprop persist.titanus2.hid_resume 0 2>/dev/null; "
            + "setprop sys.usb.config mtp,adb 2>/dev/null; "
            + "setprop persist.sys.usb.config mtp,adb 2>/dev/null; "
            + "setprop service.adb.tcp.port 5555 2>/dev/null; true";
        if (Root.available()) {
            Root.runSu(cmd);
        } else {
            try {
                Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
            } catch (Exception ignored) {}
        }
    }

    /**
     * Drop keys_pause across tmp / OS / app plane + Global when exclusive ends.
     * Pause is only meaningful while session+grab+layout specials are live.
     * <p>
     * 1.69: also clear twin {@code titan2_usb_hid_keys_pause} (Controls 12.38
     * mirror) so a leftover name cannot leave dual-type / dead host keys.
     * <p>
     * 1.90 B2: also clear {@code titan2_specials_inject_pause}. Pre-13.21 Controls
     * armed inject_pause for Specials; sticky inject∧grab left exclusive keys=0
     * with layout=off (bench C-DUAL-TYPE residual) after Stop / layout release.
     */
    public static void clearHostLayoutKeysPause(Context ctx) {
        write(ctx, "titan2_host_layout_keys_pause", "0");
        write(ctx, "titan2_usb_hid_keys_pause", "0");
        write(ctx, "titan2_specials_inject_pause", "0");
        try {
            if (ctx != null) {
                android.provider.Settings.Global.putString(
                    ctx.getContentResolver(), "titan2_host_layout_keys_pause", "0");
                android.provider.Settings.Global.putString(
                    ctx.getContentResolver(), "titan2_usb_hid_keys_pause", "0");
                android.provider.Settings.Global.putString(
                    ctx.getContentResolver(), "titan2_specials_inject_pause", "0");
            }
        } catch (Exception ignored) {}
    }

    /**
     * 1.88: ask Controls a11y to drop stuck Sym inject hold + agent/remote queues.
     * Explicit component broadcast only (no multi-deliver).
     */
    private static void notifyControlsClearPhoneInject(Context ctx) {
        if (ctx == null) return;
        try {
            Intent i = new Intent("com.titanus2.controls.KEY_FIRE");
            i.setClassName("com.titanus2.controls",
                "com.titanus2.controls.KeyFireReceiver");
            i.putExtra("action", "clear_phone_inject");
            ctx.sendBroadcast(i);
        } catch (Exception ignored) {}
    }

    /**
     * 1.85: clear inject + keys pause twins (phone Sym residual) so USB host
     * keyboard can open TitanKey at Start. Does not force keys=1 when layout
     * specials are actually on.
     */
    public static void clearInjectAndKeysPause(Context ctx) {
        write(ctx, "titan2_specials_inject_pause", "0");
        try {
            if (ctx != null) {
                android.provider.Settings.Global.putString(
                    ctx.getContentResolver(), "titan2_specials_inject_pause", "0");
            }
        } catch (Exception ignored) {}
        String lay = readPlaneAny(ctx, "titan2_host_layout");
        boolean layoutOn = false;
        if (lay != null && !lay.isEmpty()) {
            String l = lay.trim().toLowerCase(Locale.US);
            layoutOn = !("off".equals(l) || "0".equals(l) || "inherit".equals(l));
        }
        if (!layoutOn) {
            clearHostLayoutKeysPause(ctx);
        }
    }

    /**
     * Soft Type / compose: phys pad+keys off, session stays up so USB/BT bridge
     * keeps draining soft inject. App-private first; one batched root mirror.
     * Prefer this over {@link #parkSession} when HID is already live — park
     * tears session=0 and reconfigures gadget (Type lag with session on).
     */
    public static void setPhysRedirect(Context ctx, boolean mouse, boolean grab, boolean keys) {
        // OS plane + app (rootless); service polls mtime-max across both.
        if (grab && keys && isHostLayoutKeysPaused(ctx)) {
            keys = false;
        }
        write(ctx, MOUSE, mouse ? "1" : "0");
        write(ctx, GRAB, grab ? "1" : "0");
        write(ctx, KEYS, keys ? "1" : "0");
    }

    /**
     * Controls host-layout (specials/arrows) forces keys=0 while exclusive HID
     * is live so Accessibility can remap. Plane written by HostLayoutController.
     * <p>
     * Defensive: if pause flag lagged (process death / plane race) but layout
     * is on and exclusive session is live, still treat as paused so we never
     * dual-type (phys keys=1 + soft specials). Layout alone without exclusive
     * still does <b>not</b> force keys=0 (phone typing).
     */
    public static boolean isHostLayoutKeysPaused(Context ctx) {
        // Pause is exclusive-session-only — ignore sticky pause after Stop.
        String sess = readPlaneAny(ctx, SESSION);
        boolean sessionOn = "1".equals(sess) || "true".equalsIgnoreCase(sess);
        if (!sessionOn) return false;
        // B2 1.43: keys_pause only valid while host layout is actually on.
        // Sticky pause=1 after Specials release left exclusive host typing dead
        // (keys forced 0) until manual layout toggle.
        String lay = readPlaneAny(ctx, "titan2_host_layout");
        boolean layoutOn = false;
        if (lay != null && !lay.isEmpty()) {
            String l = lay.trim().toLowerCase(Locale.US);
            layoutOn = !("off".equals(l) || "0".equals(l) || "inherit".equals(l));
        }
        String pause = readPlaneAny(ctx, "titan2_host_layout_keys_pause");
        String pauseTwin = readPlaneAny(ctx, "titan2_usb_hid_keys_pause");
        String injPause = readPlaneAny(ctx, "titan2_specials_inject_pause");
        boolean keysPause = "1".equals(pause) || "true".equalsIgnoreCase(pause)
            || "1".equals(pauseTwin) || "true".equalsIgnoreCase(pauseTwin);
        boolean injectPause = "1".equals(injPause) || "true".equalsIgnoreCase(injPause);
        // 1.90 B2 plane health: inject_pause is Sym-only while layout is off.
        // Pre-13.21 Specials armed inject_pause; if layout is off and keys_pause
        // is also clear, heal inject residual so exclusive keys return to 1.
        // Keep inject_pause when keys_pause still set (active Sym arm path).
        if (injectPause && !layoutOn && !keysPause) {
            try { clearHostLayoutKeysPause(ctx); } catch (Exception ignored) {}
            try {
                if (isGrabPlaneExplicit(ctx)) {
                    writePlaneKeys(ctx, true);
                }
            } catch (Exception ignored) {}
            injectPause = false;
        }
        // Single source of truth: InputPlane (Controls + HID + pad-agent files).
        // After residual heal above, re-check so sticky inject does not win.
        try {
            if (com.titanus2.api.InputPlane.isPhysKeysPaused(ctx)) return true;
        } catch (Throwable ignored) {}
        // Explicit pause flag from Controls (layout on + exclusive).
        if (keysPause) {
            if (layoutOn || injectPause) return true;
            // Heal sticky pause with layout already off (and not inject-method)
            try { clearHostLayoutKeysPause(ctx); } catch (Exception ignored) {}
            // B2 1.45: exclusive still live — restore keys=1 so host typing returns
            // even if Controls afterExclusiveLayoutRelease was missed.
            try {
                if (isGrabPlaneExplicit(ctx)) {
                    writePlaneKeys(ctx, true);
                }
            } catch (Exception ignored) {}
            return false;
        }
        // Defensive dual-type guard: exclusive + active layout plane
        if (isExclusiveLayoutActive(ctx)) return true;
        return false;
    }

    /** Plane helpers for session drain reconcile (B2 layout pause heal). */
    public static void writePlaneKeys(Context ctx, boolean on) {
        write(ctx, KEYS, on ? "1" : "0");
    }

    public static String readKeysPlane(Context ctx) {
        return readPlaneAny(ctx, KEYS);
    }

    /** Public plane read for FGS reassert (grab/mouse/session). */
    public static String readPlaneValue(Context ctx, String name) {
        return readPlaneAny(ctx, name);
    }

    public static String readKeysPausePlane(Context ctx) {
        return readPlaneAny(ctx, "titan2_host_layout_keys_pause");
    }

    /** Shared session plane (mtime merge). Used by FGS reassert. */
    public static String readSessionPlane(Context ctx) {
        return readPlaneAny(ctx, SESSION);
    }

    /**
     * B2: exclusive arm with host layout already on → seed keys_pause=1 and
     * keys=0 immediately so Specials never dual-type while Controls PUBLISH_KM
     * is still in flight (session plane race after FGS start).
     *
     * @return true if pause was seeded
     */
    public static boolean seedKeysPauseIfLayoutOn(Context ctx, boolean exclusiveGrab) {
        if (ctx == null || !exclusiveGrab) return false;
        String lay = readPlaneAny(ctx, "titan2_host_layout");
        if (lay == null || lay.isEmpty()) return false;
        lay = lay.trim().toLowerCase(Locale.US);
        if ("off".equals(lay) || "0".equals(lay) || "inherit".equals(lay)) return false;
        write(ctx, "titan2_host_layout_keys_pause", "1");
        writePlaneKeys(ctx, false);
        // B2 1.42: exclusive Specials arm must not leave phone local_input pause
        write(ctx, LOCAL_INPUT, "0");
        // B2 1.11: Global + queues so Controls/a11y see pause before first glyph
        try {
            android.provider.Settings.Global.putString(
                ctx.getContentResolver(), "titan2_host_layout_keys_pause", "1");
            android.provider.Settings.Global.putString(
                ctx.getContentResolver(), KEYS, "0");
            android.provider.Settings.Global.putString(
                ctx.getContentResolver(), LOCAL_INPUT, "0");
        } catch (Exception ignored) {}
        try { ensureSpecialsQueues(ctx); } catch (Exception ignored) {}
        setSoftCompose(false);
        return true;
    }

    /**
     * Exclusive HID (session + grab) and host layout not off/empty.
     * Used only as lag-safe keys=0 force — not as soft-type pause.
     */
    public static boolean isExclusiveLayoutActive(Context ctx) {
        String sess = readPlaneAny(ctx, SESSION);
        boolean sessionOn = "1".equals(sess) || "true".equalsIgnoreCase(sess)
            || isSessionLikelyOn();
        if (!sessionOn) return false;
        String grab = readPlaneAny(ctx, GRAB);
        if (!"1".equals(grab) && !"true".equalsIgnoreCase(grab)) return false;
        String lay = readPlaneAny(ctx, "titan2_host_layout");
        if (lay == null || lay.isEmpty()) return false;
        lay = lay.trim().toLowerCase(Locale.US);
        if ("off".equals(lay) || "0".equals(lay) || "inherit".equals(lay)) return false;
        // specials / arrows / custom c_* etc.
        return true;
    }

    /**
     * After SELinux denies open on /data/misc/titan2, skip that path for a
     * while so the 4–12 ms drain loop does not flood avc: denied audit spam.
     */
    private static volatile long osCtrlReadBackoffUntilMs;
    /** Cached Controls CE path — never createPackageContext on the drain loop. */
    private static volatile File controlsFilesDir;
    private static volatile boolean controlsFilesDirProbed;

    private static File controlsPlaneFile(String name) {
        if (!controlsFilesDirProbed) {
            controlsFilesDirProbed = true;
            // Fixed CE path only — createPackageContext every 6–12ms flooded
            // ContextImpl warnings and froze Specials drain.
            controlsFilesDir = new File("/data/user/0/com.titanus2.controls/files");
        }
        File dir = controlsFilesDir;
        return dir != null ? new File(dir, name) : null;
    }

    /**
     * Newest mtime wins across HID CE / tmp / OS plane. Stale app-private CE
     * must not beat a fresher Controls {@code keys_pause=1} on tmp (B2 dual-type).
     * Settings.Global is fallback only when no file has a value.
     */
    private static String readPlaneAny(Context ctx, String name) {
        long bestMt = -1;
        String best = null;
        boolean tryOs = System.currentTimeMillis() >= osCtrlReadBackoffUntilMs;
        // Synergy: Controls CE is authoritative for host_layout / keys_pause.
        // Prefer tmp + Controls CE + Global — never createPackageContext on drain.
        File controlsCe = controlsPlaneFile(name);
        File[] files = new File[]{
            // tmp first: Controls hostRemoteOnly + layout write here (world 0666)
            new File("/data/local/tmp", name),
            controlsCe,
            ctx != null ? new File(ctx.getFilesDir(), name) : null,
            tryOs ? new File(OS_CTRL, name) : null,
        };
        if (ctx != null) {
            try {
                File ext = ctx.getExternalFilesDir(null);
                if (ext != null) {
                    files = new File[]{
                        files[0], files[1], files[2], files[3], new File(ext, name)
                    };
                }
            } catch (Exception ignored) {}
        }
        for (File f : files) {
            if (f == null || !f.isFile()) continue;
            try {
                String s = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                    StandardCharsets.UTF_8).trim();
                if (s.isEmpty()) continue;
                long mt = f.lastModified();
                if (mt >= bestMt) {
                    bestMt = mt;
                    best = s;
                }
            } catch (Exception e) {
                // SELinux open deny on OS plane — back off 30s (drain is 4–12ms)
                if (tryOs && f.getPath() != null && f.getPath().startsWith(OS_CTRL)) {
                    osCtrlReadBackoffUntilMs = System.currentTimeMillis() + 30_000L;
                    tryOs = false;
                }
            }
        }
        if (best != null) return best;
        // Settings.Global fallback (Controls 11.32+ / HID write mirror). When
        // tmp/OS files are SELinux-denied or lag after package-replace, exclusive
        // Specials still need keys_pause + host_layout + session from Global so
        // we never dual-type phys keys=1 + soft glyphs (B2).
        if (ctx != null && isGlobalPlaneName(name)) {
            try {
                String g = android.provider.Settings.Global.getString(
                    ctx.getContentResolver(), name);
                if (g != null) {
                    g = g.trim();
                    if (!g.isEmpty()) return g;
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    /**
     * Drop bridge session without pad restore. Clears phys redirect flags so
     * TitanKey/pad stay on the phone (Type compose). Soft inject re-arms later.
     * <p><b>Heavy</b> — only for full stop / USB gadget teardown, not Type open.
     */
    public static void parkSession(Context ctx) {
        write(ctx, SESSION, "0");
        write(ctx, ON, "0");
        write(ctx, MOUSE, "0");
        write(ctx, KEYS, "0");
        write(ctx, GRAB, "0");
        write(ctx, LOCAL_INPUT, "0");
        try { restoreSoftImeAfterExclusive(ctx); } catch (Exception ignored) {}
        // B2 1.18: Type park / soft demote used to leave keys_pause=1 with
        // session=0 — next exclusive Specials dual-type until full Stop.
        clearHostLayoutKeysPause(ctx);
        // B2 1.23: Type park also clears sticky pad cursor freeze
        try { write(ctx, "titan2_pad_cursor_pause", "0"); } catch (Exception ignored) {}
        // B2 1.30: Type park must clear session sysprop (same as endSession) so
        // Controls corroboration does not treat Type-park as live exclusive.
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            sp.getMethod("set", String.class, String.class)
                .invoke(null, "sys.titanus2.usb_hid.session", "0");
        } catch (Throwable ignored) {}
        // 1.73: Type park left remote_q residual same as endSession gap
        try { flushSpecialsQueues(ctx); } catch (Exception ignored) {}
        // Keep transport enables so re-arm is quick; session=0 stops bridge.
    }

    public static boolean send(byte[] packet) {
        if (packet == null || packet.length < 1) return false;
        byte[] rec = new byte[4];
        System.arraycopy(packet, 0, rec, 0, Math.min(4, packet.length));
        boolean ok = false;
        boolean bt = useBt();
        boolean usb = useUsb();
        // 1.68: never both transports on one send — dual host glyphs (user 1:N).
        // Drain paths already pick one; hot keyTap/send must match.
        if (bt && usb) {
            if (planeOnTmp(SESSION) || planeOnTmp("titan2_usb_hid_grab") || planeOnTmp(USB_EN)) {
                bt = false;
            } else {
                usb = false;
            }
        }
        if (bt) {
            if (BluetoothHidClient.get().handlePacket(rec)) ok = true;
        }
        // Soft pad/keys → hid_bridge. Hot path must NEVER su or fsync — that
        // froze Type/soft-pad (1s+ frame skips) when HID session was live.
        // Socket first; app-private inject file only; async root mirror rare.
        //
        // Only inject USB when Link has USB. Forcing softCompose → USB always
        // double-fed BT (app handlePacket + bridge hw.out) and flipped case.
        if (usb) {
            if (sendSock(rec)) ok = true;
            else if (sendAppInj(rec)) ok = true;
            // Do not call sendSuInj here — blocks up to 3s on su waitFor.
        } else if (!bt && (softCompose || isSessionLikelyOn())) {
            // No Link transport bits? last-resort inject for lab.
            if (sendSock(rec)) ok = true;
            else if (sendAppInj(rec)) ok = true;
        }
        return ok;
    }

    /**
     * Best-effort: session flag in memory transport is not enough; check common
     * control files without blocking. Used only to decide whether to attempt USB inject.
     */
    private static boolean isSessionLikelyOn() {
        try {
            File[] paths = new File[]{
                new File(OS_CTRL, SESSION),
                new File("/data/local/tmp", SESSION)
            };
            for (File f : paths) {
                if (f == null || !f.isFile()) continue;
                String s = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                    StandardCharsets.UTF_8).trim();
                if ("1".equals(s) || "true".equalsIgnoreCase(s) || "on".equalsIgnoreCase(s))
                    return true;
            }
        } catch (Exception ignored) {}
        // Global session mirror (0.97) — soft inject path has no Context file CE.
        try {
            // ContentResolver needs a Context; use ActivityThread when available.
            android.app.Application app = null;
            try {
                Class<?> at = Class.forName("android.app.ActivityThread");
                Object cur = at.getMethod("currentApplication").invoke(null);
                if (cur instanceof android.app.Application) {
                    app = (android.app.Application) cur;
                }
            } catch (Throwable ignored) {}
            if (app != null) {
                String g = android.provider.Settings.Global.getString(
                    app.getContentResolver(), SESSION);
                if (g != null) {
                    g = g.trim();
                    if ("1".equals(g) || "true".equalsIgnoreCase(g) || "on".equalsIgnoreCase(g))
                        return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    /**
     * Ensure Magisk/in-ROM USB HID service is alive and control plane is writable.
     * Call on Start. Safe no-op when stack absent.
     */
    /** Seed world-writable queue files Controls + drain paths share (B2). */
    private static void seedQueueFile(File f) {
        try {
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            if (!f.exists()) f.createNewFile();
            //noinspection ResultOfMethodCallIgnored
            f.setReadable(true, false);
            //noinspection ResultOfMethodCallIgnored
            f.setWritable(true, false);
            // Os.chmod 0666 so priv_app Controls can append exclusive Specials
            // (File.setWritable often only owner-write on modern SELinux).
            try {
                android.system.Os.chmod(f.getAbsolutePath(), 0666);
            } catch (Throwable ignored) {}
        } catch (Exception ignored) {}
    }

    /**
     * B2: call on exclusive START / host key path so remote_q is world-writable
     * for Controls hostRemoteOnly before the first Specials symbol.
     */
    public static void ensureSpecialsQueues(Context ctx) {
        seedQueueFile(new File("/data/local/tmp/" + HW_OUT));
        seedQueueFile(new File("/data/local/tmp/" + REMOTE_Q));
        // 1.62: legacy mis-seed name (pad-agent/RootlessPlane ≤1.37/12.10)
        seedQueueFile(new File("/data/local/tmp/titan2_hid_remote_q"));
        seedQueueFile(new File("/data/misc/titan2/" + HW_OUT));
        seedQueueFile(new File("/data/misc/titan2/" + REMOTE_Q));
        seedQueueFile(new File("/data/misc/titan2/titan2_hid_remote_q"));
        if (ctx != null) {
            try {
                seedQueueFile(new File(ctx.getFilesDir(), HW_OUT));
                seedQueueFile(new File(ctx.getFilesDir(), REMOTE_Q));
            } catch (Exception ignored) {}
        }
    }

    /**
     * 1.70: truncate specials/phys inject queues then ensure empty world-writable
     * files exist. Used on exclusive START only (not mid-session drain).
     */
    public static void flushSpecialsQueues(Context ctx) {
        String[] names = new String[]{ HW_OUT, REMOTE_Q, "titan2_hid_remote_q", "titan2_hid.inj" };
        String[] dirs = new String[]{
            "/data/local/tmp",
            "/data/misc/titan2",
        };
        for (String dir : dirs) {
            for (String name : names) {
                try {
                    File f = new File(dir, name);
                    if (!f.isFile()) continue;
                    try (java.io.FileOutputStream fos = new java.io.FileOutputStream(f, false)) {
                        // truncate
                    }
                } catch (Exception ignored) {}
            }
        }
        if (ctx != null) {
            try {
                for (String name : names) {
                    File f = new File(ctx.getFilesDir(), name);
                    if (!f.isFile()) continue;
                    try (java.io.FileOutputStream fos = new java.io.FileOutputStream(f, false)) {
                        // truncate
                    }
                }
            } catch (Exception ignored) {}
        }
        ensureSpecialsQueues(ctx);
    }

    public static void ensureStack(Context ctx) {
        write(ctx, LOCAL_INPUT, "0");
        // Always seed world-readable queues so specials/phys drain works rootless (B2).
        ensureSpecialsQueues(ctx);
        if (!Root.available() && !Root.usbGadgetAvailable()) {
            init(ctx);
            return;
        }
        if (!Root.available()) {
            // Rootless hybrid: only ensure inject files exist
            init(ctx);
            return;
        }
        // Root must not leave files/ as root:root 0700 — that blocks drain forever.
        Root.runSu(
            "mkdir -p " + OS_CTRL + " " + MAGISK_CTRL + " /data/local/tmp /data/misc/titan2; "
            + "chmod 777 " + OS_CTRL + " /data/misc/titan2 2>/dev/null; "
            + "touch " + OS_CTRL + "/" + INJ_NAME + " /data/local/tmp/" + INJ_NAME
            + " /data/local/tmp/" + HW_OUT + " /data/local/tmp/" + REMOTE_Q
            + " /data/misc/titan2/" + HW_OUT + " /data/misc/titan2/" + REMOTE_Q + "; "
            + "chmod 666 " + OS_CTRL + "/" + INJ_NAME + " /data/local/tmp/" + INJ_NAME
            + " /data/local/tmp/" + HW_OUT + " /data/local/tmp/" + REMOTE_Q
            + " /data/misc/titan2/" + HW_OUT + " /data/misc/titan2/" + REMOTE_Q + " 2>/dev/null; "
            + "printf 0 > " + OS_CTRL + "/" + LOCAL_INPUT + "; "
            + "printf 0 > /data/local/tmp/" + LOCAL_INPUT + "; "
            // Fix CE files/ ownership so priv_app can read hw.out / session files
            + "APPF=/data/user/0/com.titanus2.usbhid/files; "
            + "if [ -d \"$APPF\" ]; then "
            + "  APPU=$(stat -c %u /data/user/0/com.titanus2.usbhid 2>/dev/null); "
            + "  [ -n \"$APPU\" ] || APPU=$(stat -c %u /data/data/com.titanus2.usbhid 2>/dev/null); "
            + "  [ -n \"$APPU\" ] && chown -R $APPU:$APPU \"$APPF\" 2>/dev/null; "
            + "  chmod 771 \"$APPF\" 2>/dev/null; "
            + "  chmod 666 \"$APPF\"/* 2>/dev/null; "
            + "  touch \"$APPF/" + HW_OUT + "\" \"$APPF/" + INJ_NAME + "\" \"$APPF/" + REMOTE_Q + "\"; "
            + "  [ -n \"$APPU\" ] && chown $APPU:$APPU \"$APPF/" + HW_OUT + "\" \"$APPF/" + INJ_NAME + "\" \"$APPF/" + REMOTE_Q + "\"; "
            + "  chmod 666 \"$APPF/" + HW_OUT + "\" \"$APPF/" + INJ_NAME + "\" \"$APPF/" + REMOTE_Q + "\" 2>/dev/null; "
            + "fi; "
            + "settings put global adb_enabled 1 2>/dev/null; "
            + "setprop service.adb.tcp.port 5555 2>/dev/null; "
            + "MOD=/data/adb/modules/titan2_usb_hid; "
            + "alive=0; "
            + "if [ -f /data/local/tmp/t2uhid.pid ]; then "
            + "  kill -0 $(cat /data/local/tmp/t2uhid.pid 2>/dev/null) 2>/dev/null && alive=1; "
            + "fi; "
            + "if [ \"$alive\" != 1 ] && [ -x $MOD/service.sh ]; then "
            + "  nohup $MOD/service.sh >/data/local/tmp/titan2_usb_hid_svc.out 2>&1 & "
            + "  echo $! > /data/local/tmp/t2uhid.pid; "
            + "fi; "
            + "true"
        );
        init(ctx);
    }

    public static final String REMOTE_Q = "titan2_remote_hid.q";

    /**
     * True when Controls (or self) has queued host Specials bytes waiting.
     * Used by FGS DRAIN so sticky Type softCompose never drops first glyphs
     * while exclusive plane is still catching up (B2 1.16).
     */
    public static boolean remoteQueueHasBytes(Context ctx) {
        return queueHasBytes(ctx, REMOTE_Q) || queueHasBytes(ctx, HW_OUT);
    }

    /** True when a drain queue has at least one 4-byte HID record. */
    private static boolean queueHasBytes(Context ctx, String queueName) {
        File[] paths = new File[]{
            new File("/data/local/tmp/" + queueName),
            new File("/data/misc/titan2/" + queueName),
        };
        if (ctx != null) {
            try {
                File app = ctx.getFilesDir();
                if (app != null) {
                    paths = new File[]{
                        paths[0], paths[1],
                        new File(app, queueName),
                        new File("/data/user/0/com.titanus2.usbhid/files/" + queueName),
                    };
                }
            } catch (Exception ignored) {}
        }
        for (File f : paths) {
            try {
                if (f != null && f.isFile() && f.length() >= 4) return true;
            } catch (Exception ignored) {}
        }
        return false;
    }

    private static boolean planeOnTmp(String name) {
        try {
            File f = new File("/data/local/tmp", name);
            if (!f.isFile()) return false;
            String v = new String(java.nio.file.Files.readAllBytes(f.toPath())).trim();
            return "1".equals(v) || "true".equalsIgnoreCase(v);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Drain bridge / Controls hardware-out packets (phys keys + host specials).
     * Prefer {@code /data/local/tmp} (0666); CE files/ is secondary.
     * <p><b>B2:</b> must fan-out to <b>USB and BT</b>. Older path only fed BT,
     * so exclusive USB HID never saw specials written only to {@link #HW_OUT}.
     */
    public static void drainHwOut(Context ctx) {
        boolean bt = useBt()
            || "1".equals(readLocal(ctx, BT_EN))
            || planeOnTmp("titan2_usb_hid_bt")
            || planeOnTmp(BT_EN);
        boolean usb = useUsb()
            || "1".equals(readLocal(ctx, USB_EN))
            || planeOnTmp(USB_EN);
        // 1.65: never force both transports — dual emit = dual host glyphs.
        if (!bt && !usb) {
            // Prefer USB gadget if plane says usb session, else BT
            usb = planeOnTmp(USB_EN) || planeOnTmp(SESSION);
            bt = !usb;
            if (!bt && !usb) bt = true;
        } else if (bt && usb) {
            // Both flagged: prefer USB when session grab exclusive (lab cable)
            if (planeOnTmp(SESSION) || planeOnTmp("titan2_usb_hid_grab")) {
                bt = false;
            } else {
                usb = false;
            }
        }
        if (bt) ensureMouseMailbox(ctx);
        File[] paths = new File[]{
            new File("/data/local/tmp/" + HW_OUT),
            new File(ctx.getFilesDir(), HW_OUT),
            new File("/data/user/0/com.titanus2.usbhid/files/" + HW_OUT),
            new File("/data/misc/titan2/" + HW_OUT),
        };
        // 1.67: first non-empty only + clear siblings (was: emit every path →
        // multi-glyph when fan-out leftovers or dual writers hit tmp+CE).
        for (File f : paths) {
            byte[] all = readAndClearQueueFile(f);
            if (all == null || all.length < 4) continue;
            if (bt) emitQueueRecordsBt(all);
            else if (usb) emitQueueRecordsUsb(all);
            for (File g : paths) {
                if (g.equals(f)) continue;
                try { readAndClearQueueFile(g); } catch (Exception ignored) {}
            }
            break;
        }
    }

    /** @deprecated use {@link #drainHwOut} — kept for call sites / Magisk notes */
    @Deprecated
    public static void drainHwOutToBt(Context ctx) {
        drainHwOut(ctx);
    }

    /** Create empty MBX in app files dir so native can open/write with 0666. */
    public static void ensureMouseMailbox(Context ctx) {
        try {
            File f = new File(ctx.getFilesDir(), "titan2_hid_mouse.mbx");
            if (!f.exists() || f.length() < 16) {
                try (FileOutputStream o = new FileOutputStream(f, false)) {
                    byte[] z = new byte[16];
                    z[0] = 'M'; z[1] = 'B'; z[2] = 'X'; z[3] = '1';
                    o.write(z);
                }
                //noinspection ResultOfMethodCallIgnored
                f.setReadable(true, false);
                //noinspection ResultOfMethodCallIgnored
                f.setWritable(true, false);
            }
            /* world symlink-like copy path for native (same inode not needed) */
        } catch (Exception ignored) {}
    }

    /**
     * Drain Controls layout specials soft-inject queue (hostRemoteOnly).
     * Works when exclusive keys=0 (phys TitanKey on phone, remapped to host).
     * <p>
     * Transport: prefer in-process mask, then plane files (FGS can lose static
     * mask after process recycle). If both unknown, still try BT then USB so
     * exclusive Specials are not dropped silently (report: symbols on phone only).
     */
    public static void drainRemoteQueue(Context ctx) {
        boolean bt = useBt()
            || "1".equals(readLocal(ctx, BT_EN))
            || planeOnTmp(BT_EN);
        boolean usb = useUsb()
            || "1".equals(readLocal(ctx, USB_EN))
            || planeOnTmp(USB_EN);
        if (!bt && !usb) {
            usb = planeOnTmp(USB_EN) || planeOnTmp(SESSION);
            bt = !usb;
            if (!bt && !usb) bt = true;
        } else if (bt && usb) {
            if (planeOnTmp(SESSION) || planeOnTmp("titan2_usb_hid_grab")) bt = false;
            else usb = false;
        }
        // 1.74: tmp first (Controls hostRemoteOnly world path). CE-first drained
        // stale app-private leftovers and cleared tmp (fresh Specials lost / dual).
        // 1.91 B2 residual: include legacy titan2_hid_remote_q in the primary
        // drain list. Pre-1.67 only cleared legacy after a canonical hit — if
        // writers still appended only the alias, Specials never reached host
        // (exclusive remote_q feel dead) while plane looked healthy.
        File[] paths = new File[]{
            new File("/data/local/tmp/" + REMOTE_Q),
            new File("/data/local/tmp/titan2_hid_remote_q"),
            new File("/data/misc/titan2/" + REMOTE_Q),
            new File("/data/misc/titan2/titan2_hid_remote_q"),
            new File(ctx.getFilesDir(), REMOTE_Q),
            new File("/data/user/0/com.titanus2.usbhid/files/" + REMOTE_Q),
        };
        // Drain first non-empty queue only — stop after first payload (1.65)
        for (File f : paths) {
            byte[] all = readAndClearQueueFile(f);
            if (all == null || all.length < 4) continue;
            if (bt) emitQueueRecordsBt(all);
            else if (usb) emitQueueRecordsUsb(all);
            // clear remaining path copies without re-emit
            for (File g : paths) {
                if (g.equals(f)) continue;
                try { readAndClearQueueFile(g); } catch (Exception ignored) {}
            }
            break;
        }
    }

    private static byte[] readAndClearQueueFile(File f) {
        try {
            if (f == null || !f.isFile() || f.length() < 4) return null;
            byte[] all = java.nio.file.Files.readAllBytes(f.toPath());
            try (FileOutputStream fos = new FileOutputStream(f, false)) { /* empty */ }
            //noinspection ResultOfMethodCallIgnored
            f.setReadable(true, false);
            //noinspection ResultOfMethodCallIgnored
            f.setWritable(true, false);
            return all;
        } catch (Exception e) {
            return null;
        }
    }

    private static void emitQueueRecordsBt(byte[] all) {
        try {
            drainMouseMailbox();
            BluetoothHidClient bt = BluetoothHidClient.get();
            for (int off = 0; off + 4 <= all.length; off += 4) {
                int t = all[off] & 0xff;
                if (t == 0x02 || t == 0x04) continue;
                byte[] rec = new byte[4];
                System.arraycopy(all, off, rec, 0, 4);
                bt.handlePacket(rec);
            }
        } catch (Exception e) {
            android.util.Log.w("HidControl", "emitQueueBt: " + e.getMessage());
        }
    }

    private static void emitQueueRecordsUsb(byte[] all) {
        try {
            for (int off = 0; off + 4 <= all.length; off += 4) {
                byte[] rec = new byte[4];
                System.arraycopy(all, off, rec, 0, 4);
                send(rec);
            }
        } catch (Exception ignored) {}
    }

    private static final File[] MOUSE_MBX_PATHS = new File[]{
        new File("/data/user/0/com.titanus2.usbhid/files/titan2_hid_mouse.mbx"),
        new File("/data/misc/titan2/titan2_hid_mouse.mbx"),
        new File("/data/local/tmp/titan2_hid_mouse.mbx"),
    };

    /**
     * Latest-wins mouse mailbox (native writer). One take → at most one
     * int8 report. Never replay a second of history (Neckband lag symptom).
     * Prefer app-private path — /data/local/tmp is often SELinux-blocked for priv-app.
     */
    private static void drainMouseMailbox() {
        // QA typing lock: freeze host mouse while user is typing on phone
        if (isPadCursorPaused()) return;
        /* Shared with sock thread — multi-report emit, no clamp-drop teleport */
        BtMouseSock.pollMailbox();
    }

    /**
     * Typing lock plane — same SoT as Controls {@code TypingCursorLock}
     * ({@link com.titanus2.api.Titan2ApiContract#FILE_PAD_CURSOR_PAUSE}).
     * Prefer {@link com.titanus2.api.InputPlane#isCursorPaused} when Application
     * is available; file/Global fallback for early boot.
     */
    private static boolean isPadCursorPaused() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object cur = at.getMethod("currentApplication").invoke(null);
            if (cur instanceof Context) {
                if (com.titanus2.api.InputPlane.isCursorPaused((Context) cur)) return true;
            }
        } catch (Throwable ignored) {}
        try {
            for (File f : new File[]{
                new File("/data/local/tmp/titan2_pad_cursor_pause"),
                new File(OS_CTRL, "titan2_pad_cursor_pause"),
            }) {
                if (!f.isFile() || f.length() < 1) continue;
                String v = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8).trim();
                if ("1".equals(v) || "true".equalsIgnoreCase(v)) return true;
            }
        } catch (Exception ignored) {}
        // Global fallback when SELinux denies cross-app tmp (Controls 11.38+)
        try {
            Context app = null;
            try {
                Class<?> at = Class.forName("android.app.ActivityThread");
                Object cur = at.getMethod("currentApplication").invoke(null);
                if (cur instanceof Context) app = (Context) cur;
            } catch (Throwable ignored) {}
            if (app != null) {
                String g = android.provider.Settings.Global.getString(
                    app.getContentResolver(), "titan2_pad_cursor_pause");
                if (g != null) {
                    g = g.trim();
                    if ("1".equals(g) || "true".equalsIgnoreCase(g)) return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static void drainFileToBt(File f) {
        /* Mouse first via mailbox (no FIFO). */
        drainMouseMailbox();
        try {
            if (f == null || !f.isFile() || f.length() < 4) return;
            byte[] all = java.nio.file.Files.readAllBytes(f.toPath());
            try (FileOutputStream fos = new FileOutputStream(f, false)) { /* empty */ }
            //noinspection ResultOfMethodCallIgnored
            f.setReadable(true, false);
            //noinspection ResultOfMethodCallIgnored
            f.setWritable(true, false);
            BluetoothHidClient bt = BluetoothHidClient.get();
            /* hw.out is keys-only for BT now; discard residual mouse FIFO */
            for (int off = 0; off + 4 <= all.length; off += 4) {
                int t = all[off] & 0xff;
                if (t == 0x02 || t == 0x04) continue;
                byte[] rec = new byte[4];
                System.arraycopy(all, off, rec, 0, 4);
                bt.handlePacket(rec);
            }
        } catch (Exception e) {
            android.util.Log.w("HidControl", "drain " + f + ": " + e.getMessage());
        }
    }

    private static void drainFileToUsb(File f) {
        try {
            if (f == null || !f.isFile() || f.length() < 4) return;
            byte[] all = java.nio.file.Files.readAllBytes(f.toPath());
            try (FileOutputStream fos = new FileOutputStream(f, false)) { /* empty */ }
            for (int off = 0; off + 4 <= all.length; off += 4) {
                byte[] rec = new byte[4];
                System.arraycopy(all, off, rec, 0, 4);
                send(rec);
            }
        } catch (Exception ignored) {}
    }

    /**
     * Deliver one 4-byte soft-inject record to hid_bridge via unix DGRAM.
     * Prefer abstract {@code @titan2_hid} (no filesystem SELinux); fall back to
     * {@code /data/local/tmp/titan2_hid.sock}. Keeps sockets open across moves.
     */
    private static boolean sendSock(byte[] rec) {
        synchronized (SOCK_LOCK) {
            if (writeSockCached(true, rec)) return true;
            if (writeSockCached(false, rec)) return true;
            return false;
        }
    }

    private static boolean writeSockCached(boolean abstractNs, byte[] rec) {
        LocalSocket s = abstractNs ? sockAbs : sockFs;
        if (s != null) {
            try {
                OutputStream out = s.getOutputStream();
                out.write(rec);
                out.flush();
                return true;
            } catch (Exception e) {
                closeSock(abstractNs);
            }
        }
        s = openSock(abstractNs);
        if (s == null) return false;
        if (abstractNs) sockAbs = s; else sockFs = s;
        try {
            OutputStream out = s.getOutputStream();
            out.write(rec);
            out.flush();
            return true;
        } catch (Exception e) {
            closeSock(abstractNs);
            return false;
        }
    }

    private static LocalSocket openSock(boolean abstractNs) {
        try {
            LocalSocket s = new LocalSocket(LocalSocket.SOCKET_DGRAM);
            LocalSocketAddress addr = abstractNs
                ? new LocalSocketAddress(INJ_SOCK_ABS, LocalSocketAddress.Namespace.ABSTRACT)
                : new LocalSocketAddress(INJ_SOCK_FS, LocalSocketAddress.Namespace.FILESYSTEM);
            s.connect(addr);
            return s;
        } catch (Exception e) {
            return null;
        }
    }

    private static void closeSock(boolean abstractNs) {
        try {
            LocalSocket s = abstractNs ? sockAbs : sockFs;
            if (s != null) s.close();
        } catch (Exception ignored) {}
        if (abstractNs) sockAbs = null; else sockFs = null;
    }

    /**
     * App-private inject only — no fsync, no SELinux-denied OS/tmp paths.
     * (Writing /data/misc and /data/local/tmp from the app floods audit and
     * stalls the UI; hid_bridge prefers app files + abstract socket.)
     */
    private static boolean sendAppInj(byte[] rec) {
        File[] files = INJ_FILES.get();
        if (files == null || files.length == 0) return false;
        for (File f : files) {
            if (f == null) continue;
            String p = f.getAbsolutePath();
            // Skip shared planes that always AVC-deny for priv_app
            if (p.contains("/data/misc/") || p.contains("/data/local/tmp")
                    || p.contains("/data/adb/")) {
                continue;
            }
            try (FileOutputStream fos = new FileOutputStream(f, true)) {
                fos.write(rec);
                // no fsync — bridge polls; sync made every mouse move multi-ms
                return true;
            } catch (Exception ignored) {}
        }
        return false;
    }

    /** Rare recovery path only — never call from {@link #send} hot path. */
    private static boolean sendSuInj(byte[] rec) {
        if (!Root.available()) return false;
        StringBuilder hex = new StringBuilder(rec.length * 4);
        for (byte b : rec) {
            hex.append(String.format(Locale.US, "\\x%02x", b & 0xff));
        }
        return Root.runSu(
            "printf '" + hex + "' >> /data/local/tmp/titan2_hid.inj; " +
            "printf '" + hex + "' >> " + OS_CTRL + "/titan2_hid.inj; " +
            "printf '" + hex + "' >> /data/user/0/com.titanus2.usbhid/files/titan2_hid.inj; " +
            "chmod 666 /data/local/tmp/titan2_hid.inj " + OS_CTRL + "/titan2_hid.inj " +
            "/data/user/0/com.titanus2.usbhid/files/titan2_hid.inj 2>/dev/null; true"
        );
    }

    public static boolean key(int hidUsage, boolean press) {
        return key(0, hidUsage, press);
    }

    /** Soft HID key with modifier byte (bit0=LCtrl … bit1=LShift …). */
    public static boolean key(int mod, int hidUsage, boolean press) {
        byte[] rec = new byte[]{
            0x01,
            (byte) (mod & 0xff),
            (byte) (hidUsage & 0xff),
            (byte) (press ? 1 : 0)
        };
        boolean ok = send(rec);
        // B2 exclusive Specials: if BT/USB not ready yet, still queue for FGS drain
        // so hostRemoteOnly + keyTap dual-path does not drop symbols.
        // 1.94: only enqueue when a sink is live. Session-off enqueue left remote_q
        // residual that re-emitted on next exclusive Start (dual host glyphs).
        if (!ok) {
            boolean live = softCompose
                || isSessionLikelyOn()
                || HidSessionService.isRunning();
            if (live) {
                enqueueKeyRecord(rec);
                ok = true;
            }
        }
        return ok;
    }

    /** Append 4-byte keyboard record to ONE drain queue (no fan-out multi-glyph). */
    private static void enqueueKeyRecord(byte[] rec) {
        if (rec == null || rec.length < 4) return;
        // 1.64: single path only — FGS drains every non-empty queue; writing N
        // copies of the same record = N host keypresses.
        java.util.ArrayList<String> pathList = new java.util.ArrayList<>();
        try {
            Context app = HidAppContext.get();
            if (app != null && app.getFilesDir() != null) {
                pathList.add(new File(app.getFilesDir(), REMOTE_Q).getAbsolutePath());
            }
        } catch (Exception ignored) {}
        pathList.add("/data/local/tmp/" + REMOTE_Q);
        pathList.add("/data/misc/titan2/" + REMOTE_Q);
        for (String path : pathList) {
            try {
                File f = new File(path);
                seedQueueFile(f);
                try (FileOutputStream out = new FileOutputStream(f, true)) {
                    out.write(rec, 0, 4);
                }
                return; // first success only
            } catch (Exception ignored) {}
        }
    }

    public static boolean keyTap(int hidUsage) {
        return keyTap(0, hidUsage);
    }

    public static boolean keyTap(int mod, int hidUsage) {
        // 1.86: do NOT clear softCompose here. typeText/keyTap is the Type inject
        // path — clearing softCompose mid-payload let FGS reassert keys=1 and
        // dual-fire phys TitanKey + soft inject (multi keys on host / in field).
        // Exclusive Specials clear softCompose in HidSessionService / HostMouseReceiver.
        // Hold/gap from type-speed only — old min 16/10 made Inject feel lagged.
        // Floor 8/3 keeps Shift+letter order on Snapdragon without ~30ms/key tax.
        int hold = Math.max(8, keyHoldMs());
        int gap = Math.max(3, keyGapMs());
        final int m = mod & 0xff;
        boolean a = key(m, hidUsage, true);
        try { Thread.sleep(hold); } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        // Release key; clear soft modifiers via e0–e7 so sticky phys mods and
        // soft Shift/Ctrl/Alt never stick across characters (BT applyKey keeps
        // sticky mods when packet mod byte is 0).
        boolean b = key(0, hidUsage, false);
        if ((m & 0x01) != 0) key(0, 0xe0, false); // LCtrl
        if ((m & 0x02) != 0) key(0, 0xe1, false); // LShift
        if ((m & 0x04) != 0) key(0, 0xe2, false); // LAlt
        if ((m & 0x08) != 0) key(0, 0xe3, false); // LGUI
        if ((m & 0x10) != 0) key(0, 0xe4, false); // RCtrl
        if ((m & 0x20) != 0) key(0, 0xe5, false); // RShift
        if ((m & 0x40) != 0) key(0, 0xe6, false); // RAlt
        if ((m & 0x80) != 0) key(0, 0xe7, false); // RGUI
        try { Thread.sleep(gap); } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        return a && b;
    }

    /**
     * Clear stuck keyboard state on BT (and a no-op-safe USB all-up) after
     * Type inject or leaving the Type tab. Does not change session plane.
     */
    public static void releaseAllKeys() {
        try { BluetoothHidClient.get().releaseAllKeys(); } catch (Exception ignored) {}
        // USB bridge: synthetic all-keys-up (usage 0, release)
        try { key(0, 0, false); } catch (Exception ignored) {}
    }

    /**
     * B6 1.98: before session plane goes to 0, push pure mouse button-up on
     * the USB inject path (sock / app-private inj). BT is handled by
     * {@link BluetoothHidClient} force release; this covers USB-only and
     * dual-path residual when gadget still accepts reports.
     */
    public static void releaseHostInputBeforePlaneDown() {
        try { releaseAllKeys(); } catch (Exception ignored) {}
        try {
            // Absolute buttons=0 packet (0x03) — pure release, no motion.
            mouseButtons(0);
        } catch (Exception ignored) {}
        try {
            // Also emit motion report with buttons=0 (0x02) for hosts that only
            // edge on the motion report descriptor path.
            mouseMove(0, 0, 0);
        } catch (Exception ignored) {}
    }

    /** Key-down hold at current type speed (min 8 ms so hosts still see press). */
    private static int keyHoldMs() {
        int pct = typeSpeedPct;
        if (pct < 25) pct = 25;
        int ms = (BASE_KEY_HOLD_MS * 100 + pct / 2) / pct;
        // Align soft type with product key-repeat period when set (shared SoT)
        try {
            Context app = appContext();
            if (app != null && com.titanus2.api.KeyInputTiming.isKeyRepeatEnabled(app)) {
                int d = com.titanus2.api.KeyInputTiming.keyRepeatDelayMs(app);
                // hold slightly under period so host sees clean edges
                ms = Math.min(ms, Math.max(8, d / 2));
            }
        } catch (Throwable ignored) {}
        return Math.max(8, ms);
    }

    /** Inter-key gap at current type speed. */
    private static int keyGapMs() {
        int pct = typeSpeedPct;
        if (pct < 25) pct = 25;
        int ms = (BASE_KEY_GAP_MS * 100 + pct / 2) / pct;
        try {
            Context app = appContext();
            if (app != null && com.titanus2.api.KeyInputTiming.isKeyRepeatEnabled(app)) {
                int d = com.titanus2.api.KeyInputTiming.keyRepeatDelayMs(app);
                ms = Math.min(ms, Math.max(2, d / 2));
            }
        } catch (Throwable ignored) {}
        return Math.max(2, ms);
    }

    private static Context appContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object cur = at.getMethod("currentApplication").invoke(null);
            if (cur instanceof Context) return (Context) cur;
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Type a string as HID key events (US layout). Used by payload mode.
     * Unknown chars are skipped. Returns count of characters sent.
     * <p>
     * 1.86: keep softCompose true for the whole payload so phys keys stay off
     * (Type inject field + host single-owner soft stream).
     */
    public static int typeText(String text) {
        if (text == null || text.isEmpty()) return 0;
        boolean prevSoft = softCompose;
        try {
            setSoftCompose(true);
            int n = 0;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                int[] ku = charToKey(c);
                if (ku == null) continue;
                if (keyTap(ku[0], ku[1])) {
                    n++;
                    // Keep keyboard backlight alive during long payloads
                    if ((n % 3) == 0) KeyLedClient.bumpActivity();
                }
            }
            if (n > 0) KeyLedClient.bumpActivity();
            return n;
        } finally {
            // Restore caller's softCompose (Send path parks Type after)
            setSoftCompose(prevSoft);
        }
    }

    /**
     * US keyboard: [mod, usage] or null if unsupported.
     * mod bit1 = Left Shift.
     */
    public static int[] charToKey(char c) {
        final int SH = 0x02;
        if (c >= 'a' && c <= 'z') return new int[]{0, 0x04 + (c - 'a')};
        if (c >= 'A' && c <= 'Z') return new int[]{SH, 0x04 + (c - 'A')};
        if (c >= '1' && c <= '9') return new int[]{0, 0x1e + (c - '1')};
        if (c == '0') return new int[]{0, 0x27};
        switch (c) {
            case ' ': return new int[]{0, 0x2c};
            case '\n': case '\r': return new int[]{0, 0x28};
            case '\t': return new int[]{0, 0x2b};
            case '-': return new int[]{0, 0x2d};
            case '=': return new int[]{0, 0x2e};
            case '[': return new int[]{0, 0x2f};
            case ']': return new int[]{0, 0x30};
            case '\\': return new int[]{0, 0x31};
            case ';': return new int[]{0, 0x33};
            case '\'': return new int[]{0, 0x34};
            case '`': return new int[]{0, 0x35};
            case ',': return new int[]{0, 0x36};
            case '.': return new int[]{0, 0x37};
            case '/': return new int[]{0, 0x38};
            case '!': return new int[]{SH, 0x1e};
            case '@': return new int[]{SH, 0x1f};
            case '#': return new int[]{SH, 0x20};
            case '$': return new int[]{SH, 0x21};
            case '%': return new int[]{SH, 0x22};
            case '^': return new int[]{SH, 0x23};
            case '&': return new int[]{SH, 0x24};
            case '*': return new int[]{SH, 0x25};
            case '(': return new int[]{SH, 0x26};
            case ')': return new int[]{SH, 0x27};
            case '_': return new int[]{SH, 0x2d};
            case '+': return new int[]{SH, 0x2e};
            case '{': return new int[]{SH, 0x2f};
            case '}': return new int[]{SH, 0x30};
            case '|': return new int[]{SH, 0x31};
            case ':': return new int[]{SH, 0x33};
            case '"': return new int[]{SH, 0x34};
            case '~': return new int[]{SH, 0x35};
            case '<': return new int[]{SH, 0x36};
            case '>': return new int[]{SH, 0x37};
            case '?': return new int[]{SH, 0x38};
            default: return null;
        }
    }

    public static boolean mouseMove(int dx, int dy, int buttons) {
        return send(new byte[]{
            0x02,
            (byte) clamp(dx),
            (byte) clamp(dy),
            (byte) (buttons & 0xff)
        });
    }

    public static boolean mouseButtons(int buttons) {
        return send(new byte[]{0x03, (byte) (buttons & 0xff), 0, 0});
    }

    /**
     * Host mouse wheel (report type 0x04 — same as SoftPadView 2-finger scroll).
     * Positive notches = scroll up / away; negative = scroll down.
     */
    public static boolean mouseWheel(int notches) {
        if (notches == 0) return true;
        int n = clamp(notches);
        return send(new byte[]{0x04, (byte) n, 0, 0});
    }

    private static int clamp(int v) {
        if (v > 127) return 127;
        if (v < -127) return -127;
        return v;
    }
}
