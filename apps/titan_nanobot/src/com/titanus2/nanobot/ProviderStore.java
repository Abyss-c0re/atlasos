package com.titanus2.nanobot;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Multi-provider registry (encrypted JSON). Seeds Grok + optional local llama slot.
 */
public final class ProviderStore {
    private static final String KEY = "providers_v1";

    private ProviderStore() {}

    public static synchronized List<ProviderProfile> list(Context c) {
        ensureSeeded(c);
        ArrayList<ProviderProfile> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(SecureStore.getSecret(c, KEY));
            for (int i = 0; i < arr.length(); i++) {
                out.add(ProviderProfile.fromJson(arr.getJSONObject(i)));
            }
        } catch (Exception e) {
            out.clear();
        }
        Collections.sort(out, Comparator.comparingInt(a -> a.order));
        return out;
    }

    public static synchronized void saveAll(Context c, List<ProviderProfile> list) {
        try {
            JSONArray arr = new JSONArray();
            int i = 0;
            for (ProviderProfile p : list) {
                if (p.id == null || p.id.isEmpty()) p.id = newId();
                p.order = i++;
                arr.put(p.toJson(true));
            }
            SecureStore.putSecret(c, KEY, arr.toString());
        } catch (Exception ignored) {}
    }

    public static synchronized ProviderProfile get(Context c, String id) {
        if (id == null) return null;
        for (ProviderProfile p : list(c)) {
            if (id.equals(p.id)) return p;
        }
        return null;
    }

    public static synchronized void upsert(Context c, ProviderProfile p) {
        List<ProviderProfile> all = list(c);
        if (p.id == null || p.id.isEmpty()) p.id = newId();
        boolean found = false;
        for (int i = 0; i < all.size(); i++) {
            if (p.id.equals(all.get(i).id)) {
                all.set(i, p);
                found = true;
                break;
            }
        }
        if (!found) all.add(p);
        // role exclusivity for default/privacy (one primary each)
        if (p.roleDefault) {
            for (ProviderProfile x : all) if (!x.id.equals(p.id)) x.roleDefault = false;
        }
        if (p.rolePrivacy) {
            for (ProviderProfile x : all) if (!x.id.equals(p.id)) x.rolePrivacy = false;
        }
        saveAll(c, all);
        AccessLog.record(c, "provider_upsert", p.summaryLine(), p.id);
    }

    public static synchronized void delete(Context c, String id) {
        List<ProviderProfile> all = list(c);
        all.removeIf(p -> id != null && id.equals(p.id));
        saveAll(c, all);
        AccessLog.record(c, "provider_delete", "removed", id);
    }

    public static String newId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static void ensureSeeded(Context c) {
        String raw = SecureStore.getSecret(c, KEY);
        if (raw != null && raw.length() > 4) return;
        ArrayList<ProviderProfile> seed = new ArrayList<>();

        ProviderProfile grok = new ProviderProfile();
        grok.id = "grok";
        grok.name = "Grok (cloud)";
        grok.kind = "grok";
        grok.baseUrl = "https://cli-chat-proxy.grok.com/v1";
        grok.model = "grok-4.5";
        grok.roleDefault = true;
        grok.roleFallback = false;
        grok.rolePrivacy = false;
        grok.localOnly = false;
        grok.enabled = true;
        grok.order = 0;
        seed.add(grok);

        ProviderProfile local = new ProviderProfile();
        local.id = "llama_local";
        local.name = "On-device llama.cpp";
        local.kind = "llama_cpp";
        local.baseUrl = LlamaRuntime.baseUrl();
        local.model = "";
        local.roleDefault = false;
        local.rolePrivacy = true;  // privacy-critical → local by default
        local.roleFallback = true; // offline fallback
        local.localOnly = true;
        local.enabled = false;     // optional until user enables llama + model
        local.order = 1;
        seed.add(local);

        saveAll(c, seed);
        AccessLog.record(c, "providers_seeded", "grok + llama_local");
    }

    /**
     * Chat mode: remote | local only.
     * - remote = Grok cloud (default)
     * - local  = on-device llama
     * "auto" is accepted as alias of remote for old prefs.
     */
    public static String mode(Context c) {
        String m = SecureStore.getSecret(c, "provider_mode");
        if (m == null || m.isEmpty()) return "remote";
        m = m.toLowerCase();
        if ("local".equals(m)) return "local";
        // auto / remote / anything else → remote (Grok)
        return "remote";
    }

    public static void setMode(Context c, String mode) {
        if (mode == null) mode = "remote";
        mode = mode.toLowerCase();
        if ("auto".equals(mode)) mode = "remote";
        if (!mode.equals("local") && !mode.equals("remote")) mode = "remote";
        SecureStore.putSecret(c, "provider_mode", mode);
        AccessLog.record(c, "provider_mode", mode);
    }

    /**
     * Legacy flag — no longer steers chat routing (Remote/Local chips only).
     * Kept so settings UI does not crash; always false for routing purposes.
     */
    public static boolean privacyMode(Context c) {
        return false; // routing is Local|Remote only; never hijack Grok
    }

    public static void setPrivacyMode(Context c, boolean on) {
        // Persist for UI if needed, but routing ignores it
        SecureStore.putSecret(c, "privacy_route", on ? "1" : "0");
        AccessLog.record(c, "privacy_route_ignored",
            "chat routing is Local|Remote only — privacy flag does not override");
    }
}
