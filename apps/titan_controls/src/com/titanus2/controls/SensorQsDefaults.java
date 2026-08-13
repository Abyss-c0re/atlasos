package com.titanus2.controls;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

/**
 * QS hygiene for camera/mic privacy — stock tiles only after Cube MTK overlay.
 * <p>
 * SoT: docs/project/PRODUCT_PRIVACY.md · PRIVACY_POLICY §2.2.
 * <b>15.63:</b> Remove fake product {@code CameraPrivacyTileService} from QS.
 * Stock {@code cameratoggle}/{@code mictoggle} are the user surface when
 * {@code config_supportsCamToggle=true} (Cube bind of MTK FrameworkResOverlay).
 * Product tile was theater when CamToggle=false — apps ignored it; China vendor
 * could ignore it the same way. Fail-closed = SensorPrivacyService + belt, not
 * a custom QS chrome.
 */
public final class SensorQsDefaults {
    private static final String TAG = "SensorQsDefaults";
    private static final String SECURE_KEY = "sysui_qs_tiles";
    private static final long[] RETRY_MS = { 3_000L, 12_000L, 40_000L, 90_000L };
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static boolean retriesScheduled;

    private SensorQsDefaults() {}

    /**
     * Prefer stock cam/mic toggles; strip product privacy tiles and dead specs.
     * @return next list or null when no change
     */
    private static final String TORCH_TILE =
            "custom(com.titanus2.controls/.TorchTileService)";

    public static String buildCleanList(Context ctx, String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        String cur = raw.trim();
        java.util.ArrayList<String> list = new java.util.ArrayList<>();
        boolean hasCam = false;
        boolean hasMic = false;
        boolean hasTorch = false;
        for (String p : cur.split(",")) {
            if (p == null) continue;
            p = p.trim();
            if (p.isEmpty()) continue;
            // Remove fake / product privacy tiles — never re-seed
            if (isProductCam(p) || isProductMic(p)) {
                continue;
            }
            if (isStockCam(p)) {
                if (!hasCam) {
                    list.add("cameratoggle");
                    hasCam = true;
                }
                continue;
            }
            if (isStockMic(p)) {
                if (!hasMic) {
                    list.add("mictoggle");
                    hasMic = true;
                }
                continue;
            }
            // Stock flashlight goes gray under camera privacy — product Torch tile.
            if (isStockFlashlight(p) || isProductTorch(p)) {
                if (!hasTorch) {
                    list.add(TORCH_TILE);
                    hasTorch = true;
                }
                continue;
            }
            list.add(p);
        }
        // Seed stock tiles near front (privacy first-class)
        if (!hasCam) {
            list.add(Math.min(3, list.size()), "cameratoggle");
        }
        if (!hasMic) {
            int at = Math.min(4, list.size());
            for (int i = 0; i < list.size(); i++) {
                if ("cameratoggle".equals(list.get(i))) {
                    at = i + 1;
                    break;
                }
            }
            list.add(at, "mictoggle");
        }
        if (!hasTorch) {
            int at = Math.min(5, list.size());
            for (int i = 0; i < list.size(); i++) {
                if ("mictoggle".equals(list.get(i))) {
                    at = i + 1;
                    break;
                }
            }
            list.add(at, TORCH_TILE);
        }
        String next = join(list);
        if (next.equals(cur)) return null;
        return next;
    }

    /** @deprecated use {@link #buildCleanList} */
    public static String buildReplacedList(Context ctx, String raw) {
        return buildCleanList(ctx, raw);
    }

    public static boolean tryStatusBarSetTiles(String list) {
        if (list == null || list.trim().isEmpty()) return false;
        Process p = null;
        try {
            p = new ProcessBuilder("cmd", "statusbar", "set-tiles", list)
                .redirectErrorStream(true)
                .start();
            boolean finished = p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (p != null) p.destroy();
        }
    }

    public static boolean ensureDefaultTiles(Context ctx) {
        if (ctx == null) return false;
        try {
            String raw = Settings.Secure.getString(ctx.getContentResolver(), SECURE_KEY);
            String next = buildCleanList(ctx, raw);
            if (next == null) {
                // Still ensure stock present when raw was null
                if (raw == null || raw.trim().isEmpty()) {
                    next = "internet,bt,location,cameratoggle,mictoggle,"
                            + TORCH_TILE + ",airplane,rotation";
                } else {
                    return looksGood(raw);
                }
            }
            boolean ok = Settings.Secure.putString(ctx.getContentResolver(), SECURE_KEY, next);
            if (!ok) {
                ok = tryStatusBarSetTiles(next);
            }
            if (ok) {
                Log.i(TAG, "QS tiles → cam/mic + product Torch (no stock flashlight)");
            }
            return ok && looksGood(Settings.Secure.getString(ctx.getContentResolver(), SECURE_KEY));
        } catch (Exception e) {
            Log.w(TAG, "ensureDefaultTiles: " + e.getMessage());
            return false;
        }
    }

    public static void ensureDefaultTilesWithRetries(Context ctx) {
        if (ctx == null) return;
        final Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        ensureDefaultTiles(app);
        if (retriesScheduled) return;
        retriesScheduled = true;
        for (long delay : RETRY_MS) {
            MAIN.postDelayed(() -> {
                try {
                    ensureDefaultTiles(app);
                } catch (Exception ignored) {}
            }, delay);
        }
    }

    private static boolean looksGood(String raw) {
        if (raw == null) return false;
        return raw.contains("cameratoggle")
            && raw.contains("mictoggle")
            && raw.contains("TorchTileService")
            && !raw.contains("CameraPrivacyTileService")
            && !raw.contains("MicPrivacyTileService")
            && !raw.contains("flashlight");
    }

    private static String join(java.util.List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(list.get(i));
        }
        return sb.toString();
    }

    private static boolean isStockCam(String p) {
        if ("cameratoggle".equals(p)) return true;
        return p != null && (p.endsWith(".cameratoggle") || p.contains("CameraToggleTile"));
    }

    private static boolean isStockMic(String p) {
        if ("mictoggle".equals(p)) return true;
        return p != null && (p.endsWith(".mictoggle") || p.contains("MicrophoneToggleTile"));
    }

    private static boolean isStockFlashlight(String p) {
        if ("flashlight".equals(p)) return true;
        return p != null && (p.endsWith(".flashlight") || p.contains("FlashlightTile"));
    }

    private static boolean isProductTorch(String p) {
        return p != null && p.contains("TorchTileService");
    }

    private static boolean isProductCam(String p) {
        return p != null && p.contains("CameraPrivacyTileService");
    }

    private static boolean isProductMic(String p) {
        return p != null && p.contains("MicPrivacyTileService");
    }
}
