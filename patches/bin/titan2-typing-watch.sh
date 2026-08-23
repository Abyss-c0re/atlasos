#!/system/bin/sh
# titan2-typing-watch — OPTIMIZE Phase 3 peel from pad-agent tower
# Prefer in-process touchpadd park (plane PAUSE + cool); kill only if legacy binary
# lacks park= status. Spawned by pad-agent _ensure_typing_watch.
# SoT: docs/project/PAD_TOUCHPADD_CONTRACT.md · OPTIMIZE_SOURCE_PRODUCT.md
export PATH=/system/bin:/system/xbin:/vendor/bin:$PATH
T2=/data/misc/titan2
ST=/data/local/tmp
PAD_STATUS=$ST/titan2_pad_status
ACTIVITY=$ST/titan2_key_activity
TP_LOG=$ST/titan2_touchpadd.log
AGENT_LOCKDIR=$T2/pad-agent.lockdir
TW_VER=2.169-typing-watch-plane

echo "typing-watch pid=$$ parent=$PPID ver=$TW_VER" >"$ST/titan2_typing_watch_status" 2>/dev/null
chmod 666 "$ST/titan2_typing_watch_status" 2>/dev/null || true
# 2.167: always publish pidfile so agent live-detect works after agent restart
echo $$ >"$ST/titan2_typing_watch.pid" 2>/dev/null || true
chmod 666 "$ST/titan2_typing_watch.pid" 2>/dev/null || true
_tw_last_sig=""
_tw_unlock_ms=0
_tw_locked=0
_tw_prev_pause=0
if [ -x /data/local/tmp/titan2-touchpadd ]; then
  _TW_TP=/data/local/tmp/titan2-touchpadd
elif [ -x /data/adb/modules/titan2_touchpadd/system/bin/titan2-touchpadd ]; then
  _TW_TP=/data/adb/modules/titan2_touchpadd/system/bin/titan2-touchpadd
else
  _TW_TP=/system/bin/titan2-touchpadd
fi
_tw_now_ms() {
  if command -v awk >/dev/null 2>&1; then
    _m=`awk '{printf "%d\n", $1 * 1000}' /proc/uptime 2>/dev/null`
    case "$_m" in ''|*[!0-9]*) ;; *) echo "$_m"; return ;; esac
  fi
  _line=`cat /proc/uptime 2>/dev/null` || { echo 0; return; }
  _sec=${_line%% *}; _i=${_sec%%.*}
  case "$_i" in ''|*[!0-9]*) echo 0 ;; *) echo $((_i * 1000)) ;; esac
}
_tw_cool() {
  _ms=
  for _f in "$T2/titan2_pad_cursor_cool_ms" "$ST/titan2_pad_cursor_cool_ms" \
      "$T2/titan2_pad_cursor_pause_ms" "$ST/titan2_pad_cursor_pause_ms"; do
    [ -f "$_f" ] || continue
    _ms=`cat "$_f" 2>/dev/null | tr -d '\r\n \t'`
    case "$_ms" in ''|0|*[!0-9]*) _ms=; continue ;; *) break ;; esac
  done
  case "$_ms" in ''|*[!0-9]*) _ms=500 ;; esac
  [ "$_ms" -lt 100 ] 2>/dev/null && _ms=100
  [ "$_ms" -gt 5000 ] 2>/dev/null && _ms=5000
  echo "$_ms"
}
_tw_pause_on() {
  for _f in "$T2/titan2_pad_cursor_pause" "$ST/titan2_pad_cursor_pause"; do
    [ -f "$_f" ] || continue
    _v=`cat "$_f" 2>/dev/null | tr -d '\r\n \t'`
    case "$_v" in 1|true|on|yes) return 0 ;; esac
  done
  return 1
}
_tw_mode() {
  _m=`cat "$T2/titan2_pad_mode" 2>/dev/null | tr -d '\r\n \t'`
  [ -n "$_m" ] || _m=`cat "$ST/titan2_pad_mode" 2>/dev/null | tr -d '\r\n \t'`
  echo "$_m"
}
_tw_inhibit() {
  _v="$1"
  for inh in /sys/class/input/input*/inhibited; do
    [ -e "$inh" ] || continue
    n=`cat "$(dirname "$inh")/name" 2>/dev/null` || continue
    case "$n" in
      touchPad|titan2-orient-mouse)
        echo "$_v" >"$inh" 2>/dev/null || true ;;
    esac
  done
}
_tw_park() {
  # Hold only. Java owns pause=1. Leave TP/orient running so the pointer does not warp.
  _inproc=0
  if pidof titan2-touchpadd >/dev/null 2>&1; then
    if [ -f "$ST/titan2_touchpadd_status" ] \
        && grep -q "park=" "$ST/titan2_touchpadd_status" 2>/dev/null; then
      _inproc=1
    fi
  fi
  if [ "$_inproc" = "1" ]; then
    case "$(_tw_mode)" in
      trackpad) _tw_inhibit 1 ;;
      mouse) ;;
      *) _tw_inhibit 1 ;;
    esac
    echo "mode=$(_tw_mode) typing_lock=1 inproc_park" >"$PAD_STATUS" 2>/dev/null || true
  else
    _tw_inhibit 1
    echo "mode=$(_tw_mode) typing_lock=1 sysfs_park" >"$PAD_STATUS" 2>/dev/null || true
  fi
  chmod 666 "$PAD_STATUS" 2>/dev/null || true
  _tw_locked=1
}
_tw_start_mouse() {
  [ -x "$_TW_TP" ] || return 1
  _click=`cat "$T2/titan2_pad_click" 2>/dev/null | tr -d '\r\n \t'`
  case "$_click" in 0|1) ;; *) _click=1 ;; esac
  _trc=`cat "$T2/titan2_pad_top_row_cursor" 2>/dev/null | tr -d '\r\n \t'`
  case "$_trc" in 0|1) ;; *) _trc=1 ;; esac
  _surf=`cat "$T2/titan2_input_surface" 2>/dev/null | tr -d '\r\n \t'`
  case "$_surf" in hw|sub|both) ;; *) _surf=hw ;; esac
  _flip=`cat "$T2/titan2_sub_touch_flip_x" 2>/dev/null | tr -d '\r\n \t'`
  case "$_flip" in 0|1) ;; *) _flip=1 ;; esac
  # 2.161: start touchpadd FIRST while pad still inhibited, then uninhibit.
  # Uninhibit-before-spawn left native ABS trackpad for hundreds of ms.
  if ! pidof titan2-touchpadd >/dev/null 2>&1; then
    : >"$TP_LOG" 2>/dev/null
    chmod 666 "$TP_LOG" 2>/dev/null || true
    LOGCAT_OUTPUT=true KEYBOARD_FEATURES=false TAP_TO_CLICK="$_click" \
      TEXT_CARET_NAV="$_trc" TOP_ROW_CURSOR="$_trc" TOP_ROW_ONLY=0 \
      PAD_SURFACE="$_surf" FLIP_X="$_flip" \
      "$_TW_TP" >>"$TP_LOG" 2>&1 &
    # brief wait for uinput virtual mouse before opening HW pad to Android
    if command -v usleep >/dev/null 2>&1; then usleep 80000; else sleep 0.08; fi
  fi
  _pid=`pidof titan2-touchpadd 2>/dev/null | awk '{print $1; exit}'`
  if [ -z "$_pid" ]; then
    # failed spawn — keep HW inhibited (never native trackpad fallback)
    _tw_inhibit 1
    echo "mode=mouse applied=tp_fail typing_lock=0" >"$PAD_STATUS" 2>/dev/null || true
    chmod 666 "$PAD_STATUS" 2>/dev/null || true
    return 1
  fi
  _tw_inhibit 0
  echo "mode=mouse applied=running pid=$_pid typing_lock=0 unlocked" >"$PAD_STATUS" 2>/dev/null || true
  chmod 666 "$PAD_STATUS" 2>/dev/null || true
  return 0
}
_tw_release() {
  _m=`_tw_mode`
  # Java Handler owns pause=0. Writing it here unparked the pointer mid-word.
  case "$_m" in
    mouse)
      # Inproc: TP still running — just unpark emit; only start if dead.
      if pidof titan2-touchpadd >/dev/null 2>&1; then
        _tw_inhibit 0
        _pid=`pidof titan2-touchpadd 2>/dev/null | awk '{print $1; exit}'`
        echo "mode=mouse applied=running pid=${_pid:-?} typing_lock=0 unlocked inproc" >"$PAD_STATUS" 2>/dev/null || true
        chmod 666 "$PAD_STATUS" 2>/dev/null || true
      else
        _tw_start_mouse
      fi
      ;;
    trackpad)
      _tw_inhibit 0
      echo "mode=trackpad typing_lock=0 unlocked" >"$PAD_STATUS" 2>/dev/null || true
      chmod 666 "$PAD_STATUS" 2>/dev/null || true
      ;;
    *)
      _tw_inhibit 1
      echo "mode=${_m:-off} typing_lock=0 unlocked" >"$PAD_STATUS" 2>/dev/null || true
      chmod 666 "$PAD_STATUS" 2>/dev/null || true
      ;;
  esac
  _tw_locked=0
  _tw_unlock_ms=0
}
while true; do
  # Do not die when adb su parent exits (PPID→1). Only exit if pad-agent lock holder gone.
  if [ -f "$AGENT_LOCKDIR/pid" ]; then
    _ap=`cat "$AGENT_LOCKDIR/pid" 2>/dev/null | tr -d '\r\n '`
    if [ -n "$_ap" ] && [ ! -d "/proc/$_ap" ]; then
      exit 0
    fi
  fi
  _m=`_tw_mode`
  case "$_m" in
    mouse|trackpad) ;;
    *)
      [ "$_tw_locked" = "1" ] && _tw_release
      if command -v usleep >/dev/null 2>&1; then usleep 100000; else sleep 0.1; fi
      continue
      ;;
  esac
  _cool=`_tw_cool`
  _now=`_tw_now_ms`
  # KEYS ONLY — mtime is 1s resolution; also hash content so same-second keys re-arm.
  # Do not watch pause files (unlock writing pause=0 re-armed thrash).
  # 2.161: body must look like unix seconds/ms (10+ digits). Junk "1" / empty
  # ops stamps were re-arming hard_park and killing mouse → trackpad residual.
  _best_mt=0
  _body=""
  for _f in "$ACTIVITY" "$T2/titan2_key_activity"; do
    [ -f "$_f" ] || continue
    _mt=`stat -c %Y "$_f" 2>/dev/null` || continue
    case "$_mt" in ''|*[!0-9]*) continue ;; esac
    _b=`cat "$_f" 2>/dev/null | tr -d '\r\n \t'`
    case "$_b" in
      [0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]*) ;;
      *) continue ;;
    esac
    if [ "$_mt" -ge "$_best_mt" ] 2>/dev/null; then
      _best_mt=$_mt
      _body=$_b
    fi
  done
  _wall=`date +%s 2>/dev/null` || _wall=0
  _sig="${_best_mt}:${_body}"
  # Key edge → cool-down. 2.161: age from stamp **value** (unix s/ms), NOT file
  # mtime. Ops/heal writes refresh mtime with old bodies and were hard_park
  # killing mouse every tip land (user: mouse became trackpad).
  if [ "$_best_mt" -gt 0 ] 2>/dev/null && [ -n "$_body" ] && [ "$_sig" != "$_tw_last_sig" ]; then
    _act=$_body
    case "$_act" in
      [0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]*)
        _act=`awk -v n="$_act" 'BEGIN{printf "%d\n", n/1000}' 2>/dev/null` || _act=0
        ;;
    esac
    _age=9999
    case "$_act" in ''|*[!0-9]*) ;; *)
      _age=$((_wall - _act)) 2>/dev/null || _age=9999
      ;;
    esac
    case "$_age" in ''|-*|*[!0-9]*) _age=9999 ;; esac
    _tw_last_sig=$_sig
    if [ "$_age" -le 3 ] 2>/dev/null && [ "$_now" -gt 0 ] 2>/dev/null; then
      _tw_unlock_ms=$((_now + _cool)) 2>/dev/null || _tw_unlock_ms=0
    fi
  fi
  # pause plane rising edge only (never re-arm every tick — multi-sec lock)
  _pnow=0
  _tw_pause_on && _pnow=1
  if [ "$_pnow" = "1" ] && [ "${_tw_prev_pause:-0}" != "1" ]; then
    if [ "$_now" -gt 0 ] 2>/dev/null; then
      _tw_unlock_ms=`expr "$_now" + "$_cool" 2>/dev/null` || _tw_unlock_ms=0
    fi
  fi
  _tw_prev_pause=$_pnow
  # Pause plane only. key_activity is also the LED stamp and used to keep
  # typing_lock=1 forever, which made pad-apply refuse to change mode.
  _want=0
  if [ "$_pnow" = "1" ]; then
    _want=1
  fi
  if [ "$_want" = "1" ]; then
    _tw_park
  elif [ "$_tw_locked" = "1" ]; then
    _tw_release
  fi
  if command -v usleep >/dev/null 2>&1; then usleep 50000; else sleep 0.05; fi
done
exit 0
