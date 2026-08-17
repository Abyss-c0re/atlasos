#!/system/bin/sh
# titan2-key-watch — single-instance Home/recents peel + LED
# 2.180: ONLY TitanKey; flock; short Home always unless hold >= LONG_MS
# Multi-instance shared titan2_recents_down made short presses look long → APP SWITCH.
export PATH=/system/bin:/system/xbin:/vendor/bin:$PATH
T2=/data/misc/titan2
ST=/data/local/tmp
KW_VER=2.197-screen-on-never-getevent
KW_STATUS=$ST/titan2_key_watch_status
KW_PID=$ST/titan2_key_watch.pid
KW_LOCK=$ST/titan2_key_watch.lock
KW_LOCKD=${KW_LOCK}.d
ACTIVITY=$ST/titan2_key_activity
NAV_LAST_MS=$ST/titan2_nav_last_ms
NAV_FIRE_LOCKD=$ST/titan2_nav_fire.lock.d
NAV_COOL_UNTIL_MS=$ST/titan2_nav_cool_until_ms
DEFAULT_LED=3
LONG_MS=700
# Product: screen-on Home/Recents = Titan Controls a11y (GLOBAL_ACTION_*).
# LAW: never keyevent 187 — Quickstep next-app / quick-switch.
# LAW: never am start RecentsActivity — that plants an invisible recents task
#      (flg 0x10800000). Overview then only “works” while that ghost exists.
#      REGRESSION. Fixed path is Controls GLOBAL_ACTION_RECENTS.
# Screen-off / a11y dead: KEY_FIRE to Controls. Never invent a dummy task.
RECENTS_DEBOUNCE_MS=900
HOME_DEBOUNCE_MS=400
RECENTS_COOL_MS=1100

log() {
  mkdir -p "$ST" 2>/dev/null || true
  { echo "key-watch: $*" >>"$ST/titan2_pad_agent.log"; } 2>/dev/null || true
  { echo "key-watch: $*" >>"$ST/titan2_key_watch.log"; } 2>/dev/null || true
  chmod 666 "$ST/titan2_key_watch.log" 2>/dev/null || true
}

# 2.182: always drop singleton lock so pad-agent ensure can revive Home (scan 580).
_kw_release_lock() {
  rmdir "$KW_LOCKD" 2>/dev/null || rm -rf "$KW_LOCKD" 2>/dev/null || true
  rm -f "$KW_LOCK" 2>/dev/null || true
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

read_km() { read_first "$1"; }

now() { date +%s; }

bump() {
  now > "$ACTIVITY" 2>/dev/null || true
  chmod 666 "$ACTIVITY" 2>/dev/null || true
  now > "$T2/titan2_key_activity" 2>/dev/null || true
}

_now_ms() {
  if [ -r /proc/uptime ]; then
    _u=`cut -d' ' -f1 /proc/uptime 2>/dev/null`
    _sec=${_u%.*}
    _frac=${_u#*.}
    case "$_sec" in ''|*[!0-9]*) _sec=0 ;; esac
    case "$_frac" in ''|*[!0-9]*) _frac=0 ;; esac
    _frac=`printf '%-3.3s' "$_frac" | tr ' ' '0'`
    echo $((_sec * 1000 + 10#$_frac))
    return 0
  fi
  echo $(($(date +%s 2>/dev/null || echo 0) * 1000))
}

# LED stubs if keyled missing — home peel still works
write_led() { :; }
read_led_want() { echo "$DEFAULT_LED"; }
# debug.tracing.screen_state: 2|6 on, 1|3|4 off. Unknown → on (never steal TitanKey).
_screen_on() {
  _scr=`getprop debug.tracing.screen_state 2>/dev/null | tr -d '\r\n \t'`
  case "$_scr" in
    2|6) return 0 ;;
    1|3|4) return 1 ;;
  esac
  return 0
}
screen_is_on() { _screen_on; }
is_display_off() { _screen_on && return 1; return 0; }

fire_key_action() { :; }

_parse_ev() {
  line="$1"
  rest=${line#*: }
  [ -n "$rest" ] || rest=$line
  set -- $rest
  case "$1" in \[*) shift ;; esac
  EV_TYP=$1; EV_CODE=$2; EV_VAL=$3
}

# Fire the exact Controls plane action (titan2_km_*). Do not rewrite to home/recents.
_fire_nav() {
  act=`echo "$1" | tr -d '\r\n ' | awk '{print $1}'`
  case "$act" in none|default|"") return 0 ;; esac
  now=`_now_ms`
  # After overview, skip the mapped Recents-key short (whatever Controls set).
  cool=`cat "$NAV_COOL_UNTIL_MS" 2>/dev/null | tr -d '\r\n \t'`
  skip=`cat "$ST/titan2_nav_cool_skip" 2>/dev/null | tr -d '\r\n \t'`
  case "$cool" in ''|*[!0-9]*) cool=0 ;; esac
  if [ -n "$skip" ] && [ "$act" = "$skip" ] && [ "$cool" -gt 0 ] 2>/dev/null; then
    if [ "$now" -lt "$cool" ] 2>/dev/null; then
      log "nav cool skip mapped-short=$skip until=${cool} now=${now}"
      return 0
    fi
  fi
  last=`cat "$NAV_LAST_MS" 2>/dev/null | tr -d '\r\n '`
  case "$last" in ''|*[!0-9]*) last=0 ;; esac
  delta=$((now - last))
  deb=$HOME_DEBOUNCE_MS
  [ "$act" = "recents" ] && deb=$RECENTS_DEBOUNCE_MS
  if [ "$delta" -ge 0 ] 2>/dev/null && [ "$delta" -lt "$deb" ] 2>/dev/null; then
    log "nav debounce skip act=$act d=${delta}ms deb=${deb}ms"
    return 0
  fi
  if ! mkdir "$NAV_FIRE_LOCKD" 2>/dev/null; then
    log "nav fire lock busy skip act=$act"
    return 0
  fi
  echo "$now" >"$NAV_LAST_MS" 2>/dev/null || true
  chmod 666 "$NAV_LAST_MS" 2>/dev/null || true
  if [ "$act" = "recents" ]; then
    cool_to=$((now + RECENTS_COOL_MS))
    echo "$cool_to" >"$NAV_COOL_UNTIL_MS" 2>/dev/null || true
    chmod 666 "$NAV_COOL_UNTIL_MS" 2>/dev/null || true
    short=`read_km titan2_km_recents_short`
    short=`echo "$short" | awk '{print $1}' | tr -d '\r\n'`
    echo "$short" >"$ST/titan2_nav_cool_skip" 2>/dev/null || true
    chmod 666 "$ST/titan2_nav_cool_skip" 2>/dev/null || true
  fi
  log "nav KEY_FIRE act=$act (Controls plane, never 187 / RecentsActivity)"
  /system/bin/am broadcast -a com.titanus2.controls.KEY_FIRE \
    --es action "$act" --ei scan 580 >/dev/null 2>&1 \
    || am broadcast -a com.titanus2.controls.KEY_FIRE \
         --es action "$act" --ei scan 580 >/dev/null 2>&1
  if [ "$act" = "recents" ]; then
    sleep 0.45
  else
    sleep 0.12
  fi
  rmdir "$NAV_FIRE_LOCKD" 2>/dev/null || rm -rf "$NAV_FIRE_LOCKD" 2>/dev/null || true
}

# Listed Controls a11y = live. File goes 0 on install onDestroy;
# getevent on TitanKey then starves InputReader (dead Back/Recents).
# TITAN_RECENTS_LAW: never getevent TitanKey while Key a11y is listed.
_a11y_live_fresh() {
  en=`settings get secure accessibility_enabled 2>/dev/null | tr -d '\r'`
  svc=`settings get secure enabled_accessibility_services 2>/dev/null | tr -d '\r'`
  case "$en" in 1|true|on)
    case "$svc" in
      *TrackpadAccessService*) return 0 ;;
    esac
    ;;
  esac
  v=`read_km titan2_a11y_live`
  case "$v" in 1|true|on) ;; *) return 1 ;; esac
  mt=0
  for f in "$ST/titan2_a11y_live" "$T2/titan2_a11y_live"; do
    [ -f "$f" ] || continue
    t=`stat -c %Y "$f" 2>/dev/null` || t=0
    case "$t" in ''|*[!0-9]*) t=0 ;; esac
    [ "$t" -gt "$mt" ] 2>/dev/null && mt=$t
  done
  [ "$mt" -gt 0 ] 2>/dev/null || return 1
  now_s=`date +%s 2>/dev/null` || now_s=0
  age=$((now_s - mt))
  [ "$age" -le 20 ] 2>/dev/null
}

# Only scan 0x244 = KEY_APPSELECT (580)
_recents_handle() {
  val="$1"
  # Controls a11y owns screen-on Home/Recents. Dual fire closes overview / eats Home.
  if _a11y_live_fresh; then
    return 0
  fi
  km_en=`read_km titan2_km_enabled`
  case "$km_en" in 0|false|off) return 0 ;; esac
  now_ms=`_now_ms`
  dfile=$ST/titan2_recents_down
  if [ "$val" = "1" ] || [ "$val" = "00000001" ]; then
    echo "$now_ms" > "$dfile" 2>/dev/null || true
    chmod 666 "$dfile" 2>/dev/null || true
    return 0
  fi
  if [ "$val" = "0" ] || [ "$val" = "00000000" ]; then
    dms=`cat "$dfile" 2>/dev/null | tr -d '\r\n '`
    rm -f "$dfile" 2>/dev/null || true
    case "$dms" in ''|*[!0-9]*)
      log "UP without DOWN — ignore"
      return 0
      ;;
    esac
    held=$((now_ms - dms))
    case "$held" in ''|-*|*[!0-9]*) held=0 ;; esac
    # Cap absurd holds (stale multi-instance timestamps)
    if [ "$held" -gt 5000 ] 2>/dev/null; then
      log "held=${held}ms absurd → treat as short home"
      held=0
    fi
    if [ "$held" -ge "$LONG_MS" ] 2>/dev/null; then
      act=`read_km titan2_km_recents_long`
    else
      act=`read_km titan2_km_recents_short`
    fi
    act=`echo "$act" | awk '{print $1}' | tr -d '\r\n'`
    # Empty/default = Controls did not publish; do not invent home/recents.
    log "held=${held}ms → $act"
    _fire_nav "$act"
  fi
}

_handle_ev_line() {
  line="$1"
  _parse_ev "$line"
  [ "$EV_TYP" = "0001" ] || return 0
  bump
  # 0244 = KEY_APPSELECT (linux 580)
  case "$EV_CODE" in
    0244|244)
      _recents_handle "$EV_VAL"
      ;;
  esac
}

discover_titankey() {
  for d in /sys/class/input/input*; do
    [ -e "$d/name" ] || continue
    n=`cat "$d/name" 2>/dev/null` || continue
    [ "$n" = "TitanKey" ] || continue
    for e in "$d"/event*; do
      [ -e "$e" ] || continue
      echo "/dev/input/$(basename "$e")"
      return 0
    done
  done
  echo "/dev/input/event7"
}

# Kill only the previous singleton via pidfile. NEVER walk all of /proc —
# that hangs under load (lab: dual starts die mid-scan; Home/Recents dead).
# Dual instances: mkdir lock.d is exclusive; second start exits lock-busy.
_kill_others() {
  my=$$
  op=`cat "$KW_PID" 2>/dev/null | tr -d '\r\n '`
  case "$op" in
    ''|"$my"|*[!0-9]*) ;;
    *)
      if kill -0 "$op" 2>/dev/null && _cmdline_match_kw "$op"; then
        kill -9 "$op" 2>/dev/null || true
        log "killed peer key-watch pid=$op"
        sleep 0.15
      fi
      ;;
  esac
  rm -f "$KW_PID" 2>/dev/null || true
  rm -rf "$NAV_FIRE_LOCKD" 2>/dev/null || true
}

run_key_watch() {
  mkdir -p "$ST" 2>/dev/null || true
  # 2.182: kill peers first, then take lock hard. Stale lock.d after heat/OOM
  # used to make every ensure spawn exit "lock busy" → Home dead forever.
  _kill_others
  op=`cat "$KW_PID" 2>/dev/null | tr -d '\r\n '`
  if [ -n "$op" ] && [ "$op" != "$$" ] && kill -0 "$op" 2>/dev/null; then
    if _cmdline_match_kw "$op"; then
      log "already running pid=$op — exit"
      exit 0
    fi
  fi
  _kw_release_lock
  if ! mkdir "$KW_LOCKD" 2>/dev/null; then
    # last resort: force drop + retry once
    _kw_release_lock
    sleep 0.05
    if ! mkdir "$KW_LOCKD" 2>/dev/null; then
      log "lock busy after force — exit"
      exit 1
    fi
  fi
  trap '_kw_release_lock; rm -f "$KW_PID" 2>/dev/null' EXIT HUP INT TERM
  # also try flock if it works (best-effort; mkdir already held)
  exec 9>"$KW_LOCK" 2>/dev/null || true
  if command -v flock >/dev/null 2>&1; then
    flock -n 9 2>/dev/null || true
  fi
  echo "key-watch pid=$$ parent=$PPID ver=$KW_VER" >"$KW_STATUS"
  chmod 666 "$KW_STATUS" 2>/dev/null || true
  echo $$ >"$KW_PID"
  chmod 666 "$KW_PID" 2>/dev/null || true
  rm -f $ST/titan2_recents_down
  DEV=`discover_titankey`
  log "start ver=$KW_VER dev=$DEV LONG_MS=$LONG_MS"
  while true; do
    # ROM: screen-on TitanKey is Controls a11y only. getevent starves
    # InputReader (dead Back/Recents). adb install must not change that.
    if _screen_on; then
      log "screen on — yield TitanKey (no getevent)"
      sleep 2
      continue
    fi
    if _a11y_live_fresh; then
      log "a11y live — yield TitanKey (no getevent)"
      sleep 2
      continue
    fi
    [ -e "$DEV" ] || { sleep 1; DEV=`discover_titankey`; continue; }
    log "screen off — KEY_FIRE getevent $DEV"
    getevent "$DEV" 2>/dev/null | while read -r line; do
      if _screen_on || _a11y_live_fresh; then
        log "screen/a11y live — drop getevent"
        break
      fi
      _handle_ev_line "$line"
    done
    log "getevent exited — reopen"
    sleep 0.3
    DEV=`discover_titankey`
  done
}

_cmdline_match_kw() {
  _p="$1"
  [ -r "/proc/$_p/cmdline" ] || return 1
  grep -a -F -q "titan2-key-watch" "/proc/$_p/cmdline" 2>/dev/null
}

cmd="${1-run}"
case "$cmd" in
  run|start) run_key_watch ;;
  version|-v|--version) echo "$KW_VER" ;;
  *) log "unknown cmd=$cmd"; exit 1 ;;
esac
exit 0
