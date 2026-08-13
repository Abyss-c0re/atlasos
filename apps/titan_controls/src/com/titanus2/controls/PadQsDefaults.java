package com.titanus2.controls;

import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

/**
 * Ensure the pad mode QS tile is present and near the front of the default
 * Quick Settings panel (not only available under "edit tiles").
 * <p>
 * Uses {@code Settings.Secure.sysui_qs_tiles} (priv-app WRITE_SECURE_SETTINGS).
 * Spec format: {@code custom(com.titanus2.controls/.PadModeTileService)}.
 * <p>
 * After wipe, SystemUI often owns an empty setting then writes stock defaults
 * later — a single BOOT_COMPLETED write can be lost. We seed a full stock
 * list when empty and re-apply on delayed retries until the tile sticks.
 */
public final class PadQsDefaults {
    private static final String TAG = "PadQsDefaults";
    private static final String SECURE_KEY = "sysui_qs_tiles";
    /** Place after connectivity cluster so it shows on first QS page. */
    private static final String[] PREFER_AFTER = {
        "internet", "wifi", "cell", "bt", "bluetooth"
    };
    /**
     * Lineage/AOSP-ish first-page defaults used only when the secure setting
     * is empty (fresh wipe / SystemUI not yet seeded). Pad tile is inserted
     * into this list — writing only our custom tile would leave QS empty of
     * stock tiles and SystemUI may replace the whole string.
     */
    private static final String STOCK_DEFAULTS =
        "internet,bt,flashlight,dnd,alarm,airplane,controls,rotation,"
            + "battery,cast,screenrecord,hotspot,location,night,saver";
    /** Boot re-seed delays (ms) — cover SystemUI late default write. */
    private static final long[] RETRY_MS = {
        2_000L, 8_000L, 20_000L, 45_000L, 90_000L
    };

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static boolean retriesScheduled;

    private PadQsDefaults() {}

    public static String tileSpec(Context ctx) {
        ComponentName cn = new ComponentName(ctx, PadModeTileService.class);
        // SystemUI accepts short form .Class when package matches
        return "custom(" + cn.flattenToShortString() + ")";
    }

    /**
     * Insert pad tile into default QS list if missing, or move it forward if it
     * is only at the end of a long list. Idempotent.
     * @return true if secure setting was written
     */
    public static boolean ensureDefaultTile(Context ctx) {
        if (ctx == null) return false;
        Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        String spec = tileSpec(app);
        String alt = "custom(" + new ComponentName(app.getPackageName(),
            PadModeTileService.class.getName()).flattenToString() + ")";
        try {
            String raw = Settings.Secure.getString(app.getContentResolver(), SECURE_KEY);
            boolean wasEmpty = raw == null || raw.trim().isEmpty();
            String cur = wasEmpty ? STOCK_DEFAULTS : raw.trim();
            if (wasEmpty) {
                Log.i(TAG, "sysui_qs_tiles empty — seeding stock defaults + pad");
            }

            String[] parts = cur.split(",");
            java.util.ArrayList<String> list = new java.util.ArrayList<>();
            int existing = -1;
            for (String p : parts) {
                if (p == null) continue;
                p = p.trim();
                if (p.isEmpty()) continue;
                if (isOurTile(p, spec, alt)) {
                    if (existing < 0) existing = list.size();
                    continue; // drop duplicates; re-insert once
                }
                list.add(p);
            }
            // Guard: never leave QS as pad-only (bad host seed / partial write)
            if (list.size() < 4) {
                list.clear();
                for (String p : STOCK_DEFAULTS.split(",")) {
                    if (p != null && !p.trim().isEmpty()) list.add(p.trim());
                }
                existing = -1;
                Log.w(TAG, "sysui_qs_tiles too thin — reseed stock defaults");
            }

            // QA: trackpad tile as OS default near front (after internet/bt cluster).
            // Force re-front whenever tile sits past first page (index > 3) — SystemUI
            // / human edit often parks custom tiles at the end of sysui_qs_tiles.
            int insertAt = preferredIndex(list);
            if (insertAt > 3) insertAt = 3;
            // Keep only if already early; never preserve end-of-list parking.
            if (existing >= 0 && existing <= 3) {
                insertAt = Math.min(existing, list.size());
            }
            if (insertAt > list.size()) insertAt = list.size();
            list.add(insertAt, spec);
            String next = join(list);

            // Already correct: present once, first page (≤3), same as live setting
            String live = raw == null ? "" : raw.trim();
            boolean alreadyFront = existing >= 0 && existing <= 3;
            if (!wasEmpty
                    && alreadyFront
                    && !hasDuplicate(parts, spec, alt)
                    && next.equals(live)) {
                return false;
            }

            boolean ok = Settings.Secure.putString(app.getContentResolver(), SECURE_KEY, next);
            Log.i(TAG, "sysui_qs_tiles pad default ok=" + ok + " at=" + insertAt
                + " wasAt=" + existing + " n=" + list.size()
                + " emptySeed=" + wasEmpty + " forceFront=" + !alreadyFront
                + " spec=" + spec);
            return ok;
        } catch (Exception e) {
            Log.w(TAG, "ensureDefaultTile: " + e.getMessage());
            return false;
        }
    }

    /**
     * Ensure now, then re-apply on delayed retries so SystemUI late defaults
     * cannot drop the pad tile after wipe. Safe to call multiple times.
     */
    public static void ensureDefaultTileWithRetries(Context ctx) {
        if (ctx == null) return;
        final Context app = ctx.getApplicationContext() != null
            ? ctx.getApplicationContext() : ctx;
        ensureDefaultTile(app);
        if (retriesScheduled) return;
        retriesScheduled = true;
        for (long delay : RETRY_MS) {
            MAIN.postDelayed(() -> {
                try {
                    ensureDefaultTile(app);
                } catch (Exception e) {
                    Log.w(TAG, "retry: " + e.getMessage());
                }
            }, delay);
        }
        // After last retry, allow a future boot to schedule again
        MAIN.postDelayed(() -> retriesScheduled = false,
            RETRY_MS[RETRY_MS.length - 1] + 5_000L);
    }

    private static boolean isOurTile(String p, String spec, String alt) {
        if (spec.equals(p) || alt.equals(p)) return true;
        return p.startsWith("custom(com.titanus2.controls/")
            && p.contains("PadModeTileService");
    }

    private static boolean hasDuplicate(String[] parts, String spec, String alt) {
        int n = 0;
        for (String p : parts) {
            if (p != null && isOurTile(p.trim(), spec, alt)) n++;
        }
        return n > 1;
    }

    private static int preferredIndex(java.util.List<String> list) {
        int best = -1;
        for (int i = 0; i < list.size(); i++) {
            String t = list.get(i).toLowerCase();
            for (String pref : PREFER_AFTER) {
                if (t.equals(pref) || t.startsWith(pref)) {
                    best = i;
                }
            }
        }
        if (best >= 0) return best + 1;
        return list.isEmpty() ? 0 : Math.min(2, list.size());
    }

    private static String join(java.util.List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(list.get(i));
        }
        return sb.toString();
    }
}
