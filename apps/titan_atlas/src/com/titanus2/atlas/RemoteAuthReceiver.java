package com.titanus2.atlas;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;

/**
 * Remote Atlas auth requests — KDE Connect plugin bridge, BlackCube sudo, nanobot.
 * <p>
 * Does <b>not</b> change local {@code atlas-auth}/sudo file protocol for shell.
 * Remote callers fire:
 * <pre>
 *   am broadcast -a com.titanus2.atlas.action.REMOTE_AUTH \
 *     --es auth_source "BlackCube sudo" \
 *     --es auth_reason "sudo apt install …" \
 *     --es auth_id "remote-…"
 * </pre>
 * Or share text via KDE Connect starting with {@code ATLAS_AUTH:}.
 * Phone wakes, shows notification with origin, launches biometric sheet.
 */
public class RemoteAuthReceiver extends BroadcastReceiver {
    private static final String TAG = "AtlasRemoteAuth";
    public static final String ACTION = "com.titanus2.atlas.action.REMOTE_AUTH";
    public static final String ACTION_KDE_SHARE = "android.intent.action.SEND";
    public static final String EXTRA_SOURCE = "auth_source";
    public static final String EXTRA_REASON = "auth_reason";
    public static final String EXTRA_ID = "auth_id";
    private static final String CH = "atlas_remote_auth";
    private static final int NID = 0xA071;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        String source = intent.getStringExtra(EXTRA_SOURCE);
        String reason = intent.getStringExtra(EXTRA_REASON);
        String id = intent.getStringExtra(EXTRA_ID);

        // KDE Connect / share text: ATLAS_AUTH:source|reason
        if (ACTION_KDE_SHARE.equals(action)
                || Intent.ACTION_SEND.equals(action)) {
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (text == null) text = intent.getStringExtra("android.intent.extra.TEXT");
            if (text == null || !text.trim().startsWith("ATLAS_AUTH:")) {
                return; // not ours
            }
            String body = text.trim().substring("ATLAS_AUTH:".length()).trim();
            int bar = body.indexOf('|');
            if (bar > 0) {
                source = body.substring(0, bar).trim();
                reason = body.substring(bar + 1).trim();
            } else {
                source = "KDE Connect";
                reason = body;
            }
        }

        if (source == null || source.isEmpty()) source = "Remote";
        if (reason == null || reason.isEmpty()) reason = "Privilege request";
        if (id == null || id.isEmpty()) {
            id = "remote-" + System.currentTimeMillis();
        }

        // File protocol so atlas-auth waiters (remote askpass) can complete
        try {
            java.io.File dir = AtlasAuth.authDir(context);
            java.io.File req = new java.io.File(dir, "req." + id);
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(req)) {
                String line = reason + "\n# source=" + source + "\n";
                fos.write(line.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            //noinspection ResultOfMethodCallIgnored
            req.setReadable(true, false);
            java.io.File wake = new java.io.File(dir, "wake");
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(wake)) {
                fos.write("1\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Log.w(TAG, "req write", e);
        }

        wakeScreen(context);
        notifyAndPrompt(context, id, source, reason);
        // Ensure session service polls
        try {
            Intent svc = new Intent(context, AtlasSessionService.class);
            svc.setAction(AtlasSessionService.ACTION_ENSURE_AUTH);
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(svc);
            } else {
                context.startService(svc);
            }
        } catch (Exception ignored) {
        }
    }

    private static void wakeScreen(Context c) {
        try {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            @SuppressWarnings("deprecation")
            PowerManager.WakeLock wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                    | PowerManager.ACQUIRE_CAUSES_WAKEUP
                    | PowerManager.ON_AFTER_RELEASE,
                "titanus2:atlas_remote_auth");
            wl.acquire(8000L);
        } catch (Exception e) {
            Log.w(TAG, "wake", e);
        }
    }

    private static void notifyAndPrompt(Context c, String id, String source, String reason) {
        ensureChannel(c);
        String title = "Atlas auth · " + source;
        String body = reason;
        Intent ui = new Intent(c, AuthPromptActivity.class);
        ui.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
            | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
            | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        ui.putExtra(AuthPromptActivity.EXTRA_ID, id);
        ui.putExtra(AuthPromptActivity.EXTRA_REASON, reason);
        ui.putExtra(AuthPromptActivity.EXTRA_SOURCE, source);
        PendingIntent pi = PendingIntent.getActivity(c, id.hashCode(), ui,
            Build.VERSION.SDK_INT >= 23
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(c, CH);
        } else {
            b = new Notification.Builder(c);
        }
        b.setContentTitle(title)
            .setContentText(body)
            .setStyle(new Notification.BigTextStyle().bigText(
                "From: " + source + "\n\n" + body
                    + "\n\nConfirm with fingerprint or lock screen."))
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(Notification.PRIORITY_HIGH)
            .setCategory(Notification.CATEGORY_ALARM);
        if (Build.VERSION.SDK_INT >= 21) {
            b.setFullScreenIntent(pi, true);
        }
        NotificationManager nm = (NotificationManager)
            c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NID, b.build());
        }
        try {
            AtlasPrefs.markAuthUi(c, true);
            c.startActivity(ui);
        } catch (Exception e) {
            Log.w(TAG, "start auth ui", e);
        }
    }

    private static void ensureChannel(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager)
            c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CH,
            "Atlas remote auth", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Sudo / privilege requests from KDE Connect, BlackCube, nanobot");
        ch.enableVibration(true);
        nm.createNotificationChannel(ch);
    }
}
