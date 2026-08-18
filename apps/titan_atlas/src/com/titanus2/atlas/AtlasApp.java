package com.titanus2.atlas;

import android.app.Application;
import android.util.Log;

/**
 * Product Application: extract native PTY/term libs <b>before</b> any Activity
 * builds a {@code TerminalView} (JNI static load). No root required.
 *
 * Architecture (2026-08) — <b>wipe law for every change</b>:
 * <ul>
 *   <li>Atlas = terminal + atlas-auth biometry</li>
 *   <li>Debian root = super LP {@code atlas_linux} (survives userdata wipe)
 *       or hybrid overlay/lower (survives Atlas Clear data)</li>
 *   <li>Auth plane = {@code /data/local/atlas-linux/var/lib/atlas-auth} on that LP
 *       (survives userdata wipe) — never app CE, never tmp</li>
 *   <li>Linux HOME = {@code /data/local/atlas-home/atlas} (survives Atlas
 *       Clear data; wiped with factory reset)</li>
 *   <li>Atlas Clear data wipes CE prefs only — must remount + enterd, never
 *       bootstrap or treat Deb as gone</li>
 * </ul>
 */
public final class AtlasApp extends Application {
    private static final String TAG = "AtlasApp";

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            NativeBin.ensureUserInstallDirs(this);
            NativeBin.ensureNativeLibs(this);
            /* Nanobot peers: Nanobot app + Titan command plane. Not Atlas. */
            AtlasPrefs.publishPrivilegePlane(this);
            AtlasPrefs.publishBioPlane(this);
            AtlasPrefs.publishAuthPolicy(this);
            boolean ceWipe = AtlasPrefs.restoreHybridAfterCeWipe(this);
            if (ceWipe || NativeBin.debianRootPresent()) {
                HybridEnsure.kickAfterCeWipe(this);
            } else {
                HybridEnsure.ensureLiveUidAsync(this);
            }
        } catch (Throwable t) {
            Log.w(TAG, "native lib warm", t);
        }
        // atlas-lpctl / hybrid mount can block tens of seconds. Doing that
        // on the main thread ANRs the splash (Process.waitFor). Auth plane
        // is not required to draw the terminal.
        final android.content.Context app = getApplicationContext();
        new Thread(() -> {
            try {
                NativeBin.ensureAuthPlaneOnLp(app);
                NativeBin.healAuthDir(app);
                java.io.File dir = NativeBin.authDirLp();
                //noinspection ResultOfMethodCallIgnored
                new java.io.File(dir, "ticket").delete();
                //noinspection ResultOfMethodCallIgnored
                new java.io.File(dir, "ticket.screencap").delete();
                //noinspection ResultOfMethodCallIgnored
                new java.io.File("/data/local/tmp/atlas_auth.ticket").delete();
            } catch (Throwable t) {
                Log.w(TAG, "auth plane warm", t);
            }
        }, "atlas-auth-plane").start();
    }
}
