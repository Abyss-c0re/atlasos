package com.titanus2.controls;

/**
 * Privacy sandbox catalog — wrap critical-but-sus China OEM / MTK surfaces so
 * the user can isolate without blind removal (PRIVACY_POLICY §2).
 * <p>
 * Net path: {@code titan2-fw} entities (deny-uid / desire for system).
 * Sensor path: stock QS + SensorPrivacyEnforcer + hybrid belt (not this class).
 * <p>
 * Modes:
 * <ul>
 *   <li>{@link #MODE_MONITOR} — observe only (default for protected)</li>
 *   <li>{@link #MODE_WRAP} — desire deny-svc / deny-bin / deny-uid when fw on</li>
 *   <li>{@link #MODE_ALLOW} — never auto-deny</li>
 * </ul>
 * System UIDs never get netfilter DROP (brick risk) — wrap = honest desire +
 * privacy-belt messaging in Firewall UI.
 */
public final class PrivacySandbox {
    public static final String MODE_MONITOR = "monitor";
    public static final String MODE_WRAP = "wrap";
    public static final String MODE_ALLOW = "allow";

    /** kind, key, defaultMode, label, notes */
    public static final String[][] CATALOG = {
        { "svc", "cameraserver", MODE_WRAP, "Camera server",
            "System cam HAL. Netfilter cannot DROP uid 1047 safely alone — use sensor privacy + belt." },
        { "svc", "camerahalserver", MODE_WRAP, "Camera HAL",
            "MTK camera HAL. Privacy ON stops HAL via belt." },
        { "pkg", "com.mediatek.ims", MODE_MONITOR, "MTK IMS",
            "VoLTE. LOCATION_BYPASS+CAMERA privapp — monitor; wrap only if no voice." },
        { "pkg", "com.mediatek.gbaservice", MODE_MONITOR, "MTK GBA",
            "SIM auth for IMS. Network only — leave allow unless isolating radio plane." },
        { "pkg", "com.android.DeviceAsWebcam", MODE_WRAP, "Device as webcam",
            "USB camera export. User may wrap when not using webcam." },
        { "pkg", "com.android.devicediagnostics", MODE_MONITOR, "Device diagnostics",
            "AOSP diagnostics. Prefer monitor." },
        { "pkg", "com.mediatek.op08.ims", MODE_MONITOR, "OP08 IMS residual",
            "China carrier residual on data — monitor." },
        { "pkg", "com.mediatek.op08.phone", MODE_MONITOR, "OP08 phone residual",
            "China carrier residual — monitor." },
        { "bin", "nanobot", MODE_ALLOW, "Nanobot",
            "Protected — never auto-deny." },
        { "bin", "atlas", MODE_ALLOW, "Atlas",
            "Protected — never auto-deny." },
    };

    private PrivacySandbox() {}

    public static String defaultMode(String kind, String key) {
        if (kind == null || key == null) return MODE_MONITOR;
        for (String[] row : CATALOG) {
            if (kind.equals(row[0]) && key.equals(row[1])) return row[2];
        }
        return MODE_MONITOR;
    }
}
