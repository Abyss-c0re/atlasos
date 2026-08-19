package com.titanus2.api;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/**
 * Client of the Atlas privilege plane — same protocol as Debian
 * {@code atlas-auth request/exec}.
 *
 * <p>This is <b>not</b> a second auth host. Atlas {@code AuthPromptActivity}
 * + {@code AtlasSessionService} remain the only biometric UI. Tickets are
 * {@code ticket.<scope>} only. Capture/mutate never skip-bio.
 *
 * <p>LAW: {@link Titan2ApiContract#ATLAS_AUTH_ON_LP} (wipe-survives).
 */
public final class AtlasAuthPlane {
    private static final String TAG = "AtlasAuthPlane";
    public static final int DEFAULT_TIMEOUT_SEC = 25;

    private AtlasAuthPlane() {}

    public static final class Result {
        public final boolean ok;
        /** ticket | grant | observe | off | deny | timeout | setup */
        public final String via;
        public final String error;

        public Result(boolean ok, String via, String error) {
            this.ok = ok;
            this.via = via != null ? via : "";
            this.error = error != null ? error : "";
        }

        public static Result ok(String via) {
            return new Result(true, via, "");
        }

        public static Result fail(String via, String error) {
            return new Result(false, via, error);
        }
    }

    public static File authDir() {
        File lp = new File(Titan2ApiContract.ATLAS_AUTH_ON_LP);
        if (lp.isDirectory() || lp.getParentFile() != null
                && new File(Titan2ApiContract.ATLAS_LP_MNT).isDirectory()) {
            return lp;
        }
        File deb = new File(Titan2ApiContract.ATLAS_AUTH_IN_DEB);
        if (deb.isDirectory()) return deb;
        return lp;
    }

    public static boolean planeReady() {
        File d = authDir();
        return d.isDirectory() && d.canRead();
    }

    public static String sanitizeScope(String scope) {
        if (scope == null) return "ask";
        int slash = scope.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < scope.length()) scope = scope.substring(slash + 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < scope.length(); i++) {
            char ch = scope.charAt(i);
            if (ch >= 'A' && ch <= 'Z') ch = (char) (ch - 'A' + 'a');
            if ((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')
                    || ch == '-' || ch == '_') {
                sb.append(ch);
            }
        }
        return sb.length() == 0 ? "ask" : sb.toString();
    }

    public static boolean isObserve(String scope) {
        String s = sanitizeScope(scope);
        return "getprop".equals(s) || "dumpsys".equals(s) || "logcat".equals(s)
            || "status".equals(s) || "dump".equals(s);
    }

    /** Matches atlas_bridge_capture_name — never skip-bio. */
    public static boolean isCaptureOrMutate(String scope) {
        switch (sanitizeScope(scope)) {
            case "screencap":
            case "screenshot":
            case "input":
            case "am":
            case "pm":
            case "cmd":
            case "settings":
            case "setprop":
            case "wm":
            case "service":
            case "content":
            case "appops":
            case "nsenter":
            case "unshare":
            case "reboot":
            case "sm":
            case "bm":
            case "sudo":
            case "su":
            case "exec":
            case "adb":
            case "remoteadb":
            case "remote_adb":
                return true;
            default:
                return false;
        }
    }

    public static boolean hasValidTicket(String scope) {
        if (isCaptureOrMutate(scope)) return false;
        if (isStrict()) return false;
        if (ticketTtlSec() <= 0) return false;
        String sc = sanitizeScope(scope);
        if ("exec".equals(sc)) return false;
        return ticketFileValid(new File(authDir(), "ticket." + sc));
    }

    public static boolean isStrict() {
        return readPlaneInt("titan2_atlas_auth_strict", 0) == 1;
    }

    public static int ticketTtlSec() {
        if (isStrict()) return 0;
        int t = readPlaneInt("titan2_atlas_ticket_ttl", 60);
        if (t < 0) t = 0;
        if (t > 1800) t = 1800;
        return t;
    }

    /**
     * Same gate Debian uses: observe flows; capture/mutate asks Atlas.
     * Caller must honor {@code optionalOff} when the product pref is disabled.
     */
    public static Result request(Context c, String scope, String reason, String cmd,
                                 int timeoutSec) {
        String sc = sanitizeScope(scope);
        if (isObserve(sc)) return Result.ok("observe");
        if (hasValidTicket(sc)) return Result.ok("ticket");
        if (!planeReady()) {
            return Result.fail("setup",
                "atlas-auth plane missing — open Atlas or atlas-lpctl auth-ensure");
        }
        if (timeoutSec <= 0) timeoutSec = DEFAULT_TIMEOUT_SEC;
        if (reason == null || reason.isEmpty()) reason = "Nanobot privilege";
        Result bin = requestViaBinary(sc, reason, cmd, timeoutSec);
        if (bin != null) return bin;
        return requestViaFiles(c, sc, reason, cmd, timeoutSec);
    }

    private static Result requestViaBinary(String scope, String reason, String cmd,
                                           int timeoutSec) {
        File bin = new File("/system/bin/atlas-auth");
        if (!bin.isFile() || !bin.canExecute()) return null;
        try {
            java.util.ArrayList<String> argv = new java.util.ArrayList<>();
            argv.add(bin.getAbsolutePath());
            argv.add("request");
            argv.add("--scope");
            argv.add(scope);
            argv.add("-t");
            argv.add(String.valueOf(timeoutSec));
            argv.add(reason);
            ProcessBuilder pb = new ProcessBuilder(argv);
            pb.redirectErrorStream(true);
            pb.environment().put("ATLAS_AUTH_DIR", authDir().getAbsolutePath());
            Process p = pb.start();
            boolean done = p.waitFor(timeoutSec + 5L, java.util.concurrent.TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                return Result.fail("timeout", "atlas-auth timeout");
            }
            int ec = p.exitValue();
            if (ec == 0) return Result.ok("grant");
            if (ec == 1) return Result.fail("deny", "denied");
            if (ec == 3) return Result.fail("timeout", "atlas-auth timeout");
            return Result.fail("setup", "atlas-auth exit " + ec);
        } catch (Exception e) {
            Log.w(TAG, "atlas-auth exec: " + e.getMessage());
            return null;
        }
    }

    private static Result requestViaFiles(Context c, String scope, String reason,
                                          String cmd, int timeoutSec) {
        File dir = authDir();
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        String id = android.os.Process.myPid() + "-" + (System.currentTimeMillis() / 1000L);
        File req = new File(dir, "req." + id);
        File ok = new File(dir, "ok." + id);
        File fail = new File(dir, "fail." + id);
        File busy = new File(dir, "busy." + id);
        try (OutputStreamWriter w = new OutputStreamWriter(
                new FileOutputStream(req), StandardCharsets.UTF_8)) {
            w.write(reason + "\nscope=" + scope + "\n");
            if (cmd != null && !cmd.isEmpty()) w.write("cmd=" + cmd + "\n");
        } catch (Exception e) {
            return Result.fail("setup", "cannot write req: " + e.getMessage());
        }
        // 0666 so Atlas host can read reason (caller UID ≠ Atlas UID).
        //noinspection ResultOfMethodCallIgnored
        req.setReadable(true, false);
        //noinspection ResultOfMethodCallIgnored
        req.setWritable(true, false);
        nudgeWake(dir);
        launchAtlasPrompt(c, id, reason);
        long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (ok.isFile()) {
                //noinspection ResultOfMethodCallIgnored
                ok.delete();
                //noinspection ResultOfMethodCallIgnored
                fail.delete();
                //noinspection ResultOfMethodCallIgnored
                req.delete();
                //noinspection ResultOfMethodCallIgnored
                busy.delete();
                return Result.ok("grant");
            }
            if (fail.isFile()) {
                //noinspection ResultOfMethodCallIgnored
                fail.delete();
                //noinspection ResultOfMethodCallIgnored
                ok.delete();
                //noinspection ResultOfMethodCallIgnored
                req.delete();
                //noinspection ResultOfMethodCallIgnored
                busy.delete();
                return Result.fail("deny", "denied");
            }
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Result.fail("deny", "interrupted");
            }
        }
        //noinspection ResultOfMethodCallIgnored
        req.delete();
        //noinspection ResultOfMethodCallIgnored
        ok.delete();
        //noinspection ResultOfMethodCallIgnored
        fail.delete();
        //noinspection ResultOfMethodCallIgnored
        busy.delete();
        return Result.fail("timeout", "atlas-auth timeout — unlock / open Atlas");
    }

    private static void nudgeWake(File dir) {
        File wake = new File(dir, "wake");
        try (FileOutputStream o = new FileOutputStream(wake)) {
            o.write('1');
        } catch (Exception ignored) {}
    }

    /** Same exported prompt Debian uses (no second biometric religion). */
    private static void launchAtlasPrompt(Context c, String id, String reason) {
        if (c == null) return;
        try {
            Intent i = new Intent();
            i.setClassName(Titan2ApiContract.ATLAS_PKG, Titan2ApiContract.ATLAS_AUTH_PROMPT);
            i.setAction(Titan2ApiContract.ACTION_ATLAS_AUTH_PROMPT);
            i.putExtra("auth_id", id);
            i.putExtra("auth_reason", reason);
            i.putExtra("auth_source", "nanobot");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
            c.startActivity(i);
        } catch (Exception e) {
            Log.w(TAG, "AuthPrompt: " + e.getMessage());
        }
    }

    private static boolean ticketFileValid(File ticket) {
        if (ticket == null || !ticket.isFile()) return false;
        try {
            byte[] b = java.nio.file.Files.readAllBytes(ticket.toPath());
            String t = new String(b, StandardCharsets.UTF_8).trim();
            String[] p = t.split("\\s+");
            if (p.length < 2) return false;
            long exp = Long.parseLong(p[0]);
            int ttl = Integer.parseInt(p[1]);
            if (ttl <= 0) return false;
            long now = System.currentTimeMillis() / 1000L;
            return exp > now && exp <= now + ttl + 5;
        } catch (Exception e) {
            return false;
        }
    }

    private static int readPlaneInt(String name, int fallback) {
        String[] paths = {
            Titan2ApiContract.ATLAS_AUTH_ON_LP + "/" + name,
            Titan2ApiContract.ATLAS_AUTH_IN_DEB + "/" + name,
            "/data/misc/titan2/" + name,
            "/data/local/tmp/" + name,
        };
        for (String p : paths) {
            File f = new File(p);
            if (!f.isFile()) continue;
            try {
                byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
                String s = new String(b, StandardCharsets.UTF_8).trim();
                if (s.isEmpty()) continue;
                return Integer.parseInt(s.split("\\s+")[0]);
            } catch (Exception ignored) {}
        }
        return fallback;
    }
}
