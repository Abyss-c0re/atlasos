package com.titanus2.usbhid;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppQosSettings;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Phone = Bluetooth HID keyboard + mouse. We send keys/pad to a chosen host PC.
 *
 * How the app knows which device to control:
 *  1. User picks a target from Scan / Paired list (saved as preferred MAC).
 *  2. App pairs if needed, registers HID Device profile, then hid.connect(host).
 *  3. All reports go only to that connected host until user picks another.
 *
 * Compatible targets: Bluetooth hosts (PCs, tablets, TVs) that accept HID keyboards.
 * Speakers/headphones are listed but marked unlikely.
 */
@SuppressLint("MissingPermission")
public final class BluetoothHidClient {
    private static final String TAG = "TitanBtHid";
    private static final String PREFS = "usb_hid";
    private static final String PREF_HOST = "bt_host_mac";
    private static final String PREF_HOST_NAME = "bt_host_name";
    public static final int REQ_ENABLE_BT = 2101;
    public static final int REQ_DISCOVERABLE = 2102;

    private static final int ID_KBD = 1;
    private static final int ID_MOUSE = 2;

    private static final byte[] DESCRIPTOR = new byte[]{
        0x05, 0x01, 0x09, 0x06, (byte) 0xA1, 0x01, (byte) 0x85, 0x01,
        0x05, 0x07, 0x19, (byte) 0xE0, 0x29, (byte) 0xE7, 0x15, 0x00,
        0x25, 0x01, 0x75, 0x01, (byte) 0x95, 0x08, (byte) 0x81, 0x02,
        (byte) 0x95, 0x01, 0x75, 0x08, (byte) 0x81, 0x01, (byte) 0x95, 0x05,
        0x75, 0x01, 0x05, 0x08, 0x19, 0x01, 0x29, 0x05, (byte) 0x91, 0x02,
        (byte) 0x95, 0x01, 0x75, 0x03, (byte) 0x91, 0x01, (byte) 0x95, 0x06,
        0x75, 0x08, 0x15, 0x00, 0x25, 0x65, 0x05, 0x07, 0x19, 0x00,
        0x29, 0x65, (byte) 0x81, 0x00, (byte) 0xC0,
        0x05, 0x01, 0x09, 0x02, (byte) 0xA1, 0x01, (byte) 0x85, 0x02,
        0x09, 0x01, (byte) 0xA1, 0x00, 0x05, 0x09, 0x19, 0x01, 0x29, 0x03,
        0x15, 0x00, 0x25, 0x01, (byte) 0x95, 0x03, 0x75, 0x01, (byte) 0x81, 0x02,
        (byte) 0x95, 0x01, 0x75, 0x05, (byte) 0x81, 0x01, 0x05, 0x01,
        0x09, 0x30, 0x09, 0x31, 0x09, 0x38, 0x15, (byte) 0x81, 0x25, 0x7F,
        0x75, 0x08, (byte) 0x95, 0x03, (byte) 0x81, 0x06, (byte) 0xC0, (byte) 0xC0
    };

    /** One row in the target picker. */
    public static final class HostInfo {
        public final String mac;
        public final String name;
        public final String kind;       // PC, Phone, Audio, Other
        public final boolean likelyHost; // likely accepts keyboard
        public final boolean bonded;
        public final boolean nearby;
        public final boolean preferred;
        public final boolean connected;
        public final boolean connecting;

        public HostInfo(String mac, String name, String kind, boolean likelyHost,
                        boolean bonded, boolean nearby, boolean preferred,
                        boolean connected, boolean connecting) {
            this.mac = mac;
            this.name = name;
            this.kind = kind;
            this.likelyHost = likelyHost;
            this.bonded = bonded;
            this.nearby = nearby;
            this.preferred = preferred;
            this.connected = connected;
            this.connecting = connecting;
        }

        public String label() {
            StringBuilder sb = new StringBuilder();
            if (connected) sb.append("● ");
            else if (connecting) sb.append("… ");
            else if (preferred) sb.append("★ ");
            sb.append(name);
            if (kind != null && !kind.isEmpty()) sb.append(" · ").append(kind);
            if (!bonded && nearby) sb.append(" · new");
            else if (bonded && !connected) sb.append(" · paired");
            return sb.toString();
        }
    }

    public interface Listener {
        void onBtStatus(String status);
        default void onHostsChanged() {}
    }

    private static final class SeenDevice {
        String mac;
        String name;
        String kind;
        boolean likelyHost;
        boolean bonded;
        boolean nearby;
        int rssi = Integer.MIN_VALUE;
    }

    private static final BluetoothHidClient INSTANCE = new BluetoothHidClient();
    public static BluetoothHidClient get() { return INSTANCE; }

    private final AtomicBoolean wantRunning = new AtomicBoolean(false);
    private final AtomicBoolean proxyRequested = new AtomicBoolean(false);
    private final AtomicBoolean registered = new AtomicBoolean(false);
    private final AtomicBoolean ready = new AtomicBoolean(false);
    private final AtomicBoolean registering = new AtomicBoolean(false);
    private final AtomicBoolean scanning = new AtomicBoolean(false);
    private final AtomicReference<String> status = new AtomicReference<>("off");
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    /** MAC → seen (bonded + discovery). */
    private final Map<String, SeenDevice> seen = new LinkedHashMap<>();

    private Context appCtx;
    private BluetoothAdapter adapter;
    private BluetoothHidDevice hid;
    private BluetoothDevice host;
    private String preferredMac = "";
    private String preferredName = "";
    private String pendingConnectMac = "";
    private String connectingMac = "";
    private boolean receiverRegistered;
    private final KeyState keyState = new KeyState();
    private int mouseButtons;
    /**
     * some host SoCs: never queue motion across threads.
     * Native hid_bridge already coalesces ~8ms. Java must sendReport on the
     * caller (drain) thread immediately — hopping to main + another 8ms delay
     * was stacking lag. If BT is still busy, drop intermediate and keep sum.
     */
    private final Object mouseLock = new Object();
    private int pendDx;
    private int pendDy;
    private int pendWheel;
    private int pendButtons;
    private boolean mousePending;
    /**
     * Last buttons byte actually sent to the host (B6 1.95).
     * -1 = never sent. Pure button-up with zero motion must still emit
     * (otherwise host keeps a stuck click). Matches hid_bridge bt_last_btn_sent.
     */
    private int lastSentMouseButtons = -1;
    private final AtomicBoolean mouseSendBusy = new AtomicBoolean(false);
    /**
     * B6 2.01: generation bumped on every host drop/release. In-flight
     * {@link #sendMouseRaw} that started before the bump must not write
     * {@code lastSentMouseButtons} or reflush residual after the pure
     * button-up — that re-armed stuck clicks on Snapdragon (late click
     * after zeros, or lastSent=1 while host already saw release).
     */
    private final AtomicInteger mouseDropEpoch = new AtomicInteger(0);
    /**
     * B6 2.07: true for the whole {@link #releaseHostInputBeforeDrop} window.
     * Concurrent pad samples that fail CAS (or arrive after epoch bump) must
     * <b>not</b> fold click bits into {@code pendButtons} while busy is still
     * held by a late-settle pure-zero — that re-armed stuck host clicks after
     * Stop/drop once busy cleared (post-2.05 residual).
     */
    private final AtomicBoolean mouseDropGate = new AtomicBoolean(false);
    /**
     * Depth guard for post-send residual reflush (B6).
     * Up to {@link #MOUSE_REFLUSH_MAX} immediate packets preserve distance on
     * healthy BT; never a multi-second time-ordered playback queue.
     */
    private int mouseReflushDepth;
    /** Max chained residual packets after one sendReport (4 total with initial). */
    private static final int MOUSE_REFLUSH_MAX = 3;
    /**
     * If last sendReport took longer than this, Snapdragon host is congested:
     * drop residual (latest-wins) instead of multi-packet catch-up.
     */
    private static final long MOUSE_CONGEST_NS = 25_000_000L;
    private long lastMouseSendNs;
    private long reconnectBackoffMs = 2500L;
    private volatile boolean connectInFlight;
    private long lastConnectAttemptMs;

    private final Runnable retryRegister = new Runnable() {
        @Override public void run() {
            if (!wantRunning.get()) return;
            if (registered.get() && hid != null) {
                connectPreferred();
                return;
            }
            if (hid != null) registerApp();
            else requestProxy();
        }
    };
    private final Runnable reconnectTick = new Runnable() {
        @Override public void run() {
            if (!wantRunning.get()) return;
            if (registered.get() && !ready.get() && preferredMac != null && !preferredMac.isEmpty())
                connectPreferred();
            main.postDelayed(this, 2500);
        }
    };
    private final Runnable scanTimeout = () -> stopScan();

    private static final class KeyState {
        byte mods;
        final byte[] keys = new byte[6];
    }

    private BluetoothHidClient() {}

    public void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) listeners.add(l);
    }
    public void removeListener(Listener l) { listeners.remove(l); }

    public String status() { return status.get(); }
    public boolean isReady() { return ready.get() && host != null; }
    public boolean isRegistered() { return registered.get(); }
    public boolean isWantRunning() { return wantRunning.get(); }
    public boolean isScanning() { return scanning.get(); }
    public boolean isAdapterOn() {
        try { return adapter != null && adapter.isEnabled(); } catch (Exception e) { return false; }
    }
    public String preferredMac() { return preferredMac != null ? preferredMac : ""; }
    public String preferredName() {
        if (preferredName != null && !preferredName.isEmpty()) return preferredName;
        return preferredMac();
    }
    public String connectedName() {
        if (host == null) return preferredName();
        return safeName(host);
    }
    public String connectedMac() {
        try { return host != null ? host.getAddress() : ""; } catch (Exception e) { return ""; }
    }

    private void setStatus(String s) {
        status.set(s);
        for (Listener l : listeners) {
            try { l.onBtStatus(s); } catch (Exception ignored) {}
        }
        Log.i(TAG, s);
    }

    private void notifyHosts() {
        for (Listener l : listeners) {
            try { l.onHostsChanged(); } catch (Exception ignored) {}
        }
    }

    public void loadPreferred(Context ctx) {
        try {
            SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            preferredMac = p.getString(PREF_HOST, "");
            preferredName = p.getString(PREF_HOST_NAME, "");
            if (preferredMac == null) preferredMac = "";
            if (preferredName == null) preferredName = "";
        } catch (Exception ignored) {}
    }

    public void setPreferred(Context ctx, String mac, String name) {
        preferredMac = mac != null ? mac.trim().toUpperCase(Locale.US) : "";
        preferredName = name != null ? name : "";
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(PREF_HOST, preferredMac)
                .putString(PREF_HOST_NAME, preferredName)
                .apply();
        } catch (Exception ignored) {}
        notifyHosts();
    }

    public boolean requestEnable(Activity act) {
        try {
            ensureAdapter(act);
            if (adapter == null) {
                setStatus("no Bluetooth hardware");
                return false;
            }
            if (adapter.isEnabled()) return false;
            Intent i = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            act.startActivityForResult(i, REQ_ENABLE_BT);
            setStatus("turn Bluetooth on…");
            return true;
        } catch (Exception e) {
            try {
                if (adapter != null) adapter.enable();
                setStatus("enabling Bluetooth…");
            } catch (Exception e2) {
                setStatus("open Bluetooth in Settings");
                openBluetoothSettings(act);
            }
            return true;
        }
    }

    public boolean requestDiscoverable(Activity act, int seconds) {
        try {
            ensureAdapter(act);
            if (adapter == null) return false;
            if (!adapter.isEnabled()) {
                requestEnable(act);
                return true;
            }
            Intent i = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
            i.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, seconds > 0 ? seconds : 300);
            act.startActivityForResult(i, REQ_DISCOVERABLE);
            setStatus("visible for pairing…");
            return true;
        } catch (Exception e) {
            openBluetoothSettings(act);
            return false;
        }
    }

    public void openBluetoothSettings(Context ctx) {
        try {
            Intent i = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception e) {
            try {
                Intent i = new Intent(Settings.ACTION_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            } catch (Exception ignored) {}
        }
    }

    /** Merged paired + nearby list for the picker. */
    public List<HostInfo> listHosts(Context ctx) {
        loadPreferred(ctx);
        ensureAdapter(ctx);
        refreshBondedIntoSeen();
        String connMac = connectedMac();
        List<HostInfo> out = new ArrayList<>();
        synchronized (seen) {
            for (SeenDevice s : seen.values()) {
                boolean pref = preferredMac != null && preferredMac.equalsIgnoreCase(s.mac);
                boolean conn = connMac != null && !connMac.isEmpty()
                    && connMac.equalsIgnoreCase(s.mac) && ready.get();
                boolean conning = connectingMac != null && connectingMac.equalsIgnoreCase(s.mac)
                    && !conn;
                out.add(new HostInfo(
                    s.mac, s.name, s.kind, s.likelyHost,
                    s.bonded, s.nearby, pref, conn, conning));
            }
        }
        Collections.sort(out, (a, b) -> {
            if (a.connected != b.connected) return a.connected ? -1 : 1;
            if (a.connecting != b.connecting) return a.connecting ? -1 : 1;
            if (a.preferred != b.preferred) return a.preferred ? -1 : 1;
            if (a.likelyHost != b.likelyHost) return a.likelyHost ? -1 : 1;
            if (a.bonded != b.bonded) return a.bonded ? -1 : 1;
            return a.name.compareToIgnoreCase(b.name);
        });
        return out;
    }

    /**
     * User picked a target. Saves it, pairs if needed, registers HID, connects.
     * This is how the app knows which device to control.
     */
    public void selectAndConnect(Context ctx, String mac, String name) {
        if (mac == null || mac.isEmpty()) return;
        appCtx = ctx.getApplicationContext();
        loadPreferred(appCtx);
        setPreferred(appCtx, mac, name != null ? name : mac);
        pendingConnectMac = preferredMac;
        connectingMac = preferredMac;
        wantRunning.set(true);
        ensureAdapter(appCtx);
        ensureReceiver();
        stopScan(); // discovery blocks outgoing connections

        if (adapter == null) {
            setStatus("no Bluetooth hardware");
            return;
        }
        if (!adapter.isEnabled()) {
            setStatus("turn Bluetooth on first");
            return;
        }

        BluetoothDevice dev;
        try {
            dev = adapter.getRemoteDevice(preferredMac);
        } catch (Exception e) {
            setStatus("bad address");
            return;
        }

        int bond = BluetoothDevice.BOND_NONE;
        try { bond = dev.getBondState(); } catch (Exception ignored) {}

        if (bond != BluetoothDevice.BOND_BONDED) {
            setStatus("pairing with " + preferredName() + "…");
            try {
                boolean started = dev.createBond();
                if (!started) setStatus("pair failed — try again");
                // BOND_BONDED receiver continues connect
            } catch (Exception e) {
                setStatus("pair error");
                Log.e(TAG, "createBond", e);
            }
            notifyHosts();
            // Still start HID profile so we're ready after pair
            if (!registered.get()) start(appCtx);
            return;
        }

        // Bonded → ensure HID registered then connect
        if (!registered.get()) {
            setStatus("preparing keyboard…");
            start(appCtx);
            // connectPreferred after register callback
            return;
        }
        connectDevice(dev);
    }

    public void selectAndConnect(Context ctx, String mac) {
        String name = mac;
        synchronized (seen) {
            SeenDevice s = seen.get(normMac(mac));
            if (s != null && s.name != null) name = s.name;
        }
        selectAndConnect(ctx, mac, name);
    }

    public void disconnectHost() {
        // 1.97 B6: release click/keys on host *before* drop (stuck click residual)
        releaseHostInputBeforeDrop();
        if (hid != null && host != null) {
            try { hid.disconnect(host); } catch (Exception ignored) {}
        }
        host = null;
        ready.set(false);
        connectingMac = "";
        resetMouseButtonBaseline();
        BtMouseSock.resetSeq();
        BtMouseSock.zeroAllMirrors();
        setStatus(registered.get() ? "disconnected — pick a PC" : "off");
        notifyHosts();
    }

    /** Start classic inquiry for nearby hosts (~12s). Retries; falls back to paired list. */
    public void startScan(Context ctx) {
        appCtx = ctx.getApplicationContext();
        ensureAdapter(appCtx);
        ensureReceiver();
        if (adapter == null) {
            setStatus("no Bluetooth hardware");
            return;
        }
        if (!adapter.isEnabled()) {
            setStatus("turn Bluetooth on first");
            return;
        }
        refreshBondedIntoSeen();
        notifyHosts(); // show paired immediately even if inquiry fails

        setStatus("scanning for PCs…");
        scanning.set(true);
        scanWindowUntil = System.currentTimeMillis() + 14000;
        main.removeCallbacks(scanTimeout);
        main.removeCallbacks(scanAttempt);
        // cancel any prior inquiry, then start after a short settle (MTK often fails if busy)
        try {
            if (adapter.isDiscovering()) adapter.cancelDiscovery();
        } catch (Exception ignored) {}
        scanAttemptCount = 0;
        main.postDelayed(scanAttempt, 350);
        main.postDelayed(scanTimeout, 14000);
    }

    private int scanAttemptCount = 0;
    private long scanWindowUntil = 0;
    private final Runnable scanAttempt = new Runnable() {
        @Override public void run() {
            if (!scanning.get() || adapter == null) return;
            try {
                if (adapter.isDiscovering()) {
                    // Already running — treat as success
                    setStatus("scanning for PCs…");
                    return;
                }
                boolean ok = false;
                SecurityException se = null;
                try {
                    ok = adapter.startDiscovery();
                } catch (SecurityException e) {
                    se = e;
                    Log.e(TAG, "startDiscovery security", e);
                }
                Log.i(TAG, "startDiscovery ok=" + ok
                    + " state=" + adapter.getState()
                    + " attempt=" + scanAttemptCount
                    + (se != null ? " se=" + se.getMessage() : ""));

                if (ok || adapter.isDiscovering()) {
                    setStatus("scanning for PCs…");
                    return;
                }

                scanAttemptCount++;
                if (scanAttemptCount < 4) {
                    try { adapter.cancelDiscovery(); } catch (Exception ignored) {}
                    main.postDelayed(this, 400);
                    setStatus("scan retry " + scanAttemptCount + "…");
                    return;
                }

                // Hard fail: keep paired list usable
                scanning.set(false);
                main.removeCallbacks(scanTimeout);
                int paired = 0;
                try {
                    Set<BluetoothDevice> b = adapter.getBondedDevices();
                    if (b != null) paired = b.size();
                } catch (Exception ignored) {}
                if (paired > 0) {
                    setStatus("scan blocked — pick a paired PC below");
                } else {
                    setStatus("scan blocked — open BT settings to pair");
                }
                notifyHosts();
            } catch (Exception e) {
                Log.e(TAG, "scanAttempt", e);
                scanning.set(false);
                setStatus("scan error — use paired list / BT settings");
                notifyHosts();
            }
        }
    };

    public void stopScan() {
        main.removeCallbacks(scanTimeout);
        main.removeCallbacks(scanAttempt);
        try {
            if (adapter != null && adapter.isDiscovering()) adapter.cancelDiscovery();
        } catch (Exception ignored) {}
        if (scanning.getAndSet(false)) {
            if (wantRunning.get() && !ready.get() && preferredMac != null && !preferredMac.isEmpty())
                setStatus("scan done — tap a PC to connect");
            else if (!ready.get())
                setStatus(registered.get() ? "tap a PC to connect" : status.get());
            notifyHosts();
        }
    }

    public synchronized void start(Context ctx) {
        appCtx = ctx.getApplicationContext();
        loadPreferred(appCtx);
        wantRunning.set(true);
        ensureReceiver();
        ensureAdapter(appCtx);
        refreshBondedIntoSeen();

        if (adapter == null) {
            setStatus("no Bluetooth hardware");
            return;
        }
        if (!adapter.isEnabled()) {
            setStatus("Bluetooth off — tap Enable");
            return;
        }
        if (registered.get() && hid != null) {
            if (ready.get() && host != null) {
                setStatus("connected " + safeName(host));
            } else if (preferredMac != null && !preferredMac.isEmpty()) {
                setStatus("ready — connecting " + preferredName());
                connectPreferred();
            } else {
                setStatus("ready — scan & pick a PC");
            }
            main.removeCallbacks(reconnectTick);
            main.postDelayed(reconnectTick, 2500);
            notifyHosts();
            return;
        }
        if (hid != null && !registered.get() && !registering.get()) {
            registerApp();
            return;
        }
        if (proxyRequested.get() && hid == null) {
            setStatus("waiting for Bluetooth…");
            scheduleRetry(800);
            return;
        }
        setStatus("starting…");
        requestProxy();
        main.removeCallbacks(reconnectTick);
        main.postDelayed(reconnectTick, 2500);
    }

    public synchronized void stop() {
        wantRunning.set(false);
        main.removeCallbacks(retryRegister);
        main.removeCallbacks(reconnectTick);
        stopScan();
        // 1.97 B6: host still needs button-up / empty kbd while proxy is live
        releaseHostInputBeforeDrop();
        ready.set(false);
        registered.set(false);
        registering.set(false);
        proxyRequested.set(false);
        pendingConnectMac = "";
        connectingMac = "";
        host = null;
        resetMouseButtonBaseline();
        BtMouseSock.resetSeq();
        BtMouseSock.zeroAllMirrors();
        try {
            if (hid != null) {
                try { hid.unregisterApp(); } catch (Exception ignored) {}
                if (adapter != null) {
                    try { adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid); } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}
        hid = null;
        dropReceiver();
        setStatus("off");
        notifyHosts();
    }

    /**
     * B6 1.97–2.03: Snapdragon hosts keep last mouse buttons / kbd state until a
     * clean release report. Stop/disconnect without this left stuck clicks
     * and sticky mods after exclusive session end (feel residual).
     * Must run while {@code hid}+{@code host} are still valid.
     * <p>
     * 1.98: under congested BT, {@link #sendMouseRaw} could fold the release into
     * pending while {@code mouseSendBusy}, then stop cleared pending and dropped
     * the proxy — host kept left-click. Force a direct sendReport after a short
     * busy wait; never leave release in the coalesce queue.
     * <p>
     * 1.99: always emit pure button-up (even when Java held==0) — host may still
     * hold a click from a partial/congested edge Java never tracked. Callers that
     * see remote drop must invoke this <b>before</b> nulling {@code host}.
     * <p>
     * 2.01: bump {@link #mouseDropEpoch} first so an in-flight click report that
     * finishes after this path cannot rewrite lastSent or reflush motion; zero
     * mailbox SoT so pad-agent cannot re-queue a held click mid-drop; double
     * button-up (+ one retry on false) because Snapdragon often drops the first
     * pure-zero while the ACL is dying or re-pairing.
     * <p>
     * 2.03: never steal/clear {@code mouseSendBusy} owned by an in-flight
     * {@link #sendMouseRaw} (that false-cleared busy mid-sendReport and let a
     * late click land <b>after</b> pure zeros). Wait for busy to drain, force
     * zeros without corrupting ownership, then one settle zero after the late
     * report finishes so host cannot keep a stuck click.
     * <p>
     * 2.05: {@link #sendMouseRaw} keeps busy owned through its own late-drop
     * pure-zero (no clear→reacquire gap where a concurrent sample can start
     * another click report between zeros).
     * <p>
     * 2.07: hold {@link #mouseDropGate} for the whole release so concurrent
     * {@link #queueMouse} cannot fold click-down into pending while a late
     * settle still owns busy — clearing busy then reflushing that fold left
     * Snapdragon hosts with stuck left-click after Stop.
     */
    private void releaseHostInputBeforeDrop() {
        if (hid == null || host == null) return;
        // Invalidate any in-flight sendMouseRaw before we touch reports.
        mouseDropEpoch.incrementAndGet();
        mouseDropGate.set(true);
        try {
            // B6 2.09: empty kbd twice (mods latch like mouse buttons on Snapdragon
            // hosts; sendReport=false is common under ACL congestion — retry inside).
            try {
                releaseAllKeys();
            } catch (Exception ignored) {}
            try {
                Thread.sleep(2);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            try {
                releaseAllKeys();
            } catch (Exception ignored) {}
            // Stop mailbox → queueHostMouse races during the release window.
            try {
                BtMouseSock.zeroAllMirrors();
            } catch (Exception ignored) {}
            synchronized (mouseLock) {
                // Drop motion residual — stop must not replay pad dx/dy after release.
                pendDx = 0;
                pendDy = 0;
                pendWheel = 0;
                pendButtons = 0;
                mousePending = false;
                mouseButtons = 0;
            }
            // Wait out an in-flight sendReport so pure zeros order after it when possible.
            waitMouseSendIdle(40_000_000L);
            try {
                forceMouseButtonUp();
            } catch (Exception ignored) {}
            // Second pure-zero: first report is often ignored on ACL tear/re-pair.
            try {
                Thread.sleep(4);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            try {
                forceMouseButtonUp();
            } catch (Exception ignored) {}
            // 2.03: if a pre-drop sendMouseRaw was still inside sendReport during the
            // first zeros, it may deliver a late click *after* them. Wait for that
            // owner to leave busy, then one settle pure-zero (host final 1→0).
            waitMouseSendIdle(50_000_000L);
            try {
                forceMouseButtonUp();
            } catch (Exception ignored) {}
            // Final empty kbd after mouse settle (sticky Shift/Alt residual on host).
            try {
                releaseAllKeys();
            } catch (Exception ignored) {}
        } finally {
            // Late-settle finally may have raced a fold before gate was checked —
            // wipe again after busy drains, then open the plane for new edges.
            waitMouseSendIdle(30_000_000L);
            synchronized (mouseLock) {
                pendDx = 0;
                pendDy = 0;
                pendWheel = 0;
                pendButtons = 0;
                mousePending = false;
                mouseButtons = 0;
            }
            lastSentMouseButtons = 0;
            mouseDropGate.set(false);
        }
    }

    /** Spin until {@link #mouseSendBusy} clears or {@code maxWaitNs} elapses. */
    private void waitMouseSendIdle(long maxWaitNs) {
        long deadline = System.nanoTime() + maxWaitNs;
        while (mouseSendBusy.get() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(1);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * B6 1.98–2.03: pure button-up that never folds into the busy coalesce path.
     * Host must see 1→0 before HID proxy dies. Always sends zeros (1.99).
     * Retries once when sendReport returns false (2.01).
     * <p>
     * 2.03: acquire {@code mouseSendBusy} only via CAS; if an in-flight
     * {@link #sendMouseRaw} owns it, still emit zeros but do <b>not</b> clear
     * the flag (false clear mid-sendReport let a concurrent sample start and
     * left host with click-after-release).
     */
    private void forceMouseButtonUp() {
        if (hid == null || host == null) return;
        // Prefer idle; if still busy after a short wait, send zeros without
        // stealing ownership of mouseSendBusy.
        waitMouseSendIdle(20_000_000L);
        final boolean ownBusy = mouseSendBusy.compareAndSet(false, true);
        try {
            byte[] r = new byte[]{0, 0, 0, 0};
            boolean ok = false;
            try {
                ok = hid.sendReport(host, ID_MOUSE, r);
            } catch (Exception e) {
                Log.w(TAG, "forceMouseButtonUp", e);
            }
            if (!ok) {
                try {
                    Thread.sleep(8);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                try {
                    ok = hid.sendReport(host, ID_MOUSE, r);
                } catch (Exception e) {
                    Log.w(TAG, "forceMouseButtonUp retry", e);
                }
            }
            // Trust the report attempt: local baseline must be 0 either way so the
            // next edge is 0→1, not a phantom hold after a failed send.
            lastSentMouseButtons = 0;
            synchronized (mouseLock) {
                pendButtons = 0;
                mouseButtons = 0;
                pendDx = 0;
                pendDy = 0;
                pendWheel = 0;
                mousePending = false;
            }
            if (!ok) {
                Log.w(TAG, "forceMouseButtonUp sendReport=false (host may keep click)");
            }
        } finally {
            if (ownBusy) {
                mouseSendBusy.set(false);
            }
        }
    }

    private void ensureAdapter(Context ctx) {
        if (adapter != null) return;
        try {
            Context c = ctx != null ? ctx.getApplicationContext() : appCtx;
            if (c == null) return;
            BluetoothManager bm = (BluetoothManager) c.getSystemService(Context.BLUETOOTH_SERVICE);
            adapter = bm != null ? bm.getAdapter() : BluetoothAdapter.getDefaultAdapter();
        } catch (Exception ignored) {}
    }

    private void requestProxy() {
        if (adapter == null || appCtx == null) return;
        if (hid != null) {
            if (!registered.get()) registerApp();
            return;
        }
        try {
            boolean ok = adapter.getProfileProxy(appCtx, serviceListener, BluetoothProfile.HID_DEVICE);
            proxyRequested.set(ok);
            if (!ok) {
                setStatus("HID profile unavailable — retry");
                scheduleRetry(2000);
            } else {
                setStatus("loading HID profile…");
            }
        } catch (Exception e) {
            Log.e(TAG, "getProfileProxy", e);
            setStatus("profile error — retry");
            scheduleRetry(2000);
        }
    }

    private void scheduleRetry(long ms) {
        main.removeCallbacks(retryRegister);
        if (wantRunning.get()) main.postDelayed(retryRegister, ms);
    }

    private void ensureReceiver() {
        if (receiverRegistered || appCtx == null) return;
        try {
            IntentFilter f = new IntentFilter();
            f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
            f.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
            f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
            f.addAction(BluetoothDevice.ACTION_FOUND);
            f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
            f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
            f.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
            f.addAction(BluetoothDevice.ACTION_NAME_CHANGED);
            appCtx.registerReceiver(btReceiver, f);
            receiverRegistered = true;
        } catch (Exception e) {
            Log.w(TAG, "registerReceiver", e);
        }
    }

    private void dropReceiver() {
        if (!receiverRegistered || appCtx == null) return;
        try { appCtx.unregisterReceiver(btReceiver); } catch (Exception ignored) {}
        receiverRegistered = false;
    }

    private void refreshBondedIntoSeen() {
        if (adapter == null) return;
        try {
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded == null) return;
            synchronized (seen) {
                for (BluetoothDevice d : bonded) {
                    upsertSeen(d, true, false, Integer.MIN_VALUE);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "refreshBonded", e);
        }
    }

    private void upsertSeen(BluetoothDevice d, boolean bonded, boolean nearby, int rssi) {
        if (d == null) return;
        String mac;
        try { mac = d.getAddress(); } catch (Exception e) { return; }
        if (mac == null || mac.isEmpty()) return;
        mac = normMac(mac);
        String name = safeName(d);
        String kind = kindOf(d);
        boolean likely = isLikelyHost(d);
        synchronized (seen) {
            SeenDevice s = seen.get(mac);
            if (s == null) {
                s = new SeenDevice();
                s.mac = mac;
                seen.put(mac, s);
            }
            s.mac = mac;
            if (name != null && !name.isEmpty() && !name.equals(mac)) s.name = name;
            else if (s.name == null || s.name.isEmpty()) s.name = mac;
            s.kind = kind;
            s.likelyHost = likely;
            if (bonded) s.bonded = true;
            if (nearby) s.nearby = true;
            if (rssi != Integer.MIN_VALUE) s.rssi = rssi;
        }
    }

    private static String normMac(String mac) {
        return mac == null ? "" : mac.trim().toUpperCase(Locale.US);
    }

    private final BroadcastReceiver btReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String a = intent.getAction();
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(a)) {
                int st = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                if (st == BluetoothAdapter.STATE_ON) {
                    if (wantRunning.get()) {
                        setStatus("Bluetooth on");
                        main.post(() -> {
                            if (wantRunning.get() && appCtx != null) start(appCtx);
                        });
                    }
                    notifyHosts();
                } else if (st == BluetoothAdapter.STATE_OFF) {
                    registered.set(false);
                    registering.set(false);
                    ready.set(false);
                    host = null;
                    hid = null;
                    proxyRequested.set(false);
                    scanning.set(false);
                    connectingMac = "";
                    if (wantRunning.get()) setStatus("Bluetooth off — tap Enable");
                    else setStatus("off");
                    notifyHosts();
                }
            } else if (BluetoothAdapter.ACTION_DISCOVERY_STARTED.equals(a)) {
                scanning.set(true);
                setStatus("scanning for PCs…");
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(a)) {
                // MTK often finishes immediately if stack was busy — retry while window open
                long left = scanWindowUntil - System.currentTimeMillis();
                if (scanning.get() && left > 1500 && wantRunning.get() && !ready.get()) {
                    Log.i(TAG, "discovery finished early, retry leftMs=" + left);
                    main.postDelayed(scanAttempt, 500);
                    setStatus("scanning for PCs…");
                    return;
                }
                scanning.set(false);
                main.removeCallbacks(scanTimeout);
                main.removeCallbacks(scanAttempt);
                if (!ready.get()) {
                    if (preferredMac != null && !preferredMac.isEmpty())
                        setStatus("scan done — connecting " + preferredName());
                    else
                        setStatus("scan done — tap a PC");
                }
                notifyHosts();
            } else if (BluetoothDevice.ACTION_FOUND.equals(a)
                    || BluetoothDevice.ACTION_NAME_CHANGED.equals(a)) {
                BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                short rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE);
                if (d != null) {
                    upsertSeen(d, d.getBondState() == BluetoothDevice.BOND_BONDED, true,
                        rssi == Short.MIN_VALUE ? Integer.MIN_VALUE : rssi);
                    notifyHosts();
                }
            } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(a)) {
                int bond = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1);
                BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (d == null) return;
                String mac = "";
                try { mac = normMac(d.getAddress()); } catch (Exception ignored) {}
                upsertSeen(d, bond == BluetoothDevice.BOND_BONDED, true, Integer.MIN_VALUE);
                if (bond == BluetoothDevice.BOND_BONDED) {
                    setStatus("paired " + safeName(d));
                    final String bondedMac = mac;
                    boolean wantThis = preferredMac != null && preferredMac.equalsIgnoreCase(bondedMac)
                        || pendingConnectMac != null && pendingConnectMac.equalsIgnoreCase(bondedMac);
                    if (wantThis) {
                        pendingConnectMac = bondedMac;
                        connectingMac = bondedMac;
                        if (appCtx != null) setPreferred(appCtx, bondedMac, safeName(d));
                        main.postDelayed(() -> {
                            if (!wantRunning.get()) return;
                            if (!registered.get()) start(appCtx);
                            else connectMac(bondedMac);
                        }, 500);
                    }
                    notifyHosts();
                } else if (bond == BluetoothDevice.BOND_NONE) {
                    if (preferredMac != null && preferredMac.equalsIgnoreCase(mac)) {
                        setStatus("pairing cancelled");
                        connectingMac = "";
                        notifyHosts();
                    }
                } else if (bond == BluetoothDevice.BOND_BONDING) {
                    setStatus("pairing with " + safeName(d) + "…");
                }
            } else if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(a)
                    || BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(a)) {
                if (wantRunning.get() && registered.get() && !ready.get() && !connectInFlight) {
                    main.postDelayed(() -> {
                        if (wantRunning.get() && registered.get() && !ready.get() && !connectInFlight && !hidIsConnecting())
                            connectPreferred();
                    }, reconnectBackoffMs);
                }
            }
        }
    };

    private final BluetoothProfile.ServiceListener serviceListener = new BluetoothProfile.ServiceListener() {
        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (profile != BluetoothProfile.HID_DEVICE) return;
            if (!wantRunning.get()) {
                try {
                    if (adapter != null)
                        adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, proxy);
                } catch (Exception ignored) {}
                return;
            }
            hid = (BluetoothHidDevice) proxy;
            if (!registered.get()) registerApp();
            else connectPreferred();
        }

        @Override
        public void onServiceDisconnected(int profile) {
            if (profile != BluetoothProfile.HID_DEVICE) return;
            hid = null;
            registered.set(false);
            registering.set(false);
            ready.set(false);
            host = null;
            proxyRequested.set(false);
            connectingMac = "";
            if (wantRunning.get()) {
                setStatus("profile lost — retry");
                scheduleRetry(1500);
            } else {
                setStatus("off");
            }
            notifyHosts();
        }
    };

    private final BluetoothHidDevice.Callback hidCallback = new BluetoothHidDevice.Callback() {
        @Override
        public void onAppStatusChanged(BluetoothDevice pluggedDevice, boolean reg) {
            registering.set(false);
            registered.set(reg);
            if (reg) {
                if (pluggedDevice != null) {
                    host = pluggedDevice;
                    ready.set(true);
                    connectingMac = "";
                    rememberHost(pluggedDevice);
                    setStatus("connected " + safeName(pluggedDevice));
                } else if (preferredMac != null && !preferredMac.isEmpty()) {
                    setStatus("ready — connecting " + preferredName());
                    connectPreferred();
                } else {
                    setStatus("ready — scanning for PCs…");
                    // Inquiry after register — starting scan during register aborts on MTK
                    if (appCtx != null && !scanning.get()) {
                        main.postDelayed(() -> {
                            if (wantRunning.get() && registered.get()
                                    && (preferredMac == null || preferredMac.isEmpty())
                                    && !ready.get()) {
                                startScan(appCtx);
                            }
                        }, 600);
                    }
                }
            } else {
                ready.set(false);
                host = null;
                connectingMac = "";
                if (wantRunning.get()) {
                    setStatus("unregistered — retry");
                    scheduleRetry(1500);
                } else {
                    setStatus("off");
                }
            }
            notifyHosts();
        }

        @Override
        public void onConnectionStateChanged(BluetoothDevice device, int state) {
            Log.i(TAG, "onConnectionStateChanged " + safeName(device) + " state=" + state);
            if (state == BluetoothProfile.STATE_CONNECTED) {
                host = device;
                ready.set(true);
                connectingMac = "";
                resetReconnectBackoff();
                forbidHostAudio(device);
                rememberHost(device);
                // B6 1.99: Snapdragon may keep prior-link click/mods after re-pair.
                // Pure all-up while the new ACL is live clears host residual.
                try { releaseHostInputBeforeDrop(); } catch (Exception ignored) {}
                setStatus("connected " + safeName(device));
                notifyHosts();
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                connectInFlight = false;
                lastConnectAttemptMs = 0L;
                if (host != null && host.equals(device)) {
                    // B6 1.99: release while host ref still valid (stop path only
                    // covered intentional disconnect; remote drop left stuck click).
                    try { releaseHostInputBeforeDrop(); } catch (Exception ignored) {}
                    host = null;
                    ready.set(false);
                    resetMouseButtonBaseline();
                    BtMouseSock.resetSeq();
                    BtMouseSock.zeroAllMirrors();
                    connectInFlight = false;
                    bumpReconnectBackoff();
                    if (wantRunning.get() && registered.get()) {
                        setStatus("disconnected — retrying " + preferredName());
                        connectingMac = preferredMac;
                        scheduleRetry(800);
                    } else if (registered.get()) {
                        setStatus("ready — scan & pick a PC");
                    }
                    notifyHosts();
                }
            } else if (state == BluetoothProfile.STATE_CONNECTING) {
                try { connectingMac = normMac(device.getAddress()); } catch (Exception ignored) {}
                setStatus("connecting " + safeName(device) + "…");
                notifyHosts();
            }
        }

        @Override public void onGetReport(BluetoothDevice device, byte type, byte id, int bufferSize) {}
        @Override public void onSetReport(BluetoothDevice device, byte type, byte id, byte[] data) {}
        @Override public void onSetProtocol(BluetoothDevice device, byte protocol) {}
        @Override public void onInterruptData(BluetoothDevice device, byte reportId, byte[] data) {}

        @Override
        public void onVirtualCableUnplug(BluetoothDevice device) {
            if (host != null && host.equals(device)) {
                // B6 1.99: unplug is a drop — release before host null.
                try { releaseHostInputBeforeDrop(); } catch (Exception ignored) {}
                host = null;
                ready.set(false);
                resetMouseButtonBaseline();
                BtMouseSock.resetSeq();
                BtMouseSock.zeroAllMirrors();
                if (wantRunning.get()) {
                    setStatus("unplugged — retrying");
                    connectingMac = preferredMac;
                    scheduleRetry(1000);
                }
                notifyHosts();
            }
        }
    };

    private synchronized void registerApp() {
        if (hid == null || !wantRunning.get()) return;
        if (registered.get()) {
            connectPreferred();
            return;
        }
        if (!registering.compareAndSet(false, true)) return;
        try {
            BluetoothHidDeviceAppSdpSettings sdp = new BluetoothHidDeviceAppSdpSettings(
                "Titan 2 HID",
                "Titan keyboard + mouse",
                "titanus2",
                BluetoothHidDevice.SUBCLASS1_COMBO,
                DESCRIPTOR
            );
            // B6 2.10: request low-latency interrupt QoS (null = stack defaults often
            // sniff/lag on Snapdragon hosts). latency ~11.25ms matches ~8–9×1.25ms
            // connection events; BEST_EFFORT is portable across host stacks.
            BluetoothHidDeviceAppQosSettings qos = new BluetoothHidDeviceAppQosSettings(
                BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
                800,
                9,
                0,
                11250,
                BluetoothHidDeviceAppQosSettings.MAX
            );
            java.util.concurrent.Executor ex = r -> main.post(r);
            boolean ok = hid.registerApp(sdp, qos, qos, ex, hidCallback);
            Log.i(TAG, "registerApp ok=" + ok + " qos=BEST_EFFORT");
            if (ok) {
                setStatus("registering as keyboard…");
            } else {
                registering.set(false);
                if (registered.get()) {
                    connectPreferred();
                } else {
                    setStatus("register failed — retry");
                    scheduleRetry(2000);
                }
            }
        } catch (Exception e) {
            registering.set(false);
            Log.e(TAG, "registerApp", e);
            setStatus("register error — retry");
            scheduleRetry(2000);
        }
    }

    private boolean hidIsConnecting() {
        if (hid == null || adapter == null) return connectInFlight;
        try {
            String mac = (connectingMac != null && !connectingMac.isEmpty()) ? connectingMac : preferredMac;
            if (mac == null || mac.isEmpty()) return connectInFlight;
            BluetoothDevice d = adapter.getRemoteDevice(mac);
            return hid.getConnectionState(d) == BluetoothProfile.STATE_CONNECTING;
        } catch (Exception e) {
            return connectInFlight;
        }
    }

    private void bumpReconnectBackoff() {
        long n = reconnectBackoffMs <= 0L ? 2500L : reconnectBackoffMs * 2L;
        if (n < 2500L) n = 2500L;
        if (n > 15000L) n = 15000L;
        reconnectBackoffMs = n;
        Log.i(TAG, "reconnect backoff " + reconnectBackoffMs + "ms");
    }

    private void resetReconnectBackoff() {
        reconnectBackoffMs = 2500L;
        connectInFlight = false;
    }

    private void forbidHostAudio(BluetoothDevice d) {
        if (d == null || adapter == null) return;
        try {
            java.lang.reflect.Method m = BluetoothAdapter.class.getMethod(
                "setProfileConnectionPolicy", BluetoothDevice.class, int.class, int.class);
            int off = 0;
            try { off = BluetoothProfile.class.getField("CONNECTION_POLICY_FORBIDDEN").getInt(null); }
            catch (Exception ignored) {}
            Object a2 = m.invoke(adapter, d, BluetoothProfile.A2DP, off);
            Object hs = m.invoke(adapter, d, BluetoothProfile.HEADSET, off);
            Log.i(TAG, "audio policy off A2DP=" + a2 + " HEADSET=" + hs + " " + safeName(d));
            return;
        } catch (Exception ignored) {}
        forbidHostAudioViaPriority(d, BluetoothProfile.A2DP);
        forbidHostAudioViaPriority(d, BluetoothProfile.HEADSET);
    }

    private void forbidHostAudioViaPriority(BluetoothDevice d, final int profile) {
        if (adapter == null || appCtx == null || d == null) return;
        try {
            adapter.getProfileProxy(appCtx, new BluetoothProfile.ServiceListener() {
                @Override public void onServiceConnected(int pr, BluetoothProfile proxy) {
                    try {
                        java.lang.reflect.Method sp = proxy.getClass().getMethod(
                            "setPriority", BluetoothDevice.class, int.class);
                        Object r = sp.invoke(proxy, d, 0);
                        Log.i(TAG, "setPriority 0 profile=" + pr + " -> " + r);
                    } catch (Exception e) {
                        Log.w(TAG, "setPriority profile=" + pr, e);
                    }
                    try { adapter.closeProfileProxy(pr, proxy); } catch (Exception ignored) {}
                }
                @Override public void onServiceDisconnected(int pr) {}
            }, profile);
        } catch (Exception e) {
            Log.w(TAG, "forbid audio profile=" + profile, e);
        }
    }

    private boolean mouseBlockedByTyping() {
        // Host-mouse freeze while typing (BT exclusive must still honor pause).
        if (readPlaneInt("titan2_pad_cursor_pause", 0) == 1) return true;
        long act = readPlaneLong("titan2_key_activity", 0L);
        if (act <= 0L) return false;
        long now = System.currentTimeMillis();
        long actMs = act;
        // Plane may store epoch seconds or milliseconds.
        if (actMs < 10_000_000_000L) actMs *= 1000L;
        long age = now - actMs;
        if (age < 0L) return false;
        int cool = readPlaneInt("titan2_pad_cursor_cool_ms", 0);
        if (cool < 100) cool = readPlaneInt("titan2_pad_cursor_pause_ms", 500);
        if (cool < 100) cool = 500;
        if (cool > 5000) cool = 5000;
        return age < (long) cool;
    }

    private static int readPlaneInt(String name, int def) {
        String s = readPlane(name);
        if (s == null || s.isEmpty()) return def;
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    private static long readPlaneLong(String name, long def) {
        String s = readPlane(name);
        if (s == null || s.isEmpty()) return def;
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return def; }
    }

    private static String readPlane(String name) {
        String[] roots = { "/data/misc/titan2/", "/data/local/tmp/" };
        for (String r : roots) {
            File f = new File(r + name);
            if (!f.isFile()) continue;
            try (FileInputStream in = new FileInputStream(f)) {
                byte[] b = new byte[32];
                int n = in.read(b);
                if (n <= 0) continue;
                return new String(b, 0, n, java.nio.charset.StandardCharsets.US_ASCII).trim();
            } catch (Exception ignored) {}
        }
        return "";
    }

    /** Connect to the user-selected preferred host only (no silent random pick). */
    private void connectPreferred() {
        if (!registered.get() || hid == null || adapter == null) return;
        if (ready.get() && host != null) return;
        if (preferredMac == null || preferredMac.isEmpty()) {
            if (!ready.get()) setStatus("scan & pick a PC to control");
            return;
        }
        connectMac(preferredMac);
    }

    private boolean connectMac(String mac) {
        if (hid == null || adapter == null || mac == null || mac.isEmpty()) return false;
        try {
            stopScan();
            BluetoothDevice d = adapter.getRemoteDevice(mac);
            return connectDevice(d);
        } catch (Exception e) {
            Log.w(TAG, "connectMac " + mac, e);
            return false;
        }
    }

    private boolean connectDevice(BluetoothDevice d) {
        if (hid == null || d == null) return false;
        try {
            int bond = d.getBondState();
            if (bond != BluetoothDevice.BOND_BONDED) {
                setStatus("pairing with " + safeName(d) + "…");
                connectingMac = normMac(d.getAddress());
                pendingConnectMac = connectingMac;
                d.createBond();
                notifyHosts();
                return false;
            }
            connectingMac = normMac(d.getAddress());
            long now = android.os.SystemClock.uptimeMillis();
            if (lastConnectAttemptMs > 0L && (now - lastConnectAttemptMs) < 800L) {
                Log.i(TAG, "hid.connect gap skip");
                return false;
            }
            lastConnectAttemptMs = now;
            setStatus("connecting " + safeName(d) + "...");
            connectInFlight = true;
            boolean c = hid.connect(d);
            Log.i(TAG, "hid.connect " + safeName(d) + " ok=" + c);
            if (!c) {
                connectInFlight = false;
                setStatus("connect failed - is PC Bluetooth on?");
                connectingMac = "";
            }
            notifyHosts();
            return c;
        } catch (Exception e) {
            Log.w(TAG, "connectDevice", e);
            connectInFlight = false;
            setStatus("connect failed");
            connectingMac = "";
            notifyHosts();
            return false;
        }
    }

    private void rememberHost(BluetoothDevice d) {
        if (d == null || appCtx == null) return;
        try {
            String mac = normMac(d.getAddress());
            String name = safeName(d);
            setPreferred(appCtx, mac, name);
            upsertSeen(d, true, true, Integer.MIN_VALUE);
        } catch (Exception ignored) {}
    }

    private static boolean isLikelyHost(BluetoothDevice d) {
        try {
            BluetoothClass c = d.getBluetoothClass();
            if (c == null) return true; // unknown — let user try
            int major = c.getMajorDeviceClass();
            // Computer, Phone (some tablets), Imaging/AV that host HID
            if (major == BluetoothClass.Device.Major.COMPUTER) return true;
            if (major == BluetoothClass.Device.Major.PHONE) return true;
            if (major == BluetoothClass.Device.Major.MISC) return true;
            if (major == BluetoothClass.Device.Major.UNCATEGORIZED) return true;
            // Audio/video sinks usually cannot host a keyboard
            if (major == BluetoothClass.Device.Major.AUDIO_VIDEO) return false;
            if (major == BluetoothClass.Device.Major.PERIPHERAL) return false;
            if (major == BluetoothClass.Device.Major.WEARABLE) return false;
            if (major == BluetoothClass.Device.Major.TOY) return false;
            if (major == BluetoothClass.Device.Major.HEALTH) return false;
            return true;
        } catch (Exception e) {
            return true;
        }
    }

    private static String kindOf(BluetoothDevice d) {
        try {
            BluetoothClass c = d.getBluetoothClass();
            if (c == null) return "Other";
            switch (c.getMajorDeviceClass()) {
                case BluetoothClass.Device.Major.COMPUTER: return "PC";
                case BluetoothClass.Device.Major.PHONE: return "Phone";
                case BluetoothClass.Device.Major.AUDIO_VIDEO: return "Audio";
                case BluetoothClass.Device.Major.PERIPHERAL: return "Peripheral";
                case BluetoothClass.Device.Major.IMAGING: return "Imaging";
                case BluetoothClass.Device.Major.WEARABLE: return "Wearable";
                case BluetoothClass.Device.Major.TOY: return "Toy";
                case BluetoothClass.Device.Major.HEALTH: return "Health";
                default: return "Other";
            }
        } catch (Exception e) {
            return "Other";
        }
    }

    private static String safeName(BluetoothDevice d) {
        try {
            String n = d.getName();
            if (n != null && !n.isEmpty()) return n;
        } catch (Exception ignored) {}
        try { return d.getAddress(); } catch (Exception ignored) {}
        return "host";
    }

    public boolean handlePacket(byte[] p) {
        if (p == null || p.length < 1 || !ready.get() || hid == null || host == null) return false;
        try {
            switch (p[0] & 0xff) {
                case 0x01:
                    if (p.length < 4) return false;
                    flushMouseNow();
                    applyKey(p[1], p[2] & 0xff, p[3] != 0);
                    return sendKbd();
                case 0x02:
                    if (p.length < 4) return false;
                    return queueMouse(p[3] & 0xff, (int) p[1], (int) p[2], 0);
                case 0x03:
                    if (p.length < 2) return false;
                    flushMouseNow();
                    mouseButtons = p[1] & 0xff;
                    return sendMouseRaw(mouseButtons, 0, 0, 0);
                case 0x04:
                    if (p.length < 2) return false;
                    return queueMouse(mouseButtons, 0, 0, (int) p[1]);
                default:
                    return false;
            }
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Full-int motion from mailbox / bridge (B6). Values may exceed int8;
     * residual is preserved across immediate reflush packets when BT is healthy.
     */
    public boolean queueHostMouse(int buttons, int dx, int dy, int wheel) {
        return queueMouse(buttons, dx, dy, wheel);
    }

    /**
     * Sum motion on this thread (drain). No main-looper hop.
     * While BT is busy, keep accumulating into one pending sample so the next
     * flush after sendReport returns still tracks the finger (B6). Never build
     * a multi-report time-ordered backlog that plays out a second later.
     */
    private boolean queueMouse(int buttons, int dx, int dy, int wheel) {
        // B6 2.07: during Stop/drop, never re-arm click into pend while
        // late-settle still owns busy (fold→reflush stuck host button).
        if (mouseDropGate.get()) {
            return false;
        }
        // Freeze host mouse while typing: drop REL/wheel/new clicks.
        // Pure button-up still flows (stuck-click release). forceMouseButtonUp
        // uses sendReport directly and bypasses this path.
        if (mouseBlockedByTyping()) {
            if (dx != 0 || dy != 0 || wheel != 0) return true;
            if ((buttons & 0x07) != 0) return true;
        }
        synchronized (mouseLock) {
            pendButtons = buttons & 0x07;
            mouseButtons = pendButtons;
            // Always sum — one report carries the window; busy reflush emits it.
            pendDx += dx;
            pendDy += dy;
            pendWheel += wheel;
            mousePending = true;
        }
        flushMouseNow();
        synchronized (mouseLock) {
            return !mousePending;
        }
    }

    private void flushMouseNow() {
        if (mouseDropGate.get()) {
            return;
        }
        if (mouseSendBusy.get()) {
            // sendReport in flight — residual stays pending; finally re-flushes
            return;
        }
        int dx, dy, wh, btn;
        boolean btnEdge;
        synchronized (mouseLock) {
            if (!mousePending) return;
            dx = clampI8(pendDx);
            dy = clampI8(pendDy);
            wh = clampI8(pendWheel);
            btn = pendButtons & 0x07;
            mouseButtons = btn;
            // 1.95: button edge (incl. release→0) always ships even with 0 motion
            btnEdge = (btn != lastSentMouseButtons);
            // Congested Snapdragon: latest-wins — drop residual so catch-up
            // never rubber-bands a second of motion. Healthy BT: keep residual
            // for immediate reflush packets (distance preserved, not delayed).
            boolean congested = lastMouseSendNs > MOUSE_CONGEST_NS
                    || mouseReflushDepth >= MOUSE_REFLUSH_MAX;
            if (congested) {
                // Drop motion residual; current packet still carries btn (edge ok)
                pendDx = 0;
                pendDy = 0;
                pendWheel = 0;
                mousePending = false;
            } else {
                pendDx -= dx;
                pendDy -= dy;
                pendWheel -= wh;
                mousePending = (pendDx != 0 || pendDy != 0 || pendWheel != 0);
            }
        }
        // Zero motion + no button change = nothing to send (idle poll noise).
        // Button release (btn=0 after btn=1) is an edge and must go out.
        if (dx == 0 && dy == 0 && wh == 0 && !btnEdge) return;
        sendMouseRaw(btn, dx, dy, wh);
    }

    private static int clampI8(int v) {
        if (v > 127) return 127;
        if (v < -127) return -127;
        return v;
    }

    private void applyKey(byte mod, int usage, boolean press) {
        if (usage >= 0xe0 && usage <= 0xe7) {
            int bit = 1 << (usage - 0xe0);
            if (press) keyState.mods |= bit;
            else keyState.mods &= ~bit;
            return;
        }
        // Soft extra bits OR in (Shift+A). Never replace the mask — that
        // dropped held physical/virtual Alt so Alt+Tab arrived as bare Tab.
        // Releases go through e0–e7 (is_mod path above).
        if (press && mod != 0) {
            keyState.mods |= mod;
        }
        if (press) {
            for (int i = 0; i < 6; i++) if (keyState.keys[i] == (byte) usage) return;
            for (int i = 0; i < 6; i++) {
                if (keyState.keys[i] == 0) {
                    keyState.keys[i] = (byte) usage;
                    return;
                }
            }
        } else {
            for (int i = 0; i < 6; i++) {
                if (keyState.keys[i] == (byte) usage) {
                    for (int j = i; j < 5; j++) keyState.keys[j] = keyState.keys[j + 1];
                    keyState.keys[5] = 0;
                    return;
                }
            }
        }
    }

    /**
     * Empty keyboard report — clears sticky mods/keys after Type inject / Stop.
     * B6 2.09: retry once on sendReport=false (AOSP interrupt channel can drop
     * under congestion; hosts latch Shift/Alt until a successful empty lands).
     */
    public void releaseAllKeys() {
        for (int i = 0; i < 6; i++) keyState.keys[i] = 0;
        keyState.mods = 0;
        try {
            if (!sendKbd()) {
                try {
                    Thread.sleep(2);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                sendKbd();
            }
        } catch (Exception ignored) {}
    }

    private boolean sendKbd() {
        if (hid == null || host == null) return false;
        byte[] r = new byte[]{
            keyState.mods, 0,
            keyState.keys[0], keyState.keys[1], keyState.keys[2],
            keyState.keys[3], keyState.keys[4], keyState.keys[5]
        };
        try {
            boolean ok = hid.sendReport(host, ID_KBD, r);
            // Hot path: never Log.i — every keystroke on Snapdragon hosts
            // was multi-ms of logd and contributed to HID lag.
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "sendReport kbd ok=" + ok
                    + " keys=" + String.format(Locale.US, "%02x%02x%02x",
                        r[2] & 0xff, r[3] & 0xff, r[4] & 0xff));
            }
            return ok;
        } catch (Exception e) {
            Log.w(TAG, "sendReport kbd", e);
            return false;
        }
    }

    private boolean sendMouseRaw(int buttons, int dx, int dy, int wheel) {
        if (hid == null || host == null) return false;
        // B6 2.07: release window owns the plane — no new click/motion reports
        // (forceMouseButtonUp uses sendReport directly, not this path).
        if (mouseDropGate.get()) {
            return false;
        }
        // Freeze host mouse while typing: drop REL/wheel/new clicks.
        // Pure button-up still flows (stuck-click release). forceMouseButtonUp
        // uses sendReport directly and bypasses this path.
        if (mouseBlockedByTyping()) {
            if (dx != 0 || dy != 0 || wheel != 0) return true;
            if ((buttons & 0x07) != 0) return true;
        }
        // Snapshot drop epoch so a release during sendReport cannot be undone
        // by lastSent rewrite or residual reflush (B6 2.01).
        final int epochAtStart = mouseDropEpoch.get();
        if (!mouseSendBusy.compareAndSet(false, true)) {
            // Overlapping send — fold this sample into pending; finally reflush
            // (unless drop gate/epoch advanced — then discard, never re-arm click).
            if (mouseDropGate.get() || mouseDropEpoch.get() != epochAtStart) {
                return false;
            }
            synchronized (mouseLock) {
                pendDx += dx;
                pendDy += dy;
                pendWheel += wheel;
                pendButtons = buttons & 0x07;
                mousePending = true;
            }
            return false;
        }
        byte[] r = new byte[]{
            (byte) (buttons & 0x07),
            (byte) clampI8(dx), (byte) clampI8(dy), (byte) clampI8(wheel)
        };
        try {
            long t0 = System.nanoTime();
            boolean ok = hid.sendReport(host, ID_MOUSE, r);
            lastMouseSendNs = System.nanoTime() - t0;
            // 1.95 + 2.01: advance edge baseline only on success AND only if no
            // drop started while we were blocked in sendReport.
            if (ok && mouseDropEpoch.get() == epochAtStart) {
                lastSentMouseButtons = buttons & 0x07;
            }
            // If BT took >25ms, Snapdragon host is congested — residual path
            // drops instead of multi-packet catch-up (see flushMouseNow).
            if (Log.isLoggable(TAG, Log.DEBUG) && lastMouseSendNs > MOUSE_CONGEST_NS) {
                Log.d(TAG, "sendReport mouse slow ns=" + lastMouseSendNs + " ok=" + ok);
            }
            return ok;
        } catch (Exception e) {
            Log.w(TAG, "sendReport mouse", e);
            return false;
        } finally {
            // Drop epoch advanced → release owns the plane; do not reflush.
            // 2.03/2.05: this sendReport may have delivered a late click *after*
            // releaseHostInputBeforeDrop returned. Emit one pure zero while still
            // owning mouseSendBusy so a concurrent sample cannot CAS-start another
            // click between clear and settle (stuck host button residual).
            if (mouseDropEpoch.get() != epochAtStart) {
                synchronized (mouseLock) {
                    pendDx = 0;
                    pendDy = 0;
                    pendWheel = 0;
                    pendButtons = 0;
                    mousePending = false;
                    mouseButtons = 0;
                }
                if (hid != null && host != null && (buttons & 0x07) != 0) {
                    try {
                        byte[] z = new byte[]{0, 0, 0, 0};
                        boolean zOk = false;
                        try {
                            zOk = hid.sendReport(host, ID_MOUSE, z);
                        } catch (Exception e) {
                            Log.w(TAG, "late-drop settle zero", e);
                        }
                        if (!zOk) {
                            try {
                                Thread.sleep(8);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                            }
                            try {
                                hid.sendReport(host, ID_MOUSE, z);
                            } catch (Exception e) {
                                Log.w(TAG, "late-drop settle zero retry", e);
                            }
                        }
                    } catch (Exception ignored) {}
                }
                lastSentMouseButtons = 0;
                mouseSendBusy.set(false);
            } else if (mouseReflushDepth < MOUSE_REFLUSH_MAX) {
                // Must clear busy before reflush can re-enter sendMouseRaw.
                mouseSendBusy.set(false);
                // B6: (1) motion that arrived during sendReport must not wait for
                // the next EV_REL (finger-stop stutter). (2) residual beyond int8
                // drains as immediate chained packets while BT is healthy.
                mouseReflushDepth++;
                try {
                    flushMouseNow();
                } finally {
                    mouseReflushDepth--;
                }
            } else {
                // Hard drop leftover after max chain — next EV_REL restarts.
                // Keep lastSentMouseButtons so a later pure release still edges.
                synchronized (mouseLock) {
                    pendDx = 0;
                    pendDy = 0;
                    pendWheel = 0;
                    mousePending = false;
                }
                mouseSendBusy.set(false);
            }
        }
    }

    /** Reset mouse edge baseline (session end / disconnect). */
    void resetMouseButtonBaseline() {
        synchronized (mouseLock) {
            lastSentMouseButtons = -1;
            pendDx = 0;
            pendDy = 0;
            pendWheel = 0;
            pendButtons = 0;
            mousePending = false;
            mouseButtons = 0;
        }
    }
}
