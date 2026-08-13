package com.titanus2.cubecontact;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;

/**
 * Detect Hybrid ROM / system install vs sideloaded APK.
 * Used so the app can default privilege and surface the right Settings path.
 */
public final class RomIntegration {
    private static final String CONTROLS_PKG = "com.titanus2.controls";

    private RomIntegration() {}

    public static boolean isSystemApp(Context c) {
        try {
            ApplicationInfo ai = c.getApplicationInfo();
            if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) return true;
            if ((ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) return true;
            String p = ai.sourceDir;
            if (p == null) return false;
            return p.startsWith("/system/")
                || p.startsWith("/system_ext/")
                || p.startsWith("/product/")
                || p.startsWith("/vendor/")
                || p.contains("/priv-app/");
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isPrivApp(Context c) {
        try {
            String p = c.getApplicationInfo().sourceDir;
            return p != null && p.contains("/priv-app/");
        } catch (Exception e) {
            return false;
        }
    }

    /** Hybrid ROM fingerprint: pad-agent plane keys or titan props. */
    public static boolean isHybridRom(Context c) {
        try {
            String sub = Settings.Global.getString(c.getContentResolver(), "titan2_sub_mode");
            if (sub != null) return true;
        } catch (Exception ignored) {}
        try {
            String fp = Build.FINGERPRINT != null ? Build.FINGERPRINT.toLowerCase() : "";
            String model = Build.MODEL != null ? Build.MODEL.toLowerCase() : "";
            String product = Build.PRODUCT != null ? Build.PRODUCT.toLowerCase() : "";
            if (fp.contains("lineage") || fp.contains("titan") || model.contains("titan")
                    || product.contains("g71") || product.contains("titan")) {
                // Only count as hybrid if Controls is present (our inject)
                return controlsInstalled(c);
            }
        } catch (Exception ignored) {}
        return controlsInstalled(c) && isSystemApp(c);
    }

    public static boolean controlsInstalled(Context c) {
        if (c == null) return false;
        try {
            c.getPackageManager().getPackageInfo(CONTROLS_PKG, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Bundle = system/priv-app or Hybrid plane present with our inject. */
    public static boolean isRomBundled(Context c) {
        return isSystemApp(c) || isPrivApp(c) || isHybridRom(c);
    }

    public static String installLabel(Context c) {
        if (isPrivApp(c)) return "ROM · priv-app";
        if (isSystemApp(c)) return "ROM · system";
        if (isHybridRom(c)) return "Hybrid ROM";
        return "User install";
    }

    public static String roleLine(Context c) {
        if (isRomBundled(c) && controlsInstalled(c)) {
            return "Rear cube: Titan Controls → Sub display → Cube";
        }
        if (controlsInstalled(c)) {
            return "Install as system for full access · Sub display → Cube available";
        }
        return "Live lattice · on-device agent";
    }

    public static PrivilegeMode defaultMode(Context c) {
        if (isRomBundled(c)) return PrivilegeMode.SYSTEM;
        return PrivilegeMode.UNPRIVILEGED_A11Y;
    }
}
