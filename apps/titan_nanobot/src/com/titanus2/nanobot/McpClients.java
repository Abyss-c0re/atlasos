package com.titanus2.nanobot;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Paired MCP / API clients (encrypted registry). Approval requires biometric gate. */
public final class McpClients {
    private McpClients() {}

    public static final class Client {
        public String id;
        public String name;
        public String createdAt;
        public String lastAccess;
        public boolean active;
    }

    public static List<Client> list(Context c) {
        ArrayList<Client> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(SecureStore.getClientsJson(c));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Client cl = new Client();
                cl.id = o.optString("id", "");
                cl.name = o.optString("name", "client");
                cl.createdAt = o.optString("created_at", "");
                cl.lastAccess = o.optString("last_access", "");
                cl.active = o.optBoolean("active", true);
                out.add(cl);
            }
        } catch (Exception ignored) {}
        return out;
    }

    public static Client approve(Context c, String name) throws Exception {
        if (name == null || name.trim().isEmpty()) name = "MCP client";
        name = name.trim();
        Client cl = new Client();
        cl.id = UUID.randomUUID().toString().substring(0, 8);
        cl.name = name;
        cl.createdAt = AccessLog.class.getSimpleName(); // placeholder fixed below
        cl.createdAt = new java.text.SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(new java.util.Date());
        cl.lastAccess = "";
        cl.active = true;

        JSONArray arr;
        try {
            arr = new JSONArray(SecureStore.getClientsJson(c));
        } catch (Exception e) {
            arr = new JSONArray();
        }
        JSONObject o = new JSONObject();
        o.put("id", cl.id);
        o.put("name", cl.name);
        o.put("created_at", cl.createdAt);
        o.put("last_access", "");
        o.put("active", true);
        // random client label secret (for future multi-token); master peer token is shared
        byte[] rnd = new byte[16];
        new SecureRandom().nextBytes(rnd);
        StringBuilder hx = new StringBuilder();
        for (byte b : rnd) hx.append(String.format(Locale.US, "%02x", b));
        o.put("client_secret_id", hx.toString());
        arr.put(o);
        SecureStore.setClientsJson(c, arr.toString());
        AccessLog.record(c, "mcp_pair_approved",
            "Paired MCP client \"" + cl.name + "\"", cl.id);
        return cl;
    }

    public static void revoke(Context c, String id) throws Exception {
        JSONArray arr = new JSONArray(SecureStore.getClientsJson(c));
        JSONArray next = new JSONArray();
        String name = id;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            if (id != null && id.equals(o.optString("id"))) {
                name = o.optString("name", id);
                o.put("active", false);
                o.put("revoked_at", new java.text.SimpleDateFormat(
                    "yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(new java.util.Date()));
                // keep record for history
            }
            next.put(o);
        }
        SecureStore.setClientsJson(c, next.toString());
        AccessLog.record(c, "mcp_pair_revoked", "Revoked MCP client \"" + name + "\"", id);
    }

    public static void touchAccess(Context c, String id, String how) {
        try {
            JSONArray arr = new JSONArray(SecureStore.getClientsJson(c));
            String now = new java.text.SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(new java.util.Date());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (id != null && id.equals(o.optString("id"))) {
                    o.put("last_access", now);
                    AccessLog.record(c, "mcp_access",
                        how == null ? "token used/shown" : how, id);
                    break;
                }
            }
            SecureStore.setClientsJson(c, arr.toString());
        } catch (Exception ignored) {}
    }

    public static String connectionGuide(Context c, Client cl, String url, String token) {
        StringBuilder sb = new StringBuilder();
        sb.append("MCP / API client: ").append(cl.name).append(" (").append(cl.id).append(")\n\n");
        sb.append("This phone shares the on-device agent over Wi‑Fi.\n\n");
        sb.append("1) Base URL (same Wi‑Fi as phone):\n   ").append(url).append("\n\n");
        sb.append("2) Auth header (required for chat/shell/MCP bridge):\n");
        sb.append("   X-Nanobot-Peer-Token: ").append(token == null ? "(none)" : token).append("\n\n");
        sb.append("3) Health check (no token):\n   GET ").append(url).append("/peer/v1/health\n\n");
        sb.append("4) Chat:\n   POST ").append(url).append("/api/chat\n");
        sb.append("   {\"prompt\":\"hello\"} + peer token header\n\n");
        sb.append("5) MCP bridge (host):\n");
        sb.append("   scripts/peer_mcp_bridge.py with NANOBOT_URL + peer token\n\n");
        sb.append("Token is device-bound. Revoke this client in the app if lost.\n");
        sb.append("Provider login stays sealed on the phone (not sent to MCP clients).\n");
        return sb.toString();
    }
}
