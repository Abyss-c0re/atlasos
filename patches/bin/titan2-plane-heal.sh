#!/system/bin/sh
# titan2-plane-heal — OPTIMIZE Phase 3 peel: phone plane heals (non-pad-hot)
# SoT: docs/project/OPTIMIZE_SOURCE_PRODUCT.md
# Invoked by pad-agent:
#   ghost_host   — sticky specials/arrows → off when no exclusive HID
#   sensor_qs    — strip Titan privacy QS; keep stock cam/mic toggles
#   long_press   — pin secure long_press_timeout=400 (heal 2800 poison)
#   soft_ime     — show_ime_with_hard_keyboard policy vs HID grab
#   latinime     — empty default IME → AOSP LatinIME (never ban Pastiera)
#   boot         — long_press + soft_ime + latinime (agent claim path)
#   version
export PATH=/system/bin:/system/xbin:/vendor/bin:$PATH
T2=/data/misc/titan2
ST=/data/local/tmp
HEAL_VER=2.183-plane-heal-peel

log() {
  mkdir -p "$ST" 2>/dev/null || true
  { echo "plane-heal: $*" >>"$ST/titan2_pad_agent.log"; } 2>/dev/null || true
}

_read_line_file() {
  f="$1"
  [ -f "$f" ] || { echo ""; return 1; }
  v=""
  IFS= read -r v < "$f" || true
  case "$v" in *$'\r') v=${v%$'\r'} ;; esac
  echo "$v"
  return 0
}

read_first() {
  _n=$1
  best_mt=-1
  best_v=""
  found=0
  for f in "$T2/$_n" "$ST/$_n"; do
    [ -f "$f" ] || continue
    v=`_read_line_file "$f"`
    v=`echo "$v" | tr -d '\r\n \t'`
    case "$v" in ''|null|NULL|-|clear|CLEAR) continue ;; esac
    mt=`stat -c %Y "$f" 2>/dev/null` || mt=0
    case "$mt" in ''|*[!0-9]*) mt=0;; esac
    if [ "$found" = "0" ] || [ "$mt" -ge "$best_mt" ] 2>/dev/null; then
      best_mt=$mt
      best_v=$v
      found=1
    fi
  done
  [ "$found" = "1" ] && echo "$best_v" || echo ""
}

# 1.97/2.10: phone + no exclusive HID → sticky specials/arrows is ghost.
heal_ghost_host() {
  sess=`read_first titan2_usb_hid_session`
  grab=`read_first titan2_usb_hid_grab`
  keys=`read_first titan2_usb_hid_keys`
  case "$sess$grab$keys" in
    *1*) return 0 ;;
  esac
  cleared=0
  last_v=""
  for f in \
    "$ST/titan2_host_layout" \
    "$T2/titan2_host_layout"
  do
    [ -f "$f" ] || continue
    v=`_read_line_file "$f" | tr -d '\r\n ' | tr 'A-Z' 'a-z'`
    case "$v" in
      specials|arrows)
        printf 'off\n' > "$f" 2>/dev/null || true
        chmod 666 "$f" 2>/dev/null || true
        cleared=1
        last_v=$v
        ;;
    esac
  done
  g=`settings get global titan2_host_layout 2>/dev/null | tr -d '\r\n ' | tr 'A-Z' 'a-z'`
  case "$g" in
    specials|arrows)
      settings put global titan2_host_layout off 2>/dev/null || true
      cleared=1
      last_v=$g
      ;;
  esac
  if [ "$cleared" = "1" ]; then
    log "heal ghost host_layout ${last_v:-?} → off"
  fi
  return 0
}

# 15.63: STOCK cameratoggle+mictoggle only (PRIVACY_POLICY §2.2 foundation).
# AOSP SensorPrivacyService.setToggleSensorPrivacy REFUSES enable when
# supportsSensorToggle(CAMERA)==false — so MTK CamToggle=false made all UI theater.
# Cube binds privacy-fixed FrameworkResOverlay; never re-seed product fake tiles.
heal_sensor_qs() {
  _raw=`settings get secure sysui_qs_tiles 2>/dev/null | tr -d '\r'`
  case "$_raw" in
    ''|null|NULL) return 0 ;;
  esac
  _next=''
  _has_cam=0
  _has_mic=0
  _old_ifs=$IFS
  IFS=,
  set -- $_raw
  IFS=$_old_ifs
  for _p in "$@"; do
    _p=`echo "$_p" | tr -d '\r\n ' `
    [ -n "$_p" ] || continue
    case "$_p" in
      *CameraPrivacyTileService*|*MicPrivacyTileService*|*PrivateModeTileService*|*\.privacy/*)
        # Strip product/fake privacy tiles
        continue
        ;;
      cameratoggle|*.cameratoggle|*CameraToggleTile*)
        if [ "$_has_cam" = "0" ]; then
          if [ -n "$_next" ]; then _next="$_next,cameratoggle"; else _next="cameratoggle"; fi
          _has_cam=1
        fi
        continue
        ;;
      mictoggle|*.mictoggle|*MicrophoneToggleTile*)
        if [ "$_has_mic" = "0" ]; then
          if [ -n "$_next" ]; then _next="$_next,mictoggle"; else _next="mictoggle"; fi
          _has_mic=1
        fi
        continue
        ;;
    esac
    if [ -n "$_next" ]; then _next="$_next,$_p"; else _next="$_p"; fi
  done
  if [ "$_has_cam" = "0" ]; then
    if [ -n "$_next" ]; then _next="$_next,cameratoggle"; else _next="cameratoggle"; fi
  fi
  if [ "$_has_mic" = "0" ]; then
    if [ -n "$_next" ]; then _next="$_next,mictoggle"; else _next="mictoggle"; fi
  fi
  [ -n "$_next" ] || return 0
  [ "$_next" = "$_raw" ] && return 0
  settings put secure sysui_qs_tiles "$_next" 2>/dev/null || true
  cmd statusbar set-tiles "$_next" >/dev/null 2>&1 || true
  _after=`settings get secure sysui_qs_tiles 2>/dev/null | tr -d '\r'`
  case "$_after" in
    *cameratoggle*)
      log "sensor_qs stock cameratoggle+mictoggle (no fake product tile)"
      return 0
      ;;
  esac
  # After MTK bind + reboot, SystemUI accepts cameratoggle; before that WARN only
  log "sensor_qs WARN cameratoggle missing (need Cube MTK overlay + reboot)"
  return 1
}

# 2.51 FB-IN-3: secure long_press_timeout pin 400 (heal 2800 poison).
heal_long_press() {
  command -v settings >/dev/null 2>&1 || return 0
  cur=`settings get secure long_press_timeout 2>/dev/null | tr -d '\r'`
  case "$cur" in
    400) return 0 ;;
  esac
  settings put secure long_press_timeout 400 2>/dev/null || true
  return 0
}

_hid_exclusive_grab_live() {
  case "`read_first titan2_usb_hid_session`" in 1|true|on|ON) ;; *) return 1 ;; esac
  case "`read_first titan2_usb_hid_grab`" in 1|true|on|ON) return 0 ;; esac
  return 1
}

# Soft IME with HW keyboard: allow under exclusive grab; hide when idle.
heal_soft_ime() {
  _want=0
  if _hid_exclusive_grab_live 2>/dev/null; then
    _want=1
  fi
  _cur=$(settings get secure show_ime_with_hard_keyboard 2>/dev/null | tr -d '\r')
  case "$_cur" in
    "$_want") return 0 ;;
  esac
  settings put secure show_ime_with_hard_keyboard "$_want" 2>/dev/null || true
  if [ "$_want" = 0 ]; then
    input keyevent 111 2>/dev/null || true
  fi
  return 0
}

# Product default IME: PocketBoard (EN+RU Titan maps) when present.
# Fallback: AOSP LatinIME. Never force Pastiera. Do not steal Gboard if user set it
# (only heal empty/null or known-broken empty).
# SoT: docs/project/ATLAS_HYBRID_ROM.md · third_party/pocket-board (SinuXVR)
heal_default_ime() {
  command -v pm >/dev/null 2>&1 || return 0
  command -v settings >/dev/null 2>&1 || return 0
  POCKET_IME='com.sinux.pocketboard/.PocketBoardIME'
  # subtypes 1=en_US 2=ru BB Passport (method.xml) — match Titan pull enabled_input_methods
  POCKET_ENABLED='com.sinux.pocketboard/.PocketBoardIME;1;2'
  LATIN_IME='com.android.inputmethod.latin/.LatinIME'
  settings put system show_key_presses 0 2>/dev/null || true
  settings put secure show_key_presses 0 2>/dev/null || true
  heal_soft_ime
  heal_long_press
  cur=`settings get secure default_input_method 2>/dev/null | tr -d '\r'`
  # Already PocketBoard — ensure EN+RU subtypes and exit
  case "$cur" in
    *sinux.pocketboard*|*PocketBoard*)
      pm enable com.sinux.pocketboard 2>/dev/null || true
      ime enable "$POCKET_IME" 2>/dev/null || true
      settings put secure enabled_input_methods "$POCKET_ENABLED" 2>/dev/null || true
      return 0
      ;;
  esac
  # Non-empty foreign IME (Gboard etc.) — leave alone
  case "$cur" in
    ''|null|NULL) ;;
    *) return 0 ;;
  esac
  # Empty default: prefer PocketBoard product, else LatinIME
  if pm path com.sinux.pocketboard >/dev/null 2>&1; then
    pm enable com.sinux.pocketboard 2>/dev/null || true
    ime enable "$POCKET_IME" 2>/dev/null || true
    ime set "$POCKET_IME" 2>/dev/null || true
    settings put secure enabled_input_methods "$POCKET_ENABLED" 2>/dev/null || true
    settings put secure default_input_method "$POCKET_IME" 2>/dev/null || true
    log "pocketboard default IME (en+ru; empty IME healed)"
    return 0
  fi
  if pm path com.android.inputmethod.latin >/dev/null 2>&1; then
    pm enable com.android.inputmethod.latin 2>/dev/null || true
    pm enable com.android.inputmethod.latin.auto_generated_rro_product__ 2>/dev/null || true
    ime enable "$LATIN_IME" 2>/dev/null || true
    ime set "$LATIN_IME" 2>/dev/null || true
    settings put secure enabled_input_methods "$LATIN_IME" 2>/dev/null || true
    settings put secure default_input_method "$LATIN_IME" 2>/dev/null || true
    log "latinime_product default (no PocketBoard; empty IME healed)"
  fi
  return 0
}

# Compat alias
heal_latinime() { heal_default_ime; }

heal_boot() {
  heal_long_press
  heal_soft_ime
  heal_default_ime
}

cmd=${1:-}
case "$cmd" in
  ghost_host|ghost) heal_ghost_host ;;
  sensor_qs|qs) heal_sensor_qs ;;
  long_press) heal_long_press ;;
  soft_ime) heal_soft_ime ;;
  latinime|ime) heal_latinime ;;
  boot) heal_boot ;;
  version|-v|--version) echo "$HEAL_VER" ;;
  *)
    echo "usage: titan2-plane-heal.sh ghost_host|sensor_qs|long_press|soft_ime|latinime|boot|version" >&2
    exit 2
    ;;
esac
