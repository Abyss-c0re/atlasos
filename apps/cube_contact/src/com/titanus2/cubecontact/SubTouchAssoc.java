package com.titanus2.cubecontact;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Bind rear digitizer {@code sub_touch} → display 2 uniqueId.
 * A16: ASSOCIATE is signature-only; shell can {@code service call input 43}.
 * Pad-agent 2.67 owns this; cube re-asserts on rear open so touch is not stuck
 * on main (assoc=pending left spin/zoom dead on subscreen).
 */
public final class SubTouchAssoc {
    private static final String TAG = "SubTouchAssoc";
    /** Lab Titan 2 EventHub hash for sub_touch (stable). */
    public static final String DESC = "d498fd4b8ff8c34cb9de09546f4b0e8a26606f5f";
    public static final String REAR_UID = "local:4627039422300187651";

    private SubTouchAssoc() {}

    /** Fire-and-forget on a background thread. */
    public static void ensureAsync() {
        new Thread(SubTouchAssoc::ensure, "subtouch-assoc").start();
    }

    public static boolean ensure() {
        // service call input 43 s16 <desc> s16 <uid>
        String[][] cmds = {
            {"su", "2000", "-c",
                "service call input 43 s16 " + DESC + " s16 " + REAR_UID},
            {"/system/bin/sh", "-c",
                "service call input 43 s16 " + DESC + " s16 " + REAR_UID},
            {"service", "call", "input", "43", "s16", DESC, "s16", REAR_UID},
        };
        for (String[] cmd : cmds) {
            if (run(cmd)) {
                try {
                    android.content.Context c = StateMatrix.resolveAppContext();
                    if (c != null) {
                        android.provider.Settings.Global.putString(
                            c.getContentResolver(),
                            "titan2_subtouch_assoc", REAR_UID);
                    }
                } catch (Exception ignored) {}
                Log.i(TAG, "sub_touch → rear " + REAR_UID);
                return true;
            }
        }
        Log.w(TAG, "sub_touch assoc failed (need shell/pad-agent)");
        return false;
    }

    private static boolean run(String[] cmd) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(cmd);
            boolean ok = p.waitFor() == 0;
            // even non-zero may have applied; read a line of result
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream()))) {
                String line = br.readLine();
                if (line != null && line.contains("Parcel")) ok = true;
            } catch (Exception ignored) {}
            return ok;
        } catch (Exception e) {
            return false;
        } finally {
            if (p != null) try { p.destroy(); } catch (Exception ignored) {}
        }
    }
}
