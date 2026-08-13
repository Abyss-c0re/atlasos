package com.titanus2.controls;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Temporary keymap layers on top of {@link KeyMapPrefs}.
 * Persisted so reboot mid-session can recover (HID should still pop on stop).
 * Used by {@link Titan2CoreService}.
 * <p>
 * <b>Effective resolution (highest first)</b> — product SoT 15.38:
 * <ol>
 *   <li><b>API client layers</b> (any temp layer except system silence) —
 *       topmost first. Only slots <em>present</em> in the layer count as a
 *       conflict; missing slots fall through.</li>
 *   <li><b>System silence</b> ({@code hid_session}) — phone chrome → none,
 *       computer pins kept.</li>
 *   <li>{@link KeyMapProfiles} foreground app overrides</li>
 *   <li>Global {@link KeyMapPrefs} (active permanent mappings)</li>
 * </ol>
 * So an API app that binds side→scroll wins over permanent Home; keys the
 * API never mentioned keep current Controls mappings (unless silence none).
 */
public final class TempKeyMapStack {
    private static final String TAG = "TempKeyMap";
    private static final String PREFS = "titan2_temp_keymap";
    private static final String KEY_ORDER = "layer_order";
    private static final String KEY_PREFIX = "layer_";

    /**
     * System silence layer id — always below API client layers in
     * {@link #getEffective}, even if stack order is wrong after refresh.
     */
    public static final String LAYER_SILENCE =
        com.titanus2.api.Titan2ApiContract.LAYER_HID_SESSION;

    private final SharedPreferences p;
    private final Context app;
    private final KeyMapProfiles profiles;

    public TempKeyMapStack(Context ctx) {
        app = ctx.getApplicationContext();
        p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        profiles = new KeyMapProfiles(app);
    }

    public synchronized void push(String layerId, Map<String, String> slotToAction) {
        if (layerId == null || layerId.isEmpty()) return;
        List<String> order = loadOrder();
        order.remove(layerId);
        // API client layers go on top; silence stays under all API layers so a
        // refreshSilenceIfActive cannot bury hid_side_keys / third-party maps.
        if (isSilenceLayer(layerId)) {
            int at = -1;
            for (int i = 0; i < order.size(); i++) {
                if (!isSilenceLayer(order.get(i))) {
                    at = i;
                    break;
                }
            }
            if (at >= 0) {
                order.add(at, layerId); // below first API client layer
            } else {
                order.add(layerId);
            }
        } else {
            order.add(layerId); // top
        }
        saveOrder(order);
        JSONObject o = new JSONObject();
        try {
            if (slotToAction != null) {
                for (Map.Entry<String, String> e : slotToAction.entrySet()) {
                    if (e.getKey() != null && e.getValue() != null) {
                        o.put(e.getKey(), e.getValue());
                    }
                }
            }
            p.edit().putString(KEY_PREFIX + layerId, o.toString()).apply();
        } catch (Exception e) {
            Log.w(TAG, "push fail: " + e.getMessage());
        }
        Log.i(TAG, "push layer=" + layerId + " slots=" + o.length()
            + " silence=" + isSilenceLayer(layerId));
    }

    /** True for system HID silence — not an API client overlay. */
    public static boolean isSilenceLayer(String layerId) {
        if (layerId == null) return false;
        return LAYER_SILENCE.equals(layerId) || "hid_session".equals(layerId);
    }

    /** API / guest layers (HID side keys, games, third parties). */
    public static boolean isApiClientLayer(String layerId) {
        return layerId != null && !layerId.isEmpty() && !isSilenceLayer(layerId);
    }

    public synchronized boolean pop(String layerId) {
        if (layerId == null) return false;
        List<String> order = loadOrder();
        boolean removed = order.remove(layerId);
        saveOrder(order);
        p.edit().remove(KEY_PREFIX + layerId).apply();
        Log.i(TAG, "pop layer=" + layerId + " removed=" + removed);
        return removed;
    }

    public synchronized List<String> layersBottomToTop() {
        return loadOrder();
    }

    /**
     * Effective action for a11y / live remaps.
     * API client layers (conflict) → silence → per-app → global.
     */
    public synchronized String getEffective(KeyMapPrefs base, String slotId) {
        return getEffective(base, slotId, TrackpadAccessService.foregroundPkg());
    }

    /**
     * @param fgPkg foreground package, or null to skip per-app layer
     */
    public synchronized String getEffective(KeyMapPrefs base, String slotId, String fgPkg) {
        if (slotId == null) {
            return base != null ? base.getAction(slotId) : KeyMapPrefs.ACT_DEFAULT;
        }
        List<String> order = loadOrder();
        // 1) API client layers only (top → bottom). Slot must be present.
        for (int i = order.size() - 1; i >= 0; i--) {
            String id = order.get(i);
            if (isSilenceLayer(id)) continue;
            Map<String, String> m = loadLayer(id);
            if (m != null && m.containsKey(slotId)) {
                return m.get(slotId);
            }
        }
        // 2) System silence (phone chrome off / computer pins)
        for (int i = order.size() - 1; i >= 0; i--) {
            String id = order.get(i);
            if (!isSilenceLayer(id)) continue;
            Map<String, String> m = loadLayer(id);
            if (m != null && m.containsKey(slotId)) {
                return m.get(slotId);
            }
        }
        // 3) Foreground per-app profile
        if (fgPkg != null && KeyMapProfiles.isEligiblePkg(fgPkg)) {
            String o = profiles.getOverride(fgPkg, slotId);
            if (o != null) return o;
        }
        // 4) Permanent / active Controls mappings
        return base.getAction(slotId);
    }

    /**
     * True when any temp layer explicitly maps this slot (including
     * {@link KeyMapPrefs#ACT_NONE}). Used so HID silence {@code none} still
     * swallows the key — keylayout must not fire APP_SWITCH / Back.
     */
    public synchronized boolean tempDefines(String slotId) {
        if (slotId == null) return false;
        List<String> order = loadOrder();
        // Same priority as getEffective: API first, then silence
        for (int i = order.size() - 1; i >= 0; i--) {
            String id = order.get(i);
            if (isSilenceLayer(id)) continue;
            Map<String, String> m = loadLayer(id);
            if (m != null && m.containsKey(slotId)) return true;
        }
        for (int i = order.size() - 1; i >= 0; i--) {
            String id = order.get(i);
            if (!isSilenceLayer(id)) continue;
            Map<String, String> m = loadLayer(id);
            if (m != null && m.containsKey(slotId)) return true;
        }
        return false;
    }

    /** True if any short/long/double slot for this scan is set by a temp layer. */
    public synchronized boolean tempDefinesScan(int scan) {
        int c = KeyMapPrefs.canonicalizeScan(scan);
        for (KeyMapPrefs.Press pr : KeyMapPrefs.Press.values()) {
            KeyMapPrefs.Slot sl = KeyMapPrefs.slotByScan(c, pr);
            if (sl != null && tempDefines(sl.id)) return true;
        }
        return false;
    }

    public synchronized boolean hasLayer(String layerId) {
        if (layerId == null) return false;
        return loadOrder().contains(layerId);
    }

    /**
     * Build HID/remote silence layer: phone shortcuts → none, computer
     * actions pinned (global or any profile). Safe to call mid-session when
     * the user reassigns side buttons to mouse.
     * <p>
     * Never silence Sym/Alt/Fn layout keys — {@code none} made a11y swallow
     * them so OS KCM specials (Sym+letter → !@#) died while HID exclusive
     * still looked fine (host saw raw RAlt).
     * <p>
     * Never silence upper phone-nav chrome (Back / Recents) — exclusive grab
     * re-injects them via {@code titan2-phone-nav} uinput (hid_bridge 0.16.8+).
     * Silencing those scans made a11y consume the reinject so the master
     * phone could not leave apps while letters went to the host (FB-HID-1).
     */
    public static Map<String, String> buildSilenceMap(KeyMapPrefs base,
                                                      KeyMapProfiles profiles) {
        return buildSilenceMap(base, profiles, null);
    }

    public static Map<String, String> buildSilenceMap(KeyMapPrefs base,
                                                      KeyMapProfiles profiles,
                                                      Context ctx) {
        Map<String, String> silence = new LinkedHashMap<>();
        for (KeyMapPrefs.Slot s : KeyMapPrefs.SLOTS) {
            String computer = resolveComputerAction(base, profiles, s.id);
            if (computer != null) {
                silence.put(s.id, computer);
                continue;
            }
            // Layout hold/toggle (incl. custom) — keep so modifiers work under HID.
            String baseAct = base != null ? base.getAction(s.id) : null;
            if (KeyMapPrefs.isLayoutAction(baseAct)) {
                silence.put(s.id, baseAct);
                continue;
            }
            // Sym/Alt/Fn layout keys: omit → inherit DEFAULT → pass to keylayout/KCM
            if (KeyMapPrefs.isSymScan(s.scan) || KeyMapPrefs.isAltScan(s.scan)
                    || KeyMapPrefs.isFnScan(s.scan)) {
                continue;
            }
            if (ctx != null && KeyMapPrefs.isCharModScan(ctx, s.scan)) {
                continue;
            }
            // FB-HID-1: phone chrome nav stays on master under exclusive grab
            // (phone-nav reinject + share residual). Do not ACT_NONE swallow.
            if (isPhoneNavChromeScan(s.scan)) {
                continue;
            }
            silence.put(s.id, KeyMapPrefs.ACT_NONE);
        }
        return silence;
    }

    /**
     * Upper-row / product nav that must reach the Titan phone under exclusive
     * HID (hid_bridge phone-nav bypass list SoT).
     */
    public static boolean isPhoneNavChromeScan(int scan) {
        int c = KeyMapPrefs.canonicalizeScan(scan);
        return c == KeyMapPrefs.SCAN_BACK || c == KeyMapPrefs.SCAN_APP_SWITCH;
    }

    /** Global computer action, else first profile that maps this slot to one. */
    public static String resolveComputerAction(KeyMapPrefs base, KeyMapProfiles profiles,
                                               String slotId) {
        if (base != null) {
            String g = base.getAction(slotId);
            if (KeyMapPrefs.isComputerAction(g)) return g;
        }
        if (profiles == null) return null;
        for (String pkg : profiles.listPackages()) {
            String o = profiles.getOverride(pkg, slotId);
            if (KeyMapPrefs.isComputerAction(o)) return o;
        }
        return null;
    }

    /**
     * If {@code hid_session} (or {@code layerId}) is active, rebuild pins from
     * current prefs/profiles and republish to pad-agent.
     */
    public synchronized void refreshSilenceIfActive(Context ctx, KeyMapPrefs base,
                                                    String layerId) {
        if (layerId == null || layerId.isEmpty()) {
            layerId = com.titanus2.api.Titan2ApiContract.LAYER_HID_SESSION;
        }
        if (!hasLayer(layerId)) return;
        KeyMapProfiles prof = profiles != null ? profiles : new KeyMapProfiles(app);
        push(layerId, buildSilenceMap(base, prof, ctx != null ? ctx : app));
        publishEffective(ctx, base);
        Log.i(TAG, "refreshed silence layer=" + layerId);
    }

    /**
     * Drop stuck HID temp layers when the physical session is off.
     * <p>
     * Product: HID remap override is <b>if and only if</b> the USB HID session
     * is running. Process death / offline stop without pop used to leave
     * {@code hid_session} + {@code hid_host} forever (sides stuck, chrome
     * wrong). Clear <em>both</em> when session plane is off.
     *
     * @return true if any stale HID layer was removed
     */
    public synchronized boolean clearStaleHidSilence(Context ctx, KeyMapPrefs base) {
        String silenceId = com.titanus2.api.Titan2ApiContract.LAYER_HID_SESSION;
        String hostId = com.titanus2.api.Titan2ApiContract.LAYER_HID_HOST;
        boolean silencePresent = hasLayer(silenceId);
        boolean hostPresent = hasLayer(hostId)
            || hasLayer(com.titanus2.api.Titan2ApiContract.LAYER_HID_SIDE_KEYS);
        if (!silencePresent && !hostPresent) return false;
        if (hidSessionLive(ctx)) {
            // Session still claimed — refresh silence pins; keep host layer
            if (silencePresent) {
                refreshSilenceIfActive(ctx, base, silenceId);
            }
            return false;
        }
        boolean removed = false;
        if (silencePresent) removed |= pop(silenceId);
        if (hasLayer(hostId)) removed |= pop(hostId);
        // Legacy alias (same string as LAYER_HID_HOST today; safe if diverged)
        String sideAlias = com.titanus2.api.Titan2ApiContract.LAYER_HID_SIDE_KEYS;
        if (!hostId.equals(sideAlias) && hasLayer(sideAlias)) {
            removed |= pop(sideAlias);
        }
        publishEffective(ctx, base);
        Log.i(TAG, "cleared stale HID layers (session off) removed=" + removed
            + " silence=" + silencePresent + " host=" + hostPresent);
        return removed;
    }

    /**
     * True when a physical HID session is on.
     * Prefer HID app-private CE (session owner). Stale
     * {@code /data/local/tmp} session=1 after app death used to keep silence
     * forever and break OS Sym specials.
     */
    public static boolean hidSessionLive(Context ctx) {
        String ce = null;
        for (String path : new String[]{
            "/data/user/0/com.titanus2.usbhid/files/titan2_usb_hid_session",
            "/data/data/com.titanus2.usbhid/files/titan2_usb_hid_session"
        }) {
            try {
                java.io.File f = new java.io.File(path);
                if (!f.isFile()) continue;
                ce = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8).trim();
                if ("1".equals(ce) || "true".equalsIgnoreCase(ce) || "on".equalsIgnoreCase(ce))
                    return true;
                if ("0".equals(ce) || "false".equalsIgnoreCase(ce) || "off".equalsIgnoreCase(ce)
                        || ce.isEmpty()) {
                    // CE says off — trust it over stale tmp leftovers
                    return false;
                }
            } catch (Exception ignored) {}
        }
        String v = AgentBridge.get(ctx, "titan2_usb_hid_session", "0");
        if (v == null) v = "0";
        v = v.trim();
        return "1".equals(v) || "true".equalsIgnoreCase(v) || "on".equalsIgnoreCase(v);
    }

    /**
     * Publish base + temp to pad-agent (screen-off path).
     * Per-app is not published — no reliable foreground while display is off.
     * Temp layers (incl. HID silence with computer mouse:*) are included.
     */
    public synchronized void publishEffective(Context ctx, KeyMapPrefs base) {
        for (KeyMapPrefs.Slot s : KeyMapPrefs.SLOTS) {
            String act = getEffective(base, s.id, null);
            AgentBridge.put(ctx, "titan2_km_" + s.id, act);
        }
        AgentBridge.put(ctx, "titan2_km_enabled", base.isEnabled() ? "1" : "0");
        AgentBridge.put(ctx, "titan2_km_screen_off", base.isAllowScreenOff() ? "1" : "0");
    }

    private List<String> loadOrder() {
        List<String> out = new ArrayList<>();
        String raw = p.getString(KEY_ORDER, "[]");
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                String id = a.optString(i, null);
                if (id != null && !id.isEmpty()) out.add(id);
            }
        } catch (Exception ignored) {}
        return out;
    }

    private void saveOrder(List<String> order) {
        JSONArray a = new JSONArray();
        for (String id : order) a.put(id);
        p.edit().putString(KEY_ORDER, a.toString()).apply();
    }

    private Map<String, String> loadLayer(String layerId) {
        String raw = p.getString(KEY_PREFIX + layerId, null);
        if (raw == null || raw.isEmpty()) return null;
        Map<String, String> m = new LinkedHashMap<>();
        try {
            JSONObject o = new JSONObject(raw);
            java.util.Iterator<String> keys = o.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                m.put(k, o.optString(k, null));
            }
        } catch (Exception e) {
            return null;
        }
        return m;
    }
}
