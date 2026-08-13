#!/system/bin/sh
# titan2-key-fire — OPTIMIZE Phase 3 peel: KEY_FIRE broadcast sink
# SoT: docs/project/OPTIMIZE_SOURCE_PRODUCT.md
# Invoked by pad-agent:
#   fire <action> [scan] [down] [meta]
#   flush              — truncate Controls←agent inject backlog
#   version
# 2.199: fire_key_action (flashlight + subdisplay_toggle) peeled from agent.
export PATH=/system/bin:/system/xbin:/vendor/bin:$PATH
ST=/data/local/tmp
T2=/data/misc/titan2
HW_META_FILE=$ST/titan2_hw_meta
KEYFIRE_VER=2.201-mtk-torch

_hw_meta_get() {
  m=`cat "$HW_META_FILE" 2>/dev/null | tr -d '\r\n '`
  case "$m" in ''|*[!0-9]*) m=0 ;; esac
  echo "$m"
}

# 1.82 B1: side scans never KEY_FIRE system chrome (camera/home/recents/…)
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

# Truncate Controls←agent inject backlog (B2 multi-glyph leftover).
# 2.200: full boot/session-off queue flush (inject + exclusive specials residual).
_flush_keycode_inject() {
  for f in \
    $ST/titan2_keycode_inject $T2/titan2_keycode_inject \
    $ST/titan2_hid_remote_q $T2/titan2_hid_remote_q \
    $ST/titan2_remote_hid.q $T2/titan2_remote_hid.q \
    $ST/titan2_hid_hw.out $T2/titan2_hid_hw.out \
    $ST/titan2_hid.inj $T2/titan2_hid.inj
  do
    [ -f "$f" ] || continue
    : > "$f" 2>/dev/null || true
    chmod 666 "$f" 2>/dev/null || true
  done
  rm -f $ST/titan2_keycode_wake $T2/titan2_keycode_wake 2>/dev/null || true
}

# KEY_FIRE with optional Linux scan (side hold begin/end).
# Optional $3 = down (1|0) for host_layout letter path.
# Optional $4 = Android meta mask (default: tracked HW modifiers).

_sysbin() {
  _bn="$1"
  for _c in "$ST/$_bn" /data/local/tmp/"$_bn" \
      /data/adb/modules/titan2_pad_agent/system/bin/"$_bn" /system/bin/"$_bn"; do
    [ -f "$_c" ] && [ -r "$_c" ] && { echo "$_c"; return 0; }
  done
  return 1
}

# High-level KEY_FIRE (2.199 peel): flashlight sysfs + subdisplay toggle + scan fire.
fire_key_action() {
  act="$1"
  [ -n "$act" ] || return 0
  case "$act" in default|"") return 0 ;; none) return 0 ;; esac
  if [ "$act" = "flashlight" ]; then
    # MTK flashlight-core first (works even when sensor-privacy revokes /dev/video*
    # and camerahalserver is stopped — QS CameraService torch needs HAL).
    _mtk=/sys/devices/virtual/flashlight_core/flashlight/flashlight_torch
    if [ ! -e "$_mtk" ]; then
      _mtk=/sys/class/flashlight_core/flashlight/flashlight_torch
    fi
    if [ -e "$_mtk" ]; then
      # STATUS column: 0=off 1=on. Toggle both color temps (type0 ct0/ct1).
      _st=`cat "$_mtk" 2>/dev/null | awk '/^[0-9]/{print $4; exit}'`
      if [ "$_st" = "1" ]; then _on=0; else _on=1; fi
      echo "0 0 0 $_on" > "$_mtk" 2>/dev/null || true
      echo "0 1 0 $_on" > "$_mtk" 2>/dev/null || true
      return 0
    fi
    for t in /sys/class/leds/torch-light0/brightness \
             /sys/class/leds/torch_led/brightness \
             /sys/class/leds/led:torch_0/brightness \
             /sys/class/leds/flashlight/brightness; do
      if [ -w "$t" ]; then
        cur=`cat "$t" 2>/dev/null`
        if [ "$cur" = "0" ] || [ -z "$cur" ]; then echo 1 > "$t"; else echo 0 > "$t"; fi
        return 0
      fi
    done
  fi
  if [ "$act" = "subdisplay_toggle" ]; then
    _s=`_sysbin titan2-subdisplay.sh` || return 1
    /system/bin/sh "$_s" toggle || true
    _p=`_sysbin titan2-pad-idc.sh` || true
    [ -n "$_p" ] && /system/bin/sh "$_p" digitizer_post 2>/dev/null || true
    return 0
  fi
  fire_key_action_scan "$act" 0
}

fire_key_action_scan() {
  act="$1"
  scan="$2"
  down="${3-}"
  meta="${4-}"
  case "$act" in ""|none|default) return 0 ;; esac
  case "$scan" in
    249|250)
      if _side_is_chrome_act "$act"; then
        return 0
      fi
      ;;
  esac
  # 1.48/1.51: layout hold arm/release + sticky toggle must start empty queues
  case "$act" in
    layout:end_hold|layout:specials_hold|layout:arrows_hold|layout:hold:*|\
    layout:specials_toggle|layout:arrows_toggle|layout:toggle:*)
      _flush_keycode_inject
      ;;
  esac
  if [ -z "$meta" ]; then
    meta=`_hw_meta_get 2>/dev/null` || meta=0
  fi
  case "$meta" in ''|*[!0-9]*) meta=0 ;; esac
  if [ -n "$scan" ] && [ "$scan" != "0" ]; then
    if [ -n "$down" ]; then
      am broadcast -a com.titanus2.controls.KEY_FIRE --es action "$act" \
        --ei scan "$scan" --ei down "$down" --ei meta "$meta" \
        -n com.titanus2.controls/.KeyFireReceiver >/dev/null 2>&1 || \
        am broadcast -a com.titanus2.controls.KEY_FIRE --es action "$act" \
          --ei scan "$scan" --ei down "$down" --ei meta "$meta" >/dev/null 2>&1 || true
    else
      am broadcast -a com.titanus2.controls.KEY_FIRE --es action "$act" \
        --ei scan "$scan" --ei meta "$meta" \
        -n com.titanus2.controls/.KeyFireReceiver >/dev/null 2>&1 || \
        am broadcast -a com.titanus2.controls.KEY_FIRE --es action "$act" \
          --ei scan "$scan" --ei meta "$meta" >/dev/null 2>&1 || true
    fi
  else
    am broadcast -a com.titanus2.controls.KEY_FIRE --es action "$act" \
      --ei meta "$meta" \
      -n com.titanus2.controls/.KeyFireReceiver >/dev/null 2>&1 || \
      am broadcast -a com.titanus2.controls.KEY_FIRE --es action "$act" \
        --ei meta "$meta" >/dev/null 2>&1 || true
  fi
}

cmd="${1-}"
case "$cmd" in
  fire)
    shift
    # bare action (no scan) → fire_key_action; with scan → scan path
    if [ -z "${2-}" ] || [ "${2-}" = "0" ]; then
      fire_key_action "${1-}"
    else
      fire_key_action_scan "${1-}" "${2-}" "${3-}" "${4-}"
    fi
    ;;
  action)
    fire_key_action "${2-}"
    ;;
  flush)
    _flush_keycode_inject
    ;;
  version|-v|--version)
    echo "$KEYFIRE_VER"
    ;;
  ""|help|-h|--help)
    echo "usage: titan2-key-fire.sh fire <action> [scan] [down] [meta]"
    echo "       titan2-key-fire.sh action <action> | flush | version"
    exit 0
    ;;
  *)
    echo "titan2-key-fire: unknown cmd=$cmd" >&2
    exit 2
    ;;
esac
