package com.titanus2.controls;

import android.content.Context;
import android.content.SharedPreferences;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Inserted / known SIMs including UICC-off. Settings
 * {@code getAvailableSubscriptionInfoList} drops those; this list does not.
 * <p>
 * Heresy 15.94: empty {@code getIccId()} + {@code simSlotIndex=-1} skipped
 * T-Mobile so Controls mirrored Settings. Named / valid subIds stay.
 */
public final class SimCards {
    private static final String PREF = "titan2_sim_memory";
    private static final String KEY = "cards";

    private SimCards() {}

    public static final class Card {
        public final int subId;
        public final int slot;
        public final String name;
        public final String carrier;
        public final boolean uicc;
        public final boolean inSettings;
        public final String mccMnc;
        public final boolean remembered;

        Card(int subId, int slot, String name, String carrier,
             boolean uicc, boolean inSettings, String mccMnc, boolean remembered) {
            this.subId = subId;
            this.slot = slot;
            this.name = name != null ? name : ("SIM " + subId);
            this.carrier = carrier != null ? carrier : "";
            this.uicc = uicc;
            this.inSettings = inSettings;
            this.mccMnc = mccMnc != null ? mccMnc : "";
            this.remembered = remembered;
        }

        public String stateWord() {
            return uicc ? "On" : "Off";
        }

        public String fact() {
            String slotWord = slot >= 0 ? ("slot " + slot) : "slot —";
            String listed = inSettings ? "in Settings"
                : (remembered ? "Settings hid · remembered" : "Settings hid");
            String m = mccMnc.isEmpty() ? "" : (" · " + mccMnc);
            return stateWord() + " · " + slotWord + " · sub " + subId + " · " + listed + m;
        }
    }

    public static List<Card> list(Context ctx) {
        List<Card> out = new ArrayList<Card>();
        if (ctx == null) return out;
        SubscriptionManager sm = ctx.getSystemService(SubscriptionManager.class);
        Set<Integer> available = sm != null ? availableIds(sm) : new HashSet<Integer>();
        Map<Integer, Card> byId = new HashMap<Integer, Card>();

        for (Card mem : loadMemory(ctx)) {
            byId.put(mem.subId, mem);
        }

        if (sm != null) {
            for (SubscriptionInfo s : allInfos(sm)) {
                Card c = fromInfo(s, available, false);
                if (c == null) continue;
                Card old = byId.get(c.subId);
                int slot = c.slot >= 0 ? c.slot : (old != null ? old.slot : -1);
                String name = c.name;
                if ((name == null || name.isEmpty() || name.startsWith("SIM "))
                        && old != null && old.name != null) {
                    name = old.name;
                }
                String carrier = !c.carrier.isEmpty() ? c.carrier
                    : (old != null ? old.carrier : "");
                String plmn = !c.mccMnc.isEmpty() ? c.mccMnc
                    : (old != null ? old.mccMnc : "");
                byId.put(c.subId, new Card(c.subId, slot, name, carrier,
                    c.uicc, c.inSettings, plmn, false));
            }
        }

        // Slot occupancy: NOT_READY with no mapped card still gets a tray row
        // only if we have no remembered sub for that tray (avoid dup T-Mobile).
        Set<Integer> slotsTaken = new HashSet<Integer>();
        for (Card c : byId.values()) {
            if (c.slot >= 0) slotsTaken.add(c.slot);
        }
        String[] slotState = gsmSimState();
        for (int i = 0; i < slotState.length; i++) {
            if (slotsTaken.contains(i)) continue;
            String st = slotState[i];
            if (st == null || st.isEmpty() || "ABSENT".equals(st)) continue;
            // Bind a slot=-1 remembered card onto this tray when only one orphan.
            Card orphan = null;
            int orphans = 0;
            for (Card c : byId.values()) {
                if (c.slot < 0) {
                    orphan = c;
                    orphans++;
                }
            }
            if (orphans == 1 && orphan != null) {
                byId.put(orphan.subId, new Card(orphan.subId, i, orphan.name,
                    orphan.carrier, false, orphan.inSettings, orphan.mccMnc,
                    orphan.remembered));
            }
        }

        out.addAll(byId.values());
        Collections.sort(out, new Comparator<Card>() {
            @Override public int compare(Card a, Card b) {
                if (a.slot >= 0 && b.slot >= 0 && a.slot != b.slot) {
                    return Integer.compare(a.slot, b.slot);
                }
                if (a.slot >= 0 && b.slot < 0) return -1;
                if (a.slot < 0 && b.slot >= 0) return 1;
                return Integer.compare(a.subId, b.subId);
            }
        });
        saveMemory(ctx, out);
        return out;
    }

    public static String hubSummary(Context ctx) {
        List<Card> cards = list(ctx);
        if (cards.isEmpty()) return "none";
        StringBuilder sb = new StringBuilder();
        for (Card c : cards) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(c.name).append(' ').append(c.stateWord().toLowerCase());
        }
        return sb.toString();
    }

    public static boolean setUicc(Context ctx, int subId, boolean on) {
        if (ctx == null || subId <= 0) return false;
        SubscriptionManager sm = ctx.getSystemService(SubscriptionManager.class);
        if (sm == null) return false;
        try {
            sm.getClass()
                .getMethod("setUiccApplicationsEnabled", int.class, boolean.class)
                .invoke(sm, Integer.valueOf(subId), Boolean.valueOf(on));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Card fromInfo(SubscriptionInfo s, Set<Integer> available,
                                 boolean remembered) {
        if (s == null) return null;
        int id = s.getSubscriptionId();
        if (id <= 0) return null;
        String icc = null;
        try { icc = s.getIccId(); } catch (Throwable ignored) {}
        int slot = s.getSimSlotIndex();
        String name = s.getDisplayName() != null
            ? s.getDisplayName().toString() : "";
        // Empty ICC + invalid slot used to drop UICC-off cards. Keep any
        // named or ICC-bearing record. Only skip totally hollow ghosts.
        if ((icc == null || icc.isEmpty()) && slot < 0
                && (name == null || name.isEmpty())) {
            return null;
        }
        if (name == null || name.isEmpty()) name = "SIM " + id;
        String carrier = "";
        try {
            if (s.getCarrierName() != null) carrier = s.getCarrierName().toString();
        } catch (Throwable ignored) {}
        return new Card(id, slot, name, carrier, uiccOn(s),
            available.contains(id), plmnOf(s), remembered);
    }

    @SuppressWarnings("unchecked")
    private static List<SubscriptionInfo> allInfos(SubscriptionManager sm) {
        List<SubscriptionInfo> out = new ArrayList<SubscriptionInfo>();
        Set<Integer> seen = new HashSet<Integer>();
        String[] methods = {
            "getAllSubscriptionInfoList",
            "getAvailableSubscriptionInfoList",
            "getCompleteActiveSubscriptionInfoList",
            "getAccessibleSubscriptionInfoList",
            "getActiveSubscriptionInfoList",
        };
        for (String m : methods) {
            try {
                Object v = sm.getClass().getMethod(m).invoke(sm);
                if (!(v instanceof List)) continue;
                for (Object o : (List<?>) v) {
                    if (!(o instanceof SubscriptionInfo)) continue;
                    SubscriptionInfo s = (SubscriptionInfo) o;
                    if (seen.add(s.getSubscriptionId())) out.add(s);
                }
            } catch (Throwable ignored) {}
        }
        // Brute: inactive UICC-off still answers getSubscriptionInfo(id).
        for (int id = 1; id <= 16; id++) {
            if (seen.contains(id)) continue;
            SubscriptionInfo s = infoById(sm, id);
            if (s != null && seen.add(id)) out.add(s);
        }
        return out;
    }

    private static SubscriptionInfo infoById(SubscriptionManager sm, int id) {
        try {
            Object v = sm.getClass().getMethod("getActiveSubscriptionInfo", int.class)
                .invoke(sm, Integer.valueOf(id));
            if (v instanceof SubscriptionInfo) return (SubscriptionInfo) v;
        } catch (Throwable ignored) {}
        try {
            Object v = sm.getClass().getMethod("getSubscriptionInfo", int.class)
                .invoke(sm, Integer.valueOf(id));
            if (v instanceof SubscriptionInfo) return (SubscriptionInfo) v;
        } catch (Throwable ignored) {}
        return null;
    }

    private static List<Card> loadMemory(Context ctx) {
        List<Card> out = new ArrayList<Card>();
        String raw;
        try {
            raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, "");
        } catch (Exception e) {
            return out;
        }
        if (raw == null || raw.isEmpty()) return out;
        for (String line : raw.split("\n")) {
            if (line == null || line.isEmpty()) continue;
            String[] p = line.split("\t", -1);
            if (p.length < 4) continue;
            try {
                int id = Integer.parseInt(p[0]);
                int slot = Integer.parseInt(p[3]);
                String name = p[1];
                String carrier = p.length > 2 ? p[2] : "";
                String plmn = p.length > 5 ? p[5] : "";
                if (id <= 0) continue;
                out.add(new Card(id, slot, name, carrier, false, false, plmn, true));
            } catch (Exception ignored) {}
        }
        return out;
    }

    private static void saveMemory(Context ctx, List<Card> cards) {
        StringBuilder sb = new StringBuilder();
        for (Card c : cards) {
            if (c.subId <= 0) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(c.subId).append('\t')
                .append(c.name.replace('\t', ' ')).append('\t')
                .append(c.carrier.replace('\t', ' ')).append('\t')
                .append(c.slot).append('\t')
                .append('\t')
                .append(c.mccMnc.replace('\t', ' '));
        }
        try {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putString(KEY, sb.toString()).apply();
        } catch (Exception ignored) {}
        try {
            android.provider.Settings.Global.putString(
                ctx.getContentResolver(), "titan2_sim_memory", sb.toString());
        } catch (Exception ignored) {}
    }

    private static String[] gsmSimState() {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            String v = (String) c.getMethod("get", String.class, String.class)
                .invoke(null, "gsm.sim.state", "");
            if (v == null || v.isEmpty()) return new String[0];
            return v.split(",");
        } catch (Exception e) {
            return new String[0];
        }
    }

    private static String plmnOf(SubscriptionInfo s) {
        try {
            Object mcc = s.getClass().getMethod("getMccString").invoke(s);
            Object mnc = s.getClass().getMethod("getMncString").invoke(s);
            if (mcc instanceof String && !((String) mcc).isEmpty()) {
                return (String) mcc + (mnc instanceof String ? (String) mnc : "");
            }
        } catch (Throwable ignored) {}
        try {
            int mcc = s.getMcc();
            int mnc = s.getMnc();
            if (mcc > 0) return String.valueOf(mcc) + (mnc >= 0 ? String.valueOf(mnc) : "");
        } catch (Throwable ignored) {}
        return "";
    }

    private static boolean uiccOn(SubscriptionInfo s) {
        try {
            Object v = s.getClass().getMethod("areUiccApplicationsEnabled").invoke(s);
            return !Boolean.FALSE.equals(v);
        } catch (Throwable ignored) {
            return s.getSimSlotIndex() >= 0;
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<Integer> availableIds(SubscriptionManager sm) {
        Set<Integer> ids = new HashSet<Integer>();
        try {
            Object v = sm.getClass().getMethod("getAvailableSubscriptionInfoList").invoke(sm);
            if (v instanceof List) {
                for (Object o : (List<?>) v) {
                    if (o instanceof SubscriptionInfo) {
                        ids.add(((SubscriptionInfo) o).getSubscriptionId());
                    }
                }
            }
        } catch (Throwable ignored) {}
        return ids;
    }
}
