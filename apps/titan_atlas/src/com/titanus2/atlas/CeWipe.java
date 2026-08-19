package com.titanus2.atlas;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Atlas Clear data must drop privilege identity. Debian rootfs on LP may stay
 * (that is the OS image). Tickets, logs, and the Linux HOME auth tree must not.
 *
 * <p>Factory reset deletes {@code /data/local/tmp} but keeps LP auth — that
 * path is still wipe-survive LAW. App wipe leaves tmp; tmp marker present
 * means Clear data, not factory reset.
 */
public final class CeWipe {
    private static final String TAG = "AtlasCeWipe";
    private static final String CE_MARK = "ce_alive";
    private static final String TMP_MARK = "/data/local/tmp/titan2_atlas_ce_alive";

    private CeWipe() {}

    /**
     * @return true if this start is an app Clear-data (identity shredded)
     */
    public static boolean reconcile(Context c) {
        if (c == null) return false;
        File ce = new File(c.getFilesDir(), CE_MARK);
        File tmp = new File(TMP_MARK);
        File lpBind = new File(NativeBin.authDirLp(), "ce_bind");
        String gen = installGen(c);
        if (ce.isFile()) {
            if (!tmp.isFile()) writeText(tmp, gen);
            if (!lpBind.isFile()) writeText(lpBind, gen);
            return false;
        }
        // CE mark gone. Same install generation on LP/tmp → Clear data, not factory reset.
        boolean appWipe = gen.equals(readText(tmp)) || gen.equals(readText(lpBind));
        if (appWipe) {
            Log.w(TAG, "CE empty + same install gen — Atlas Clear data; shred identity + user CLIs");
            shredAuthIdentity();
            shredUserExtras();
        }
        writeText(ce, gen);
        writeText(tmp, gen);
        writeText(lpBind, gen);
        return appWipe;
    }

    private static String installGen(Context c) {
        try {
            android.content.pm.PackageInfo pi =
                c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return pi.firstInstallTime + ":" + pi.lastUpdateTime + "\n";
        } catch (Exception e) {
            return "unknown\n";
        }
    }

    private static String readText(File f) {
        if (f == null || !f.isFile()) return "";
        try {
            byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
            return new String(b, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static void writeText(File mark, String body) {
        try {
            File p = mark.getParentFile();
            if (p != null) //noinspection ResultOfMethodCallIgnored
                p.mkdirs();
            try (FileOutputStream o = new FileOutputStream(mark)) {
                o.write((body != null ? body : "v1\n").getBytes(StandardCharsets.UTF_8));
            }
            //noinspection ResultOfMethodCallIgnored
            mark.setReadable(true, false);
            //noinspection ResultOfMethodCallIgnored
            mark.setWritable(true, false);
        } catch (Exception e) {
            Log.w(TAG, "mark " + mark + ": " + e.getMessage());
        }
    }

    /** Tickets, request log, HOME auth — not Debian root, not LP itself. */
    public static void shredAuthIdentity() {
        shredAuthDir(NativeBin.authDirLp());
        shredAuthDir(new File(NativeBin.AUTH_IN_DEB));
        deleteTree(new File(NativeBin.LINUX_HOME, "auth"));
        deleteQuiet(new File("/data/local/tmp/atlas_auth.ticket"));
        deleteQuiet(new File("/data/local/tmp/titan2_remote_adb_grant"));
    }

    /**
     * Grok and other user CLIs live under LINUX_HOME (bind-mounted as Deb
     * {@code /home/atlas}). That tree survives Atlas Clear data — leftover
     * {@code ~/.grok} after wipe is heresy. Atlas stack is {@code atlas-*},
     * not Grok. User may reinstall Grok after a wipe if they want it.
     */
    public static void shredUserExtras() {
        File home = new File(NativeBin.LINUX_HOME);
        shredGrokTree(home);
        shredGrokTree(new File("/data/local/atlas-linux/home/atlas"));
        shredGrokTree(new File("/home/atlas"));
        Log.i(TAG, "shredded user extras (grok) under linux home");
    }

    private static void shredGrokTree(File home) {
        if (home == null || !home.isDirectory()) return;
        deleteTree(new File(home, ".grok"));
        deleteQuiet(new File(home, "bin/grok"));
        deleteQuiet(new File(home, ".local/bin/grok"));
        deleteQuiet(new File(home, ".config/fish/completions/grok.fish"));
    }

    private static void shredAuthDir(File dir) {
        if (dir == null || !dir.isDirectory()) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            String n = f.getName();
            if (n == null) continue;
            if (n.equals("ticket") || n.startsWith("ticket.")
                    || n.startsWith("req.") || n.startsWith("ok.")
                    || n.startsWith("fail.") || n.startsWith("busy.")
                    || n.equals("wake") || n.startsWith("log.jsonl")) {
                deleteQuiet(f);
            }
        }
        Log.i(TAG, "shredded tickets under " + dir.getAbsolutePath());
    }

    private static void deleteQuiet(File f) {
        if (f != null) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) deleteTree(k);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
