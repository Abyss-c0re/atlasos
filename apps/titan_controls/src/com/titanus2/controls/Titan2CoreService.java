package com.titanus2.controls;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;
import com.titanus2.api.ControlPlane;
import com.titanus2.api.PadModes;
import com.titanus2.api.Titan2ApiContract;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Titan2 framework core — pad mode, keymap (incl. temp layers), keyboard LED.
 * Bound via Messenger ({@link Titan2ApiContract#ACTION_BIND}).
 * <p>
 * No Magisk required: this priv-app + pad-agent (init) own hardware paths.
 * Third parties hold {@link Titan2ApiContract#PERMISSION_USE}.
 */
public class Titan2CoreService extends Service {
    private static final String TAG = "Titan2Core";

    private final CopyOnWriteArrayList<Messenger> eventClients = new CopyOnWriteArrayList<>();
    private TempKeyMapStack tempStack;
    private KeyMapPrefs keyPrefs;

    private final Messenger messenger = new Messenger(new Handler(Looper.getMainLooper()) {
        @Override public void handleMessage(Message msg) {
            handle(msg);
        }
    });

    @Override
    public void onCreate() {
        super.onCreate();
        tempStack = new TempKeyMapStack(this);
        keyPrefs = new KeyMapPrefs(this);
        // Drop orphaned HID silence after process death without pop
        try {
            tempStack.clearStaleHidSilence(this, keyPrefs);
        } catch (Exception e) {
            Log.w(TAG, "stale silence clear: " + e.getMessage());
        }
        Log.i(TAG, "core service created");
    }

    @Override
    public IBinder onBind(Intent intent) {
        // Enforce framework permission for external binds (component is exported
        // without android:permission so same-UID startService always works).
        if (checkCallingOrSelfPermission(Titan2ApiContract.PERMISSION_USE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "bind denied: missing " + Titan2ApiContract.PERMISSION_USE
                + " uid=" + android.os.Binder.getCallingUid());
            return null;
        }
        return messenger.getBinder();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Sticky so BOOT can keep process warm for API binds
        return START_STICKY;
    }

    private void handle(Message msg) {
        Bundle data = msg.getData();
        if (data != null) data.setClassLoader(getClassLoader());
        Bundle reply = new Bundle();
        int what = msg.what;
        try {
            switch (what) {
                case Titan2ApiContract.MSG_PING:
                    reply.putBoolean(Titan2ApiContract.KEY_OK, true);
                    reply.putString(Titan2ApiContract.KEY_VALUE, "pong");
                    break;
                case Titan2ApiContract.MSG_GET_PAD_MODE:
                    reply.putString(Titan2ApiContract.KEY_MODE, PadModeController.getMode(this));
                    reply.putLong(Titan2ApiContract.KEY_EPOCH, readEpoch());
                    reply.putBoolean(Titan2ApiContract.KEY_OK, true);
                    break;
                case Titan2ApiContract.MSG_SET_PAD_MODE: {
                    String mode = data != null
                        ? data.getString(Titan2ApiContract.KEY_MODE, PadModeController.OFF)
                        : PadModeController.OFF;
                    // setMode → notifyPadModeChanged already bumps epoch + regrab
                    boolean ok = PadModeController.setMode(this, mode);
                    long epoch = readEpoch();
                    reply.putBoolean(Titan2ApiContract.KEY_OK, ok);
                    reply.putString(Titan2ApiContract.KEY_MODE, PadModeController.getMode(this));
                    reply.putLong(Titan2ApiContract.KEY_EPOCH, epoch);
                    broadcastPadEvent(PadModeController.getMode(this), epoch);
                    break;
                }
                case Titan2ApiContract.MSG_GET_FOLLOW_ORIENT:
                    reply.putBoolean(Titan2ApiContract.KEY_FOLLOW,
                        PadModeController.isFollowOrient(this));
                    reply.putBoolean(Titan2ApiContract.KEY_OK, true);
                    break;
                case Titan2ApiContract.MSG_SET_FOLLOW_ORIENT: {
                    boolean on = data != null && data.getBoolean(Titan2ApiContract.KEY_FOLLOW, false);
                    boolean ok = PadModeController.setFollowOrient(this, on);
                    reply.putBoolean(Titan2ApiContract.KEY_OK, ok);
                    reply.putBoolean(Titan2ApiContract.KEY_FOLLOW, on);
                    break;
                }
                case Titan2ApiContract.MSG_PUBLISH_ROTATION:
                    PadModeController.publishRotation(this);
                    reply.putBoolean(Titan2ApiContract.KEY_OK, true);
                    break;
                case Titan2ApiContract.MSG_GET_PAD_EPOCH:
                    reply.putLong(Titan2ApiContract.KEY_EPOCH, readEpoch());
                    reply.putBoolean(Titan2ApiContract.KEY_OK, true);
                    break;

                case Titan2ApiContract.MSG_GET_KEY_ACTION:
                case Titan2ApiContract.MSG_GET_EFFECTIVE_KEY_ACTION: {
                    String slot = data != null ? data.getString(Titan2ApiContract.KEY_SLOT) : null;
                    String act = (slot == null) ? KeyMapPrefs.ACT_DEFAULT
                        : tempStack.getEffective(keyPrefs, slot);
                    reply.putString(Titan2ApiContract.KEY_ACTION, act);
                    reply.putBoolean(Titan2ApiContract.KEY_OK, true);
                    break;
                }
                case Titan2ApiContract.MSG_SET_KEY_ACTION: {
                    String slot = data != null ? data.getString(Titan2ApiContract.KEY_SLOT) : null;
                    String act = data != null ? data.getString(Titan2ApiContract.KEY_ACTION) : null;
                    String pkg = data != null ? data.getString(Titan2ApiContract.KEY_PKG) : null;
                    if (slot != null) {
                        if (pkg != null && !pkg.isEmpty()) {
                            // Per-app profile (HID host = com.titanus2.usbhid)
                            KeyMapProfiles profiles = new KeyMapProfiles(this);
                            profiles.ensureProfile(pkg, null);
                            profiles.setOverride(pkg, slot, act);
                            // Live HID session: refresh host layer from profile
                            if (Titan2ApiContract.HID_HOST_PKG.equals(pkg)
                                    && tempStack.hasLayer(Titan2ApiContract.LAYER_HID_HOST)) {
                                Map<String, String> snap = profiles.snapshot(pkg);
                                tempStack.push(Titan2ApiContract.LAYER_HID_HOST, snap);
                            }
                        } else {
                            keyPrefs.setAction(slot, act);
                        }
                        tempStack.publishEffective(this, keyPrefs);
                        reply.putBoolean(Titan2ApiContract.KEY_OK, true);
                    } else {
                        reply.putBoolean(Titan2ApiContract.KEY_OK, false);
                        reply.putString(Titan2ApiContract.KEY_ERROR, "no slot");
                    }
                    break;
                }
                case Titan2ApiContract.MSG_ENSURE_KEYMAP_PROFILE: {
                    String pkg = data != null ? data.getString(Titan2ApiContract.KEY_PKG) : null;
                    String label = data != null ? data.getString(Titan2ApiContract.KEY_LABEL) : null;
                    if (pkg == null || pkg.isEmpty()) {
                        reply.putBoolean(Titan2ApiContract.KEY_OK, false);
                        reply.putString(Titan2ApiContract.KEY_ERROR, "no pkg");
                        break;
                    }
                    KeyMapProfiles profiles = new KeyMapProfiles(this);
                    profiles.ensureProfile(pkg, label);
                    reply.putBoolean(Titan2ApiContract.KEY_OK, true);
                    reply.putString(Titan2ApiContract.KEY_PKG, pkg);
                    break;
                }
                case Titan2ApiContract.MSG_GET_PROFILE_MAP: {
                    String pkg = data != null ? data.getString(Titan2ApiContract.KEY_PKG) : null;
                    Bundle map = new Bundle();
                    if (pkg != null && !pkg.isEmpty()) {
                        KeyMapProfiles profiles = new KeyMapProfiles(this);
                        for (Map.Entry<String, String> e : profiles.snapshot(pkg).entrySet()) {
                            if (e.getKey() != null && e.getValue() != null) {
                                map.putString(e.getKey(), e.getValue());
                            }
                        }
                    }
                    reply.putBundle(Titan2ApiContract.KEY_MAP, map);
                    reply.putBoolean(Titan2ApiContract.KEY_OK, true);
                    break;
                }
                case Titan2ApiContract.MSG_REFRESH_HID_HOST_LAYER: {
                    // IFF physical HID session — never re-stick host layer when idle.
                    if (!TempKeyMapStack.hidSessionLive(this)) {
                        try {
                            tempStack.clearStaleHidSilence(this, keyPrefs);
                        } catch (Exception ignored) {}
                        reply.putBoolean(Titan2ApiContract.KEY_OK, false);
                        reply.putString(Titan2ApiContract.KEY_ERROR, "hid_session_off");
                        reply.putInt(Titan2ApiContract.KEY_VALUE, 0);
                        break;
                    }
                    KeyMapProfiles profiles = new KeyMapProfiles(this);
                    String pkg = Titan2ApiContract.HID_HOST_PKG;
                    profiles.ensureProfile(pkg, Titan2ApiContract.HID_HOST_LABEL);
                    Map<String, String> snap = profiles.snapshot(pkg);
                    tempStack.push(Titan2ApiContract.LAYER_HID_HOST, snap);
                    tempStack.publishEffective(this, keyPrefs);
                    reply.putBoolean(Titan2ApiContract.KEY_OK, true);
                    reply.putInt(Titan2ApiContract.KEY_VALUE, snap.size());
                    break;
                }
                case Titan2ApiContract.MSG_PUSH_TEMP_KEYMAP: {
                    String layerId = data != null
                        ? data.getString(Titan2ApiContract.KEY_LAYER_ID) : null;
                    // HID layers only while session is live (session-only override).
                    if (TempKeyMapStack.isSilenceLayer(layerId)
                            || Titan2ApiContract.LAYER_HID_HOST.equals(layerId)
                            || Titan2ApiContract.LAYER_HID_SIDE_KEYS.equals(layerId)) {
                        if (!TempKeyMapStack.hidSessionLive(this)) {
                            try {
                                tempStack.clearStaleHidSilence(this, keyPrefs);
                            } catch (Exception ignored) {}
                            reply.putBoolean(Titan2ApiContract.KEY_OK, false);
                            reply.putString(Titan2ApiContract.KEY_ERROR, "hid_session_off");
                            break;
                        }
                    }
                    Bundle mapB = data != null
                        ? data.getBundle(Titan2ApiContract.KEY_MAP) : null;
                    Map<String, String> map = new LinkedHashMap<>();
                    if (mapB != null) {
                        for (String k : mapB.keySet()) {
                            String v = mapB.getString(k);
                            if (k != null && v != null) map.put(k, v);
                        }
                    }
                    tempStack.push(layerId, map);
                    tempStack.publishEffective(this, keyPrefs);
                    reply.putBoolean(Titan2ApiContract.KEY_OK, true);
                    reply.putString(Titan2ApiContract.KEY_LAYER_ID, layerId);
                    break;
                }
                case Titan2ApiContract.MSG_SILENCE_KEY_REMAPS: {
                    // HID / guests: pause phone shortcuts for this layer.
                    // Computer actions (mouse:*/host:*) are pinned into the layer
                    // so side→click works without relying on foreground package.
                    // Phone actions become none — a11y must still swallow those
                    // keys (tempDefines) so keylayout APP_SWITCH does not fire.
                    // Exception (FB-HID-1): Back/Recents omitted from silence so
                    // exclusive titan2-phone-nav reinject reaches the master phone.
                    // IFF physical HID session — silence is not a sticky global.
                    if (!TempKeyMapStack.hidSessionLive(this)) {
                        try {
                            tempStack.clearStaleHidSilence(this, keyPrefs);
                        } catch (Exception ignored) {}
                        reply.putBoolean(Titan2ApiContract.KEY_OK, false);
                        reply.putString(Titan2ApiContract.KEY_ERROR, "hid_session_off");
                        break;
                    }
                    String layerId = data != null
                        ? data.getString(Titan2ApiContract.KEY_LAYER_ID) : null;
                    if (layerId == null || layerId.isEmpty()) {
                        layerId = Titan2ApiContract.LAYER_HID_SESSION;
                    }
                    KeyMapProfiles profiles = new KeyMapProfiles(this);
                    Map<String, String> silence =
                        TempKeyMapStack.buildSilenceMap(keyPrefs, profiles, this);
                    int keptMouse = 0;
                    for (String v : silence.values()) {
                        if (KeyMapPrefs.isComputerAction(v)) keptMouse++;
                    }
                    tempStack.push(layerId, silence);
                    tempStack.publishEffective(this, keyPrefs);
                    Log.i(TAG, "silence key remaps layer=" + layerId
                        + " slots=" + silence.size() + " computerPinned=" + keptMouse);
                    reply.putBoolean(Titan2ApiContract.KEY_OK, true);
                    reply.putString(Titan2ApiContract.KEY_LAYER_ID, layerId);
                    break;
                }
                case Titan2ApiContract.MSG_POP_TEMP_KEYMAP: {
                    String layerId = data != null
                        ? data.getString(Titan2ApiContract.KEY_LAYER_ID) : null;
                    boolean removed = tempStack.pop(layerId);
                    tempStack.publishEffective(this, keyPrefs);
                    reply.putBoolean(Titan2ApiContract.KEY_OK, true);
                    reply.putBoolean(Titan2ApiContract.KEY_VALUE, removed);
                    break;
                }
                case Titan2ApiContract.MSG_LIST_TEMP_LAYERS: {
                    List<String> layers = tempStack.layersBottomToTop();
                    reply.putStringArray(Titan2ApiContract.KEY_LAYERS,
                        layers.toArray(new String[0]));
                    reply.putBoolean(Titan2ApiContract.KEY_OK, true);
                    break;
                }
                case Titan2ApiContract.MSG_PUBLISH_KEYMAP:
                    tempStack.publishEffective(this, keyPrefs);
                    reply.putBoolean(Titan2ApiContract.KEY_OK, true);
                    break;

                case Titan2ApiContract.MSG_GET_LED_LEVEL: {
                    int lvl = 3;
                    try {
                        String s = AgentBridge.get(this, AgentBridge.LED_LEVEL, "3");
                        lvl = Integer.parseInt(s.trim());
                    } catch (Exception ignored) {}
                    reply.putInt(Titan2ApiContract.KEY_LEVEL, lvl);
                    reply.putBoolean(Titan2ApiContract.KEY_OK, true);
                    break;
                }
                case Titan2ApiContract.MSG_SET_LED_LEVEL: {
                    int lvl = data != null ? data.getInt(Titan2ApiContract.KEY_LEVEL, 3) : 3;
                    String err = KeyboardLed.setLevel(this, lvl);
                    reply.putBoolean(Titan2ApiContract.KEY_OK, err == null);
                    if (err != null) reply.putString(Titan2ApiContract.KEY_ERROR, err);
                    break;
                }
                case Titan2ApiContract.MSG_GET_LED_TIMEOUT: {
                    int to = 30;
                    try {
                        String s = AgentBridge.get(this, AgentBridge.LED_TIMEOUT, "30");
                        to = Integer.parseInt(s.trim());
                    } catch (Exception ignored) {}
                    reply.putInt(Titan2ApiContract.KEY_TIMEOUT, to);
                    reply.putBoolean(Titan2ApiContract.KEY_OK, true);
                    break;
                }
                case Titan2ApiContract.MSG_SET_LED_TIMEOUT: {
                    int to = data != null ? data.getInt(Titan2ApiContract.KEY_TIMEOUT, 0) : 0;
                    String err = KeyboardLed.setTimeoutSec(this, to);
                    reply.putBoolean(Titan2ApiContract.KEY_OK, err == null);
                    if (err != null) reply.putString(Titan2ApiContract.KEY_ERROR, err);
                    break;
                }
                case Titan2ApiContract.MSG_GET_ICON_OVERLAY:
                    reply.putString(Titan2ApiContract.KEY_PLATE,
                        com.titanus2.controls.ui.ThemePrefs.iconPlateHex(this));
                    reply.putString(Titan2ApiContract.KEY_GLYPH,
                        com.titanus2.controls.ui.ThemePrefs.iconGlyphHex(this));
                    reply.putBoolean(Titan2ApiContract.KEY_OK, true);
                    break;
                case Titan2ApiContract.MSG_SET_ICON_OVERLAY: {
                    String plate = data != null
                        ? data.getString(Titan2ApiContract.KEY_PLATE) : null;
                    String glyph = data != null
                        ? data.getString(Titan2ApiContract.KEY_GLYPH) : null;
                    String fact = com.titanus2.controls.ui.ThemePrefs.persistIconOverlay(
                        this, plate, glyph);
                    new Thread(com.titanus2.controls.ui.ThemePrefs::runIconOverlayApply,
                        "cube-icons").start();
                    boolean ok = fact != null && !fact.startsWith("fail");
                    reply.putBoolean(Titan2ApiContract.KEY_OK, ok);
                    reply.putString(Titan2ApiContract.KEY_VALUE, ok ? "queued" : fact);
                    if (!ok) reply.putString(Titan2ApiContract.KEY_ERROR, fact);
                    reply.putString(Titan2ApiContract.KEY_PLATE,
                        com.titanus2.controls.ui.ThemePrefs.iconPlateHex(this));
                    reply.putString(Titan2ApiContract.KEY_GLYPH,
                        com.titanus2.controls.ui.ThemePrefs.iconGlyphHex(this));
                    break;
                }
                case Titan2ApiContract.MSG_BUMP_KEY_ACTIVITY:
                    AgentBridge.bumpKeyActivity(this);
                    reply.putBoolean(Titan2ApiContract.KEY_OK, true);
                    break;

                default:
                    reply.putBoolean(Titan2ApiContract.KEY_OK, false);
                    reply.putString(Titan2ApiContract.KEY_ERROR, "unknown what=" + what);
                    what = Titan2ApiContract.MSG_REPLY_ERR;
                    break;
            }
            if (what != Titan2ApiContract.MSG_REPLY_ERR) {
                what = Titan2ApiContract.MSG_REPLY_OK;
            }
        } catch (Exception e) {
            Log.e(TAG, "handle fail what=" + msg.what, e);
            reply.putBoolean(Titan2ApiContract.KEY_OK, false);
            reply.putString(Titan2ApiContract.KEY_ERROR, e.getMessage());
            what = Titan2ApiContract.MSG_REPLY_ERR;
        }
        reply(msg, what, reply);
    }

    private void reply(Message req, int what, Bundle data) {
        if (req.replyTo == null) return;
        Message m = Message.obtain(null, what);
        m.arg1 = req.arg1; // echo token
        m.setData(data);
        try {
            req.replyTo.send(m);
        } catch (RemoteException e) {
            Log.w(TAG, "reply fail: " + e.getMessage());
        }
    }

    private long readEpoch() {
        try {
            String s = AgentBridge.get(this, AgentBridge.PAD_EPOCH, "0");
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return ControlPlane.getLong(this, Titan2ApiContract.FILE_PAD_EPOCH, 0);
        }
    }

    private void broadcastPadEvent(String mode, long epoch) {
        Bundle b = new Bundle();
        b.putString(Titan2ApiContract.KEY_MODE, PadModes.normalize(mode));
        b.putLong(Titan2ApiContract.KEY_EPOCH, epoch);
        for (Messenger c : eventClients) {
            try {
                Message m = Message.obtain(null, Titan2ApiContract.MSG_EVENT_PAD_MODE);
                m.setData(b);
                c.send(m);
            } catch (RemoteException e) {
                eventClients.remove(c);
            }
        }
    }

    /**
     * Bump pad epoch + regrab via AgentBridge (same multi-plane write as pad_mode).
     * ControlPlane alone can fail to create new files under SELinux.
     */
    public static long bumpPadEpoch(android.content.Context ctx) {
        long cur = 0;
        try {
            String s = AgentBridge.get(ctx, AgentBridge.PAD_EPOCH, "0");
            if (s != null && !s.isEmpty()) cur = Long.parseLong(s.trim());
        } catch (Exception ignored) {}
        long next = cur + 1;
        if (next < 1) next = 1;
        AgentBridge.put(ctx, AgentBridge.PAD_EPOCH, String.valueOf(next));
        AgentBridge.put(ctx, AgentBridge.PAD_REGRAB, "1");
        // Also best-effort ControlPlane for clients that only read OS/tmp
        ControlPlane.put(ctx, Titan2ApiContract.FILE_PAD_EPOCH, String.valueOf(next));
        ControlPlane.put(ctx, Titan2ApiContract.FILE_PAD_REGRAB, "1");
        return next;
    }

    /** Called from PadModeController so UI path also bumps epoch. */
    public static void notifyPadModeChanged(android.content.Context ctx, String mode) {
        long epoch = bumpPadEpoch(ctx);
        // Start service so binds stay warm
        try {
            Intent i = new Intent(ctx, Titan2CoreService.class);
            ctx.startService(i);
        } catch (Exception ignored) {}
        Log.i(TAG, "pad mode=" + mode + " epoch=" + epoch);
    }
}
