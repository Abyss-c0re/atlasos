#!/system/bin/sh
# titan2-cool-park — OPTIMIZE Phase 3 peel: cool idle plane park
# SoT: docs/project/OPTIMIZE_SOURCE_PRODUCT.md · plug_cool / cool_park_plane
# Invoked by pad-agent:
#   apply|run   — _cool_idle_park_plane once (agent start / residual)
#   dim_mild|dim_settings|allow_dim — wallpaper dim belt (2.188)
#   version
#
# Leaves live exclusive HID (usbhid app + grab/keys/mouse/session) alone.
# Ghost exclusive residual → force phone-safe plane (no thrash).
export PATH=/system/bin:/system/xbin:/vendor/bin:$PATH
T2=/data/misc/titan2
ST=/data/local/tmp
COOL_VER=2.188-cool-park-dim

log() {
  mkdir -p "$ST" 2>/dev/null || true
  { echo "cool-park: $*" >>"$ST/titan2_pad_agent.log"; } 2>/dev/null || true
  { echo "pad-agent cool_park $*" >"$ST/titan2_agent_status"; } 2>/dev/null || true
  chmod 666 "$ST/titan2_agent_status" 2>/dev/null || true
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

read_pad_mode() {
  m=`read_first titan2_pad_mode`
  case "$m" in
    off|OFF|0) echo off; return ;;
    trackpad|TRACKPAD|pad|PAD|native|NATIVE) echo trackpad; return ;;
    mouse|MOUSE|module|MODULE|on|ON|1|global|GLOBAL) echo mouse; return ;;
  esac
  case "`read_first titan2_touchpad_enabled`" in
    1|true|on|ON) echo mouse; return ;;
  esac
  echo off
}

_usbhid_app_live() {
  pidof com.titanus2.usbhid >/dev/null 2>&1
}

_label_os_plane_for_apps() {
  [ -d "$T2" ] || mkdir -p "$T2" 2>/dev/null || true
  [ -d "$T2" ] || return 0
  chcon u:object_r:shell_data_file:s0 "$T2" 2>/dev/null || true
  if command -v find >/dev/null 2>&1; then
    find "$T2" -maxdepth 2 \( -type f -o -type d \) -exec \
      chcon u:object_r:shell_data_file:s0 {} + 2>/dev/null || true
  else
    chcon -R u:object_r:shell_data_file:s0 "$T2" 2>/dev/null || true
  fi
  for f in \
    "$T2/titan2_keycode_inject" \
    "$ST/titan2_keycode_inject" \
    "$T2/titan2_keycode_wake" \
    "$ST/titan2_keycode_wake"
  do
    [ -f "$f" ] || { : > "$f" 2>/dev/null || true; }
    chmod 666 "$f" 2>/dev/null || true
  done
}

_run_peer() {
  # $1=script basename without path; $@ rest args
  _bn="$1"; shift
  for _c in \
      "$ST/$_bn" \
      /data/local/tmp/$_bn \
      /data/adb/modules/titan2_pad_agent/system/bin/$_bn \
      /system/bin/$_bn; do
    if [ -f "$_c" ] && [ -r "$_c" ]; then
      /system/bin/sh "$_c" "$@"
      return $?
    fi
  done
  return 1
}

_allow_dim_belt() {
  set -- $(cat /proc/loadavg 2>/dev/null)
  _load1=${1%%.*}
  case "$_load1" in ''|*[!0-9]*) _load1=0 ;; esac
  [ "$_load1" -lt 8 ] 2>/dev/null
}

_put_wallpaper_dim_settings_only() {
  settings put system wallpaper_dim_amount 0 2>/dev/null || true
}

_put_wallpaper_dim_mild() {
  # Product 1.88: pure black (was 0.15 mild gray wash).
  cmd wallpaper dim-with-uid 0 0 >/dev/null 2>&1 || true
  cmd wallpaper dim-with-uid 1000 0 >/dev/null 2>&1 || true
  cmd wallpaper dim-with-uid 2000 0 >/dev/null 2>&1 || true
  if command -v timeout >/dev/null 2>&1; then
    timeout 3 cmd wallpaper set-dim-amount 0 >/dev/null 2>&1 \
      || settings put system wallpaper_dim_amount 0 2>/dev/null || true
  else
    cmd wallpaper set-dim-amount 0 >/dev/null 2>&1 \
      || settings put system wallpaper_dim_amount 0 2>/dev/null || true
  fi
  settings put system wallpaper_dim_amount 0 2>/dev/null || true
  am broadcast -a com.titanus2.cubecontact.DIM_GUARD \
    -n com.titanus2.cubecontact/.DimGuardReceiver --include-stopped-packages \
    >/dev/null 2>&1 || true
  am start-service -n com.titanus2.cubecontact/.DimGuardService >/dev/null 2>&1 \
    || am startservice -n com.titanus2.cubecontact/.DimGuardService >/dev/null 2>&1 || true
}

# Sysfs-only pad park (no agent LAST_* / typing watch — cool path pad-off only).
_set_pad_inhibited_cool() {
  want_inh="$1"
  for inh in /sys/class/input/input*/inhibited; do
    [ -e "$inh" ] || continue
    dir=`dirname "$inh"`
    name=`cat "$dir/name" 2>/dev/null` || continue
    case "$name" in
      touchPad)
        echo "$want_inh" > "$inh" 2>/dev/null || true
        ;;
      titan2-virtual-mouse|titan2-touchpadd|titan2_touchpadd|Titan2\ Touchpad)
        echo "$want_inh" > "$inh" 2>/dev/null || true
        ;;
      sub_touch)
        # cool park always forces face + subtouch inhibit
        echo 1 > "$inh" 2>/dev/null || true
        ;;
    esac
  done
}

cool_idle_park_plane() {
  _usbhid_live=0
  if _usbhid_app_live; then
    _usbhid_live=1
  fi
  _excl_sig=0
  case "`read_first titan2_usb_hid_grab`" in
    1|true|on|yes|ON) _excl_sig=1 ;;
  esac
  case "`read_first titan2_usb_hid_keys`" in
    1|true|on|yes|ON) _excl_sig=1 ;;
  esac
  case "`read_first titan2_usb_hid_mouse`" in
    1|true|on|yes|ON) _excl_sig=1 ;;
  esac
  case "`read_first titan2_usb_hid_session`" in
    1|true|on|yes|ON) _excl_sig=1 ;;
  esac
  if [ "$_excl_sig" = "1" ] && [ "$_usbhid_live" = "1" ]; then
    return 0
  fi
  for _f in titan2_usb_hid_session titan2_usb_hid_on titan2_usb_hid_keys \
    titan2_usb_hid_grab titan2_usb_hid_mouse titan2_usb_hid_local_input \
    titan2_host_layout_keys_pause titan2_usb_hid_keys_pause \
    titan2_specials_inject_pause titan2_pad_cursor_pause; do
    for _d in "$T2" "$ST"; do
      printf 0 > "$_d/$_f" 2>/dev/null || true
      chmod 666 "$_d/$_f" 2>/dev/null || true
    done
    settings put global "$_f" 0 2>/dev/null || true
  done
  for _d in "$T2" "$ST"; do
    printf 'off\n' > "$_d/titan2_host_layout" 2>/dev/null || true
    chmod 666 "$_d/titan2_host_layout" 2>/dev/null || true
  done
  settings put global titan2_host_layout off 2>/dev/null || true
  settings put system titan2_host_layout off 2>/dev/null || true
  for _d in "$T2" "$ST"; do
    if [ ! -s "$_d/titan2_specials_method" ]; then
      printf kcm > "$_d/titan2_specials_method" 2>/dev/null || true
      chmod 666 "$_d/titan2_specials_method" 2>/dev/null || true
    fi
  done
  _smg=`settings get global titan2_specials_method 2>/dev/null | tr -d '\r\n '`
  case "$_smg" in
    ''|null|NULL) settings put global titan2_specials_method kcm 2>/dev/null || true ;;
  esac
  setprop sys.titanus2.usb_hid.session 0 2>/dev/null || true
  setprop persist.titanus2.hid_resume 0 2>/dev/null || true
  for _rq in titan2_remote_hid.q titan2_hid_remote_q titan2_hid_hw.out titan2_hid.inj; do
    for _d in "$T2" "$ST"; do
      : > "$_d/$_rq" 2>/dev/null || true
      chmod 666 "$_d/$_rq" 2>/dev/null || true
    done
  done
  _label_os_plane_for_apps
  _run_peer titan2-plane-heal.sh sensor_qs >/dev/null 2>&1 || true
  for _d in "$T2" "$ST" /data/adb/titan2; do
    mkdir -p "$_d" 2>/dev/null || true
    printf 1 >"$_d/titan2_subtouch_inhibit" 2>/dev/null || true
    printf face >"$_d/titan2_sub_mode" 2>/dev/null || true
    chmod 666 "$_d/titan2_subtouch_inhibit" "$_d/titan2_sub_mode" 2>/dev/null || true
  done
  settings put global titan2_subtouch_inhibit 1 2>/dev/null || true
  settings put global titan2_sub_mode face 2>/dev/null || true
  _run_peer titan2-pad-idc.sh subtouch ignore >/dev/null 2>&1 || true
  _run_peer titan2-pad-idc.sh clear >/dev/null 2>&1 || true
  for _d in /sys/class/input/input*; do
    [ -e "$_d/name" ] || continue
    _n=`cat "$_d/name" 2>/dev/null` || continue
    [ "$_n" = sub_touch ] || continue
    echo 1 >"$_d/inhibited" 2>/dev/null || true
    echo 1 >"$_d/device/inhibited" 2>/dev/null || true
  done
  case "`read_pad_mode`" in
    mouse|trackpad) ;;
    *)
      for _d in "$T2" "$ST"; do
        printf none >"$_d/titan2_input_surface" 2>/dev/null || true
        printf 1 >"$_d/titan2_hw_pad_inhibit" 2>/dev/null || true
        chmod 666 "$_d/titan2_input_surface" "$_d/titan2_hw_pad_inhibit" 2>/dev/null || true
      done
      settings put global titan2_input_surface none 2>/dev/null || true
      settings put global titan2_hw_pad_inhibit 1 2>/dev/null || true
      _set_pad_inhibited_cool 1
      ;;
  esac
  if [ "$_excl_sig" = "1" ]; then
    log "cool_idle_park plane (ghost-excl force, misc+inject+os-label)"
  else
    log "cool_idle_park plane (ghost-excl-ok, misc+inject+os-label)"
  fi
  # subdisplay reassert if On (do not force off)
  _on=`_run_peer titan2-subdisplay.sh on 2>/dev/null` || _on=0
  case "$_on" in
    1)
      _run_peer titan2-subdisplay.sh apply >/dev/null 2>&1 || true
      # digitizer post stays face (already set above)
      log "cool_idle_subdisplay_reassert"
      ;;
  esac
  if _allow_dim_belt; then
    _put_wallpaper_dim_mild
  else
    _put_wallpaper_dim_settings_only
    log "cool_idle_dim_park load (settings-only; multi-UID + DimGuard skip)"
  fi
  _run_peer titan2-plane-heal.sh latinime >/dev/null 2>&1 || true
  return 0
}

cmd=${1:-apply}
case "$cmd" in
  apply|run|"") cool_idle_park_plane ;;
  dim_mild|dim-mild|mild)
    _put_wallpaper_dim_mild
    ;;
  dim_settings|dim-settings|settings)
    _put_wallpaper_dim_settings_only
    ;;
  allow_dim|allow)
    if _allow_dim_belt; then exit 0; else exit 1; fi
    ;;
  version|-v|--version) echo "$COOL_VER" ;;
  *)
    echo "usage: titan2-cool-park.sh apply|dim_mild|dim_settings|allow_dim|version" >&2
    exit 2
    ;;
esac
