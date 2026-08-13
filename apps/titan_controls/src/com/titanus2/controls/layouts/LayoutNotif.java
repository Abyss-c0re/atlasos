package com.titanus2.controls.layouts;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import com.titanus2.controls.HostLayoutController;
import com.titanus2.controls.KeyMapActivity;
import com.titanus2.controls.KeyMapPrefs;

/**
 * Ongoing notification while a layout is sticky-toggled.
 * Action: turn layout off.
 */
public final class LayoutNotif {
    private static final String CH = "titan2_layout";
    private static final int ID = 0x7a1101;
    public static final String ACTION_OFF = "com.titanus2.controls.LAYOUT_NOTIF_OFF";

    private LayoutNotif() {}

    public static void sync(Context ctx) {
        if (ctx == null) return;
        Context app = ctx.getApplicationContext();
        String mode = HostLayoutController.effective(app);
        if (mode == null || HostLayoutController.MODE_OFF.equals(mode)
                || HostLayoutController.isHoldActive()) {
            cancel(app);
            return;
        }
        // Only sticky toggle / default (not momentary hold) needs a notif
        if (!HostLayoutController.isStickyActive()) {
            // default-on layout still useful to show
            if (!HostLayoutController.hasNonOffDefault(app)) {
                cancel(app);
                return;
            }
        }
        String name = new CustomLayoutStore(app).nameOf(mode);
        show(app, name, mode);
    }

    public static void show(Context ctx, String layoutName, String layoutId) {
        NotificationManager nm = (NotificationManager)
            ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        ensureChannel(nm);
        Intent open = new Intent(ctx, KeyMapActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent piOpen = PendingIntent.getActivity(ctx, 1, open,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent off = new Intent(ACTION_OFF);
        off.setPackage(ctx.getPackageName());
        PendingIntent piOff = PendingIntent.getBroadcast(ctx, 2, off,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String title = "Layout · " + (layoutName == null ? layoutId : layoutName);
        Notification.Builder b = new Notification.Builder(ctx, CH)
            .setContentTitle(title)
            .setContentText("Tap Off to return to normal typing")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(piOpen)
            .addAction(new Notification.Action.Builder(
                null, "Off", piOff).build())
            .setCategory(Notification.CATEGORY_SERVICE);
        nm.notify(ID, b.build());
    }

    public static void cancel(Context ctx) {
        try {
            NotificationManager nm = (NotificationManager)
                ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(ID);
        } catch (Exception ignored) {}
    }

    private static void ensureChannel(NotificationManager nm) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel ch = new NotificationChannel(CH, "Keyboard layouts",
            NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Active layout toggle");
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    public static final class OffReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null || !ACTION_OFF.equals(intent.getAction())) return;
            HostLayoutController.applyAction(context, KeyMapPrefs.ACT_LAYOUT_OFF);
            cancel(context);
        }
    }
}
