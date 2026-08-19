package com.titanus2.nanobot;

import android.content.Context;
import android.util.Log;

import com.titanus2.api.AtlasAuthPlane;

import org.json.JSONObject;

/**
 * Optional Nanobot hook onto the Atlas privilege plane.
 * Pref off → existing PrivacyPrefs gates only (no regression).
 * Pref on → Debian-class: observe flows, capture/mutate asks Atlas.
 */
public final class AtlasAuthGate {
    private static final String TAG = "AtlasAuthGate";

    private AtlasAuthGate() {}

    /**
     * @return null if allowed; error JSON fragment fields via {@code dest} if blocked
     */
    public static boolean allow(Context c, JSONObject dest, String scope, String reason) {
        if (!PrivacyPrefs.atlasAuth(c)) return true;
        AtlasAuthPlane.Result r = AtlasAuthPlane.request(
            c, scope, reason, reason, AtlasAuthPlane.DEFAULT_TIMEOUT_SEC);
        if (r.ok) {
            try {
                AccessLog.record(c, "atlas_auth", scope + " via=" + r.via);
            } catch (Exception ignored) {}
            return true;
        }
        Log.w(TAG, "deny scope=" + scope + " via=" + r.via + " " + r.error);
        if (dest != null) {
            try {
                dest.put("ok", false);
                dest.put("error", "atlas-auth: " + r.error);
                dest.put("atlas_via", r.via);
                dest.put("atlas_scope", AtlasAuthPlane.sanitizeScope(scope));
            } catch (Exception ignored) {}
        }
        try {
            AccessLog.record(c, "atlas_auth_deny", scope + " " + r.via + " " + r.error);
        } catch (Exception ignored) {}
        return false;
    }

    public static boolean allowSilent(Context c, String scope, String reason) {
        return allow(c, null, scope, reason);
    }

    public static JSONObject status(Context c) {
        JSONObject o = new JSONObject();
        try {
            o.put("optional", true);
            o.put("enabled", PrivacyPrefs.atlasAuth(c));
            o.put("plane", AtlasAuthPlane.authDir().getAbsolutePath());
            o.put("plane_ready", AtlasAuthPlane.planeReady());
            o.put("strict", AtlasAuthPlane.isStrict());
            o.put("ticket_ttl", AtlasAuthPlane.ticketTtlSec());
        } catch (Exception ignored) {}
        return o;
    }
}
