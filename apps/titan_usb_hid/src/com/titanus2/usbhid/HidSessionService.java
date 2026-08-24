package com.titanus2.usbhid;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.widget.RemoteViews;

/**
 * Owns HID session (USB and/or Bluetooth) while backgrounded / screen off.
 * Notification Stop ends the session.
 * <p>
 * FB-HID-3: when Screen off OK is armed, holds PARTIAL_WAKE_LOCK, keeps FGS
 * on task swipe, and reasserts plane + BT on {@code ACTION_SCREEN_OFF}.
 */
public class HidSessionService extends Service {
    public static final String ACTION_START = "com.titanus2.usbhid.START";
    public static final String ACTION_STOP = "com.titanus2.usbhid.STOP";
    public static final String ACTION_UPDATE = "com.titanus2.usbhid.UPDATE";
    /** Drop session/bridge without pad restore — Type tab compose (phys idle). */
    public static final String ACTION_PARK = "com.titanus2.usbhid.PARK";
    /** Immediate remote_q / hw.out drain (layout specials hostRemoteOnly). */
    public static final String ACTION_DRAIN = "com.titanus2.usbhid.DRAIN";
    /** Controls published host_layout — re-seed keys_pause + drain specials. */
    public static final String ACTION_LAYOUT_PLANE = "com.titanus2.controls.action.LAYOUT_PLANE";
    public static final String EXTRA_MOUSE = "mouse";
    public static final String EXTRA_GRAB = "grab";
    public static final String EXTRA_KEYS = "keys";
    public static final String EXTRA_TRANSPORT = "transport";
    public static final String EXTRA_SCREEN_OFF = "screen_off";
    private static final String CH = "usb_hid_session";
    /** Post-1.54 colorized-black channel (deleted). */
    private static final String CH_V2 = "usb_hid_session_v2";
    /**
     * Current FGS channel. Must not be IMPORTANCE_LOW/MIN alone in Silent section —
     * LOS SystemUI draws Silent rows with a white app-icon square (ugly).
     * DEFAULT + silent/no-sound keeps a normal cyan-circle row.
     */
    private static final String CH_V3 = "usb_hid_session_v3";
    private static final int NOTIF_ID = 42;
    private PowerManager.WakeLock wake;
    private static volatile boolean running;
    private static volatile boolean ending;
    /** Last applied redirect plane — skip no-op UPDATE (Type open jank). */
    private static volatile boolean appliedMouse = true;
    private static volatile boolean appliedGrab = true;
    private static volatile boolean appliedKeys = true;
    private static volatile int appliedTransport = -1;
    private boolean grabMode = true;
    private boolean mouseMode = true;
    private boolean keysMode = true;
    private int transport = HidControl.TRANSPORT_USB;
    private boolean screenOffOk = false;
    /** FB-HID-3: reassert session when display turns off / on. */
    private boolean screenReceiverRegistered = false;
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null || !running || ending) return;
            String a = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(a)) {
                onDisplayOff();
            } else if (Intent.ACTION_SCREEN_ON.equals(a)
                    || Intent.ACTION_USER_PRESENT.equals(a)) {
                onDisplayOn();
            }
        }
    };
    /** Debounced: phone text field / IME active → pause TitanKey host redirect */
    private boolean localInputPaused;
    private int localInputHits;
    private int localInputMisses;
    private final Handler h = new Handler(Looper.getMainLooper());
    /** Dedicated looper for HID drain — keep main thread free (Snapdragon lag). */
    private HandlerThread drainThread;
    private Handler drainHandler;
    /** Immediate mouse path from hid_bridge (abstract DGRAM). */
    private BtMouseSock mouseSock;
    /**
     * True while exclusive keys were forced off for host-layout (not soft Type).
     * Drain loop heals keys back on when layout plane goes off — report 17.05
     * needed layout-off + BT reconnect because keys=0 stayed stuck.
     */
    private boolean layoutForcedKeysOff;

    private final Runnable hwDrain = new Runnable() {
        @Override public void run() {
            if (!running || ending) return;
            // Outside try so interval scheduling can use it after catch.
            boolean layoutKeys = false;
            try {
                // FGS live ⇒ session plane must be 1 (share + exclusive + Type).
                // Controls heal / stale Global can write session=0 while the
                // notification session is still up → bridge stops, pad/keys
                // stick on phone, Specials dual-type.
                reassertSessionPlaneIfLive();
                // B2 exclusive + Specials: keep keys plane in sync with Controls
                // layout (pause → keys=0; layout off → keys=1 without reconnect).
                reconcileLayoutKeysPause();
                // Layout specials (hostRemoteOnly) enqueue titan2_remote_hid.q and
                // titan2_hid_hw.out while exclusive keys=0. Drain both queues on
                // **USB and BT** (B2) — hw.out used to be BT-only.
                // Always drain remote_q when layout keys are paused (exclusive
                // specials), even if softCompose was left sticky — dual-type fix.
                // Also drain whenever exclusive grab is live: session plane can
                // glitch empty while Controls still queues host specials.
                layoutKeys = HidControl.isHostLayoutKeysPaused(
                    HidSessionService.this);
                // 1.86: Type soft inject (keys=mouse=grab=0) must keep softCompose.
                // Clearing softCompose mid-Type let reassertPhysContract arm keys=1
                // while user typed in inject field → multi keys on host.
                boolean typeSoftOnly = !grabMode && !mouseMode && !keysMode
                    && HidControl.isSoftCompose();
                // Exclusive Specials: clear sticky Type softCompose so remote_q drains.
                // Never clear when already on intentional Type soft plane.
                if (!typeSoftOnly && grabMode && HidControl.isSoftCompose()) {
                    HidControl.setSoftCompose(false);
                }
                if (!typeSoftOnly && layoutKeys && HidControl.isSoftCompose()) {
                    HidControl.setSoftCompose(false);
                }
                if (!HidControl.isSoftCompose()) {
                    HidControl.drainHwOut(HidSessionService.this);
                }
                // Always drain specials queue when exclusive or layout-paused
                // Type soft still drains remote_q so Send inject is not stalled
                if (grabMode || layoutKeys || !HidControl.isSoftCompose()
                        || typeSoftOnly) {
                    HidControl.drainRemoteQueue(HidSessionService.this);
                }
                // Exclusive: do NOT re-arm wireless ADB here — that used to write
                // enable_wireless_adb every few seconds → pad-agent stop/start
                // adbd → reconnect thrash + Magisk su spam. Armed once at START.
                if (grabMode) {
                    // Magisk service used to set local_input=1 on exclusive IME
                    // focus — clear every drain so host keys never go phone-only.
                    if (HidControl.isLocalInputPaused(HidSessionService.this)) {
                        try {
                            HidControl.setLocalInputPause(HidSessionService.this, false);
                        } catch (Exception ignored) {}
                    }
                }
            } catch (Exception ignored) {}
            // Soft Type (inject): keep drain hot — 250ms made specials/queue lag.
            // BT mouse: 4 ms. USB-only: 12 ms.
            // B2 1.10: exclusive Specials (keys_pause) drain hotter so first
            // host glyph does not wait a full USB period after hold arm.
            int ms;
            if (HidControl.isSoftCompose()) ms = 16;
            else if (layoutKeys) ms = HidControl.useBt() ? 3 : 6;
            else if (HidControl.useBt()) ms = 4;
            else ms = 12;
            Handler dh = drainHandler;
            if (dh != null) dh.postDelayed(this, ms);
        }
    };

    private long lastPhysReassertMs;

    /**
     * While FGS owns a phys session, heal plane if Controls/softCompose raced
     * it to session=1 + grab/keys/mouse=0. Throttled — bridge restarts on every
     * grab/keys/mouse edge; do not write every drain tick.
     */
    private void reassertSessionPlaneIfLive() {
        if (!running || ending) return;
        // 1.86 Soft Type: never reassert phys keys/grab — dual-fires with inject field
        if (HidControl.isSoftCompose()) return;
        if (!grabMode && !mouseMode && !keysMode) return;
        long now = android.os.SystemClock.elapsedRealtime();
        // 1.89: 2.5s reassert — enough to heal plane without drain-path write thrash
        if (now - lastPhysReassertMs < 2500L) return;
        try {
            String s = HidControl.readSessionPlane(this);
            boolean sessOk = "1".equals(s) || "true".equalsIgnoreCase(s);
            boolean grabOk = !grabMode || HidControl.isGrabPlaneExplicit(this);
            boolean mouseOk = !mouseMode
                || "1".equals(HidControl.readPlaneValue(this, HidControl.MOUSE));
            boolean pause = HidControl.isHostLayoutKeysPaused(this);
            // Exclusive + layout off must never leave keys=0 (Specials release
            // race left HW keyboard dead while grab/mouse stayed 1).
            if (grabMode && !pause && !"1".equals(HidControl.readKeysPlane(this))) {
                keysMode = true;
            }
            boolean keysOk = !keysMode || pause
                || "1".equals(HidControl.readKeysPlane(this));
            if (sessOk && grabOk && mouseOk && keysOk) return;
            lastPhysReassertMs = now;
            HidControl.reassertPhysContract(this, grabMode, mouseMode, keysMode);
        } catch (Exception ignored) {}
    }

    /**
     * Exclusive HID: when Controls toggles Specials/Arrows, phys keys must drop
     * so a11y remaps host specials; when layout goes off, restore keys=1 so the
     * host keyboard works again without leaving the HID app / re-pairing BT.
     * Never touches mouse/grab (trackpad stays live).
     */
    private void reconcileLayoutKeysPause() {
        // Exclusive + Specials: clear sticky softCompose so keys_pause can apply
        if (grabMode && HidControl.isSoftCompose()
                && HidControl.isHostLayoutKeysPaused(this)) {
            HidControl.setSoftCompose(false);
        }
        if (HidControl.isSoftCompose() || !grabMode) {
            layoutForcedKeysOff = false;
            return;
        }
        boolean wantPause = HidControl.isHostLayoutKeysPaused(this);
        if (wantPause) {
            layoutForcedKeysOff = true;
            // B2 1.08: layout armed mid-session — re-seed world-writable queues
            // so Controls hostRemoteOnly can append before the first glyph.
            try { HidControl.ensureSpecialsQueues(this); } catch (Exception ignored) {}
            // B2 1.40: exclusive Specials must never leave local_input phone pause
            try {
                if (HidControl.isLocalInputPaused(this)) {
                    HidControl.setLocalInputPause(this, false);
                }
            } catch (Exception ignored) {}
            if (keysMode) {
                keysMode = false;
                appliedKeys = false;
            }
            String k = HidControl.readKeysPlane(this);
            if (!"0".equals(k)) {
                try { HidControl.writePlaneKeys(this, false); } catch (Exception ignored) {}
            }
            return;
        }
        // Layout off: heal keys if we paused them, or plane stuck at 0 under excl
        if (!layoutForcedKeysOff && keysMode) return;
        if (HidControl.isExclusiveLayoutActive(this)) return;
        String pause = HidControl.readKeysPausePlane(this);
        if ("1".equals(pause) || "true".equalsIgnoreCase(pause)) return;
        // 1.90 B2: layout off + keys_pause clear → drop inject residual and
        // flush remote_q so next exclusive letter is not a leftover Specials glyph.
        try { HidControl.clearHostLayoutKeysPause(this); } catch (Exception ignored) {}
        try { HidControl.flushSpecialsQueues(this); } catch (Exception ignored) {}
        keysMode = true;
        layoutForcedKeysOff = false;
        appliedKeys = true;
        try { HidControl.writePlaneKeys(this, true); } catch (Exception ignored) {}
        // 1.76: do NOT requestPadRegrab here — that restarts touchpadd and
        // drops host/Moonlight mouse every Specials/Sym release (disconnect thrash).
        // Keys plane alone is enough; pad/mouse stays live across keys_pause.
    }
    private final Runnable notifTick = new Runnable() {
        @Override public void run() {
            if (!running || ending) return;
            // Soft compose: do not rebuild notifications (IME was already lagging)
            if (!HidControl.isSoftCompose()) notifySession();
            h.postDelayed(this, HidControl.isSoftCompose() ? 8000 : 3000);
        }
    };
    /**
     * Share mode only: yield TitanKey to Android when a phone editor is focused.
     * Pad stays guest. Exclusive must never arm {@code local_input}.
     * <p><b>Never</b> run dumpsys on the main thread while Type is open —
     * that locked IME binder for ~1.1s every poll (logcat Davey/Skipped frames).
     */
    private final java.util.concurrent.ExecutorService localPollExec =
        java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "titan-hid-local-in");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
    private final Runnable localInputTick = new Runnable() {
        @Override public void run() {
            if (!running || ending) return;
            // Exclusive: force clear sticky local_input (Magisk/app races) and
            // skip the IME probe entirely — host owns keys+pad.
            if (grabMode) {
                clearLocalInputPauseIfSet();
                h.postDelayed(this, 1500);
                return;
            }
            // Soft Type / soft plane: zero work — IME must stay responsive
            if (HidControl.isSoftCompose() || (!keysMode && !mouseMode)) {
                if (localInputPaused) {
                    localInputPaused = false;
                    localInputHits = 0;
                    localInputMisses = 0;
                    try { HidControl.setLocalInputPause(HidSessionService.this, false); }
                    catch (Exception ignored) {}
                }
                h.postDelayed(this, 2000);
                return;
            }
            localPollExec.execute(() -> {
                if (!running || ending || HidControl.isSoftCompose() || grabMode) return;
                boolean active;
                try {
                    active = LocalInputGuard.isLocalTextInputActive(HidSessionService.this);
                } catch (Exception e) {
                    active = false;
                }
                final boolean a = active;
                h.post(() -> applyLocalInputSample(a));
            });
            h.postDelayed(this, 800);
        }
    };

    /** Exclusive / soft: never leave local_input=1 (bridge would drop host keys). */
    private void clearLocalInputPauseIfSet() {
        if (localInputPaused || HidControl.isLocalInputPaused(this)) {
            localInputPaused = false;
            localInputHits = 0;
            localInputMisses = 0;
            try { HidControl.setLocalInputPause(this, false); } catch (Exception ignored) {}
        }
    }

    private void applyLocalInputSample(boolean active) {
        if (!running || ending || HidControl.isSoftCompose()) return;
        // Exclusive never pauses for phone editors (share-only product rule).
        if (grabMode) {
            clearLocalInputPauseIfSet();
            return;
        }
        if (!keysMode && !mouseMode) return;
        if (active) {
            localInputHits++;
            localInputMisses = 0;
            if (!localInputPaused && localInputHits >= 1) {
                localInputPaused = true;
                HidControl.setLocalInputPause(this, true);
                notifySession();
            }
        } else {
            localInputMisses++;
            localInputHits = 0;
            if (localInputPaused && localInputMisses >= 2) {
                localInputPaused = false;
                HidControl.setLocalInputPause(this, false);
                notifySession();
            }
        }
    }

    public static boolean isRunning() { return running && !ending; }

    /** Share hub: session live, not exclusive, phys pad/keys (not Type soft). */
    public static boolean isShareRouting() {
        return running && !ending && !appliedGrab && (appliedKeys || appliedMouse);
    }

    /** Phys HID session (share or exclusive). Type soft is not live. */
    public static boolean isPhysLive() {
        return running && !ending && (appliedGrab || appliedKeys || appliedMouse);
    }

    public static void start(Context ctx, boolean mouse, boolean grab,
                             int transport, boolean screenOff) {
        start(ctx, mouse, grab, true, transport, screenOff);
    }

    public static void start(Context ctx, boolean mouse, boolean grab, boolean keys,
                             int transport, boolean screenOff) {
        Intent i = new Intent(ctx, HidSessionService.class);
        i.setAction(ACTION_START);
        i.putExtra(EXTRA_MOUSE, mouse);
        i.putExtra(EXTRA_GRAB, grab);
        i.putExtra(EXTRA_KEYS, keys);
        i.putExtra(EXTRA_TRANSPORT, transport);
        i.putExtra(EXTRA_SCREEN_OFF, screenOff);
        try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (Exception e) {
            HidControl.setTransport(transport);
            HidControl.setScreenOffOk(ctx, screenOff);
            HidControl.setSession(ctx, true, mouse, grab, keys,
                (transport & HidControl.TRANSPORT_USB) != 0,
                (transport & HidControl.TRANSPORT_BT) != 0);
        }
    }

    public static void start(Context ctx, boolean mouse, boolean grab) {
        SharedPreferences p = ctx.getSharedPreferences("usb_hid", MODE_PRIVATE);
        int t = p.getInt("transport", HidControl.TRANSPORT_USB);
        // BT sessions default screen_off=true so swipe does not kill FGS.
        // Do not overwrite an explicit user false once persisted.
        boolean soDefault = (t & HidControl.TRANSPORT_BT) != 0;
        boolean so = p.contains("screen_off") ? p.getBoolean("screen_off", soDefault) : soDefault;
        start(ctx, mouse, grab, true, t, so);
    }

    public static void update(Context ctx, boolean mouse, boolean grab,
                              int transport, boolean screenOff) {
        update(ctx, mouse, grab, true, transport, screenOff);
    }

    public static void update(Context ctx, boolean mouse, boolean grab, boolean keys,
                              int transport, boolean screenOff) {
        // Already on this plane — do not bounce the service (session-on Type lag)
        if (running && !ending
                && appliedMouse == mouse
                && appliedGrab == grab
                && appliedKeys == keys
                && appliedTransport == transport) {
            return;
        }
        Intent i = new Intent(ctx, HidSessionService.class);
        i.setAction(ACTION_UPDATE);
        i.putExtra(EXTRA_MOUSE, mouse);
        i.putExtra(EXTRA_GRAB, grab);
        i.putExtra(EXTRA_KEYS, keys);
        i.putExtra(EXTRA_TRANSPORT, transport);
        i.putExtra(EXTRA_SCREEN_OFF, screenOff);
        try {
            if (Build.VERSION.SDK_INT >= 26 && !running) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (Exception e) {
            HidControl.setTransport(transport);
            HidControl.setSession(ctx, true, mouse, grab, keys,
                (transport & HidControl.TRANSPORT_USB) != 0,
                (transport & HidControl.TRANSPORT_BT) != 0);
            appliedMouse = mouse;
            appliedGrab = grab;
            appliedKeys = keys;
            appliedTransport = transport;
        }
    }

    public static void update(Context ctx, boolean mouse, boolean grab) {
        SharedPreferences p = ctx.getSharedPreferences("usb_hid", MODE_PRIVATE);
        update(ctx, mouse, grab, true,
            p.getInt("transport", HidControl.TRANSPORT_USB),
            p.getBoolean("screen_off", false));
    }

    public static void stop(Context ctx) {
        if (!running && !HidControl.isSessionOn(ctx)) {
            HidControl.endSessionAndRestore(ctx);
            BluetoothHidClient.get().stop();
            return;
        }
        Intent i = new Intent(ctx, HidSessionService.class);
        i.setAction(ACTION_STOP);
        try {
            ctx.startService(i);
        } catch (Exception e) {
            HidControl.endSessionAndRestore(ctx);
            BluetoothHidClient.get().stop();
        }
    }

    /**
     * Stop FGS + clear session so bridge releases TitanKey/pad, but do not
     * restore pad mode (Type tab will re-arm soft inject on Send / soft pad).
     * Always writes park flags immediately; FGS teardown is async.
     */
    public static void park(Context ctx) {
        HidControl.parkSession(ctx);
        if (!running) return;
        // Mark down immediately so Type soft-arm is not blocked / raced
        running = false;
        Intent i = new Intent(ctx, HidSessionService.class);
        i.setAction(ACTION_PARK);
        try {
            ctx.startService(i);
        } catch (Exception e) {
            /* already parked files */
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String act = intent != null ? intent.getAction() : null;
        if (act == null) {
            // System restart after START_STICKY / process death. Do not bring
            // HID back if the user already Stopped (plane session=0).
            boolean sessOn = false;
            try {
                String s = HidControl.readSessionPlane(this);
                sessOn = "1".equals(s) || "true".equalsIgnoreCase(s);
            } catch (Exception ignored) {}
            if (!sessOn) {
                stopSelf(startId);
                return START_NOT_STICKY;
            }
            act = ACTION_START;
        }

        if (ACTION_STOP.equals(act)) {
            endSessionInternal(true);
            return START_NOT_STICKY;
        }
        if (ACTION_LAYOUT_PLANE.equals(act)) {
            // Controls Use/toggle layout — force keys_pause + drain so custom
            // layouts apply under exclusive HID (plane file race fix).
            // Not running: do not stay started (zombie ServiceRecord / DENIED
            // FGS from cross-app startService while idle — 12.88 ghost session).
            if (!running || ending) {
                stopSelf(startId);
                return START_NOT_STICKY;
            }
            try {
                boolean excl = grabMode || HidControl.isGrabOn(this);
                HidControl.seedKeysPauseIfLayoutOn(this, excl);
                reconcileLayoutKeysPause();
                if (HidControl.isSoftCompose() && excl) {
                    HidControl.setSoftCompose(false);
                }
                HidControl.ensureSpecialsQueues(this);
                if (!HidControl.isSoftCompose()) {
                    HidControl.drainHwOut(this);
                }
                HidControl.drainRemoteQueue(this);
            } catch (Exception ignored) {}
            return START_STICKY;
        }
        if (ACTION_DRAIN.equals(act)) {
            // Layout specials: Controls just queued remote_q — drain now, do not
            // wait for the 4–12ms loop. Exclusive host symbols must never be
            // blocked by sticky softCompose (Type tab) leftover.
            // 1.93: if FGS not running and session plane is off, exit immediately
            // (Controls used to cold-start DRAIN → zombie ServiceRecord app=null).
            if (!running && !ending) {
                try {
                    String sess = HidControl.readSessionPlane(this);
                    boolean sessOn = "1".equals(sess) || "true".equalsIgnoreCase(sess);
                    boolean pending = HidControl.remoteQueueHasBytes(this);
                    if (!sessOn && !HidControl.isGrabPlaneExplicit(this) && !pending) {
                        stopSelf(startId);
                        return START_NOT_STICKY;
                    }
                } catch (Exception ignored) {}
            }
            try {
                // B2 1.09: seed queues on every DRAIN (mid-session hold arm race)
                try { HidControl.ensureSpecialsQueues(this); } catch (Exception ignored) {}
                // Clear softCompose for exclusive / keys_pause / live grab plane
                // so Specials always hit host queues (not Type-only soft path).
                // B2 1.16: also win when remote_q already has Specials bytes even if
                // session/grab plane lagged one frame after exclusive START.
                boolean exclPlane = grabMode
                    || HidControl.isGrabOn(this)
                    || "1".equals(HidControl.readKeysPausePlane(this))
                    || HidControl.isExclusiveLayoutActive(this);
                boolean pendingSpecials = HidControl.remoteQueueHasBytes(this);
                if (HidControl.isSoftCompose() && (exclPlane || pendingSpecials)) {
                    HidControl.setSoftCompose(false);
                }
                // B2 1.25: typing-cursor freeze must not block exclusive Specials
                // host pad/mouse while remote_q is draining.
                if (exclPlane || pendingSpecials) {
                    try {
                        HidControl.write(this, "titan2_pad_cursor_pause", "0");
                    } catch (Exception ignored) {}
                }
                // Always drain remote_q (Specials); hw.out when not soft Type
                // 2.00: exclusive/pending specials force softCompose off already above
                // so both drains always run for host Sym inject.
                if (!HidControl.isSoftCompose() || exclPlane || pendingSpecials) {
                    if (exclPlane || pendingSpecials) {
                        try { HidControl.setSoftCompose(false); } catch (Exception ignored2) {}
                    }
                    if (!HidControl.isSoftCompose()) {
                        HidControl.drainHwOut(this);
                    }
                }
                HidControl.drainRemoteQueue(this);
            } catch (Exception ignored) {}
            // B2 1.38/1.39: exclusive plane live but FGS dead (process thrash) —
            // re-arm session. Require explicit grab=1 and phys keys or mouse
            // (not session-only soft-zero ghost from shell/heal lag).
            if (!running && !ending) {
                try {
                    String sess = HidControl.readSessionPlane(this);
                    boolean sessOn = "1".equals(sess) || "true".equalsIgnoreCase(sess);
                    boolean phys = HidControl.isKeysOn(this) || HidControl.isMouseOn(this);
                    if (sessOn && HidControl.isGrabPlaneExplicit(this) && phys) {
                        mouseMode = HidControl.isMouseOn(this);
                        grabMode = true;
                        keysMode = HidControl.isKeysOn(this);
                        SharedPreferences p =
                            getSharedPreferences("usb_hid", MODE_PRIVATE);
                        transport = p.getInt("transport", HidControl.TRANSPORT_USB);
                        if (transport == 0) transport = HidControl.TRANSPORT_USB;
                        screenOffOk = p.getBoolean("screen_off", true);
                        ending = false;
                        ensureChannel();
                        startForeground(NOTIF_ID, buildNotification());
                        applySession(false);
                        acquireWake();
                        registerScreenReceiver();
                        running = true;
                        startDrainLoop();
                        return START_STICKY;
                    }
                } catch (Exception ignored) {}
                if (!running) {
                    stopSelf(startId);
                    return START_NOT_STICKY;
                }
            }
            return running ? START_STICKY : START_NOT_STICKY;
        }
        if (ACTION_PARK.equals(act)) {
            // Control files already parked by park(); only tear down FGS.
            // Do not rewrite session=0 after Type soft-arm may have set session=1.
            teardownFgsOnly();
            return START_NOT_STICKY;
        }

        SharedPreferences prefs = getSharedPreferences("usb_hid", MODE_PRIVATE);
        if (intent != null) {
            mouseMode = intent.getBooleanExtra(EXTRA_MOUSE, true);
            grabMode = intent.getBooleanExtra(EXTRA_GRAB, true);
            keysMode = intent.getBooleanExtra(EXTRA_KEYS, true);
            if (intent.hasExtra(EXTRA_TRANSPORT))
                transport = intent.getIntExtra(EXTRA_TRANSPORT, transport);
            else
                transport = prefs.getInt("transport", HidControl.TRANSPORT_USB);
            if (intent.hasExtra(EXTRA_SCREEN_OFF))
                screenOffOk = intent.getBooleanExtra(EXTRA_SCREEN_OFF, true);
            else
                screenOffOk = prefs.getBoolean("screen_off", true);
        } else {
            grabMode = HidControl.isGrabOn(this);
            mouseMode = true;
            keysMode = true;
            transport = prefs.getInt("transport", HidControl.TRANSPORT_USB);
            screenOffOk = prefs.getBoolean("screen_off", true);
        }
        if (transport == 0) transport = HidControl.TRANSPORT_USB;
        // BT HID hold: first-run / unset screen_off defaults true (keep FGS on swipe).
        // Never clobber an explicit persisted false.
        if ((transport & HidControl.TRANSPORT_BT) != 0 && !prefs.contains("screen_off")) {
            screenOffOk = true;
            try { prefs.edit().putBoolean("screen_off", true).apply(); } catch (Exception ignored) {}
        }

        // B2: exclusive START must win over sticky Type softCompose. applySession
        // used to demote grab/keys to soft-only when softCompose was left true,
        // so Specials never got remote_q drain until the user reopened HID.
        if (grabMode && HidControl.isSoftCompose()) {
            HidControl.setSoftCompose(false);
        }
        // World-writable remote_q before first Specials key (Controls append)
        if (grabMode) {
            try { HidControl.ensureSpecialsQueues(this); } catch (Exception ignored) {}
        }

        if (ACTION_UPDATE.equals(act) && running && !ending) {
            applySession(false);
            notifySession();
            return START_STICKY;
        }

        ending = false;
        ensureChannel();
        startForeground(NOTIF_ID, buildNotification());
        applySession(true);
        acquireWake();
        registerScreenReceiver();
        running = true;
        startDrainLoop();
        // Immediate specials drain after exclusive arm (queue may already wait)
        if (grabMode) {
            try {
                HidControl.drainRemoteQueue(this);
                HidControl.drainHwOut(this);
            } catch (Exception ignored) {}
        }
        h.removeCallbacks(notifTick);
        h.postDelayed(notifTick, 2000);
        h.removeCallbacks(localInputTick);
        h.post(localInputTick);
        return START_NOT_STICKY;
    }

    /**
     * FB-HID-3: when display turns off under Screen off OK, keep CPU + plane +
     * BT live. Without reassert, doze / plane races left host keyboard dead.
     */
    private void onDisplayOff() {
        if (!running || ending) return;
        // Always re-hold wake while FGS owns a phys session (screenOffOk or not —
        // display off without OK still needs a short window to finish reports).
        acquireWake();
        // Persist plane so root service keep-awake loop sees screen_off=1.
        try {
            HidControl.setScreenOffOk(this, screenOffOk);
        } catch (Exception ignored) {}
        // Kick service: do not unplug host / force nograb while blank (USB blip).
        try {
            if (screenOffOk) {
                HidControl.write(this, "titan2_usb_hid_screen_off_kick",
                        String.valueOf(System.currentTimeMillis()));
            }
        } catch (Exception ignored) {}
        try {
            if (!HidControl.isSoftCompose()) {
                reassertSessionPlaneIfLive();
            }
        } catch (Exception ignored) {}
        try {
            if ((transport & HidControl.TRANSPORT_BT) != 0) {
                BluetoothHidClient bt = BluetoothHidClient.get();
                if (bt.isWantRunning() && !bt.isReady()) {
                    bt.start(this);
                }
            }
        } catch (Exception ignored) {}
        try {
            if (grabMode || mouseMode || keysMode) {
                HidControl.reassertPhysContract(this, grabMode, mouseMode, keysMode);
            }
        } catch (Exception ignored) {}
        // Hot drain tick so first key after lid-close is not stalled
        try {
            Handler dh = drainHandler;
            if (dh != null) {
                dh.post(() -> {
                    try {
                        if (!HidControl.isSoftCompose()) {
                            HidControl.drainHwOut(HidSessionService.this);
                        }
                        HidControl.drainRemoteQueue(HidSessionService.this);
                    } catch (Exception ignored) {}
                });
            }
        } catch (Exception ignored) {}
    }

    private void onDisplayOn() {
        if (!running || ending) return;
        acquireWake();
        try {
            if (!HidControl.isSoftCompose()
                    && (grabMode || mouseMode || keysMode)) {
                HidControl.reassertPhysContract(this, grabMode, mouseMode, keysMode);
            }
        } catch (Exception ignored) {}
    }

    private void registerScreenReceiver() {
        if (screenReceiverRegistered) return;
        try {
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_SCREEN_OFF);
            f.addAction(Intent.ACTION_SCREEN_ON);
            f.addAction(Intent.ACTION_USER_PRESENT);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(screenReceiver, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(screenReceiver, f);
            }
            screenReceiverRegistered = true;
        } catch (Exception ignored) {
            screenReceiverRegistered = false;
        }
    }

    private void unregisterScreenReceiver() {
        if (!screenReceiverRegistered) return;
        try {
            unregisterReceiver(screenReceiver);
        } catch (Exception ignored) {}
        screenReceiverRegistered = false;
    }

    private void startDrainLoop() {
        stopDrainLoop();
        drainThread = new HandlerThread("titan-hid-drain",
            android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY);
        drainThread.start();
        drainHandler = new Handler(drainThread.getLooper());
        drainHandler.post(hwDrain);
    }

    private void stopDrainLoop() {
        if (drainHandler != null) {
            try { drainHandler.removeCallbacks(hwDrain); } catch (Exception ignored) {}
        }
        try { h.removeCallbacks(hwDrain); } catch (Exception ignored) {}
        drainHandler = null;
        if (drainThread != null) {
            try { drainThread.quitSafely(); } catch (Exception ignored) {}
            drainThread = null;
        }
    }

    private void applySession(boolean starting) {
        HidAppContext.init(this);
        PadModeClient.connect(this);
        HidControl.setTransport(transport);
        HidControl.setScreenOffOk(this, screenOffOk);
        // Phys Pad/Keys/exclusive always wins over sticky Type softCompose.
        boolean phys = grabMode || mouseMode || keysMode;
        if (phys) {
            HidControl.setSoftCompose(false);
        } else if (HidControl.isSoftCompose()) {
            mouseMode = false;
            grabMode = false;
            keysMode = false;
        }
        boolean usb = (transport & HidControl.TRANSPORT_USB) != 0;
        boolean bt = (transport & HidControl.TRANSPORT_BT) != 0;
        if (bt && phys) {
            if (mouseSock == null) mouseSock = new BtMouseSock();
            mouseSock.start();
        } else if (mouseSock != null) {
            mouseSock.stop();
        }
        // Pad prepare / orient / keymap off hot path — was main/FGS jank + USB flap.
        final boolean doMouse = mouseMode && phys;
        final boolean doStartKm = starting && phys && (keysMode || mouseMode);
        final boolean doGrabRestore = grabMode && phys && !HidControl.hasPadRestore(this);
        if (doMouse || doStartKm || doGrabRestore || phys) {
            final Context app = getApplicationContext();
            final boolean grab = grabMode;
            new Thread(() -> {
                try {
                    if (phys && PadModeClient.isFollowOrient(app)) {
                        PadModeClient.publishRotation(app);
                    }
                } catch (Exception ignored) {}
                if (doMouse) {
                    try { HidControl.prepareDriverPad(app); } catch (Exception ignored) {}
                }
                if (doGrabRestore) {
                    try {
                        String before = PadModeClient.get(app);
                        if (PadModeClient.MOUSE.equals(before)) {
                            HidControl.savePadRestore(app, PadModeClient.OFF);
                        } else {
                            HidControl.savePadRestore(app, before);
                        }
                    } catch (Exception ignored) {}
                }
                if (doStartKm) {
                    try { HidKeyMapSession.onSessionStart(app); } catch (Exception ignored) {}
                    try { PadModeClient.requestRegrab(app); } catch (Exception ignored) {}
                    // Once per Start: USB gadget just killed adbd. Restore
                    // Controls Remote ADB if the human already armed it.
                    try { HidControl.ensureWirelessAdbBackup(app); } catch (Exception ignored) {}
                }
            }, "hid-sess-pad").start();
        }
        if (grabMode || starting) {
            localInputPaused = false;
            localInputHits = 0;
            localInputMisses = 0;
            try { HidControl.setLocalInputPause(this, false); } catch (Exception ignored) {}
        }
        // Specials already on at exclusive arm → keys_pause (keys plane 0)
        if (grabMode && phys) {
            try {
                if (HidControl.seedKeysPauseIfLayoutOn(this, true)) {
                    keysMode = false;
                }
            } catch (Exception ignored) {}
        }
        // Single plane write — contract for hid_bridge
        HidControl.setSession(this, true, mouseMode, grabMode, keysMode, usb, bt);
        if (grabMode && phys && HidControl.isHostLayoutKeysPaused(this)) {
            try { HidKeyMapSession.republishHostLayout(this); } catch (Exception ignored) {}
        }
        appliedMouse = mouseMode;
        appliedGrab = grabMode;
        appliedKeys = keysMode;
        appliedTransport = transport;
        lastPhysReassertMs = 0; // allow immediate reassert if race
        BluetoothHidClient btClient = BluetoothHidClient.get();
        if (bt) {
            // Idempotent start — never stop/re-register mid-session (causes "unregistered")
            btClient.loadPreferred(this);
            // start() will scan after HID profile is registered if no preferred host
            btClient.start(this);
        } else if (btClient.isWantRunning()) {
            btClient.stop();
        }
        // Ensure hw.out exists for bridge
        try {
            java.io.File f = new java.io.File(getFilesDir(), HidControl.HW_OUT);
            if (!f.exists()) f.createNewFile();
            //noinspection ResultOfMethodCallIgnored
            f.setReadable(true, false);
            //noinspection ResultOfMethodCallIgnored
            f.setWritable(true, false);
        } catch (Exception ignored) {}
        if (starting) { /* already logged via control files */ }
    }

    private void notifySession() {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(NOTIF_ID, buildNotification());
        } catch (Exception ignored) {}
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPi = PendingIntent.getActivity(
            this, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stop = new Intent(this, HidSessionService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(
            this, 1, stop,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        boolean usb = (transport & HidControl.TRANSPORT_USB) != 0;
        boolean bt = (transport & HidControl.TRANSPORT_BT) != 0;
        String link;
        if (usb && bt) link = "USB+BT";
        else if (bt) link = "BT";
        else link = "USB";
        String route;
        if (!keysMode && !mouseMode) route = "soft";
        else if (localInputPaused) route = "share · pad:guest · keys:android";
        else if (grabMode) route = "excl";
        else route = "share · pad:guest · keys:guest";
        String body = link + " · " + route;
        if (bt) body += " · " + BluetoothHidClient.get().status();
        if (screenOffOk) body += " · screen-off";
        if (localInputPaused) body += " · phone kb";
        try { ShareTileService.requestRefresh(this); } catch (Exception ignored) {}

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CH_V3);
        } else {
            b = new Notification.Builder(this);
        }
        // Custom content avoids LOS Silent/third-party white app-icon square.
        // smallIcon still required for status bar (framework monochrome).
        String title = "Titan HID · " + link;
        int layoutId = getResources().getIdentifier(
            "notif_hid", "layout", getPackageName());
        if (layoutId != 0) {
            RemoteViews rv = new RemoteViews(getPackageName(), layoutId);
            int titleId = getResources().getIdentifier(
                "notif_title", "id", getPackageName());
            int textId = getResources().getIdentifier(
                "notif_text", "id", getPackageName());
            if (titleId != 0) rv.setTextViewText(titleId, title);
            if (textId != 0) rv.setTextViewText(textId, body);
            b.setCustomContentView(rv);
            b.setCustomBigContentView(rv);
        }
        int statusIcon = getResources().getIdentifier(
            "ic_hid_status", "drawable", getPackageName());
        if (statusIcon == 0) statusIcon = android.R.drawable.stat_sys_data_bluetooth;
        b.setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(statusIcon)
            .setColor(0xFF4DD0E1)
            .setContentIntent(openPi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(0, "Stop", stopPi);
        if (Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return b.build();
    }

    private void endSessionInternal() {
        endSessionInternal(true);
    }

    private void endSessionInternal(boolean restorePad) {
        if (ending) return;
        ending = true;
        running = false;
        layoutForcedKeysOff = false;
        appliedMouse = true;
        appliedGrab = true;
        appliedKeys = true;
        appliedTransport = -1;
        stopDrainLoop();
        unregisterScreenReceiver();
        if (mouseSock != null) {
            try { mouseSock.stop(); } catch (Exception ignored) {}
            mouseSock = null;
        }
        h.removeCallbacks(notifTick);
        h.removeCallbacks(localInputTick);
        try { HidKeyMapSession.onSessionStop(this); } catch (Exception ignored) {}
        try { ShareTileService.requestRefresh(this); } catch (Exception ignored) {}
        try { HidControl.setLocalInputPause(this, false); } catch (Exception ignored) {}
        localInputPaused = false;
        try { BluetoothHidClient.get().stop(); } catch (Exception ignored) {}
        try {
            getSharedPreferences("usb_hid", MODE_PRIVATE)
                .edit().putBoolean("pending_start", false).apply();
        } catch (Exception ignored) {}
        try {
            if (restorePad) HidControl.endSessionAndRestore(this);
            else HidControl.parkSession(this);
        } catch (Exception ignored) {
            try { HidControl.parkSession(this); } catch (Exception ignored2) {}
        }
        // Host-safety: always drop pure HID gadget when FGS ends (plane alone
        // is not enough if native service lagged — dual PC keyboard incident).
        try { HidControl.forceHostSafeIdle(this); } catch (Exception ignored) {}
        releaseWake();
        try { stopForeground(true); } catch (Exception ignored) {}
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.cancel(NOTIF_ID);
        } catch (Exception ignored) {}
        stopSelf();
    }

    /** Stop FGS without touching control files (Type park already wrote them). */
    private void teardownFgsOnly() {
        ending = true;
        running = false;
        appliedMouse = true;
        appliedGrab = true;
        appliedKeys = true;
        appliedTransport = -1;
        stopDrainLoop();
        unregisterScreenReceiver();
        if (mouseSock != null) {
            try { mouseSock.stop(); } catch (Exception ignored) {}
            mouseSock = null;
        }
        h.removeCallbacks(notifTick);
        h.removeCallbacks(localInputTick);
        try { HidKeyMapSession.onSessionStop(this); } catch (Exception ignored) {}
        try { BluetoothHidClient.get().stop(); } catch (Exception ignored) {}
        releaseWake();
        try { stopForeground(true); } catch (Exception ignored) {}
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.cancel(NOTIF_ID);
        } catch (Exception ignored) {}
        ending = false;
        stopSelf();
    }

    private void acquireWake() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null) return;
            if (wake == null) {
                // PARTIAL keeps CPU for bridge drain + BT while screen is off
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "titanus2:usbhid");
                wake.setReferenceCounted(false);
            }
            // Screen-off sessions can run long; 12h cap. Re-acquire on every
            // SCREEN_OFF so a mid-session timeout cannot leave HID frozen.
            long ms = screenOffOk ? 12L * 60 * 60 * 1000 : 4L * 60 * 60 * 1000;
            if (wake.isHeld()) {
                try { wake.release(); } catch (Exception ignored) {}
            }
            wake.acquire(ms);
        } catch (Exception ignored) {}
    }

    private void releaseWake() {
        try {
            if (wake != null && wake.isHeld()) wake.release();
        } catch (Exception ignored) {}
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (nm.getNotificationChannel(CH_V3) == null) {
            NotificationChannel ch = new NotificationChannel(
                CH_V3, "HID session", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("USB / Bluetooth keyboard+mouse session");
            ch.setShowBadge(false);
            ch.setSound(null, null);
            ch.enableLights(false);
            ch.enableVibration(false);
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            if (Build.VERSION.SDK_INT >= 29) {
                try { ch.setAllowBubbles(false); } catch (Exception ignored) {}
            }
            nm.createNotificationChannel(ch);
        }
        // Drop legacy channels (black colorized + Silent white-square rows)
        try { nm.deleteNotificationChannel(CH); } catch (Exception ignored) {}
        try { nm.deleteNotificationChannel(CH_V2); } catch (Exception ignored) {}
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Screen-off mode: keep session when user leaves app (swipe).
        // BT transport (prefs or plane) must not endSession on swipe — HID ACL dies.
        boolean bt = (transport & HidControl.TRANSPORT_BT) != 0;
        if (!bt) {
            try {
                java.io.File f = new java.io.File("/data/misc/titan2/titan2_usb_hid_bt");
                if (f.isFile()) {
                    byte[] b = new byte[8];
                    try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                        int n = in.read(b);
                        if (n > 0 && "1".equals(new String(b, 0, n).trim())) bt = true;
                    }
                }
            } catch (Exception ignored) {}
        }
        if (!screenOffOk && !bt) {
            endSessionInternal();
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        stopDrainLoop();
        unregisterScreenReceiver();
        h.removeCallbacks(notifTick);
        h.removeCallbacks(localInputTick);
        try { HidControl.setLocalInputPause(this, false); } catch (Exception ignored) {}
        if (running && !ending) {
            ending = true;
            running = false;
            try { HidKeyMapSession.onSessionStop(this); } catch (Exception ignored) {}
            try { BluetoothHidClient.get().stop(); } catch (Exception ignored) {}
            try {
                HidControl.endSessionAndRestore(this);
            } catch (Exception ignored) {
                try { HidControl.endSession(this); } catch (Exception ignored2) {}
            }
            releaseWake();
        }
        // 1.72 B2: process death without clean Stop left specials queues + keys_pause
        try { HidControl.flushSpecialsQueues(this); } catch (Exception ignored) {}
        try { HidControl.clearHostLayoutKeysPause(this); } catch (Exception ignored) {}
        try { HidControl.restoreSoftImeAfterExclusive(this); } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
