package com.titanus2.controls;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Display;
import android.view.Surface;

/**
 * Keeps {@code titan2_pad_rotation} in sync with the default display while
 * Follow screen is on. Pad-agent / orient-rel read that stamp for mouse axes.
 * <p>
 * Also polled every 1.5s — configChanges in Activities can miss landscape.
 */
public class PadOrientationService extends Service {
    private static final long POLL_MS = 1500L;
    private final Handler h = new Handler(Looper.getMainLooper());
    private DisplayManager dm;
    private int last = -1;

    private final DisplayManager.DisplayListener listener =
        new DisplayManager.DisplayListener() {
            @Override public void onDisplayAdded(int displayId) {}
            @Override public void onDisplayRemoved(int displayId) {}
            @Override public void onDisplayChanged(int displayId) {
                if (displayId == Display.DEFAULT_DISPLAY) stamp();
            }
        };

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!PadModeController.isFollowOrient(PadOrientationService.this)) {
                stopSelf();
                return;
            }
            stamp();
            h.postDelayed(this, POLL_MS);
        }
    };

    public static void sync(Context ctx) {
        if (ctx == null) return;
        if (!PadModeController.isFollowOrient(ctx)) {
            try {
                ctx.getApplicationContext()
                    .stopService(new Intent(ctx, PadOrientationService.class));
            } catch (Exception ignored) {}
            return;
        }
        String mode = PadModeController.getMode(ctx);
        if (!PadModeController.MOUSE.equals(mode)
                && !PadModeController.TRACKPAD.equals(mode)) {
            try {
                ctx.getApplicationContext()
                    .stopService(new Intent(ctx, PadOrientationService.class));
            } catch (Exception ignored) {}
            return;
        }
        try {
            Intent i = new Intent(ctx, PadOrientationService.class);
            ctx.getApplicationContext().startService(i);
        } catch (Exception ignored) {}
        PadModeController.publishRotation(ctx);
    }

    @Override public void onCreate() {
        super.onCreate();
        dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        if (dm != null) {
            try {
                dm.registerDisplayListener(listener, h);
            } catch (Exception ignored) {}
        }
        stamp();
        h.postDelayed(poll, POLL_MS);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        stamp();
        return START_STICKY;
    }

    @Override public void onDestroy() {
        h.removeCallbacks(poll);
        if (dm != null) {
            try { dm.unregisterDisplayListener(listener); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void stamp() {
        if (!PadModeController.isFollowOrient(this)) return;
        int r = 0;
        try {
            if (dm != null) {
                Display d = dm.getDisplay(Display.DEFAULT_DISPLAY);
                if (d != null) r = d.getRotation();
            }
        } catch (Exception ignored) {}
        if (r < Surface.ROTATION_0 || r > Surface.ROTATION_270) r = 0;
        if (r == last) return;
        last = r;
        AgentBridge.put(this, AgentBridge.PAD_ROTATION, String.valueOf(r));
    }
}
