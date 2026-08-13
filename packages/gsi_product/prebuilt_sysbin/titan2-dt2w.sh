#!/system/bin/sh
# titan2-dt2w — OPTIMIZE Phase 3 peel: double-tap-to-wake plane
# SoT: docs/project/OPTIMIZE_SOURCE_PRODUCT.md
# Invoked by pad-agent:
#   apply   — apply_dt2w (plane titan2_dt2w 0|1; default 1 when unset)
#   version
# Never force-enable when user/Controls turned it off.
# Never enables wake on touchPad/sub_touch (pad surfaces stay park for DT2W).
export PATH=/system/bin:/system/xbin:/vendor/bin:$PATH
T2=/data/misc/titan2
ST=/data/local/tmp
DT2W_VER=2.182-dt2w-no-lift

log() {
  mkdir -p "$ST" 2>/dev/null || true
  { echo "dt2w: $*" >>"$ST/titan2_pad_agent.log"; } 2>/dev/null || true
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

# DT2W = digitizer double-tap only (secure/system double_tap_to_wake + sysfs).
# NEVER couple to Settings.Secure.WAKE_GESTURE_ENABLED / ambient_tilt_to_wake —
# those are "lift to wake" (TYPE_WAKE_GESTURE / tilt). Forcing them ON with DT2W
# made Lift to wake un-disableable (pad-agent re-applied every boot).
apply_dt2w() {
  want=`read_first titan2_dt2w`
  case "$want" in
    0|false|off|OFF|no|NO) want=0 ;;
    1|true|on|ON|yes|YES) want=1 ;;
    *)
      # Fall back to settings if plane empty
      g=`settings get secure double_tap_to_wake 2>/dev/null | tr -d '\r'`
      case "$g" in 0) want=0 ;; *) want=1 ;; esac
      ;;
  esac
  if [ "$want" = "0" ]; then
    settings put system double_tap_to_wake 0 >/dev/null 2>&1 || true
    settings put secure double_tap_to_wake 0 >/dev/null 2>&1 || true
    setprop persist.sys.doubletapwake 0 >/dev/null 2>&1 || true
    for d in /sys/class/input/input*; do
      [ -f "$d/wake_gesture" ] || continue
      n=`cat "$d/name" 2>/dev/null` || continue
      case "$n" in
        synaptics*|fts*|goodix*|nt36*|focaltech*|touchPad|sub_touch)
          echo 0 > "$d/wake_gesture" 2>/dev/null || true
          ;;
      esac
    done
    echo "want=0" >"$ST/titan2_dt2w_status" 2>/dev/null || true
    chmod 666 "$ST/titan2_dt2w_status" 2>/dev/null || true
    return 0
  fi
  settings put system double_tap_to_wake 1 >/dev/null 2>&1 || true
  settings put secure double_tap_to_wake 1 >/dev/null 2>&1 || true
  setprop persist.sys.doubletapwake 1 >/dev/null 2>&1 || true
  for d in /sys/class/input/input*; do
    [ -f "$d/wake_gesture" ] || continue
    n=`cat "$d/name" 2>/dev/null` || continue
    case "$n" in
      synaptics*|fts*|goodix*|nt36*|focaltech*)
        cur=`cat "$d/wake_gesture" 2>/dev/null`
        [ "$cur" = "1" ] || echo 1 > "$d/wake_gesture" 2>/dev/null || true
        ;;
      touchPad|sub_touch)
        cur=`cat "$d/wake_gesture" 2>/dev/null`
        [ "$cur" = "0" ] || echo 0 > "$d/wake_gesture" 2>/dev/null || true
        ;;
    esac
  done
  echo "want=1" >"$ST/titan2_dt2w_status" 2>/dev/null || true
  chmod 666 "$ST/titan2_dt2w_status" 2>/dev/null || true
  return 0
}

cmd=${1:-apply}
case "$cmd" in
  apply|run|"")
    apply_dt2w
    ;;
  version|-v|--version)
    echo "$DT2W_VER"
    ;;
  *)
    echo "usage: titan2-dt2w.sh apply|version" >&2
    exit 2
    ;;
esac
