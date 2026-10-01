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
TW_VER=2.170-typing-watch-nofork

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
# One line, no fork. Drops CR, space, and tab the way tr -d did.
_tw_read() {
  _tw_read_v=
  [ -r "$1" ] || return 1
  IFS= read -r _tw_read_v < "$1" || [ -n "$_tw_read_v" ] || return 1
  _old=$_tw_read_v
  _tw_read_v=
  while [ -n "$_old" ]; do
    _ch=${_old%"${_old#?}"}
    _old=${_old#?}
    case "$_ch" in
      [[:space:]]) ;;
      *) _tw_read_v=$_tw_read_v$_ch ;;
    esac
  done
  [ -n "$_tw_read_v" ]
}
_tw_set_now() {
  # Uptime milliseconds. Do not use _m here; _m is the pad mode.
  _now=0
  _up=
  IFS= read -r _up < /proc/uptime || return 0
  _sec=${_up%% *}
  _i=${_sec%%.*}
  _frac=${_sec#*.}
  [ "$_frac" = "$_sec" ] && _frac=
  case "$_i" in ''|*[!0-9]*) return 0 ;; esac
  _f3=${_frac}000
  _rest=${_f3#???}
  _f3=${_f3%"$_rest"}
  case "$_f3" in ''|*[!0-9]*) _f3=0 ;; esac
  _now=$((_i * 1000 + 10#$_f3))
}
_tw_now_ms() {
  _tw_set_now
  echo "$_now"
}
_tw_set_cool() {
  _cool=
  for _f in "$T2/titan2_pad_cursor_cool_ms" "$ST/titan2_pad_cursor_cool_ms" \
      "$T2/titan2_pad_cursor_pause_ms" "$ST/titan2_pad_cursor_pause_ms"; do
    [ -f "$_f" ] || continue
    _tw_read "$_f" || continue
    case "$_tw_read_v" in ''|0|*[!0-9]*) continue ;; *) _cool=$_tw_read_v; break ;; esac
  done
  case "$_cool" in ''|*[!0-9]*) _cool=500 ;; esac
  [ "$_cool" -lt 100 ] 2>/dev/null && _cool=100
  [ "$_cool" -gt 5000 ] 2>/dev/null && _cool=5000
}
_tw_cool() {
  _tw_set_cool
  echo "$_cool"
}
_tw_set_pause() {
  _pnow=0
  for _f in "$T2/titan2_pad_cursor_pause" "$ST/titan2_pad_cursor_pause"; do
    [ -f "$_f" ] || continue
    _tw_read "$_f" || continue
    case "$_tw_read_v" in 1|true|on|yes) _pnow=1; return 0 ;; esac
  done
}
_tw_pause_on() {
  _tw_set_pause
  [ "$_pnow" = 1 ]
}
_tw_set_mode() {
  _m=
  if _tw_read "$T2/titan2_pad_mode"; then
    _m=$_tw_read_v
  fi
  if [ -z "$_m" ]; then
    _tw_read "$ST/titan2_pad_mode" && _m=$_tw_read_v
  fi
}
_tw_mode() {
  _tw_set_mode
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
_tw_have_usleep=0
command -v usleep >/dev/null 2>&1 && _tw_have_usleep=1
while true; do
  # Do not die when adb su parent exits (PPID→1). Only exit if pad-agent lock holder gone.
  # Idle tick must not fork a shell pipeline. cat|tr|awk|date at 20 Hz was ~300 forks/s.
  if [ -f "$AGENT_LOCKDIR/pid" ]; then
    _ap=
    _tw_read "$AGENT_LOCKDIR/pid" && _ap=$_tw_read_v
    if [ -n "$_ap" ] && [ ! -d "/proc/$_ap" ]; then
      exit 0
    fi
  fi
  _tw_set_mode
  case "$_m" in
    mouse|trackpad) ;;
    *)
      [ "$_tw_locked" = "1" ] && _tw_release
      if [ "$_tw_have_usleep" = 1 ]; then usleep 100000; else sleep 0.1; fi
      continue
      ;;
  esac
  _tw_set_cool
  _tw_set_now
  # KEYS ONLY u2014 mtime is 1s resolution; also hash content so same-second keys re-arm.
  # Do not watch pause files (unlock writing pause=0 re-armed thrash).
  # 2.161: body must look like unix seconds/ms (10+ digits). Junk "1" / empty
  # ops stamps were re-arming hard_park and killing mouse u2192 trackpad residual.
  _best_mt=0
  _body=""
  for _f in "$ACTIVITY" "$T2/titan2_key_activity"; do
    [ -f "$_f" ] || continue
    _mt=$(stat -c %Y "$_f" 2>/dev/null) || continue
    case "$_mt" in ''|*[!0-9]*) continue ;; esac
    _tw_read "$_f" || continue
    _b=$_tw_read_v
    case "$_b" in
      [0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]*) ;;
      *) continue ;;
    esac
    if [ "$_mt" -ge "$_best_mt" ] 2>/dev/null; then
      _best_mt=$_mt
      _body=$_b
    fi
  done
  _sig="${_best_mt}:${_body}"
  # Key edge u2192 cool-down. Age comes from the stamp value, not file mtime.
  if [ "$_best_mt" -gt 0 ] 2>/dev/null && [ -n "$_body" ] && [ "$_sig" != "$_tw_last_sig" ]; then
    _wall=$(date +%s 2>/dev/null) || _wall=0
    _act=$_body
    case "$_act" in
      [0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]*)
        _act=${_act%???}
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
  # pause plane rising edge only (never re-arm every tick u2014 multi-sec lock)
  _tw_set_pause
  if [ "$_pnow" = "1" ] && [ "${_tw_prev_pause:-0}" != "1" ]; then
    if [ "$_now" -gt 0 ] 2>/dev/null; then
      _tw_unlock_ms=$((_now + _cool)) 2>/dev/null || _tw_unlock_ms=0
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
  if [ "$_tw_have_usleep" = 1 ]; then usleep 50000; else sleep 0.05; fi
done
exit 0
