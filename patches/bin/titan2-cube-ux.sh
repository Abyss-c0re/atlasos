#!/system/bin/sh
# Hybrid ROM-wide Cube UX — night OLED + Cube spike glow (no square RROs).
# Geometry: square chrome RROs DROPPED 2026-07-21 (FGS pill NPE + mangled Settings).
# Product: night + spike #FF141A via theme seed — never re-enable Titan*Square*.
# Cyan #00E5FF was leftover OS seed; CubeAI/CubeUI ticket is mesh/cage/spike/void.
# Called from titan2-display. Version: 15 (Cube spike default; migrate leftover cyan)
# Glow/mode from leftover Look (ThemePrefs) when the human picked a non-default:
#   settings global titan2_ui_accent_argb  (hex without #, e.g. ffff141a or FF141A)
#   settings global titan2_ui_day_night    day | night | auto

export PATH=/system/bin:/system/xbin:/product/bin:$PATH
LOG=/data/local/tmp/titan2-cube-ux.log
logm() { echo "$(date '+%H:%M:%S') $*" >>"$LOG" 2>/dev/null || true; }

mkdir -p /data/local/tmp 2>/dev/null || true
# Wipe may leave root-owned log; recreate world-writable for shell debug
rm -f "$LOG" 2>/dev/null || true
: >"$LOG" 2>/dev/null || true
chmod 666 "$LOG" 2>/dev/null || true
logm "start v15 theme=$(getprop ro.titanus2.theme) profile=$(getprop ro.titanus2.profile)"

# Seed Look plane defaults after wipe so SystemUI/cube match Cube spike.
# Leftover product cyan #00E5FF is not a human pick — migrate to spike.
ACCENT_RAW=$(settings get global titan2_ui_accent_argb 2>/dev/null | tr -d '\r' | tr 'A-F' 'a-f')
case "$ACCENT_RAW" in
  null|""|*" "*|00e5ff|ff00e5ff)
    settings put global titan2_ui_accent_argb ff141a 2>/dev/null || true
    ACCENT_RAW=ff141a
    ;;
esac
DAYNIGHT=$(settings get global titan2_ui_day_night 2>/dev/null | tr -d '\r' | tr 'A-Z' 'a-z')
case "$DAYNIGHT" in
  null|""|*" "*)
    settings put global titan2_ui_day_night night 2>/dev/null || true
    DAYNIGHT=night
    ;;
esac

# --- Accent from Look prefs (default Cube spike) ---
case "$ACCENT_RAW" in
  null|""|*" "*) ACCENT_HEX=ff141a ;;
  *)
    ACCENT_HEX=$(echo "$ACCENT_RAW" | sed 's/^0x//;s/^#//')
    case "$ACCENT_HEX" in
      ???????? ) ACCENT_HEX=$(echo "$ACCENT_HEX" | sed 's/^..//') ;;
    esac
    ;;
esac
[ ${#ACCENT_HEX} -eq 6 ] || ACCENT_HEX=ff141a

case "$DAYNIGHT" in
  day)
    settings put secure ui_night_mode 1 2>/dev/null || true
    settings put system ui_night_mode 1 2>/dev/null || true
    cmd uimode night no >/dev/null 2>&1 || true
    ;;
  auto)
    settings put secure ui_night_mode 0 2>/dev/null || true
    settings put system ui_night_mode 0 2>/dev/null || true
    ;;
  *)
    settings put secure ui_night_mode 2 2>/dev/null || true
    settings put system ui_night_mode 2 2>/dev/null || true
    cmd uimode night yes >/dev/null 2>&1 || true
    ;;
esac
logm "glow=#$ACCENT_HEX day_night=${DAYNIGHT:-night}"

# --- Cube seed: monochromatic black + spike glow (not Material pastels) ---
# PRODUCT_UX 2026-08-20: CubeAI/CubeUI spike. No Titan square shape package (FGS).
# Stock adaptive shape stays; night + #FF141A only.
theme_json="{\"android.theme.customization.theme_style\":\"MONOCHROMATIC\",\"android.theme.customization.color_source\":\"preset\",\"android.theme.customization.system_palette\":\"$ACCENT_HEX\",\"android.theme.customization.accent_color\":\"$ACCENT_HEX\"}"
settings put secure theme_customization_overlay_packages "$theme_json" 2>/dev/null || true
if ! settings get secure theme_customization_overlay_packages 2>/dev/null | grep -q MONOCHROMATIC; then
  theme_json="{\"android.theme.customization.theme_style\":\"TONAL_SPOT\",\"android.theme.customization.color_source\":\"preset\",\"android.theme.customization.system_palette\":\"$ACCENT_HEX\",\"android.theme.customization.accent_color\":\"$ACCENT_HEX\"}"
  settings put secure theme_customization_overlay_packages "$theme_json" 2>/dev/null || true
fi
# Full zero-radius square chrome DROPPED — disable leftovers every boot.
# Product Cube uses static cubemask (icon only); never re-arm Settings/SystemUI RROs.
for _ov in com.titanus2.overlay.iconshape com.titanus2.overlay.settings_square com.titanus2.overlay.systemui_square; do
  cmd overlay disable --user current "$_ov" >/dev/null 2>&1 \
    || cmd overlay disable --user 0 "$_ov" >/dev/null 2>&1 \
    || cmd overlay disable "$_ov" >/dev/null 2>&1 || true
done
# Safe Cube mask: enable when present (static product APK may already be force-enabled).
cmd overlay enable --user current com.titanus2.overlay.cubemask >/dev/null 2>&1 \
  || cmd overlay enable --user 0 com.titanus2.overlay.cubemask >/dev/null 2>&1 \
  || cmd overlay enable com.titanus2.overlay.cubemask >/dev/null 2>&1 || true
unset _ov

# --- Keyboard-first IME pin: AOSP LatinIME only ---
# FB-HID-2: exclusive grab owns temp soft-IME allow (HID 2.14 + pad-agent 2.59+).
# Cube apply / boot waves must not force hide while grab is live.
_put_soft_ime_product() {
  # Write-if-changed only — thrash of show_ime restarts LatinIME insets (flicker).
  _s=$(cat /data/local/tmp/titan2_usb_hid_session 2>/dev/null | tr -d '\r\n')
  [ -z "$_s" ] && _s=$(settings get global titan2_usb_hid_session 2>/dev/null | tr -d '\r')
  _g=$(cat /data/local/tmp/titan2_usb_hid_grab 2>/dev/null | tr -d '\r\n')
  [ -z "$_g" ] && _g=$(settings get global titan2_usb_hid_grab 2>/dev/null | tr -d '\r')
  _want=0
  case "$_s" in
    1|true|on|ON)
      case "$_g" in
        1|true|on|ON) _want=1 ;;
      esac
      ;;
  esac
  _cur=$(settings get secure show_ime_with_hard_keyboard 2>/dev/null | tr -d '\r')
  if [ "$_cur" != "$_want" ]; then
    settings put secure show_ime_with_hard_keyboard "$_want" 2>/dev/null || true
  fi
  unset _s _g _want _cur
}
_put_soft_ime_product
# FB-IN-3: Secure long_press is ALL UI long-holds — AOSP 400; never 2800 poison
settings put secure long_press_timeout 400 2>/dev/null || true
# User IME is sacred. Do not ime set, do not wipe enabled_input_methods,
# do not pin LatinIME over PocketBoard / Gboard / anything the human installed.
unset _cur_ime _en

# Product Keys a11y — release has no titan2-dev-adb. Without this, sides/specials
# remaps stay dead until a human opens Controls. Safe on lab profiles too.
A11Y_SVC='com.titanus2.controls/com.titanus2.controls.TrackpadAccessService'
if pm path com.titanus2.controls >/dev/null 2>&1; then
  settings put secure accessibility_enabled 1 2>/dev/null || true
  cur=$(settings get secure enabled_accessibility_services 2>/dev/null | tr -d '\r')
  case "$cur" in
    *TrackpadAccessService*) ;;
    null|""|null*)
      settings put secure enabled_accessibility_services "$A11Y_SVC" 2>/dev/null || true
      ;;
    *)
      case "$cur" in *"$A11Y_SVC"*) ;; *)
        settings put secure enabled_accessibility_services "${cur}:${A11Y_SVC}" 2>/dev/null || true
      ;; esac
      ;;
  esac
fi

# Demo / consumer: never show right-edge key-name popups
settings put system show_key_presses 0 2>/dev/null || true
settings put secure show_key_presses 0 2>/dev/null || true

# P0 taskbar residual on cube dens tablet plane (full belt). Re-applied in
# background waves — SettingsProvider often not ready on first boot after wipe.
# v8: full parity with TaskbarPin 12.76 dens residual (always_show / hide).
pin_taskbar_off() {
  # Taskbar residual only. NEVER force navigation_mode / gestural overlay —
  # that undid user 3-button vs gesture after every reboot (product heresy 2026-08-12).
  # Do not cmd overlay enable navbar.gestural here.
  settings put system enable_taskbar 0 2>/dev/null || true
  settings put system lineage_enable_taskbar 0 2>/dev/null || true
  settings put system taskbar_unpinning 1 2>/dev/null || true
  settings put system taskbar_collapse_duration 0 2>/dev/null || true
  settings put system taskbar 0 2>/dev/null || true
  settings put system show_taskbar 0 2>/dev/null || true
  settings put system launcher_taskbar_education_showing 0 2>/dev/null || true
  settings put system three_button_taskbar 0 2>/dev/null || true
  settings put system navbar_taskbar 0 2>/dev/null || true
  settings put system taskbar_force_visible 0 2>/dev/null || true
  settings put system force_taskbar 0 2>/dev/null || true
  settings put system taskbar_showing 0 2>/dev/null || true
  settings put system transient_taskbar 1 2>/dev/null || true
  settings put system taskbar_pinning 0 2>/dev/null || true
  settings put system enable_taskbar_on_phone 0 2>/dev/null || true
  settings put system taskbar_stashing_enabled 0 2>/dev/null || true
  settings put secure launcher_taskbar_education_showing 0 2>/dev/null || true
  settings put secure launcher_taskbar_rewrite_enabled 0 2>/dev/null || true
  settings put secure desktop_mode_enabled 0 2>/dev/null || true
  settings put secure enable_taskbar 0 2>/dev/null || true
  settings put secure show_taskbar 0 2>/dev/null || true
  settings put secure swipe_bottom_to_notification_enabled 1 2>/dev/null || true
  settings put secure systemui_taskbar 0 2>/dev/null || true
  settings put secure taskbar_pinned 0 2>/dev/null || true
  settings put secure taskbar_force_visible 0 2>/dev/null || true
  settings put secure launcher_taskbar_enabled 0 2>/dev/null || true
  settings put secure taskbar_showing 0 2>/dev/null || true
  settings put secure transient_taskbar 1 2>/dev/null || true
  settings put secure launcher_taskbar_pinning 0 2>/dev/null || true
  settings put secure taskbar_type 0 2>/dev/null || true
  settings put secure enable_taskbar_edu 0 2>/dev/null || true
  settings put secure taskbar_edu_tooltip_step 0 2>/dev/null || true
  settings put secure taskbar_pinning_enabled 0 2>/dev/null || true
  settings put secure launcher_taskbar_pinning_enabled 0 2>/dev/null || true
  settings put secure stashed_taskbar 0 2>/dev/null || true
  settings put secure taskbar_stashed 0 2>/dev/null || true
  settings put secure launcher_taskbar_edu_seen 1 2>/dev/null || true
  settings put secure windowed_mode_taskbar 0 2>/dev/null || true
  settings put secure enable_nav_bar_taskbar 0 2>/dev/null || true
  settings put secure taskbar_in_overview 0 2>/dev/null || true
  settings put secure overview_taskbar 0 2>/dev/null || true
  settings put secure taskbar_pinning 0 2>/dev/null || true
  settings put secure enable_taskbar_on_phone 0 2>/dev/null || true
  settings put secure launcher_taskbar_edu_tooltip_step 0 2>/dev/null || true
  settings put secure taskbar_edu_show_step 0 2>/dev/null || true
  settings put secure taskbar_stashing_enabled 0 2>/dev/null || true
  settings put secure enable_taskbar_pinning 0 2>/dev/null || true
  settings put secure launcher_taskbar_stashing_enabled 0 2>/dev/null || true
  settings put secure taskbar_enabled 0 2>/dev/null || true
  settings put secure launcher3_taskbar_enabled 0 2>/dev/null || true
  settings put secure enable_launcher_taskbar 0 2>/dev/null || true
  settings put secure taskbar_in_app 0 2>/dev/null || true
  settings put secure is_taskbar_visible 0 2>/dev/null || true
  settings put secure taskbar_force_show 0 2>/dev/null || true
  settings put secure force_show_taskbar 0 2>/dev/null || true
  settings put secure launcher_show_taskbar 0 2>/dev/null || true
  settings put secure always_show_taskbar 0 2>/dev/null || true
  settings put secure taskbar_always_show 0 2>/dev/null || true
  settings put secure taskbar_always_show_window 0 2>/dev/null || true
  settings put secure force_taskbar_visible 0 2>/dev/null || true
  settings put secure hide_taskbar 1 2>/dev/null || true
  settings put secure taskbar_hidden 1 2>/dev/null || true
  settings put secure show_taskbar_in_overview 0 2>/dev/null || true
  settings put secure taskbar_in_recents 0 2>/dev/null || true
  settings put system always_show_taskbar 0 2>/dev/null || true
  settings put system taskbar_always_show 0 2>/dev/null || true
  settings put system hide_taskbar 1 2>/dev/null || true
  settings put global launcher_taskbar_education_showing 0 2>/dev/null || true
  settings put global force_resizable_activities 0 2>/dev/null || true
  settings put global enable_freeform_support 0 2>/dev/null || true
  settings put global desktop_mode_enabled 0 2>/dev/null || true
  settings put global enable_taskbar 0 2>/dev/null || true
  settings put global show_taskbar 0 2>/dev/null || true
  settings put global development_enable_freeform_windows_support 0 2>/dev/null || true
  settings put global systemui_taskbar 0 2>/dev/null || true
  settings put global force_desktop_mode_on_external_displays 0 2>/dev/null || true
  settings put global enable_non_resizable_multi_window 0 2>/dev/null || true
  settings put global taskbar_force_visible 0 2>/dev/null || true
  settings put global taskbar_showing 0 2>/dev/null || true
  settings put global transient_taskbar 1 2>/dev/null || true
  settings put global taskbar_type 0 2>/dev/null || true
  settings put global enable_taskbar_edu 0 2>/dev/null || true
  settings put global taskbar_pinning_enabled 0 2>/dev/null || true
  settings put global stashed_taskbar 0 2>/dev/null || true
  settings put global windowed_mode_taskbar 0 2>/dev/null || true
  settings put global enable_nav_bar_taskbar 0 2>/dev/null || true
  settings put global taskbar_in_overview 0 2>/dev/null || true
  settings put global taskbar_pinning 0 2>/dev/null || true
  settings put global enable_taskbar_on_phone 0 2>/dev/null || true
  settings put global overview_taskbar 0 2>/dev/null || true
  settings put global taskbar_enabled 0 2>/dev/null || true
  settings put global launcher3_taskbar_enabled 0 2>/dev/null || true
  settings put global taskbar_in_app 0 2>/dev/null || true
  settings put global is_taskbar_visible 0 2>/dev/null || true
  settings put global taskbar_force_show 0 2>/dev/null || true
  settings put global force_show_taskbar 0 2>/dev/null || true
  settings put global enable_launcher_taskbar 0 2>/dev/null || true
  settings put global launcher_show_taskbar 0 2>/dev/null || true
  settings put global always_show_taskbar 0 2>/dev/null || true
  settings put global taskbar_always_show 0 2>/dev/null || true
  settings put global taskbar_always_show_window 0 2>/dev/null || true
  settings put global force_taskbar_visible 0 2>/dev/null || true
  settings put global hide_taskbar 1 2>/dev/null || true
  settings put global taskbar_hidden 1 2>/dev/null || true
  settings put global show_taskbar_in_overview 0 2>/dev/null || true
  settings put global taskbar_in_recents 0 2>/dev/null || true
}

pin_taskbar_off

# Snappy animations
settings put global window_animation_scale 0.75 2>/dev/null || true
settings put global transition_animation_scale 0.75 2>/dev/null || true
settings put global animator_duration_scale 0.75 2>/dev/null || true

settings put global heads_up_notifications_enabled 1 2>/dev/null || true

# Was 0.92 crush → 0.15 mild gray wash → 0 pure black (product 1.88).
# AOSP wallpaper dim is MAX of mUidToDimAmount. Multi-UID clear still stamps
# root/system/shell so a residual 0.92 cannot MAX-win.
_put_wallpaper_dim_mild() {
  cmd wallpaper dim-with-uid 0 0 >/dev/null 2>&1 || true
  cmd wallpaper dim-with-uid 1000 0 >/dev/null 2>&1 || true
  cmd wallpaper dim-with-uid 2000 0 >/dev/null 2>&1 || true
  if command -v timeout >/dev/null 2>&1; then
    timeout 3 cmd wallpaper set-dim-amount 0 >/dev/null 2>&1 \
      || settings put system wallpaper_dim_amount 0 2>/dev/null \
      || true
  else
    cmd wallpaper set-dim-amount 0 >/dev/null 2>&1 \
      || settings put system wallpaper_dim_amount 0 2>/dev/null \
      || true
  fi
  settings put system wallpaper_dim_amount 0 2>/dev/null || true
}
# v11 / pad-agent 2.111: park multi-UID wallpaper shell under load≥8 (boot + 15/45/90
# waves still forked dim-with-uid under residual heat after pad denser-belt gate).
# Settings-only stamp is cheap and keeps product dim plane for cool belt later.
_allow_dim_shell() {
  _load1=`awk '{print int($1)}' /proc/loadavg 2>/dev/null`
  case "$_load1" in ''|*[!0-9]*) _load1=0 ;; esac
  [ "$_load1" -lt 8 ] 2>/dev/null
}
if _allow_dim_shell; then
  _put_wallpaper_dim_mild
else
  settings put system wallpaper_dim_amount 0 2>/dev/null || true
  logm "dim_park load (multi-UID shell skip)"
fi

settings put global search_global 0 2>/dev/null || true
settings put system font_scale 0.95 2>/dev/null || true

setprop persist.titanus2.cube_ux 15 2>/dev/null || true
settings put global titanus2_cube_ux 15 2>/dev/null || true
setprop persist.titanus2.theme cube 2>/dev/null || true

night=$(settings get secure ui_night_mode 2>/dev/null | tr -d '\r')
nav=$(settings get secure navigation_mode 2>/dev/null | tr -d '\r')
tb=$(settings get system enable_taskbar 2>/dev/null | tr -d '\r')
ime=$(settings get secure show_ime_with_hard_keyboard 2>/dev/null | tr -d '\r')
def_ime=$(settings get secure default_input_method 2>/dev/null | tr -d '\r')
logm "done v15 night=$night nav=$nav taskbar=$tb cube=15 ime=$ime def_ime=$def_ime glow=#$ACCENT_HEX no-square"

# Waves: Launcher3 re-enables taskbar after first boot; re-pin at 15s/45s/90s.
# Re-assert dim=0 on each wave so a late polish / residual root 0.92 cannot
# re-crush gray. v11: skip multi-UID under load≥8.
(
  for d in 15 45 90; do
    sleep "$d"
    pin_taskbar_off
    if _allow_dim_shell; then
      _put_wallpaper_dim_mild
      tb2=$(settings get system enable_taskbar 2>/dev/null | tr -d '\r')
      logm "wave +${d}s taskbar=$tb2 dim=0"
    else
      settings put system wallpaper_dim_amount 0 2>/dev/null || true
      tb2=$(settings get system enable_taskbar 2>/dev/null | tr -d '\r')
      logm "wave +${d}s taskbar=$tb2 dim_park load"
    fi
  done
) >/dev/null 2>&1 &
disown 2>/dev/null || true

exit 0
