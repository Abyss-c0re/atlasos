package com.titanus2.controls.notifled;

import android.app.Notification;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.view.Display;

/**
 * Publishes alert presence to pad-agent for keyboard LED.
 *
 * Rules (product):
 * <ul>
 *   <li>Screen interactive / unlocked → LED never held for notifs; baseline cleared.</li>
 *   <li>On lock (SCREEN_OFF) → record lock epoch; force blink off until a <em>new</em>
 *       notification arrives after that epoch.</li>
 *   <li>Blink only while screen off AND there is at least one user alert posted
 *       at/after the current lock epoch (or after unlock baseline).</li>
 *   <li>On unlock (USER_PRESENT / SCREEN_ON interactive) → clear active blink;
 *       next lock only flashes for notifications that arrive after that lock.</li>
 * </ul>
 */
public class NotifLedService extends NotificationListenerService {
    private static volatile NotifLedService sInstance;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable debounced = this::publish;

    /** ElapsedRealtime when panel last went off (lock). 0 = not locked this cycle. */
    private volatile long lockEpochElapsed;
    /**
     * Highest postTime (wall ms) of a notif that already counted during a previous
     * unlock session — after unlock we mark all current posts as "seen" so they
     * never flash on the next lock unless re-posted as new.
     */
    private volatile long seenThroughPostTime;

    private DisplayManager displayManager;
    private final DisplayManager.DisplayListener displayListener =
        new DisplayManager.DisplayListener() {
            @Override public void onDisplayAdded(int displayId) {}
            @Override public void onDisplayRemoved(int displayId) {}
            @Override public void onDisplayChanged(int displayId) {
                schedule();
            }
        };
    private final BroadcastReceiver screenRx = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String a = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(a)) {
                // New lock session: only posts after this moment count.
                lockEpochElapsed = SystemClock.elapsedRealtime();
                // Keep seenThroughPostTime — old notifs still do not count.
                NotifLedController.setActiveAlerts(NotifLedService.this, false);
            } else if (Intent.ACTION_SCREEN_ON.equals(a)
                    || Intent.ACTION_USER_PRESENT.equals(a)) {
                // Unlock: clear blink and mark all current alerts as old.
                lockEpochElapsed = 0;
                markAllCurrentAsSeen();
                NotifLedController.setActiveAlerts(NotifLedService.this, false);
            }
            schedule();
        }
    };

    public static boolean isFeatureEnabled(android.content.Context ctx) {
        return NotifLedPrefs.isEnabled(ctx);
    }

    public static void setFeatureEnabled(android.content.Context ctx, boolean on) {
        NotifLedPrefs.setEnabled(ctx, on);
        NotifLedController.publishConfig(ctx);
        if (!on) NotifLedController.setActiveAlerts(ctx, false);
    }

    public static int getBlinkLevel(android.content.Context ctx) {
        return NotifLedPrefs.getBrightness(ctx);
    }

    public static void setBlinkLevel(android.content.Context ctx, int level) {
        NotifLedPrefs.setBrightness(ctx, level);
        NotifLedController.publishConfig(ctx);
    }

    /** Rebuild rear notif strip after user changes max/size. */
    public static void refreshRearNotifs() {
        NotifLedService s = sInstance;
        if (s != null) s.schedule();
    }

    @Override public void onListenerConnected() {
        super.onListenerConnected();
        sInstance = this;
        displayManager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        if (displayManager != null) {
            displayManager.registerDisplayListener(displayListener, h);
        }
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_USER_PRESENT);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenRx, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenRx, f);
        }
        NotifLedController.publishConfig(this);
        // Boot: treat existing as seen; only new posts after next lock flash.
        markAllCurrentAsSeen();
        NotifLedController.setActiveAlerts(this, false);
        publish();
    }

    @Override public void onListenerDisconnected() {
        sInstance = null;
        try { unregisterReceiver(screenRx); } catch (Exception ignored) {}
        if (displayManager != null) {
            try { displayManager.unregisterDisplayListener(displayListener); } catch (Exception ignored) {}
        }
        super.onListenerDisconnected();
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        schedule();
    }

    @Override public void onNotificationRemoved(StatusBarNotification sbn) {
        schedule();
    }

    private void schedule() {
        h.removeCallbacks(debounced);
        h.postDelayed(debounced, 100);
    }

    private boolean isInteractive() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && pm.isInteractive()) return true;
        } catch (Exception ignored) {}
        try {
            if (displayManager == null) {
                displayManager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
            }
            if (displayManager != null) {
                for (Display d : displayManager.getDisplays()) {
                    // Main display only — rear cube/face must not keep "interactive"
                    // forever and suppress lock LED rules.
                    if (d.getDisplayId() != Display.DEFAULT_DISPLAY) continue;
                    if (d.getState() == Display.STATE_ON
                            || d.getState() == Display.STATE_ON_SUSPEND) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private void markAllCurrentAsSeen() {
        long max = seenThroughPostTime;
        try {
            StatusBarNotification[] all = getActiveNotifications();
            if (all != null) {
                for (StatusBarNotification sbn : all) {
                    if (sbn == null) continue;
                    long t = sbn.getPostTime();
                    if (t > max) max = t;
                }
            }
        } catch (Exception ignored) {}
        // Also advance past "now" so in-flight posts during unlock edge don't flash.
        long now = System.currentTimeMillis();
        if (now > max) max = now;
        seenThroughPostTime = max;
    }

    /**
     * New since lock: posted after lock wall-clock baseline and after unlock-seen
     * watermark. Uses postTime (wall) + lockElapsed for the lock edge.
     */
    private boolean isNewSinceLock(StatusBarNotification sbn) {
        if (sbn == null) return false;
        long post = sbn.getPostTime();
        if (post <= seenThroughPostTime) return false;
        if (lockEpochElapsed <= 0) return false;
        // postTime is wall clock; lock uses elapsed. Approximate: if posted
        // after we marked seenThrough, and we are in a lock session, it is new.
        // Tighten: require postTime >= seenThroughPostTime (already) and that
        // the post is not older than the lock session wall estimate.
        long lockWall = System.currentTimeMillis()
            - (SystemClock.elapsedRealtime() - lockEpochElapsed);
        return post >= lockWall - 250L;
    }

    private void publish() {
        try {
            StatusBarNotification[] all = null;
            try {
                all = getActiveNotifications();
            } catch (SecurityException ignored) {}
            // Always feed rear-face notif strip (independent of keyboard LED toggle).
            try {
                com.titanus2.controls.subdisplay.SubDisplayNotifs.updateFrom(this, all);
            } catch (Exception ignored) {}
            if (!NotifLedPrefs.isEnabled(this)) {
                NotifLedController.publishConfig(this);
                NotifLedController.setActiveAlerts(this, false);
                return;
            }
            NotifLedController.publishConfig(this);
            // Looking at the phone: never hold notif LED; treat current as seen.
            if (isInteractive()) {
                if (lockEpochElapsed != 0) {
                    lockEpochElapsed = 0;
                    markAllCurrentAsSeen();
                }
                NotifLedController.setActiveAlerts(this, false);
                return;
            }
            // Screen off / locked: only NEW alerts since this lock.
            if (lockEpochElapsed <= 0) {
                // SCREEN_OFF missed (Doze edge) — start lock session now.
                lockEpochElapsed = SystemClock.elapsedRealtime();
            }
            int count = 0;
            if (all != null) {
                for (StatusBarNotification sbn : all) {
                    if (!countsAsAlert(sbn)) continue;
                    if (!isNewSinceLock(sbn)) continue;
                    count++;
                }
            }
            NotifLedController.setActiveAlerts(this, count > 0);
        } catch (SecurityException e) {
            NotifLedController.setActiveAlerts(this, false);
        } catch (Exception e) {
            NotifLedController.setActiveAlerts(this, false);
        }
    }

    /**
     * Only real user-facing alerts pulse the keyboard LED.
     * Persistent / FGS / media / silent / local-only never count.
     */
    static boolean countsAsAlert(StatusBarNotification sbn) {
        if (sbn == null) return false;
        if (sbn.isOngoing()) return false;
        Notification n = sbn.getNotification();
        if (n == null) return false;
        int flags = n.flags;
        if ((flags & Notification.FLAG_ONGOING_EVENT) != 0) return false;
        if ((flags & Notification.FLAG_FOREGROUND_SERVICE) != 0) return false;
        if ((flags & Notification.FLAG_GROUP_SUMMARY) != 0) return false;
        if ((flags & Notification.FLAG_LOCAL_ONLY) != 0) return false;
        if ("com.titanus2.controls".equals(sbn.getPackageName())) return false;
        if ("com.titanus2.cubecontact".equals(sbn.getPackageName())) return false;
        if ("android".equals(sbn.getPackageName())) return false;
        if ("com.android.systemui".equals(sbn.getPackageName())) return false;
        String cat = n.category;
        if (cat != null) {
            if (Notification.CATEGORY_SERVICE.equals(cat)) return false;
            if (Notification.CATEGORY_TRANSPORT.equals(cat)) return false;
            if (Notification.CATEGORY_PROGRESS.equals(cat)) return false;
            if (Notification.CATEGORY_SYSTEM.equals(cat)) return false;
        }
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                if (n.getChannelId() != null
                        && n.extras != null
                        && n.extras.getBoolean("android.ongoing", false)) {
                    return false;
                }
            } catch (Throwable ignored) {}
        }
        if (n.priority < Notification.PRIORITY_DEFAULT
                && (cat == null
                || Notification.CATEGORY_STATUS.equals(cat)
                || Notification.CATEGORY_RECOMMENDATION.equals(cat))) {
            return false;
        }
        if (n.priority < Notification.PRIORITY_HIGH
                && n.defaults == 0
                && (n.sound == null)
                && (n.vibrate == null || n.vibrate.length == 0)
                && (n.ledARGB == 0)) {
            if (cat != null && (Notification.CATEGORY_MESSAGE.equals(cat)
                    || Notification.CATEGORY_EMAIL.equals(cat)
                    || Notification.CATEGORY_CALL.equals(cat)
                    || Notification.CATEGORY_ALARM.equals(cat)
                    || Notification.CATEGORY_REMINDER.equals(cat)
                    || Notification.CATEGORY_EVENT.equals(cat))) {
                return true;
            }
            if (n.priority < Notification.PRIORITY_DEFAULT) return false;
        }
        return true;
    }
}
