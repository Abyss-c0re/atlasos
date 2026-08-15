package com.titanus2.atlas;

import android.content.Context;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * PTY-backed atlas session.
 * Starts via atlas-net.sh so static Linux ELFs get DNS on GSI —
 * not a per-tool wrapper; whole terminal session is network-ready.
 */
public final class ShellSession {
    public interface Listener {
        void onOutput(String chunk);
        void onExit(int code);
    }

    private final Context app;
    private final Listener listener;
    private final ExecutorService io = Executors.newCachedThreadPool();
    private final AtomicBoolean alive = new AtomicBoolean(false);
    private Process proc;
    private OutputStream stdin;

    public ShellSession(Context c, Listener l) {
        this.app = c.getApplicationContext();
        this.listener = l;
    }

    public synchronized void start() throws Exception {
        stop();
        NativeBin.ensureExtracted(app);
        NativeBin.linkUserToolsIntoBin(app);
        NativeBin.ensureShellProfile(app);
        NativeBin.dropGrokOsHooks(app);
        NativeBin.stageCaBundle(app);

        File home = NativeBin.home(app);
        File bin = NativeBin.binDir(app);
        String path = NativeBin.pathEnv(app);
        File net = new File(bin, "atlas-net.sh");
        File atlas = NativeBin.atlas(app);
        File ptyexec = new File(bin, "ptyexec");
        File ca = new File(home, "cacert.pem");

        List<String> cmd = new ArrayList<>();
        if (net.isFile()) {
            cmd.add("/system/bin/sh");
            cmd.add(net.getAbsolutePath());
        } else {
            if (ptyexec.isFile() && ptyexec.canExecute()) {
                cmd.add(ptyexec.getAbsolutePath());
            }
            cmd.add(atlas.getAbsolutePath());
            cmd.add("-i");
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(home);
        pb.redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.put("HOME", home.getAbsolutePath());
        env.put("ATLAS_HOME", home.getAbsolutePath());
        env.put("ATLAS_BIN", bin.getAbsolutePath());
        env.put("PATH", path);
        env.put("TERM", "xterm-256color");
        env.put("LANG", "C.UTF-8");
        env.put("BASH_ENV", new File(home, ".bash_env").getAbsolutePath());
        env.put("ENV", new File(home, ".profile").getAbsolutePath());
        if (ca.isFile()) {
            env.put("SSL_CERT_FILE", ca.getAbsolutePath());
        }
        File apexCa = new File("/apex/com.android.conscrypt/cacerts");
        if (apexCa.isDirectory()) {
            env.put("SSL_CERT_DIR", apexCa.getAbsolutePath());
        }

        proc = pb.start();
        alive.set(true);
        stdin = proc.getOutputStream();

        final Process p = proc;
        io.execute(() -> {
            try {
                InputStream in = p.getInputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    if (n == 0) continue;
                    listener.onOutput(new String(buf, 0, n, StandardCharsets.UTF_8));
                }
            } catch (Exception e) {
                listener.onOutput("\r\n[atlas io] " + e.getMessage() + "\r\n");
            } finally {
                alive.set(false);
                int code = -1;
                try {
                    code = p.waitFor();
                } catch (InterruptedException ignored) {
                }
                listener.onExit(code);
            }
        });
    }

    public synchronized void writeLine(String line) {
        if (line == null) return;
        String s = line;
        if (s.endsWith("\r\n")) {
            /* ok */
        } else if (s.endsWith("\n")) {
            /* ok */
        } else if (s.endsWith("\r")) {
            s = s.substring(0, s.length() - 1) + "\n";
        } else {
            s = s + "\n";
        }
        writeRaw(s);
    }

    public synchronized void writeRaw(String data) {
        if (!alive.get() || stdin == null || data == null || data.isEmpty()) return;
        try {
            byte[] b = data.getBytes(StandardCharsets.UTF_8);
            stdin.write(b);
            stdin.flush();
        } catch (Exception e) {
            listener.onOutput("\r\n[atlas write] " + e.getMessage() + "\r\n");
        }
    }

    public synchronized void stop() {
        alive.set(false);
        try {
            if (stdin != null) stdin.close();
        } catch (Exception ignored) {
        }
        stdin = null;
        if (proc != null) {
            proc.destroy();
            try {
                proc.waitFor();
            } catch (Exception ignored) {
            }
            proc = null;
        }
    }

    public boolean isAlive() {
        return alive.get();
    }
}
