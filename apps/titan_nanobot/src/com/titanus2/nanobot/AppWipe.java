package com.titanus2.nanobot;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * App Clear data must be a fresh empty Nanobot. Grok session / peer_token
 * living under {@link NanobotRuntime#SHARED_HOME} after CE wipe is heresy.
 *
 * <p>Factory reset already deletes {@code /data/local/tmp}. App wipe does not —
 * that is the case we shred.
 */
public final class AppWipe {
    private static final String TAG = "NanobotWipe";
    private static final String CE_MARK = "ce_alive";

    private AppWipe() {}

    public static void reconcile(Context c) {
        if (c == null) return;
        File mark = new File(c.getFilesDir(), CE_MARK);
        if (mark.isFile()) return;
        File shared = new File(NanobotRuntime.SHARED_HOME);
        boolean secrets = sharedHasIdentity(shared);
        if (secrets) {
            Log.w(TAG, "CE empty + shared identity — app wipe; shred Grok/session");
            NanobotRuntime.stopPeer(c);
            shredIdentity(shared);
            File appHome = new File(c.getFilesDir(), NanobotCli.APP_HOME_NAME);
            shredIdentity(appHome);
        }
        writeMark(mark);
    }

    public static boolean sharedHasIdentity(File home) {
        if (home == null || !home.isDirectory()) return false;
        String[] names = {
            "session", "session.key", "peer_token", "grok_auth.json",
            "device_login", ".grok"
        };
        for (String n : names) {
            File f = new File(home, n);
            if (f.isFile() && f.length() > 0) return true;
            if (f.isDirectory()) {
                String[] kids = f.list();
                if (kids != null && kids.length > 0) return true;
            }
        }
        return false;
    }

    /** Fresh empty home — identity only. Leave nothing that can rehydrate Grok. */
    public static void shredIdentity(File home) {
        if (home == null || !home.isDirectory()) return;
        String[] names = {
            "session", "session.key", "peer_token", "grok_auth.json",
            "device_login", "nanobot.serve.lock"
        };
        for (String n : names) deleteQuiet(new File(home, n));
        deleteTree(new File(home, ".grok"));
        // Chat memory is the same identity surface after a wipe.
        deleteTree(new File(home, "memory"));
        deleteQuiet(new File(home, "access_history.jsonl"));
        Log.i(TAG, "shredded identity under " + home.getAbsolutePath());
    }

    private static void writeMark(File mark) {
        try {
            File p = mark.getParentFile();
            if (p != null) //noinspection ResultOfMethodCallIgnored
                p.mkdirs();
            try (FileOutputStream o = new FileOutputStream(mark)) {
                o.write(("v1 " + System.currentTimeMillis() + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Log.w(TAG, "ce mark: " + e.getMessage());
        }
    }

    private static void deleteQuiet(File f) {
        if (f == null) return;
        //noinspection ResultOfMethodCallIgnored
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
