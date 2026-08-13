package com.titanus2.atlas;

import android.content.Context;
import android.content.res.AssetManager;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.OutputStream;

/** Extract bundled aarch64 tools + seed PATH/profile so tools Just Work. */
public final class NativeBin {
    private NativeBin() {}

    /**
     * LAW (every Atlas change): privilege <b>auth plane lives on super LP</b>
     * {@code atlas_linux} so it <b>survives userdata wipe</b>.
     * Never app CE {@code files/auth}. Never {@code /data/local/tmp}.
     * <p>
     * Android path (app + enterd): {@link #AUTH_ON_LP}<br>
     * Inside Deb (LP root / bind): {@link #AUTH_IN_DEB}
     */
    public static final String LP_MNT = "/data/local/atlas-linux";
    public static final String AUTH_ON_LP = LP_MNT + "/var/lib/atlas-auth";
    public static final String AUTH_IN_DEB = "/var/lib/atlas-auth";
    /** Linux HOME on Android data — wiped with factory reset (not auth). */
    public static final String LINUX_HOME = "/data/local/atlas-home/atlas";

    /**
     * Canonical app files dir. Prefer {@code /data/data/.../files} over
     * {@code /data/user/0/.../files} — they are often the same inode after bind,
     * but grok keys sessions by cwd string; mixing the two splits /resume history.
     */
    public static File home(Context c) {
        File f = c.getFilesDir();
        String p = f != null ? f.getAbsolutePath() : "";
        if (p.startsWith("/data/user/")) {
            File canon = new File("/data/data/com.titanus2.atlas/files");
            if (canon.isDirectory()) return canon;
        }
        return f;
    }

    /** Product auth directory on super LP (survives wipe). */
    public static File authDirLp() {
        return new File(AUTH_ON_LP);
    }

    /**
     * Ensure LP is mounted and auth dir exists (best-effort). Root path uses
     * {@code atlas-lpctl}; app UID may only chmod/mkdir if already mounted 0777.
     */
    public static void ensureAuthPlaneOnLp(Context c) {
        File auth = authDirLp();
        if (auth.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            auth.setReadable(true, false);
            //noinspection ResultOfMethodCallIgnored
            auth.setWritable(true, false);
            //noinspection ResultOfMethodCallIgnored
            auth.setExecutable(true, false);
            return;
        }
        // Try mkdir if LP already mounted and writable
        File parent = auth.getParentFile();
        if (parent != null) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        //noinspection ResultOfMethodCallIgnored
        auth.mkdirs();
        if (auth.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            auth.setReadable(true, false);
            //noinspection ResultOfMethodCallIgnored
            auth.setWritable(true, false);
            //noinspection ResultOfMethodCallIgnored
            auth.setExecutable(true, false);
            return;
        }
        // Root/init: atlas-lpctl mount + auth-ensure (enterd / hybrid-boot)
        final String[] cmds = {
            "/system/bin/atlas-lpctl auth-ensure",
            "/system/bin/atlas-lpctl mount",
        };
        for (String line : cmds) {
            try {
                Process p = new ProcessBuilder("sh", "-c", line)
                    .redirectErrorStream(true).start();
                p.waitFor(8, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
            if (auth.isDirectory()) return;
        }
        // hybrid script may mount LP
        try {
            Process p = new ProcessBuilder(
                "/system/bin/sh", "/system/bin/atlas-hybrid.sh", "mount")
                .redirectErrorStream(true).start();
            p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
        try {
            Process p = new ProcessBuilder(
                "/system/bin/atlas-lpctl", "auth-ensure")
                .redirectErrorStream(true).start();
            p.waitFor(8, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
    }

    public static File binDir(Context c) {
        File d = new File(home(c), "bin");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    /**
     * Product: force {@code libatlaspty.so} / {@code libatlasterm.so} onto app-private
     * dirs so priv-app installs still load without root / without system lib extract.
     * Prefer {@link Context#getDir(String, int)} (app-native lib dir) over files/bin
     * for SELinux mmap/exec friendliness.
     */
    public static void ensureNativeLibs(Context c) {
        if (c == null) return;
        File[] dirs = {
            c.getDir("lib", Context.MODE_PRIVATE),
            binDir(c),
            new File(c.getCodeCacheDir(), "lib"),
        };
        for (File dir : dirs) {
            if (dir == null) continue;
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            for (String so : new String[] { "libatlaspty.so", "libatlasterm.so" }) {
                File out = new File(dir, so);
                if (out.isFile() && out.length() > 1000) {
                    //noinspection ResultOfMethodCallIgnored
                    out.setReadable(true, false);
                    //noinspection ResultOfMethodCallIgnored
                    out.setExecutable(true, false);
                    continue;
                }
                extractAssetAtomic(c, "bin/" + so, out);
            }
        }
    }

    /**
     * Absolute path load of atlaspty. Returns true if JNI can use PTY.
     * Order: app-private lib dirs (no root) → system priv-app lib → loadLibrary.
     */
    public static boolean loadAtlasPty(Context c) {
        if (c == null) return false;
        ensureNativeLibs(c);
        java.util.ArrayList<String> paths = new java.util.ArrayList<>();
        File appLib = c.getDir("lib", Context.MODE_PRIVATE);
        if (appLib != null) paths.add(new File(appLib, "libatlaspty.so").getAbsolutePath());
        File codeLib = new File(c.getCodeCacheDir(), "lib");
        paths.add(new File(codeLib, "libatlaspty.so").getAbsolutePath());
        paths.add(new File(binDir(c), "libatlaspty.so").getAbsolutePath());
        paths.add("/data/data/com.titanus2.atlas/files/bin/libatlaspty.so");
        paths.add("/data/user/0/com.titanus2.atlas/files/bin/libatlaspty.so");
        paths.add("/system/priv-app/TitanAtlas/lib/arm64/libatlaspty.so");
        paths.add("/system/priv-app/TitanAtlas/lib/arm64-v8a/libatlaspty.so");
        if (c.getApplicationInfo() != null) {
            String nld = c.getApplicationInfo().nativeLibraryDir;
            if (nld != null) paths.add(0, nld + "/libatlaspty.so");
            String src = c.getApplicationInfo().sourceDir;
            if (src != null) {
                File parent = new File(src).getParentFile();
                if (parent != null) {
                    paths.add(new File(parent, "lib/arm64/libatlaspty.so").getAbsolutePath());
                    paths.add(new File(parent, "lib/arm64-v8a/libatlaspty.so").getAbsolutePath());
                }
            }
        }
        for (String p : paths) {
            if (p == null) continue;
            File f = new File(p);
            if (!f.isFile() || f.length() < 1000) continue;
            try {
                System.load(f.getAbsolutePath());
                return true;
            } catch (UnsatisfiedLinkError ignored) {
            }
        }
        try {
            System.loadLibrary("atlaspty");
            return true;
        } catch (UnsatisfiedLinkError ignored) {
            return false;
        }
    }

    public static File grokBin(Context c) {
        File d = new File(home(c), ".grok/bin");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    public static File atlas(Context c) {
        return new File(binDir(c), "atlas");
    }

    /**
     * PATH for Atlas session (hybrid OS).
     * User-install dirs under <b>both</b> CE home and product linux home, then
     * Debian bins, then ROM system bins. Never hardcode a single tool name —
     * any {@code $HOME/.<vendor>/bin} (and bin/.local/bin) is included when present.
     * Base shell/auth/sudo must live under /system* — priv_app cannot
     * execute_no_trans on privapp_data_file (app files/bin ELFs).
     */
    public static String pathEnv(Context c) {
        StringBuilder sb = new StringBuilder(512);
        // ATLAS_BIN overlay (app) — scripts only; ELFs may be SELinux-denied
        appendPath(sb, binDir(c).getAbsolutePath());
        appendUserInstallPaths(sb, home(c));
        appendUserInstallPaths(sb, new File(LINUX_HOME));
        // Debian + ROM
        for (String p : new String[] {
            "/usr/local/sbin", "/usr/local/bin", "/usr/sbin", "/usr/bin", "/sbin", "/bin",
            "/atlas-bin",
            "/system/bin", "/system_ext/bin", "/product/bin", "/system/xbin", "/vendor/bin"
        }) {
            appendPath(sb, p);
        }
        return sb.toString();
    }

    /** Append dir to PATH builder if non-empty. */
    private static void appendPath(StringBuilder sb, String dir) {
        if (dir == null || dir.isEmpty()) return;
        if (sb.length() > 0) sb.append(':');
        sb.append(dir);
    }

    /**
     * Universal install surface: {@code $h/bin}, {@code $h/.local/bin}, and every
     * existing {@code $h/.<name>/bin}. No tool-specific names.
     */
    private static void appendUserInstallPaths(StringBuilder sb, File h) {
        if (h == null) return;
        File bin = new File(h, "bin");
        if (bin.isDirectory()) appendPath(sb, bin.getAbsolutePath());
        File local = new File(h, ".local/bin");
        if (local.isDirectory()) appendPath(sb, local.getAbsolutePath());
        File[] kids = h.listFiles();
        if (kids == null) return;
        for (File kid : kids) {
            if (!kid.isDirectory()) continue;
            String n = kid.getName();
            if (n.length() < 2 || n.charAt(0) != '.') continue;
            if (".local".equals(n) || ".".equals(n) || "..".equals(n)) continue;
            File b = new File(kid, "bin");
            if (b.isDirectory()) appendPath(sb, b.getAbsolutePath());
        }
    }

    /**
     * ROM bash — system image only. Never prefer app-private static bash
     * (SELinux execute_no_trans denied on privapp_data_file).
     */
    public static File systemBash() {
        for (String p : new String[] {
            "/system/bin/bash",
            "/system_ext/bin/bash",
            "/product/bin/bash",
            "/vendor/bin/bash"
        }) {
            File f = new File(p);
            if (f.isFile() && f.canExecute()) return f;
        }
        return null;
    }

    /** Ensure standard user-install dirs exist (curl -o ~/.local/bin/…). */
    public static void ensureUserInstallDirs(Context c) {
        File home = home(c);
        for (String rel : new String[] {
            "bin", ".local/bin", ".cargo/bin", ".npm-global/bin", ".grok/bin", ".grok/downloads"
        }) {
            File d = new File(home, rel);
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
    }

    /** True if session can start (core PTY + entry scripts present). */
    public static boolean hasCoreBins(Context c) {
        // Product path: ROM system inject is enough after wipe. App files/bin extract
        // is best-effort overlay — never "bin fail" when /system/bin is complete.
        File netSys = new File("/system/bin/atlas-net.sh");
        File atlasSys = new File("/system/bin/atlas");
        File enterSys = new File("/system/bin/atlas-enter");
        if ((netSys.isFile() && netSys.length() > 100)
            || (atlasSys.isFile() && atlasSys.length() > 1000)
            || (enterSys.isFile() && enterSys.length() > 1000)) {
            return true;
        }
        File bin = binDir(c);
        File atlas = new File(bin, "atlas");
        File net = new File(bin, "atlas-net.sh");
        File pty = new File(bin, "libatlaspty.so");
        return (atlas.isFile() && atlas.length() > 1000)
            || (net.isFile() && net.length() > 100)
            || (pty.isFile() && pty.length() > 1000);
    }

    /**
     * Preferred atlas-net entry: <b>system inject first</b> (product rootless,
     * correct SELinux). App files/bin overlay only as fallback — never prefer
     * privapp_data_file for the session entry (exit 126 after reboot residual).
     */
    public static File atlasNetScript(Context c) {
        File netSys = new File("/system/bin/atlas-net.sh");
        if (netSys.isFile() && netSys.length() > 50) return netSys;
        if (c != null) {
            File netUser = new File(binDir(c), "atlas-net.sh");
            if (netUser.isFile() && netUser.length() > 50) return netUser;
        }
        return null;
    }

    private static boolean hasLinuxHomeFix(File f) {
        try {
            byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
            String s = new String(b, 0, Math.min(b.length, 12000),
                java.nio.charset.StandardCharsets.UTF_8);
            return s.contains("atlas-home/atlas") || s.contains("ATLAS_LINUX_HOME");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Atomic extract: write temp then rename. Never leave 0-byte targets
     * (empty atlas-screencap / sudo was black-terminal / ENOENT spam).
     */
    private static boolean extractAssetAtomic(Context c, String assetRel, File out) {
        if (out == null || assetRel == null) return false;
        File parent = out.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        if (parent == null || !parent.isDirectory()) return false;
        File tmp = new File(parent, out.getName() + ".extracting." + android.os.Process.myPid());
        try {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            try (InputStream in = c.getAssets().open(assetRel);
                    OutputStream os = new FileOutputStream(tmp)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            }
            if (!tmp.isFile() || tmp.length() <= 0) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                return false;
            }
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            if (!tmp.renameTo(out)) {
                copyFile(tmp, out);
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
            //noinspection ResultOfMethodCallIgnored
            out.setReadable(true, false);
            //noinspection ResultOfMethodCallIgnored
            out.setExecutable(true, false);
            return out.isFile() && out.length() > 0;
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        }
    }

    /**
     * Extract all assets under bin/ (idempotent; refreshes if APK newer).
     * Never hard-fails if core tools already on disk — tip pushes / partial APKs OK.
     */
    public static void ensureExtracted(Context c) throws Exception {
        File destRoot = binDir(c);
        //noinspection ResultOfMethodCallIgnored
        destRoot.mkdirs();
        AssetManager am = c.getAssets();
        String[] names = null;
        try {
            names = am.list("bin");
        } catch (Exception ignored) {
        }
        long apk = new File(c.getApplicationInfo().sourceDir).lastModified();
        int extracted = 0;
        Exception last = null;
        if (names != null) {
            for (String name : names) {
                if (name == null || name.isEmpty()) continue;
                // skip junk / dirs
                if (name.endsWith("/")) continue;
                File out = new File(destRoot, name);
                // never clobber live symlinks (grok/agent → ELF)
                try {
                    if (java.nio.file.Files.isSymbolicLink(out.toPath())) continue;
                } catch (Exception ignored) {
                }
                if (out.exists() && !out.isFile()) continue;
                // Always re-extract 0-byte corpses (failed prior extract)
                if (out.isFile() && out.length() > 0 && out.lastModified() >= apk) {
                    //noinspection ResultOfMethodCallIgnored
                    out.setExecutable(true, false);
                    extracted++;
                    continue;
                }
                if (extractAssetAtomic(c, "bin/" + name, out)) {
                    extracted++;
                } else {
                    last = new IllegalStateException("extract open failed: " + out.getAbsolutePath());
                }
            }
        }
        if (extracted == 0 && !hasCoreBins(c)) {
            throw new IllegalStateException(
                last != null ? last.getMessage() : "assets/bin empty");
        }
        try {
            installPrivilegeWrappers(c);
        } catch (Exception ignored) {
        }
        try {
            ensureShellProfile(c);
        } catch (Exception ignored) {
        }
        try {
            linkUserToolsIntoBin(c);
        } catch (Exception ignored) {
        }
        try {
            installPureGrok(c);
        } catch (Exception ignored) {
        }
        try {
            stageCaBundle(c);
        } catch (Exception ignored) {
        }
        try {
            stageDebianRootfs(c);
        } catch (Exception ignored) {
        }
        try {
            healHomePermissions(c);
        } catch (Exception ignored) {
        }
    }

    /**
     * Machine-readable plane for agents + humans. Must match the shell about to start.
     * hybridWant = prefs; hybridReady = overlay/lower actually usable.
     */
    public static void writePlaneStatus(Context c, boolean hybridWant, boolean hybridReady) {
        boolean hybrid = hybridWant && hybridReady;
        File home = home(c);
        String mode = hybrid ? "debian" : "android";
        String plane = hybrid ? "hybrid" : "android";
        String homePath = home.getAbsolutePath();
        String binPath = binDir(c).getAbsolutePath();
        String body =
            "=== ATLAS PLANE (agent) ===\n"
                + "atlas_version=" + MainActivity.VERSION + "\n"
                + "plane=" + plane + "\n"
                + "mode=" + mode + "\n"
                + "session=" + (hybrid ? "hybrid" : "atlas") + "\n"
                + "priv=" + (hybrid ? "1" : "0") + "\n"
                + "hybrid_want=" + (hybridWant ? "1" : "0") + "\n"
                + "hybrid_ready=" + (hybridReady ? "1" : "0") + "\n"
                + "hybrid_env=" + (hybrid ? "yes" : "no") + "\n"
                + "hybrid_disk=" + (hybridReady ? "yes" : "no") + "\n"
                + "hybrid_overlay=" + (hybridReady ? "yes" : "no") + "\n"
                + "os_pretty=" + (hybrid ? "debian" : "android") + "\n"
                + "uid=" + android.os.Process.myUid() + " user=admin role=admin\n"
                + "home=" + homePath + "\n"
                + "atlas_bin=" + binPath + "\n"
                + "atlas_sysbin=/system/bin\n"
                + "reports_dir=" + homePath + "/reports\n"
                + "status_file=" + homePath + "/ATLAS_STATUS\n"
                + "plane_files=/data/local/tmp/titan2_atlas_mode /data/misc/titan2/\n"
                + "titan2_atlas_mode=" + mode + "\n"
                + "=== AGENT RULES ===\n"
                + "1) plane=hybrid|debian → Debian tools OK; Android Binder: android <cmd>\n"
                + "2) screencap: atlas-screencap only (never bare screencap in hybrid)\n"
                + "3) reports: $HOME/reports/ · atlas-agent-status before claims\n"
                + "4) prove: cat /etc/os-release · echo $ATLAS_HYBRID\n";
        writeText(new File(home, "ATLAS_STATUS"), body);
        writeText(new File(home, "ATLAS_PLANE.env"),
            "ATLAS_PLANE=" + plane + "\n"
                + "ATLAS_MODE=" + mode + "\n"
                + "ATLAS_HYBRID=" + (hybrid ? "1" : "0") + "\n"
                + "ATLAS_SESSION=" + (hybrid ? "hybrid" : "atlas") + "\n"
                + "HOME=" + homePath + "\n"
                + "ATLAS_HOME=" + homePath + "\n"
                + "ATLAS_BIN=" + binPath + "\n");
        // Best-effort plane file (no su on main thread — hybrid enter writes as root)
        try {
            writeText(new File("/data/local/tmp/titan2_atlas_mode"), mode);
        } catch (Exception ignored) {
        }
    }

    /**
     * True when Debian hybrid is <b>actually enterable</b>.
     * Require live overlay + real bash + Debian identity. Dirty need-fsck means
     * not ready (force ensure). Never trust empty merge dirs or a stale ATLAS_STATUS.
     */
    public static boolean hybridRootfsReady() {
        // Dirty image after crash/reboot — must re-ensure (e2fsck + remount).
        if (new File("/data/local/tmp/atlas-hybrid-need-fsck").isFile()) {
            return false;
        }
        // Product status from system ensure (merge is often 0700 — app cannot stat bash).
        try {
            File ready = new File("/data/local/tmp/atlas_hybrid.ready");
            if (ready.isFile()) {
                java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.FileReader(ready));
                try {
                    String line = br.readLine();
                    if (line != null) {
                        line = line.trim();
                        if ("1".equals(line) && hybridOverlayMounted()) return true;
                        if ("0".equals(line)) return false;
                    }
                } finally {
                    br.close();
                }
            }
            File st = new File("/data/local/tmp/atlas_hybrid.status");
            if (st.isFile() && st.canRead()) {
                java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.FileReader(st));
                try {
                    String line;
                    boolean readyBit = false;
                    while ((line = br.readLine()) != null) {
                        if (line.startsWith("ready=1")) readyBit = true;
                    }
                    if (readyBit && hybridOverlayMounted()) return true;
                } finally {
                    br.close();
                }
            }
        } catch (Exception ignored) {
        }
        // Plane must be live. Without it, merge/lower paths are empty host dirs.
        if (!hybridOverlayMounted()) return false;
        File bash = null;
        for (String p : new String[] {
            "/data/local/atlas-hybrid/merge/usr/bin/bash",
            "/data/local/atlas-hybrid/merge/bin/bash",
            "/data/local/atlas-linux/usr/bin/bash",
            "/data/local/atlas-linux/bin/bash"
        }) {
            File f = new File(p);
            if (f.isFile() && f.canExecute() && f.length() > 1000) {
                bash = f;
                break;
            }
        }
        if (bash == null) {
            // Overlay up + status may be unreadable mid-write; still not ready without bash
            return false;
        }
        // Debian identity through the merge (proves content, not host stub).
        for (String base : new String[] {
            "/data/local/atlas-hybrid/merge",
            "/data/local/atlas-linux"
        }) {
            File deb = new File(base + "/etc/debian_version");
            File osr = new File(base + "/etc/os-release");
            if (deb.isFile() && deb.length() > 0) return true;
            if (osr.isFile() && osr.length() > 10) return true;
            File peer = new File(base + "/etc/atlas-hybrid-peer");
            if (peer.isFile()) return true;
        }
        return false;
    }

    /**
     * True if Debian plane is mounted for enter.
     * Product: overlay (legacy loop) <b>or</b> bind/ext4 of super {@code atlas_linux}
     * at merge / {@code /data/local/atlas-linux}.
     */
    public static boolean hybridOverlayMounted() {
        File mounts = new File("/proc/mounts");
        if (!mounts.isFile()) return false;
        try {
            java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.FileReader(mounts));
            try {
                String line;
                while ((line = br.readLine()) != null) {
                    // /proc/mounts: source mountpoint fstype options
                    String[] f = line.split(" ");
                    if (f.length < 3) continue;
                    String mp = f[1];
                    String fs = f[2];
                    if ("/data/local/atlas-hybrid/merge".equals(mp)) {
                        // overlay (loop) or ext4 bind of super LP
                        if ("overlay".equals(fs) || "ext4".equals(fs)
                                || "bind".equals(fs)) {
                            return true;
                        }
                        // any mount at merge with debian identity nearby
                        return true;
                    }
                    if ("/data/local/atlas-linux".equals(mp)
                            && ("ext4".equals(fs) || "bind".equals(fs))) {
                        return true;
                    }
                }
            } finally {
                br.close();
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * Free-root sessions often leave $HOME/.grok/* as root:root mode 600.
     * App uid then gets "Permission denied" on grok (binary is fine; auth.json is not).
     * Fix what we can as app; flag unreadable root leftovers.
     */
    public static void healHomePermissions(Context c) {
        File home = home(c);
        fixTreeMode(home);
        // ensure key dirs exist + user-writable
        for (String rel : new String[] {
            ".grok", ".grok/downloads", ".grok/bin", ".grok/logs",
            "bin", "auth", "etc", "rootfs"
        }) {
            File d = new File(home, rel);
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
            //noinspection ResultOfMethodCallIgnored
            d.setWritable(true, true);
            //noinspection ResultOfMethodCallIgnored
            d.setExecutable(true, false);
            //noinspection ResultOfMethodCallIgnored
            d.setReadable(true, false);
        }
        File grokElf = grokElf(c);
        if (grokElf.isFile()) {
            //noinspection ResultOfMethodCallIgnored
            grokElf.setExecutable(true, false);
            //noinspection ResultOfMethodCallIgnored
            grokElf.setReadable(true, false);
        }
        File auth = new File(home, ".grok/auth.json");
        File cfg = new File(home, ".grok/config.toml");
        // If still unreadable, root owns them — user needs atlas-heal-home once
        if ((auth.isFile() && !auth.canRead()) || (cfg.isFile() && !cfg.canRead())) {
            File marker = new File(home, ".atlas-need-heal");
            try (FileWriter w = new FileWriter(marker, false)) {
                w.write("root-owned files under $HOME — run: atlas-heal-home\n");
            } catch (Exception ignored) {
            }
        } else {
            //noinspection ResultOfMethodCallIgnored
            new File(home, ".atlas-need-heal").delete();
        }
    }

    /** True if grok/auth looks blocked by root leftovers. */
    public static boolean needsHomeHeal(Context c) {
        File auth = new File(home(c), ".grok/auth.json");
        File cfg = new File(home(c), ".grok/config.toml");
        if (auth.isFile() && !auth.canRead()) return true;
        if (cfg.isFile() && !cfg.canRead()) return true;
        return new File(home(c), ".atlas-need-heal").isFile();
    }

    private static void fixTreeMode(File root) {
        if (root == null || !root.exists()) return;
        //noinspection ResultOfMethodCallIgnored
        root.setReadable(true, false);
        if (root.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            root.setExecutable(true, false);
            //noinspection ResultOfMethodCallIgnored
            root.setWritable(true, true);
            File[] kids = root.listFiles();
            if (kids == null) return;
            for (File k : kids) {
                try {
                    fixTreeMode(k);
                } catch (Exception ignored) {
                }
            }
        } else {
            //noinspection ResultOfMethodCallIgnored
            root.setWritable(true, true);
            String n = root.getName();
            if (n.endsWith(".so") || n.equals("atlas") || n.equals("grok")
                || n.equals("ptyexec") || n.equals("bash") || !n.contains(".")) {
                //noinspection ResultOfMethodCallIgnored
                root.setExecutable(true, false);
            }
        }
    }

    /**
     * Force-refresh privilege wrappers so su/sudo always gate through biometrics.
     * Always overwrite these script names (never skip on timestamp).
     */
    public static void installPrivilegeWrappers(Context c) {
        File destRoot = binDir(c);
        //noinspection ResultOfMethodCallIgnored
        destRoot.mkdirs();
        // Auth agent clients. PATH su/sudo = agent clients → real absolute KSU after grant.
        // Always force-refresh (never leave 0-byte stubs).
        String[] force = {
            "atlas-auth", "atlas-auth-askpass", "atlas-heal-home",
            "atlas-sudo", "sudo", "su", "atlas-auth-pam",
            "apt-hybrid.sh", "atlas-hybrid.sh", "atlas-net.sh",
            "atlas-agent-status.sh", "atlas-screencap.sh"
        };
        for (String name : force) {
            File out = new File(destRoot, name);
            try {
                if (java.nio.file.Files.isSymbolicLink(out.toPath())) {
                    //noinspection ResultOfMethodCallIgnored
                    out.delete();
                }
            } catch (Exception ignored) {
            }
            // Skip only if a real non-empty ELF/script is already present and fresh enough
            // for optional large ELFs; always refresh small wrappers / 0-byte files.
            extractAssetAtomic(c, "bin/" + name, out);
        }
        // apt / apt-get / apt-cache → Debian hybrid bridge (needs KernelSU + bootstrap)
        File aptHyb = new File(destRoot, "apt-hybrid.sh");
        if (aptHyb.isFile()) {
            for (String name : new String[] {"apt", "apt-get", "apt-cache"}) {
                File link = new File(destRoot, name);
                try {
                    if (link.exists()) {
                        //noinspection ResultOfMethodCallIgnored
                        link.delete();
                    }
                    try (InputStream in = new java.io.FileInputStream(aptHyb);
                            OutputStream os = new FileOutputStream(link)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                    }
                    //noinspection ResultOfMethodCallIgnored
                    link.setExecutable(true, false);
                    //noinspection ResultOfMethodCallIgnored
                    link.setReadable(true, false);
                } catch (Exception ignored) {
                }
            }
        }
        // Agent clients as both names — bare `su` must not hit free KernelSU.
        File sudoGate = new File(destRoot, "atlas-sudo");
        File sudo = new File(destRoot, "sudo");
        File su = new File(destRoot, "su");
        if (sudoGate.isFile() && sudoGate.length() > 1000) {
            try {
                copyFile(sudoGate, sudo);
                copyFile(sudoGate, su);
                //noinspection ResultOfMethodCallIgnored
                sudo.setExecutable(true, false);
                //noinspection ResultOfMethodCallIgnored
                su.setExecutable(true, false);
            } catch (Exception ignored) {
            }
        }
        // Agent-facing short names (no .sh) for PATH discovery
        linkOrCopy(destRoot, "atlas-agent-status.sh", "atlas-agent-status");
        linkOrCopy(destRoot, "atlas-screencap.sh", "atlas-screencap");
        writeReportsReadme(c);
        healAuthDir(c);
    }

    private static void linkOrCopy(File destRoot, String srcName, String dstName) {
        File src = new File(destRoot, srcName);
        File dst = new File(destRoot, dstName);
        if (!src.isFile()) return;
        try {
            if (dst.exists()) {
                //noinspection ResultOfMethodCallIgnored
                dst.delete();
            }
            java.nio.file.Files.createSymbolicLink(dst.toPath(), src.toPath());
        } catch (Exception e) {
            try {
                copyFile(src, dst);
            } catch (Exception ignored) {
            }
        }
        //noinspection ResultOfMethodCallIgnored
        dst.setExecutable(true, false);
    }

    /** Agent report channel — agents write here; humans/other agents read. */
    private static void writeReportsReadme(Context c) {
        File dir = new File(home(c), "reports");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        writeText(new File(dir, "README.md"),
            "# Atlas agent reports\n\n"
                + "This directory is the **agent side channel** for Atlas hybrid work.\n\n"
                + "- Humans enable hybrid / auth in the Atlas UI.\n"
                + "- Agents write issue/status markdown here (`atlas-*.md`).\n"
                + "- Machine plane: `../ATLAS_STATUS`, `../ATLAS_PLANE.env`, "
                + "`/data/local/tmp/atlas_status.txt`.\n"
                + "- Always run `atlas-agent-status` before claiming hybrid or Android IPC.\n"
                + "- Hybrid Android tools: `android <cmd>`, `android-exec`, `atlas-screencap` "
                + "(never bare `screencap` / `am` without nsenter).\n");
    }

    /**
     * LAW: heal auth on super LP only. World R/W so Deb admin + app both write
     * req/ok/fail (same plane). Wipe-surviving.
     */
    public static void healAuthDir(Context c) {
        ensureAuthPlaneOnLp(c);
        File dir = authDirLp();
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        //noinspection ResultOfMethodCallIgnored
        dir.setReadable(true, false);
        //noinspection ResultOfMethodCallIgnored
        dir.setWritable(true, false);
        //noinspection ResultOfMethodCallIgnored
        dir.setExecutable(true, false);
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            String n = f.getName();
            if (n == null) continue;
            // Drop root-stale wake/busy; keep recent ok for clients to drain
            if (n.equals("wake") || n.startsWith("busy.")) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                continue;
            }
            //noinspection ResultOfMethodCallIgnored
            f.setReadable(true, false);
            //noinspection ResultOfMethodCallIgnored
            f.setWritable(true, false);
        }
    }

    /** Mozilla CA bundle for static Linux TLS (app home). */
    public static void stageCaBundle(Context c) {
        File out = new File(home(c), "cacert.pem");
        long apk = new File(c.getApplicationInfo().sourceDir).lastModified();
        if (out.isFile() && out.length() > 50_000L && out.lastModified() >= apk) {
            return;
        }
        AssetManager am = c.getAssets();
        for (String path : new String[] {"ssl/cacert.pem", "cacert.pem"}) {
            try (InputStream in = am.open(path);
                    OutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                //noinspection ResultOfMethodCallIgnored
                out.setReadable(true, false);
                return;
            } catch (Exception ignored) {
            }
        }
    }

    /** Official x.ai ELF only — never shell wrappers. */
    public static File grokElf(Context c) {
        return new File(home(c), ".grok/downloads/grok-linux-aarch64");
    }

    /**
     * Stage the same Debian 13 trixie arm64 image Atlas hybrid deploys
     * (peer: Armbian Radxa NIO 12L 16G). Source: assets/rootfs/ or already on disk.
     */
    public static File rootfsDir(Context c) {
        File d = new File(home(c), "rootfs");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    public static File stagedRootfsTar(Context c) {
        return new File(rootfsDir(c), "debian-trixie-arm64-rootfs.tar.gz");
    }

    public static void stageDebianRootfs(Context c) {
        File out = stagedRootfsTar(c);
        long apk = new File(c.getApplicationInfo().sourceDir).lastModified();
        if (out.isFile() && out.length() > 1_000_000L && out.lastModified() >= apk) {
            return;
        }
        AssetManager am = c.getAssets();
        /* Direct open first — list() is flaky for large single assets */
        String[] tryPaths = {
            "rootfs/debian-trixie-arm64-rootfs.tar.gz",
            "rootfs/rootfs.tar.gz",
        };
        String[] listed = null;
        try {
            listed = am.list("rootfs");
        } catch (Exception ignored) {
        }
        if (listed != null) {
            for (String n : listed) {
                if (n != null && n.endsWith(".tar.gz")) {
                    tryPaths = new String[] {
                        "rootfs/" + n,
                        "rootfs/debian-trixie-arm64-rootfs.tar.gz",
                    };
                    break;
                }
            }
        }
        for (String path : tryPaths) {
            try (InputStream in = am.open(path);
                    OutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                //noinspection ResultOfMethodCallIgnored
                out.setReadable(true, false);
                return;
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Install {@code $BIN/grok} as a plane gate (not a raw symlink).
     * Android plane: refuse and tell user to switch top-bar Deb.
     * Hybrid plane: exec real ELF with stable HOME/GROK_HOME/cwd.
     */
    public static void installPureGrok(Context c) {
        File elf = grokElf(c);
        if (!elf.isFile() || elf.length() < 1_000_000L) {
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        elf.setExecutable(true, false);
        File bin = binDir(c);
        File home = home(c);
        File link = new File(bin, "grok");
        // Remove stale symlink-to-ELF (Android could run Debian musl ELF badly /
        // empty resume). Always prefer the gate script.
        if (link.exists()) {
            //noinspection ResultOfMethodCallIgnored
            link.delete();
        }
        // Prefer Debian /bin/sh in hybrid so a poisoned LD_LIBRARY_PATH cannot
        // break Bionic /system/bin/sh (CANNOT LINK bad ELF magic). Fallback:
        // env -u before any Android binary.
        String body =
            "#!/bin/sh\n"
                + "# Atlas grok gate — Debian hybrid only (2026-08-10 LD harden)\n"
                + "# Never inherit Debian LD_LIBRARY_PATH into Bionic linkers.\n"
                + "unset LD_LIBRARY_PATH LD_PRELOAD 2>/dev/null || true\n"
                + "CANON_HOME=\"" + home.getAbsolutePath() + "\"\n"
                + "case \"${HOME:-}\" in\n"
                + "  /data/user/*|/data/user_de/*|\"\"|\"/\"|\"/root\") HOME=\"$CANON_HOME\" ;;\n"
                + "esac\n"
                + "export HOME=\"${HOME:-$CANON_HOME}\"\n"
                + "export ATLAS_HOME=\"${ATLAS_HOME:-$HOME}\"\n"
                + "export GROK_HOME=\"${GROK_HOME:-$HOME/.grok}\"\n"
                + "cd \"$HOME\" 2>/dev/null || cd \"$CANON_HOME\" 2>/dev/null || true\n"
                + "hybrid=0\n"
                + "if [ \"${ATLAS_HYBRID:-0}\" = \"1\" ] || [ \"${ATLAS_COMBINED:-0}\" = \"1\" ] \\\n"
                + "  || [ \"${ATLAS_SESSION:-}\" = \"hybrid\" ] || [ \"${ATLAS_PLANE:-}\" = \"hybrid\" ] \\\n"
                + "  || [ \"${ATLAS_MODE:-}\" = \"debian\" ] \\\n"
                + "  || [ -f /etc/debian_version ] || [ -f /etc/atlas-hybrid-peer ]; then\n"
                + "  hybrid=1\n"
                + "fi\n"
                + "if [ \"$hybrid\" != \"1\" ]; then\n"
                + "  echo \"grok: blocked on Android plane\" >&2\n"
                + "  echo \"Switch Atlas top-bar And → Deb (hybrid), then run: grok\" >&2\n"
                + "  echo \"(Debian tools need the hybrid shell — not Android PATH)\" >&2\n"
                + "  exit 90\n"
                + "fi\n"
                + "ELF=\"" + elf.getAbsolutePath() + "\"\n"
                + "if [ ! -x \"$ELF\" ]; then\n"
                + "  echo \"grok: ELF missing: $ELF\" >&2\n"
                + "  exit 127\n"
                + "fi\n"
                + "exec env -u LD_LIBRARY_PATH -u LD_PRELOAD \"$ELF\" \"$@\"\n";
        writeText(link, body);
        // Bionic nanobot (NDK) also dies if LD_LIBRARY_PATH points at Debian
        // libm.so ld-script — install a thin gate when nanobot ELF is present.
        File nano = new File(bin, "nanobot");
        File nanoReal = new File(bin, "nanobot.real");
        if (nano.isFile() && isElf(nano) && !nanoReal.exists()) {
            //noinspection ResultOfMethodCallIgnored
            nano.renameTo(nanoReal);
        }
        File nanoElf = nanoReal.isFile() ? nanoReal : nano;
        if (nanoElf.isFile() && isElf(nanoElf)) {
            String nbody =
                "#!/bin/sh\n"
                    + "# Atlas nanobot gate — strip Debian LD_* for Bionic NDK ELF\n"
                    + "unset LD_LIBRARY_PATH LD_PRELOAD 2>/dev/null || true\n"
                    + "ELF=\"" + nanoElf.getAbsolutePath() + "\"\n"
                    + "exec env -u LD_LIBRARY_PATH -u LD_PRELOAD \"$ELF\" \"$@\"\n";
            writeText(nano, nbody);
            //noinspection ResultOfMethodCallIgnored
            nano.setExecutable(true, false);
        }
        //noinspection ResultOfMethodCallIgnored
        link.setExecutable(true, false);
        File wrap = new File(bin, "grok-atlas");
        if (wrap.isFile()) {
            //noinspection ResultOfMethodCallIgnored
            wrap.delete();
        }
    }

    /**
     * Seed ~/.profile + ~/.bashrc so interactive bash inherits PATH without
     * the user typing export. Written every start (small, idempotent) to
     * <b>both</b> CE home and product linux home.
     */
    public static void ensureShellProfile(Context c) {
        ensureUserInstallDirs(c);
        // Linux home install dirs too (curl installs on Deb plane land here)
        File lh = new File(LINUX_HOME);
        //noinspection ResultOfMethodCallIgnored
        lh.mkdirs();
        for (String rel : new String[] {
            "bin", ".local/bin", ".cargo/bin", ".npm-global/bin"
        }) {
            //noinspection ResultOfMethodCallIgnored
            new File(lh, rel).mkdirs();
        }
        File home = home(c);
        String path = pathEnv(c);
        String homePath = home.getAbsolutePath();
        String linuxHomePath = LINUX_HOME;
        String bin = binDir(c).getAbsolutePath();
        // Dynamic PATH helper (no tool names): bin + .local/bin + every $HOME/.<x>/bin
        String pathHelper =
            "atlas_user_path() {\n"
                + "  _p=\"\"\n"
                + "  for _h in \"${ATLAS_LINUX_HOME:-/data/local/atlas-home/atlas}\" "
                + "\"${HOME:-}\" \"" + homePath + "\"; do\n"
                + "    [ -n \"$_h\" ] && [ -d \"$_h\" ] || continue\n"
                + "    [ -d \"$_h/bin\" ] && _p=\"${_p:+$_p:}$_h/bin\"\n"
                + "    [ -d \"$_h/.local/bin\" ] && _p=\"${_p:+$_p:}$_h/.local/bin\"\n"
                + "    for _d in \"$_h\"/.*; do\n"
                + "      [ -d \"$_d/bin\" ] || continue\n"
                + "      case \"$_d\" in */.|*/..|*/.local) continue ;; esac\n"
                + "      _p=\"${_p:+$_p:}$_d/bin\"\n"
                + "    done\n"
                + "  done\n"
                + "  echo \"$_p\"\n"
                + "}\n"
                + "_UP=$(atlas_user_path)\n"
                + "export PATH=\"${_UP:+$_UP:}/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:"
                + "/sbin:/bin:/atlas-bin:/system/bin:/system_ext/bin:/product/bin:"
                + "/system/xbin:/vendor/bin\"\n";
        String body =
            "# Atlas auto PATH — do not remove (regenerated)\n"
                + "# Universal: any curl install under $HOME/bin, .local/bin, or .*/bin → PATH\n"
                + "export ATLAS_LINUX_HOME=\"" + linuxHomePath + "\"\n"
                + "export HOME=\"" + homePath + "\"\n"
                + "export ATLAS_HOME=\"" + homePath + "\"\n"
                + "export ATLAS_BIN=\"" + bin + "\"\n"
                + "export TERM=\"${TERM:-xterm-256color}\"\n"
                + "export LANG=\"${LANG:-C.UTF-8}\"\n"
                + "export COLORTERM=\"${COLORTERM:-truecolor}\"\n"
                + pathHelper
                + "mkdir -p \"$HOME/bin\" \"$HOME/.local/bin\" 2>/dev/null || true\n"
                + "mkdir -p \"$ATLAS_LINUX_HOME/bin\" \"$ATLAS_LINUX_HOME/.local/bin\" "
                + "2>/dev/null || true\n"
                + "cd \"$HOME\" 2>/dev/null || true\n";
        writeText(new File(home, ".profile"), body);
        // Deb plane profile (linux home) — same PATH helper, HOME = linux home
        String bodyLinux =
            "# Atlas linux-home PATH (Deb plane) — regenerated\n"
                + "export ATLAS_LINUX_HOME=\"" + linuxHomePath + "\"\n"
                + "export HOME=\"" + linuxHomePath + "\"\n"
                + "export ATLAS_HOME=\"" + linuxHomePath + "\"\n"
                + "export ATLAS_BIN=\"" + bin + "\"\n"
                + pathHelper
                + "mkdir -p \"$HOME/bin\" \"$HOME/.local/bin\" 2>/dev/null || true\n"
                + "cd \"$HOME\" 2>/dev/null || true\n";
        writeText(new File(lh, ".profile"), bodyLinux);
        writeText(new File(lh, ".bashrc"),
            "# Atlas Deb interactive — source PATH helper\n"
                + "[ -f \"$HOME/.profile\" ] && . \"$HOME/.profile\"\n"
                + "hash -r 2>/dev/null || true\n");
        writeText(new File(home, ".bash_profile"),
            "# Atlas\n[ -f \"$HOME/.profile\" ] && . \"$HOME/.profile\"\n"
                + "[ -f \"$HOME/.bashrc\" ] && . \"$HOME/.bashrc\"\n");
        writeText(new File(home, ".bashrc"),
            "# Atlas interactive bash — hybrid awareness for humans + agents\n"
                + "# Canonical HOME (never /data/user/0) — grok sessions key by cwd string\n"
                + "export HOME=\"" + homePath + "\"\n"
                + "export ATLAS_HOME=\"" + homePath + "\"\n"
                + "export GROK_HOME=\"" + homePath + "/.grok\"\n"
                + "cd \"$HOME\" 2>/dev/null || true\n"
                + "[ -f \"$HOME/.profile\" ] && . \"$HOME/.profile\"\n"
                + "# --- PLANE (agents: do not guess android vs debian) ---\n"
                + "atlas_plane_detect() {\n"
                + "  if [ \"${ATLAS_HYBRID:-0}\" = \"1\" ] || [ -n \"${ATLAS_COMBINED:-}\" ] \\\n"
                + "    || [ \"${ATLAS_SESSION:-}\" = \"hybrid\" ] \\\n"
                + "    || [ \"${ATLAS_PLANE:-}\" = \"hybrid\" ] \\\n"
                + "    || [ \"${ATLAS_MODE:-}\" = \"debian\" ] \\\n"
                + "    || [ -f /etc/atlas-hybrid-peer ] \\\n"
                + "    || [ -f /etc/debian_version ] \\\n"
                + "    || { [ -f /etc/os-release ] && grep -qi debian /etc/os-release 2>/dev/null; }; then\n"
                + "    export ATLAS_PLANE=hybrid ATLAS_MODE=debian ATLAS_HYBRID=1\n"
                + "  else\n"
                + "    export ATLAS_PLANE=android ATLAS_MODE=android\n"
                + "    unset ATLAS_HYBRID ATLAS_COMBINED 2>/dev/null || true\n"
                + "  fi\n"
                + "}\n"
                + "atlas_plane_detect\n"
                + "# Block Debian-only tools on Android plane — force user to top-bar Deb\n"
                + "atlas_need_hybrid() {\n"
                + "  atlas_plane_detect\n"
                + "  if [ \"${ATLAS_PLANE:-android}\" = \"hybrid\" ]; then return 0; fi\n"
                + "  echo \"$1: blocked on Android plane\" >&2\n"
                + "  echo \"Switch Atlas top-bar And → Deb (hybrid), then retry: $1\" >&2\n"
                + "  return 90\n"
                + "}\n"
                + "if [ \"${ATLAS_PLANE:-android}\" != \"hybrid\" ]; then\n"
                + "  grok() { atlas_need_hybrid grok || return $?; command grok \"$@\"; }\n"
                + "  apt() { atlas_need_hybrid apt || return $?; }\n"
                + "  apt-get() { atlas_need_hybrid apt-get || return $?; }\n"
                + "  apt-cache() { atlas_need_hybrid apt-cache || return $?; }\n"
                + "  dpkg() { atlas_need_hybrid dpkg || return $?; }\n"
                + "  python3() { atlas_need_hybrid python3 || return $?; }\n"
                + "  pip() { atlas_need_hybrid pip || return $?; }\n"
                + "  pip3() { atlas_need_hybrid pip3 || return $?; }\n"
                + "fi\n"
                + "if [ \"${ATLAS_PLANE:-android}\" = \"hybrid\" ]; then\n"
                + "  # Never keep Debian LD_* — breaks Bionic nanobot/sh/am (bad ELF magic)\n"
                + "  unset LD_LIBRARY_PATH LD_PRELOAD 2>/dev/null || true\n"
                + "  PS1='\\[\\e[1;36m\\]debian\\[\\e[0m\\]:admin\\$ '\n"
                + "else\n"
                + "  PS1='\\[\\e[1;33m\\]android\\[\\e[0m\\]:admin\\$ '\n"
                + "fi\n"
                + "# MOTD once per interactive shell (agents + humans)\n"
                + "if [ -z \"${ATLAS_MOTD_SHOWN:-}\" ] && [ -n \"$PS1\" ]; then\n"
                + "  export ATLAS_MOTD_SHOWN=1\n"
                + "  echo \"Atlas plane=${ATLAS_PLANE:-?} mode=${ATLAS_MODE:-?} session=${ATLAS_SESSION:-?}\"\n"
                + "  if [ \"${ATLAS_PLANE:-}\" = \"hybrid\" ]; then\n"
                + "    echo \"Android IPC: android <cmd> | android-exec | atlas-screencap  (not bare screencap/am)\"\n"
                + "    echo \"Grok: cwd=$HOME · store=$GROK_HOME\"\n"
                + "    echo \"Grok past chats: /resume (Ctrl+S) — NOT /dashboard (live agents only)\"\n"
                + "    alias screencap='atlas-screencap' 2>/dev/null || true\n"
                + "  else\n"
                + "    echo \"Android shell — Debian tools (grok/apt/python3) blocked.\"\n"
                + "    echo \"Switch top-bar And → Deb for hybrid.\"\n"
                + "  fi\n"
                + "  echo \"Status: atlas-agent-status · Reports: $HOME/reports/\"\n"
                + "  # never run atlas-agent-status on shell open (load spike / typing lag)\n"
                + "fi\n"
                + "# DEL (0x7F) erase — matches TerminalView / ExtraKeys BKSP\n"
                + "stty erase '^?' 2>/dev/null || true\n"
                + "set -o emacs 2>/dev/null || true\n"
                + "alias ll='ls -la' 2>/dev/null || true\n"
                + "alias android-run='android' 2>/dev/null || true\n"
                + "# Privilege law: every su/sudo → Authentication Agent (biometrics) first.\n"
                + "export PATH=\"$ATLAS_BIN:$PATH\"\n"
                + "hash -r 2>/dev/null || true\n"
                + "export SUDO_ASKPASS=\"${SUDO_ASKPASS:-$ATLAS_BIN/atlas-auth-askpass}\"\n"
                + "export ATLAS_AUTH_DIR=\"${ATLAS_AUTH_DIR:-$HOME/auth}\"\n"
                + "export ATLAS_REPORTS=\"$HOME/reports\"\n"
                + "mkdir -p \"$HOME/reports\" \"$HOME/screenshots\" 2>/dev/null || true\n"
                + "sudo() { \"$ATLAS_BIN/sudo\" \"$@\"; }\n"
                + "su() { \"$ATLAS_BIN/su\" \"$@\"; }\n"
                + "unalias sudo 2>/dev/null || true\n"
                + "unalias su 2>/dev/null || true\n");
        writeText(new File(home, ".bash_env"),
            "[ -f \"$HOME/.profile\" ] && . \"$HOME/.profile\"\n");
        // readline: prefer no beep, standard key bindings
        writeText(new File(home, ".inputrc"),
            "set bell-style none\n"
                + "set meta-flag on\n"
                + "set input-meta on\n"
                + "set convert-meta off\n"
                + "set output-meta on\n"
                + "\"\\e[A\": history-search-backward\n"
                + "\"\\e[B\": history-search-forward\n"
                + "\"\\e[C\": forward-char\n"
                + "\"\\e[D\": backward-char\n"
                + "\"\\eOA\": history-search-backward\n"
                + "\"\\eOB\": history-search-forward\n"
                + "\"\\eOC\": forward-char\n"
                + "\"\\eOD\": backward-char\n");
    }

    /**
     * Symlink every executable under user-install bin dirs (CE + linux home)
     * into {@code $ATLAS_BIN} so thin PATHs still resolve tools. Universal —
     * no tool names; skips atlas reserved names (sudo/su/apt/…).
     */
    public static void linkUserToolsIntoBin(Context c) {
        File dest = binDir(c);
        //noinspection ResultOfMethodCallIgnored
        dest.mkdirs();
        java.util.LinkedHashSet<File> roots = new java.util.LinkedHashSet<>();
        roots.add(home(c));
        roots.add(new File(LINUX_HOME));
        for (File h : roots) {
            if (h == null || !h.isDirectory()) continue;
            java.util.ArrayList<File> bins = new java.util.ArrayList<>();
            File b0 = new File(h, "bin");
            if (b0.isDirectory()) bins.add(b0);
            File b1 = new File(h, ".local/bin");
            if (b1.isDirectory()) bins.add(b1);
            File[] kids = h.listFiles();
            if (kids != null) {
                for (File kid : kids) {
                    if (!kid.isDirectory()) continue;
                    String n = kid.getName();
                    if (n.length() < 2 || n.charAt(0) != '.') continue;
                    if (".local".equals(n)) continue;
                    File b = new File(kid, "bin");
                    if (b.isDirectory()) bins.add(b);
                }
            }
            for (File bdir : bins) {
                File[] tools = bdir.listFiles();
                if (tools == null) continue;
                for (File t : tools) {
                    if (t == null) continue;
                    if (!t.isFile() && !java.nio.file.Files.isSymbolicLink(t.toPath())) continue;
                    String name = t.getName();
                    if (name.isEmpty() || name.indexOf('/') >= 0) continue;
                    if (isReservedUserLinkName(name)) continue;
                    // Ensure executable bit when we can (curl install often 644)
                    //noinspection ResultOfMethodCallIgnored
                    t.setExecutable(true, false);
                    File link = new File(dest, name);
                    try {
                        if (link.exists()) {
                            if (link.getCanonicalPath().equals(t.getCanonicalPath())) continue;
                            //noinspection ResultOfMethodCallIgnored
                            link.delete();
                        }
                        try {
                            java.nio.file.Files.createSymbolicLink(link.toPath(), t.toPath());
                        } catch (Exception e) {
                            if (t.isFile()) {
                                copyFile(t, link);
                                //noinspection ResultOfMethodCallIgnored
                                link.setExecutable(true, false);
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        }
    }

    private static boolean isReservedUserLinkName(String name) {
        if (name == null) return true;
        switch (name) {
            case "sudo":
            case "su":
            case "doas":
            case "pkexec":
            case "apt":
            case "apt-get":
            case "apt-cache":
            case "bash":
            case "sh":
            case "atlas":
            case "atlas-sudo":
            case "atlas-net.sh":
            case "atlas-hybrid.sh":
                return true;
            default:
                return false;
        }
    }

    private static void copyFile(File from, File to) throws Exception {
        try (InputStream in = new java.io.FileInputStream(from);
                OutputStream os = new FileOutputStream(to)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
    }

    /** True if file starts with ELF magic (Bionic NDK / Linux shared objects). */
    private static boolean isElf(File f) {
        if (f == null || !f.isFile() || f.length() < 4) return false;
        try (InputStream in = new java.io.FileInputStream(f)) {
            byte[] m = new byte[4];
            if (in.read(m) != 4) return false;
            return m[0] == 0x7f && m[1] == 'E' && m[2] == 'L' && m[3] == 'F';
        } catch (Exception e) {
            return false;
        }
    }

    private static void writeText(File f, String body) {
        try (FileWriter w = new FileWriter(f, false)) {
            w.write(body);
        } catch (Exception ignored) {
        }
    }
}
