package com.titanus2.controls;

import android.content.Context;
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
 * Known SIMs including UICC-off. Labels are tray names only (SIM 1 / SIM 2).
 * Never carrier / displayName. Settings available-list may hide a row; this
 * list does not.
 */
public final class SimCards {
    private static final String PREF = "titan2_sim_memory";
    private static final String KEY = "cards";

    private SimCards() {}

    public static final class Card {
        public final int subId;
        public final int slot;
        public final String name;
        public final boolean uicc;
        public final boolean inSettings;
        public final boolean remembered;

        Card(int subId, int slot, boolean uicc, boolean inSettings, boolean remembered) {
            this.subId = subId;
            this.slot = slot;
            this.name = trayName(slot, subId);
            this.uicc = uicc;
            this.inSettings = inSettings;
            this.remembered = remembered;
        }

        public String stateWord() {
            return uicc ? "On" : "Off";
        }

        public String fact() {
            String slotWord = slot >= 0 ? ("slot " + slot) : "slot —";
            String listed = inSettings ? "in Settings" : "Settings hid";
            return stateWord() + " · " + slotWord + " · sub " + subId + " · " + listed;
        }
    }

    /** Tray label. Slot 0 → SIM 1. Unknown slot falls back to subId. */
    public static String trayName(int slot, int subId) {
        int n = slot >= 0 ? slot + 1 : subId;
        if (n < 1) n = 1;
        return "SIM " + n;
    }

    public static List<Card> list(Context ctx) {
        List<Card> out = new ArrayList<Card>();
        if (ctx == null) return out;
        SubscriptionManager sm = ctx.getSystemService(SubscriptionManager.class);
        Set<Integer> available = sm != null ? availableIds(sm) : new HashSet<Integer>();
        Map<Integer, Card> byId = new HashMap<Integer, Card>();

        if (sm != null) {
            for (SubscriptionInfo s : allInfos(sm)) {
                Card c = fromInfo(s, available, false);
                if (c == null || c.slot < 0) continue;
                byId.put(c.subId, c);
            }
        }

        // Memory only keeps SIMs still in a tray. Pulled cards (slot -1 / ABSENT)
        // are not "disabled" — they are gone.
        for (Card mem : loadMemory(ctx)) {
            if (mem.slot < 0 || byId.containsKey(mem.subId)) continue;
            if (slotAbsent(mem.slot)) continue;
            byId.put(mem.subId, mem);
        }

        Set<Integer> slotsTaken = new HashSet<Integer>();
        for (Card c : byId.values()) {
            if (c.slot >= 0) slotsTaken.add(c.slot);
        }
        String[] slotState = gsmSimState();
        for (int i = 0; i < slotState.length; i++) {
            if (slotsTaken.contains(i)) continue;
            String st = slotState[i];
            if (st == null || st.isEmpty() || "ABSENT".equals(st)) continue;
            Card orphan = null;
            int orphans = 0;
            for (Card c : byId.values()) {
                if (c.slot < 0) {
                    orphan = c;
                    orphans++;
                }
            }
            if (orphans == 1 && orphan != null) {
                byId.put(orphan.subId, new Card(orphan.subId, i, false,
                    orphan.inSettings, orphan.remembered));
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
        String shown = "";
        try {
            if (s.getDisplayName() != null) shown = s.getDisplayName().toString();
        } catch (Throwable ignored) {}
        if (slot < 0) return null;
        if ((icc == null || icc.isEmpty()) && shown.isEmpty()) {
            return null;
        }
        return new Card(id, slot, uiccOn(s), available.contains(id), remembered);
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
            if (p.length < 2) continue;
            try {
                int id = Integer.parseInt(p[0]);
                int slot = Integer.parseInt(p[1]);
                if (id <= 0) continue;
                out.add(new Card(id, slot, false, false, true));
            } catch (Exception ignored) {}
        }
        return out;
    }

    private static void saveMemory(Context ctx, List<Card> cards) {
        StringBuilder sb = new StringBuilder();
        for (Card c : cards) {
            if (c.subId <= 0) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(c.subId).append('\t').append(c.slot);
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

    private static boolean slotAbsent(int slot) {
        if (slot < 0) return true;
        String[] st = gsmSimState();
        if (slot >= st.length) return true;
        String s = st[slot];
        return s == null || s.isEmpty() || "ABSENT".equals(s);
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
