package com.titanus2.api;

import android.content.Context;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;

/**
 * Best-effort read/write of the Titan2 OS control plane without Binder.
 * Used as fallback when {@link Titan2Client} is unbound, and by services.
 * Prefer the API service when available (temp layers, epoch bump, prefs).
 */
public final class ControlPlane {
    /**
     * After SELinux denies open/write on {@link Titan2ApiContract#OS_CTRL}, skip
     * that path for a while (rootless priv_app + system_data_file). Stops 4s
     * inject_pause / a11y heartbeats from flooding avc: denied audit.
     */
    private static volatile long osCtrlBackoffUntilMs;
    private static final long OS_CTRL_BACKOFF_MS = 60_000L;

    private ControlPlane() {}

    private static boolean osCtrlAllowed() {
        return System.currentTimeMillis() >= osCtrlBackoffUntilMs;
    }

    private static void noteOsCtrlDenied() {
        osCtrlBackoffUntilMs = System.currentTimeMillis() + OS_CTRL_BACKOFF_MS;
    }

    private static boolean isOsCtrlDir(File dir) {
        if (dir == null) return false;
        String p = dir.getPath();
        return p != null && p.equals(Titan2ApiContract.OS_CTRL);
    }

    public static boolean put(Context ctx, String name, String value) {
        if (value == null) value = "";
        byte[] data = value.getBytes(StandardCharsets.UTF_8);
        int ok = 0;
        // tmp first; OS plane only outside SELinux backoff
        java.util.ArrayList<File> dirs = new java.util.ArrayList<>(4);
        dirs.add(new File(Titan2ApiContract.TMP_CTRL));
        if (osCtrlAllowed()) {
            dirs.add(new File(Titan2ApiContract.OS_CTRL));
        }
        if (ctx != null) {
            dirs.add(ctx.getFilesDir());
            try {
                File ext = ctx.getExternalFilesDir(null);
                if (ext != null) dirs.add(ext);
            } catch (Exception ignored) {}
        }
        for (File dir : dirs) {
            if (dir == null) continue;
            try {
                if (!dir.exists()) //noinspection ResultOfMethodCallIgnored
                    dir.mkdirs();
                File f = new File(dir, name);
                // Skip rewrite when content matches (mtime thrash)
                if (f.isFile()) {
                    String cur = readFile(f);
                    if (value.equals(cur)) {
                        ok++;
                        continue;
                    }
                }
                try (FileOutputStream fos = new FileOutputStream(f)) {
                    fos.write(data);
                }
                // Skip world chmod on shared tmp/OS — setattr often SELinux-denied
                String dp = dir.getPath();
                if (dp != null && !dp.equals(Titan2ApiContract.OS_CTRL)
                        && !dp.equals(Titan2ApiContract.TMP_CTRL)) {
                    //noinspection ResultOfMethodCallIgnored
                    f.setReadable(true, false);
                    //noinspection ResultOfMethodCallIgnored
                    f.setWritable(true, false);
                }
                ok++;
            } catch (Exception e) {
                if (isOsCtrlDir(dir)) noteOsCtrlDenied();
            }
        }
        return ok > 0;
    }

    public static String get(Context ctx, String name, String def) {
        long bestMt = -1;
        String best = null;
        java.util.ArrayList<File> shared = new java.util.ArrayList<>(2);
        shared.add(new File(Titan2ApiContract.TMP_CTRL, name));
        if (osCtrlAllowed()) {
            shared.add(new File(Titan2ApiContract.OS_CTRL, name));
        }
        for (File f : shared) {
            if (!f.isFile()) continue;
            String v = readFile(f);
            if (v == null || v.isEmpty()) {
                if (f.getPath() != null
                        && f.getPath().startsWith(Titan2ApiContract.OS_CTRL)) {
                    noteOsCtrlDenied();
                }
                continue;
            }
            long mt = f.lastModified();
            if (mt >= bestMt) {
                bestMt = mt;
                best = v;
            }
        }
        if (ctx != null) {
            File[] priv = new File[]{
                new File(ctx.getFilesDir(), name),
                null
            };
            try {
                File ext = ctx.getExternalFilesDir(null);
                if (ext != null) priv[1] = new File(ext, name);
            } catch (Exception ignored) {}
            for (File f : priv) {
                if (f == null || !f.isFile()) continue;
                String v = readFile(f);
                if (v == null || v.isEmpty()) continue;
                long mt = f.lastModified();
                if (mt > bestMt) {
                    bestMt = mt;
                    best = v;
                }
            }
        }
        return best != null ? best : def;
    }

    public static long getLong(Context ctx, String name, long def) {
        String v = get(ctx, name, null);
        if (v == null || v.isEmpty()) return def;
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static long bumpEpoch(Context ctx) {
        long cur = getLong(ctx, Titan2ApiContract.FILE_PAD_EPOCH, 0);
        long next = cur + 1;
        if (next < 1) next = 1;
        put(ctx, Titan2ApiContract.FILE_PAD_EPOCH, String.valueOf(next));
        // Signal hid_bridge to re-open mouse immediately
        put(ctx, Titan2ApiContract.FILE_PAD_REGRAB, "1");
        return next;
    }

    public static void requestRegrab(Context ctx) {
        put(ctx, Titan2ApiContract.FILE_PAD_REGRAB, "1");
    }

    private static String readFile(File f) {
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line = br.readLine();
            return line != null ? line.trim() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
