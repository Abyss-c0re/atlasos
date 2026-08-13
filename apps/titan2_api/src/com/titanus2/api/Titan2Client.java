package com.titanus2.api;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Client for {@code Titan2CoreService}. Binds via Messenger (no AIDL).
 * Methods fall back to {@link ControlPlane} files when unbound so HID still
 * works if Controls is missing.
 */
public final class Titan2Client {
    private static final String TAG = "Titan2Client";
    private static final long BIND_TIMEOUT_MS = 2500;
    private static final long RPC_TIMEOUT_MS = 1500;

    public interface PadModeListener {
        void onPadMode(String mode, long epoch);
    }

    private final Context app;
    private final HandlerThread worker;
    private final Handler workerHandler;
    private final AtomicInteger seq = new AtomicInteger(1);
    private final Object bindLock = new Object();

    private volatile Messenger service;
    private volatile boolean bound;
    private volatile boolean binding;
    private volatile PadModeListener padListener;
    private ServiceConnection conn;

    private final Messenger replyTo;

    public Titan2Client(Context ctx) {
        this.app = ctx.getApplicationContext();
        worker = new HandlerThread("titan2-api-client");
        worker.start();
        workerHandler = new Handler(worker.getLooper());
        replyTo = new Messenger(new Handler(worker.getLooper()) {
            @Override public void handleMessage(Message msg) {
                if (msg.what == Titan2ApiContract.MSG_EVENT_PAD_MODE
                        || msg.what == Titan2ApiContract.MSG_EVENT_PAD_EPOCH) {
                    Bundle b = msg.getData();
                    if (b != null && padListener != null) {
                        String mode = b.getString(Titan2ApiContract.KEY_MODE);
                        long epoch = b.getLong(Titan2ApiContract.KEY_EPOCH, 0);
                        try {
                            padListener.onPadMode(mode, epoch);
                        } catch (Exception ignored) {}
                    }
                }
                // RPC replies handled via pending latches in call()
            }
        });
    }

    public void setPadModeListener(PadModeListener l) {
        padListener = l;
    }

    /** Bind asynchronously; safe to call multiple times. */
    public void connect() {
        synchronized (bindLock) {
            if (bound || binding) return;
            binding = true;
            Intent i = new Intent(Titan2ApiContract.ACTION_BIND);
            i.setComponent(new ComponentName(
                Titan2ApiContract.CONTROLS_PKG, Titan2ApiContract.SERVICE_CLASS));
            conn = new ServiceConnection() {
                @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                    service = new Messenger(binder);
                    bound = true;
                    binding = false;
                    Log.i(TAG, "bound " + name);
                }
                @Override public void onServiceDisconnected(ComponentName name) {
                    service = null;
                    bound = false;
                    binding = false;
                    Log.w(TAG, "disconnected");
                }
            };
            try {
                boolean ok = app.bindService(i, conn, Context.BIND_AUTO_CREATE);
                if (!ok) {
                    binding = false;
                    Log.w(TAG, "bindService returned false");
                }
            } catch (Exception e) {
                binding = false;
                Log.w(TAG, "bind failed: " + e.getMessage());
            }
        }
    }

    public void disconnect() {
        synchronized (bindLock) {
            if (conn != null && bound) {
                try { app.unbindService(conn); } catch (Exception ignored) {}
            }
            conn = null;
            service = null;
            bound = false;
            binding = false;
        }
    }

    public void shutdown() {
        disconnect();
        worker.quitSafely();
    }

    public boolean isBound() { return bound && service != null; }

    private boolean awaitBound(long ms) {
        if (isBound()) return true;
        connect();
        long deadline = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < deadline) {
            if (isBound()) return true;
            try { Thread.sleep(40); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return isBound();
    }

    private Bundle call(int what, Bundle data) {
        return call(what, data, RPC_TIMEOUT_MS, true);
    }

    /**
     * Messenger RPC. When {@code waitBind} is false, never block on CoreService
     * connect — HID Start was freezing ~14s (bind 2.5s + several 1.5s timeouts)
     * while pad/keymap still work via {@link ControlPlane}.
     */
    private Bundle call(int what, Bundle data, long timeoutMs, boolean waitBind) {
        if (waitBind) {
            if (!awaitBound(BIND_TIMEOUT_MS)) return null;
        } else if (!isBound()) {
            return null;
        }
        Messenger svc = service;
        if (svc == null) return null;
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Bundle> result = new AtomicReference<>();
        final int token = seq.getAndIncrement();
        Handler replyHandler = new Handler(worker.getLooper()) {
            @Override public void handleMessage(Message msg) {
                if (msg.arg1 != token) return;
                Bundle b = msg.getData();
                if (b != null) b.setClassLoader(app.getClassLoader());
                result.set(b != null ? b : new Bundle());
                latch.countDown();
            }
        };
        Messenger localReply = new Messenger(replyHandler);
        Message msg = Message.obtain(null, what);
        msg.arg1 = token;
        msg.replyTo = localReply;
        if (data != null) msg.setData(data);
        try {
            svc.send(msg);
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "rpc timeout what=" + what);
                return null;
            }
            return result.get();
        } catch (RemoteException | InterruptedException e) {
            Log.w(TAG, "rpc fail what=" + what + ": " + e.getMessage());
            return null;
        }
    }

    // ---- Pad ----

    public String getPadMode() {
        // Plane first — never stall HID/Controls UI waiting for CoreService.
        String plane = PadModes.normalize(ControlPlane.get(app,
            Titan2ApiContract.FILE_PAD_MODE, Titan2ApiContract.MODE_OFF));
        Bundle r = call(Titan2ApiContract.MSG_GET_PAD_MODE, null, 300, false);
        if (r != null) {
            String m = r.getString(Titan2ApiContract.KEY_MODE);
            if (m != null) return PadModes.normalize(m);
        }
        return plane;
    }

    public boolean setPadMode(String mode) {
        mode = PadModes.normalize(mode);
        // Write plane first (tmp + CE) so agent/service see mode even if Binder hangs.
        boolean ok = ControlPlane.put(app, Titan2ApiContract.FILE_PAD_MODE, mode);
        ControlPlane.put(app, "titan2_touchpad_enabled",
            Titan2ApiContract.MODE_MOUSE.equals(mode) ? "1" : "0");
        try {
            android.provider.Settings.Global.putString(
                app.getContentResolver(), Titan2ApiContract.FILE_PAD_MODE, mode);
        } catch (Exception ignored) {}
        ControlPlane.bumpEpoch(app);
        try {
            Intent i = new Intent("com.titanus2.controls.action.SET_PAD_MODE");
            i.setPackage(Titan2ApiContract.CONTROLS_PKG);
            i.putExtra("mode", mode);
            app.sendBroadcast(i);
        } catch (Exception ignored) {}
        // Best-effort notify CoreService without blocking callers (300ms max).
        Bundle req = new Bundle();
        req.putString(Titan2ApiContract.KEY_MODE, mode);
        Bundle r = call(Titan2ApiContract.MSG_SET_PAD_MODE, req, 300, false);
        if (r != null && r.getBoolean(Titan2ApiContract.KEY_OK, false)) return true;
        return ok;
    }

    public boolean isFollowOrient() {
        String v = ControlPlane.get(app, Titan2ApiContract.FILE_PAD_FOLLOW, "0");
        boolean plane = "1".equals(v) || "true".equalsIgnoreCase(v);
        Bundle r = call(Titan2ApiContract.MSG_GET_FOLLOW_ORIENT, null, 300, false);
        if (r != null) return r.getBoolean(Titan2ApiContract.KEY_FOLLOW, plane);
        return plane;
    }

    public boolean setFollowOrient(boolean on) {
        ControlPlane.put(app, Titan2ApiContract.FILE_PAD_FOLLOW, on ? "1" : "0");
        Bundle req = new Bundle();
        req.putBoolean(Titan2ApiContract.KEY_FOLLOW, on);
        Bundle r = call(Titan2ApiContract.MSG_SET_FOLLOW_ORIENT, req, 300, false);
        if (r != null && r.getBoolean(Titan2ApiContract.KEY_OK, false)) return true;
        return true;
    }

    public void publishRotation() {
        // Always stamp plane; optional short RPC.
        try {
            android.view.WindowManager wm = (android.view.WindowManager)
                app.getSystemService(Context.WINDOW_SERVICE);
            int rot = 0;
            if (wm != null && wm.getDefaultDisplay() != null) {
                rot = wm.getDefaultDisplay().getRotation();
            }
            ControlPlane.put(app, Titan2ApiContract.FILE_PAD_ROTATION, String.valueOf(rot));
        } catch (Exception ignored) {}
        call(Titan2ApiContract.MSG_PUBLISH_ROTATION, null, 300, false);
    }

    public long getPadEpoch() {
        long plane = ControlPlane.getLong(app, Titan2ApiContract.FILE_PAD_EPOCH, 0);
        Bundle r = call(Titan2ApiContract.MSG_GET_PAD_EPOCH, null, 300, false);
        if (r != null) return r.getLong(Titan2ApiContract.KEY_EPOCH, plane);
        return plane;
    }

    public void requestPadRegrab() {
        ControlPlane.requestRegrab(app);
    }

    // ---- Keymap / temp layers ----

    public String getKeyAction(String slot) {
        Bundle req = new Bundle();
        req.putString(Titan2ApiContract.KEY_SLOT, slot);
        Bundle r = call(Titan2ApiContract.MSG_GET_EFFECTIVE_KEY_ACTION, req);
        if (r != null) return r.getString(Titan2ApiContract.KEY_ACTION);
        return ControlPlane.get(app, "titan2_km_" + slot, "default");
    }

    public boolean setKeyAction(String slot, String action) {
        return setKeyAction(slot, action, null);
    }

    /**
     * Set a permanent mapping. Pass {@code pkg} to write a Controls
     * <b>per-app profile</b> (HID uses {@link Titan2ApiContract#HID_HOST_PKG});
     * null pkg = global Keys map.
     */
    public boolean setKeyAction(String slot, String action, String pkg) {
        Bundle req = new Bundle();
        req.putString(Titan2ApiContract.KEY_SLOT, slot);
        req.putString(Titan2ApiContract.KEY_ACTION, action);
        if (pkg != null && !pkg.isEmpty()) {
            req.putString(Titan2ApiContract.KEY_PKG, pkg);
        }
        Bundle r = call(Titan2ApiContract.MSG_SET_KEY_ACTION, req);
        return r != null && r.getBoolean(Titan2ApiContract.KEY_OK, false);
    }

    /** Ensure Controls shows a per-app profile for {@code pkg}. */
    public boolean ensureKeymapProfile(String pkg, String label) {
        Bundle req = new Bundle();
        req.putString(Titan2ApiContract.KEY_PKG, pkg);
        if (label != null) req.putString(Titan2ApiContract.KEY_LABEL, label);
        Bundle r = call(Titan2ApiContract.MSG_ENSURE_KEYMAP_PROFILE, req, 400, false);
        return r != null && r.getBoolean(Titan2ApiContract.KEY_OK, false);
    }

    /** Sparse slot→action from a Controls per-app profile. */
    public Map<String, String> getProfileMap(String pkg) {
        Bundle req = new Bundle();
        req.putString(Titan2ApiContract.KEY_PKG, pkg);
        Bundle r = call(Titan2ApiContract.MSG_GET_PROFILE_MAP, req, 400, false);
        Map<String, String> out = new java.util.LinkedHashMap<>();
        if (r == null) return out;
        Bundle map = r.getBundle(Titan2ApiContract.KEY_MAP);
        if (map == null) return out;
        for (String k : map.keySet()) {
            String v = map.getString(k);
            if (k != null && v != null) out.put(k, v);
        }
        return out;
    }

    /** Re-push HID host layer from Controls profile (live session). */
    public boolean refreshHidHostLayer() {
        Bundle r = call(Titan2ApiContract.MSG_REFRESH_HID_HOST_LAYER, null, 400, false);
        return r != null && r.getBoolean(Titan2ApiContract.KEY_OK, false);
    }

    /**
     * Push a temporary keymap layer. Same layerId replaces.
     * <p>
     * <b>Priority:</b> API client layers (any id except
     * {@link Titan2ApiContract#LAYER_HID_SESSION}) win over silence and over
     * permanent Controls mappings <em>only for slots present in the map</em>.
     * Omit a slot to keep the user's current mapping (or silence if HID
     * session silence defines it).
     *
     * @param layerId stable id (e.g. {@link Titan2ApiContract#LAYER_HID_SIDE_KEYS})
     * @param slotToAction map of slot id → action string (keycode:N, host:up, mouse:scroll_up, …)
     */
    public boolean pushTempKeyMap(String layerId, Map<String, String> slotToAction) {
        if (layerId == null || layerId.isEmpty()) return false;
        Bundle map = new Bundle();
        if (slotToAction != null) {
            for (Map.Entry<String, String> e : slotToAction.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    map.putString(e.getKey(), e.getValue());
                }
            }
        }
        Bundle req = new Bundle();
        req.putString(Titan2ApiContract.KEY_LAYER_ID, layerId);
        req.putBundle(Titan2ApiContract.KEY_MAP, map);
        Bundle r = call(Titan2ApiContract.MSG_PUSH_TEMP_KEYMAP, req);
        return r != null && r.getBoolean(Titan2ApiContract.KEY_OK, false);
    }

    public boolean popTempKeyMap(String layerId) {
        Bundle req = new Bundle();
        req.putString(Titan2ApiContract.KEY_LAYER_ID, layerId);
        Bundle r = call(Titan2ApiContract.MSG_POP_TEMP_KEYMAP, req, 400, false);
        return r != null && r.getBoolean(Titan2ApiContract.KEY_OK, false);
    }

    /**
     * Temporarily disable all Titan Controls hardware shortcuts under
     * {@code layerId} (server builds slot→none from the real remapper).
     * Permanent maps are not modified. Pop with {@link #popTempKeyMap}.
     * Used by HID so phone remaps do not fight host keyboard/pad input.
     */
    public boolean silenceKeyRemaps(String layerId) {
        if (layerId == null || layerId.isEmpty()) {
            layerId = Titan2ApiContract.LAYER_HID_SESSION;
        }
        Bundle req = new Bundle();
        req.putString(Titan2ApiContract.KEY_LAYER_ID, layerId);
        // Short / no-bind: HID Start must not freeze if CoreService is busy.
        Bundle r = call(Titan2ApiContract.MSG_SILENCE_KEY_REMAPS, req, 400, false);
        if (r != null && r.getBoolean(Titan2ApiContract.KEY_OK, false)) return true;
        try {
            Intent i = new Intent("com.titanus2.controls.action.PUBLISH_KM");
            i.setPackage(Titan2ApiContract.CONTROLS_PKG);
            i.putExtra("silence_layer", layerId);
            app.sendBroadcast(i);
        } catch (Exception ignored) {}
        return false;
    }

    // ---- LED ----

    public int getLedLevel() {
        Bundle r = call(Titan2ApiContract.MSG_GET_LED_LEVEL, null);
        if (r != null) return r.getInt(Titan2ApiContract.KEY_LEVEL, 3);
        try {
            return Integer.parseInt(ControlPlane.get(app, Titan2ApiContract.FILE_LED_LEVEL, "3"));
        } catch (Exception e) {
            return 3;
        }
    }

    public boolean setLedLevel(int level) {
        Bundle req = new Bundle();
        req.putInt(Titan2ApiContract.KEY_LEVEL, level);
        Bundle r = call(Titan2ApiContract.MSG_SET_LED_LEVEL, req);
        if (r != null && r.getBoolean(Titan2ApiContract.KEY_OK, false)) return true;
        if (level < 0) level = 0;
        if (level > 7) level = 7;
        return ControlPlane.put(app, Titan2ApiContract.FILE_LED_LEVEL, String.valueOf(level));
    }

    public boolean setLedTimeoutSec(int sec) {
        Bundle req = new Bundle();
        req.putInt(Titan2ApiContract.KEY_TIMEOUT, sec);
        Bundle r = call(Titan2ApiContract.MSG_SET_LED_TIMEOUT, req);
        if (r != null && r.getBoolean(Titan2ApiContract.KEY_OK, false)) return true;
        if (sec < 0) sec = 0;
        return ControlPlane.put(app, Titan2ApiContract.FILE_LED_TIMEOUT, String.valueOf(sec));
    }

    public void bumpKeyActivity() {
        Bundle r = call(Titan2ApiContract.MSG_BUMP_KEY_ACTIVITY, null);
        if (r != null) return;
        long now = System.currentTimeMillis() / 1000L;
        ControlPlane.put(app, Titan2ApiContract.FILE_KEY_ACTIVITY, String.valueOf(now));
    }

    public void ensureLedDefaults() {
        String bl = ControlPlane.get(app, Titan2ApiContract.FILE_LED_LEVEL, null);
        if (bl == null || bl.isEmpty()) {
            ControlPlane.put(app, Titan2ApiContract.FILE_LED_LEVEL, "3");
        }
        String to = ControlPlane.get(app, Titan2ApiContract.FILE_LED_TIMEOUT, null);
        if (to == null || to.isEmpty()) {
            ControlPlane.put(app, Titan2ApiContract.FILE_LED_TIMEOUT, "30");
        }
    }
}
