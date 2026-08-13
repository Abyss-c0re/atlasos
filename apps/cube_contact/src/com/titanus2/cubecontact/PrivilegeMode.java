package com.titanus2.cubecontact;

/**
 * Three access tiers for Cube Contact / nanobot plane.
 * Shared methods always go through nanobot peer when available.
 */
public enum PrivilegeMode {
    /** Accessibility service — no root; limited automation. */
    UNPRIVILEGED_A11Y,
    /** Shizuku or adb root shell — elevated plane ops. */
    SHIZUKU_OR_ROOT,
    /** priv-app / Magisk system image — full ROM plane. */
    SYSTEM;

    public String label() {
        switch (this) {
            case UNPRIVILEGED_A11Y: return "Unprivileged (Accessibility)";
            case SHIZUKU_OR_ROOT: return "Shizuku / Root";
            case SYSTEM: return "System (priv-app / Magisk)";
            default: return name();
        }
    }
}
