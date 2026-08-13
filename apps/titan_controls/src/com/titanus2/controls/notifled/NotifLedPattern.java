package com.titanus2.controls.notifled;

/**
 * Pure pattern math for keyboard notif LED. Same rules as pad-agent.
 * level 0–7; periodMs/onMs as in prefs.
 */
public final class NotifLedPattern {
    private NotifLedPattern() {}

    /**
     * @param nowMs wall-clock millis
     * @param mode solid|blink|breathe
     * @param peak 1–7
     * @param periodMs cycle length
     * @param onMs blink on-time; 0 with blink ⇒ solid
     */
    public static int levelAt(long nowMs, String mode, int peak, int periodMs, int onMs) {
        if (peak < 0) peak = 0;
        if (peak > 7) peak = 7;
        if (periodMs < 50) periodMs = 50;
        if (NotifLedPrefs.MODE_SOLID.equals(mode)) return peak;
        if (NotifLedPrefs.MODE_BREATHE.equals(mode)) {
            long t = nowMs % periodMs;
            long half = periodMs / 2;
            if (half < 1) half = 1;
            int lev;
            if (t <= half) {
                lev = (int) ((t * peak) / half);
            } else {
                lev = (int) (((periodMs - t) * peak) / half);
            }
            if (lev < 0) lev = 0;
            if (lev > 7) lev = 7;
            return lev;
        }
        // blink
        if (onMs <= 0) return peak; // duration 0 ⇒ stay on
        if (onMs >= periodMs) return peak;
        long t = nowMs % periodMs;
        return t < onMs ? peak : 0;
    }
}
