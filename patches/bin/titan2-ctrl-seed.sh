#!/system/bin/sh
# Pre-create world-writable control-plane shells (create-if-missing only).
# Rootless system apps rewrite these; SELinux blocks *create* under /data/misc
# and — with default system_data_file labels — also blocks priv_app *write/open*
# (lab 2026-07-19: avc denied write on titan2_keycode_inject / remote_q / session).
# Relabel the product plane to shell_data_file so Controls/HID can share it
# with pad-agent (same type as /data/local/tmp). Re-run after restorecon.
#
# Phases:
#   early  (default) — base pad/HID shells + OS plane label (init titan2-ctrl.rc)
#   agent            — early + last-known restore + km/specials/IMS/notif + side chrome heal
#                      (pad-agent boot; Phase 3 peel 2.191)
#   late|qs          — long_press heal + pad QS tile pin (boot_completed)
export PATH=/system/bin:/system/xbin:$PATH
T2=/data/misc/titan2
ST=/data/local/tmp
PHASE="${1:-early}"
mkdir -p "$T2" "$ST" 2>/dev/null || true
chmod 777 "$T2" 2>/dev/null || true

# Product SoT: /data/misc/titan2 must be app-writable (priv_app). Default
# system_data_file leaves phone Sym inject / plane publish SELinux-denied.
label_os_plane() {
  [ -d "$T2" ] || return 0
  chcon u:object_r:shell_data_file:s0 "$T2" 2>/dev/null || true
  if command -v find >/dev/null 2>&1; then
    find "$T2" -maxdepth 2 \( -type f -o -type d \) -exec \
      chcon u:object_r:shell_data_file:s0 {} + 2>/dev/null || true
  else
    chcon -R u:object_r:shell_data_file:s0 "$T2" 2>/dev/null || true
  fi
}

seed() {
  name=$1
  def=$2
  for d in "$T2" "$ST"; do
    f="$d/$name"
    if [ ! -f "$f" ]; then
      printf '%s' "$def" > "$f" 2>/dev/null || true
    fi
    chmod 666 "$f" 2>/dev/null || true
  done
}

# P0 B1: sides never system chrome — keep in lockstep with KeyMapPrefs.isSystemChromeAction.
_side_is_chrome_act() {
  a=`echo "$1" | tr 'A-Z' 'a-z' | tr -d '\r\n '`
  case "$a" in
    home|recents|back|assist|power|power_dialog|camera|\
    app_switch|appswitch|app-switch|recent|overview|\
    keycode_camera|keycode:camera|key_camera|sys_camera|stock_camera|mtk_camera|button_camera|\
    keycode_home|keycode:home|key_home|sys_home|nav_home|gesture_home|\
    keycode_app_switch|keycode:app_switch|keycode_back|keycode:back|key_back|\
    keycode_assistant|keycode:assistant|keycode_power|keycode:power|key_power|\
    button_1|button_2|button_3|button_4|keycode_button_1|keycode_button_2|\
    keycode_menu|keycode:menu|key_menu|menu|sys_recents|sys_overview|\
    open_camera|start_camera|capture|keycode:3|keycode:4|keycode:187|keycode:26|keycode:27|\
    keycode_3|keycode_4|keycode_187|keycode_26|keycode_27)
      return 0 ;;
  esac
  return 1
}

_heal_side_plane() {
  n="$1"; good="$2"
  for d in "$T2" "$ST"; do
    f="$d/$n"
    [ -f "$f" ] || continue
    cur=`cat "$f" 2>/dev/null | tr -d '\r\n '`
    if _side_is_chrome_act "$cur"; then
      printf '%s' "$good" >"$f" 2>/dev/null || true
      chmod 666 "$f" 2>/dev/null || true
    fi
  done
}

seed_base() {
  # Pad: default OFF so fresh install matches QS/UI and can be toggled.
  seed titan2_pad_mode off
  seed titan2_pad_click 1
  seed titan2_pad_top_row_cursor 1
  seed titan2_pad_top_row_only 0
  seed titan2_pad_follow_orient 1
  seed titan2_touchpad_enabled 0
  # Keyboard backlight: product default mid + 30s idle (0 = always on while awake).
  seed titan2_keyled_brightness 3
  seed titan2_keyled_timeout 30
  seed titan2_pad_epoch 0
  seed titan2_pad_regrab 0

  # USB HID session (OS gadget stack; app arms without su)
  seed titan2_usb_hid_session 0
  seed titan2_usb_hid_on 0
  seed titan2_usb_hid_usb 1
  seed titan2_usb_hid_bt 1
  seed titan2_usb_hid_mouse 0
  seed titan2_usb_hid_keys 0
  seed titan2_usb_hid_grab 0
  seed titan2_usb_hid_local_input 0
  seed titan2_usb_hid_hw_out 0
  seed titan2_usb_hid_typing_ms 600
  seed titan2_host_layout off
  seed titan2_host_layout_keys_pause 0

  # Soft inject / status (empty files)
  for n in titan2_hid.inj titan2_hid_hw.out titan2_remote_hid.q titan2_hid_remote_q \
    titan2_keycode_inject titan2_keycode_wake \
    titan2_pad_status titan2_agent_status titan2_usb_hid.log titan2_key_activity; do
    for d in "$T2" "$ST"; do
      f="$d/$n"
      case "$n" in titan2_usb_hid.log|titan2_pad_status|titan2_agent_status)
        [ "$d" = "$ST" ] || continue
        ;;
      esac
      if [ ! -f "$f" ]; then
        : > "$f" 2>/dev/null || true
      fi
      chmod 666 "$f" 2>/dev/null || true
    done
  done
}

# Agent boot extras (pad-agent used to own these inline through 2.190).
seed_agent_extras() {
  # Restore public control files from last-known (root reboot survival)
  if [ ! -s "$T2/titan2_keyled_brightness" ] && [ -f "$T2/titan2_keyled_last" ]; then
    cp "$T2/titan2_keyled_last" "$T2/titan2_keyled_brightness" 2>/dev/null || true
    chmod 666 "$T2/titan2_keyled_brightness" 2>/dev/null || true
  fi
  if [ ! -s "$T2/titan2_keyled_timeout" ] && [ -f "$T2/titan2_keyled_timeout_last" ]; then
    cp "$T2/titan2_keyled_timeout_last" "$T2/titan2_keyled_timeout" 2>/dev/null || true
    chmod 666 "$T2/titan2_keyled_timeout" 2>/dev/null || true
  fi
  # 2.129 product heal: pre-idle30 default was timeout=0 (always-on). One-shot
  # migrate sticky 0 → 30 unless user explicitly stamped always-on.
  if [ ! -f "$T2/titan2_keyled_idle_migrated" ]; then
    _mto=`cat "$T2/titan2_keyled_timeout" 2>/dev/null | tr -d '\r\n \t'`
    _malways=`cat "$T2/titan2_keyled_always" 2>/dev/null | tr -d '\r\n \t'`
    case "$_malways" in 1|true|on|ON) ;; *)
      case "$_mto" in 0|"")
        echo 30 > "$T2/titan2_keyled_timeout" 2>/dev/null || true
        echo 30 > "$ST/titan2_keyled_timeout" 2>/dev/null || true
        echo 30 > "$T2/titan2_keyled_timeout_last" 2>/dev/null || true
        chmod 666 "$T2/titan2_keyled_timeout" "$ST/titan2_keyled_timeout" \
          "$T2/titan2_keyled_timeout_last" 2>/dev/null || true
        ;;
      esac
      ;;
    esac
    echo 1 > "$T2/titan2_keyled_idle_migrated" 2>/dev/null || true
    chmod 666 "$T2/titan2_keyled_idle_migrated" 2>/dev/null || true
  fi
  # Kill always-on residual on tmp plane (stale tip mirrors beat OS plane).
  _stto=`cat "$ST/titan2_keyled_timeout" 2>/dev/null | tr -d '\r\n \t'`
  _t2to=`cat "$T2/titan2_keyled_timeout" 2>/dev/null | tr -d '\r\n \t'`
  if [ "$_stto" = "0" ] && [ -n "$_t2to" ] && [ "$_t2to" != "0" ]; then
    echo "$_t2to" > "$ST/titan2_keyled_timeout" 2>/dev/null || true
    chmod 666 "$ST/titan2_keyled_timeout" 2>/dev/null || true
  fi

  for nm in titan2_pad_mode titan2_pad_click titan2_pad_top_row_cursor titan2_pad_top_row_only \
    titan2_pad_follow_orient titan2_fn_mode titan2_char_mod titan2_char_mod_scan \
    titan2_subtouch_inhibit \
    titan2_notif_blink_enable titan2_notif_blink_level titan2_notif_mode \
    titan2_notif_period_ms titan2_notif_on_ms \
    titan2_ims_mtk titan2_ims_force_volte titan2_ims_binder \
    titan2_tel_force_5g titan2_tel_disable_vci titan2_tel_restart_ril titan2_tel_patch_smsc \
    titan2_bt_esco; do
    if [ ! -s "$T2/$nm" ] && [ -f "$T2/${nm}_last" ]; then
      cp "$T2/${nm}_last" "$T2/$nm" 2>/dev/null || true
      chmod 666 "$T2/$nm" 2>/dev/null || true
    fi
  done

  # Defaults when never set
  if [ ! -s "$T2/titan2_pad_follow_orient" ]; then
    echo 1 > "$T2/titan2_pad_follow_orient" 2>/dev/null || true
    chmod 666 "$T2/titan2_pad_follow_orient" 2>/dev/null || true
  fi
  if [ ! -s "$T2/titan2_pad_mode" ]; then
    echo off > "$T2/titan2_pad_mode" 2>/dev/null || true
    chmod 666 "$T2/titan2_pad_mode" 2>/dev/null || true
  fi
  if [ ! -s "$ST/titan2_pad_mode" ]; then
    echo off > "$ST/titan2_pad_mode" 2>/dev/null || true
    chmod 666 "$ST/titan2_pad_mode" 2>/dev/null || true
  fi
  chmod 666 "$T2/titan2_pad_mode" "$ST/titan2_pad_mode" 2>/dev/null || true
  # Default specials on Sym so physical Alt stays real Alt for desktop/HID
  if [ ! -s "$T2/titan2_char_mod" ]; then
    echo sym > "$T2/titan2_char_mod" 2>/dev/null || true
    chmod 666 "$T2/titan2_char_mod" 2>/dev/null || true
  fi

  for pair in "titan2_ims_mtk:1" "titan2_ims_force_volte:1" "titan2_ims_binder:1" \
    "titan2_tel_patch_smsc:1"; do
    nm=${pair%%:*}; val=${pair##*:}
    if [ ! -s "$T2/$nm" ]; then
      echo "$val" > "$T2/$nm" 2>/dev/null || true
      chmod 666 "$T2/$nm" 2>/dev/null || true
    fi
  done
  for pair in "titan2_notif_blink_enable:0" "titan2_notif_mode:blink" \
    "titan2_notif_blink_level:5" "titan2_notif_period_ms:800" "titan2_notif_on_ms:400"; do
    nm=${pair%%:*}; val=${pair##*:}
    if [ ! -s "$T2/$nm" ]; then
      echo "$val" > "$T2/$nm" 2>/dev/null || true
      chmod 666 "$T2/$nm" 2>/dev/null || true
    fi
    if [ ! -s "$ST/$nm" ]; then
      echo "$val" > "$ST/$nm" 2>/dev/null || true
      chmod 666 "$ST/$nm" 2>/dev/null || true
    fi
  done

  # BT persist is TrebleApp Misc SoT. Do not seed titan2_bt_* or scrub
  # persist.sys.bt.unsupported.commands — that restamps user edits every boot.

  # KeyMap / specials / HID shells (create-if-missing; chmod always)
  # Defaults match KeyMapPrefs.factoryDefault — never seed home/recents on sides.
  seed titan2_km_enabled 1
  seed titan2_km_screen_off 1
  seed titan2_km_side_func_short none
  seed titan2_km_side_func_long none
  seed titan2_km_side_func_double none
  seed titan2_km_side_func2_short none
  seed titan2_km_side_func2_long none
  seed titan2_km_side_func2_double none
  seed titan2_specials_method kcm
  _heal_side_plane titan2_km_side_func_short none
  _heal_side_plane titan2_km_side_func_long none
  _heal_side_plane titan2_km_side_func_double none
  _heal_side_plane titan2_km_side_func2_short none
  _heal_side_plane titan2_km_side_func2_long none
  _heal_side_plane titan2_km_side_func2_double none
  seed titan2_km_recents_short home
  seed titan2_km_recents_long recents
  seed titan2_km_back_short default
  seed titan2_km_back_long default
  seed titan2_km_back_double none
  seed titan2_km_fn_short none
  seed titan2_km_fn_long none
  seed titan2_km_fn_double none
  seed titan2_km_sym_short none
  seed titan2_km_sym_long none
  seed titan2_km_sym_double none
  seed titan2_km_alt_short none
  seed titan2_km_alt_long none
  seed titan2_km_alt_double none
  seed titan2_a11y_live 0
  seed titan2_usb_hid_keys_pause 0
  seed titan2_specials_inject_pause 0
  # B2 exclusive Specials queues (empty shells)
  seed titan2_remote_hid.q ""
  seed titan2_hid_hw.out ""
  seed titan2_hid_remote_q ""
}

agui_follow_pad() {
  mode=$(cat "$T2/titan2_pad_mode" 2>/dev/null | tr -d '\r\n ')
  case "$mode" in
    trackpad|mouse|1|true|on) setprop persist.sys.agui.touchpad_function 1 2>/dev/null || true ;;
    *) setprop persist.sys.agui.touchpad_function 0 2>/dev/null || true ;;
  esac
}

# HI847S stamp only. HAL bounce is titan2-sensor-privacy v25 (honors
# camera privacy). Never dumpsys media.camera — hang skips the stamp.
stamp_aux_cam() {
  _pkgs="org.lineageos.aperture,org.lineageos.aperture.lenslauncher"
  setprop camera.aux.packagelist "$_pkgs" 2>/dev/null || true
  setprop vendor.camera.aux.packagelist "$_pkgs" 2>/dev/null || true
  setprop persist.camera.aux.packagelist "$_pkgs" 2>/dev/null || true
  setprop persist.vendor.camera.aux.packagelist "$_pkgs" 2>/dev/null || true
  setprop persist.vendor.camera.privapp.list org.lineageos.aperture 2>/dev/null || true
}

# --- late: pin pad QS tile (SystemUI may own/overwrite early) ---
seed_qs_pad() {
  command -v settings >/dev/null 2>&1 || return 0
  SPEC='custom(com.titanus2.controls/.PadModeTileService)'
  ALT='custom(com.titanus2.controls/com.titanus2.controls.PadModeTileService)'
  STOCK='internet,bt,flashlight,dnd,alarm,airplane,controls,rotation,battery,cast,screenrecord,hotspot,location,night,saver'
  cur=$(settings get secure sysui_qs_tiles 2>/dev/null | tr -d '\r')
  case "$cur" in
    null|""|"null") cur="$STOCK" ;;
  esac
  case ",$cur," in
    *",custom(com.titanus2.controls/"*"PadModeTileService),"*|*",$SPEC,"*|*",$ALT,"*)
      return 0
      ;;
  esac
  echo "$cur" | grep -q 'PadModeTileService' && return 0
  if echo "$cur" | grep -q '^internet,'; then
    next=$(echo "$cur" | sed "s|^internet,|internet,$SPEC,|")
  elif echo "$cur" | grep -q ',bt,'; then
    next=$(echo "$cur" | sed "s|,bt,|,bt,$SPEC,|")
  elif echo "$cur" | grep -q '^bt,'; then
    next=$(echo "$cur" | sed "s|^bt,|bt,$SPEC,|")
  else
    next="$SPEC,$cur"
  fi
  settings put secure sysui_qs_tiles "$next" 2>/dev/null || true
  log -t titan2-ctrl-seed "sysui_qs_tiles seeded pad tile" 2>/dev/null || true
}

# FB-IN-3: Secure long_press_timeout is ViewConfiguration for ALL UI long-holds.
heal_long_press() {
  command -v settings >/dev/null 2>&1 || return 0
  cur=$(settings get secure long_press_timeout 2>/dev/null | tr -d '\r')
  case "$cur" in
    400) return 0 ;;
    2800|null|""|"null")
      settings put secure long_press_timeout 400 2>/dev/null || true
      log -t titan2-ctrl-seed "FB-IN-3 long_press_timeout $cur→400" 2>/dev/null || true
      ;;
    *)
      settings put secure long_press_timeout 400 2>/dev/null || true
      ;;
  esac
}

# Power menu: Global.power_button_long_press
# AOSP PhoneWindowManager: 0=NOTHING, 1=GLOBAL_ACTIONS, 2=SHUT_OFF, 5=ASSISTANT.
# Lab 2026-08-11: stuck at 0 → long-press power did nothing (CRITICAL no power menu).
# Force 1 (power menu). Never reintroduce 0.
heal_power_menu() {
  command -v settings >/dev/null 2>&1 || return 0
  cur=$(settings get global power_button_long_press 2>/dev/null | tr -d '\r')
  case "$cur" in
    1) return 0 ;;
    0|null|""|"null"|2|3|4|5)
      settings put global power_button_long_press 1 2>/dev/null || true
      log -t titan2-ctrl-seed "power_button_long_press $cur→1 (GLOBAL_ACTIONS)" 2>/dev/null || true
      ;;
    *)
      settings put global power_button_long_press 1 2>/dev/null || true
      ;;
  esac
  # Keep assistant from stealing long-press power (product: power menu, not Gemini).
  settings put secure long_press_power_assistant 0 2>/dev/null || true
}


# 2.202: claim residual formerly inline in pad-agent main (legacy + hygiene + B1).
seed_agent_claim() {
  # Legacy user-visible control dir under /data/media only (never FUSE /sdcard).
  legacy=/data/media/0/titan2
  if [ -d "$legacy" ]; then
    for f in "$legacy"/titan2_*; do
      [ -f "$f" ] || continue
      base=`basename "$f"`
      if [ ! -s "$T2/$base" ]; then
        cp -f "$f" "$T2/$base" 2>/dev/null || true
        chmod 666 "$T2/$base" 2>/dev/null || true
      fi
    done
    rm -rf "$legacy" 2>/dev/null || true
  fi
  # Force inject pause clear every claim (exclusive residual).
  for _d in "$T2" "$ST"; do
    printf 0 > "$_d/titan2_specials_inject_pause" 2>/dev/null || true
    chmod 666 "$_d/titan2_specials_inject_pause" 2>/dev/null || true
  done
  settings put global titan2_specials_method kcm 2>/dev/null || true
  settings put secure long_press_timeout 400 2>/dev/null || true
  heal_power_menu
  # Key activity world-writable (root agent + a11y app).
  touch "$ST/titan2_key_activity" 2>/dev/null || true
  chmod 666 "$ST/titan2_key_activity" 2>/dev/null || true
  # B1 KL heal (phh CAMERA map / pmic dual-emit / gpio-ff sides)
  _b1=""
  for _c in "$ST/titan2-b1-kl.sh" /data/local/tmp/titan2-b1-kl.sh \
      /data/adb/modules/titan2_pad_agent/system/bin/titan2-b1-kl.sh \
      /system/bin/titan2-b1-kl.sh; do
    [ -f "$_c" ] && [ -r "$_c" ] && { _b1="$_c"; break; }
  done
  if [ -n "$_b1" ]; then
    /system/bin/sh "$_b1" mtk 2>/dev/null || true
    /system/bin/sh "$_b1" pmic 2>/dev/null || true
    /system/bin/sh "$_b1" sides 2>/dev/null || true
  fi
}

case "$PHASE" in
  agent)
    seed_base
    seed_agent_extras
    seed_agent_claim
    label_os_plane
    agui_follow_pad
    echo "ctrl-seed agent ok" >"$ST/titan2_ctrl_seed_status" 2>/dev/null || true
    chmod 666 "$ST/titan2_ctrl_seed_status" 2>/dev/null || true
    exit 0
    ;;
  late|qs)
    stamp_aux_cam
    heal_long_press
    heal_power_menu
    seed_qs_pad
    i=0
    while [ $i -lt 5 ]; do
      sleep 8
      heal_long_press
      heal_power_menu
      seed_qs_pad
      i=$((i + 1))
    done
    exit 0
    ;;
  *)
    # early (default)
    seed_base
    label_os_plane
    agui_follow_pad
    exit 0
    ;;
esac
