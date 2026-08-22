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
  id=$(echo "$ov" | sed 's/.*cubeicon.//;s/[^a-zA-Z0-9_]/_/g')
  [ -n "$id" ] || return 0
  cmd overlay fabricate --target "$ov" --name "cplate_$id" \
    "$ov:color/ic_launcher_bg" 0x1c "$PHEX" >/dev/null 2>&1 || true
  cmd overlay enable --user current "com.android.shell:cplate_$id" >/dev/null 2>&1 \
    || cmd overlay enable --user 0 "com.android.shell:cplate_$id" >/dev/null 2>&1 || true
  cmd overlay fabricate --target "$ov" --name "cglyph_$id" \
    "$ov:color/ic_launcher_fg" 0x1c "$GHEX" >/dev/null 2>&1 || true
  cmd overlay enable --user current "com.android.shell:cglyph_$id" >/dev/null 2>&1 \
    || cmd overlay enable --user 0 "com.android.shell:cglyph_$id" >/dev/null 2>&1 || true
}

cmd_settings() {
  # Hardcoded crimson RRO must not win over the human pick.
  # Homepage tiles alias M3 leaves — fab the leaves only (was 97 overlays).
  cmd overlay disable --user current com.titanus2.overlay.cubeicon.settings >/dev/null 2>&1 \
    || cmd overlay disable --user 0 com.titanus2.overlay.cubeicon.settings >/dev/null 2>&1 || true
  ok=0
  fail=0
  for n in \
    m3_ref_palette_yellow80 m3_ref_palette_yellow90 \
    m3_ref_palette_green80 m3_ref_palette_green90 \
    m3_ref_palette_grey80 m3_ref_palette_grey90 \
    m3_ref_palette_orange80 m3_ref_palette_orange90 \
    m3_ref_palette_blue80 m3_ref_palette_blue90 \
    m3_ref_palette_blue_variant80 m3_ref_palette_blue_variant90 \
    m3_ref_palette_cyan80 m3_ref_palette_cyan90 \
    m3_ref_palette_pink80 m3_ref_palette_pink90 \
    m3_ref_palette_purple80 m3_ref_palette_purple90 \
    m3_ref_palette_red80 m3_ref_palette_red90 \
    homepage_generic_icon_background
  do
    if apply_one "$n" "$SPHEX"; then ok=$((ok + 1)); else fail=$((fail + 1)); fi
  done
  for n in \
    m3_ref_palette_yellow30 m3_ref_palette_green30 m3_ref_palette_grey30 \
    m3_ref_palette_orange30 m3_ref_palette_blue30 m3_ref_palette_blue_variant30 \
    m3_ref_palette_cyan30 m3_ref_palette_pink30 m3_ref_palette_purple30 \
    m3_ref_palette_red30 icon_accent advanced_icon_color message_icon_color \
    settingslib_colorAccentPrimary
  do
    if apply_one "$n" "$SGHEX"; then ok=$((ok + 1)); else fail=$((fail + 1)); fi
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

# Themed app icons: plate = background (accent1 100/200), glyph = color
# (accent1 500/700). Never touch theme JSON (Monet teardown). Never dump
# neutrals/accent2/3 (SystemUI storm). Shape is launcher icon_shape_model.
cmd_icons_preset() {
  plate=$(hex6 "$(settings get global titan2_icon_plate_argb 2>/dev/null)" "$PLATE")
  glyph=$(hex6 "$(settings get global titan2_icon_glyph_argb 2>/dev/null)" "$GLYPH")
  [ -n "$plate" ] || plate=140308
  [ -n "$glyph" ] || glyph=ff141a
  plate=$(echo "$plate" | tr 'A-F' 'a-f')
  glyph=$(echo "$glyph" | tr 'A-F' 'a-f')
  cmd_fabricate_icon_colors "$plate" "$glyph"
  cmd_fabricate_launcher_icons "$plate" "$glyph"
  cmd_icon_shape
  cmd_launcher_themed
  got=$(cmd overlay lookup com.android.launcher3 \
    com.android.launcher3:color/themed_icon_color 2>/dev/null | tr -d '\r' | tr 'A-F' 'a-f')
  case "$got" in
    *"$glyph"*) echo "icons-preset cache keep (already $glyph)" ;;
    *) cmd_invalidate_icons ;;
  esac
  cmd_write_icon_proof "$plate" "$glyph"
  echo "icons-preset plate=$plate glyph=$glyph"
}

# Boot restore: re-fabricate android accents from the user glyph if missing.
# Never delete or rewrite theme_customization_overlay_packages.
cmd_icons_restore() {
  hex=$(hex6 "$(settings get global titan2_icon_glyph_argb 2>/dev/null)" "")
  case "$hex" in
    "") echo "icons-restore skip (no user glyph)" ;;
    *)
      hex=$(echo "$hex" | tr 'A-F' 'a-f')
      plate=$(hex6 "$(settings get global titan2_icon_plate_argb 2>/dev/null)" "")
      plate=$(echo "$plate" | tr 'A-F' 'a-f')
      hex_lc=$hex
      got=$(cmd overlay lookup android android:color/system_accent1_500 2>/dev/null | tr -d '\r' | tr 'A-F' 'a-f')
      gotp=$(cmd overlay lookup android android:color/system_accent1_100 2>/dev/null | tr -d '\r' | tr 'A-F' 'a-f')
      case "$got" in
        *"$hex_lc"*)
          if [ -z "$plate" ] || echo "$gotp" | grep -q "$plate"; then
            echo "icons-restore already $hex plate=$plate"
          else
            cmd_fabricate_icon_colors "${plate:-140308}" "$hex"
            echo "icons-restore glyph=$hex plate=$plate"
          fi
          ;;
        *)
          cmd_fabricate_icon_colors "${plate:-140308}" "$hex"
          echo "icons-restore glyph=$hex plate=$plate"
          ;;
      esac
      ;;
  esac
  cmd_chrome_restore
}

# Re-apply OS/navbar/QS after reboot. Monet systemui:accent comes back grey.
# Only runs when the human already saved a pick. Never writes theme JSON.
cmd_chrome_restore() {
  acc=$(hex6 "$(settings get global titan2_ui_accent_argb 2>/dev/null)" "")
  case "$acc" in
    00e5ff)
      settings put global titan2_ui_accent_argb ff141a 2>/dev/null || true
      acc=ff141a
      ;;
  esac
  case "$acc" in ??????) cmd_accent_preset ;;
    *) echo "chrome-restore accent skip" ;;
  esac
  nav=$(hex6 "$(settings get global titan2_nav_tint_argb 2>/dev/null)" "")
  case "$nav" in
    ??????) NAV_RECREATE=0 cmd_nav_preset ;;
    *) echo "chrome-restore nav skip" ;;
  esac
  g=$(settings get global titan2_ui_glass 2>/dev/null | tr -d '\r')
  case "$g" in
    1|true|on|glass) NAV_RECREATE=0 cmd_glass ;;
    *)
      qs=$(hex6 "$(settings get global titan2_qs_bg_argb 2>/dev/null)" "")
      case "$qs" in ??????) cmd_qs_bg ;;
        *) echo "chrome-restore qs skip" ;;
      esac
      ;;
  esac
  _sh=$(settings get global titan2_icon_shape 2>/dev/null | tr -d '\r')
  case "$_sh" in
    circle|squircle|rounded_rect|square|pure_square) cmd_icon_shape ;;
    *) echo "chrome-restore shape skip" ;;
  esac
  # App icons: OverlayManager drops fabricated cube_licon_* across reboot.
  # Cache wipe only when launcher lookup is not the human pick.
  _ig=$(hex6 "$(settings get global titan2_icon_glyph_argb 2>/dev/null)" "")
  case "$_ig" in
    ??????) cmd_icons_preset ;;
    *) echo "chrome-restore icons skip" ;;
  esac
  # Settings homepage tiles: OverlayManager also drops csimp_* across reboot.
  # cubeicon.settings RRO then sits on with no fabricate → blank Settings icons.
  _sm=$(settings get global titan2_settings_mono 2>/dev/null | tr -d '\r')
  case "$_sm" in
    1|true|on) cmd_settings ;;
    *) echo "chrome-restore settings skip" ;;
  esac
  unset _ig _sh _sm
  cmd_nav_alive
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

# Themed icons + OS accent read these four. Neutrals/accent2/3 rebuild SystemUI.
cmd_fabricate_icon_accents() {
  hex=$(echo "$1" | tr 'A-F' 'a-f' | tr -d '#')
  [ -n "$hex" ] || return 0
  cmd_fabricate_icon_colors "$hex" "$hex"
}

# Plate is the icon fill. Glyph is the monochrome mark. Mixing the glyph
# into 100/200 made Apply look like a no-op on background.
cmd_fabricate_icon_colors() {
  plate=$(echo "$1" | tr 'A-F' 'a-f' | tr -d '#')
  glyph=$(echo "$2" | tr 'A-F' 'a-f' | tr -d '#')
  [ -n "$plate" ] || plate=140308
  [ -n "$glyph" ] || glyph=ff141a
  _fab_one accent1 100 "0xff$plate"
  _fab_one accent1 200 "0xff$plate"
  echo "icon-colors plate=$plate glyph=$glyph"
}

# Launcher themed icons do NOT follow android system_accent1_*. They
# resolve themed_icon_* through Monet (live was #ff8c0b0e while accent1
# was already the human pick). Paint the launcher resources themselves.
_fab_launcher() {
  name=$1
  res=$2
  argb=$3
  cmd overlay fabricate --target com.android.launcher3 --name "$name" \
    "com.android.launcher3:color/$res" 0x1c "$argb" >/dev/null 2>&1 || true
  cmd overlay enable --user current "com.android.shell:$name" >/dev/null 2>&1 \
    || cmd overlay enable --user 0 "com.android.shell:$name" >/dev/null 2>&1 || true
}

cmd_fabricate_launcher_icons() {
  plate=$(echo "$1" | tr 'A-F' 'a-f' | tr -d '#')
  glyph=$(echo "$2" | tr 'A-F' 'a-f' | tr -d '#')
  [ -n "$plate" ] || plate=140308
  [ -n "$glyph" ] || glyph=ff141a
  _fab_launcher cube_licon_fg themed_icon_color "0xff$glyph"
  _fab_launcher cube_licon_badge themed_badge_icon_color "0xff$glyph"
  _fab_launcher cube_licon_bg themed_icon_background_color "0xff$plate"
  _fab_launcher cube_licon_abg themed_icon_adaptive_background_color "0xff$plate"
  echo "launcher-icons plate=$plate glyph=$glyph"
}

_fab_res() {
  _n=$1
  _t=$2
  _r=$3
  _a=$4
  _want=$(echo "$_a" | tr 'A-F' 'a-f' | sed 's/^0xff//;s/^0x//')
  _got=$(cmd overlay lookup "$_t" "$_t:color/$_r" 2>/dev/null | tr -d '\r' | tr 'A-F' 'a-f')
  case "$_got" in
    *"$_want"*) return 0 ;;
  esac
  cmd overlay fabricate --target "$_t" --name "$_n" \
    "$_t:color/$_r" 0x1c "$_a" >/dev/null 2>&1 || true
  cmd overlay enable --user current "com.android.shell:$_n" >/dev/null 2>&1 \
    || cmd overlay enable --user 0 "com.android.shell:$_n" >/dev/null 2>&1 || true
}

# 0x12 = TYPE_INT_BOOLEAN. true=0xffffffff false=0.
_fab_bool() {
  name=$1
  tgt=$2
  res=$3
  val=$4
  cmd overlay fabricate --target "$tgt" --name "$name" \
    "$tgt:bool/$res" 0x12 "$val" >/dev/null 2>&1 || true
  cmd overlay enable --user current "com.android.shell:$name" >/dev/null 2>&1 \
    || cmd overlay enable --user 0 "com.android.shell:$name" >/dev/null 2>&1 || true
}

_on_hex() {
  # Contrast ink for a seed. luma > 140 → black, else white.
  _seed_rgb "$1"
  luma=$(( (SEED_R * 299 + SEED_G * 587 + SEED_B * 114) / 1000 ))
  if [ "$luma" -gt 140 ]; then echo 000000; else echo ffffff; fi
}

# OS / navbar / QS accent. SystemUI reads system_accent1_*_dark|_light
# and system_primary_* (Monet systemui:accent was still #777). Never
# rewrite theme JSON. Never dump neutrals (SystemUI storm).
cmd_accent_preset() {
  hex=$(hex6 "$(settings get global titan2_ui_accent_argb 2>/dev/null)" ff141a)
  [ -n "$hex" ] || hex=ff141a
  hex=$(echo "$hex" | tr 'A-F' 'a-f')
  _seed_rgb "$hex"
  on=$(_on_hex "$hex")
  a100=$(_mix_w $SEED_R $SEED_G $SEED_B 55)
  a200=$(_mix_w $SEED_R $SEED_G $SEED_B 38)
  a300=$(_mix_w $SEED_R $SEED_G $SEED_B 20)
  a400=$(_mix_w $SEED_R $SEED_G $SEED_B 8)
  a500=$hex
  a600=$(_mix_k $SEED_R $SEED_G $SEED_B 12)
  a700=$(_mix_k $SEED_R $SEED_G $SEED_B 28)
  # Monet grey dual-shades win over our base accent1_500. Drop that overlay
  # so navbar/QS see the human pick. Do not disable :dynamic (surfaces).
  cmd overlay disable --user current com.android.systemui:accent >/dev/null 2>&1 \
    || cmd overlay disable --user 0 com.android.systemui:accent >/dev/null 2>&1 || true
  _fab_one accent1 400 "0xff$a400"
  _fab_one accent1 500 "0xff$a500"
  _fab_one accent1 600 "0xff$a600"
  _fab_one accent1 700 "0xff$a700"
  for pair in \
    "100 $a100" "200 $a200" "300 $a300" "400 $a400" \
    "500 $a500" "600 $a600" "700 $a700"
  do
    set -- $pair
    sh=$1
    hx=$2
    _fab_res "cube_a1_${sh}_d" android "system_accent1_${sh}_dark" "0xff$hx"
    _fab_res "cube_a1_${sh}_l" android "system_accent1_${sh}_light" "0xff$hx"
  done
  _fab_res cube_accent_def_d android accent_device_default_dark "0xff$hex"
  _fab_res cube_accent_def_l android accent_device_default_light "0xff$hex"
  # QS active tile / controls follow primary, not only accent1.
  _fab_res cube_pri_d android system_primary_dark "0xff$hex"
  _fab_res cube_pri_l android system_primary_light "0xff$hex"
  _fab_res cube_onpri_d android system_on_primary_dark "0xff$on"
  _fab_res cube_onpri_l android system_on_primary_light "0xff$on"
  _fab_res cube_pric_d android system_primary_container_dark "0xff$a600"
  _fab_res cube_pric_l android system_primary_container_light "0xff$a200"
  _fab_res cube_onpric_d android system_on_primary_container_dark "0xff$on"
  _fab_res cube_onpric_l android system_on_primary_container_light "0xff$on"
  _fab_res cube_pkey_d android system_palette_key_color_primary_dark "0xff$hex"
  _fab_res cube_pkey_l android system_palette_key_color_primary_light "0xff$hex"
  _fab_res cube_sui_acc_d com.android.systemui accent_material_dark "0xff$hex"
  _fab_res cube_sui_acc_l com.android.systemui accent_material_light "0xff$hex"
  mkdir -p /data/misc/titan2 2>/dev/null || true
  cmd overlay lookup android android:color/system_accent1_500_dark \
    >/data/misc/titan2/titan2_os_accent 2>/dev/null \
    || echo "#ff$hex" > /data/misc/titan2/titan2_os_accent
  cp -f /data/misc/titan2/titan2_os_accent /data/local/tmp/titan2_os_accent 2>/dev/null || true
  chmod 666 /data/misc/titan2/titan2_os_accent /data/local/tmp/titan2_os_accent 2>/dev/null || true
  echo "accent-preset hex=$hex dark=$(cat /data/misc/titan2/titan2_os_accent 2>/dev/null | tr -d '\r')"
}

# LOS SystemUI chrome: 3-button Back/Home/Recents + status icons + gesture pill.
# navigation_bar_icon_color is the 3-button SoT (colors.xml: "in sync with ic_sysbar_*").
# NavigationBarView caches singleToneColor in the constructor — overlay alone
# never retints live buttons. Human Apply recreates SystemUI once.
cmd_nav_preset() {
  hex=$(hex6 "$(settings get global titan2_nav_tint_argb 2>/dev/null)" ffffff)
  [ -n "$hex" ] || hex=ffffff
  hex=$(echo "$hex" | tr 'A-F' 'a-f')
  # navigation_bar_icon_color is in LOS colors.xml but this GSI SystemUI
  # APK has no such resource (fabricate → STATE_NO_IDMAP). 3-button icons
  # use singleToneColor → dark/light_mode_icon_color_single_tone.
  _fab_res cube_nav_h_d com.android.systemui navigation_bar_home_handle_dark_color "0xff$hex"
  _fab_res cube_nav_h_l com.android.systemui navigation_bar_home_handle_light_color "0xff$hex"
  _fab_res cube_nav_ico com.android.systemui dark_mode_icon_color_single_tone "0xff$hex"
  _fab_res cube_nav_ico_l com.android.systemui light_mode_icon_color_single_tone "0xff$hex"
  _fab_res cube_nav_d_fill com.android.systemui dark_mode_icon_color_dual_tone_fill "0xff$hex"
  _fab_res cube_nav_d_bg com.android.systemui dark_mode_icon_color_dual_tone_background "0x66$hex"
  _fab_res cube_nav_l_fill com.android.systemui light_mode_icon_color_dual_tone_fill "0xff$hex"
  _fab_res cube_nav_l_bg com.android.systemui light_mode_icon_color_dual_tone_background "0x66$hex"
  _fab_res cube_nav_qs_d com.android.systemui dark_mode_qs_icon_color_single_tone "0xff$hex"
  _fab_res cube_nav_qs_df com.android.systemui dark_mode_qs_icon_color_dual_tone_fill "0xff$hex"
  _fab_res cube_sb_clock com.android.systemui status_bar_clock_color "0xff$hex"
  mkdir -p /data/misc/titan2 2>/dev/null || true
  echo "#ff$hex" > /data/misc/titan2/titan2_nav_tint 2>/dev/null || true
  cmd overlay lookup com.android.systemui \
    com.android.systemui:color/navigation_bar_icon_color \
    >/data/misc/titan2/titan2_nav_tint 2>/dev/null || true
  cp -f /data/misc/titan2/titan2_nav_tint /data/local/tmp/titan2_nav_tint 2>/dev/null || true
  chmod 666 /data/misc/titan2/titan2_nav_tint /data/local/tmp/titan2_nav_tint 2>/dev/null || true
  # Never disable threebutton. Never SIGTERM SystemUI. That hid Taskbar
  # (the 3-button window on this GSI) and killed Back/Home/Recents.
  echo "nav-preset hex=$hex"
}

_nav_recreate() {
  echo "nav-recreate skipped (breaks 3-button Taskbar)"
}

# Keep 3-button Taskbar (this GSI's navbar) pinned + unhidden.
# LauncherPrefs.TASKBAR_PINNING is DEVICE_PROTECTED boot_aware_prefs.xml.
cmd_nav_alive() {
  nav=$(settings get secure navigation_mode 2>/dev/null | tr -d '\r')
  case "$nav" in
    0|1) ;;
    *) echo "nav-alive skip mode=$nav"; return 0 ;;
  esac
  settings put system force_show_navbar 1 2>/dev/null || true
  settings put system enable_taskbar 0 2>/dev/null || true
  settings put secure hide_taskbar 0 2>/dev/null || true
  settings put secure taskbar_hidden 0 2>/dev/null || true
  settings put secure taskbar_pinned 1 2>/dev/null || true
  settings put secure launcher_taskbar_pinning 1 2>/dev/null || true
  settings put secure transient_taskbar 0 2>/dev/null || true
  settings put global hide_taskbar 0 2>/dev/null || true
  cmd overlay disable --user current com.android.internal.systemui.navbar.transparent >/dev/null 2>&1 \
    || cmd overlay disable --user 0 com.android.internal.systemui.navbar.transparent >/dev/null 2>&1 || true
  _xml="<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name=\"TASKBAR_PINNING_KEY\" value=\"true\" />
</map>
"
  for _d in /data/user_de/0/com.android.launcher3 /data/user/0/com.android.launcher3; do
    [ -d "$_d" ] || continue
    mkdir -p "$_d/shared_prefs" 2>/dev/null || true
    _f="$_d/shared_prefs/boot_aware_prefs.xml"
    if [ -f "$_f" ] && grep -q 'name="TASKBAR_PINNING_KEY"' "$_f" 2>/dev/null; then
      sed -i 's/name="TASKBAR_PINNING_KEY" value="false"/name="TASKBAR_PINNING_KEY" value="true"/' "$_f" 2>/dev/null || true
    elif [ -f "$_f" ]; then
      sed -i 's|</map>|<boolean name="TASKBAR_PINNING_KEY" value="true" />\n</map>|' "$_f" 2>/dev/null || true
    else
      printf '%s' "$_xml" > "$_f" 2>/dev/null || true
    fi
    _own=$(stat -c %u:%g "$_d" 2>/dev/null || true)
    [ -n "$_own" ] && chown "$_own" "$_f" 2>/dev/null || true
    chmod 660 "$_f" 2>/dev/null || true
  done
  unset _d _f _own _xml nav
  am force-stop com.android.launcher3 >/dev/null 2>&1 || true
  echo "nav-alive pinned"
}

# QS + notification shade. LOS colors.xml: shade_panel_fallback, scrim, notif.
cmd_qs_bg() {
  g=$(settings get global titan2_ui_glass 2>/dev/null | tr -d '\r')
  case "$g" in 1|true|on|glass) cmd_glass; return 0 ;; esac
  hex=$(hex6 "$(settings get global titan2_qs_bg_argb 2>/dev/null)" 000000)
  [ -n "$hex" ] || hex=000000
  hex=$(echo "$hex" | tr 'A-F' 'a-f')
  on=$(_on_hex "$hex")
  _fab_bool cube_scrim_glass com.android.systemui notification_scrim_transparent 0x0
  cmd overlay disable --user current com.android.internal.systemui.navbar.transparent >/dev/null 2>&1 || true
  _fab_res cube_qs_fb com.android.systemui shade_panel_fallback "0xff$hex"
  _fab_res cube_qs_scrim com.android.systemui shade_scrim_background_dark "0xff$hex"
  _fab_res cube_qs_m3 com.android.systemui m3_sys_color_dark_surface "0xff$hex"
  _fab_res cube_qs_m3d com.android.systemui m3_sys_color_dynamic_dark_surface "0xff$hex"
  _fab_res cube_qs_m3c com.android.systemui m3_sys_color_dark_surface_container "0xff$hex"
  _fab_res cube_qs_m3dc com.android.systemui m3_sys_color_dynamic_dark_surface_container "0xff$hex"
  _fab_res cube_qs_mat com.android.systemui background_material_dark "0xff$hex"
  _fab_res cube_notif_base com.android.systemui notification_scrim_base "0xff$hex"
  _fab_res cube_notif_fb com.android.systemui notification_scrim_fallback "0xff$hex"
  _fab_res cube_notif_leg com.android.systemui notification_legacy_background_color "0xff$hex"
  _fab_res cube_shade_txt com.android.systemui shade_header_text_color "0xff$on"
  _fab_res cube_shade_tbg com.android.systemui shade_header_text_color_bg "0xff$hex"
  _fab_res cube_surf_d android system_surface_dark "0xff$hex"
  _fab_res cube_bg_d android system_background_dark "0xff$hex"
  _fab_res cube_surfc_d android system_surface_container_dark "0xff$hex"
  mkdir -p /data/misc/titan2 2>/dev/null || true
  cmd overlay lookup com.android.systemui \
    com.android.systemui:color/shade_panel_fallback \
    >/data/misc/titan2/titan2_qs_bg 2>/dev/null \
    || echo "#ff$hex" > /data/misc/titan2/titan2_qs_bg
  cp -f /data/misc/titan2/titan2_qs_bg /data/local/tmp/titan2_qs_bg 2>/dev/null || true
  chmod 666 /data/misc/titan2/titan2_qs_bg /data/local/tmp/titan2_qs_bg 2>/dev/null || true
  echo "qs-bg hex=$hex"
}

# Glass = LOS translucent scrim + wallpaper blur + transparent navbar +
# launcher all-apps surface. Not opaque #ffRRGGBB. Seed RGB from QS pick.
cmd_glass() {
  settings put global titan2_ui_glass 1 2>/dev/null || true
  hex=$(hex6 "$(settings get global titan2_qs_bg_argb 2>/dev/null)" 000000)
  [ -n "$hex" ] || hex=000000
  hex=$(echo "$hex" | tr 'A-F' 'a-f')
  on=$(_on_hex "$hex")
  # 0x88 = ~53% — wallpaper reads through. Opaque 0xff was the heresy.
  argb=0x88$hex
  _fab_bool cube_scrim_glass com.android.systemui notification_scrim_transparent 0xffffffff
  _fab_bool cube_blur_wp com.android.systemui config_supportBlurredWallpaper 0xffffffff
  setprop persist.sys.sf.disable_blurs 0 2>/dev/null || true
  # navbar.transparent hid the 3-button Taskbar. Glass is QS/drawer alpha only.
  cmd overlay disable --user current com.android.internal.systemui.navbar.transparent >/dev/null 2>&1 \
    || cmd overlay disable --user 0 com.android.internal.systemui.navbar.transparent >/dev/null 2>&1 || true
  _fab_res cube_qs_fb com.android.systemui shade_panel_fallback "$argb"
  _fab_res cube_qs_scrim com.android.systemui shade_scrim_background_dark "$argb"
  _fab_res cube_qs_m3 com.android.systemui m3_sys_color_dark_surface "$argb"
  _fab_res cube_qs_m3d com.android.systemui m3_sys_color_dynamic_dark_surface "$argb"
  _fab_res cube_qs_m3c com.android.systemui m3_sys_color_dark_surface_container "$argb"
  _fab_res cube_qs_m3dc com.android.systemui m3_sys_color_dynamic_dark_surface_container "$argb"
  _fab_res cube_qs_mat com.android.systemui background_material_dark "$argb"
  _fab_res cube_notif_base com.android.systemui notification_scrim_base "$argb"
  _fab_res cube_notif_fb com.android.systemui notification_scrim_fallback "$argb"
  _fab_res cube_notif_leg com.android.systemui notification_legacy_background_color "$argb"
  _fab_res cube_shade_txt com.android.systemui shade_header_text_color "0xff$on"
  _fab_res cube_shade_tbg com.android.systemui shade_header_text_color_bg "$argb"
  _fab_res cube_surf_d android system_surface_dark "$argb"
  _fab_res cube_bg_d android system_background_dark "$argb"
  _fab_res cube_surfc_d android system_surface_container_dark "$argb"
  _fab_res cube_drawer com.android.launcher3 materialColorSurfaceDim "$argb"
  _fab_res cube_drawer_c com.android.launcher3 materialColorSurfaceContainer "$argb"
  _fab_res cube_drawer_cl com.android.launcher3 materialColorSurfaceContainerLow "$argb"
  _fab_res cube_drawer_scrim com.android.launcher3 wallpaper_popup_scrim "$argb"
  mkdir -p /data/misc/titan2 2>/dev/null || true
  echo "$argb" > /data/misc/titan2/titan2_qs_bg 2>/dev/null || true
  cmd overlay lookup com.android.systemui \
    com.android.systemui:color/shade_panel_fallback \
    >/data/misc/titan2/titan2_qs_bg 2>/dev/null || true
  cp -f /data/misc/titan2/titan2_qs_bg /data/local/tmp/titan2_qs_bg 2>/dev/null || true
  chmod 666 /data/misc/titan2/titan2_qs_bg /data/local/tmp/titan2_qs_bg 2>/dev/null || true
  if [ "${NAV_RECREATE:-1}" = "1" ]; then
    _nav_recreate
    am force-stop com.android.launcher3 >/dev/null 2>&1 || true
  fi
  echo "glass argb=$argb recreate=${NAV_RECREATE:-1}"
}

cmd_solid() {
  settings put global titan2_ui_glass 0 2>/dev/null || true
  cmd_qs_bg
  if [ "${NAV_RECREATE:-1}" = "1" ]; then
    _nav_recreate
    am force-stop com.android.launcher3 >/dev/null 2>&1 || true
  fi
  echo "solid"
}

cmd_icon_shape() {
  model=$(settings get global titan2_icon_shape 2>/dev/null | tr -d '\r')
  case "$model" in
    circle|squircle|rounded_rect|square|pure_square) ;;
    *) model=pure_square ;;
  esac
  pref=/data/user/0/com.android.launcher3/shared_prefs/com.android.launcher3.prefs.xml
  old=""
  if [ -f "$pref" ]; then
    old=$(sed -n 's/.*name="icon_shape_model">\([^<]*\).*/\1/p' "$pref" | head -1)
    if grep -q 'name="icon_shape_model"' "$pref" 2>/dev/null; then
      sed -i 's/name="icon_shape_model">[^<]*/name="icon_shape_model">'"$model"'/' \
        "$pref" 2>/dev/null || true
    else
      sed -i 's|</map>|<string name="icon_shape_model">'"$model"'</string>\n</map>|' \
        "$pref" 2>/dev/null || true
    fi
  fi
  case "$model" in
    pure_square|square)
      cmd overlay enable --user current com.titanus2.overlay.cubemask >/dev/null 2>&1 \
        || cmd overlay enable --user 0 com.titanus2.overlay.cubemask >/dev/null 2>&1 || true
      ;;
    *)
      cmd overlay disable --user current com.titanus2.overlay.cubemask >/dev/null 2>&1 \
        || cmd overlay disable --user 0 com.titanus2.overlay.cubemask >/dev/null 2>&1 || true
      ;;
  esac
  echo "icon-shape model=$model was=$old"
}

cmd_write_icon_proof() {
  plate=$1
  glyph=$2
  mkdir -p /data/misc/titan2 2>/dev/null || true
  cmd overlay lookup com.android.launcher3 \
    com.android.launcher3:color/themed_icon_color \
    >/data/misc/titan2/titan2_icon_accent 2>/dev/null \
    || echo "#ff$glyph" > /data/misc/titan2/titan2_icon_accent
  cmd overlay lookup com.android.launcher3 \
    com.android.launcher3:color/themed_icon_adaptive_background_color \
    >/data/misc/titan2/titan2_icon_plate 2>/dev/null \
    || echo "#ff$plate" > /data/misc/titan2/titan2_icon_plate
  settings get global titan2_icon_shape >/data/misc/titan2/titan2_icon_shape 2>/dev/null || true
  cp -f /data/misc/titan2/titan2_icon_accent /data/local/tmp/titan2_icon_accent 2>/dev/null || true
  cp -f /data/misc/titan2/titan2_icon_plate /data/local/tmp/titan2_icon_plate 2>/dev/null || true
  chmod 666 /data/misc/titan2/titan2_icon_accent /data/misc/titan2/titan2_icon_plate \
    /data/misc/titan2/titan2_icon_shape /data/local/tmp/titan2_icon_accent \
    /data/local/tmp/titan2_icon_plate 2>/dev/null || true
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
    1|true|on) cmd_settings ;;
    *) cmd_settings_off ;;
  esac
  # Icon color is icons-preset (four overlays). Never dump Monet from apply.
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
  chrome-restore) cmd_chrome_restore ;;
  accent-preset) cmd_accent_preset ;;
  nav-preset) cmd_nav_preset ;;
  nav-alive) cmd_nav_alive ;;
  qs-bg) cmd_qs_bg ;;
  glass) cmd_glass ;;
  solid) cmd_solid ;;
  *) echo "usage: titan2-cube-icons.sh apply|status|reset|apps-on|apps-off|settings-on|settings-off|match-term|icons-preset|icons-restore|accent-preset|nav-preset|qs-bg" >&2; exit 2 ;;
esac
