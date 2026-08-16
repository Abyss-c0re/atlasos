package com.titanus2.usbhid;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import com.titanus2.api.Titan2ApiContract;

/**
 * Framework {@link Titan2ApiContract#ACTION_REMOTE_INPUT} → USB/BT HID reports.
 * Computer actions from Controls (left click, Ctrl+F1, …) — not phone UI.
 */
public class HostMouseReceiver extends BroadcastReceiver {
    private static final String TAG = "RemoteInput";
    private static final Handler H = new Handler(Looper.getMainLooper());
    /** 1.66: OS may still twin-deliver ordered/perm broadcasts → multi-glyph. */
    private static final long DEDUPE_MS = 48L;
    private static long sLastKeyAt;
    private static int sLastKeySig;
    private static long sLastMouseAt;
    private static int sLastMouseButtons;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String act = intent.getAction();
        boolean remote = Titan2ApiContract.ACTION_REMOTE_INPUT.equals(act);
        boolean legacy = Titan2ApiContract.ACTION_HOST_MOUSE.equals(act)
            || "com.titanus2.usbhid.action.HOST_MOUSE".equals(act);
        if (!remote && !legacy) return;

        // 1.94 B2: session-off REMOTE_INPUT is dual residual — phone already
        // injects computerInput; keyTap here stamps last_host_hid and can seed
        // remote_q for the next exclusive Start. Soft Type uses in-app keyTap.
        if (!hostSinkLive(context)) {
            Log.w(TAG, "drop REMOTE_INPUT session-off (no FGS/session/grab)");
            return;
        }

        String kind = intent.getStringExtra(Titan2ApiContract.EXTRA_KIND);
        if (kind == null && legacy) kind = Titan2ApiContract.KIND_MOUSE;
        if (kind == null) kind = Titan2ApiContract.KIND_MOUSE;

        if (Titan2ApiContract.KIND_KEY.equals(kind)) {
            int mods = intent.getIntExtra(Titan2ApiContract.EXTRA_MODIFIERS, 0);
            int hid = intent.getIntExtra(Titan2ApiContract.EXTRA_HID_USAGE, 0);
            if (hid <= 0) return;
            // Packed bitmask from Controls: 1=ctrl 2=shift 4=alt 8=meta → HID mod byte
            int hidMod = 0;
            if ((mods & 1) != 0) hidMod |= 0x01; // LCtrl
            if ((mods & 2) != 0) hidMod |= 0x02; // LShift
            if ((mods & 4) != 0) hidMod |= 0x04; // LAlt
            if ((mods & 8) != 0) hidMod |= 0x08; // LGUI
            int sig = ((hidMod & 0xff) << 16) | (hid & 0xffff);
            long now = SystemClock.uptimeMillis();
            if (sig == sLastKeySig && (now - sLastKeyAt) < DEDUPE_MS) {
                Log.w(TAG, "dedupe host key hid=0x" + Integer.toHexString(hid)
                    + " dt=" + (now - sLastKeyAt));
                return;
            }
            sLastKeySig = sig;
            sLastKeyAt = now;
            // 1.87: clear softCompose only for exclusive/layout specials.
            // Type soft inject (keys=mouse=grab=0) must keep softCompose so
            // FGS does not reassert phys keys mid-payload (1.86 dual multi-key).
            try {
                boolean exclusiveOrLayout =
                    HidControl.isGrabPlaneExplicit(context)
                        || HidControl.isHostLayoutKeysPaused(context);
                if (exclusiveOrLayout) {
                    HidControl.setSoftCompose(false);
                }
            } catch (Exception ignored) {}
            try { HidControl.ensureSpecialsQueues(context); } catch (Exception ignored) {}
            // Side / host chords must not steal TitanKey back from an Android editor.
            // Never clear local_input here (share hub).
            Log.i(TAG, "host key hid=0x" + Integer.toHexString(hid)
                + " mod=0x" + Integer.toHexString(hidMod)
                + " session=" + HidSessionService.isRunning());
            // 1.65: ONE keyTap only — no retry+drain+delayed drain stack (N glyphs).
            // key() already enqueues on send fail; FGS loop drains. Do not double-tap.
            boolean ok = HidControl.keyTap(hidMod, hid);
            if (!ok) {
                try { HidControl.drainRemoteQueue(context); } catch (Exception ignored) {}
            }
            String stamp = String.format(java.util.Locale.US, "mod=%02x usage=%02x ok=%s",
                hidMod & 0xff, hid & 0xff, ok ? "1" : "0");
            try {
                HidControl.write(context, "titan2_last_host_hid", stamp);
            } catch (Exception ignored) {}
            try {
                android.provider.Settings.Global.putString(context.getContentResolver(),
                    "titan2_last_host_hid", stamp);
            } catch (Exception ignored) {}
            // Also mirror to tmp for unrooted smoke (Global can lag)
            try {
                HidControl.write(context, "titan2_last_host_special",
                    String.format(java.util.Locale.US, "hid=%02x m=%02x", hid & 0xff, hidMod & 0xff));
            } catch (Exception ignored) {}
            if (!ok) {
                Log.w(TAG, "keyTap failed — requeue drain next tick");
            }
            return;
        }

        // mouse click and/or wheel
        int wheel = intent.getIntExtra(Titan2ApiContract.EXTRA_MOUSE_WHEEL,
            intent.getIntExtra("wheel", 0));
        if (wheel != 0) {
            long nowW = SystemClock.uptimeMillis();
            // Dedup same wheel direction only (allow reverse immediately)
            int wSig = wheel > 0 ? 1 : -1;
            if (wSig == sLastMouseButtons && (nowW - sLastMouseAt) < DEDUPE_MS) {
                Log.w(TAG, "dedupe host wheel=" + wheel + " dt=" + (nowW - sLastMouseAt));
                return;
            }
            sLastMouseButtons = wSig;
            sLastMouseAt = nowW;
            Log.i(TAG, "host mouse wheel=" + wheel
                + " session=" + HidSessionService.isRunning());
            HidControl.mouseWheel(wheel);
            return;
        }
        int buttons = intent.getIntExtra(Titan2ApiContract.EXTRA_MOUSE_BUTTONS,
            intent.getIntExtra("buttons", 1));
        boolean tap = intent.getBooleanExtra(Titan2ApiContract.EXTRA_MOUSE_TAP,
            intent.getBooleanExtra("tap", true));
        if (buttons <= 0) buttons = 1;
        long nowM = SystemClock.uptimeMillis();
        if (buttons == sLastMouseButtons && (nowM - sLastMouseAt) < DEDUPE_MS) {
            Log.w(TAG, "dedupe host mouse buttons=" + buttons
                + " dt=" + (nowM - sLastMouseAt));
            return;
        }
        sLastMouseButtons = buttons;
        sLastMouseAt = nowM;
        Log.i(TAG, "host mouse buttons=" + buttons + " tap=" + tap
            + " session=" + HidSessionService.isRunning());
        final int b = buttons & 0xff;
        if (tap) {
            HidControl.mouseButtons(b);
            H.postDelayed(() -> HidControl.mouseButtons(0), 40);
        } else {
            HidControl.mouseButtons(b);
        }
    }

    /**
     * True when a host HID sink is actually live. Session plane, exclusive grab,
     * FGS, or soft Type compose — not a cold process start from broadcast.
     */
    private static boolean hostSinkLive(Context context) {
        try {
            if (HidSessionService.isRunning()) return true;
        } catch (Exception ignored) {}
        try {
            if (HidControl.isSoftCompose()) return true;
        } catch (Exception ignored) {}
        try {
            if (HidControl.isSessionOn(context)) return true;
        } catch (Exception ignored) {}
        try {
            if (HidControl.isGrabPlaneExplicit(context)) return true;
        } catch (Exception ignored) {}
        return false;
    }
}
