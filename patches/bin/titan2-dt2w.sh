#!/system/bin/sh
# titan2-dt2w — OPTIMIZE Phase 3 peel: double-tap-to-wake plane
# SoT: docs/project/OPTIMIZE_SOURCE_PRODUCT.md
# Invoked by pad-agent:
#   apply   — apply_dt2w (plane titan2_dt2w 0|1; default 1 when unset)
#   version
# Rear-panel DT2W only (OEM Agui ioctl type 100 on /dev/touch).
# Never arms the front synaptics wake_gesture — that wakes the main glass.
export PATH=/system/bin:/system/xbin:/vendor/bin:$PATH
T2=/data/misc/titan2
ST=/data/local/tmp
DT2W_VER=2.183-dt2w-sysfs

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

# Rear DT2W = OEM ioctl type 100 on /dev/touch (0x40044103) + KEY_POWER grab
# on sub_touch. Never write synaptics wake_gesture (that wakes the main SoC).
apply_dt2w() {
  want=`read_first titan2_dt2w`
  case "$want" in
    0|false|off|OFF|no|NO) want=0 ;;
    1|true|on|ON|yes|YES) want=1 ;;
    *)
      g=`settings get global titan2_dt2w 2>/dev/null | tr -d '\r'`
      case "$g" in 1|true|on) want=1 ;; *) want=0 ;; esac
      ;;
  esac
  GEST=/system/bin/titan2-sub-dt2w
  [ -x "$GEST" ] || GEST=/data/local/tmp/titan2-sub-dt2w
  if [ "$want" = "0" ]; then
    if [ -x "$GEST" ]; then
      "$GEST" disable >/dev/null 2>&1 || true
    fi
    pkill -f titan2-sub-dt2w >/dev/null 2>&1 || true
    echo "want=0 rear" >"$ST/titan2_dt2w_status" 2>/dev/null || true
    chmod 666 "$ST/titan2_dt2w_status" 2>/dev/null || true
    return 0
  fi
  settings put system sub_screen_enabled 1 >/dev/null 2>&1 || true
  if [ -x "$GEST" ]; then
    "$GEST" enable >/dev/null 2>&1 || true
    if ! pgrep -f titan2-sub-dt2w >/dev/null 2>&1; then
      "$GEST" >>"$ST/titan2_sub_dt2w.log" 2>&1 &
    fi
  fi
  echo "want=1 rear" >"$ST/titan2_dt2w_status" 2>/dev/null || true
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
