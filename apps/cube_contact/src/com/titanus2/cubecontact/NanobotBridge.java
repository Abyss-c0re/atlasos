package com.titanus2.cubecontact;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

/**
 * Thin client for the on-device Titan nanobot peer (same port/API as TitanNanobot).
 * Chat uses whatever model the peer is currently configured with — no separate stack.
 *
 * <p>1.63 residual: always drain peer HTTP bodies via {@link PeerHttp} so
 * concurrent-fork workers do not stick CLOSE-WAIT (max 24).
 *
 * <p>1.85: user chat is never parked for cube cool/load heat
 * ({@link CubeStability#allowPeerChat} always true). Lattice multi-pull may
 * still cool; typed messages must reach nanobot.
 */
public final class NanobotBridge {
    private static final String TAG = "CubeNanobot";
    /** Same as NanobotRuntime.PORT */
    public static final String DEFAULT_PEER = "http://127.0.0.1:8787";

    public interface StreamListener {
        void onDelta(String s);
        void onDone(String full);
        void onError(Exception e);
    }

    private final String base;
    private final Context app;
    private volatile String modelLabel = "";
    private volatile boolean healthy;

    public NanobotBridge(Context c) {
        this.base = DEFAULT_PEER;
        Context a = null;
        try {
            if (c != null) {
                a = c.getApplicationContext();
                if (a == null) a = c;
            }
        } catch (Exception ignored) {
            a = c;
        }
        this.app = a;
    }

    public boolean healthy() { return healthy; }
    public String modelLabel() { return modelLabel; }

    public void refreshHealth() {
        refreshHealth(false);
    }

    /**
     * @param forChat if true, always probe peer (user message path — no cool park).
     */
    public void refreshHealth(boolean forChat) {
        // Background status can skip under cool park; chat path must probe.
        if (!forChat) {
            try {
                if (app != null && !CubeStability.allowPeerHttp(app)) {
                    healthy = false;
                    return;
                }
            } catch (Exception ignored) {}
        }
        // 1.63 residual: getResponseCode + disconnect without body drain left
        // peer workers stuck CLOSE-WAIT (fork max 24 → LAW/chat starve).
        try {
            int code = PeerHttp.probeCode(base + "/peer/v1/health", 600, 800, forChat);
            healthy = code >= 200 && code < 500;
        } catch (Exception e) {
            healthy = false;
        }
        try {
            String s = forChat
                ? PeerHttp.getBodyChat(base + "/api/auth", 600, 1200)
                : PeerHttp.getBody(base + "/api/auth", 600, 1200);
            if (s != null && !s.isEmpty()) {
                JSONObject j = new JSONObject(s);
                String m = j.optString("model", "");
                if (!m.isEmpty()) {
                    int slash = Math.max(m.lastIndexOf('/'), m.lastIndexOf('\\'));
                    modelLabel = slash >= 0 ? m.substring(slash + 1) : m;
                }
                healthy = healthy || j.optBoolean("ok", false);
            }
        } catch (Exception ignored) {}
    }

    /**
     * Chat through nanobot /api/chat — uses the peer's current backend/model.
     * Never blocked by cube cool/load heat. Prefer
     * {@link #chatCommander} from Neural Cube UI.
     */
    public void chat(String userLine, StreamListener listener) {
        chatCommander(userLine, null, false, listener);
    }

    /**
     * Commander-via-CUBE chat: maximum compliance envelope + audit event id.
     * @param overrideOk biometric already confirmed for high-risk line
     */
    public void chatCommander(String userLine, String eventId, boolean overrideOk,
                              StreamListener listener) {
        if (listener == null) return;
        new Thread(() -> {
            try {
                // 1.85: never "cube cooling — chat paused" on user text.
                refreshHealth(true);
                if (!healthy) {
                    Log.w(TAG, "health probe failed; attempting chat anyway");
                }
                JSONObject body = CommanderChat.buildBody(userLine, eventId, overrideOk);
                String reply = PeerHttp.postBodyChat(
                    base + "/api/chat", body.toString(), 5000, 300000);
                if (reply == null) reply = "";
                try {
                    JSONObject j = new JSONObject(reply);
                    if (j.has("error") && !j.isNull("error")) {
                        listener.onError(new Exception(j.optString("error", "chat error")));
                        return;
                    }
                    if (j.has("reply")) reply = j.optString("reply", reply);
                    else if (j.has("content")) reply = j.optString("content", reply);
                    else if (j.has("output")) reply = j.optString("output", reply);
                    else if (j.has("text")) reply = j.optString("text", reply);
                } catch (Exception ignored) {}
                if (reply == null || reply.isEmpty()) reply = "(empty)";
                listener.onDelta(reply);
                listener.onDone(reply);
            } catch (Exception e) {
                Log.e(TAG, "chat: " + e.getMessage());
                listener.onError(e);
            }
        }, "cube-commander-chat").start();
    }
}
