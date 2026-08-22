#!/system/bin/sh
# titan2-pad-apply — OPTIMIZE Phase 3 peel: pad mode apply stack
# SoT: docs/project/OPTIMIZE_SOURCE_PRODUCT.md · PAD_TOUCHPADD_CONTRACT.md
# Invoked by pad-agent:
#   apply|pad       — apply_pad (mouse|trackpad|off)
#   mouse           — start_mouse
#   trackpad        — start_native_trackpad
#   stop            — stop_pad
#   caret           — apply_text_caret_nav
#   orient ensure|kill|up
#   version
#
# State mirrors: ST/titan2_pad_apply_last (LAST_PAD/CLICK/FOLLOW/…)
export PATH=/system/bin:/system/xbin:/vendor/bin:$PATH
T2=/data/misc/titan2
ST=/data/local/tmp
PAD_STATUS=$ST/titan2_pad_status
TP_LOG=$ST/titan2_touchpadd.log
CARET_STATUS=$ST/titan2_caret_status
APPLY_LAST=$ST/titan2_pad_apply_last
PAD_APPLY_VER=2.219-rom-lock

# Prefer GSI/system binary (Phase 1.5 SoT); tip only for lab iteration.
TOUCHPADD=/system/bin/titan2-touchpadd
[ -x "$TOUCHPADD" ] || TOUCHPADD=/data/local/tmp/titan2-touchpadd
[ -x "$TOUCHPADD" ] || TOUCHPADD=/data/adb/modules/titan2_touchpadd/system/bin/titan2-touchpadd

log() {
  mkdir -p "$ST" 2>/dev/null || true
  { echo "pad-apply: $*" >>"$ST/titan2_pad_agent.log"; } 2>/dev/null || true
}

_sleep_brief() {
  if command -v usleep >/dev/null 2>&1; then usleep 20000
  else sleep 0.02 2>/dev/null || true
  fi
}

_read_line_file() {
  f="$1"; [ -f "$f" ] || { echo ""; return 1; }
  v=""; IFS= read -r v < "$f" || true
  case "$v" in *$'\r') v=${v%$'\r'} ;; esac
  echo "$v"; return 0
}

read_first() {
  _n=$1; best_mt=-1; best_v=""; found=0
  for f in "$T2/$_n" "$ST/$_n"; do
    [ -f "$f" ] || continue
    v=`_read_line_file "$f"`; v=`echo "$v" | tr -d '\r\n \t'`
    case "$v" in ''|null|NULL|-|clear|CLEAR) continue ;; esac
    mt=`stat -c %Y "$f" 2>/dev/null` || mt=0
    case "$mt" in ''|*[!0-9]*) mt=0;; esac
    if [ "$found" = "0" ] || [ "$mt" -ge "$best_mt" ] 2>/dev/null; then
      best_mt=$mt; best_v=$v; found=1
    fi
  done
  [ "$found" = "1" ] && echo "$best_v" || echo ""
}

write_if_changed() {
  f="$1"; v="$2"
  [ -n "$f" ] || return 0
  cur=`_read_line_file "$f" 2>/dev/null | tr -d '\r\n'`
  [ "$cur" = "$v" ] && return 0
  printf '%s' "$v" >"$f" 2>/dev/null || true
  chmod 666 "$f" 2>/dev/null || true
}

read_pad_mode() {
  m=`read_first titan2_pad_mode`
  case "$m" in
    off|OFF|0) echo off; return ;;
    trackpad|TRACKPAD|pad|PAD|native|NATIVE) echo trackpad; return ;;
    mouse|MOUSE|module|MODULE|on|ON|1|global|GLOBAL) echo mouse; return ;;
  esac
  case "`read_first titan2_touchpad_enabled`" in 1|true|on|ON) echo mouse; return ;; esac
  echo off
}
read_pad_click() {
  case "`read_first titan2_pad_click`" in 0|false|off|OFF|no|NO) echo 0;; *) echo 1;; esac
}
read_pad_top_row_cursor() {
  case "`read_first titan2_pad_top_row_cursor`" in 0|false|off|OFF|no|NO) echo 0;; *) echo 1;; esac
}
read_pad_top_row_only() {
  case "`read_pad_mode`" in mouse|trackpad) echo 0; return 0 ;; esac
  case "`read_first titan2_pad_top_row_only`" in 1|true|on|ON|yes|YES) echo 1;; *) echo 0;; esac
}
read_pad_follow_orient() {
  case "`read_first titan2_pad_follow_orient`" in 0|false|off|OFF|no|NO) echo 0;; *) echo 1;; esac
}
# Surface rotation 0..3 (PadOrientationService / Controls). 90/180/270 = alias.
# Never treat 1/2/3 as invalid — that wrote 0 over landscape and killed follow.
read_pad_rotation() {
  v=`read_first titan2_pad_rotation`
  case "$v" in
    0|1|2|3) echo "$v" ;;
    90) echo 1 ;;
    180) echo 2 ;;
    270) echo 3 ;;
    *) echo 0 ;;
  esac
}
read_sub_flip_x() {
  case "`read_first titan2_sub_touch_flip_x`" in 0|false|off|OFF) echo 0;; *) echo 1;; esac
}
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

publish_pad_rotation() {
  rot=`read_pad_rotation`
  write_if_changed "$ST/titan2_pad_rotation" "$rot"
  write_if_changed "$T2/titan2_pad_rotation" "$rot"
}

_run_pad_idc() {
  for _c in \
      "$ST/titan2-pad-idc.sh" \
      /data/local/tmp/titan2-pad-idc.sh \
      /data/adb/modules/titan2_pad_agent/system/bin/titan2-pad-idc.sh \
      /system/bin/titan2-pad-idc.sh; do
    [ -f "$_c" ] && [ -r "$_c" ] && { /system/bin/sh "$_c" "$@"; return $?; }
  done
  return 1
}
set_pad_inhibited() { _run_pad_idc inhibit "${1:-1}" "${2:-}"; }
set_touchpad_idc() { _run_pad_idc touchpad "${1:-ignore}" "${2:-}"; LAST_IDC_KIND="${1:-ignore}"; }

hid_session_on() {
  for f in $T2/titan2_usb_hid_session /data/local/tmp/titan2_usb_hid_session /data/adb/titan2/titan2_usb_hid_session; do
    [ -f "$f" ] || continue
    v=`_read_line_file "$f"`
    case "$v" in 1|true|on|ON) return 0;; esac
  done
  return 1
}
hid_needs_mouse() {
  hid_session_on || return 1
  for f in $T2/titan2_usb_hid_mouse /data/local/tmp/titan2_usb_hid_mouse /data/adb/titan2/titan2_usb_hid_mouse; do
    [ -f "$f" ] || continue
    v=`_read_line_file "$f"`
    case "$v" in 0|false|off|OFF|no|NO) return 1 ;; 1|true|on|ON|yes|YES) return 0 ;; esac
  done
  return 0
}

TP_PID_CACHE=""
tp_pid() {
  if [ -n "$TP_PID_CACHE" ] && [ -d "/proc/$TP_PID_CACHE" ]; then echo "$TP_PID_CACHE"; return 0; fi
  TP_PID_CACHE=`pidof titan2-touchpadd 2>/dev/null`
  TP_PID_CACHE=`echo $TP_PID_CACHE | awk '{print $1}'`
  echo "$TP_PID_CACHE"
}
tp_up() {
  if [ -n "$TP_PID_CACHE" ] && [ -d "/proc/$TP_PID_CACHE" ]; then return 0; fi
  TP_PID_CACHE=`pidof titan2-touchpadd 2>/dev/null`
  TP_PID_CACHE=`echo $TP_PID_CACHE | awk '{print $1}'`
  [ -n "$TP_PID_CACHE" ]
}
virt_source_mouse_up() {
  for d in /sys/class/input/input*; do
    [ -e "$d/name" ] || continue
    n=`cat "$d/name" 2>/dev/null` || continue
    case "$n" in titan2-virtual-mouse|titan2-touchpadd|titan2_touchpadd|Titan2\ Touchpad) return 0 ;; esac
  done
  return 1
}

# KEEP_DATA / reboot leaves lock=1 and a11y_live=1 on disk. a11y may not bind
# until later. Stale 1 parks mouse and trackpad for every user. Forget on boot;
# Key a11y restamps live keyguard when it actually connects.
_forget_persisted_input_lock() {
  settings put global titan2_input_lock 0 2>/dev/null || true
  settings put global titan2_a11y_live 0 2>/dev/null || true
  for _d in "$T2" "$ST"; do
    printf '0\n' >"$_d/titan2_input_lock" 2>/dev/null || true
    printf '0\n' >"$_d/titan2_a11y_live" 2>/dev/null || true
    chmod 666 "$_d/titan2_input_lock" "$_d/titan2_a11y_live" 2>/dev/null || true
  done
}

_a11y_live_ok() {
  case "`read_first titan2_a11y_live 2>/dev/null`" in 1|true|on|yes) return 0 ;; esac
  return 1
}

_input_unlocked_ok() {
  case "`getprop sys.boot_completed 2>/dev/null | tr -d '\r'`" in 1) ;; *) return 1 ;; esac
  # No PIN/pattern: leftover lock=1 after KEEP_DATA is not a credential park.
  # Same as the live heal — apply must run without a settings put.
  case "`settings get secure lockscreen.disabled 2>/dev/null | tr -d '\r'`" in
    1|true|TRUE) return 0 ;;
  esac
  if _a11y_live_ok; then
    case "`read_first titan2_input_lock 2>/dev/null`" in 1|true|on|yes) return 1 ;; esac
    case "`settings get global titan2_input_lock 2>/dev/null | tr -d '\r'`" in 1|true|on|yes) return 1 ;; esac
  fi
  ce=`getprop sys.user.0.ce_available 2>/dev/null | tr -d '\r' | tr 'A-Z' 'a-z'`
  case "$ce" in 0|false) return 1 ;; esac
  return 0
}
_lockscreen_park_input() {
  kill_touchpadd 2>/dev/null || true
  kill_orient_rel 2>/dev/null || true
  TP_PID_CACHE=""; LAST_CARET_KIND=off
  set_pad_inhibited 1 2>/dev/null || true
  set_touchpad_idc ignore 2>/dev/null || true
  echo "mode=$(read_pad_mode 2>/dev/null || echo off) applied=lockpark" >"$PAD_STATUS" 2>/dev/null || true
  chmod 666 "$PAD_STATUS" 2>/dev/null || true
}

_typing_watch_live() {
  _wp=`cat "$ST/titan2_typing_watch.pid" 2>/dev/null | tr -d '\r\n '`
  if [ -n "$_wp" ] && [ -d "/proc/$_wp" ]; then
    grep -a -F -q "titan2-typing-watch" "/proc/$_wp/cmdline" 2>/dev/null && return 0
  fi
  for _wp in `pgrep -f 'titan2-typing-watch' 2>/dev/null`; do
    case "$_wp" in ''|*[!0-9]*) continue ;; esac
    grep -a -F -q "titan2-typing-watch" "/proc/$_wp/cmdline" 2>/dev/null || continue
    return 0
  done
  return 1
}
_is_cursor_pause_on() {
  case "`read_first titan2_pad_cursor_pause`" in 1|true|on|ON|yes|YES) return 0 ;; esac
  return 1
}
_keys_fresh_for_typing_lock() {
  # Simplified: recent key_activity mtime within 2s wall
  case "`read_pad_mode`" in mouse|trackpad) ;; *) return 1 ;; esac
  _best=0
  for _f in "$ST/titan2_key_activity" "$T2/titan2_key_activity" "$ST/titan2_pad_cursor_pause"; do
    [ -f "$_f" ] || continue
    _mt=`stat -c %Y "$_f" 2>/dev/null` || continue
    [ "$_mt" -gt "$_best" ] 2>/dev/null && _best=$_mt
  done
  [ "$_best" -gt 0 ] 2>/dev/null || return 1
  _wall=`date +%s 2>/dev/null` || return 1
  _age=$((_wall - _best)) 2>/dev/null || return 1
  [ "$_age" -le 2 ] 2>/dev/null
}
_typing_should_park() {
  _is_cursor_pause_on && return 0
  _keys_fresh_for_typing_lock && return 0
  return 1
}
_ensure_typing_watch() {
  if _typing_watch_live; then return 0; fi
  for _c in \
      "$ST/titan2-typing-watch.sh" \
      /data/local/tmp/titan2-typing-watch.sh \
      /data/adb/modules/titan2_pad_agent/system/bin/titan2-typing-watch.sh \
      /system/bin/titan2-typing-watch.sh; do
    if [ -f "$_c" ]; then
      /system/bin/sh "$_c" run >>"$ST/titan2_typing_watch.log" 2>&1 &
      echo $! >"$ST/titan2_typing_watch.pid" 2>/dev/null || true
      return 0
    fi
  done
  return 1
}

# Restore LAST_* from state file (soft apply path)
LAST_PAD=""; LAST_CLICK=""; LAST_FOLLOW=""; LAST_PAD_SURFACE=""; LAST_SUB_FLIP=""
LAST_TOP_ROW_CURSOR=""; LAST_TOP_ROW_ONLY=""; LAST_CARET_KIND=""; LAST_CURSOR_PAUSE=0
LAST_IDC_KIND=""; TP_TITANKEY_RESTARTS=0
if [ -f "$APPLY_LAST" ]; then
  # shellcheck disable=SC1090
  . "$APPLY_LAST" 2>/dev/null || true
fi
# IDC kind from pad-idc peel
if [ -f "$ST/titan2_idc_kind" ]; then
  LAST_IDC_KIND=`cat "$ST/titan2_idc_kind" 2>/dev/null | tr -d '\r\n '`
fi

_save_apply_last() {
  {
    echo "LAST_PAD='${LAST_PAD:-}'"
    echo "LAST_CLICK='${LAST_CLICK:-}'"
    echo "LAST_FOLLOW='${LAST_FOLLOW:-}'"
    echo "LAST_PAD_SURFACE='${LAST_PAD_SURFACE:-}'"
    echo "LAST_SUB_FLIP='${LAST_SUB_FLIP:-}'"
    echo "LAST_TOP_ROW_CURSOR='${LAST_TOP_ROW_CURSOR:-}'"
    echo "LAST_TOP_ROW_ONLY='${LAST_TOP_ROW_ONLY:-}'"
    echo "LAST_CARET_KIND='${LAST_CARET_KIND:-}'"
    echo "LAST_CURSOR_PAUSE='${LAST_CURSOR_PAUSE:-0}'"
    echo "LAST_IDC_KIND='${LAST_IDC_KIND:-}'"
  } >"$APPLY_LAST" 2>/dev/null || true
  chmod 666 "$APPLY_LAST" 2>/dev/null || true
}


ORIENT_REL=/data/local/tmp/titan2-orient-rel
[ -x /system/bin/titan2-orient-rel ] && ORIENT_REL=/system/bin/titan2-orient-rel
ORIENT_LOG=$ST/titan2_orient_rel.log

OR_PID_CACHE=""
orient_rel_up() {
  if [ -n "$OR_PID_CACHE" ] && [ -d "/proc/$OR_PID_CACHE" ]; then
    return 0
  fi
  OR_PID_CACHE=`pidof titan2-orient-rel 2>/dev/null`
  OR_PID_CACHE=`echo $OR_PID_CACHE | awk '{print $1}'`
  [ -n "$OR_PID_CACHE" ]
}

kill_orient_rel() {
  OR_PID_CACHE=""
  pids=`pidof titan2-orient-rel 2>/dev/null`
  [ -n "$pids" ] || return 0
  for p in $pids; do kill "$p" 2>/dev/null || true; done
  i=0
  while [ $i -lt 5 ]; do
    orient_rel_up || return 0
    _sleep_brief
    i=`expr $i + 1 2>/dev/null` || i=5
  done
  for p in `pidof titan2-orient-rel 2>/dev/null`; do
    kill -9 "$p" 2>/dev/null || true
  done
  OR_PID_CACHE=""
}

# 1 once we know binary is absent — stop infinite need=1 / pad restarts.
ORIENT_REL_MISSING=0

# When follow=1 in mouse mode: grab virtual mouse and re-emit rotated REL.
# Soft only — never kill touchpadd. Missing binary is not a re-apply loop.
ensure_orient_rel() {
  follow=`read_pad_follow_orient`
  if [ "$follow" != "1" ]; then
    kill_orient_rel
    return 0
  fi
  publish_pad_rotation
  # Mirror follow without mtime thrash
  write_if_changed "$ST/titan2_pad_follow_orient" 1
  write_if_changed "$T2/titan2_pad_follow_orient" 1
  if orient_rel_up; then return 0; fi
  if [ "$ORIENT_REL_MISSING" = "1" ]; then
    return 0
  fi
  [ -x "$ORIENT_REL" ] || {
    [ -x /data/local/tmp/titan2-orient-rel ] && ORIENT_REL=/data/local/tmp/titan2-orient-rel
  }
  [ -x "$ORIENT_REL" ] || {
    ORIENT_REL_MISSING=1
    echo "orient-rel: missing binary (follow axes offline; no thrash)" > "$ORIENT_LOG" 2>/dev/null
    chmod 666 "$ORIENT_LOG" 2>/dev/null || true
    return 0
  }
  # need virtual mouse; short wait only — caller retries on later ticks
  if ! virt_source_mouse_up; then
    i=0
    while [ $i -lt 5 ]; do
      virt_source_mouse_up && break
      _sleep_brief
      i=`expr $i + 1 2>/dev/null` || i=5
    done
  fi
  virt_source_mouse_up || {
    echo "orient-rel: no virtual mouse yet" >> "$ORIENT_LOG" 2>/dev/null
    return 1
  }
  : > "$ORIENT_LOG" 2>/dev/null; chmod 666 "$ORIENT_LOG" 2>/dev/null
  (
    "$ORIENT_REL" >>"$ORIENT_LOG" 2>&1
  ) &
  i=0
  while [ $i -lt 5 ]; do
    orient_rel_up && return 0
    _sleep_brief
    i=`expr $i + 1 2>/dev/null` || i=5
  done
  # Daemon may still be starting — not a hard failure for specials path
  orient_rel_up && return 0
  echo "orient-rel: starting" >> "$ORIENT_LOG" 2>/dev/null
  return 0
}

kill_touchpadd() {
  # 2.82: instant — KILL first, no poll sleeps (Off must be <100ms).
  kill_orient_rel
  TP_PID_CACHE=""
  pids=`pidof titan2-touchpadd 2>/dev/null`
  [ -n "$pids" ] || return 0
  for p in $pids; do
    kill -9 "$p" 2>/dev/null || true
  done
  # one more pass for respawn race
  pids=`pidof titan2-touchpadd 2>/dev/null`
  for p in $pids; do
    kill -9 "$p" 2>/dev/null || true
  done
  TP_PID_CACHE=""
  return 0
}

# MOUSE mode: virtual mouse via titan2-touchpadd (module)
# follow=1 → titan2-orient-rel rotates REL to match display (local cursor + HID stamp).
# PAD_SURFACE=hw|sub|both selects touchPad and/or sub_touch; FLIP_X for rear lid.
# Keep waits SHORT — long polls here freeze Alt↔Fn in the main loop.
# B4 / S-HID-08: always KEYBOARD_FEATURES=false; if a live process still holds
# TitanKey (unpatched binary / stale env), kill+restart so only hid_bridge owns it.
LAST_PAD_SURFACE=""
LAST_SUB_FLIP=""
LAST_PAD_CLICK=""
LAST_TOP_ROW_CURSOR=""
LAST_TOP_ROW_ONLY=""
start_mouse() {
  if ! _input_unlocked_ok; then
    _lockscreen_park_input
    return 0
  fi
  # 2.154/2.160: watch owns lock while live; dead watch + sticky lock must not
  # permanently refuse mouse (trackpad fallback residual).
  if [ -f "$PAD_STATUS" ] && grep -q 'typing_lock=1' "$PAD_STATUS" 2>/dev/null; then
    if _typing_watch_live; then
      return 0
    fi
    if _typing_should_park; then
      kill_touchpadd
      set_pad_inhibited 1 force
      LAST_CURSOR_PAUSE=1
      echo "mode=$(read_pad_mode) typing_lock=1 applied=mouse_blocked" >"$PAD_STATUS" 2>/dev/null || true
      chmod 666 "$PAD_STATUS" 2>/dev/null || true
      return 0
    fi
    # stale lock — clear and start mouse below
  fi
  if _typing_watch_live; then
    : # watch live — never soft-block mouse here
  elif _typing_should_park; then
    kill_touchpadd
    set_pad_inhibited 1 force
    LAST_CURSOR_PAUSE=1
    echo "mode=$(read_pad_mode) typing_lock=1 applied=mouse_blocked" >"$PAD_STATUS" 2>/dev/null || true
    chmod 666 "$PAD_STATUS" 2>/dev/null || true
    return 0
  fi
  click=`read_pad_click`
  follow=`read_pad_follow_orient`
  surface=`read_pad_surface`
  # 2.137 Creators: top-row cursor flows with mouse (plane default on)
  trc=`read_pad_top_row_cursor`
  case "$trc" in 0|1) ;; *) trc=1 ;; esac
  if [ "$surface" = "none" ]; then
    surface=hw
  fi
  flipx=`read_sub_flip_x`
  # 2.95: stamp status FIRST so UI sees mouse before any idc/sysfs work
  # (trackpad→mouse felt multi-second while set_touchpad_idc remounted).
  echo "mode=mouse applied=starting click=$click trc=$trc follow=$follow surface=$surface flipx=$flipx" > "$PAD_STATUS"
  chmod 666 "$PAD_STATUS" 2>/dev/null
  [ -x "$TOUCHPADD" ] || {
    echo "mode=mouse applied=no_binary click=$click trc=$trc follow=$follow surface=$surface" > "$PAD_STATUS"
    chmod 666 "$PAD_STATUS" 2>/dev/null
    return 1
  }
  # 2.161: spawn touchpadd while HW still inhibited, THEN uninhibit.
  # Uninhibit-first left native ABS (trackpad feel) whenever TP was dead.
  if tp_up; then
    if [ "$surface" != "$LAST_PAD_SURFACE" ] || [ "$flipx" != "$LAST_SUB_FLIP" ] \
        || [ "$click" != "$LAST_PAD_CLICK" ] \
        || [ "${LAST_TOP_ROW_CURSOR:-}" != "$trc" ]; then
      kill_touchpadd
      TP_PID_CACHE=""
    fi
  fi
  if ! tp_up; then
    : > "$TP_LOG" 2>/dev/null; chmod 666 "$TP_LOG" 2>/dev/null
    # TEXT_CARET_NAV mirrors top-row energy when trc=1 (no separate stopper).
    LOGCAT_OUTPUT=true KEYBOARD_FEATURES=false TAP_TO_CLICK="$click" \
      TEXT_CARET_NAV="$trc" TOP_ROW_CURSOR="$trc" TOP_ROW_ONLY=0 \
      PAD_SURFACE="$surface" FLIP_X="$flipx" \
      "$TOUCHPADD" >>"$TP_LOG" 2>&1 &
    TP_PID_CACHE=""
    if command -v usleep >/dev/null 2>&1; then usleep 80000; else sleep 0.08; fi
  fi
  if ! tp_up; then
    set_pad_inhibited 1 force
    echo "mode=mouse applied=tp_fail click=$click" > "$PAD_STATUS"
    chmod 666 "$PAD_STATUS" 2>/dev/null
    return 1
  fi
  set_pad_inhibited 0
  LAST_PAD_SURFACE=$surface
  LAST_SUB_FLIP=$flipx
  LAST_PAD_CLICK=$click
  LAST_TOP_ROW_CURSOR=$trc
  LAST_TOP_ROW_ONLY=0
  TP_TITANKEY_RESTARTS=0
  # IDC bind after trackpad can take seconds — never block mode edge.
  if [ "$LAST_IDC_KIND" != "ignore" ]; then
    ( set_touchpad_idc ignore ) &
  fi
  if [ "$follow" = "1" ]; then
    ( ensure_orient_rel ) &
  else
    orient_rel_up && kill_orient_rel
  fi
  or_st=off
  orient_rel_up && or_st=on
  echo "mode=mouse applied=running pid=`tp_pid` click=$click trc=$trc follow=$follow surface=$surface flipx=$flipx orient_rel=$or_st rot=`read_pad_rotation`" > "$PAD_STATUS"
  chmod 666 "$PAD_STATUS" 2>/dev/null
  return 0
}

# Text-caret top-row only (pad off): grab pad, KEY_LEFT/RIGHT via titan2-text-nav.
start_top_row_only() {
  trc=`read_pad_top_row_cursor`
  [ "$trc" = "1" ] || trc=1
  [ -x "$TOUCHPADD" ] || {
    echo "mode=off applied=top_row_only_no_binary" > "$PAD_STATUS"
    chmod 666 "$PAD_STATUS" 2>/dev/null
    return 1
  }
  set_touchpad_idc ignore
  set_pad_inhibited 0
  if tp_up; then
    if [ "${LAST_TOP_ROW_ONLY:-}" != "1" ] || [ "${LAST_TOP_ROW_CURSOR:-}" != "$trc" ]; then
      kill_touchpadd
    fi
  fi
  if ! tp_up; then
    : > "$TP_LOG" 2>/dev/null; chmod 666 "$TP_LOG" 2>/dev/null
    (
      LOGCAT_OUTPUT=true KEYBOARD_FEATURES=false TAP_TO_CLICK=0 \
        TEXT_CARET_NAV=1 TOP_ROW_CURSOR=1 TOP_ROW_ONLY=1 \
        "$TOUCHPADD" >>"$TP_LOG" 2>&1
    ) &
    i=0
    while [ $i -lt 5 ]; do
      tp_up && break
      _sleep_brief
      i=`expr $i + 1 2>/dev/null` || i=5
    done
  fi
  LAST_TOP_ROW_CURSOR=$trc
  LAST_CARET_KIND=only
  LAST_PAD_CLICK=0
  return 0
}

# Trackpad coexistence: no EVIOCGRAB so native ABS stays for Android pointer.
start_top_row_only_nograb() {
  trc=`read_pad_top_row_cursor`
  [ "$trc" = "1" ] || return 0
  [ -x "$TOUCHPADD" ] || return 1
  if tp_up; then
    if [ "${LAST_CARET_KIND:-}" = "nograb" ]; then
      return 0
    fi
    # Replace mouse-bundle process with caret-nograb
    kill_touchpadd
  fi
  : > "$TP_LOG" 2>/dev/null; chmod 666 "$TP_LOG" 2>/dev/null
  (
    LOGCAT_OUTPUT=true KEYBOARD_FEATURES=false TAP_TO_CLICK=0 \
      TEXT_CARET_NAV=1 TOP_ROW_CURSOR=1 TOP_ROW_ONLY=1 TOP_ROW_NOGRAB=1 \
      "$TOUCHPADD" >>"$TP_LOG" 2>&1
  ) &
  i=0
  while [ $i -lt 5 ]; do
    tp_up && break
    _sleep_brief
    i=`expr $i + 1 2>/dev/null` || i=5
  done
  LAST_TOP_ROW_CURSOR=$trc
  LAST_CARET_KIND=nograb
  return 0
}

CARET_STATUS=/data/local/tmp/titan2_caret_status
# 2.70: Text caret (blinking insert point) is COMPLETELY independent of pad mode.
# Plane: titan2_pad_top_row_cursor 0|1. Does not require mouse/trackpad/off dance.
apply_text_caret_nav() {
  trc=`read_pad_top_row_cursor`
  mode=`read_pad_mode`
  mkdir -p /data/local/tmp 2>/dev/null || true

  if [ "$trc" != "1" ]; then
    # Caret disabled — stop caret-only processes; mouse mode keeps its daemon.
    case "${LAST_CARET_KIND:-}" in
      only|nograb)
        kill_touchpadd
        LAST_CARET_KIND=off
        ;;
      mouse_bundle)
        LAST_CARET_KIND=off
        # Mouse process may still be up with TEXT_CARET_NAV=1; next start_mouse
        # restarts with plane value when pad apply runs.
        ;;
      *) LAST_CARET_KIND=off ;;
    esac
    echo "caret=off mode=$mode" >"$CARET_STATUS" 2>/dev/null || true
    chmod 666 "$CARET_STATUS" 2>/dev/null || true
    return 0
  fi

  # Caret ON — independent path for every pad mode
  case "$mode" in
    mouse)
      # Bundled in start_mouse (TEXT_CARET_NAV from plane). Ensure mouse daemon.
      if ! tp_up || [ "${LAST_CARET_KIND:-}" != "mouse_bundle" ]; then
        start_mouse
      fi
      LAST_CARET_KIND=mouse_bundle
      echo "caret=on kind=mouse_bundle mode=mouse pid=`tp_pid`" >"$CARET_STATUS" 2>/dev/null || true
      ;;
    trackpad)
      start_top_row_only_nograb
      LAST_CARET_KIND=nograb
      echo "caret=on kind=nograb mode=trackpad pid=`tp_pid`" >"$CARET_STATUS" 2>/dev/null || true
      ;;
    *)
      # pad off (or anything else): exclusive strip for caret only
      start_top_row_only
      LAST_CARET_KIND=only
      echo "caret=on kind=only mode=$mode pid=`tp_pid`" >"$CARET_STATUS" 2>/dev/null || true
      ;;
  esac
  chmod 666 "$CARET_STATUS" 2>/dev/null || true
  return 0
}

# TRACKPAD mode: native pad for Android (NO module) — pre-injection behavior
# Only rebinds idc / inhibit-cycles when kind actually changes (else cursor recenters).
# 2.70: do NOT kill caret-only/nograb touchpadd — text caret is independent.
start_native_trackpad() {
  # 2.148: never uninhibit native pad while typing (palm mid-word → taps/moves).
  if _is_cursor_pause_on || _keys_fresh_for_typing_lock; then
    kill_touchpadd
    set_pad_inhibited 1 force
    LAST_CURSOR_PAUSE=1
    echo "mode=trackpad typing_lock=1 applied=native_blocked" >"$PAD_STATUS" 2>/dev/null || true
    chmod 666 "$PAD_STATUS" 2>/dev/null || true
    return 0
  fi
  # 2.96: stamp status FIRST (mirror mouse) so UI never waits on idc/sysfs.
  follow=`read_pad_follow_orient`
  if [ "$follow" = "1" ]; then
    want_kind=native
    idc_tag=orient
  else
    want_kind=native_fixed
    idc_tag=fixed
  fi
  rebind=0
  [ "$LAST_IDC_KIND" != "$want_kind" ] && rebind=1
  echo "mode=trackpad applied=starting idc=$idc_tag follow=$follow rebind=$rebind" >"$PAD_STATUS" 2>/dev/null || true
  chmod 666 "$PAD_STATUS" 2>/dev/null || true
  # 2.78: always kill touchpadd — trackpad is native ABS sole owner (no dual REL).
  kill_touchpadd
  LAST_CARET_KIND=off
  if ! _input_unlocked_ok; then
    set_pad_inhibited 1
    echo "mode=trackpad applied=lockpark" >"$PAD_STATUS" 2>/dev/null || true
    return 0
  fi
  # 2.83/2.96: uninhibit FIRST so palm moves immediately; idc rebind async.
  set_pad_inhibited 0
  if [ "$rebind" = "1" ]; then
    ( set_touchpad_idc "$want_kind"
      set_pad_inhibited 1
      set_pad_inhibited 0
    ) &
  fi
  echo "mode=trackpad applied=native idc=$idc_tag follow=$follow rebind=$rebind" > "$PAD_STATUS"
  chmod 666 "$PAD_STATUS" 2>/dev/null
}

stop_pad() {
  # Shared driver with USB HID: if a live session needs the virtual mouse,
  # leave touchpadd (and orient-rel when follow is on) running for the bridge.
  # Only park native Android path so local cursor stays off.
  if hid_needs_mouse; then
    set_pad_inhibited 0
    follow=`read_pad_follow_orient`
    if [ "$follow" = "1" ]; then
      if tp_up; then
        ( ensure_orient_rel ) &
      fi
    else
      kill_orient_rel
    fi
    if tp_up; then
      echo "mode=off applied=hid_hold pid=`tp_pid` follow=$follow" > "$PAD_STATUS"
    else
      # HID service will start HID-owned touchpadd; do not race-start here
      echo "mode=off applied=hid_pending follow=$follow" > "$PAD_STATUS"
    fi
    chmod 666 "$PAD_STATUS" 2>/dev/null
    return 0
  fi
  # 2.83: kill + sysfs inhibit only — never idc remount on Off (was multi-sec).
  kill_touchpadd
  set_pad_inhibited 1
  echo "mode=off applied=off" > "$PAD_STATUS"
  chmod 666 "$PAD_STATUS" 2>/dev/null
}

apply_pad() {
  # 2.79: lock / pre-CE — never touchpadd or native pad as pointer for password.
  if ! _input_unlocked_ok; then
    _lockscreen_park_input
    LAST_PAD=lockpark
    return 0
  fi
  # 2.151/2.160: typing-watch owns lock while LIVE. Sticky typing_lock=1 with a
  # dead watch must not block mouse forever (user: mode=mouse but trackpad feel).
  if [ -f "$PAD_STATUS" ] && grep -q 'typing_lock=1' "$PAD_STATUS" 2>/dev/null; then
    if _typing_watch_live; then
      LAST_CURSOR_PAUSE=1
      return 0
    fi
    # Watch dead: re-check live conditions; else fall through to restore mouse.
    if _typing_should_park; then
      kill_touchpadd
      set_pad_inhibited 1 force
      LAST_CURSOR_PAUSE=1
      echo "mode=$(read_pad_mode) typing_lock=1 watch_dead_park" >"$PAD_STATUS" 2>/dev/null || true
      chmod 666 "$PAD_STATUS" 2>/dev/null || true
      _ensure_typing_watch 2>/dev/null || true
      return 0
    fi
    LAST_CURSOR_PAUSE=0
    echo "mode=$(read_pad_mode) typing_lock=0 heal_watch_dead" >"$PAD_STATUS" 2>/dev/null || true
    chmod 666 "$PAD_STATUS" 2>/dev/null || true
  fi
  # Soft park only when watch is NOT live (watch owns park/unlock when present).
  if ! _typing_watch_live; then
    if _typing_should_park; then
      kill_touchpadd
      set_pad_inhibited 1 force
      LAST_CURSOR_PAUSE=1
      echo "mode=$(read_pad_mode) typing_lock=1" >"$PAD_STATUS" 2>/dev/null || true
      chmod 666 "$PAD_STATUS" 2>/dev/null || true
      _ensure_typing_watch 2>/dev/null || true
      return 0
    fi
  fi
  LAST_CURSOR_PAUSE=0
  mode=`read_pad_mode`
  want_mode=$mode
  click=`read_pad_click`
  follow=`read_pad_follow_orient`
  trc=`read_pad_top_row_cursor`
  tro=`read_pad_top_row_only`
  case "$mode" in
    mouse)
      mode_changed=0
      click_changed=0
      follow_changed=0
      surface_changed=0
      trc_changed=0
      surface=`read_pad_surface`
      flipx=`read_sub_flip_x`
      [ "$LAST_PAD" != "mouse" ] && mode_changed=1
      [ "$LAST_CLICK" != "$click" ] && click_changed=1
      [ "$LAST_FOLLOW" != "$follow" ] && follow_changed=1
      [ "$surface" != "$LAST_PAD_SURFACE" ] && surface_changed=1
      [ "$flipx" != "$LAST_SUB_FLIP" ] && surface_changed=1
      [ "${LAST_TOP_ROW_CURSOR:-}" != "$trc" ] && trc_changed=1
      dead=0
      tp_up || dead=1
      # 2.80/2.137: restart on mode/dead/click/surface/trc — Creators energy edge
      if [ "$mode_changed" = "1" ] || [ "$click_changed" = "1" ] || [ "$surface_changed" = "1" ] \
          || [ "$trc_changed" = "1" ] || [ "$dead" = "1" ]; then
        if [ "$mode_changed" = "1" ]; then
          TP_TITANKEY_RESTARTS=0
        fi
        if tp_up; then
          kill_touchpadd
          TP_PID_CACHE=""
        fi
        start_mouse
      else
        # Soft path: instant — no idc, no orient sync.
        set_pad_inhibited 0
        if [ "$follow" = "1" ]; then
          orient_rel_up || ( ensure_orient_rel ) &
        else
          orient_rel_up && kill_orient_rel
        fi
        or_st=off
        orient_rel_up && or_st=on
        echo "mode=mouse applied=running pid=`tp_pid` click=$click trc=$trc follow=$follow surface=$surface flipx=$flipx orient_rel=$or_st rot=`read_pad_rotation`" > "$PAD_STATUS"
        chmod 666 "$PAD_STATUS" 2>/dev/null
      fi
      ;;
    trackpad)
      # 2.78/2.96: native ABS only — start_native_trackpad stamps status first
      # and async-idc; soft path never re-writes status after (was racing mouse).
      need=0
      [ "$LAST_PAD" != "trackpad" ] && need=1
      [ "$LAST_FOLLOW" != "$follow" ] && need=1
      if [ "$need" = "1" ]; then
        kill_orient_rel
        kill_touchpadd
        TP_PID_CACHE=""
        LAST_CARET_KIND=off
        start_native_trackpad
      else
        if tp_up; then
          kill_touchpadd
          TP_PID_CACHE=""
        fi
        set_pad_inhibited 0
        idc_tag=fixed
        [ "$follow" = "1" ] && idc_tag=orient
        echo "mode=trackpad applied=native idc=$idc_tag follow=$follow" > "$PAD_STATUS"
        chmod 666 "$PAD_STATUS" 2>/dev/null
      fi
      ;;
    *)
      # pad off: park all pad daemons (2.78: no caret keep)
      if hid_needs_mouse; then
        :
      else
        if [ "$LAST_PAD" != "off" ] || tp_up || orient_rel_up; then
          stop_pad
        fi
        if tp_up; then
          kill_touchpadd
          TP_PID_CACHE=""
        fi
        LAST_CARET_KIND=off
        echo "mode=off applied=off" > "$PAD_STATUS"
        chmod 666 "$PAD_STATUS" 2>/dev/null
      fi
      ;;
  esac
  # Controls / QS owns titan2_pad_mode. Never write it back — a slow
  # trackpad apply after Off→Trackpad→Mouse would clobber mouse to trackpad.
  now=`read_pad_mode`
  if [ "$now" != "$want_mode" ]; then
    log "apply stale want=$want_mode now=$now — do not LAST_PAD, caller reapplies"
    chmod 666 "$PAD_STATUS" 2>/dev/null || true
    return 2
  fi
  LAST_PAD=$now
  LAST_CLICK=$click
  LAST_FOLLOW=$follow
  write_if_changed "$ST/titan2_pad_follow_orient" "$follow"
  write_if_changed "$ST/titan2_pad_click" "$click"
  write_if_changed "$T2/titan2_pad_click" "$click"
  write_if_changed "$T2/titan2_pad_follow_orient" "$follow"
  chmod 666 "$PAD_STATUS" 2>/dev/null || true
}



# Persist LAST_* after any mutating command
_after() {
  _save_apply_last
}


boot_pad_safe() {
  mkdir -p "$T2" "$ST" 2>/dev/null || true
  # KEEP_DATA: drop leftover tip apply if it is not this ROM script.
  if [ -f "$ST/titan2-pad-apply.sh" ]; then
    _tv=`grep -m1 '^PAD_APPLY_VER=' "$ST/titan2-pad-apply.sh" 2>/dev/null`
    _sv=`grep -m1 '^PAD_APPLY_VER=' /system/bin/titan2-pad-apply.sh 2>/dev/null`
    [ "$_tv" = "$_sv" ] || rm -f "$ST/titan2-pad-apply.sh" 2>/dev/null || true
  fi
  _forget_persisted_input_lock
  cur=`read_pad_mode`
  case "$cur" in
    mouse|trackpad) ;;
    *)
      printf off >"$ST/titan2_pad_mode" 2>/dev/null || true
      printf off >"$T2/titan2_pad_mode" 2>/dev/null || true
      chmod 666 "$ST/titan2_pad_mode" "$T2/titan2_pad_mode" 2>/dev/null || true
      settings put global titan2_pad_mode off 2>/dev/null || true
      for _d in "$T2" "$ST"; do
        printf none >"$_d/titan2_input_surface" 2>/dev/null || true
        printf 1 >"$_d/titan2_hw_pad_inhibit" 2>/dev/null || true
        chmod 666 "$_d/titan2_input_surface" "$_d/titan2_hw_pad_inhibit" 2>/dev/null || true
      done
      settings put global titan2_input_surface none 2>/dev/null || true
      settings put global titan2_hw_pad_inhibit 1 2>/dev/null || true
      ;;
  esac
  for _d in "$T2" "$ST"; do
    printf 1 >"$_d/titan2_subtouch_inhibit" 2>/dev/null || true
    chmod 666 "$_d/titan2_subtouch_inhibit" 2>/dev/null || true
  done
  settings put global titan2_subtouch_inhibit 1 2>/dev/null || true
  # 2.213: never stomp titan2_pad_click here. Cool-plug / boot_pad_safe used to
  # printf 0 → plane stuck click=0 → mouse mode later TAP_TO_CLICK=0 (no taps)
  # while user is not typing. Click is user product pref (seed default 1);
  # REG-K safety is inhibit + mode=off, not permanent tap-to-click off.
  kill_touchpadd 2>/dev/null || true
  set_pad_inhibited 1
  set_touchpad_idc ignore
  settings put secure long_press_timeout 400 2>/dev/null || true
  echo "mode=off applied=boot_safe inhibit=1 surface=none subtouch=1 idc=ignore" >"$PAD_STATUS" 2>/dev/null || true
  chmod 666 "$PAD_STATUS" 2>/dev/null || true
  log "boot_pad_safe inhibit+surface+subtouch+ignore (REG-K; click plane preserved)"
  _save_apply_last
}


cmd=${1:-apply}
case "$cmd" in
  apply|pad|"")
    apply_pad
    _rc=$?
    _after
    # QS Off→Trackpad→Mouse: first apply saw trackpad; plane is now mouse.
    if [ "$_rc" = "2" ]; then
      apply_pad
      _after
    fi
    ;;
  boot_safe|boot)
    boot_pad_safe
    ;;
  mouse|start_mouse)
    start_mouse
    _after
    ;;
  trackpad|native)
    start_native_trackpad
    _after
    ;;
  stop|off)
    stop_pad
    _after
    ;;
  caret)
    apply_text_caret_nav
    _after
    ;;
  orient)
    case "${2:-ensure}" in
      kill) kill_orient_rel; _after ;;
      up) orient_rel_up; exit $? ;;
      ensure|*) ensure_orient_rel; _after ;;
    esac
    ;;
  version|-v|--version)
    echo "$PAD_APPLY_VER"
    ;;
  *)
    echo "usage: titan2-pad-apply.sh apply|mouse|trackpad|stop|caret|orient|boot_safe|version" >&2
    exit 2
    ;;
esac
