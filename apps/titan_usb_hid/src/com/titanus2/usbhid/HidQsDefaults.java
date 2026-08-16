package com.titanus2.usbhid;

import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;
import android.util.Log;

/**
 * Put the HID Share tile on the first QS page. Separate from Controls pad tile.
 */
final class HidQsDefaults {
    private static final String TAG = "HidQsDefaults";
    private static final String SECURE_KEY = "sysui_qs_tiles";

    private HidQsDefaults() {}

    static String tileSpec(Context ctx) {
        return "custom(" + new ComponentName(ctx, ShareTileService.class)
            .flattenToShortString() + ")";
    }

    static boolean ensureDefaultTile(Context ctx) {
        if (ctx == null) return false;
        Context app = ctx.getApplicationContext();
        String spec = tileSpec(app);
        try {
            String raw = Settings.Secure.getString(app.getContentResolver(), SECURE_KEY);
            if (raw == null || raw.trim().isEmpty()) return false;
            String cur = raw.trim();
            if (cur.contains("ShareTileService")) return false;
            java.util.ArrayList<String> list = new java.util.ArrayList<>();
            int insert = 0;
            int i = 0;
            for (String p : cur.split(",")) {
                if (p == null) continue;
                p = p.trim();
                if (p.isEmpty()) continue;
                list.add(p);
                if (p.contains("PadModeTileService") || p.equals("bt")
                        || p.equals("bluetooth")) {
                    insert = i + 1;
                }
                i++;
            }
            if (insert > list.size()) insert = list.size();
            list.add(insert, spec);
            StringBuilder sb = new StringBuilder();
            for (int n = 0; n < list.size(); n++) {
                if (n > 0) sb.append(',');
                sb.append(list.get(n));
            }
            boolean ok = Settings.Secure.putString(
                app.getContentResolver(), SECURE_KEY, sb.toString());
            Log.i(TAG, "sysui_qs_tiles hid share ok=" + ok + " spec=" + spec);
            return ok;
        } catch (Exception e) {
            Log.w(TAG, "ensureDefaultTile: " + e.getMessage());
            return false;
        }
    }
}
