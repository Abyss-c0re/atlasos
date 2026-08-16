package com.titanus2.controls;

import android.content.Context;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Inserted / known SIMs including UICC-off. Settings
 * {@code getAvailableSubscriptionInfoList} drops those; this list does not.
 */
public final class SimCards {
    private SimCards() {}

    public static final class Card {
        public final int subId;
        public final int slot;
        public final String name;
        public final String carrier;
        public final boolean uicc;
        public final boolean inSettings;
        public final String mccMnc;

        Card(int subId, int slot, String name, String carrier,
             boolean uicc, boolean inSettings, String mccMnc) {
            this.subId = subId;
            this.slot = slot;
            this.name = name;
            this.carrier = carrier;
            this.uicc = uicc;
            this.inSettings = inSettings;
            this.mccMnc = mccMnc;
        }

        public String stateWord() {
            return uicc ? "On" : "Off";
        }

        public String fact() {
            String slotWord = slot >= 0 ? ("slot " + slot) : "slot —";
            String listed = inSettings ? "in Settings" : "Settings hid";
            String m = (mccMnc == null || mccMnc.isEmpty()) ? "" : (" · " + mccMnc);
            return stateWord() + " · " + slotWord + " · sub " + subId + " · " + listed + m;
        }
    }

    public static List<Card> list(Context ctx) {
        List<Card> out = new ArrayList<Card>();
        if (ctx == null) return out;
        SubscriptionManager sm = ctx.getSystemService(SubscriptionManager.class);
        if (sm == null) return out;
        List<SubscriptionInfo> all = sm.getAllSubscriptionInfoList();
        if (all == null) return out;
        Set<Integer> available = availableIds(sm);
        Set<Integer> seen = new HashSet<Integer>();
        for (SubscriptionInfo s : all) {
            if (s == null) continue;
            int id = s.getSubscriptionId();
            if (!seen.add(id)) continue;
            String icc = s.getIccId();
            int slot = s.getSimSlotIndex();
            if ((icc == null || icc.isEmpty()) && slot < 0) continue;
            String name = s.getDisplayName() != null
                ? s.getDisplayName().toString() : ("SIM " + id);
            String carrier = s.getCarrierName() != null
                ? s.getCarrierName().toString() : "";
            String plmn = plmnOf(s);
            out.add(new Card(id, slot, name, carrier,
                uiccOn(s), available.contains(id), plmn));
        }
        Collections.sort(out, new Comparator<Card>() {
            @Override public int compare(Card a, Card b) {
                return Integer.compare(a.subId, b.subId);
            }
        });
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
            return true;
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
