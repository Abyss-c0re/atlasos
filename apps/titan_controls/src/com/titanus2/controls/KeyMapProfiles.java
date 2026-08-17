package com.titanus2.controls;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Per-app key remap overrides on top of global {@link KeyMapPrefs}.
 * <p>
 * Priority (highest first): {@link TempKeyMapStack} → this profile → global.
 * Sparse: only slots / chords with an explicit override are stored; missing = inherit.
 * <p>
 * Hold + key: optional per-app hold scan + sparse chords (same model as
 * {@link MagicKeyPrefs}, scoped to the foreground package).
 */
public final class KeyMapProfiles {
    private static final String PREFS = "titan2_keymap_profiles";
    private static final String KEY_ORDER = "pkg_order";
    private static final String KEY_PREFIX = "p_";

    /** No per-app hold key — use global {@link MagicKeyPrefs#getScan()}. */
    public static final int MAGIC_INHERIT = Integer.MIN_VALUE;

    private final SharedPreferences p;
    private final Context app;

    public KeyMapProfiles(Context ctx) {
        app = ctx.getApplicationContext();
        p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Packages with a profile, oldest first. */
    public List<String> listPackages() {
        List<String> out = new ArrayList<>();
        String raw = p.getString(KEY_ORDER, "[]");
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                String pkg = a.optString(i, null);
                if (pkg != null && !pkg.isEmpty()) out.add(pkg);
            }
        } catch (Exception ignored) {}
        return out;
    }

    public boolean hasProfile(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        return p.contains(KEY_PREFIX + pkg) || listPackages().contains(pkg);
    }

    /** Ensure package is in the profile list (empty overrides OK). */
    public void ensureProfile(String pkg, String label) {
        if (pkg == null || pkg.isEmpty()) return;
        List<String> order = listPackages();
        if (!order.contains(pkg)) {
            order.add(pkg);
            saveOrder(order);
        }
        JSONObject o = loadRaw(pkg);
        if (o == null) o = new JSONObject();
        try {
            if (label != null && !label.isEmpty() && !o.has("label")) {
                o.put("label", label);
            }
            if (!o.has("slots")) o.put("slots", new JSONObject());
            p.edit().putString(KEY_PREFIX + pkg, o.toString()).apply();
        } catch (Exception ignored) {}
    }

    public void removeProfile(String pkg) {
        if (pkg == null) return;
        List<String> order = listPackages();
        order.remove(pkg);
        saveOrder(order);
        p.edit().remove(KEY_PREFIX + pkg).apply();
    }

    public String getLabel(String pkg) {
        JSONObject o = loadRaw(pkg);
        if (o != null) {
            String l = o.optString("label", "");
            if (l != null && !l.isEmpty()) return l;
        }
        return resolveAppLabel(pkg);
    }

    public void setLabel(String pkg, String label) {
        if (pkg == null) return;
        ensureProfile(pkg, null);
        JSONObject o = loadRaw(pkg);
        if (o == null) o = new JSONObject();
        try {
            o.put("label", label == null ? "" : label);
            p.edit().putString(KEY_PREFIX + pkg, o.toString()).apply();
        } catch (Exception ignored) {}
    }

    /**
     * Override for slot, or {@code null} if this profile does not override
     * (inherit global / temp).
     */
    public String getOverride(String pkg, String slotId) {
        if (pkg == null || slotId == null) return null;
        if (!isEligiblePkg(pkg)) return null;
        JSONObject slots = slotsOf(pkg);
        if (slots == null || !slots.has(slotId)) return null;
        String a = slots.optString(slotId, null);
        return (a == null || a.isEmpty()) ? null : a;
    }

    /** Set override. Pass null to clear (inherit). */
    public void setOverride(String pkg, String slotId, String action) {
        if (pkg == null || slotId == null) return;
        ensureProfile(pkg, null);
        JSONObject o = loadRaw(pkg);
        if (o == null) o = new JSONObject();
        try {
            JSONObject slots = o.optJSONObject("slots");
            if (slots == null) {
                slots = new JSONObject();
                o.put("slots", slots);
            }
            if (action == null || action.isEmpty()) {
                slots.remove(slotId);
            } else {
                slots.put(slotId, action);
            }
            p.edit().putString(KEY_PREFIX + pkg, o.toString()).apply();
        } catch (Exception ignored) {}
        // Live exclusive HID: keep API host layer in sync with this profile.
        maybeRefreshHidHostLayer(pkg);
    }

    private void maybeRefreshHidHostLayer(String pkg) {
        if (!com.titanus2.api.Titan2ApiContract.HID_HOST_PKG.equals(pkg)) return;
        try {
            TempKeyMapStack stack = new TempKeyMapStack(app);
            // Only while physical HID session — never re-stick host layer idle.
            if (!TempKeyMapStack.hidSessionLive(app)) {
                stack.clearStaleHidSilence(app, new KeyMapPrefs(app));
                return;
            }
            if (!stack.hasLayer(com.titanus2.api.Titan2ApiContract.LAYER_HID_HOST)) {
                return;
            }
            stack.push(com.titanus2.api.Titan2ApiContract.LAYER_HID_HOST, snapshot(pkg));
            stack.publishEffective(app, new KeyMapPrefs(app));
        } catch (Exception ignored) {}
    }

    public void clearOverride(String pkg, String slotId) {
        setOverride(pkg, slotId, null);
    }

    /** Per-app act-as-key remap: short = action, long = none. Double left alone. */
    public void assignActAsKey(String pkg, int scan, String action) {
        KeyMapPrefs.Slot sh = KeyMapPrefs.slotByScan(scan, KeyMapPrefs.Press.SHORT);
        KeyMapPrefs.Slot lo = KeyMapPrefs.slotByScan(scan, KeyMapPrefs.Press.LONG);
        if (action == null || KeyMapPrefs.ACT_NONE.equals(action)
                || KeyMapPrefs.ACT_DEFAULT.equals(action)) {
            if (sh != null) setOverride(pkg, sh.id, KeyMapPrefs.ACT_NONE);
            if (lo != null) setOverride(pkg, lo.id, KeyMapPrefs.ACT_NONE);
            return;
        }
        if (!KeyMapPrefs.isActAsKeyAction(action)) return;
        if (sh != null) setOverride(pkg, sh.id, action);
        if (lo != null) setOverride(pkg, lo.id, KeyMapPrefs.ACT_NONE);
    }

    /**
     * Per-app specials method: {@code inject} | {@code kcm} | {@code null}/empty = inherit global.
     */
    public String getSpecialsMethodOverride(String pkg) {
        if (pkg == null || !isEligiblePkg(pkg)) return null;
        JSONObject o = loadRaw(pkg);
        if (o == null) return null;
        String m = o.optString("specials_method", "");
        if (m == null || m.isEmpty() || "inherit".equalsIgnoreCase(m)) return null;
        m = m.trim().toLowerCase();
        if (KeyMapPrefs.SPECIALS_METHOD_KCM.equals(m)
                || KeyMapPrefs.SPECIALS_METHOD_INJECT.equals(m)) {
            return m;
        }
        return null;
    }

    public void setSpecialsMethodOverride(String pkg, String method) {
        if (pkg == null) return;
        ensureProfile(pkg, null);
        JSONObject o = loadRaw(pkg);
        if (o == null) o = new JSONObject();
        try {
            if (method == null || method.isEmpty() || "inherit".equalsIgnoreCase(method)) {
                o.remove("specials_method");
            } else {
                String m = method.trim().toLowerCase();
                if (!KeyMapPrefs.SPECIALS_METHOD_KCM.equals(m)
                        && !KeyMapPrefs.SPECIALS_METHOD_INJECT.equals(m)) {
                    m = KeyMapPrefs.SPECIALS_METHOD_KCM;
                }
                o.put("specials_method", m);
            }
            p.edit().putString(KEY_PREFIX + pkg, o.toString()).apply();
        } catch (Exception ignored) {}
    }

    /** Effective specials method for pkg (profile override or global inject/kcm). */
    public String resolveSpecialsMethod(String pkg) {
        String o = getSpecialsMethodOverride(pkg);
        if (o != null) return o;
        return KeyMapPrefs.getSpecialsMethod(app);
    }

    public boolean isSpecialsInject(String pkg) {
        return KeyMapPrefs.SPECIALS_METHOD_INJECT.equals(resolveSpecialsMethod(pkg));
    }

    /** Slots with an explicit override (any action including none). */
    public List<KeyMapPrefs.Slot> listOverriddenSlots(String pkg) {
        List<KeyMapPrefs.Slot> out = new ArrayList<>();
        JSONObject slots = slotsOf(pkg);
        if (slots == null) return out;
        for (KeyMapPrefs.Slot s : KeyMapPrefs.SLOTS) {
            if (slots.has(s.id)) out.add(s);
        }
        return out;
    }

    /**
     * Per-app override rows with B3 layout collapse: one tile per physical key
     * when that key is a layout hold and/or toggle (not three press rows).
     */
    public List<KeyMapPrefs.ShortcutRow> listVisibleRows(String pkg) {
        List<KeyMapPrefs.ShortcutRow> rows = new ArrayList<>();
        JSONObject slots = slotsOf(pkg);
        if (slots == null) return rows;
        java.util.HashSet<Integer> layoutScans = new java.util.HashSet<>();
        java.util.LinkedHashSet<Integer> order = new java.util.LinkedHashSet<>();
        for (KeyMapPrefs.Slot s : KeyMapPrefs.SLOTS) {
            if (!slots.has(s.id)) continue;
            String act = slots.optString(s.id, null);
            if (KeyMapPrefs.layoutHoldId(act) != null
                    || KeyMapPrefs.layoutToggleId(act) != null) {
                order.add(KeyMapPrefs.canonicalizeScan(s.scan));
            }
        }
        for (int c : order) {
            KeyMapPrefs.Slot lo = KeyMapPrefs.slotByScan(c, KeyMapPrefs.Press.LONG);
            KeyMapPrefs.Slot db = KeyMapPrefs.slotByScan(c, KeyMapPrefs.Press.DOUBLE);
            String holdAct = (lo != null && slots.has(lo.id))
                ? slots.optString(lo.id, null) : null;
            String togAct = (db != null && slots.has(db.id))
                ? slots.optString(db.id, null) : null;
            String id = KeyMapPrefs.layoutIdFromActions(holdAct, togAct);
            if (id == null) continue;
            KeyMapPrefs.Slot loOut =
                (lo != null && KeyMapPrefs.layoutHoldId(holdAct) != null) ? lo : null;
            KeyMapPrefs.Slot dbOut =
                (db != null && KeyMapPrefs.layoutToggleId(togAct) != null) ? db : null;
            layoutScans.add(c);
            rows.add(new KeyMapPrefs.ShortcutRow(c, id, loOut, dbOut));
        }
        for (KeyMapPrefs.Slot s : KeyMapPrefs.SLOTS) {
            if (!slots.has(s.id)) continue;
            int c = KeyMapPrefs.canonicalizeScan(s.scan);
            if (layoutScans.contains(c)) continue;
            String act = slots.optString(s.id, null);
            if (act == null || act.isEmpty()
                    || KeyMapPrefs.ACT_NONE.equals(act)
                    || KeyMapPrefs.ACT_DEFAULT.equals(act)) continue;
            if (KeyMapPrefs.layoutHoldId(act) != null
                    || KeyMapPrefs.layoutToggleId(act) != null) continue;
            if (s.press == KeyMapPrefs.Press.LONG) {
                KeyMapPrefs.Slot sh = KeyMapPrefs.slotByScan(c, KeyMapPrefs.Press.SHORT);
                String shAct = (sh != null && slots.has(sh.id))
                    ? slots.optString(sh.id, null) : null;
                if (KeyMapPrefs.isActAsKeyAction(shAct)) continue;
            }
            rows.add(new KeyMapPrefs.ShortcutRow(s));
        }
        return rows;
    }

    public int overrideCount(String pkg) {
        JSONObject slots = slotsOf(pkg);
        return slots == null ? 0 : slots.length();
    }

    public int chordCount(String pkg) {
        return listChords(pkg).size();
    }

    /** Slot overrides + hold chords (for list summaries). */
    public int bindingCount(String pkg) {
        return overrideCount(pkg) + chordCount(pkg);
    }

    public Map<String, String> snapshot(String pkg) {
        Map<String, String> m = new LinkedHashMap<>();
        JSONObject slots = slotsOf(pkg);
        if (slots == null) return m;
        Iterator<String> it = slots.keys();
        while (it.hasNext()) {
            String k = it.next();
            m.put(k, slots.optString(k, null));
        }
        return m;
    }

    // ---- Hold + key (per-app) ----

    /**
     * Per-app hold key scan.
     * {@link #MAGIC_INHERIT} = use global; {@code 0} = hold off in this app; {@code >0} = that scan.
     */
    public int getMagicScan(String pkg) {
        JSONObject o = loadRaw(pkg);
        if (o == null || !o.has("magic_scan")) return MAGIC_INHERIT;
        return o.optInt("magic_scan", MAGIC_INHERIT);
    }

    /** Pass {@link #MAGIC_INHERIT} to clear (inherit global). */
    public void setMagicScan(String pkg, int scan) {
        if (pkg == null) return;
        ensureProfile(pkg, null);
        JSONObject o = loadRaw(pkg);
        if (o == null) o = new JSONObject();
        try {
            if (scan == MAGIC_INHERIT) {
                o.remove("magic_scan");
            } else {
                o.put("magic_scan", scan <= 0 ? 0 : KeyMapPrefs.canonicalizeScan(scan));
            }
            p.edit().putString(KEY_PREFIX + pkg, o.toString()).apply();
        } catch (Exception ignored) {}
    }

    /**
     * Effective hold key while {@code pkg} is foreground.
     * @return scan code, or 0 if hold is off
     */
    public int resolveMagicScan(String pkg, int globalScan) {
        if (pkg == null || !isEligiblePkg(pkg)) return globalScan;
        int o = getMagicScan(pkg);
        if (o == MAGIC_INHERIT) return globalScan;
        return o <= 0 ? 0 : o;
    }

    /**
     * Per-app long-hold letter → specials glyph (no Sym).
     * {@code null} = inherit global {@link KeyMapPrefs#isLongPressSpecials()}.
     */
    public Boolean getLongPressSpecialsOverride(String pkg) {
        JSONObject o = loadRaw(pkg);
        if (o == null || !o.has("long_press_specials")) return null;
        return o.optBoolean("long_press_specials", false);
    }

    /**
     * @param on {@code null} to inherit global; true/false to force for this app
     */
    public void setLongPressSpecialsOverride(String pkg, Boolean on) {
        if (pkg == null) return;
        ensureProfile(pkg, null);
        JSONObject o = loadRaw(pkg);
        if (o == null) o = new JSONObject();
        try {
            if (on == null) {
                o.remove("long_press_specials");
            } else {
                o.put("long_press_specials", on.booleanValue());
            }
            p.edit().putString(KEY_PREFIX + pkg, o.toString()).apply();
        } catch (Exception ignored) {}
    }

    /**
     * Effective long-hold-letter→special for foreground package.
     */
    public boolean resolveLongPressSpecials(String pkg, boolean globalOn) {
        // 12.04: long-hold letter→specials purged — always off
        return false;
    }

    /**
     * Per-app host layout default: {@link HostLayoutController#MODE_INHERIT},
     * {@code off}, {@code specials}, or {@code arrows}.
     */
    public String getLayoutMode(String pkg) {
        JSONObject o = loadRaw(pkg);
        if (o == null || !o.has("layout_mode")) return HostLayoutController.MODE_INHERIT;
        String m = o.optString("layout_mode", HostLayoutController.MODE_INHERIT);
        return HostLayoutController.normalize(
            m == null || m.isEmpty() ? HostLayoutController.MODE_INHERIT : m);
    }

    /** Pass inherit/null to clear (use global default). */
    public void setLayoutMode(String pkg, String mode) {
        if (pkg == null) return;
        ensureProfile(pkg, null);
        JSONObject o = loadRaw(pkg);
        if (o == null) o = new JSONObject();
        try {
            String m = mode == null ? HostLayoutController.MODE_INHERIT
                : HostLayoutController.normalize(mode);
            if (HostLayoutController.MODE_INHERIT.equals(m)) {
                o.remove("layout_mode");
            } else {
                o.put("layout_mode", m);
            }
            p.edit().putString(KEY_PREFIX + pkg, o.toString()).apply();
        } catch (Exception ignored) {}
    }

    /**
     * Chord override for this app, or {@code null} to inherit global chord.
     */
    public String getChord(String pkg, int scan, int keyCode) {
        if (pkg == null || !isEligiblePkg(pkg)) return null;
        JSONObject chords = chordsOf(pkg);
        if (chords == null) return null;
        int c = KeyMapPrefs.canonicalizeScan(scan);
        if (c > 0 && chords.has("s_" + c)) {
            String a = chords.optString("s_" + c, null);
            return (a == null || a.isEmpty()) ? null : a;
        }
        if (keyCode > 0 && chords.has("k_" + keyCode)) {
            String a = chords.optString("k_" + keyCode, null);
            return (a == null || a.isEmpty()) ? null : a;
        }
        return null;
    }

    public void setChordByScan(String pkg, int scan, String action) {
        int c = KeyMapPrefs.canonicalizeScan(scan);
        if (c <= 0) return;
        putChord(pkg, "s_" + c, action);
    }

    public void setChordByKeyCode(String pkg, int keyCode, String action) {
        if (keyCode <= 0) return;
        putChord(pkg, "k_" + keyCode, action);
    }

    public void clearChord(String pkg, boolean byScan, int id) {
        if (byScan) setChordByScan(pkg, id, null);
        else setChordByKeyCode(pkg, id, null);
    }

    public List<MagicKeyPrefs.ChordEntry> listChords(String pkg) {
        List<MagicKeyPrefs.ChordEntry> out = new ArrayList<>();
        JSONObject chords = chordsOf(pkg);
        if (chords == null) return out;
        Iterator<String> it = chords.keys();
        while (it.hasNext()) {
            String k = it.next();
            String act = chords.optString(k, null);
            if (act == null || act.isEmpty()) continue;
            if (k.startsWith("s_")) {
                try {
                    out.add(new MagicKeyPrefs.ChordEntry(
                        true, Integer.parseInt(k.substring(2)), act));
                } catch (NumberFormatException ignored) {}
            } else if (k.startsWith("k_")) {
                try {
                    out.add(new MagicKeyPrefs.ChordEntry(
                        false, Integer.parseInt(k.substring(2)), act));
                } catch (NumberFormatException ignored) {}
            }
        }
        return out;
    }

    private void putChord(String pkg, String key, String action) {
        if (pkg == null || key == null) return;
        ensureProfile(pkg, null);
        JSONObject o = loadRaw(pkg);
        if (o == null) o = new JSONObject();
        try {
            JSONObject chords = o.optJSONObject("chords");
            if (chords == null) {
                chords = new JSONObject();
                o.put("chords", chords);
            }
            if (action == null || action.isEmpty() || KeyMapPrefs.ACT_DEFAULT.equals(action)) {
                chords.remove(key);
            } else {
                chords.put(key, action);
            }
            p.edit().putString(KEY_PREFIX + pkg, o.toString()).apply();
        } catch (Exception ignored) {}
    }

    private JSONObject chordsOf(String pkg) {
        JSONObject o = loadRaw(pkg);
        if (o == null) return null;
        return o.optJSONObject("chords");
    }

    /**
     * Packages that should not drive per-app remaps (shade, IME, etc.).
     * Profiles may still exist for them if the user forced one, but
     * resolution skips them so global shortcuts keep working.
     */
    public static boolean isEligiblePkg(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        if ("com.android.systemui".equals(pkg)) return false;
        if (pkg.startsWith("com.android.systemui.")) return false;
        if ("android".equals(pkg)) return false;
        if ("com.android.inputmethod.latin".equals(pkg)) return false;
        if (pkg.contains("inputmethod")) return false;
        return true;
    }

    public String resolveAppLabel(String pkg) {
        if (pkg == null) return "";
        try {
            PackageManager pm = app.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence l = pm.getApplicationLabel(ai);
            if (l != null && l.length() > 0) return l.toString();
        } catch (Exception ignored) {}
        return pkg;
    }

    private JSONObject slotsOf(String pkg) {
        JSONObject o = loadRaw(pkg);
        if (o == null) return null;
        return o.optJSONObject("slots");
    }

    private JSONObject loadRaw(String pkg) {
        String raw = p.getString(KEY_PREFIX + pkg, null);
        if (raw == null || raw.isEmpty()) return null;
        try {
            return new JSONObject(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private void saveOrder(List<String> order) {
        JSONArray a = new JSONArray();
        for (String id : order) a.put(id);
        p.edit().putString(KEY_ORDER, a.toString()).apply();
    }

    /** Sorted launcher packages for pickers (label, package). */
    public static List<String[]> launcherApps(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        android.content.Intent main = new android.content.Intent(android.content.Intent.ACTION_MAIN);
        main.addCategory(android.content.Intent.CATEGORY_LAUNCHER);
        List<android.content.pm.ResolveInfo> apps =
            pm.queryIntentActivities(main, 0);
        Collections.sort(apps, (a, b) -> a.loadLabel(pm).toString()
            .compareToIgnoreCase(b.loadLabel(pm).toString()));
        List<String[]> out = new ArrayList<>();
        for (android.content.pm.ResolveInfo ri : apps) {
            String pkg = ri.activityInfo.packageName;
            if (pkg == null) continue;
            out.add(new String[] { ri.loadLabel(pm).toString(), pkg });
        }
        return out;
    }
}
