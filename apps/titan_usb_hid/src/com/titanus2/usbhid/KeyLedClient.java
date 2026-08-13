package com.titanus2.usbhid;

import android.content.Context;
import com.titanus2.api.Titan2Client;

/**
 * Keyboard backlight via Titan2 framework API (same plane as Titan Controls).
 * Bridge bumps activity on physical keys; app bumps on soft keys.
 */
public final class KeyLedClient {
    public static final String BRIGHTNESS = "titan2_keyled_brightness";
    public static final String TIMEOUT = "titan2_keyled_timeout";
    public static final String ACTIVITY = "/data/local/tmp/titan2_key_activity";
    public static final int DEFAULT_LEVEL = 3;
    /** Match Titan Controls: 30s idle; 0 = always on while screen awake. */
    public static final int DEFAULT_TIMEOUT_SEC = 30;

    private static volatile long lastBumpMs;

    private KeyLedClient() {}

    private static Titan2Client api(Context ctx) {
        return PadModeClient.api(ctx);
    }

    /** Ensure agent has brightness/timeout wants (does not override existing). */
    public static void ensureDefaults(Context ctx) {
        api(ctx).ensureLedDefaults();
    }

    /**
     * Stamp key activity for pad-agent. Do NOT write keypad_led sysfs here —
     * dual writers (app + agent + bridge) cause visible blink on MTK.
     */
    public static void bumpActivity() {
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastBumpMs < 200) return;
        lastBumpMs = nowMs;
        // Prefer framework; ControlPlane fallback inside client when unbound
        try {
            // No context in static bump from inject path — use process app if set
            Context ctx = HidAppContext.get();
            if (ctx != null) {
                api(ctx).bumpKeyActivity();
                return;
            }
        } catch (Exception ignored) {}
        // Last resort: write activity files directly
        long now = nowMs / 1000L;
        String payload = String.valueOf(now);
        byte[] data = payload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.io.File[] paths = new java.io.File[]{
            new java.io.File(HidControl.OS_CTRL, "titan2_key_activity"),
            new java.io.File(ACTIVITY)
        };
        for (java.io.File f : paths) {
            try {
                java.io.File parent = f.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(f, false)) {
                    fos.write(data);
                }
                //noinspection ResultOfMethodCallIgnored
                f.setReadable(true, false);
                //noinspection ResultOfMethodCallIgnored
                f.setWritable(true, false);
            } catch (Exception ignored) {}
        }
    }
}
