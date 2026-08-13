#!/system/bin/sh
# titan2-power-wake — product belt (permanent).
#
# Root cause: GSI KeyGesture KEY_GESTURE_TYPE_TOGGLE_POWER can COMPLETE while
# mWakefulness stays Asleep → lock without unlock. KEYCODE_WAKEUP always works.
# Cube MTK overlay sets powerDecouple*=false; this belt is fail-closed backup.
#
# Listens mtk-pmic-keys (/dev/input/event1). KEY_POWER DOWN + Asleep|Dozing
# → input keyevent KEYCODE_WAKEUP. Outer loop restarts if getevent dies.
export PATH=/system/bin:/system/xbin:/vendor/bin:$PATH
ST=/data/local/tmp
PIDF=$ST/titan2_power_wake.pid
LOG=$ST/titan2_power_wake.log
DEV=/dev/input/event1

log() {
  mkdir -p "$ST" 2>/dev/null || true
  echo "power-wake: $*" >>"$LOG" 2>/dev/null || true
  chmod 644 "$LOG" 2>/dev/null || true
}

# Single instance (replace dead pid only)
if [ -f "$PIDF" ]; then
  old=$(cat "$PIDF" 2>/dev/null | tr -d '\r\n ')
  case "$old" in
    ''|*[!0-9]*) ;;
    *)
      if [ -d "/proc/$old" ] && [ "$old" != "$$" ]; then
        # another live power-wake — exit
        if tr '\0' ' ' <"/proc/$old/cmdline" 2>/dev/null | grep -q titan2-power-wake; then
          exit 0
        fi
      fi
      ;;
  esac
fi
echo $$ >"$PIDF" 2>/dev/null || true
log "start pid=$$"

# Wait for input node
i=0
while [ ! -c "$DEV" ] && [ "$i" -lt 90 ]; do
  sleep 1
  i=$((i + 1))
done
if [ ! -c "$DEV" ]; then
  log "no $DEV — exit"
  exit 1
fi

# Forever: getevent can exit on device reset; always restart.
while true; do
  log "getevent attach $DEV"
  # -lt is timed lines; -ql is label. Use -ql; pipefail not needed in toybox sh.
  getevent -ql "$DEV" 2>/dev/null | while read -r line; do
    case "$line" in
      *KEY_POWER*DOWN*|*POWER*DOWN*)
        w=$(dumpsys power 2>/dev/null | grep -m1 'mWakefulness=' | head -1)
        case "$w" in
          *Asleep*|*Dozing*)
            input keyevent KEYCODE_WAKEUP 2>/dev/null
            log "POWER while $w -> WAKEUP"
            ;;
        esac
        ;;
    esac
  done
  log "getevent exit — reattach in 1s"
  sleep 1
done
