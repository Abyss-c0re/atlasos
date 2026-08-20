#!/system/bin/sh
# Cube icon plane — Settings fabricate + cubeicon RROs + cubemask.
# Color plane writes theme_customization_* as preset (not wallpaper).
# Never writes the wallpaper image. Never replaces app-icon glyphs.
# Plane (Settings.Global, RRGGBB):
#   titan2_icon_plate_argb
#   titan2_icon_glyph_argb
# Usage: apply|status|reset|apps-on|apps-off|match-term
export PATH=/system/bin:/system/xbin:/product/bin:$PATH

hex6() {
  s=$(echo "$1" | tr -d '\r' | tr 'A-F' 'a-f' | sed 's/^0x//;s/^#//')
  case "$s" in
    null|""|*" "*) echo "$2" ;;
    ???????? ) echo "$s" | cut -c3-8 ;;
    ?????? ) echo "$s" ;;
    *) echo "$2" ;;
  esac
}
PLATE=$(hex6 "$(settings get global titan2_icon_plate_argb 2>/dev/null)" 140308)
GLYPH=$(hex6 "$(settings get global titan2_icon_glyph_argb 2>/dev/null)" ff141a)
# Settings homepage is its own plane. Default = crimson (void + spike).
SPLATE=$(hex6 "$(settings get global titan2_settings_plate_argb 2>/dev/null)" 000000)
SGLYPH=$(hex6 "$(settings get global titan2_settings_glyph_argb 2>/dev/null)" ff141a)
PHEX=0xff$PLATE
GHEX=0xff$GLYPH
SPHEX=0xff$SPLATE
SGHEX=0xff$SGLYPH

NAMES='
homepage_about_background homepage_about_foreground
homepage_accessibility_background homepage_accessibility_foreground
homepage_accounts_background homepage_accounts_foreground
homepage_apps_background homepage_apps_foreground
homepage_battery_background homepage_battery_foreground
homepage_blue_bg homepage_blue_fg
homepage_blue_variant_bg homepage_blue_variant_fg
homepage_connected_device_background homepage_connected_device_foreground
homepage_cyan_bg homepage_cyan_fg
homepage_display_background homepage_display_foreground
homepage_generic_icon_background
homepage_green_bg homepage_green_fg
homepage_grey_bg homepage_grey_fg
homepage_hub_mode_background homepage_hub_mode_foreground
homepage_location_background homepage_location_foreground
homepage_modes_background homepage_modes_foreground
homepage_network_background homepage_network_foreground
homepage_notification_background homepage_notification_foreground
homepage_orange_bg homepage_orange_fg
homepage_pink_bg homepage_pink_fg
homepage_purple_bg homepage_purple_fg
homepage_red_bg homepage_red_fg
homepage_safety_background homepage_safety_foreground
homepage_security_background homepage_security_foreground
homepage_sound_background homepage_sound_foreground
homepage_storage_background homepage_storage_foreground
homepage_supervision_background homepage_supervision_foreground
homepage_support_background homepage_support_foreground
homepage_system_background homepage_system_foreground
homepage_wallpaper_background homepage_wallpaper_foreground
homepage_yellow_bg homepage_yellow_fg
icon_accent dark_mode_icon_color_single_tone light_mode_icon_color_single_tone
advanced_icon_color message_icon_color settingslib_colorAccentPrimary
m3_ref_palette_yellow80 m3_ref_palette_yellow90 m3_ref_palette_yellow30
m3_ref_palette_green80 m3_ref_palette_green90 m3_ref_palette_green30
m3_ref_palette_grey80 m3_ref_palette_grey90 m3_ref_palette_grey30
m3_ref_palette_orange80 m3_ref_palette_orange90 m3_ref_palette_orange30
m3_ref_palette_blue80 m3_ref_palette_blue90 m3_ref_palette_blue30
m3_ref_palette_blue_variant80 m3_ref_palette_blue_variant90 m3_ref_palette_blue_variant30
m3_ref_palette_cyan80 m3_ref_palette_cyan90 m3_ref_palette_cyan30
m3_ref_palette_pink80 m3_ref_palette_pink90 m3_ref_palette_pink30
m3_ref_palette_purple80 m3_ref_palette_purple90 m3_ref_palette_purple30
m3_ref_palette_red80 m3_ref_palette_red90 m3_ref_palette_red30
'

apply_one() {
  n=$1
  v=$2
  name=csimp_$n
  cmd overlay fabricate --target com.android.settings --name "$name" \
    "com.android.settings:color/$n" 0x1c "$v" >/dev/null 2>&1 || return 1
  cmd overlay enable --user current "com.android.shell:$name" >/dev/null 2>&1 || return 1
  return 0
}

tone_one() {
  ov=$1
  cmd overlay fabricate --target "$ov" --name "cplate" \
    "$ov:color/ic_launcher_bg" 0x1c "$PHEX" >/dev/null 2>&1 || true
  cmd overlay enable --user current "com.android.shell:cplate" >/dev/null 2>&1 || true
  cmd overlay fabricate --target "$ov" --name "cglyph" \
    "$ov:color/ic_launcher_fg" 0x1c "$GHEX" >/dev/null 2>&1 || true
  cmd overlay enable --user current "com.android.shell:cglyph" >/dev/null 2>&1 || true
}

cmd_settings() {
  # Hardcoded crimson RRO must not win over the human pick.
  cmd overlay disable --user current com.titanus2.overlay.cubeicon.settings >/dev/null 2>&1 \
    || cmd overlay disable --user 0 com.titanus2.overlay.cubeicon.settings >/dev/null 2>&1 || true
  ok=0
  fail=0
  for n in $NAMES; do
    case "$n" in
      *_background|*_bg|homepage_generic_icon_background|m3_ref_palette_*80|m3_ref_palette_*90) v=$SPHEX ;;
      *) v=$SGHEX ;;
    esac
    if apply_one "$n" "$v"; then
      ok=$((ok + 1))
    else
      fail=$((fail + 1))
    fi
  done
  am force-stop com.android.settings >/dev/null 2>&1 || true
  echo "settings plate=$SPLATE glyph=$SGLYPH ok=$ok fail=$fail"
}

cmd_settings_off() {
  n=0
  for ov in $(cmd overlay list --user current 2>/dev/null | sed -n 's/^\[.\] //p' | grep 'com.android.shell:csimp_'); do
    cmd overlay disable --user current "$ov" >/dev/null 2>&1 || true
    n=$((n + 1))
  done
  cmd overlay disable --user current com.titanus2.overlay.cubeicon.settings >/dev/null 2>&1 \
    || cmd overlay disable --user 0 com.titanus2.overlay.cubeicon.settings >/dev/null 2>&1 || true
  echo "settings-off disabled=$n"
}

cmd_apps_on() {
  cmd overlay enable --user current com.titanus2.overlay.cubemask >/dev/null 2>&1 \
    || cmd overlay enable --user 0 com.titanus2.overlay.cubemask >/dev/null 2>&1 || true
  n=0
  for ov in $(cmd overlay list --user current 2>/dev/null | awk '/cubeicon/{print $NF}'); do
    cmd overlay enable --user current "$ov" >/dev/null 2>&1 \
      || cmd overlay enable --user 0 "$ov" >/dev/null 2>&1 || true
    tone_one "$ov"
    n=$((n + 1))
  done
  echo "apps-on n=$n"
}

cmd_apps_off() {
  n=0
  for ov in $(cmd overlay list --user current 2>/dev/null | awk '/cubeicon/{print $NF}'); do
    case "$ov" in
      *cubeicon.settings) continue ;;
    esac
    cmd overlay disable --user current "$ov" >/dev/null 2>&1 \
      || cmd overlay disable --user 0 "$ov" >/dev/null 2>&1 || true
    n=$((n + 1))
  done
  echo "apps-off n=$n"
}

cmd_launcher_cube() {
  # Color-only Cube: themed icons stay ON. Color comes from preset fabricate,
  # not wallpaper. Never flip themed-icons off (that is stock/wallpaper look).
  cmd_launcher_themed
}

cmd_launcher_themed() {
  pref=/data/user/0/com.android.launcher3/shared_prefs/com.android.launcher3.prefs.xml
  [ -f "$pref" ] || { echo "launcher-pref missing"; return 0; }
  [ -f "$pref.bak-cube" ] || cp -f "$pref" "$pref.bak-cube" 2>/dev/null || true
  sed -i \
    's/name="pref_allapps_themed_icons" value="false"/name="pref_allapps_themed_icons" value="true"/' \
    "$pref" 2>/dev/null || true
  if ! grep -q 'name="pref_allapps_themed_icons"' "$pref" 2>/dev/null; then
    sed -i 's|</map>|<boolean name="pref_allapps_themed_icons" value="true" />\n</map>|' \
      "$pref" 2>/dev/null || true
  fi
  if ! grep -q 'name="icon_theme_id"' "$pref" 2>/dev/null; then
    sed -i 's|</map>|<string name="icon_theme_id">mono-icons:with-theme</string>\n</map>|' \
      "$pref" 2>/dev/null || true
  fi
  echo "launcher themed (mono-icons:with-theme)"
}

cmd_launcher_stock() {
  # Never flip themed-icons off and never bind color_source to wallpaper.
  echo "launcher stock skipped (wallpaper color_source is heresy)"
}

cmd_invalidate_icons() {
  am force-stop com.android.launcher3 >/dev/null 2>&1 || true
  rm -f \
    /data/user/0/com.android.launcher3/databases/app_icons.db \
    /data/user/0/com.android.launcher3/databases/app_icons.db-journal \
    /data/user/0/com.android.launcher3/databases/app_icons.db-wal \
    /data/user/0/com.android.launcher3/databases/app_icons.db-shm
  echo "launcher icon cache wiped"
}

# Keep themed icons, but color from Cube/term preset — not wallpaper.
# ThemeOverlayController only fabricates Monet if system_palette is a hex
# OverlayIdentifier and _applied_timestamp is newer than last apply.
# Human Apply only. Boot must call icons-restore (no theme JSON rewrite).
cmd_icons_preset() {
  hex=$(hex6 "$(settings get global titan2_icon_glyph_argb 2>/dev/null)" "$GLYPH")
  [ -n "$hex" ] || hex=ff141a
  hex=$(echo "$hex" | tr 'a-f' 'A-F')
  # Concat — 32-bit $((sec*1000)) overflows and ThemeOverlay skips stale ts.
  ts=$(date +%s 2>/dev/null || echo 0)000
  json="{\"_applied_timestamp\":$ts,\"android.theme.customization.theme_style\":\"MONOCHROMATIC\",\"android.theme.customization.color_source\":\"preset\",\"android.theme.customization.system_palette\":\"$hex\",\"android.theme.customization.accent_color\":\"$hex\"}"
  settings delete secure theme_customization_overlay_packages 2>/dev/null || true
  settings put secure theme_customization_overlay_packages "$json" 2>/dev/null || true
  settings put secure accessibility_force_invert_color_enabled 0 2>/dev/null || true
  settings put secure accessibility_display_inversion_enabled 0 2>/dev/null || true
  cmd_fabricate_android_palette "$hex"
  cmd_fabricate_android_neutrals "$hex"
  cmd_launcher_themed
  # Never force-stop launcher / wipe icon db here — that froze SystemUI in a loop.
  echo "icons-preset glyph=$hex ts=$ts"
}

# Boot restore: re-fabricate android accents from the user glyph if missing.
# Never delete or rewrite theme_customization_overlay_packages.
cmd_icons_restore() {
  hex=$(hex6 "$(settings get global titan2_icon_glyph_argb 2>/dev/null)" "")
  case "$hex" in
    "") echo "icons-restore skip (no user glyph)"; return 0 ;;
  esac
  hex=$(echo "$hex" | tr 'a-f' 'A-F')
  hex_lc=$(echo "$hex" | tr 'A-F' 'a-f')
  got=$(cmd overlay lookup android android:color/system_accent1_500 2>/dev/null | tr -d '\r' | tr 'A-F' 'a-f')
  case "$got" in
    *"$hex_lc"*)
      echo "icons-restore already $hex"
      return 0
      ;;
  esac
  cmd_fabricate_android_palette "$hex"
  cmd_fabricate_android_neutrals "$hex"
  echo "icons-restore glyph=$hex"
}

# Monet is not fabricating from JSON on this GSI (accent stays #777).
# One overlay per color — OverlayManager rejects multi-resource fabricate.
# Shades are mixed from the seed — hardcoded red made custom Apply look like a no-op.
_hex_dec() { printf '%d' "0x$1" 2>/dev/null || echo 0; }
_hex2() {
  v=$1
  [ "$v" -lt 0 ] 2>/dev/null && v=0
  [ "$v" -gt 255 ] 2>/dev/null && v=255
  printf '%02x' "$v"
}
# p=0 seed, p=100 white
_mix_w() {
  echo "$(_hex2 $(( ($1 * (100 - $4) + 255 * $4) / 100 )))$(_hex2 $(( ($2 * (100 - $4) + 255 * $4) / 100 )))$(_hex2 $(( ($3 * (100 - $4) + 255 * $4) / 100 )))"
}
# p=0 seed, p=100 black
_mix_k() {
  echo "$(_hex2 $(( $1 * (100 - $4) / 100 )))$(_hex2 $(( $2 * (100 - $4) / 100 )))$(_hex2 $(( $3 * (100 - $4) / 100 )))"
}

_seed_rgb() {
  hex=$(echo "$1" | tr 'A-F' 'a-f' | tr -d '#')
  SEED_R=$(_hex_dec "$(echo "$hex" | cut -c1-2)")
  SEED_G=$(_hex_dec "$(echo "$hex" | cut -c3-4)")
  SEED_B=$(_hex_dec "$(echo "$hex" | cut -c5-6)")
}

_fab_one() {
  fam=$1
  sh=$2
  argb=$3
  name=cube_${fam}_$sh
  cmd overlay fabricate --target android --name "$name" \
    "android:color/system_${fam}_$sh" 0x1c "$argb" >/dev/null 2>&1 || true
  cmd overlay enable --user current "com.android.shell:$name" >/dev/null 2>&1 \
    || cmd overlay enable --user 0 "com.android.shell:$name" >/dev/null 2>&1 || true
}

cmd_fabricate_android_palette() {
  hex=$(echo "$1" | tr 'A-F' 'a-f' | tr -d '#')
  [ -n "$hex" ] || return 0
  _seed_rgb "$hex"
  set -- \
    0 "$(_mix_w $SEED_R $SEED_G $SEED_B 92)" \
    10 "$(_mix_w $SEED_R $SEED_G $SEED_B 84)" \
    50 "$(_mix_w $SEED_R $SEED_G $SEED_B 70)" \
    100 "$(_mix_w $SEED_R $SEED_G $SEED_B 55)" \
    200 "$(_mix_w $SEED_R $SEED_G $SEED_B 38)" \
    300 "$(_mix_w $SEED_R $SEED_G $SEED_B 20)" \
    400 "$(_mix_w $SEED_R $SEED_G $SEED_B 8)" \
    500 "$hex" \
    600 "$(_mix_k $SEED_R $SEED_G $SEED_B 12)" \
    700 "$(_mix_k $SEED_R $SEED_G $SEED_B 28)" \
    800 "$(_mix_k $SEED_R $SEED_G $SEED_B 45)" \
    900 "$(_mix_k $SEED_R $SEED_G $SEED_B 65)" \
    1000 "$(_mix_k $SEED_R $SEED_G $SEED_B 82)"
  ok=0
  while [ $# -ge 2 ]; do
    sh=$1
    hx=$2
    shift 2
    for fam in accent1 accent2 accent3; do
      _fab_one "$fam" "$sh" "0xff$hx"
      ok=$((ok + 1))
    done
  done
  echo "android-palette n=$ok seed=$hex"
}

cmd_fabricate_android_neutrals() {
  hex=$(echo "${1:-$GLYPH}" | tr 'A-F' 'a-f' | tr -d '#')
  [ -n "$hex" ] || hex=ff141a
  _seed_rgb "$hex"
  set -- \
    0 "ffffff" \
    10 "$(_mix_w $SEED_R $SEED_G $SEED_B 94)" \
    50 "$(_mix_w $SEED_R $SEED_G $SEED_B 86)" \
    100 "$(_mix_w $SEED_R $SEED_G $SEED_B 78)" \
    200 "$(_mix_w $SEED_R $SEED_G $SEED_B 62)" \
    300 "$(_mix_k $SEED_R $SEED_G $SEED_B 35)" \
    400 "$(_mix_k $SEED_R $SEED_G $SEED_B 50)" \
    500 "$(_mix_k $SEED_R $SEED_G $SEED_B 62)" \
    600 "$(_mix_k $SEED_R $SEED_G $SEED_B 72)" \
    700 "$(_mix_k $SEED_R $SEED_G $SEED_B 80)" \
    800 "$(_mix_k $SEED_R $SEED_G $SEED_B 88)" \
    900 "$(_mix_k $SEED_R $SEED_G $SEED_B 94)" \
    1000 "000000"
  ok=0
  while [ $# -ge 2 ]; do
    sh=$1
    hx=$2
    shift 2
    for fam in neutral1 neutral2; do
      _fab_one "$fam" "$sh" "0xff$hx"
      ok=$((ok + 1))
    done
  done
  echo "android-neutrals n=$ok seed=$hex"
}

cmd_apply() {
  SMONO=$(settings get global titan2_settings_mono 2>/dev/null | tr -d '\r')
  case "$SMONO" in
    0|false|off) cmd_settings_off ;;
    *) cmd_settings ;;
  esac
  cmd_icons_preset
  APPS=$(settings get global titan2_icon_apps 2>/dev/null | tr -d '\r')
  case "$APPS" in
    0|false|off) echo "apps skipped" ;;
    *) cmd_apps_on ;;
  esac
  echo "cube-icons apply plate=$PLATE glyph=$GLYPH settings=$SPLATE/$SGLYPH"
}

cmd_status() {
  echo "plane plate=$PLATE glyph=$GLYPH"
  echo -n "network_bg="
  cmd overlay lookup com.android.settings com.android.settings:color/homepage_network_background 2>/dev/null | tr -d '\r'
  echo -n "network_fg="
  cmd overlay lookup com.android.settings com.android.settings:color/homepage_network_foreground 2>/dev/null | tr -d '\r'
  echo -n "cubemask="
  cmd overlay list --user current 2>/dev/null | grep cubemask | head -1
  echo -n "cubeicon_on="
  cmd overlay list --user current 2>/dev/null | grep '\[x\].*cubeicon' | wc -l
}

cmd_reset() {
  n=0
  for ov in $(cmd overlay list --user current 2>/dev/null | sed -n 's/^\[.\] //p' | grep 'com.android.shell:csimp_'); do
    cmd overlay disable --user current "$ov" >/dev/null 2>&1 || true
    n=$((n + 1))
  done
  cmd_apps_off
  cmd_launcher_stock
  echo "cube-icons reset disabled=$n"
}

cmd_match_term() {
  FG=$(hex6 "$(settings get global titan2_term_fg_argb 2>/dev/null)" ff141a)
  BG=$(hex6 "$(settings get global titan2_term_bg_argb 2>/dev/null)" 000000)
  settings put global titan2_icon_glyph_argb "$FG" 2>/dev/null || true
  settings put global titan2_icon_plate_argb "$BG" 2>/dev/null || true
  PLATE=$BG
  GLYPH=$FG
  PHEX=0xff$PLATE
  GHEX=0xff$GLYPH
  cmd_apply
}

case "${1:-apply}" in
  apply) cmd_apply ;;
  status) cmd_status ;;
  reset) cmd_reset ;;
  apps-on) cmd_apps_on ;;
  apps-off) cmd_apps_off ;;
  settings-on) cmd_settings ;;
  settings-off) cmd_settings_off ;;
  match-term) cmd_match_term ;;
  icons-preset) cmd_icons_preset ;;
  icons-restore) cmd_icons_restore ;;
  *) echo "usage: titan2-cube-icons.sh apply|status|reset|apps-on|apps-off|settings-on|settings-off|match-term|icons-preset|icons-restore" >&2; exit 2 ;;
esac
