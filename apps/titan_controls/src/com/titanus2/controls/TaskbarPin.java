package com.titanus2.controls;

import android.content.Context;
import android.provider.Settings;

/**
 * P0 taskbar residual on cube dens tablet plane — force-off every known key.
 * Shared by BootRestore + TrackpadAccessService (a11y bind) + hub open.
 * <p>
 * <b>15.72:</b> Never write {@code navigation_mode} or nav-bar interaction mode.
 * Boot/a11y pinOff was stomping user 3-button vs gesture choice every reboot.
 * Taskbar off ≠ re-force gestural nav.
 * <p>
 * 11.45: extra Lineage/Launcher3 residual names that re-raise a bottom strip
 * after wipe / dens tablet / freeform toggles (seen post multi-device lab).
 * 11.51: dens tablet freeform/desktop residual + taskbar_force_visible.
 * 12.69: Global enable_launcher_taskbar / launcher_show_taskbar (cube-ux v7).
 * 12.76: always_show / hide_taskbar dens residual (A16 Launcher3 re-raise names).
 * 13.06: desktop_mode / split taskbar residual names (A16 dens re-raise).
 * 13.26: Global namespace mirrors (cube dens tablet plane re-raise after unlock).
 */
public final class TaskbarPin {
    private TaskbarPin() {}

    public static void pinOff(Context ctx) {
        if (ctx == null) return;
        android.content.ContentResolver cr = ctx.getContentResolver();
        // System keys (Lineage + AOSP launcher residual)
        try {
            Settings.System.putInt(cr, "enable_taskbar", 0);
            Settings.System.putInt(cr, "lineage_enable_taskbar", 0);
            Settings.System.putInt(cr, "taskbar_unpinning", 1);
            Settings.System.putInt(cr, "taskbar_collapse_duration", 0);
            // Do NOT set navigation_bar_interaction_mode — that is user nav SoT.
            Settings.System.putInt(cr, "taskbar", 0);
            Settings.System.putInt(cr, "show_taskbar", 0);
            Settings.System.putInt(cr, "launcher_taskbar_education_showing", 0);
            Settings.System.putInt(cr, "three_button_taskbar", 0);
            Settings.System.putInt(cr, "navbar_taskbar", 0);
            Settings.System.putInt(cr, "taskbar_force_visible", 0);
            Settings.System.putInt(cr, "force_taskbar", 0);
            // 11.66: dens tablet residual strip names (LOS/Launcher3 re-raise)
            Settings.System.putInt(cr, "taskbar_showing", 0);
            Settings.System.putInt(cr, "transient_taskbar", 1);
            // 12.69 dens residual (system namespace mirrors)
            Settings.System.putInt(cr, "taskbar_enabled", 0);
            Settings.System.putInt(cr, "launcher_show_taskbar", 0);
            Settings.System.putInt(cr, "enable_launcher_taskbar", 0);
            // 13.26 dens residual (system aliases after USER_PRESENT)
            Settings.System.putInt(cr, "taskbar_visible", 0);
            Settings.System.putInt(cr, "qs_show_taskbar", 0);
            Settings.System.putInt(cr, "sysui_taskbar_enabled", 0);
        } catch (Exception ignored) {}
        // Secure (Launcher3 rewrite). Never touch navigation_mode — user Settings SoT.
        try {
            Settings.Secure.putInt(cr, "launcher_taskbar_education_showing", 0);
            Settings.Secure.putInt(cr, "launcher_taskbar_rewrite_enabled", 0);
            Settings.Secure.putInt(cr, "desktop_mode_enabled", 0);
            Settings.Secure.putInt(cr, "enable_taskbar", 0);
            Settings.Secure.putInt(cr, "show_taskbar", 0);
            Settings.Secure.putInt(cr, "swipe_bottom_to_notification_enabled", 1);
            // Gesture bar residual (phone dens) — keep gestures, kill task strip
            Settings.Secure.putInt(cr, "systemui_taskbar", 0);
            Settings.Secure.putInt(cr, "taskbar_pinned", 0);
            Settings.Secure.putInt(cr, "taskbar_force_visible", 0);
            Settings.Secure.putInt(cr, "launcher_taskbar_enabled", 0);
            Settings.Secure.putInt(cr, "taskbar_showing", 0);
            Settings.Secure.putInt(cr, "transient_taskbar", 1);
            Settings.Secure.putInt(cr, "launcher_taskbar_pinning", 0);
            // 11.83: dens tablet residual (A16 / Launcher3 taskbar type)
            Settings.Secure.putInt(cr, "taskbar_type", 0);
            Settings.Secure.putInt(cr, "enable_taskbar_edu", 0);
            Settings.Secure.putInt(cr, "taskbar_edu_tooltip_step", 0);
            // 11.99: dens residual re-raise names (Launcher3 / SysUI task strip)
            Settings.Secure.putInt(cr, "taskbar_pinning_enabled", 0);
            Settings.Secure.putInt(cr, "launcher_taskbar_pinning_enabled", 0);
            Settings.Secure.putInt(cr, "stashed_taskbar", 0);
            Settings.Secure.putInt(cr, "taskbar_stashed", 0);
            // 12.10 lab_rootless wipe residual (A16 Launcher3 still exposes Taskbar window)
            Settings.Secure.putInt(cr, "launcher_taskbar_edu_seen", 1);
            Settings.Secure.putInt(cr, "enable_taskbar_edu", 0);
            Settings.Secure.putInt(cr, "windowed_mode_taskbar", 0);
            Settings.Secure.putInt(cr, "enable_nav_bar_taskbar", 0);
            Settings.Secure.putInt(cr, "taskbar_in_overview", 0);
            Settings.Secure.putInt(cr, "overview_taskbar", 0);
            // 12.30: dens tablet / freeform residual re-raise names (LOS A16 lab)
            Settings.Secure.putInt(cr, "taskbar_pinning", 0);
            Settings.Secure.putInt(cr, "enable_taskbar_on_phone", 0);
            Settings.Secure.putInt(cr, "launcher_taskbar_edu_tooltip_step", 0);
            Settings.Secure.putInt(cr, "taskbar_edu_show_step", 0);
            // 12.42 dens residual (Launcher3 stash / pin re-raise)
            Settings.Secure.putInt(cr, "taskbar_stashing_enabled", 0);
            Settings.Secure.putInt(cr, "enable_taskbar_pinning", 0);
            Settings.Secure.putInt(cr, "launcher_taskbar_stashing_enabled", 0);
            // 12.51 dens residual (A16 Launcher3 taskbar window re-raise)
            Settings.Secure.putInt(cr, "taskbar_enabled", 0);
            Settings.Secure.putInt(cr, "launcher3_taskbar_enabled", 0);
            Settings.Secure.putInt(cr, "enable_launcher_taskbar", 0);
            Settings.Secure.putInt(cr, "taskbar_in_app", 0);
            // 12.55 dens residual (Launcher3 is_taskbar_visible / force show)
            Settings.Secure.putInt(cr, "is_taskbar_visible", 0);
            Settings.Secure.putInt(cr, "taskbar_force_show", 0);
            Settings.Secure.putInt(cr, "force_show_taskbar", 0);
            Settings.Secure.putInt(cr, "launcher_show_taskbar", 0);
            // 12.76 dens residual (always-on / hide toggle names re-raise strip)
            Settings.Secure.putInt(cr, "always_show_taskbar", 0);
            Settings.Secure.putInt(cr, "taskbar_always_show", 0);
            Settings.Secure.putInt(cr, "taskbar_always_show_window", 0);
            Settings.Secure.putInt(cr, "force_taskbar_visible", 0);
            Settings.Secure.putInt(cr, "hide_taskbar", 1);
            Settings.Secure.putInt(cr, "taskbar_hidden", 1);
            Settings.Secure.putInt(cr, "show_taskbar_in_overview", 0);
            Settings.Secure.putInt(cr, "taskbar_in_recents", 0);
            // 13.06 dens residual (desktop / split / home-only re-raise)
            Settings.Secure.putInt(cr, "taskbar_in_desktop", 0);
            Settings.Secure.putInt(cr, "desktop_mode_taskbar", 0);
            Settings.Secure.putInt(cr, "enable_taskbar_in_desktop", 0);
            Settings.Secure.putInt(cr, "taskbar_home_only", 0);
            Settings.Secure.putInt(cr, "taskbar_in_split_screen", 0);
            Settings.Secure.putInt(cr, "enable_taskbar_in_split", 0);
            // 13.26 dens residual (phone/tablet hybrid after dens flip)
            Settings.Secure.putInt(cr, "taskbar_visible", 0);
            Settings.Secure.putInt(cr, "qs_show_taskbar", 0);
            Settings.Secure.putInt(cr, "sysui_taskbar_enabled", 0);
            Settings.Secure.putInt(cr, "launcher_taskbar_visible", 0);
            Settings.Secure.putInt(cr, "enable_taskbar_for_phones", 0);
        } catch (Exception ignored) {}
        // 13.26: Global mirrors — cube-ux dens sometimes only sticks Global
        try {
            Settings.Global.putInt(cr, "enable_taskbar", 0);
            Settings.Global.putInt(cr, "show_taskbar", 0);
            Settings.Global.putInt(cr, "taskbar_enabled", 0);
            Settings.Global.putInt(cr, "enable_launcher_taskbar", 0);
            Settings.Global.putInt(cr, "launcher_show_taskbar", 0);
            Settings.Global.putInt(cr, "taskbar_force_visible", 0);
            Settings.Global.putInt(cr, "force_show_taskbar", 0);
            Settings.Global.putInt(cr, "always_show_taskbar", 0);
            Settings.Global.putInt(cr, "hide_taskbar", 1);
            Settings.Global.putInt(cr, "is_taskbar_visible", 0);
            Settings.Global.putInt(cr, "taskbar_visible", 0);
            Settings.Global.putInt(cr, "desktop_mode_taskbar", 0);
            Settings.Global.putInt(cr, "enable_taskbar_in_desktop", 0);
        } catch (Exception ignored) {}
        try {
            Settings.System.putInt(cr, "taskbar_pinning", 0);
            Settings.System.putInt(cr, "enable_taskbar_on_phone", 0);
            Settings.System.putInt(cr, "taskbar_stashing_enabled", 0);
            Settings.System.putInt(cr, "always_show_taskbar", 0);
            Settings.System.putInt(cr, "taskbar_always_show", 0);
            Settings.System.putInt(cr, "hide_taskbar", 1);
            Settings.System.putInt(cr, "taskbar_in_desktop", 0);
            Settings.System.putInt(cr, "desktop_mode_taskbar", 0);
        } catch (Exception ignored) {}
        // Global freeform/desktop paths that re-raise a bottom task strip
        try {
            Settings.Global.putInt(cr, "launcher_taskbar_education_showing", 0);
            Settings.Global.putInt(cr, "force_resizable_activities", 0);
            Settings.Global.putInt(cr, "enable_freeform_support", 0);
            Settings.Global.putInt(cr, "desktop_mode_enabled", 0);
            Settings.Global.putInt(cr, "enable_taskbar", 0);
            Settings.Global.putInt(cr, "show_taskbar", 0);
            Settings.Global.putInt(cr, "development_enable_freeform_windows_support", 0);
            Settings.Global.putInt(cr, "systemui_taskbar", 0);
            Settings.Global.putInt(cr, "force_desktop_mode_on_external_displays", 0);
            Settings.Global.putInt(cr, "enable_non_resizable_multi_window", 0);
            Settings.Global.putInt(cr, "taskbar_force_visible", 0);
            Settings.Global.putInt(cr, "taskbar_showing", 0);
            Settings.Global.putInt(cr, "transient_taskbar", 1);
            Settings.Global.putInt(cr, "taskbar_type", 0);
            Settings.Global.putInt(cr, "enable_taskbar_edu", 0);
            Settings.Global.putInt(cr, "taskbar_pinning_enabled", 0);
            Settings.Global.putInt(cr, "stashed_taskbar", 0);
            Settings.Global.putInt(cr, "windowed_mode_taskbar", 0);
            Settings.Global.putInt(cr, "enable_nav_bar_taskbar", 0);
            Settings.Global.putInt(cr, "taskbar_in_overview", 0);
            Settings.Global.putInt(cr, "taskbar_pinning", 0);
            Settings.Global.putInt(cr, "enable_taskbar_on_phone", 0);
            Settings.Global.putInt(cr, "overview_taskbar", 0);
            Settings.Global.putInt(cr, "taskbar_enabled", 0);
            Settings.Global.putInt(cr, "launcher3_taskbar_enabled", 0);
            Settings.Global.putInt(cr, "taskbar_in_app", 0);
            Settings.Global.putInt(cr, "is_taskbar_visible", 0);
            Settings.Global.putInt(cr, "taskbar_force_show", 0);
            Settings.Global.putInt(cr, "force_show_taskbar", 0);
            // 12.69 dens residual (cube-ux v7 / Launcher3 global names)
            Settings.Global.putInt(cr, "enable_launcher_taskbar", 0);
            Settings.Global.putInt(cr, "launcher_show_taskbar", 0);
            Settings.Global.putInt(cr, "launcher3_taskbar_enabled", 0);
            // 12.76 dens residual (always-on / overview strip)
            Settings.Global.putInt(cr, "always_show_taskbar", 0);
            Settings.Global.putInt(cr, "taskbar_always_show", 0);
            Settings.Global.putInt(cr, "taskbar_always_show_window", 0);
            Settings.Global.putInt(cr, "force_taskbar_visible", 0);
            Settings.Global.putInt(cr, "hide_taskbar", 1);
            Settings.Global.putInt(cr, "taskbar_hidden", 1);
            Settings.Global.putInt(cr, "show_taskbar_in_overview", 0);
            Settings.Global.putInt(cr, "taskbar_in_recents", 0);
            // 13.06 dens residual (desktop / split)
            Settings.Global.putInt(cr, "taskbar_in_desktop", 0);
            Settings.Global.putInt(cr, "desktop_mode_taskbar", 0);
            Settings.Global.putInt(cr, "enable_taskbar_in_desktop", 0);
            Settings.Global.putInt(cr, "taskbar_home_only", 0);
            Settings.Global.putInt(cr, "taskbar_in_split_screen", 0);
            Settings.Global.putInt(cr, "enable_taskbar_in_split", 0);
        } catch (Exception ignored) {}
        // 13.06: do not re-apply full theme plane on every pinOff (overlay thrash).
        // Look plane is applied from BootRestore / ThemeActivity / throttled ensure.
    }
}
