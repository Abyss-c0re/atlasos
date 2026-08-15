package com.titanus2.atlas;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/**
 * Atlas <b>Authentication Agent</b> (host side).
 *
 * <p>This is not a PATH hijack of KernelSU. It is the privilege auth plane used by:
 * <ul>
 *   <li>Android elevate client ({@code sudo} → agent → absolute {@code /system/bin/su})</li>
 *   <li>Debian real sudo via {@code SUDO_ASKPASS=atlas-auth-askpass}</li>
 *   <li>Hybrid pam_exec / apt (same agent protocol)</li>
 * </ul>
 *
 * Protocol under <b>super LP auth plane</b> (survives userdata wipe):
 * {@link NativeBin#AUTH_ON_LP} · in Deb {@link NativeBin#AUTH_IN_DEB}
 * <pre>
 *   req.&lt;id&gt;     client request (reason text)
 *   busy.&lt;id&gt;    claimed by agent
 *   ok.&lt;id&gt; / fail.&lt;id&gt;  result
 *   ticket        short-lived grant (epoch expiry seconds) after success
 *   wake          nudge FileObserver
 * </pre>
 * LAW: never app CE {@code files/auth}; never {@code /data/local/tmp}.
 *
 * Agent runtime: {@link AtlasSessionService} (+ AuthWatch) polls and shows biometrics.
 */
public final class AtlasAuth {
    private static final String TAG = "AtlasAuth";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** Ticket lifetime after a successful biometric (seconds). 90s killed Deb IPC. */
    public static final int TICKET_TTL_SEC = 1800;

    private AtlasAuth() {}

    /**
     * Product auth dir on super LP. App points here; Deb uses same blocks via
     * {@code ATLAS_AUTH_DIR} / {@code /var/lib/atlas-auth} bind.
     */
    public static File authDir(Context c) {
        NativeBin.ensureAuthPlaneOnLp(c);
        File d = NativeBin.authDirLp();
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        //noinspection ResultOfMethodCallIgnored
        d.setReadable(true, false);
        //noinspection ResultOfMethodCallIgnored
        d.setWritable(true, false);
        //noinspection ResultOfMethodCallIgnored
        d.setExecutable(true, false);
        return d;
    }

    public static void clearStaleRequests(Context c) {
        File dir = authDir(c);
        File[] files = dir.listFiles();
        if (files == null) return;
        long now = System.currentTimeMillis();
        for (File f : files) {
            String n = f.getName();
            if (n == null) continue;
            if (n.startsWith("ok.") || n.startsWith("fail.") || n.startsWith("busy.")) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                continue;
            }
            if (n.startsWith("req.") && now - f.lastModified() > 2000L) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    /** Agent poll: service loop. */
    public static void pollOnce(Context c) {
        File dir = authDir(c);
        File[] files = dir.listFiles();
        if (files == null) return;
        final boolean biometricsOn = AtlasPrefs.biometricAuth(c);
        for (File f : files) {
            String name = f.getName();
            if (!name.startsWith("req.")) continue;
            String id = name.substring(4);
            if (id.isEmpty()) continue;
            File ok = new File(dir, "ok." + id);
            File fail = new File(dir, "fail." + id);
            if (ok.exists() || fail.exists()) continue;
            if (System.currentTimeMillis() - f.lastModified() < 300L) continue;
            File claimed = new File(dir, "busy." + id);
            if (!f.renameTo(claimed)) continue;
            if (!biometricsOn) {
                writeResult(c, id, true);
                continue;
            }
            // Valid agent ticket → grant without another finger (same TTL window)
            if (hasValidTicket(c)) {
                writeResult(c, id, true);
                continue;
            }
            String reason = readText(claimed);
            if (reason == null || reason.isEmpty()) reason = "Atlas privilege";
            launchAuthUi(c, id, reason.trim());
        }
    }

    public static boolean hasValidTicket(Context c) {
        File ticket = new File(authDir(c), "ticket");
        if (!ticket.isFile()) return false;
        String t = readText(ticket);
        if (t == null) return false;
        t = t.trim();
        try {
            long exp = Long.parseLong(t.split("\\s+")[0]);
            return exp > System.currentTimeMillis() / 1000L;
        } catch (Exception e) {
            return false;
        }
    }

    public static void writeTicket(Context c, int ttlSec) {
        if (ttlSec <= 0) ttlSec = TICKET_TTL_SEC;
        long exp = System.currentTimeMillis() / 1000L + ttlSec;
        File ticket = new File(authDir(c), "ticket");
        try (OutputStreamWriter w = new OutputStreamWriter(
                new FileOutputStream(ticket), StandardCharsets.UTF_8)) {
            w.write(exp + " " + ttlSec + "\n");
        } catch (Exception e) {
            Log.w(TAG, "ticket write", e);
        }
        //noinspection ResultOfMethodCallIgnored
        ticket.setReadable(true, false);
        /* Mirror for enterd / shell / Deb chroot when CE path is SELinux-denied. */
        try {
            File mir = new File("/data/local/tmp/atlas_auth.ticket");
            try (OutputStreamWriter w = new OutputStreamWriter(
                    new FileOutputStream(mir), StandardCharsets.UTF_8)) {
                w.write(exp + " " + ttlSec + "\n");
            }
            //noinspection ResultOfMethodCallIgnored
            mir.setReadable(true, false);
        } catch (Exception e) {
            Log.w(TAG, "ticket mirror", e);
        }
    }

    public static void clearTicket(Context c) {
        //noinspection ResultOfMethodCallIgnored
        new File(authDir(c), "ticket").delete();
    }

    private static void launchAuthUi(Context c, String id, String reason) {
        Intent i = new Intent(c, AuthPromptActivity.class);
        // NEW_TASK only — no CLEAR_TOP (that thrashed MainActivity / reloaded Deb
        // shell after apt biometrics). MULTIPLE_TASK keeps auth off the term task.
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
            | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
            | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        i.putExtra(AuthPromptActivity.EXTRA_ID, id);
        i.putExtra(AuthPromptActivity.EXTRA_REASON, reason);
        try {
            AtlasPrefs.markAuthUi(c, true);
            c.startActivity(i);
        } catch (Exception e) {
            Log.w(TAG, "auth ui failed", e);
            AtlasPrefs.markAuthUi(c, false);
            writeResult(c, id, false);
        }
    }

    public static void writeResult(Context c, String id, boolean grant) {
        File dir = authDir(c);
        //noinspection ResultOfMethodCallIgnored
        dir.setReadable(true, false);
        //noinspection ResultOfMethodCallIgnored
        dir.setExecutable(true, false);
        File target = new File(dir, (grant ? "ok." : "fail.") + id);
        try (OutputStreamWriter w = new OutputStreamWriter(
                new FileOutputStream(target), StandardCharsets.UTF_8)) {
            w.write(grant ? "ok\n" : "fail\n");
        } catch (Exception e) {
            Log.w(TAG, "write result", e);
        }
        //noinspection ResultOfMethodCallIgnored
        target.setReadable(true, false);
        if (grant) {
            writeTicket(c, TICKET_TTL_SEC);
        }
        //noinspection ResultOfMethodCallIgnored
        new File(dir, "busy." + id).delete();
        //noinspection ResultOfMethodCallIgnored
        new File(dir, "req." + id).delete();
        //noinspection ResultOfMethodCallIgnored
        new File(dir, "wake").delete();
    }

    /**
     * Blocking host-side grant (settings wipe / enable priv).
     */
    public static boolean requestBlocking(Context c, String reason, int timeoutSec) {
        if (!AtlasPrefs.biometricAuth(c)) return true;
        if (hasValidTicket(c)) return true;
        if (!(c instanceof Activity)) {
            // FGS path: drop a req and wait
            return requestBlockingViaFiles(c, reason, timeoutSec);
        }
        // Activity path still uses file protocol so CLI and UI share agent
        return requestBlockingViaFiles(c, reason, timeoutSec);
    }

    private static boolean requestBlockingViaFiles(Context c, String reason, int timeoutSec) {
        // Use native atlas-auth if present for consistency
        File authBin = new File(NativeBin.binDir(c), "atlas-auth");
        if (authBin.isFile()) {
            try {
                Process p = new ProcessBuilder(
                    authBin.getAbsolutePath(), "request", "-t",
                    String.valueOf(Math.max(15, timeoutSec)),
                    reason != null ? reason : "Atlas privilege")
                    .redirectErrorStream(true)
                    .start();
                boolean done = p.waitFor(timeoutSec + 5L, java.util.concurrent.TimeUnit.SECONDS);
                if (!done) {
                    p.destroyForcibly();
                    return false;
                }
                return p.exitValue() == 0;
            } catch (Exception e) {
                Log.w(TAG, "atlas-auth exec", e);
            }
        }
        return false;
    }

    private static String readText(File f) {
        try {
            byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
            return new String(b, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
