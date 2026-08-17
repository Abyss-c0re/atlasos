#!/system/bin/sh
# titan2-keyled-write — OPTIMIZE Phase 3 peel: keyboard LED sink + apply policy
# SoT: docs/project/OPTIMIZE_SOURCE_PRODUCT.md
# Invoked by pad-agent:
#   write <level> [reason]
#   apply              — apply_led policy (idle / screen / HID)
#   notif              — apply_notif_pattern (returns 0 if drove LED)
#   version
export PATH=/system/bin:/system/xbin:/vendor/bin:$PATH
ST=/data/local/tmp
T2=/data/misc/titan2
LED_STATUS=$ST/titan2_led_status
LAST_FILE=$ST/titan2_keyled_last_written
ACTIVITY=$ST/titan2_key_activity
DEFAULT_LED=3
DEFAULT_TO=30
KEYLED_VER=2.198-evdev-activity

_read_line_file() {
  f="$1"
  [ -f "$f" ] || { echo ""; return 1; }
  v=""
  IFS= read -r v < "$f" || true
  case "$v" in *$'\r') v=${v%$'\r'} ;; esac
  echo "$v"
  return 0
}

now() { date +%s; }

last_act() {
  best=0
  for f in "$ACTIVITY" "$T2/titan2_key_activity"; do
    [ -f "$f" ] || continue
    v=`cat "$f" 2>/dev/null | tr -d '\r\n '`
    case "$v" in ''|*[!0-9]*) continue;; esac
    case "$v" in
      [0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]*)
        v=`awk -v n="$v" 'BEGIN{printf "%d\n", n/1000}' 2>/dev/null` || v=0
        ;;
    esac
    case "$v" in ''|*[!0-9]*) continue;; esac
    [ "$v" -gt "$best" ] 2>/dev/null && best=$v
  done
  echo $best
}

read_first() {
  name="$1"
  best_mt=-1
  best_v=""
  found=0
  for f in "$T2/$name" "$ST/$name"; do
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

# LED plane: 0 is valid — do not treat as clear.
read_led_plane() {
  name="$1"
  best_mt=-1
  best_v=""
  found=0
  for f in "$T2/$name" "$ST/$name"; do
    [ -f "$f" ] || continue
    v=`_read_line_file "$f"`
    v=`echo "$v" | tr -d '\r\n \t'`
    case "$v" in
      ''|null|NULL|-|clear|CLEAR) continue ;;
      *[!0-9]*) continue ;;
    esac
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

read_led_to() {
  v=`read_led_plane titan2_keyled_timeout`
  case "$v" in [0-9]|[0-9][0-9]|[0-9][0-9][0-9]) echo "$v"; return ;; esac
  if [ -f $T2/titan2_keyled_timeout_last ]; then
    v=`cat $T2/titan2_keyled_timeout_last 2>/dev/null | tr -d '\r\n \t'`
    case "$v" in [0-9]|[0-9][0-9]|[0-9][0-9][0-9]) echo "$v"; return ;; esac
  fi
  echo $DEFAULT_TO
}

read_led_want() {
  v=`read_led_plane titan2_keyled_brightness`
  case "$v" in [0-7]) echo "$v"; return ;; esac
  if [ -f $T2/titan2_keyled_last ]; then
    v=`cat $T2/titan2_keyled_last 2>/dev/null | tr -d '\r\n \t'`
    case "$v" in [0-7]) echo "$v"; return ;; esac
  fi
  echo $DEFAULT_LED
}

persist_led() {
  lvl="$1"
  [ -n "$lvl" ] || return
  echo "$lvl" > $T2/titan2_keyled_last 2>/dev/null || true
  chmod 666 $T2/titan2_keyled_last 2>/dev/null || true
  if [ ! -s $T2/titan2_keyled_brightness ]; then
    echo "$lvl" > $T2/titan2_keyled_brightness 2>/dev/null || true
    chmod 666 $T2/titan2_keyled_brightness 2>/dev/null || true
  fi
}

persist_timeout() {
  to="$1"
  [ -n "$to" ] || return
  echo "$to" > $T2/titan2_keyled_timeout_last 2>/dev/null || true
  chmod 666 $T2/titan2_keyled_timeout_last 2>/dev/null || true
  if [ ! -s $T2/titan2_keyled_timeout ]; then
    echo "$to" > $T2/titan2_keyled_timeout 2>/dev/null || true
    chmod 666 $T2/titan2_keyled_timeout 2>/dev/null || true
  fi
}

screen_is_on() {
  s=`getprop debug.tracing.screen_state 2>/dev/null | tr -d '\r\n \t'`
  case "$s" in
    2|6) return 0 ;;
    1|3|4) return 1 ;;
  esac
  for bl in /sys/class/leds/lcd-backlight/brightness \
            /sys/devices/platform/leds-mtk/leds/lcd-backlight/brightness \
            /sys/devices/platform/mtk-leds/leds/lcd-backlight/brightness \
            /sys/class/backlight/panel0-backlight/brightness \
            /sys/class/backlight/*/brightness; do
    [ -e "$bl" ] || continue
    bv=`cat "$bl" 2>/dev/null | tr -d '\r\n \t'`
    case "$bv" in
      ''|*[!0-9]*) continue ;;
      0) return 1 ;;
      *) return 0 ;;
    esac
  done
  return 0
}

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

read_notif_blink_en() {
  case "`read_first titan2_notif_blink_enable`" in 1|true|on|ON) echo 1;; *) echo 0;; esac
}
read_notif_blink() {
  case "`read_first titan2_notif_blink`" in 1|true|on|ON) echo 1;; *) echo 0;; esac
}
read_notif_preview_until() {
  v=`read_first titan2_notif_preview_until`
  case "$v" in ''|*[!0-9]*) echo 0;; *) echo "$v";; esac
}

write_led() {
  level="$1"; reason="${2:-}"; ok=0; rb=""; good=""
  case "$reason" in
    screen_off*|user_off|force|force_*|idle_timeout_*|hid_user_off|keyled_enforce) force=1 ;;
    *) force=0 ;;
  esac
  last_w=`cat "$LAST_FILE" 2>/dev/null | tr -d '\r\n \t'`
  if [ "$force" != "1" ] && [ -n "$last_w" ] && [ "$level" = "$last_w" ]; then
    echo "level=$level applied=1 reason=$reason skip=same" > "$LED_STATUS" 2>/dev/null
    chmod 666 "$LED_STATUS" 2>/dev/null
    return 0
  fi
  _kp=`cat /data/local/tmp/titan2_keyled_path 2>/dev/null | tr -d '\r\n \t'`
  for led in $_kp \
             /sys/devices/platform/keypad_led/keyled_brightness \
             /sys/bus/platform/devices/keypad_led/keyled_brightness \
             /sys/class/misc/keypad_led/keyled_brightness \
             /sys/devices/virtual/misc/keypad_led/keyled_brightness \
             /sys/class/leds/keypad_led/brightness \
             /sys/class/leds/keyboard_backlight/brightness; do
    [ -n "$led" ] || continue
    if printf '%s\n' "$level" >"$led" 2>/dev/null; then
      [ "$level" = "0" ] && printf '0\n' >"$led" 2>/dev/null
      rb=`cat "$led" 2>/dev/null | tr -d '\r\n \t'`
      if [ "$rb" = "$level" ]; then
        ok=1
        good=$led
        break
      fi
      printf '%s\n' "$level" >"$led" 2>/dev/null
      rb=`cat "$led" 2>/dev/null | tr -d '\r\n \t'`
      if [ "$rb" = "$level" ]; then
        ok=1
        good=$led
        break
      fi
    fi
  done
  printf '%s\n' "$level" >"$ST/titan2_keyled_hw_want" 2>/dev/null || true
  printf '%s\n' "$reason" >"$ST/titan2_keyled_hw_reason" 2>/dev/null || true
  chmod 666 "$ST/titan2_keyled_hw_want" "$ST/titan2_keyled_hw_reason" 2>/dev/null || true
  printf '%s\n' "$level" >"$LAST_FILE" 2>/dev/null || true
  chmod 666 "$LAST_FILE" 2>/dev/null || true
  echo "level=$level applied=$ok reason=$reason rb=${rb:-na} path=${good:-none}" > "$LED_STATUS" 2>/dev/null
  chmod 666 "$LED_STATUS" 2>/dev/null
}

# apply_led policy (from pad-agent 2.168)
apply_led() {
  want=`read_led_want`
  to=`read_led_to`
  case "$want" in ''|*[!0-9]*) want=$DEFAULT_LED;; esac
  [ "$want" -gt 7 ] 2>/dev/null && want=7
  case "$to" in ''|*[!0-9]*) to=$DEFAULT_TO;; esac

  persist_led "$want"
  persist_timeout "$to"

  last=`last_act`; case "$last" in ''|*[!0-9]*) last=0;; esac
  n=`now`; case "$n" in ''|*[!0-9]*) n=0;; esac
  age=$((n - last)) 2>/dev/null || age=9999
  case "$age" in ''|-*|*[!0-9]*) age=9999;; esac
  printf 'n=%s last=%s age=%s want=%s to=%s\n' "$n" "$last" "$age" "$want" "$to" >"$ST/titan2_led_debug" 2>/dev/null || true

  prev_until=`read_notif_preview_until`
  nsec=`now`
  case "$prev_until" in ''|*[!0-9]*) prev_until=0;; esac
  if [ "$prev_until" -gt 0 ] 2>/dev/null && [ "$nsec" -le "$prev_until" ] 2>/dev/null; then
    return 0
  fi
  _hid=0
  hid_session_on && _hid=1
  _scr=`getprop debug.tracing.screen_state 2>/dev/null | tr -d '\r\n \t'`
  _scr_on=0
  case "$_scr" in
    2|6) _scr_on=1 ;;
    1|3|4) _scr_on=0 ;;
    *) screen_is_on && _scr_on=1 || _scr_on=0 ;;
  esac
  if [ "$_hid" != "1" ] && [ "$_scr_on" != "1" ]; then
    case "$_scr" in
      1|3|4)
        if [ "`read_notif_blink_en`" = "1" ] && [ "`read_notif_blink`" = "1" ]; then
          return 0
        fi
        ;;
    esac
  fi

  if [ "$_scr_on" != "1" ]; then
    write_led 0 "screen_off_prop=${_scr:-empty}"
    for _cp in $T2/titan2_pad_cursor_pause $ST/titan2_pad_cursor_pause; do
      [ -f "$_cp" ] || continue
      v=`_read_line_file "$_cp" 2>/dev/null`
      case "$v" in 1|true|on|ON)
        echo 0 > "$_cp" 2>/dev/null || true
        chmod 666 "$_cp" 2>/dev/null || true
        ;;
      esac
    done
    return 0
  fi

  if [ "$_hid" = "1" ]; then
    if [ "$want" = "0" ]; then
      write_led 0 "hid_user_off"
      return 0
    fi
    write_led "$want" "hid_session"
    return 0
  fi

  if [ "$want" = "0" ]; then write_led 0 user_off; return 0; fi
  if [ "$to" = "0" ]; then write_led "$want" always_on; return 0; fi
  if [ "$age" -lt "$to" ] 2>/dev/null; then
    write_led "$want" "typing_${age}s/${to}s"
    return 0
  fi

  if [ "$age" -lt "$to" ] 2>/dev/null; then
    write_led "$want" "active_${age}s/${to}s"
  else
    write_led 0 "idle_timeout_${to}s"
  fi
  persist_led "$want"
  persist_timeout "$to"
  return 0
}

now_ms() {
  if [ -r /proc/uptime ]; then
    set -- $(cat /proc/uptime)
    sec=${1%.*}
    frac=${1#*.}
    case "$frac" in "$1") frac=0;; esac
    frac=$(printf '%s000' "$frac" | cut -c1-3)
    case "$frac" in ''|*[!0-9]*) frac=0;; esac
    sec=$(( sec % 1000000 ))
    echo $(( sec * 1000 + 10#$frac ))
    return
  fi
  s=$(date +%s 2>/dev/null)
  case "$s" in ''|*[!0-9]*) echo 0; return;; esac
  echo $(( (s % 1000000) * 1000 ))
}

read_notif_mode() {
  m=`read_first titan2_notif_mode`
  case "$m" in solid|blink|breathe) echo "$m";; *) echo blink;; esac
}
read_notif_period_ms() {
  v=`read_first titan2_notif_period_ms`
  case "$v" in ''|*[!0-9]*) echo 1000; return;; esac
  [ "$v" -lt 200 ] 2>/dev/null && v=200
  [ "$v" -gt 5000 ] 2>/dev/null && v=5000
  echo "$v"
}
read_notif_on_ms() {
  v=`read_first titan2_notif_on_ms`
  case "$v" in ''|*[!0-9]*) echo 500; return;; esac
  echo "$v"
}
read_notif_blink_lv() {
  v=`read_first titan2_notif_blink_level`
  case "$v" in [1-7]) echo "$v";; *) echo 3;; esac
}

notif_level_at() {
  mode="$1"; peak="$2"; period="$3"; onms="$4"; tms="$5"
  case "$peak" in ''|*[!0-9]*) peak=3;; esac
  [ "$peak" -gt 7 ] 2>/dev/null && peak=7
  case "$period" in ''|*[!0-9]*) period=1000;; esac
  [ "$period" -lt 50 ] 2>/dev/null && period=50
  case "$onms" in ''|*[!0-9]*) onms=0;; esac
  case "$tms" in ''|*[!0-9]*) tms=0;; esac
  t=$(( tms % period ))
  case "$mode" in
    solid)
      echo "$peak"; return
      ;;
    breathe)
      half=$(( period / 2 ))
      [ "$half" -lt 1 ] && half=1
      if [ "$t" -le "$half" ]; then
        echo $(( t * peak / half ))
      else
        echo $(( (period - t) * peak / half ))
      fi
      return
      ;;
    *)
      if [ "$onms" -le 0 ] 2>/dev/null; then
        echo "$peak"; return
      fi
      if [ "$onms" -ge "$period" ] 2>/dev/null; then
        echo "$peak"; return
      fi
      if [ "$t" -lt "$onms" ]; then
        echo "$peak"
      else
        echo 0
      fi
      return
      ;;
  esac
}

usb_data_connected() {
  st=`getprop sys.usb.state 2>/dev/null`
  case "$st" in
    CONFIGURED|*mtp*|*adb*|*ptp*|*rndis*|*midi*) return 0 ;;
  esac
  cfg=`getprop sys.usb.config 2>/dev/null`
  case "$cfg" in
    none|"") ;;
    *)
      case "$st" in CONFIGURED) return 0 ;; esac
      ;;
  esac
  return 1
}

# Returns 0 if pattern applied (caller should skip apply_led).
apply_notif_pattern() {
  prev_until=`read_notif_preview_until`
  nsec=`now`
  preview=0
  case "$prev_until" in ''|*[!0-9]*) prev_until=0;; esac
  if [ "$prev_until" -gt 0 ] 2>/dev/null && [ "$nsec" -le "$prev_until" ] 2>/dev/null; then
    preview=1
  fi
  if [ "$preview" != "1" ]; then
    last_k=`last_act`
    nnow=`now`
    case "$last_k" in ''|*[!0-9]*) last_k=0;; esac
    case "$nnow" in ''|*[!0-9]*) nnow=0;; esac
    kage=$((nnow - last_k))
    case "$kage" in ''|-*|*[!0-9]*) kage=9999;; esac
    to_k=`read_led_to`
    case "$to_k" in ''|*[!0-9]*) to_k=30;; esac
    [ "$to_k" = "0" ] && to_k=30
    if [ "$kage" -lt "$to_k" ] 2>/dev/null; then
      return 1
    fi
  fi
  notif_active=0
  if [ "`read_notif_blink_en`" = "1" ] && [ "`read_notif_blink`" = "1" ]; then
    notif_active=1
  fi
  if [ "$preview" != "1" ]; then
    if [ "$notif_active" != "1" ]; then
      return 1
    fi
    if screen_is_on; then
      return 1
    fi
    if usb_data_connected; then
      return 1
    fi
  fi
  mode=`read_notif_mode`
  peak=`read_notif_blink_lv`
  case "$peak" in [1-7]) ;; *) peak=5;; esac
  period=`read_notif_period_ms`
  onms=`read_notif_on_ms`
  case "$onms" in ''|*[!0-9]*) onms=0;; esac
  [ "$onms" -gt "$period" ] 2>/dev/null && onms=$period
  tms=`now_ms`
  lev=`notif_level_at "$mode" "$peak" "$period" "$onms" "$tms"`
  case "$lev" in ''|*[!0-9]*) lev=0;; esac
  [ "$lev" -gt 7 ] 2>/dev/null && lev=7
  tag="notif_${mode}"
  [ "$preview" = "1" ] && tag="preview_${mode}"
  write_led "$lev" "${tag}_p${period}_on${onms}_t${tms}"
  return 0
}


# ROM: bump key-activity from evdev (no grab). Does not own nav — key-watch
# must not getevent TitanKey while the screen is on. APK install must not
# be required to keep the keypad light alive.
_led_discover() {
  for want in TitanKey gpio_key-func ff_key; do
    for d in /sys/class/input/input*; do
      [ -e "$d/name" ] || continue
      n=`cat "$d/name" 2>/dev/null` || continue
      [ "$n" = "$want" ] || continue
      for e in "$d"/event*; do
        [ -e "$e" ] || continue
        echo "/dev/input/$(basename "$e")"
      done
    done
  done
}

_led_bump_act() {
  n=`date +%s 2>/dev/null` || return 0
  echo "$n" >"$ACTIVITY" 2>/dev/null || true
  echo "$n" >"$T2/titan2_key_activity" 2>/dev/null || true
}

_led_input_watch_one() {
  dev="$1"
  [ -e "$dev" ] || return 0
  getevent "$dev" 2>/dev/null | while read -r _line; do
    _led_bump_act
  done
}

_ensure_led_input_watch() {
  pidf=$ST/titan2_keyled_inwatch.pid
  op=`cat "$pidf" 2>/dev/null | tr -d '\r\n '`
  case "$op" in
    ''|*[!0-9]*) ;;
    *)
      if kill -0 "$op" 2>/dev/null; then
        if grep -a -F -q "titan2-keyled-write" "/proc/$op/cmdline" 2>/dev/null \
            || grep -a -F -q "getevent" "/proc/$op/cmdline" 2>/dev/null; then
          return 0
        fi
      fi
      ;;
  esac
  (
    while true; do
      for dev in `_led_discover`; do
        _led_input_watch_one "$dev" &
      done
      wait
      sleep 1
    done
  ) >/dev/null 2>&1 &
  echo $! >"$pidf" 2>/dev/null || true
  chmod 666 "$pidf" 2>/dev/null || true
}

# Dedicated notif LED engine (2.192 peel from pad-agent). Idle cheaply when feature off.
notif_engine_loop() {
  _ensure_led_input_watch
  while true; do
    if apply_notif_pattern; then
      if command -v usleep >/dev/null 2>&1; then
        usleep 40000
      else
        sleep 0.04 2>/dev/null || sleep 0.1
      fi
    else
      sleep 2
    fi
  done
}


# Persist notif wants + optional led_test pulse (2.197 peel from pad-agent cool belt).
# $2 = heat (1=skip test pulse sleep thrash)
plane_persist() {
  heat="${1:-0}"
  # light persist: only write T2 when value present (create-if missing done by ctrl-seed)
  _persist() {
    name="$1"; val="$2"
    [ -n "$val" ] || return 0
    cur=`cat "$T2/$name" 2>/dev/null | tr -d '\r\n '`
    if [ "$cur" = "$val" ]; then
      last=`cat "$T2/${name}_last" 2>/dev/null | tr -d '\r\n '`
      [ "$last" = "$val" ] && return 0
    fi
    echo "$val" >"$T2/$name" 2>/dev/null || true
    echo "$val" >"$T2/${name}_last" 2>/dev/null || true
    chmod 666 "$T2/$name" "$T2/${name}_last" 2>/dev/null || true
  }
  n_en=`read_notif_blink_en`
  n_lv=`read_notif_blink_lv`
  _persist titan2_notif_blink_enable "$n_en"
  _persist titan2_notif_blink_level "$n_lv"
  _persist titan2_notif_mode "`read_notif_mode`"
  _persist titan2_notif_period_ms "`read_notif_period_ms`"
  _persist titan2_notif_on_ms "`read_notif_on_ms`"
  [ "$heat" = "1" ] && return 0
  t=`read_first titan2_led_test`
  case "$t" in
    [0-7])
      for ii in 1 2 3; do
        write_led "$t" "test_notif_pulse"
        sleep 0.35
        write_led 0 "test_notif_off"
        sleep 0.15
      done
      echo "" >"$T2/titan2_led_test" 2>/dev/null || true
      echo "" >"$ST/titan2_led_test" 2>/dev/null || true
      ;;
  esac
  return 0
}

cmd=${1:-version}
case "$cmd" in
  write)
    write_led "$2" "$3"
    ;;
  apply)
    apply_led
    ;;
  notif)
    apply_notif_pattern
    exit $?
    ;;
  engine)
    echo "notif_engine pid=$$ ver=$KEYLED_VER" >"$ST/titan2_notif_engine_status" 2>/dev/null || true
    chmod 666 "$ST/titan2_notif_engine_status" 2>/dev/null || true
    notif_engine_loop
    ;;
  plane|plane_persist)
    plane_persist "${2:-0}"
    ;;
  version)
    echo "$KEYLED_VER"
    ;;
  *)
    echo "usage: titan2-keyled-write.sh write|apply|notif|engine|plane [heat]|version" >&2
    exit 2
    ;;
esac
