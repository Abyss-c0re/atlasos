package com.titanus2.nanobot;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/** Local cache of outbound MCP server configs (mirrored to peer mcp_servers.json). */
public final class McpServerStore {
    private static final String P = "titan_nanobot_mcp_servers";

    private McpServerStore() {}

    public static JSONArray listArray(Context c) throws Exception {
        String raw = sp(c).getString("servers_json", "{\"servers\":[]}");
        JSONObject o = new JSONObject(raw);
        JSONArray a = o.optJSONArray("servers");
        return a != null ? a : new JSONArray();
    }

    public static void saveArray(Context c, JSONArray arr) throws Exception {
        JSONObject o = new JSONObject();
        o.put("servers", arr != null ? arr : new JSONArray());
        sp(c).edit().putString("servers_json", o.toString()).apply();
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(P, Context.MODE_PRIVATE);
    }
}
