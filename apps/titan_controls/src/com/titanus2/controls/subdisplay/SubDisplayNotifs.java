package com.titanus2.controls.subdisplay;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Top notification apps for the rear face (count from prefs).
 * Fed by {@link com.titanus2.controls.notifled.NotifLedService}.
 */
public final class SubDisplayNotifs {
    /** Hard ceiling; user max is via {@link SubDisplayPrefs#notifMaxApps}. */
    public static final int MAX_APPS_HARD = 6;
    @Deprecated public static final int MAX_APPS = 3;

    public static final class Entry {
        public final String pkg;
        public final int count;
        public final Drawable icon; // may be null

        public Entry(String pkg, int count, Drawable icon) {
            this.pkg = pkg;
            this.count = count;
            this.icon = icon;
        }
    }

    private static final Object LOCK = new Object();
    private static List<Entry> snapshot = Collections.emptyList();

    private SubDisplayNotifs() {}

    public static List<Entry> get() {
        synchronized (LOCK) {
            return snapshot;
        }
    }

    /** Rebuild from active notifications (listener thread). */
    public static void updateFrom(Context ctx, StatusBarNotification[] sbns) {
        Map<String, Integer> counts = new HashMap<>();
        if (sbns != null) {
            for (StatusBarNotification sbn : sbns) {
                if (sbn == null) continue;
                if (sbn.isOngoing()) continue;
                if ((sbn.getNotification().flags & android.app.Notification.FLAG_GROUP_SUMMARY) != 0)
                    continue;
                String pkg = sbn.getPackageName();
                if (pkg == null || pkg.isEmpty()) continue;
                if ("com.titanus2.controls".equals(pkg)) continue;
                if ("android".equals(pkg)) continue;
                counts.put(pkg, counts.getOrDefault(pkg, 0) + 1);
            }
        }
        List<Map.Entry<String, Integer>> ranked = new ArrayList<>(counts.entrySet());
        Collections.sort(ranked, new Comparator<Map.Entry<String, Integer>>() {
            @Override public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                int c = Integer.compare(b.getValue(), a.getValue());
                return c != 0 ? c : a.getKey().compareTo(b.getKey());
            }
        });
        PackageManager pm = ctx.getPackageManager();
        int max = Math.min(MAX_APPS_HARD, SubDisplayPrefs.notifMaxApps(ctx));
        List<Entry> out = new ArrayList<>(max);
        for (int i = 0; i < ranked.size() && out.size() < max; i++) {
            String pkg = ranked.get(i).getKey();
            int n = ranked.get(i).getValue();
            Drawable icon = null;
            try {
                icon = pm.getApplicationIcon(pkg);
            } catch (Exception ignored) {}
            out.add(new Entry(pkg, n, icon));
        }
        synchronized (LOCK) {
            snapshot = Collections.unmodifiableList(out);
        }
        // Nudge face repaint
        try {
            SubDisplayFaceOverlay.repaint(ctx);
        } catch (Exception ignored) {}
    }

    public static void clear() {
        synchronized (LOCK) {
            snapshot = Collections.emptyList();
        }
    }
}
