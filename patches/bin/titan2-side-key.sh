#!/system/bin/sh
# titan2-side-key — OPTIMIZE Phase 3 peel: side KEY_FIRE watcher daemon
# SoT: docs/project/OPTIMIZE_SOURCE_PRODUCT.md
# Invoked by pad-agent: run | version
# Owns gpio_key-func (grab) + ff_key (share) side remaps → titan2-key-fire.sh fire.
# Subdisplay/flashlight one-shots still via KEY_FIRE broadcast to Controls.
# Agent keeps screen-off key remaps + a11y helpers for non-side keys.
export PATH=/system/bin:/system/xbin:/vendor/bin:$PATH
T2=/data/misc/titan2
ST=/data/local/tmp
SIDE_VER=2.199-mouse-hold
SIDE_STATUS=$ST/titan2_side_key_status
SIDE_PID=$ST/titan2_side_key.pid

log() {
  mkdir -p "$ST" 2>/dev/null || true
  { echo "side-key: $*" >>"$ST/titan2_pad_agent.log"; } 2>/dev/null || true
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
  for _d in "$T2" "$ST"; do
    [ -f "$_d/$_n" ] || continue
    _v=`cat "$_d/$_n" 2>/dev/null | tr -d '\r\n \t'`
    [ -n "$_v" ] && { echo "$_v"; return 0; }
  done
  echo ""
}

read_km() { read_first "$1"; }

_key_fire_script() {
  for _c in \
      "$ST/titan2-key-fire.sh" \
      /data/local/tmp/titan2-key-fire.sh \
      /data/adb/modules/titan2_pad_agent/system/bin/titan2-key-fire.sh \
      /system/bin/titan2-key-fire.sh; do
    [ -f "$_c" ] && [ -r "$_c" ] && { echo "$_c"; return 0; }
  done
  return 1
}

fire_key_action_scan() {
  act="$1"; scan="$2"; down="${3-}"; meta="${4-}"
  case "$act" in ""|none|default) return 0 ;; esac
  _s=`_key_fire_script` || {
    log "key-fire missing act=$act"
    return 1
  }
  /system/bin/sh "$_s" fire "$act" "$scan" "$down" "$meta"
  return $?
}

_cmdline_has() {
  _ch_pid="$1"; _ch_pat="$2"
  [ -n "$_ch_pid" ] && [ -n "$_ch_pat" ] || return 1
  [ -r "/proc/$_ch_pid/cmdline" ] || return 1
  grep -a -F -q "$_ch_pat" "/proc/$_ch_pid/cmdline" 2>/dev/null
}

_kill_sh_cmdline_match() {
  _pat="$1"
  [ -n "$_pat" ] || return 0
  n=0
  for p in `pgrep -f "$_pat" 2>/dev/null`; do
    case "$p" in ''|*[!0-9]*) continue ;; esac
    [ "$p" = "$$" ] && continue
    comm=`cat "/proc/$p/comm" 2>/dev/null` || continue
    [ "$comm" = "sh" ] || continue
    _cmdline_has "$p" "$_pat" || continue
    kill -9 "$p" 2>/dev/null || true
    n=`expr $n + 1 2>/dev/null` || n=32
    [ "$n" -ge 32 ] 2>/dev/null && break
  done
  return 0
}


is_display_off() {
  ss=`getprop debug.tracing.screen_state 2>/dev/null`
  case "$ss" in 1|3|4|6) return 0 ;; esac
  # Some builds leave screen_state sticky; battery_stats screen 0 ≈ off
  bs=`getprop debug.tracing.battery_stats.screen 2>/dev/null`
  case "$bs" in 0) return 0 ;; esac
  return 1
}

# True if Key a11y is live on any plane (1 beats 0; Global counted).
# "0" is a valid dead mark — never treat via read_first clear-token path.
# 1.59/1.60: 2s cache, invalidated when plane mtime changes (toggle Key service off).
# 1.62: plane/Global live=1 also requires accessibility_enabled + TrackpadAccessService
# listed — force-stop/crash left a11y_live=1 and screen-on sides never KEY_FIRE (B1).
# 1.63: plane mtime age >20s with value 1 = dead (service listed but unbound/zombie).
# Controls 12.70 heartbeats every 8s while bound.
A11Y_LIVE_CACHE=""
A11Y_LIVE_CACHE_AT=0
A11Y_LIVE_CACHE_MT=0
A11Y_LIVE_STALE_S=20
a11y_is_live() {
  now_s=`date +%s 2>/dev/null` || now_s=0
  case "$now_s" in ''|*[!0-9]*) now_s=0 ;; esac
  mt=0
  for f in $ST/titan2_a11y_live $T2/titan2_a11y_live; do
    [ -f "$f" ] || continue
    t=`stat -c %Y "$f" 2>/dev/null` || t=0
    case "$t" in ''|*[!0-9]*) t=0 ;; esac
    [ "$t" -gt "$mt" ] 2>/dev/null && mt=$t
  done
  if [ -n "$A11Y_LIVE_CACHE" ] && [ "$A11Y_LIVE_CACHE_AT" -gt 0 ] 2>/dev/null \
      && [ "$mt" = "$A11Y_LIVE_CACHE_MT" ] 2>/dev/null \
      && [ $((now_s - A11Y_LIVE_CACHE_AT)) -lt 2 ] 2>/dev/null; then
    [ "$A11Y_LIVE_CACHE" = "1" ] && return 0
    return 1
  fi
  live=0
  for f in \
    $ST/titan2_a11y_live \
    $T2/titan2_a11y_live \
    /data/user/0/com.titanus2.controls/files/titan2_a11y_live \
    /data/data/com.titanus2.controls/files/titan2_a11y_live
  do
    [ -f "$f" ] || continue
    v=`_read_line_file "$f"`
    case "$v" in 1|true|on|ON|yes|YES) live=1; break ;; esac
  done
  if [ "$live" != "1" ]; then
    g=`settings get global titan2_a11y_live 2>/dev/null | tr -d '\r\n '`
    case "$g" in 1|true|on|ON|yes|YES) live=1 ;; esac
  fi
  if [ "$live" = "1" ]; then
    en=`settings get secure accessibility_enabled 2>/dev/null | tr -d '\r\n '`
    case "$en" in 1|true|on|ON) ;; *) live=0 ;; esac
  fi
  if [ "$live" = "1" ]; then
    svc=`settings get secure enabled_accessibility_services 2>/dev/null | tr -d '\r\n '`
    case "$svc" in
      *TrackpadAccessService*|*titanus2.controls/*titanus2.controls.TrackpadAccessService*|*titanus2.controls*)
        ;;
      *) live=0 ;;
    esac
  fi
  # 1.63: stale plane — process listed but unbound (no heartbeat for 20s+)
  if [ "$live" = "1" ] && [ "$mt" -gt 0 ] 2>/dev/null; then
    age=$((now_s - mt))
    if [ "$age" -gt "$A11Y_LIVE_STALE_S" ] 2>/dev/null; then
      live=0
    fi
  fi
  A11Y_LIVE_CACHE=$live
  A11Y_LIVE_CACHE_AT=$now_s
  A11Y_LIVE_CACHE_MT=$mt
  [ "$live" = "1" ] && return 0
  return 1
}
read_km() { read_first "$1"; }
# 00fa=250 side bottom, 00f9=249 side top (both left edge), …
# Accept 00fa / 0fa / fa (getevent vs side-watch printf width).
km_pref_for_code() {
  c=`echo "$1" | tr 'A-F' 'a-f' | sed 's/^0x//;s/^0*//'`
  [ -z "$c" ] && c=0
  case "$c" in
    fa) echo side_func ;;
    f9) echo side_func2 ;;
    9e) echo back ;;
    244) echo recents ;;
    b7|fb) echo fn ;;
    de|fd) echo sym ;;
    64) echo alt ;;
    *) echo "" ;;
  esac
}
# Parse getevent line → typ code val (handles with/without "path: " prefix)
_parse_ev() {
  line="$1"
  rest=${line#*: }
  [ -n "$rest" ] || rest=$line
  set -- $rest
  # skip optional timestamp token [n.n]
  case "$1" in \[*) shift ;; esac
  EV_TYP=$1; EV_CODE=$2; EV_VAL=$3
}

# Dedicated side-button watcher (always on).
# Side scans are unmapped in keylayout so Android never sees them as Home.
# gpio_key-func (bottom, scan 250): prefer titan2-side-watch with EVIOCGRAB so
# nothing else can re-map the node. ff_key (top, 249): getevent only — full
# grab would steal BACK/POWER on that cluster.
# One device per process — multi-arg getevent stalls on Titan.
# Side long-hold: real layout hold while pressed (not toggle-on-release).
# DOWN starts a 400ms timer → KEY_FIRE layout:*_hold + scan.
# UP: if hold armed → layout:end_hold; else short action.
# P0 B1: sides never system chrome (home/recents/back/camera). Heal plane → none.
# Keep in lockstep with KeyMapPrefs.isSystemChromeAction (Controls).
_side_is_chrome_act() {
  a=`echo "$1" | tr 'A-Z' 'a-z' | tr -d '\r\n '`
  case "$a" in
    home|recents|back|assist|power|power_dialog|camera|\
    app_switch|appswitch|app-switch|recent|overview|\
    keycode_camera|keycode:camera|key_camera|sys_camera|stock_camera|mtk_camera|button_camera|\
    keycode_home|keycode:home|key_home|sys_home|nav_home|gesture_home|\
    keycode_app_switch|keycode:app_switch|keycode_back|keycode:back|key_back|\
    keycode_assistant|keycode:assistant|keycode_power|keycode:power|key_power|\
    button_1|button_2|button_3|button_4|keycode_button_1|keycode_button_2|\
    keycode_menu|keycode:menu|key_menu|menu|sys_recents|sys_overview|\
    open_camera|start_camera|capture|keycode:3|keycode:4|keycode:187|keycode:26|keycode:27|\
    keycode_3|keycode_4|keycode_187|keycode_26|keycode_27)
      return 0 ;;
  esac
  return 1
}
_side_sanitize_act() {
  pref="$1"  # side_func | side_func2
  which="$2" # short | long | double
  act="$3"
  if _side_is_chrome_act "$act"; then
    # 1.69/1.82: never rebind factory layouts — chrome → none only
    act=none
    echo -n "$act" >"$ST/titan2_km_${pref}_${which}" 2>/dev/null || true
    echo -n "$act" >"$T2/titan2_km_${pref}_${which}" 2>/dev/null || true
    chmod 666 "$ST/titan2_km_${pref}_${which}" "$T2/titan2_km_${pref}_${which}" 2>/dev/null || true
  fi
  echo -n "$act"
}

# Side double window ms — match Controls SIDE_DOUBLE_MS (480).
SIDE_DOUBLE_MS=480

# $3 = grab (1 = EVIOCGRAB exclusive → always KEY_FIRE; 0 = getevent share)
# 1.54: non-grab + screen on → skip (a11y TrackpadAccessService owns sides).
# Non-grab + screen off → KEY_FIRE (a11y filter-key-events blind).
# Grab always KEY_FIRE (InputReader never sees the node).
_side_handle_key() {
  code="$1"
  val="$2"
  grab="${3-0}"
  pref=`km_pref_for_code "$code"`
  case "$pref" in side_func|side_func2) ;; *) return 0 ;; esac
  km_en=`read_km titan2_km_enabled`
  case "$km_en" in 0|false|off) return 0 ;; esac
  # 1.54/1.56/1.58: non-grab + screen on → a11y owns sides if any plane says live.
  # Do NOT use read_first: it treats "0" as clear and can ignore Global=1 when
  # tmp holds a newer 0 (lab: dual Specials after agent boot seed).
  if [ "$grab" != "1" ] && ! is_display_off; then
    if a11y_is_live; then
      return 0
    fi
    # a11y not live — fall through to KEY_FIRE (B1 sides still work)
  fi
  now_ms=`date +%s%3N 2>/dev/null || echo $(($(date +%s)*1000))`
  dfile=$ST/titan2_side_down_$code
  hfile=$ST/titan2_side_held_$code
  ufile=$ST/titan2_side_last_up_$code
  sfile=$ST/titan2_side_short_pend_$code
  scan_dec=`printf '%d' "0x$code" 2>/dev/null || echo 0`
  case "$code" in
    fa|0fa|00fa|FA|0FA|00FA) scan_dec=250 ;;
    f9|0f9|00f9|F9|0F9|00F9) scan_dec=249 ;;
  esac
  if [ "$val" = "1" ] || [ "$val" = "00000001" ]; then
    # 1.53 B1: second press within double window → cancel pending short, arm double
    last_up=`cat "$ufile" 2>/dev/null | tr -d '\r\n '`
    case "$last_up" in ''|*[!0-9]*) last_up=0 ;; esac
    dbl_gap=$((now_ms - last_up))
    if [ "$last_up" -gt 0 ] 2>/dev/null && [ "$dbl_gap" -lt "$SIDE_DOUBLE_MS" ] 2>/dev/null; then
      rm -f "$ufile" "$sfile" 2>/dev/null || true
      dbl_act=`read_km titan2_km_${pref}_double`
      dbl_act=`_side_sanitize_act "$pref" double "$dbl_act"`
      case "$dbl_act" in ""|none|default) ;; *)
        fire_key_action_scan "$dbl_act" "$scan_dec"
        # Swallow this press cycle (no long/short on this down/up)
        echo 1 > "$ST/titan2_side_dbl_arm_$code" 2>/dev/null || true
        echo "$now_ms" > "$dfile" 2>/dev/null || true
        return 0
        ;;
      esac
    fi
    echo "$now_ms" > "$dfile" 2>/dev/null || true
    rm -f "$hfile" "$ST/titan2_side_dbl_arm_$code" 2>/dev/null || true
    # Act-as-key mouse: follow physical hold (down now, up on release).
    short_now=`read_km titan2_km_${pref}_short`
    short_now=`_side_sanitize_act "$pref" short "$short_now"`
    case "$short_now" in
      mouse:left|mouse:right|mouse:middle)
        echo "$short_now" > "$ST/titan2_side_mouse_$code" 2>/dev/null || true
        fire_key_action_scan "$short_now" "$scan_dec" 1
        return 0
        ;;
    esac
    long_act=`read_km titan2_km_${pref}_long`
    long_act=`_side_sanitize_act "$pref" long "$long_act"`
    case "$long_act" in
      layout:specials_hold|layout:arrows_hold|layout:hold:*)
        (
          sleep 0.40
          [ -f "$dfile" ] || exit 0
          [ -f "$hfile" ] && exit 0
          [ -f "$ST/titan2_side_dbl_arm_$code" ] && exit 0
          echo 1 > "$hfile" 2>/dev/null || true
          fire_key_action_scan "$long_act" "$scan_dec"
        ) &
        ;;
    esac
  elif [ "$val" = "0" ] || [ "$val" = "00000000" ]; then
    dms=`cat "$dfile" 2>/dev/null | tr -d '\r\n '`
    rm -f "$dfile" 2>/dev/null || true
    if [ -f "$ST/titan2_side_mouse_$code" ]; then
      mact=`cat "$ST/titan2_side_mouse_$code" 2>/dev/null | tr -d '\r\n '`
      rm -f "$ST/titan2_side_mouse_$code" "$hfile" 2>/dev/null || true
      case "$mact" in
        mouse:left|mouse:right|mouse:middle)
          fire_key_action_scan "$mact" "$scan_dec" 0
          ;;
      esac
      return 0
    fi
    # Double already fired on second DOWN — ignore this UP
    if [ -f "$ST/titan2_side_dbl_arm_$code" ]; then
      rm -f "$ST/titan2_side_dbl_arm_$code" "$hfile" 2>/dev/null || true
      return 0
    fi
    if [ -f "$hfile" ]; then
      rm -f "$hfile" 2>/dev/null || true
      fire_key_action_scan "layout:end_hold" "$scan_dec"
      return 0
    fi
    case "$dms" in ''|*[!0-9]*) dms=$now_ms ;; esac
    held=$((now_ms - dms))
    if [ "$held" -ge 400 ] 2>/dev/null; then
      # Long non-hold action (toggle / app / host) fires on release
      act=`read_km titan2_km_${pref}_long`
      act=`_side_sanitize_act "$pref" long "$act"`
      case "$act" in
        layout:specials_hold|layout:arrows_hold|layout:hold:*)
          # Timer should have armed; if race, end cleanly
          fire_key_action_scan "layout:end_hold" "$scan_dec"
          return 0
          ;;
      esac
      fire_key_action_scan "$act" "$scan_dec"
      return 0
    fi
    # Short release: delay short if double is configured (match a11y SIDE_DOUBLE_MS)
    dbl_act=`read_km titan2_km_${pref}_double`
    dbl_act=`_side_sanitize_act "$pref" double "$dbl_act"`
    short_act=`read_km titan2_km_${pref}_short`
    short_act=`_side_sanitize_act "$pref" short "$short_act"`
    echo "$now_ms" > "$ufile" 2>/dev/null || true
    case "$dbl_act" in ""|none|default)
      fire_key_action_scan "$short_act" "$scan_dec"
      ;;
    *)
      # Pending short: fire after double window unless second press cancels
      echo "$now_ms" > "$sfile" 2>/dev/null || true
      (
        sleep 0.48
        pend=`cat "$sfile" 2>/dev/null | tr -d '\r\n '`
        [ "$pend" = "$now_ms" ] || exit 0
        rm -f "$sfile" 2>/dev/null || true
        fire_key_action_scan "$short_act" "$scan_dec"
      ) &
      ;;
    esac
  fi
}
_side_watch_one() {
  dev="$1"
  grab="$2"
  [ -e "$dev" ] || return 0
  # Prefer exclusive grab helper for single-key nodes (gpio bottom).
  if [ "$grab" = "1" ] && [ -x /system/bin/titan2-side-watch ]; then
    /system/bin/titan2-side-watch "$dev" 2>>"$ST/titan2_side_watch.err" | while read -r tag code val; do
      [ "$tag" = "KEY" ] || continue
      _side_handle_key "$code" "$val" 1
    done
    return 0
  fi
  if [ "$grab" = "1" ] && [ -x /data/local/tmp/titan2-side-watch ]; then
    /data/local/tmp/titan2-side-watch "$dev" 2>>"$ST/titan2_side_watch.err" | while read -r tag code val; do
      [ "$tag" = "KEY" ] || continue
      _side_handle_key "$code" "$val" 1
    done
    return 0
  fi
  # getevent share path: only KEY_FIRE when display off (1.54 single-owner)
  getevent "$dev" 2>/dev/null | while read -r line; do
    _parse_ev "$line"
    [ "$EV_TYP" = "0001" ] || continue
    _side_handle_key "$EV_CODE" "$EV_VAL" 0
  done
}

run_side_key() {
  echo "side-key pid=$$ parent=$PPID ver=$SIDE_VER" >"$SIDE_STATUS" 2>/dev/null || true
  chmod 666 "$SIDE_STATUS" 2>/dev/null || true
  echo $$ >"$SIDE_PID" 2>/dev/null || true
  chmod 666 "$SIDE_PID" 2>/dev/null || true
  log "start ver=$SIDE_VER"
  # Single owner: kill orphan side-watch from previous pad-agent instances
  # (multi-agent thrash left many EVIOCGRAB holders on gpio_key-func).
  # 2.44: pidof + sh cmdline — never ps -A (agent start under residual heat hang).
  for p in `pidof titan2-side-watch 2>/dev/null`; do
    kill -9 "$p" 2>/dev/null || true
  done
  _kill_sh_cmdline_match 'titan2-side-watch'
  sleep 0.15 2>/dev/null || true
  SIDE_LIST=""
  for d in /sys/class/input/input*; do
    [ -e "$d/name" ] || continue
    n=`cat "$d/name" 2>/dev/null` || continue
    case "$n" in
      gpio_key-func)
        for e in "$d"/event*; do
          [ -e "$e" ] || continue
          dev="/dev/input/$(basename "$e")"
          SIDE_LIST="$SIDE_LIST $dev(grab)"
          _side_watch_one "$dev" 1 &
        done
        ;;
      ff_key)
        for e in "$d"/event*; do
          [ -e "$e" ] || continue
          dev="/dev/input/$(basename "$e")"
          SIDE_LIST="$SIDE_LIST $dev"
          # no grab — cluster also has BACK/POWER/DPAD
          _side_watch_one "$dev" 0 &
        done
        ;;
    esac
  done
  if [ -z "$SIDE_LIST" ]; then
    _side_watch_one /dev/input/event3 1 &
    _side_watch_one /dev/input/event8 0 &
    SIDE_LIST="/dev/input/event3(grab) /dev/input/event8"
  fi
  echo "side_watcher_devs=$SIDE_LIST" >> /data/local/tmp/titan2_key_watcher.log 2>/dev/null || true
  wait
  log "side watchers exited"
}

cmd="${1-run}"
case "$cmd" in
  run|start)
    run_side_key
    ;;
  version|-v|--version)
    echo "$SIDE_VER"
    ;;
  *)
    log "unknown cmd=$cmd"
    exit 1
    ;;
esac
exit 0
