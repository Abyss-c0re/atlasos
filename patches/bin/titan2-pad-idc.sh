#!/system/bin/sh
# titan2-pad-idc — OPTIMIZE Phase 3 peel: touchPad/sub_touch IDC + rear assoc
# SoT: docs/project/OPTIMIZE_SOURCE_PRODUCT.md · PAD / SUB_DISPLAY digitizer
# Invoked by pad-agent:
#   touchpad <ignore|native|native_fixed> [force=1]
#   subtouch <ignore|native|flipx|apps>
#   associate          — bind sub_touch → rear display
#   clear              — drop sub_touch association
#   digitizer_post     — apps|cube → touchScreen only if assoc binds; else ignore
#   inhibit <0|1> [force] — set_pad_inhibited sysfs park (2.186)
#   kind               — print last touchPad kind
#   version
#
# REG-K1: stage under /data/adb/titan2/idc (system_file) not shell_data_file.
export PATH=/system/bin:/system/xbin:/vendor/bin:$PATH
T2=/data/misc/titan2
ST=/data/local/tmp
IDC_VER=2.224-subtouch-failclosed
_IDC_STAGE=/data/adb/titan2/idc
KIND_FILE=$ST/titan2_idc_kind
ASSOC_FILE=$ST/titan2_subtouch_assoc_state

log() {
  mkdir -p "$ST" 2>/dev/null || true
  { echo "pad-idc: $*" >>"$ST/titan2_pad_agent.log"; } 2>/dev/null || true
}

_hb() {
  mkdir -p "$ST" 2>/dev/null || true
  { echo "pad-agent idc $*" >"$ST/titan2_agent_status"; } 2>/dev/null || true
  chmod 666 "$ST/titan2_agent_status" 2>/dev/null || true
}

_sleep_brief() {
  if command -v usleep >/dev/null 2>&1; then
    usleep 20000
  else
    sleep 0.02 2>/dev/null || true
  fi
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

read_sub_mode() {
  m=`read_first titan2_sub_mode`
  m=`echo "$m" | tr 'A-Z' 'a-z' | tr -d '\r\n '`
  case "$m" in
    apps|app|launcher|touch|interactive) echo apps; return ;;
    cube|lattice|brain|neural) echo cube; return ;;
    face|clock|stock|custom|aod) echo face; return ;;
    off|0|none) echo off; return ;;
  esac
  case "`read_first titan2_subdisplay_on`" in
    1|true|on|ON) echo face ;;
    *) echo off ;;
  esac
}

_last_kind() {
  v=`_read_line_file "$KIND_FILE" 2>/dev/null` || v=""
  echo "$v" | tr -d '\r\n '
}

_set_last_kind() {
  k="$1"
  mkdir -p "$ST" 2>/dev/null || true
  printf '%s' "$k" >"$KIND_FILE" 2>/dev/null || true
  chmod 666 "$KIND_FILE" 2>/dev/null || true
}

_last_assoc() {
  v=`_read_line_file "$ASSOC_FILE" 2>/dev/null` || v=""
  echo "$v" | tr -d '\r\n '
}

_set_last_assoc() {
  a="$1"
  mkdir -p "$ST" 2>/dev/null || true
  printf '%s' "$a" >"$ASSOC_FILE" 2>/dev/null || true
  chmod 666 "$ASSOC_FILE" 2>/dev/null || true
}

_label_idc_for_inputreader() {
  f="$1"
  [ -n "$f" ] && [ -f "$f" ] || return 1
  chmod 0644 "$f" 2>/dev/null || true
  chcon u:object_r:system_file:s0 "$f" 2>/dev/null || true
  return 0
}

_idc_stage_dir() {
  if mkdir -p "$_IDC_STAGE" 2>/dev/null; then
    chmod 0755 "$_IDC_STAGE" 2>/dev/null || true
    echo "$_IDC_STAGE"
    return 0
  fi
  mkdir -p /data/local/tmp/titan2_idc 2>/dev/null
  chmod 0755 /data/local/tmp/titan2_idc 2>/dev/null || true
  echo /data/local/tmp/titan2_idc
}

# touchPad.idc: ignore | native | native_fixed
set_touchpad_idc() {
  kind="$1"
  force="$2"
  last=`_last_kind`
  [ "$force" = "1" ] || [ "$kind" != "$last" ] || return 0
  IDC_DIR=/system/usr/idc
  ETC=/system/etc/titan2_idc
  STAGE=`_idc_stage_dir`
  case "$kind" in
    native)
      src=$ETC/touchPad.native.idc
      [ -f "$src" ] || src=$IDC_DIR/touchPad.native.idc
      ;;
    native_fixed)
      src=$ETC/touchPad.native.fixed.idc
      [ -f "$src" ] || src=$IDC_DIR/touchPad.native.fixed.idc
      if [ ! -f "$src" ]; then
        base=$ETC/touchPad.native.idc
        [ -f "$base" ] || base=$IDC_DIR/touchPad.native.idc
        if [ -f "$base" ]; then
          sed 's/touch.orientationAware.*/touch.orientationAware = 0/' "$base" \
            > "$STAGE/touchPad.native.fixed.idc" 2>/dev/null
          _label_idc_for_inputreader "$STAGE/touchPad.native.fixed.idc"
          src="$STAGE/touchPad.native.fixed.idc"
        fi
      fi
      ;;
    *)
      src=$ETC/touchPad.ignore.idc
      [ -f "$src" ] || src=$IDC_DIR/touchPad.ignore.idc
      kind=ignore
      ;;
  esac
  [ -f "$src" ] || return 1
  _label_idc_for_inputreader "$src"
  cp "$src" "$STAGE/touchPad.idc" 2>/dev/null
  _label_idc_for_inputreader "$STAGE/touchPad.idc"
  # bind-mount ONLY — never remount /system (multi-second hang trackpad↔mouse)
  ok=0
  if [ -f "$STAGE/touchPad.idc" ]; then
    umount $IDC_DIR/touchPad.idc 2>/dev/null || true
    mount --bind "$STAGE/touchPad.idc" $IDC_DIR/touchPad.idc 2>/dev/null && ok=1
  fi
  if [ "$ok" = "0" ]; then
    cp "$src" $IDC_DIR/touchPad.idc 2>/dev/null && ok=1
    _label_idc_for_inputreader $IDC_DIR/touchPad.idc
  fi
  for d in /sys/class/input/input*; do
    [ -e "$d/name" ] || continue
    n=`cat "$d/name" 2>/dev/null` || continue
    [ "$n" = "touchPad" ] || continue
    echo 1 > "$d/inhibited" 2>/dev/null || true
    echo 0 > "$d/inhibited" 2>/dev/null || true
    echo change > "$d/uevent" 2>/dev/null || true
  done
  _set_last_kind "$kind"
  return 0
}

# sub_touch.idc: ignore | native | flipx | apps
set_subtouch_idc() {
  kind="$1"
  IDC_DIR=/system/usr/idc
  ETC=/system/etc/titan2_idc
  STAGE=`_idc_stage_dir`
  if [ "$kind" = "apps" ] || [ "$kind" = "touchscreen" ] || [ "$kind" = "touchScreen" ]; then
    src=$ETC/sub_touch.touchscreen.idc
    [ -f "$src" ] || src=$IDC_DIR/sub_touch.touchscreen.idc
    [ -f "$src" ] || src=$STAGE/sub_touch.touchscreen.idc
    if [ ! -f "$src" ]; then
      cat > "$STAGE/sub_touch.touchscreen.idc" << 'IDCEOF'
# Rear digitizer as touchscreen for display-2 apps (sub_mode=apps).
# Association to display uniqueId still required for correct viewport.
device.internal = 1
touch.deviceType = touchScreen
touch.orientationAware = 1
device.displayPort = 3
IDCEOF
      _label_idc_for_inputreader "$STAGE/sub_touch.touchscreen.idc"
      src="$STAGE/sub_touch.touchscreen.idc"
    fi
    kind=apps
  elif [ "$kind" = "flipx" ]; then
    src=$ETC/sub_touch.pointer.flipx.idc
    [ -f "$src" ] || src=$ETC/sub_touch.native.idc
    [ -f "$src" ] || src=$IDC_DIR/sub_touch.native.idc
  elif [ "$kind" = "native" ]; then
    src=$ETC/sub_touch.native.idc
    [ -f "$src" ] || src=$IDC_DIR/sub_touch.native.idc
  else
    src=$ETC/sub_touch.idc
    [ -f "$src" ] || src=$IDC_DIR/sub_touch.idc
    kind=ignore
  fi
  [ -f "$src" ] || return 1
  _label_idc_for_inputreader "$src"
  cp "$src" "$STAGE/sub_touch.idc" 2>/dev/null
  _label_idc_for_inputreader "$STAGE/sub_touch.idc"
  ok=0
  mount -o remount,rw /system 2>/dev/null || mount -o remount,rw / 2>/dev/null || true
  cp "$src" $IDC_DIR/sub_touch.idc 2>/dev/null && ok=1
  _label_idc_for_inputreader $IDC_DIR/sub_touch.idc
  if [ "$ok" = "0" ] && [ -f "$STAGE/sub_touch.idc" ]; then
    umount $IDC_DIR/sub_touch.idc 2>/dev/null || true
    mount --bind "$STAGE/sub_touch.idc" $IDC_DIR/sub_touch.idc 2>/dev/null && ok=1
  fi
  for d in /sys/class/input/input*; do
    [ -e "$d/name" ] || continue
    n=`cat "$d/name" 2>/dev/null` || continue
    [ "$n" = "sub_touch" ] || continue
    for inhf in "$d/inhibited" "$d/device/inhibited"; do
      [ -e "$inhf" ] || continue
      echo 1 > "$inhf" 2>/dev/null || true
      _sleep_brief
      echo 0 > "$inhf" 2>/dev/null || true
    done
    echo change > "$d/uevent" 2>/dev/null || true
  done
  # Do NOT stomp touchPad kind file (agent LAST_IDC_KIND is touchPad-only).
  return 0
}

# Lab-known EventHub descriptor + rear uniqueId (no dumpsys on hot path).
_subtouch_descriptor() {
  echo d498fd4b8ff8c34cb9de09546f4b0e8a26606f5f
}

_rear_display_unique_id() {
  echo local:4627039422300187651
}

_svc_input_call() {
  _ok=0
  if [ "`id -u 2>/dev/null`" = "0" ]; then
    for _su in /data/adb/magisk/su /sbin/su /system/xbin/su /system/bin/su; do
      [ -x "$_su" ] || continue
      "$_su" 2000 -c "service call input $*" >/dev/null 2>&1 && _ok=1 && break
      "$_su" shell -c "service call input $*" >/dev/null 2>&1 && _ok=1 && break
    done
  fi
  if [ "$_ok" != "1" ]; then
    service call input "$@" >/dev/null 2>&1 && _ok=1 || true
  fi
  [ "$_ok" = "1" ]
}

associate_sub_touch_display() {
  desc=`_subtouch_descriptor`
  uid=`_rear_display_unique_id`
  [ -n "$desc" ] && [ -n "$uid" ] || return 1
  want="assoc:$desc>$uid"
  if [ "`_last_assoc`" = "$want" ]; then
    return 0
  fi
  if _svc_input_call 43 s16 "$desc" s16 "$uid"; then
    _set_last_assoc "$want"
    for _d in "$T2" "$ST"; do
      [ -d "$_d" ] || continue
      printf "%s" "$uid" >"$_d/titan2_subtouch_assoc" 2>/dev/null || true
      chmod 666 "$_d/titan2_subtouch_assoc" 2>/dev/null || true
    done
    settings put global titan2_subtouch_assoc "$uid" 2>/dev/null || true
    _hb "subtouch assoc ok →$uid"
    return 0
  fi
  _hb "subtouch assoc fail (service call)"
  return 1
}

clear_sub_touch_display() {
  desc=`_subtouch_descriptor`
  if [ -n "$desc" ]; then
    _svc_input_call 44 s16 "$desc" || true
  fi
  _set_last_assoc ""
  for _d in "$T2" "$ST" /data/adb/titan2; do
    [ -d "$_d" ] || continue
    printf none >"$_d/titan2_subtouch_assoc" 2>/dev/null || true
    chmod 666 "$_d/titan2_subtouch_assoc" 2>/dev/null || true
  done
  settings put global titan2_subtouch_assoc none 2>/dev/null || true
  return 0
}

digitizer_post() {
  case "`read_sub_mode`" in
    apps|cube)
      # Never leave touchScreen unbound — pending assoc = main-display ghost.
      set_subtouch_idc apps 2>/dev/null || true
      if associate_sub_touch_display; then
        return 0
      fi
      _hb "subtouch fail-closed (assoc pending/fail) — ignore"
      set_subtouch_idc ignore 2>/dev/null || true
      clear_sub_touch_display 2>/dev/null || true
      ;;
    *)
      set_subtouch_idc ignore 2>/dev/null || true
      clear_sub_touch_display 2>/dev/null || true
      ;;
  esac
  return 0
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

# Shared surface → PAD_SURFACE for touchpadd (hw|none only after 2.63).
read_pad_surface() {
  s=`read_first titan2_input_surface`
  s=`echo "$s" | tr 'A-Z' 'a-z' | tr -d '\r\n '`
  case "$s" in
    sub|rear|sub_touch|both|all|dual) s=hw ;;
    none|off) s=none ;;
    hw|pad|trackpad|mouse|"") s=hw ;;
    *) s=hw ;;
  esac
  echo "$s"
}

PAD_STATUS=$ST/titan2_pad_status

hid_session_on() {
  for f in \
    $T2/titan2_usb_hid_session \
    /data/local/tmp/titan2_usb_hid_session \
    /data/adb/titan2/titan2_usb_hid_session
  do
    [ -f "$f" ] || continue
    v=`_read_line_file "$f"`
    case "$v" in 1|true|on|ON) return 0;; esac
  done
  return 1
}

hid_needs_mouse() {
  hid_session_on || return 1
  for f in \
    $T2/titan2_usb_hid_mouse \
    /data/local/tmp/titan2_usb_hid_mouse \
    /data/adb/titan2/titan2_usb_hid_mouse
  do
    [ -f "$f" ] || continue
    v=`_read_line_file "$f"`
    case "$v" in
      0|false|off|OFF|no|NO) return 1 ;;
      1|true|on|ON|yes|YES) return 0 ;;
    esac
  done
  return 0
}

_typing_watch_live() {
  _wp=`cat "$ST/titan2_typing_watch.pid" 2>/dev/null | tr -d '\r\n '`
  if [ -n "$_wp" ] && [ -d "/proc/$_wp" ]; then
    grep -a -F -q "titan2-typing-watch" "/proc/$_wp/cmdline" 2>/dev/null && return 0
  fi
  _st=`cat "$ST/titan2_typing_watch_status" 2>/dev/null | tr -d '\r'`
  _wp=`echo "$_st" | sed -n 's/.*pid=\([0-9][0-9]*\).*/\1/p' | head -1`
  if [ -n "$_wp" ] && [ -d "/proc/$_wp" ]; then
    return 0
  fi
  for _wp in `pgrep -f 'titan2-typing-watch' 2>/dev/null`; do
    case "$_wp" in ''|*[!0-9]*) continue ;; esac
    grep -a -F -q "titan2-typing-watch" "/proc/$_wp/cmdline" 2>/dev/null || continue
    return 0
  done
  return 1
}

_cursor_pause_on() {
  case "`read_first titan2_pad_cursor_pause`" in
    1|true|on|ON|yes|YES) return 0 ;;
  esac
  return 1
}

# Sysfs-only pad inhibit (2.186 peel of set_pad_inhibited).
# $1 = want_inh (0=live, 1=park). $2 = optional force|typing.
set_pad_inhibited() {
  want_inh="$1"
  force_inh="${2:-}"
  # refuse unpark while typing-locked and watch/pause live
  if [ "$want_inh" = "0" ] && [ -f "$PAD_STATUS" ] \
      && grep -q 'typing_lock=1' "$PAD_STATUS" 2>/dev/null; then
    if _typing_watch_live 2>/dev/null || _cursor_pause_on 2>/dev/null; then
      return 0
    fi
  fi
  mode=$(read_pad_mode)
  surface=$(read_pad_surface)
  hw_inh=1
  subtouch_inh=1
  if [ "$want_inh" = "0" ]; then
    case "$mode" in
      off|OFF|0|"") hw_inh=1 ;;
      *)
        case "$surface" in
          hw|both|sub) hw_inh=0 ;;
          *) hw_inh=1 ;;
        esac
        ;;
    esac
  fi
  if [ "$want_inh" = "1" ]; then
    case "$force_inh" in
      force|1|true|yes|typing)
        hw_inh=1
        ;;
      *)
        if hid_needs_mouse 2>/dev/null; then hw_inh=0; else hw_inh=1; fi
        ;;
    esac
  fi
  case "$mode" in
    off|OFF|0|"")
      if ! hid_needs_mouse 2>/dev/null; then hw_inh=1; fi
      ;;
  esac
  case "`read_sub_mode`" in
    apps|cube)
      # Fail closed: only pad-idc last-assoc (not Cube Global, which lies).
      sa=`_last_assoc`
      case "$sa" in
        "assoc:"*">local:"*|"assoc:"*">unique:"*) subtouch_inh=0 ;;
        *) subtouch_inh=1 ;;
      esac
      ;;
    *) subtouch_inh=1 ;;
  esac
  virt_inh="$hw_inh"
  case "$force_inh" in
    force|1|true|yes|typing) virt_inh=1 ;;
  esac
  for inh in /sys/class/input/input*/inhibited; do
    [ -e "$inh" ] || continue
    dir=`dirname "$inh"`
    name=`cat "$dir/name" 2>/dev/null` || continue
    case "$name" in
      touchPad) echo "$hw_inh" > "$inh" 2>/dev/null || true ;;
      titan2-virtual-mouse|titan2-touchpadd|titan2_touchpadd|Titan2\ Touchpad)
        echo "$virt_inh" > "$inh" 2>/dev/null || true ;;
      sub_touch) echo "$subtouch_inh" > "$inh" 2>/dev/null || true ;;
    esac
  done
  case "$force_inh" in
    force|1|true|yes|typing)
      if [ "$want_inh" = "1" ]; then
        for p in `pidof titan2-touchpadd 2>/dev/null`; do
          kill -9 "$p" 2>/dev/null || true
        done
        for inh in /sys/class/input/input*/inhibited; do
          [ -e "$inh" ] || continue
          dir=`dirname "$inh"`
          name=`cat "$dir/name" 2>/dev/null` || continue
          case "$name" in
            titan2-virtual-mouse|titan2-touchpadd|titan2_touchpadd)
              echo 1 > "$inh" 2>/dev/null ;;
          esac
        done
      fi
      ;;
  esac
  return 0
}


# REG-K (2.200 peel): pad mode off must keep touchPad inhibited + ignore idc.
off_assert() {
  # mode from plane
  m=`cat "$T2/titan2_pad_mode" 2>/dev/null | tr -d '\r\n \t'`
  [ -n "$m" ] || m=`cat "$ST/titan2_pad_mode" 2>/dev/null | tr -d '\r\n \t'`
  case "$m" in mouse|trackpad|MOUSE|TRACKPAD) return 0 ;; esac
  # text-caret nav independent — needs touchPad events while pad=off
  trc=`cat "$T2/titan2_pad_top_row_cursor" 2>/dev/null | tr -d '\r\n \t'`
  [ -n "$trc" ] || trc=`cat "$ST/titan2_pad_top_row_cursor" 2>/dev/null | tr -d '\r\n \t'`
  case "$trc" in 1|true|on|ON)
    set_pad_inhibited 0
    set_touchpad_idc ignore
    return 0
    ;;
  esac
  set_pad_inhibited 1
  set_touchpad_idc ignore
  return 0
}

cmd=${1:-}
case "$cmd" in
  touchpad)
    set_touchpad_idc "${2:-ignore}" "${3:-}"
    ;;
  subtouch)
    set_subtouch_idc "${2:-ignore}"
    ;;
  associate)
    associate_sub_touch_display
    ;;
  clear)
    clear_sub_touch_display
    ;;
  digitizer_post|post)
    digitizer_post
    ;;
  off_assert|assert_off)
    off_assert
    ;;
  inhibit)
    set_pad_inhibited "${2:-1}" "${3:-}"
    ;;
  kind)
    _last_kind
    ;;
  version|-v|--version)
    echo "$IDC_VER"
    ;;
  *)
    echo "usage: titan2-pad-idc.sh touchpad|subtouch|associate|clear|digitizer_post|inhibit|off_assert|kind|version" >&2
    exit 2
    ;;
esac
