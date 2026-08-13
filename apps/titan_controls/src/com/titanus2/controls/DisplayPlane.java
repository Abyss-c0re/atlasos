package com.titanus2.controls;

import android.content.Context;
import android.provider.Settings;

/**
 * Display plane hints. Density/size are <b>global</b> on Android — cannot run
 * tablet dens for Settings and phone dens for Launcher at the same time.
 * <p>
 * Product default: <b>no foreground dens switching</b> (avoids visible DPI
 * flash when opening Settings). OS stays tablet dens ~300 + physical size
 * (Settings two-pane). Launcher shares that dens; clock size = DeskClock
 * widget span (see {@code resize_deskclock_widget.sh}).
 * <p>
 * Opt-in lab thrash: {@code settings put secure titan2_ui_plane_switch 1}
 * + host {@code titan_ui_plane_watch.sh} (will flash dens on app switch).
 */
public final class DisplayPlane {
    public static final String TABLET = "tablet";
    public static final String PHONE_LAUNCHER = "phone_launcher";
    public static final String PREF_ENABLED = "titan2_ui_plane_switch";

    private DisplayPlane() {}

    public static boolean switchEnabled(Context c) {
        try {
            // Default OFF — dens thrash when opening Settings is not product.
            String v = Settings.Secure.getString(c.getContentResolver(), PREF_ENABLED);
            return "1".equals(v) || "true".equalsIgnoreCase(v);
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isLauncherPkg(String pkg) {
        if (pkg == null) return false;
        if (pkg.contains("launcher3")) return true;
        if (pkg.contains("nexuslauncher")) return true;
        if (pkg.endsWith(".launcher")) return true;
        return false;
    }

    public static void onForeground(Context c, String pkg) {
        if (!switchEnabled(c)) {
            // Static tablet OS plane — do not flip dens/size with app focus.
            AgentBridge.put(c, AgentBridge.UI_PLANE, TABLET);
            return;
        }
        if (isLauncherPkg(pkg)) {
            AgentBridge.put(c, AgentBridge.UI_PLANE, PHONE_LAUNCHER);
        } else {
            AgentBridge.put(c, AgentBridge.UI_PLANE, TABLET);
        }
    }
}
