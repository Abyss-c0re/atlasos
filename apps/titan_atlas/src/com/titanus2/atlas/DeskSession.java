package com.titanus2.atlas;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Start and install the Debian graphic session from the Android side. */
public final class DeskSession {
    private static final String TAG = "AtlasDesk";
    static final String CHROOT = "/data/local/atlas-linux";

    private DeskSession() {}

    /** Same directory the session uses inside the chroot as /home/atlas/atlas-x. */
    public static String liveDir() {
        return CHROOT + "/home/atlas/atlas-x";
    }

    public static String presentSock(Context c) {
        return liveDir() + "/present.sock";
    }

    public static String inputSock(Context c) {
        return liveDir() + "/input.sock";
    }

    public static String start(Context c) {
        return launch(c, false);
    }

    /**
     * Ask the root desk helper to restart Plasma.
     * The app cannot exec su, so a file in the shared directory is the signal.
     */
    public static String restart(Context c) {
        try {
            java.nio.file.Files.write(
                java.nio.file.Path.of("/data/local/tmp/atlas-virgl/desk.cmd"),
                "restart\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            return "restarting";
        } catch (Exception e) {
            return e.getMessage() != null ? e.getMessage() : "restart failed";
        }
    }

    private static String launch(Context c, boolean restart) {
        try {
            NativeBin.ensureExtracted(c);
        } catch (Exception e) {
            return e.getMessage() != null ? e.getMessage() : "extract failed";
        }
        File bindir = NativeBin.binDir(c);
        String bin = new File(bindir, "atlas-desk-session").getAbsolutePath();
        int w = AtlasPrefs.deskW(c);
        int h = AtlasPrefs.deskH(c);
        String scale = AtlasPrefs.deskScaleLabel(c);
        String comp = AtlasPrefs.deskCompose(c) ? "1" : "0";
        String virgl = new File(bindir, "virgl_test_server").getAbsolutePath();
        String vrend = new File(bindir, "libvirglrenderer.so").getAbsolutePath();
        String epoxy = new File(bindir, "libepoxy.so").getAbsolutePath();
        String tfp = new File(bindir, "libatlas-virpipe-tfp.so").getAbsolutePath();
        String vdir = "/data/local/tmp/atlas-virgl";
        String cmd = ""
            + "mkdir -p " + CHROOT + "/home/atlas/atlas-x "
            + CHROOT + "/usr/local/bin " + CHROOT + "/tmp/atlas-virgl " + vdir + "; "
            + "chmod 0777 " + CHROOT + "/home/atlas/atlas-x " + vdir + "; "
            + "if ! grep -q ' " + CHROOT + "/tmp/atlas-virgl ' /proc/mounts; then "
            + "mount --bind " + vdir + " " + CHROOT + "/tmp/atlas-virgl; fi; "
            + "mkdir -p " + CHROOT + "/sdcard " + CHROOT + "/mnt; "
            + "if ! grep -q ' " + CHROOT + "/sdcard ' /proc/mounts; then "
            + "mount --bind /storage/emulated/0 " + CHROOT + "/sdcard "
            + "|| mount --bind /sdcard " + CHROOT + "/sdcard || true; fi; "
            + "if ! pidof virgl_test_server >/dev/null 2>&1; then "
            + "cp -f '" + virgl + "' '" + vrend + "' '" + epoxy + "' " + vdir + "/; "
            + "cp -f '" + tfp + "' " + vdir + "/ 2>/dev/null || true; "
            + "chmod 755 " + vdir + "/virgl_test_server " + vdir + "/libvirglrenderer.so "
            + vdir + "/libepoxy.so; "
            + "if [ -x " + vdir + "/virgl_test_server ]; then "
            + "LD_LIBRARY_PATH=" + vdir + " "
            + "VTEST_USE_EGL_SURFACELESS=1 VTEST_USE_GLES=1 "
            + vdir + "/virgl_test_server --use-egl-surfaceless --use-gles "
            + "--socket-path " + vdir + "/virgl.sock "
            + "</dev/null >" + vdir + "/server.log 2>&1 & "
            + "fi; fi; "
            + "i=0; while [ ! -S " + vdir + "/virgl.sock ] && [ \"$i\" -lt 40 ]; do "
            + "i=$((i+1)); sleep 0.1; done; "
            + "cp -f '" + bin + "' " + CHROOT + "/usr/local/bin/atlas-desk-session; "
            + "chmod 755 " + CHROOT + "/usr/local/bin/atlas-desk-session; "
            + "printf '%s\\n' \"$(getprop persist.sys.timezone)\" > " + vdir + "/android-tz; "
            + "chroot " + CHROOT + " /usr/bin/env -i "
            + "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin "
            + "HOME=/home/atlas USER=atlas LOGNAME=atlas "
            + "ATLAS_TZ=\"$(getprop persist.sys.timezone)\" "
            + (restart ? "ATLAS_DESK_RESTART=1 " : "")
            + "ATLAS_DESK_W=" + w + " ATLAS_DESK_H=" + h + " "
            + "ATLAS_DESK_COMPOSE=" + comp + " ATLAS_DESK_SCALE=" + scale + " "
            + "/usr/bin/setsid /usr/local/bin/atlas-desk-session "
            + "</dev/null >/tmp/desk-session.out 2>&1 & echo STARTED";
        return root(cmd);
    }

    public static String stop() {
        DeskAudio.stop();
        return root("chroot " + CHROOT + " /bin/sh -c '"
            + "if [ -f /home/atlas/atlas-x/session.pid ]; then "
            + "kill $(cat /home/atlas/atlas-x/session.pid) 2>/dev/null || true; fi; "
            + "pkill -f atlas-desk-session 2>/dev/null || true; "
            + "pkill -f startplasma-x11 2>/dev/null || true; "
            + "pkill -x kwin_x11 2>/dev/null || true; "
            + "pkill -x plasmashell 2>/dev/null || true; "
            + "pkill -x atlas-x 2>/dev/null || true; "
            + "echo STOPPED'; "
            + "pids=$(pidof atlas-audio-bridge 2>/dev/null) || true; "
            + "if [ -n \"$pids\" ]; then kill $pids 2>/dev/null || true; fi");
    }

    /**
     * Speaker and mic FIFOs. The Atlas process opens AudioTrack and AudioRecord.
     * Do not start atlas-audio-bridge: a root client is given no mix session.
     */
    public static String startAudio(Context c) {
        prepareAudioFifos();
        DeskAudio.start(c);
        return "AUDIO";
    }

    static void prepareAudioFifos() {
        String vdir = "/data/local/tmp/atlas-virgl";
        root("mkdir -p " + vdir + "; "
            + "if [ ! -p " + vdir + "/audio-play ]; then mkfifo -m 666 " + vdir + "/audio-play; fi; "
            + "if [ ! -p " + vdir + "/audio-cap ]; then mkfifo -m 666 " + vdir + "/audio-cap; fi; "
            + "chmod 666 " + vdir + "/audio-play " + vdir + "/audio-cap; "
            + "echo FIFOS");
    }

    /** After the app holds both FIFOs. Closing the last reader makes PipeWire see EPIPE. */
    static void releaseRootBridge() {
        root("pids=$(pidof atlas-audio-bridge 2>/dev/null) || exit 0; "
            + "kill $pids 2>/dev/null || true; "
            + "sleep 0.2; "
            + "pids=$(pidof atlas-audio-bridge 2>/dev/null) || exit 0; "
            + "kill -9 $pids 2>/dev/null || true; "
            + "echo BRIDGE-OFF");
    }

    /** Apt-install Plasma. Blocks. Log is the returned text. */
    public static String install(Context c) {
        try {
            NativeBin.ensureExtracted(c);
        } catch (Exception e) {
            return e.getMessage() != null ? e.getMessage() : "extract failed";
        }
        String script = new File(NativeBin.binDir(c), "atlas-desk-install").getAbsolutePath();
        String cmd = ""
            + "mkdir -p " + CHROOT + "/usr/local/bin; "
            + "cp -f '" + script + "' " + CHROOT + "/usr/local/bin/atlas-desk-install; "
            + "chmod 755 " + CHROOT + "/usr/local/bin/atlas-desk-install; "
            + "chroot " + CHROOT + " /usr/bin/env -i "
            + "PATH=/usr/sbin:/usr/bin:/sbin:/bin DEBIAN_FRONTEND=noninteractive HOME=/root "
            + "/bin/sh /usr/local/bin/atlas-desk-install; "
            + "echo EXIT:$?; "
            + "chroot " + CHROOT + " /bin/cat /tmp/desk-install.log";
        return root(cmd);
    }

    /** TitanKey → seat, for as long as Desk is in front. */
    public static String startKeys(Context c) {
        try {
            NativeBin.ensureExtracted(c);
        } catch (Exception e) {
            return e.getMessage() != null ? e.getMessage() : "extract failed";
        }
        String bin = new File(NativeBin.binDir(c), "atlas-desk-keys").getAbsolutePath();
        String vdir = "/data/local/tmp/atlas-virgl";
        String cmd = ""
            + "mkdir -p " + vdir + "; "
            + "cp -f '" + bin + "' " + vdir + "/atlas-desk-keys; "
            + "chmod 755 " + vdir + "/atlas-desk-keys; "
            + "if [ -f " + vdir + "/keys.pid ]; then "
            + "kill $(cat " + vdir + "/keys.pid) 2>/dev/null || true; fi; "
            + "dev=; for n in /sys/class/input/event*; do "
            + "name=$(cat $n/device/name 2>/dev/null); "
            + "if [ \"$name\" = TitanKey ]; then dev=/dev/input/$(basename $n); fi; done; "
            + "if [ -z \"$dev\" ] || [ ! -x " + vdir + "/atlas-desk-keys ]; then echo NODEV; exit 0; fi; "
            + "setsid " + vdir + "/atlas-desk-keys \"$dev\" "
            + CHROOT + "/home/atlas/atlas-x/input.sock "
            + "</dev/null >" + vdir + "/keys.log 2>&1 & echo $! > " + vdir + "/keys.pid; "
            + "echo KEYS $dev";
        return root(cmd);
    }

    public static String stopKeys() {
        return root("if [ -f /data/local/tmp/atlas-virgl/keys.pid ]; then "
            + "kill $(cat /data/local/tmp/atlas-virgl/keys.pid) 2>/dev/null || true; "
            + "rm -f /data/local/tmp/atlas-virgl/keys.pid; fi; echo KEYS-OFF");
    }

    public static String status() {
        return root("chroot " + CHROOT + " /bin/sh -c '"
            + "if [ -x /usr/bin/startplasma-x11 ]; then echo plasma=yes; else echo plasma=no; fi; "
            + "if [ -x /usr/local/bin/atlas-x ]; then echo server=yes; else echo server=no; fi; "
            + "if [ -f /home/atlas/atlas-x/xdisplay ]; then echo display=$(cat /home/atlas/atlas-x/xdisplay); fi; "
            + "ps -A -o comm= 2>/dev/null | grep -E \"^(atlas-x|kwin_x11|plasmashell|Xwayland)$\" || true'");
    }

    private static String root(String cmd) {
        String[] sus = {
            "/system/bin/su",
            "/debug_ramdisk/su",
            "/system/xbin/su",
            HybridEnsure.resolveRealSu(),
            "su"
        };
        Exception last = null;
        for (String su : sus) {
            if (su == null || su.isEmpty()) continue;
            try {
                Process p = new ProcessBuilder(su, "0", "sh", "-c", cmd)
                    .redirectErrorStream(true)
                    .start();
                InputStream in = p.getInputStream();
                /* The session script is backgrounded. su must not block the
                 * thread that also has to run Restart. */
                boolean done = p.waitFor(8, java.util.concurrent.TimeUnit.SECONDS);
                byte[] buf = in.readNBytes(4096);
                if (!done) {
                    p.destroy();
                }
                String s = new String(buf, StandardCharsets.UTF_8).trim();
                Log.i(TAG, su + " " + (s.length() > 300 ? s.substring(0, 300) : s));
                return s.isEmpty() ? ("exit " + p.exitValue()) : s;
            } catch (Exception e) {
                last = e;
                Log.w(TAG, su + ": " + e.getMessage());
            }
        }
        return last != null && last.getMessage() != null ? last.getMessage() : "su failed";
    }
}
