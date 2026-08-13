#!/system/bin/sh
# titan2-keycode-inject — OPTIMIZE Phase 3 peel: Controls→input inject drain
# SoT: docs/project/OPTIMIZE_SOURCE_PRODUCT.md
# Invoked by pad-agent: run | version
# Owns flock drain of titan2_keycode_inject (text/keyevent/keycombination).
export PATH=/system/bin:/system/xbin:/vendor/bin:$PATH
T2=/data/misc/titan2
ST=/data/local/tmp
KI_VER=2.179-keycode-inject-peel
KI_STATUS=$ST/titan2_keycode_inject_status
KI_PID=$ST/titan2_keycode_drain.pid

log() {
  mkdir -p "$ST" 2>/dev/null || true
  { echo "keycode-inject: $*" >>"$ST/titan2_pad_agent.log"; } 2>/dev/null || true
}


_ensure_keycode_inject_shells() {
  # 2.25: SELinux label before chmod — system_data_file left priv_app inject dead
  mkdir -p "$ST" "$T2" 2>/dev/null || true
  for f in \
    "$ST/titan2_keycode_inject" \
    "$T2/titan2_keycode_inject" \
    /data/local/tmp/titan2_keycode_wake \
    "$ST/titan2_keycode_wake" \
    "$T2/titan2_keycode_wake"
  do
    [ -f "$f" ] || { : > "$f" 2>/dev/null || true; }
    chmod 666 "$f" 2>/dev/null || true
    # tmp is already shell_data_file; force OS plane inject too
    case "$f" in
      "$T2"/*) chcon u:object_r:shell_data_file:s0 "$f" 2>/dev/null || true ;;
    esac
  done
}
# 1.96: non-empty inject queue or Controls wake byte → drain now (no sleep).
_keycode_inject_pending() {
  for f in \
    "$ST/titan2_keycode_inject" \
    "$T2/titan2_keycode_inject" \
    /data/user/0/com.titanus2.controls/files/titan2_keycode_inject \
    /data/data/com.titanus2.controls/files/titan2_keycode_inject
  do
    [ -s "$f" ] && return 0
  done
  for f in \
    /data/local/tmp/titan2_keycode_wake \
    "$ST/titan2_keycode_wake" \
    "$T2/titan2_keycode_wake"
  do
    [ -s "$f" ] && return 0
  done
  return 1
}
_clear_keycode_wake() {
  for f in \
    /data/local/tmp/titan2_keycode_wake \
    "$ST/titan2_keycode_wake" \
    "$T2/titan2_keycode_wake"
  do
    [ -f "$f" ] && { : > "$f" 2>/dev/null || true; }
  done
}
drain_keycode_inject() {
  # returns 0 if work done, 1 if empty
  # 2.20: flock so dual drain thrash cannot each input-text the same line
  # (Termux multi-glyph then dead kb). 1.43 path order unchanged.
  _inj_lock="$ST/titan2_keycode_inject.lock"
  (
    flock -n 9 || exit 1
    did=0
    line=""
    used=""
    for f in \
      "$ST/titan2_keycode_inject" \
      "$T2/titan2_keycode_inject" \
      /data/user/0/com.titanus2.controls/files/titan2_keycode_inject \
      /data/data/com.titanus2.controls/files/titan2_keycode_inject
    do
      [ -f "$f" ] || continue
      [ -s "$f" ] || continue
      line=`head -1 "$f" 2>/dev/null | tr -d '\r\n'`
      : > "$f" 2>/dev/null || true
      used="$f"
      [ -n "$line" ] || continue
      did=1
      break
    done
    if [ "$did" = "1" ]; then
      for f in \
        "$ST/titan2_keycode_inject" \
        "$T2/titan2_keycode_inject" \
        /data/user/0/com.titanus2.controls/files/titan2_keycode_inject \
        /data/data/com.titanus2.controls/files/titan2_keycode_inject
      do
        [ "$f" = "$used" ] && continue
        : > "$f" 2>/dev/null || true
      done
      _clear_keycode_wake
    fi
    [ "$did" = "1" ] || exit 1
    case "$line" in
      t\ *|T\ *)
        rest=${line#?}
        rest=${rest# }
        rest=`printf '%s' "$rest" | tr -d '\r\n'`
        # One BMP only — multi-char residual → multi-print
        rest=`printf '%s' "$rest" | cut -c1`
        case "$rest" in
          '') exit 1 ;;
        esac
        # timeout if present — hung input text left drain wedged / Termux stuck
        if command -v timeout >/dev/null 2>&1; then
          timeout 1 input text "$rest" 2>/dev/null \
            || timeout 1 /system/bin/input text "$rest" 2>/dev/null || true
        else
          input text "$rest" 2>/dev/null \
            || /system/bin/input text "$rest" 2>/dev/null || true
        fi
        ;;
      c\ *|C\ *)
        rest=${line#* }
        rest=`echo "$rest" | tr -s ' ' | sed 's/^[[:space:]]*//;s/[[:space:]]*$//'`
        case "$rest" in
          ''|*[!0-9\ ]*) exit 1 ;;
        esac
        ncodes=0
        for _c in $rest; do ncodes=`expr $ncodes + 1 2>/dev/null` || ncodes=2; done
        if [ "$ncodes" -le 1 ] 2>/dev/null; then
          input keyevent $rest 2>/dev/null \
            || /system/bin/input keyevent $rest 2>/dev/null || true
        else
          input keycombination $rest 2>/dev/null \
            || /system/bin/input keycombination $rest 2>/dev/null || true
        fi
        ;;
      *)
        case "$line" in ''|*[!0-9]*) exit 1 ;; esac
        input keyevent "$line" 2>/dev/null \
          || /system/bin/input keyevent "$line" 2>/dev/null || true
        ;;
    esac
    exit 0
  ) 9>"$_inj_lock"
  return $?
}

# 2.19: keycode inject drain — Termux/Moonlight phone Sym specials depend on this.
# Drain used to exit forever when lockdir was wiped under a still-live parent
# (cool park / thrash residual) → queue fills, exclusive HID still worked (bridge).


run_inject_drain() {
  echo "keycode-inject pid=$$ ver=$KI_VER" >"$KI_STATUS" 2>/dev/null || true
  chmod 666 "$KI_STATUS" 2>/dev/null || true
  echo $$ >"$KI_PID" 2>/dev/null || true
  chmod 666 "$KI_PID" 2>/dev/null || true
  _ensure_keycode_inject_shells
  log "start ver=$KI_VER"
  empty_streak=0
  fail_streak=0
  while true; do
    if _keycode_inject_pending; then
      if drain_keycode_inject; then
        empty_streak=0
        fail_streak=0
        _burst=0
        while [ "$_burst" -lt 8 ] 2>/dev/null && _keycode_inject_pending; do
          drain_keycode_inject || break
          _burst=`expr $_burst + 1 2>/dev/null` || _burst=8
        done
        if command -v usleep >/dev/null 2>&1; then usleep 8000; else sleep 0.01; fi
      else
        fail_streak=`expr $fail_streak + 1 2>/dev/null` || fail_streak=1
        if [ "$fail_streak" -ge 32 ] 2>/dev/null; then
          for f in \
            "$ST/titan2_keycode_inject" \
            "$T2/titan2_keycode_inject" \
            /data/user/0/com.titanus2.controls/files/titan2_keycode_inject \
            /data/data/com.titanus2.controls/files/titan2_keycode_inject
          do
            [ -f "$f" ] && { : > "$f" 2>/dev/null || true; }
          done
          _clear_keycode_wake
          fail_streak=0
        fi
        if command -v usleep >/dev/null 2>&1; then usleep 20000; else sleep 0.02; fi
      fi
    else
      fail_streak=0
      empty_streak=`expr $empty_streak + 1 2>/dev/null` || empty_streak=1
      if command -v inotifywait >/dev/null 2>&1; then
        inotifywait -qq -t 1 -e modify,close_write,create,moved_to \
          "$ST/titan2_keycode_inject" "$T2/titan2_keycode_inject" \
          /data/local/tmp/titan2_keycode_wake \
          "$ST/titan2_keycode_wake" 2>/dev/null || true
      else
        if [ $((empty_streak % 8)) -eq 1 ] 2>/dev/null || [ -z "${_drain_heat_c:-}" ]; then
          _drain_heat_c=0
          set -- $(cat /proc/loadavg 2>/dev/null)
          _dl=${1%%.*}
          case "$_dl" in ''|*[!0-9]*) _dl=0 ;; esac
          [ "$_dl" -ge 8 ] 2>/dev/null && _drain_heat_c=1
        fi
        if [ "${_drain_heat_c:-0}" = "1" ] && [ "$empty_streak" -gt 10 ] 2>/dev/null; then
          if command -v usleep >/dev/null 2>&1; then usleep 2000000; else sleep 2; fi
        elif [ "$empty_streak" -gt 200 ] 2>/dev/null; then
          if command -v usleep >/dev/null 2>&1; then usleep 500000; else sleep 0.5; fi
        elif [ "$empty_streak" -gt 80 ] 2>/dev/null; then
          if command -v usleep >/dev/null 2>&1; then usleep 250000; else sleep 0.25; fi
        elif [ "$empty_streak" -gt 40 ] 2>/dev/null; then
          if command -v usleep >/dev/null 2>&1; then usleep 100000; else sleep 0.1; fi
        elif [ "$empty_streak" -gt 20 ] 2>/dev/null; then
          if command -v usleep >/dev/null 2>&1; then usleep 32000; else sleep 0.03; fi
        elif [ "$empty_streak" -gt 5 ] 2>/dev/null; then
          if command -v usleep >/dev/null 2>&1; then usleep 20000; else sleep 0.02; fi
        else
          if command -v usleep >/dev/null 2>&1; then usleep 12000; else sleep 0.02; fi
        fi
      fi
    fi
  done
}

cmd="${1-run}"
case "$cmd" in
  run|start)
    run_inject_drain
    ;;
  version|-v|--version)
    echo "$KI_VER"
    ;;
  *)
    log "usage: titan2-keycode-inject.sh run|version"
    exit 1
    ;;
esac
exit 0
