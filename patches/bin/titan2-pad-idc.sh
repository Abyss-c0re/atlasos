#!/system/bin/sh
# titan2-pad-idc — OPTIMIZE Phase 3 peel: touchPad/sub_touch IDC + rear assoc
# SoT: docs/project/OPTIMIZE_SOURCE_PRODUCT.md · PAD / SUB_DISPLAY digitizer
# Invoked by pad-agent:
#   touchpad <ignore|native|native_fixed> [force=1]
#   subtouch <ignore|native|flipx|apps>
#   associate          — bind sub_touch → rear display
#   clear              — drop sub_touch association
#   digitizer_post     — apps|cube → touchScreen pinned to the rear viewport, else inhibit
#   inhibit <0|1> [force] — set_pad_inhibited sysfs park (2.186)
#   kind               — print last touchPad kind
#   version
#
# REG-K1: stage under /data/adb/titan2/idc (system_file) not shell_data_file.
export PATH=/system/bin:/system/xbin:/vendor/bin:$PATH
T2=/data/misc/titan2
ST=/data/local/tmp
IDC_VER=2.228-no-i2c-rebind
_IDC_STAGE=/data/adb/titan2/idc
KIND_FILE=$ST/titan2_idc_kind
ASSOC_FILE=$ST/titan2_subtouch_assoc_state
# InputReader reads touch.displayId. device.displayPort is not a binding.
# This id is the rear panel in display_settings.xml; dumpsys overrides it.
REAR_UID_FALLBACK=local:4627039422300187651
REAR_OK_FILE=$ST/titan2_subtouch_rear_ok
REAR_PROBE_FILE=$ST/titan2_subtouch_rear_probe
INPUT_SNAP=$ST/titan2_subtouch_input_snap

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
    hid|hidmouse|hid_mouse) echo hid; return ;;
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

# Lab EventHub descriptor. Stable across reboots on this panel.
_subtouch_descriptor() {
  echo d498fd4b8ff8c34cb9de09546f4b0e8a26606f5f
}

_safe_svc_word() {
  case "$1" in
    ''|*[!A-Za-z0-9:_-]*) return 1 ;;
  esac
  return 0
}

_now_s() {
  date +%s 2>/dev/null || echo 0
}

_refresh_input_snap() {
  force="${1:-0}"
  if [ "$force" != "1" ] && [ -f "$INPUT_SNAP" ]; then
    mt=`stat -c %Y "$INPUT_SNAP" 2>/dev/null` || mt=0
    now=`_now_s`
    case "$mt" in ''|*[!0-9]*) mt=0 ;; esac
    case "$now" in ''|*[!0-9]*) now=0 ;; esac
    age=`expr "$now" - "$mt" 2>/dev/null` || age=999
    if [ "$age" -ge 0 ] && [ "$age" -lt 2 ]; then
      return 0
    fi
  fi
  dumpsys input >"$INPUT_SNAP" 2>/dev/null || true
  chmod 666 "$INPUT_SNAP" 2>/dev/null || true
  return 0
}

_rear_uid_from_snap() {
  uid=""
  if [ -f "$INPUT_SNAP" ]; then
    uid=`grep 'Viewport INTERNAL:' "$INPUT_SNAP" 2>/dev/null \
      | grep -v 'displayId=0,' \
      | sed -n 's/.*uniqueId=\(local:[0-9][0-9]*\).*/\1/p' \
      | head -1`
  fi
  case "$uid" in
    local:[0-9]*) echo "$uid" ;;
    *) echo "$REAR_UID_FALLBACK" ;;
  esac
}

_rear_display_unique_id() {
  if [ -n "${_REAR_UID_CACHED:-}" ]; then
    echo "$_REAR_UID_CACHED"
    return 0
  fi
  _refresh_input_snap 0
  _REAR_UID_CACHED=`_rear_uid_from_snap`
  echo "$_REAR_UID_CACHED"
}

# False when dumpsys produced nothing we can judge. Do not fail closed on that.
_snap_usable() {
  [ -s "$INPUT_SNAP" ] || return 1
  grep -q 'Viewport INTERNAL:' "$INPUT_SNAP" 2>/dev/null
}

# Viewport line for the sub_touch InputReader device, not the EventHub node.
_subtouch_viewport_line() {
  sed -n '/^  Device [0-9][0-9]*: sub_touch$/,/^  Device [0-9]/p' \
    "$INPUT_SNAP" 2>/dev/null \
    | grep 'Viewport INTERNAL:' | head -1
}

# 0 when the cooked viewport is the rear panel and not display 0.
# $1=1 bypasses the 2s probe cache (use after a rebind).
_rear_viewport_ok() {
  force="${1:-0}"
  if [ "$force" != "1" ] && [ -f "$REAR_PROBE_FILE" ]; then
    line=`_read_line_file "$REAR_PROBE_FILE"`
    ts=`echo "$line" | sed 's/ .*//'`
    st=`echo "$line" | sed 's/^[0-9][0-9]* //'`
    now=`_now_s`
    case "$ts" in ''|*[!0-9]*) ts=0 ;; esac
    case "$now" in ''|*[!0-9]*) now=0 ;; esac
    age=`expr "$now" - "$ts" 2>/dev/null` || age=999
    if [ "$age" -ge 0 ] && [ "$age" -lt 2 ]; then
      [ "$st" = "ok" ]
      return $?
    fi
  fi
  _refresh_input_snap "$force"
  uid=`_rear_display_unique_id`
  line=`_subtouch_viewport_line`
  st=bad
  case "$line" in
    *'displayId=0,'*) st=bad ;;
    *'uniqueId=local:4627039422300187648'*) st=bad ;;
    *"uniqueId=$uid"*) st=ok ;;
  esac
  now=`_now_s`
  printf '%s %s\n' "$now" "$st" >"$REAR_PROBE_FILE" 2>/dev/null || true
  chmod 666 "$REAR_PROBE_FILE" 2>/dev/null || true
  [ "$st" = "ok" ]
}

_idc_has_pin() {
  idc=/system/usr/idc/sub_touch.idc
  [ -f "$idc" ] || return 1
  grep -F -q 'touch.deviceType = touchScreen' "$idc" 2>/dev/null || return 1
  # Fallback id is the lab rear panel. Skip dumpsys when the IDC already has it.
  if grep -F -q "touch.displayId = $REAR_UID_FALLBACK" "$idc" 2>/dev/null; then
    return 0
  fi
  uid=`_rear_display_unique_id`
  grep -F -q "touch.displayId = $uid" "$idc" 2>/dev/null
}

_stamp_rear_ok() {
  mkdir -p "$ST" 2>/dev/null || true
  _now_s >"$REAR_OK_FILE" 2>/dev/null || printf 1 >"$REAR_OK_FILE"
  chmod 666 "$REAR_OK_FILE" 2>/dev/null || true
}

_rear_ok_fresh() {
  [ -f "$REAR_OK_FILE" ] || return 1
  mt=`stat -c %Y "$REAR_OK_FILE" 2>/dev/null` || return 1
  now=`_now_s`
  case "$mt" in ''|*[!0-9]*) return 1 ;; esac
  case "$now" in ''|*[!0-9]*) return 1 ;; esac
  age=`expr "$now" - "$mt" 2>/dev/null` || return 1
  [ "$age" -ge 0 ] && [ "$age" -lt 12 ]
}

_drop_rear_proof() {
  rm -f "$REAR_OK_FILE" "$REAR_PROBE_FILE" "$INPUT_SNAP" 2>/dev/null || true
  _REAR_UID_CACHED=""
}

_subtouch_sysfs_inh() {
  want="$1"
  case "$want" in 0|1) ;; *) return 1 ;; esac
  for d in /sys/class/input/input*; do
    [ -e "$d/name" ] || continue
    n=`cat "$d/name" 2>/dev/null` || continue
    [ "$n" = "sub_touch" ] || continue
    for inhf in "$d/inhibited" "$d/device/inhibited"; do
      [ -e "$inhf" ] || continue
      echo "$want" > "$inhf" 2>/dev/null || true
    done
  done
}

_uevent_subtouch() {
  for d in /sys/class/input/input*; do
    [ -e "$d/name" ] || continue
    n=`cat "$d/name" 2>/dev/null` || continue
    [ "$n" = "sub_touch" ] || continue
    echo change > "$d/uevent" 2>/dev/null || true
  done
}

# Parameters AssociatedDisplay uses quotes. The viewport line does not.
# Empty displayId='' means InputReader opened sub_touch before the pin existed.
_reader_has_display_pin() {
  _refresh_input_snap "${1:-0}"
  uid=`_rear_uid_from_snap`
  case "$uid" in
    local:[0-9]*) ;;
    *) uid=$REAR_UID_FALLBACK ;;
  esac
  sed -n '/^  Device [0-9][0-9]*: sub_touch$/,/^  Device [0-9]/p' \
    "$INPUT_SNAP" 2>/dev/null \
    | grep -F "displayId='$uid'" >/dev/null 2>&1
}

# Parameters displayId is whatever InputReader read when the node opened.
# Do not unbind/bind 2-005a. The sub_touch driver is hynitron_touch, and a
# second probe misc_register()s "touch" again. That EEXIST leaves misc_list
# corrupt and misc_open panics the kernel about ten seconds later.
_ensure_reader_loaded() {
  _reader_has_display_pin 0 && return 0
  _idc_has_pin || return 1
  _hb "subtouch reader pin empty — leave driver bound"
  log "subtouch reader pin empty — no i2c rebind"
  return 1
}

_sleep_settle() {
  if command -v usleep >/dev/null 2>&1; then
    usleep 250000
  else
    sleep 0.3 2>/dev/null || sleep 1
  fi
}

# Success is a Parcel reply with no Exception. service call exits 0 on SecurityException.
_svc_reply_ok() {
  echo "$1" | grep -q -i 'Exception' && return 1
  echo "$1" | grep -q 'Parcel'
}

_svc_input_call() {
  for _a in "$@"; do
    _safe_svc_word "$_a" || return 1
  done
  _argstr=""
  for _a in "$@"; do
    _argstr="$_argstr $_a"
  done
  _svc_out=""
  if [ "`id -u 2>/dev/null`" = "0" ]; then
    for _su in /data/adb/magisk/su /sbin/su /system/xbin/su /system/bin/su; do
      [ -x "$_su" ] || continue
      _svc_out=`"$_su" 1000 -c "/system/bin/service call input $_argstr" 2>&1` || true
      _svc_reply_ok "$_svc_out" && return 0
      _svc_out=`"$_su" 2000 -c "/system/bin/service call input $_argstr" 2>&1` || true
      _svc_reply_ok "$_svc_out" && return 0
    done
  fi
  _svc_out=`/system/bin/service call input "$@" 2>&1` || true
  _svc_reply_ok "$_svc_out"
}

_remember_assoc() {
  desc="$1"
  uid="$2"
  _set_last_assoc "assoc:$desc>$uid"
  for _d in "$T2" "$ST"; do
    [ -d "$_d" ] || continue
    printf '%s' "$uid" >"$_d/titan2_subtouch_assoc" 2>/dev/null || true
    chmod 666 "$_d/titan2_subtouch_assoc" 2>/dev/null || true
  done
  settings put global titan2_subtouch_assoc "$uid" 2>/dev/null || true
}

# sub_touch.idc: ignore | native | flipx | apps
# Always stages touch.displayId. Does not uninhibit — caller proves the viewport first.
set_subtouch_idc() {
  kind="$1"
  IDC_DIR=/system/usr/idc
  STAGE=`_idc_stage_dir`
  uid=`_rear_display_unique_id`
  _safe_svc_word "$uid" || uid=$REAR_UID_FALLBACK
  case "$kind" in
    apps|touchscreen|touchScreen|native|flipx) kind=apps ;;
    *) kind=ignore ;;
  esac
  dest="$STAGE/sub_touch.want.idc"
  tmp="$dest.tmp"
  if [ "$kind" = "ignore" ]; then
    cat >"$tmp" << EOF
device.internal = 1
touch.deviceType = ignore
touch.displayId = $uid
EOF
  else
    cat >"$tmp" << EOF
device.internal = 1
touch.deviceType = touchScreen
touch.orientationAware = 1
touch.displayId = $uid
EOF
  fi
  mv -f "$tmp" "$dest" 2>/dev/null || cp "$tmp" "$dest"
  rm -f "$tmp" 2>/dev/null || true
  _label_idc_for_inputreader "$dest"
  if cmp -s "$dest" "$IDC_DIR/sub_touch.idc" 2>/dev/null; then
    if [ ! -f "$STAGE/sub_touch.idc" ] || ! cmp -s "$dest" "$STAGE/sub_touch.idc" 2>/dev/null; then
      cp "$dest" "$STAGE/sub_touch.idc" 2>/dev/null || true
      _label_idc_for_inputreader "$STAGE/sub_touch.idc"
    fi
    return 0
  fi
  # Inhibit across the reload. Uninhibit only after the rear viewport is proven.
  _subtouch_sysfs_inh 1
  cp "$dest" "$STAGE/sub_touch.idc" 2>/dev/null || return 1
  _label_idc_for_inputreader "$STAGE/sub_touch.idc"
  ok=0
  mount -o remount,rw /system 2>/dev/null || mount -o remount,rw / 2>/dev/null || true
  cp "$dest" $IDC_DIR/sub_touch.idc 2>/dev/null && ok=1
  _label_idc_for_inputreader $IDC_DIR/sub_touch.idc
  if [ "$ok" = "0" ] && [ -f "$STAGE/sub_touch.idc" ]; then
    umount $IDC_DIR/sub_touch.idc 2>/dev/null || true
    mount --bind "$STAGE/sub_touch.idc" $IDC_DIR/sub_touch.idc 2>/dev/null && ok=1
  fi
  if [ "$ok" != "1" ]; then
    _hb "subtouch idc install failed"
    _drop_rear_proof
    return 1
  fi
  # The new file is what the next open reads. Uevent does not reload an
  # already-open node. i2c unbind/bind panics hynitron — never do it.
  _uevent_subtouch
  _drop_rear_proof
  return 0
}

associate_sub_touch_display() {
  desc=`_subtouch_descriptor`
  uid=`_rear_display_unique_id`
  _safe_svc_word "$desc" || return 1
  _safe_svc_word "$uid" || return 1
  # Assoc-file equality is not proof: service call exits 0 on SecurityException.
  if _svc_input_call 43 s16 "$desc" s16 "$uid"; then
    _remember_assoc "$desc" "$uid"
    _hb "subtouch assoc ok -> $uid"
    return 0
  fi
  if _rear_viewport_ok 1; then
    _remember_assoc "$desc" "$uid"
    _hb "subtouch assoc already rear"
    return 0
  fi
  _set_last_assoc ""
  _hb "subtouch assoc fail (service call)"
  return 1
}

clear_sub_touch_display() {
  desc=`_subtouch_descriptor`
  if _safe_svc_word "$desc"; then
    _svc_input_call 44 s16 "$desc" || true
  fi
  _set_last_assoc ""
  _drop_rear_proof
  for _d in "$T2" "$ST" /data/adb/titan2; do
    [ -d "$_d" ] || continue
    printf none >"$_d/titan2_subtouch_assoc" 2>/dev/null || true
    chmod 666 "$_d/titan2_subtouch_assoc" 2>/dev/null || true
  done
  settings put global titan2_subtouch_assoc none 2>/dev/null || true
  return 0
}

_subtouch_idc_is_ignore() {
  _f=/system/usr/idc/sub_touch.idc
  [ -f "$_f" ] || return 1
  grep -q 'deviceType *= *ignore' "$_f" 2>/dev/null || return 1
  grep -q 'deviceType *= *touchScreen' "$_f" 2>/dev/null && return 1
  return 0
}

_fail_closed_subtouch() {
  _hb "subtouch fail-closed — inhibit, ignore pin, no primary"
  _subtouch_sysfs_inh 1
  set_subtouch_idc ignore 2>/dev/null || true
  clear_sub_touch_display 2>/dev/null || true
  _subtouch_sysfs_inh 1
}

digitizer_post() {
  case "`read_sub_mode`" in
    apps|cube)
      # File pin plus rear viewport: load touch.displayId once, then leave it.
      if _idc_has_pin && _rear_viewport_ok 0; then
        _ensure_reader_loaded || true
        if _rear_viewport_ok 1; then
          _subtouch_sysfs_inh 0
          _stamp_rear_ok
          _desc=`_subtouch_descriptor`
          _uid=`_rear_display_unique_id`
          if [ "`_last_assoc`" != "assoc:${_desc}>${_uid}" ]; then
            _remember_assoc "$_desc" "$_uid"
          fi
          return 0
        fi
      fi
      # Empty dumpsys must not clear a pin or the rear association.
      if _idc_has_pin && ! _snap_usable; then
        if _rear_ok_fresh; then
          _subtouch_sysfs_inh 0
          return 0
        fi
        _hb "subtouch dumpsys empty — leave pin, stay inhibited"
        _subtouch_sysfs_inh 1
        return 0
      fi
      _subtouch_sysfs_inh 1
      set_subtouch_idc apps 2>/dev/null || true
      _ensure_reader_loaded || true
      _sleep_settle
      if ! _rear_viewport_ok 1; then
        associate_sub_touch_display || true
        _sleep_settle
      fi
      if ! _rear_viewport_ok 1; then
        line=`_subtouch_viewport_line`
        case "$line" in
          *'displayId=0,'*)
            # Step 1 beats touch.displayId. Drop a primary association and rebind.
            desc=`_subtouch_descriptor`
            _safe_svc_word "$desc" && _svc_input_call 44 s16 "$desc" || true
            _set_last_assoc ""
            associate_sub_touch_display || true
            _sleep_settle
            ;;
        esac
      fi
      if _rear_viewport_ok 1; then
        _subtouch_sysfs_inh 0
        _stamp_rear_ok
        _hb "subtouch rear pin ok"
        return 0
      fi
      # Reopen can outrun dumpsys. Keep the touchScreen pin and the association
      # instead of rewriting ignore, which would make the next open fall through.
      if _idc_has_pin; then
        _hb "subtouch rear not proven — keep pin, stay inhibited"
        _subtouch_sysfs_inh 1
        return 0
      fi
      _fail_closed_subtouch
      ;;
    hid|hidmouse)
      # Ignore first, then uninhibit so touchpadd can grab. Never a touchscreen.
      _subtouch_sysfs_inh 1
      if ! set_subtouch_idc ignore; then
        _fail_closed_subtouch
        return 0
      fi
      clear_sub_touch_display 2>/dev/null || true
      if _subtouch_idc_is_ignore; then
        _subtouch_sysfs_inh 0
      else
        _fail_closed_subtouch
      fi
      ;;
    *)
      _subtouch_sysfs_inh 1
      set_subtouch_idc ignore 2>/dev/null || true
      clear_sub_touch_display 2>/dev/null || true
      _subtouch_sysfs_inh 1
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

# HID mouse keeps sub|both. Outside HID those tokens still collapse.
read_pad_surface() {
  s=`read_first titan2_input_surface`
  s=`echo "$s" | tr 'A-Z' 'a-z' | tr -d '\r\n '`
  case "`read_sub_mode`" in
    hid)
      case "`read_pad_mode`" in
        mouse) echo both ;;
        *) echo sub ;;
      esac
      return 0
      ;;
  esac
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
  submode=$(read_sub_mode)
  hw_inh=1
  subtouch_inh=1
  case "$mode" in
    mouse)
      case "$surface" in
        hw|both) hw_inh=0 ;;
        *) hw_inh=1 ;;
      esac
      ;;
    trackpad) hw_inh=0 ;;
    *)
      hw_inh=1
      if [ "$submode" != "hid" ] && hid_needs_mouse 2>/dev/null; then hw_inh=0; fi
      ;;
  esac
  if [ "$want_inh" = "1" ]; then
    case "$force_inh" in
      force|1|true|yes|typing)
        hw_inh=1
        ;;
      *)
        if [ "$submode" = "hid" ]; then
          case "$mode" in
            trackpad) hw_inh=0 ;;
            mouse)
              case "$surface" in hw|both) hw_inh=0 ;; *) hw_inh=1 ;; esac
              ;;
            *) hw_inh=1 ;;
          esac
        elif hid_needs_mouse 2>/dev/null; then
          hw_inh=0
        else
          hw_inh=1
        fi
        ;;
    esac
  fi
  case "$submode" in
    apps|cube)
      # Uninhibit only when the IDC pin is loaded and the rear viewport was
      # proven. The assoc stamp used to go true on a service-call exit 0.
      subtouch_inh=1
      if _idc_has_pin; then
        if _rear_ok_fresh || _rear_viewport_ok 0; then
          subtouch_inh=0
          _stamp_rear_ok
        fi
      fi
      ;;
    hid)
      if _subtouch_idc_is_ignore; then subtouch_inh=0; else subtouch_inh=1; fi
      ;;
    *) subtouch_inh=1 ;;
  esac
  virt_inh="$hw_inh"
  if [ "$submode" = "hid" ] || [ "$mode" = "mouse" ]; then
    virt_inh=0
  fi
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
