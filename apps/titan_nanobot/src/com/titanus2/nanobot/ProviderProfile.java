package com.titanus2.nanobot;

import org.json.JSONObject;

/**
 * One LLM endpoint the app can route to.
 * kind: grok | openai | llama_cpp | custom
 * Roles are soft tags used by {@link ProviderRouter}.
 */
public final class ProviderProfile {
    public String id;
    public String name;
    public String kind;       // grok | openai | llama_cpp | custom
    public String baseUrl;    // e.g. https://api.openai.com/v1 or http://127.0.0.1:8080/v1
    public String model;
    public boolean enabled = true;
    /** Encrypted at rest via ProviderStore; never log. */
    public String apiKey;
    /** Use for normal chat when selected as default. */
    public boolean roleDefault;
    /** Prefer for privacy-critical / offline-sensitive work (usually local). */
    public boolean rolePrivacy;
    /** Tried if primary fails (network/auth). */
    public boolean roleFallback;
    /** On-device or same LAN only (never leave phone). */
    public boolean localOnly;
    public int order;

    public ProviderProfile() {}

    public JSONObject toJson(boolean includeSecret) throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("name", name);
        o.put("kind", kind);
        o.put("base_url", baseUrl == null ? "" : baseUrl);
        o.put("model", model == null ? "" : model);
        o.put("enabled", enabled);
        o.put("role_default", roleDefault);
        o.put("role_privacy", rolePrivacy);
        o.put("role_fallback", roleFallback);
        o.put("local_only", localOnly);
        o.put("order", order);
        if (includeSecret) o.put("api_key", apiKey == null ? "" : apiKey);
        else o.put("has_key", apiKey != null && !apiKey.isEmpty());
        return o;
    }

    public static ProviderProfile fromJson(JSONObject o) {
        ProviderProfile p = new ProviderProfile();
        if (o == null) return p;
        p.id = o.optString("id", "");
        p.name = o.optString("name", "provider");
        p.kind = o.optString("kind", "custom");
        p.baseUrl = o.optString("base_url", "");
        p.model = o.optString("model", "");
        p.enabled = o.optBoolean("enabled", true);
        p.roleDefault = o.optBoolean("role_default", false);
        p.rolePrivacy = o.optBoolean("role_privacy", false);
        p.roleFallback = o.optBoolean("role_fallback", false);
        p.localOnly = o.optBoolean("local_only", false);
        p.order = o.optInt("order", 0);
        p.apiKey = o.optString("api_key", "");
        if (p.apiKey.isEmpty()) p.apiKey = null;
        return p;
    }

    public boolean isGrok() {
        return "grok".equalsIgnoreCase(kind) || "cloud".equalsIgnoreCase(kind);
    }

    public boolean isLocalStack() {
        return localOnly || "llama_cpp".equalsIgnoreCase(kind)
            || (baseUrl != null && (baseUrl.contains("127.0.0.1") || baseUrl.contains("localhost")));
    }

    /** Backend string for nanobot /api/settings. */
    public String nanobotBackend() {
        if (isGrok()) return "grok";
        if ("llama_cpp".equalsIgnoreCase(kind)) return "local";
        return "openai_compatible";
    }

    public String summaryLine() {
        String roles = "";
        if (roleDefault) roles += " default";
        if (rolePrivacy) roles += " privacy";
        if (roleFallback) roles += " fallback";
        if (roles.isEmpty()) roles = " (no role)";
        return name + " · " + kind + " · " + (enabled ? "ON" : "off") + " ·" + roles.trim();
    }
}
