package com.titanus2.api;

/**
 * HID session ↔ Titan Controls remapper harmony.
 * <p>
 * The HID app does <b>not</b> re-implement key assignment. Shortcuts live only
 * in Titan Controls ({@code KeyMapPrefs} / Keys UI). During a physical HID
 * session the framework pushes layer {@link Titan2ApiContract#LAYER_HID_SESSION}
 * that sets every managed slot to {@code none}, then pops it on stop.
 * <p>
 * Use {@link Titan2Client#silenceKeyRemaps(String)} /
 * {@link Titan2Client#popTempKeyMap(String)} — do not invent parallel host-button maps.
 */
public final class HidSessionKeyMap {
    private HidSessionKeyMap() {}

    /** Layer id used for HID session silence (alias). */
    public static String layerId() {
        return Titan2ApiContract.LAYER_HID_SESSION;
    }
}
