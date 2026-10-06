package com.titanus2.usbhid;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.util.Log;
import android.util.SparseArray;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * BLE cursor link for a neckband. Java only scans, connects, and writes.
 * Frame bytes come from {@link NeckbandNative}. Classic HID stays in place
 * until {@link #captures()} is true, and the USB gadget path is never replaced.
 * <p>
 * Auto and On take the trackpad and keyboard when the neckband service is up.
 * They stay there until Off or the link drops. Off releases them.
 */
public final class NeckbandLink {
    private static final String TAG = "NeckbandLink";
    private static final String PREFS = "usb_hid";
    private static final String PREF_MODE = "nb_mode";
    private static final String PREF_GAIN = "nb_gain";

    public static final int MODE_OFF = 0;
    public static final int MODE_AUTO = 1;
    public static final int MODE_ON = 2;

    /** Remote service. Interop id, not a copied client. */
    private static final UUID SVC =
        UUID.fromString("b8e4f3a9-5445-4907-a324-b200f571f4cc");
    private static final UUID WRITE =
        UUID.fromString("294d469d-10ea-47ed-9710-7a7333e80a10");
    private static final UUID NOTIFY =
        UUID.fromString("ed3aaf63-d1cd-43c0-b287-d662284f4198");
    private static final UUID CUSTOM =
        UUID.fromString("97777cdf-9130-4b95-a24d-e70c2158e162");
    private static final UUID CCCD =
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final int QUEUE_MAX = 32;
    private static final int WRITE_FAIL_MAX = 8;
    private static final long BLACKLIST_MS = 120_000L;
    /** Backup if the stack never completes a no-response write. The callback is the pace. */
    private static final int WRITE_PACE_HOVER_MS = 8;
    private static final int WRITE_PACE_KEY_MS = 12;
    private static final int BUSY_RETRY_MS = 2;
    /** ~160ms of writeCharacteristic busy, then the link is stuck and must reconnect. */
    private static final int BUSY_GIVE_UP = 80;

    public interface Listener {
        void onLinkChanged();
        void onTextWanted();
    }

    private static final class Out {
        final byte[] bytes;
        final boolean hover;
        Out(byte[] bytes, boolean hover) {
            this.bytes = bytes;
            this.hover = hover;
        }
    }

    private static volatile NeckbandLink inst;

    private final Context app;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    /** Keys, clicks, and auth. Sent before hover. Link thread only. */
    private final ArrayDeque<Out> urgent = new ArrayDeque<>();
    /** One coalesced move. Link thread only. */
    private Out pendingHover;
    private final android.util.ArrayMap<String, Long> skipUntil = new android.util.ArrayMap<>();

    private HandlerThread thread;
    private Handler io;
    private boolean opened;
    private boolean rxReg;

    private volatile int mode = MODE_AUTO;
    private volatile int gain = 100;
    private volatile boolean captureFlag;
    private volatile boolean gattReady;
    private volatile String statusText = "off";
    private volatile String peerName = "";

    private BluetoothLeScanner scanner;
    private boolean scanning;
    private BluetoothGatt gatt;
    private int connectGen;
    private boolean linkUp;
    private BluetoothGattCharacteristic writeChar;
    private BluetoothGattCharacteristic notifyChar;
    private int writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
    private boolean discovering;
    private boolean discovered;
    private boolean authed;
    private int descStep;
    private boolean inflight;
    /** Written on the link thread, read on the GATT binder thread. */
    private volatile int inflightGen = -1;
    private long lastTextMs;
    private int writeSerial;
    private int writeFails;
    private Out inflightFrame;
    private final AtomicBoolean hoverKick = new AtomicBoolean(false);
    private int offWaits;
    private boolean descBusy;
    /** 1 while the descriptor write we issued is still the one we will honor. */
    private int descWait;
    private int descGen;
    /** Completions to ignore after a proceed beat the callback. Link thread only. */
    private int skipWrites;
    private int busyStreak;
    /** Service found. Capture follows this, not a poll of classic HID. */
    private volatile boolean armed;

    public static void ensure(Context ctx) {
        if (ctx == null) return;
        Context app = ctx.getApplicationContext();
        synchronized (NeckbandLink.class) {
            if (inst == null) inst = new NeckbandLink(app);
        }
        inst.open();
    }

    public static void setMode(Context ctx, int mode) {
        ensure(ctx);
        final int next = (mode < MODE_OFF || mode > MODE_ON) ? MODE_AUTO : mode;
        NeckbandLink l = inst;
        l.mode = next;
        l.persist();
        Handler h = l.io;
        if (h != null) h.post(() -> l.applyMode(next));
    }

    public static void setGain(Context ctx, int pct) {
        ensure(ctx);
        int g = pct;
        if (g < 25) g = 25;
        if (g > 400) g = 400;
        NeckbandLink l = inst;
        l.gain = g;
        l.persist();
        if (NeckbandNative.LOADED) {
            try { NeckbandNative.setGain(g); } catch (Throwable ignored) {}
        }
    }

    public static int mode() {
        NeckbandLink l = inst;
        return l == null ? MODE_OFF : l.mode;
    }

    public static int gainPercent() {
        NeckbandLink l = inst;
        return l == null ? 100 : l.gain;
    }

    /** Hot path: one volatile read. No binder, file, or scan work. */
    public static boolean captures() {
        NeckbandLink l = inst;
        return l != null && l.captureFlag;
    }

    public static boolean held() {
        NeckbandLink l = inst;
        return l != null && l.gattReady && !l.captureFlag && l.mode != MODE_OFF;
    }

    public static String status() {
        NeckbandLink l = inst;
        return l == null ? "off" : l.statusText;
    }

    public static void addListener(Listener l) {
        if (l == null) return;
        NeckbandLink link = inst;
        if (link == null) return;
        if (!link.listeners.contains(l)) link.listeners.add(l);
    }

    public static void removeListener(Listener l) {
        NeckbandLink link = inst;
        if (link != null && l != null) link.listeners.remove(l);
    }

    /**
     * Soft record (0x01 key, 0x02 move, 0x03 buttons, 0x04 wheel).
     * False means the caller should use classic HID.
     */
    public static boolean offerPacket(byte[] rec) {
        NeckbandLink l = inst;
        if (l == null || !l.captureFlag || !NeckbandNative.LOADED) return false;
        if (rec == null || rec.length < 2) return false;
        int op = rec[0] & 0xff;
        switch (op) {
            case 0x01: {
                if (rec.length < 4) return false;
                byte[] frames = l.nativeKey(rec[1] & 0xff, rec[2] & 0xff, rec[3] != 0);
                if (frames != null) l.postUrgent(frames);
                return true;
            }
            case 0x02: {
                if (rec.length < 4) return false;
                return l.offerMotion(rec[3] & 0xff, rec[1], rec[2], 0);
            }
            case 0x03:
                return l.offerMotion(rec[1] & 0xff, 0, 0, 0);
            case 0x04:
                return l.offerMotion(-1, 0, 0, rec[1]);
            default:
                return false;
        }
    }

    /** Full-int hardware motion. {@code buttons < 0} keeps the previous buttons. */
    public static boolean offerMouse(int buttons, int dx, int dy, int wheel) {
        NeckbandLink l = inst;
        if (l == null || !l.captureFlag || !NeckbandNative.LOADED) return false;
        return l.offerMotion(buttons, dx, dy, wheel);
    }

    /** 8-byte boot report. False means classic HID should send it. */
    public static boolean offerKbd(byte[] report) {
        NeckbandLink l = inst;
        if (l == null || !l.captureFlag || !NeckbandNative.LOADED) return false;
        if (report == null || report.length < 8) return false;
        byte[] frames;
        try {
            frames = NeckbandNative.kbdReport(Arrays.copyOf(report, 8));
        } catch (Throwable t) {
            return false;
        }
        if (frames != null) l.postUrgent(frames);
        return true;
    }

    /**
     * Codec runs on the caller. The link thread only writes BLE, so a burst
     * of trackpad samples cannot sit in front of the write callback.
     */
    private boolean offerMotion(int buttons, int dx, int dy, int wheel) {
        byte[] frames = null;
        try {
            frames = NeckbandNative.feed(buttons, dx, dy, wheel);
        } catch (Throwable t) {
            Log.w(TAG, "feed", t);
            return false;
        }
        if (wheel != 0) scheduleScrollEnd();
        if (frames != null) postMotionEdge(frames);
        else if (dx != 0 || dy != 0) scheduleHover();
        return true;
    }

    /** Click or wheel follows the move already stored in the codec. */
    private void postMotionEdge(byte[] frames) {
        Handler h = io;
        if (h == null) return;
        byte[] copy = Arrays.copyOf(frames, frames.length);
        h.post(() -> {
            if (!captureFlag) return;
            byte[] hover = null;
            try { hover = NeckbandNative.takeHover(); } catch (Throwable ignored) {}
            if (hover != null) enqueue(hover, true);
            enqueueBlob(copy, false);
        });
    }

    private byte[] nativeKey(int mod, int usage, boolean press) {
        try {
            return NeckbandNative.keyEdge(mod, usage, press);
        } catch (Throwable t) {
            Log.w(TAG, "key", t);
            return null;
        }
    }

    /** Keys jump a queued move. One runnable per report. */
    private void postUrgent(byte[] frames) {
        Handler h = io;
        if (h == null || frames == null || frames.length == 0) return;
        byte[] copy = Arrays.copyOf(frames, frames.length);
        h.postAtFrontOfQueue(() -> {
            if (!captureFlag) return;
            enqueueBlob(copy, false);
        });
    }

    private void scheduleHover() {
        Handler h = io;
        if (h == null) return;
        if (!hoverKick.compareAndSet(false, true)) return;
        h.post(hoverTask);
    }

    private void scheduleScrollEnd() {
        Handler h = io;
        if (h == null) return;
        h.removeCallbacks(scrollEndTask);
        h.postDelayed(scrollEndTask, 70);
    }

    public static void releaseKeys() {
        NeckbandLink l = inst;
        if (l == null || !NeckbandNative.LOADED) return;
        Handler h = l.io;
        if (h == null) return;
        h.post(l::emitRelease);
    }

    /**
     * Chunked text frames. Returns the number of UTF-16 chars accepted,
     * or -1 so the caller can fall through to HID key taps.
     */
    public static int sendText(String text) {
        NeckbandLink l = inst;
        if (l == null || !l.captureFlag || !NeckbandNative.LOADED) return -1;
        if (text == null || text.isEmpty()) return -1;
        int cap = 15 * 40;
        int bytes = 0;
        int end = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int nb = utf8Len(cp);
            if (bytes + nb > cap) break;
            bytes += nb;
            i += Character.charCount(cp);
            end = i;
        }
        if (end <= 0) return -1;
        byte[] utf8 = text.substring(0, end).getBytes(StandardCharsets.UTF_8);
        byte[] frames;
        try {
            frames = NeckbandNative.text(utf8);
        } catch (Throwable t) {
            return -1;
        }
        if (frames == null || frames.length < 20) return -1;
        Handler h = l.io;
        if (h == null) return -1;
        byte[] copy = Arrays.copyOf(frames, frames.length);
        h.post(() -> {
            if (!l.captureFlag) return;
            l.enqueueBlob(copy, false);
        });
        return end;
    }

    private NeckbandLink(Context app) {
        this.app = app;
        SharedPreferences p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int m = p.getInt(PREF_MODE, MODE_AUTO);
        if (m < MODE_OFF || m > MODE_ON) m = MODE_AUTO;
        int g = p.getInt(PREF_GAIN, 100);
        if (g < 25) g = 25;
        if (g > 400) g = 400;
        mode = m;
        gain = g;
    }

    private void open() {
        synchronized (this) {
            if (opened) return;
            opened = true;
            thread = new HandlerThread("nb-link");
            thread.start();
            io = new Handler(thread.getLooper());
        }
        if (NeckbandNative.LOADED) {
            try { NeckbandNative.setGain(gain); } catch (Throwable ignored) {}
        }
        registerBt();
        io.post(this::kick);
        io.postDelayed(this::tick, 700);
    }

    private void kick() {
        if (mode == MODE_OFF) {
            publishStatus("off", true);
            return;
        }
        ensureScan();
        recompute();
    }

    private void tick() {
        try {
            recompute();
            if (mode != MODE_OFF && gatt == null && !scanning) ensureScan();
        } catch (Throwable t) {
            Log.w(TAG, "tick", t);
        }
        Handler h = io;
        if (h != null) h.postDelayed(this::tick, 700);
    }

    private void applyMode(int m) {
        mode = m;
        persist();
        if (m == MODE_OFF) {
            stopScan();
            boolean was = captureFlag;
            captureFlag = false;
            if (was) emitRelease();
            offWaits = 0;
            io.postDelayed(this::finishOff, was ? 40 : 0);
        } else {
            ensureScan();
        }
        recompute();
        try { HidSessionService.kickInputSocks(); } catch (Throwable ignored) {}
    }

    private void finishOff() {
        if (mode != MODE_OFF) return;
        if (hasOutbound() && offWaits < 20) {
            offWaits++;
            io.postDelayed(this::finishOff, 20);
            return;
        }
        offWaits = 0;
        scrubLink();
        BluetoothGatt old = gatt;
        gatt = null;
        if (old != null) {
            try { old.disconnect(); } catch (Throwable ignored) {}
            try { old.close(); } catch (Throwable ignored) {}
        }
        if (NeckbandNative.LOADED) {
            try {
                NeckbandNative.reset();
                NeckbandNative.setGain(gain);
            } catch (Throwable ignored) {}
        }
        recompute();
    }

    private void persist() {
        try {
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(PREF_MODE, mode)
                .putInt(PREF_GAIN, gain)
                .apply();
        } catch (Throwable ignored) {}
    }

    private void registerBt() {
        if (rxReg) return;
        try {
            IntentFilter f = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(btRx, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                app.registerReceiver(btRx, f);
            }
            rxReg = true;
        } catch (Throwable t) {
            Log.w(TAG, "receiver", t);
        }
    }

    private final BroadcastReceiver btRx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            if (intent == null) return;
            int st = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
            Handler h = io;
            if (h == null) return;
            h.post(() -> onAdapter(st));
        }
    };

    private void onAdapter(int st) {
        if (st == BluetoothAdapter.STATE_OFF || st == BluetoothAdapter.STATE_TURNING_OFF) {
            stopScan();
            if (captureFlag || gattReady) emitRelease();
            io.postDelayed(this::closeGatt, 60);
            recompute();
            return;
        }
        if (st == BluetoothAdapter.STATE_ON && mode != MODE_OFF) ensureScan();
    }

    private BluetoothAdapter adapter() {
        try {
            BluetoothManager bm = (BluetoothManager) app.getSystemService(Context.BLUETOOTH_SERVICE);
            if (bm == null) return null;
            return bm.getAdapter();
        } catch (Throwable t) {
            return null;
        }
    }

    private boolean adapterOn() {
        try {
            BluetoothAdapter ad = adapter();
            return ad != null && ad.isEnabled();
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean hasPerm() {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                return app.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                        == android.content.pm.PackageManager.PERMISSION_GRANTED
                    && app.checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN)
                        == android.content.pm.PackageManager.PERMISSION_GRANTED;
            }
            return app.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED
                || app.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    private void ensureScan() {
        if (mode == MODE_OFF || gatt != null) return;
        if (!NeckbandNative.LOADED) {
            publishStatus("driver missing", false);
            return;
        }
        if (!hasPerm()) {
            publishStatus("need permission", false);
            return;
        }
        BluetoothAdapter ad = adapter();
        if (ad == null || !ad.isEnabled()) {
            publishStatus("bluetooth off", false);
            return;
        }
        startScan(ad);
    }

    private void startScan(BluetoothAdapter ad) {
        if (scanning || gatt != null || mode == MODE_OFF) return;
        try {
            BluetoothLeScanner s = ad.getBluetoothLeScanner();
            if (s == null) {
                publishStatus("bluetooth off", false);
                return;
            }
            scanner = s;
            ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
            s.startScan(Collections.emptyList(), settings, scanCb);
            scanning = true;
            publishStatus("scanning", false);
            Log.i(TAG, "scanning");
        } catch (SecurityException se) {
            scanning = false;
            publishStatus("need permission", false);
            Log.w(TAG, "scan permission");
        } catch (Throwable t) {
            scanning = false;
            Log.w(TAG, "scan", t);
        }
    }

    private void stopScan() {
        if (!scanning) return;
        scanning = false;
        try {
            if (scanner != null) scanner.stopScan(scanCb);
        } catch (SecurityException se) {
            publishStatus("need permission", false);
        } catch (Throwable ignored) {}
    }

    private final ScanCallback scanCb = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            Handler h = io;
            if (h == null || result == null) return;
            h.post(() -> onResult(result));
        }

        @Override public void onBatchScanResults(List<ScanResult> results) {
            if (results == null) return;
            Handler h = io;
            if (h == null) return;
            h.post(() -> {
                for (int i = 0; i < results.size(); i++) onResult(results.get(i));
            });
        }

        @Override public void onScanFailed(int errorCode) {
            Log.w(TAG, "scan failed " + errorCode);
            Handler h = io;
            if (h == null) return;
            h.post(() -> {
                scanning = false;
                if (mode != MODE_OFF && gatt == null) {
                    h.postDelayed(NeckbandLink.this::ensureScan, 400);
                }
            });
        }
    };

    private void onResult(ScanResult result) {
        if (mode == MODE_OFF || gatt != null || result == null) return;
        BluetoothDevice dev = result.getDevice();
        if (dev == null) return;
        if (!match(result)) return;
        String addr;
        try {
            addr = dev.getAddress();
        } catch (SecurityException se) {
            publishStatus("need permission", false);
            return;
        }
        if (addr == null || addr.isEmpty()) return;
        Long until = skipUntil.get(addr);
        long now = SystemClock.elapsedRealtime();
        if (until != null && until > now) return;
        peerName = deviceName(dev, result.getScanRecord());
        stopScan();
        connect(dev);
    }

    private boolean match(ScanResult result) {
        try {
            ScanRecord rec = result.getScanRecord();
            if (rec != null) {
                List<ParcelUuid> uuids = rec.getServiceUuids();
                if (uuids != null) {
                    for (int i = 0; i < uuids.size(); i++) {
                        ParcelUuid u = uuids.get(i);
                        if (u != null && SVC.equals(u.getUuid())) return true;
                    }
                }
            }
            BluetoothDevice dev = result.getDevice();
            String name = null;
            try {
                if (dev != null) name = dev.getName();
            } catch (SecurityException ignored) {}
            if ((name == null || name.isEmpty()) && rec != null) name = rec.getDeviceName();
            if (name == null) return false;
            String low = name.trim().toLowerCase(java.util.Locale.US);
            if (low.isEmpty()) return false;
            if (low.contains("neckband")) return true;
            // Serial-style names (P8A1…). A short prefix alone is not enough.
            if ((low.startsWith("n8") || low.startsWith("p8")) && low.length() >= 6) return true;
            if (!"viture".equals(low) || rec == null) return false;
            if (rec.getManufacturerSpecificData(1) != null) return true;
            SparseArray<byte[]> all = rec.getManufacturerSpecificData();
            return all != null && all.indexOfKey(1) >= 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private void connect(BluetoothDevice dev) {
        if (gatt != null) return;
        try {
            gatt = dev.connectGatt(app, false, gattCb, BluetoothDevice.TRANSPORT_LE);
            if (gatt == null) {
                io.postDelayed(this::ensureScan, 400);
                return;
            }
            linkUp = false;
            final int gen = ++connectGen;
            publishStatus("connecting " + shownName(), false);
            Log.i(TAG, "connecting " + shownName());
            io.postDelayed(() -> {
                if (gen != connectGen || gatt == null || linkUp) return;
                Log.i(TAG, "connect timeout");
                BluetoothGatt stuck = gatt;
                if (stuck != null) {
                    try { stuck.disconnect(); } catch (Throwable ignored) {}
                }
            }, 8000);
        } catch (SecurityException se) {
            gatt = null;
            publishStatus("need permission", false);
        } catch (Throwable t) {
            gatt = null;
            Log.w(TAG, "connect", t);
            io.postDelayed(this::ensureScan, 400);
        }
    }

    private final BluetoothGattCallback gattCb = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            Handler h = io;
            if (h == null) return;
            h.post(() -> onConn(g, status, newState));
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            Handler h = io;
            if (h == null) return;
            h.post(() -> {
                if (g != gatt) return;
                Log.i(TAG, "mtu " + mtu);
                if (!discovered && !discovering) discover(g);
            });
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            Handler h = io;
            if (h == null) return;
            h.post(() -> onServices(g, status));
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            Handler h = io;
            if (h == null) return;
            h.post(() -> onDesc(g, status));
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g,
                                          BluetoothGattCharacteristic ch, int status) {
            Handler h = io;
            if (h == null) return;
            // Do not read inflightGen here. The binder thread can sample the next write.
            h.post(() -> onStackWrite(g, status));
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g,
                                            BluetoothGattCharacteristic ch, byte[] value) {
            byte[] copy = value == null ? null : Arrays.copyOf(value, value.length);
            Handler h = io;
            if (h == null) return;
            h.post(() -> onNotify(g, copy));
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch) {
            byte[] v = null;
            try { if (ch != null) v = ch.getValue(); } catch (Throwable ignored) {}
            byte[] copy = v == null ? null : Arrays.copyOf(v, v.length);
            Handler h = io;
            if (h == null) return;
            h.post(() -> onNotify(g, copy));
        }
    };

    private void onConn(BluetoothGatt g, int status, int newState) {
        if (g != gatt) {
            try { g.close(); } catch (Throwable ignored) {}
            return;
        }
        if (newState == BluetoothProfile.STATE_CONNECTED) {
            boolean fresh = !linkUp;
            linkUp = true;
            if (!fresh) return;
            authed = false;
            armed = false;
            gattReady = false;
            discovered = false;
            discovering = false;
            try { g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH); } catch (Throwable ignored) {}
            try { g.requestMtu(185); } catch (Throwable ignored) {}
            // 20-byte frames fit the default ATT size. Waiting on MTU was the slow switch.
            discover(g);
            return;
        }
        if (newState == BluetoothProfile.STATE_DISCONNECTED) onDropped(g);
    }

    private void discover(BluetoothGatt g) {
        if (g != gatt || discovered || discovering) return;
        discovering = true;
        try {
            if (!g.discoverServices()) {
                discovering = false;
                io.postDelayed(() -> {
                    if (g == gatt && !discovered) discover(g);
                }, 200);
            }
        } catch (SecurityException se) {
            discovering = false;
            publishStatus("need permission", false);
        } catch (Throwable t) {
            discovering = false;
            Log.w(TAG, "discover", t);
            io.postDelayed(() -> {
                if (g == gatt && !discovered) discover(g);
            }, 200);
        }
    }

    private void onServices(BluetoothGatt g, int status) {
        if (g != gatt) return;
        discovering = false;
        discovered = true;
        BluetoothGattService svc = null;
        try { svc = g.getService(SVC); } catch (Throwable ignored) {}
        if (svc == null) {
            blacklist(g);
            Log.i(TAG, "no remote service");
            try { g.disconnect(); } catch (Throwable ignored) {}
            io.postDelayed(() -> { if (g == gatt) onDropped(g); }, 800);
            return;
        }
        writeChar = svc.getCharacteristic(WRITE);
        notifyChar = svc.getCharacteristic(NOTIFY);
        if (writeChar == null) {
            blacklist(g);
            try { g.disconnect(); } catch (Throwable ignored) {}
            io.postDelayed(() -> { if (g == gatt) onDropped(g); }, 800);
            return;
        }
        int props = writeChar.getProperties();
        writeType = ((props & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0)
            ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
        try { writeChar.setWriteType(writeType); } catch (Throwable ignored) {}
        try {
            g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
        } catch (Throwable ignored) {}
        if (NeckbandNative.LOADED) {
            try {
                NeckbandNative.reset();
                NeckbandNative.setGain(gain);
            } catch (Throwable ignored) {}
        }
        clearOutbound();
        inflight = false;
        inflightGen = -1;
        inflightFrame = null;
        descBusy = false;
        descWait = 0;
        descStep = 0;
        skipWrites = 0;
        busyStreak = 0;
        writeFails = 0;
        authed = false;
        gattReady = false;
        armed = true;
        recompute();
        beginNotify();
    }

    private void blacklist(BluetoothGatt g) {
        String addr = safeAddr(g);
        if (addr == null) return;
        skipUntil.put(addr, SystemClock.elapsedRealtime() + BLACKLIST_MS);
        if (skipUntil.size() > 32) {
            long now = SystemClock.elapsedRealtime();
            for (int i = skipUntil.size() - 1; i >= 0; i--) {
                Long until = skipUntil.valueAt(i);
                if (until == null || until < now) skipUntil.removeAt(i);
            }
        }
    }

    /**
     * Subscribe, then auth. Cursor frames wait in the codec until auth,
     * so a descriptor write cannot sit in front of every move.
     */
    private void beginNotify() {
        if (gatt == null || writeChar == null) return;
        if (notifyChar == null) {
            sendAuth();
            return;
        }
        BluetoothGatt g = gatt;
        try {
            g.setCharacteristicNotification(notifyChar, true);
        } catch (Throwable t) {
            sendAuth();
            return;
        }
        BluetoothGattDescriptor cccd = notifyChar.getDescriptor(CCCD);
        if (cccd == null || !writeDesc(g, cccd)) {
            sendAuth();
            return;
        }
        descStep = 1;
        descBusy = true;
        armDescTimeout();
    }

    private void armDescTimeout() {
        final int token = ++descGen;
        descWait = 1;
        io.postDelayed(() -> {
            if (token != descGen || descWait == 0) return;
            descWait = 0;
            Log.i(TAG, "desc timeout");
            finishDesc();
        }, 90);
    }

    private void finishDesc() {
        descStep = 0;
        descBusy = false;
        descWait = 0;
        if (!authed) sendAuth();
        else pump();
    }

    private boolean writeDesc(BluetoothGatt g, BluetoothGattDescriptor d) {
        try {
            d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            return g.writeDescriptor(d);
        } catch (SecurityException se) {
            publishStatus("need permission", false);
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    private void onDesc(BluetoothGatt g, int status) {
        if (g != gatt || descWait == 0) return;
        descWait = 0;
        descGen++;
        if (status != BluetoothGatt.GATT_SUCCESS) Log.i(TAG, "desc " + status);
        if (descStep == 1 && status == BluetoothGatt.GATT_SUCCESS && notifyChar != null) {
            BluetoothGattDescriptor custom = notifyChar.getDescriptor(CUSTOM);
            descStep = 2;
            if (custom != null && writeDesc(g, custom)) {
                descBusy = true;
                armDescTimeout();
                return;
            }
        }
        finishDesc();
    }

    private void sendAuth() {
        if (authed || gatt == null || writeChar == null) return;
        authed = true;
        descBusy = false;
        descWait = 0;
        byte[] auth = null;
        try {
            if (NeckbandNative.LOADED) auth = NeckbandNative.auth();
        } catch (Throwable ignored) {}
        if (auth != null && auth.length > 0) {
            urgent.addFirst(new Out(Arrays.copyOf(auth, auth.length), false));
        }
        gattReady = true;
        writeFails = 0;
        pump();
        Log.i(TAG, "auth queued " + shownName());
        recompute();
    }

    private void onNotify(BluetoothGatt g, byte[] value) {
        if (g != gatt || value == null || value.length < 1) return;
        if ((value[0] & 0xff) != 8) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastTextMs < 300) return;
        lastTextMs = now;
        for (Listener l : listeners) {
            try { l.onTextWanted(); } catch (Throwable ignored) {}
        }
    }

    private void onDropped(BluetoothGatt g) {
        if (g != gatt) return;
        linkUp = false;
        scrubLink();
        gatt = null;
        try { g.close(); } catch (Throwable ignored) {}
        if (NeckbandNative.LOADED) {
            try {
                NeckbandNative.reset();
                NeckbandNative.setGain(gain);
            } catch (Throwable ignored) {}
        }
        Log.i(TAG, "dropped");
        recompute();
        if (mode != MODE_OFF) io.postDelayed(this::ensureScan, 400);
    }

    private void scrubLink() {
        descGen++;
        descWait = 0;
        descStep = 0;
        descBusy = false;
        armed = false;
        gattReady = false;
        authed = false;
        discovered = false;
        discovering = false;
        writeChar = null;
        notifyChar = null;
        clearOutbound();
        inflight = false;
        inflightGen = -1;
        inflightFrame = null;
        skipWrites = 0;
        busyStreak = 0;
        writeFails = 0;
    }

    private final Runnable hoverTask = new Runnable() {
        @Override public void run() {
            hoverKick.set(false);
            if (!NeckbandNative.LOADED) return;
            if (!captureFlag) {
                try { NeckbandNative.takeHover(); } catch (Throwable ignored) {}
                return;
            }
            // Setup and an in-flight write keep the delta in the codec.
            if (!gattReady || descBusy || inflight || pendingHover != null) return;
            byte[] frame = null;
            try { frame = NeckbandNative.takeHover(); } catch (Throwable ignored) {}
            if (frame != null) enqueueBlob(frame, true);
        }
    };

    private final Runnable scrollEndTask = () -> {
        if (!captureFlag || !NeckbandNative.LOADED) return;
        byte[] frame = null;
        try { frame = NeckbandNative.scrollEnd(); } catch (Throwable ignored) {}
        if (frame != null) enqueueBlob(frame, false);
    };

    private void emitRelease() {
        if (!NeckbandNative.LOADED) return;
        byte[] keys = null;
        byte[] scroll = null;
        byte[] cancel = null;
        try {
            keys = NeckbandNative.releaseKeys();
            scroll = NeckbandNative.scrollEnd();
            cancel = NeckbandNative.cancel();
        } catch (Throwable t) {
            Log.w(TAG, "release", t);
            return;
        }
        io.removeCallbacks(scrollEndTask);
        if (gatt == null || writeChar == null) return;
        pendingHover = null;
        if (cancel != null && cancel.length > 0) {
            urgent.addFirst(new Out(Arrays.copyOf(cancel, cancel.length), false));
        }
        if (scroll != null && scroll.length >= 20) {
            urgent.addFirst(new Out(Arrays.copyOf(scroll, 20), false));
        }
        if (keys != null && keys.length >= 20 && keys.length % 20 == 0) {
            int n = keys.length / 20;
            for (int i = n - 1; i >= 0; i--) {
                urgent.addFirst(new Out(
                    Arrays.copyOfRange(keys, i * 20, (i + 1) * 20), false));
            }
        }
        pump();
    }

    private void enqueueBlob(byte[] blob, boolean hover) {
        if (blob == null || blob.length == 0) return;
        if (blob.length % 20 != 0) {
            enqueue(Arrays.copyOf(blob, blob.length), hover);
            return;
        }
        for (int i = 0; i < blob.length; i += 20) {
            enqueue(Arrays.copyOfRange(blob, i, i + 20), hover);
        }
    }

    private void enqueue(byte[] frame, boolean hover) {
        if (frame == null || frame.length == 0) return;
        if (hover) {
            if (pendingHover != null && isHover(pendingHover.bytes) && isHover(frame)) {
                int dx = i16(pendingHover.bytes, 3) + i16(frame, 3);
                int dy = i16(pendingHover.bytes, 5) + i16(frame, 5);
                putI16(pendingHover.bytes, 3, dx);
                putI16(pendingHover.bytes, 5, dy);
            } else {
                pendingHover = new Out(frame, true);
            }
        } else {
            trimUrgent();
            urgent.addLast(new Out(frame, false));
        }
        pump();
    }

    /** Drop gestures before keys. A trimmed key-down with the up still queued sticks. */
    private void trimUrgent() {
        if (urgent.size() < QUEUE_MAX) return;
        Iterator<Out> it = urgent.iterator();
        while (urgent.size() >= QUEUE_MAX && it.hasNext()) {
            Out o = it.next();
            int ev = (o == null || o.bytes == null || o.bytes.length < 1)
                ? -1 : (o.bytes[0] & 0xff);
            if (ev != 1 && ev != 4) it.remove();
        }
        while (urgent.size() >= QUEUE_MAX) urgent.pollFirst();
    }

    private static boolean isHover(byte[] frame) {
        return frame != null && frame.length >= 8
            && (frame[0] & 0xff) == 5
            && (frame[2] & 0xff) == 1
            && frame[7] == 0;
    }

    private boolean hasOutbound() {
        return inflight || !urgent.isEmpty() || pendingHover != null;
    }

    private void clearOutbound() {
        urgent.clear();
        pendingHover = null;
    }

    private Out pollNext() {
        Out key = urgent.peekFirst();
        // Keys and auth pass a queued move. A click waits until that move is sent.
        if (key != null && jumpsHover(key.bytes)) return urgent.pollFirst();
        if (pendingHover != null) {
            Out h = pendingHover;
            pendingHover = null;
            return h;
        }
        return urgent.pollFirst();
    }

    private static boolean jumpsHover(byte[] frame) {
        if (frame == null || frame.length < 1) return false;
        int ev = frame[0] & 0xff;
        return ev == 1 || ev == 4;
    }

    private void requeue(Out frame) {
        if (frame == null) return;
        if (frame.hover) {
            if (pendingHover == null) {
                pendingHover = frame;
            } else if (isHover(pendingHover.bytes) && isHover(frame.bytes)) {
                int dx = i16(frame.bytes, 3) + i16(pendingHover.bytes, 3);
                int dy = i16(frame.bytes, 5) + i16(pendingHover.bytes, 5);
                putI16(pendingHover.bytes, 3, dx);
                putI16(pendingHover.bytes, 5, dy);
            }
            return;
        }
        urgent.addFirst(frame);
    }

    private static int i16(byte[] b, int off) {
        int v = (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8);
        if (v >= 0x8000) v -= 0x10000;
        return v;
    }

    private static void putI16(byte[] b, int off, int v) {
        if (v > 32767) v = 32767;
        if (v < -32768) v = -32768;
        b[off] = (byte) (v & 0xff);
        b[off + 1] = (byte) ((v >> 8) & 0xff);
    }

    private void closeGatt() {
        BluetoothGatt g = gatt;
        if (g == null) return;
        try { g.disconnect(); } catch (Throwable ignored) {}
    }

    private void pump() {
        if (descBusy || inflight || writeChar == null || gatt == null) return;
        Out next = pollNext();
        if (next == null) {
            if (captureFlag && gattReady) scheduleHover();
            return;
        }
        inflight = true;
        inflightFrame = next;
        int gen = ++writeSerial;
        inflightGen = gen;
        boolean ok = writeNow(next.bytes);
        if (!ok) {
            inflight = false;
            inflightGen = -1;
            inflightFrame = null;
            requeue(next);
            busyStreak++;
            if (busyStreak == 40) Log.w(TAG, "gatt busy");
            if (busyStreak >= BUSY_GIVE_UP && gatt != null) {
                busyStreak = 0;
                Log.w(TAG, "gatt stuck, reconnect");
                try { gatt.disconnect(); } catch (Throwable ignored) {}
                return;
            }
            io.postDelayed(this::pump, BUSY_RETRY_MS);
            return;
        }
        busyStreak = 0;
        if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
            // Accepted. mDeviceBusy clears in onCharacteristicWrite, which is
            // the pace. Proceed only if that callback does not arrive.
            int pace = next.hover ? WRITE_PACE_HOVER_MS : WRITE_PACE_KEY_MS;
            io.postDelayed(() -> onWriteProceed(gen), pace);
            return;
        }
        io.postDelayed(() -> onWriteTimeout(gen), 25);
    }

    private boolean writeNow(byte[] value) {
        BluetoothGatt g = gatt;
        BluetoothGattCharacteristic ch = writeChar;
        if (g == null || ch == null || value == null || value.length == 0) return false;
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                // The byte[] is copied by the stack. setValue on the shared
                // characteristic races the previous write on API 33+.
                return g.writeCharacteristic(ch, value, writeType) == 0;
            }
            ch.setWriteType(writeType);
            ch.setValue(value);
            return g.writeCharacteristic(ch);
        } catch (SecurityException se) {
            publishStatus("need permission", false);
            return false;
        } catch (Throwable t) {
            Log.w(TAG, "write", t);
            return false;
        }
    }

    /** With-response only. The frame was not accepted, so it goes back. */
    private void onWriteTimeout(int gen) {
        if (!inflight || gen != inflightGen) return;
        Out failed = inflightFrame;
        inflight = false;
        inflightGen = -1;
        inflightFrame = null;
        skipWrites++;
        if (failed != null) requeue(failed);
        pump();
    }

    /** No-response: the stack already took the packet. Do not send it twice. */
    private void onWriteProceed(int gen) {
        if (!inflight || gen != inflightGen) return;
        inflight = false;
        inflightGen = -1;
        inflightFrame = null;
        skipWrites++;
        pump();
    }

    private void onStackWrite(BluetoothGatt g, int status) {
        if (g != gatt) return;
        if (skipWrites > 0) {
            skipWrites--;
            return;
        }
        if (!inflight) return;
        Out failed = inflightFrame;
        inflight = false;
        inflightGen = -1;
        inflightFrame = null;
        if (status != BluetoothGatt.GATT_SUCCESS && failed != null && writeFails < WRITE_FAIL_MAX) {
            writeFails++;
            requeue(failed);
            io.postDelayed(this::pump, BUSY_RETRY_MS);
            return;
        }
        writeFails = 0;
        pump();
    }

    /** Service up means this sink owns the pad and the keyboard. No flap. */
    private boolean wantCapture() {
        return mode != MODE_OFF && armed && writeChar != null && NeckbandNative.LOADED;
    }

    private void recompute() {
        boolean was = captureFlag;
        boolean next = wantCapture();
        captureFlag = next;
        if (was != next) {
            Log.i(TAG, next ? "capture on" : "capture off");
            if (next) {
                try { BluetoothHidClient.get().forgetPendingMouse(); } catch (Throwable ignored) {}
                try { HidSessionService.kickInputSocks(); } catch (Throwable ignored) {}
            } else if (gatt != null) {
                emitRelease();
            }
        }
        String st = buildStatus();
        boolean stChanged = !st.equals(statusText);
        statusText = st;
        if (was != next || stChanged) {
            if (stChanged) Log.i(TAG, st);
            notifyListeners();
        }
    }

    private String buildStatus() {
        if (mode == MODE_OFF) return "off";
        if (!NeckbandNative.LOADED) return "driver missing";
        if (!hasPerm()) return "need permission";
        if (!adapterOn()) return "bluetooth off";
        if (gattReady) {
            String n = shownName();
            return captureFlag ? n : (n + " · held");
        }
        if (gatt != null) return "connecting " + shownName();
        return "scanning";
    }

    private void publishStatus(String st, boolean force) {
        if (st == null) st = "off";
        boolean changed = !st.equals(statusText);
        statusText = st;
        if (changed || force) {
            if (changed) Log.i(TAG, st);
            notifyListeners();
        }
    }

    private void notifyListeners() {
        for (Listener l : listeners) {
            try { l.onLinkChanged(); } catch (Throwable ignored) {}
        }
    }

    private String shownName() {
        String n = peerName;
        if (n == null || n.isEmpty()) return "neckband";
        return n;
    }

    private static String deviceName(BluetoothDevice dev, ScanRecord rec) {
        String n = null;
        try { if (dev != null) n = dev.getName(); } catch (SecurityException ignored) {}
        if ((n == null || n.isEmpty()) && rec != null) n = rec.getDeviceName();
        if (n == null || n.isEmpty()) {
            try { return dev != null ? dev.getAddress() : "neckband"; }
            catch (Throwable t) { return "neckband"; }
        }
        return n;
    }

    private static String safeAddr(BluetoothGatt g) {
        try {
            BluetoothDevice d = g.getDevice();
            return d != null ? d.getAddress() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    static boolean looksLikeNeckband(String name) {
        if (name == null) return false;
        String s = name.trim().toLowerCase(java.util.Locale.US);
        if (s.isEmpty()) return false;
        if (s.contains("neckband")) return true;
        if (s.contains("viture")) return true;
        if (s.contains("v1231")) return true;
        return s.startsWith("n8") || s.startsWith("p8");
    }

    private static int utf8Len(int cp) {
        if (cp < 0x80) return 1;
        if (cp < 0x800) return 2;
        if (cp < 0x10000) return 3;
        return 4;
    }
}
