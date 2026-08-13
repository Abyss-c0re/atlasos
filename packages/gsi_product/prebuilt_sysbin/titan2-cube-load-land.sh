#!/system/bin/sh
# titan2-cube-load-land — OPTIMIZE Phase 3 peel: cube-load park tip land
# SoT: docs/project/OPTIMIZE_SOURCE_PRODUCT.md · Phase 5 tip theater shrink
# Invoked by pad-agent:
#   kick   — cooldown + bg land (kernel_cube / sensor_privacy / usb_hid tips)
#   land   — run land once (sync; used by tests)
#   version
export PATH=/system/bin:/system/xbin:/vendor/bin:$PATH
T2=/data/misc/titan2
ST=/data/local/tmp
CUBE_LAND_VER=2.184-cube-load-land-peel

log() {
  mkdir -p "$ST" 2>/dev/null || true
  { echo "cube-load-land: $*" >>"$ST/titan2_pad_agent.log"; } 2>/dev/null || true
}

# Cached NUL-c-NUL token (set once; used by _is_real_svc_pid).
_CMD_PROBE_C=
_pgrep_has() {
  # $1=fixed cmdline substr — true if any process matches.
  [ -n "$1" ] || return 1
  pgrep -f "$1" >/dev/null 2>&1
}
# 2.125/2.127: true if PID is a real service shell (not adb/probe `sh -c …mark…`).
# Real: `/system/bin/sh /data/local/tmp/titan2-….sh` or Magisk service path.
# Probe: `sh -c '…pgrep…titan2-…'` embeds mark in -c string → false dual.
# 2.127: NEVER tr '\0' (2.108 SoT; 2.125 tr reopened lab heat residual).
_is_real_svc_pid() {
  _rp="$1"
  _rm="$2"
  [ -n "$_rp" ] && [ -n "$_rm" ] || return 1
  case "$_rp" in ''|*[!0-9]*) return 1 ;; esac
  [ -d "/proc/$_rp" ] || return 1
  [ -r "/proc/$_rp/cmdline" ] || return 1
  # Reject interactive/probe shells (adb shell -c, run-as wrappers).
  # Probe argv is NUL-framed standalone -c; real tip is sh /path/script.sh.
  [ -n "$_CMD_PROBE_C" ] || _CMD_PROBE_C=$(printf '\0-c\0')
  if grep -a -F -q "$_CMD_PROBE_C" "/proc/$_rp/cmdline" 2>/dev/null; then
    return 1
  fi
  # Space-joined residual (rare host dumps / non-NUL cmdline).
  if grep -a -F -q 'sh -c' "/proc/$_rp/cmdline" 2>/dev/null; then
    return 1
  fi
  grep -a -F -q "$_rm" "/proc/$_rp/cmdline" 2>/dev/null || return 1
  return 0
}
# Emit real service PIDs for mark (pgrep then filter). Never full /proc walk.
_real_svc_pids() {
  _rm="$1"
  [ -n "$_rm" ] || return 0
  for _rp in `pgrep -f "$_rm" 2>/dev/null`; do
    case "$_rp" in ''|*[!0-9]*) continue ;; esac
    _is_real_svc_pid "$_rp" "$_rm" || continue
    echo "$_rp"
  done
}
_pgrep_real_has() {
  [ -n "$1" ] || return 1
  for _rp in `_real_svc_pids "$1"`; do
    return 0
  done
  return 1
}
_tip_has_cube_load_park() {
  # $1=path — true if tip/system carries any cube-load-park family marker.
  [ -n "$1" ] && [ -r "$1" ] || return 1
  grep -a -F -q 'cube-load-park' "$1" 2>/dev/null && return 0
  grep -a -F -q 'cube-load-deep-park' "$1" 2>/dev/null && return 0
  grep -a -F -q 'cube-load-deep-zero' "$1" 2>/dev/null && return 0
  grep -a -F -q '1.9-cube-load-steady-on' "$1" 2>/dev/null && return 0
  grep -a -F -q '1.8-single-root' "$1" 2>/dev/null && return 0
  return 1
}
# Stamp tip-ver file for leave-alive / reexec (all three services).
_stamp_cube_load_tip_ver() {
  _sn="$1"
  _stip="$2"
  _svf="$3"
  [ -n "$_svf" ] || return 0
  case "$_sn" in
    kernel_cube)
      if grep -a -F -q '1.6-cube-load-deep-zero' "$_stip" 2>/dev/null; then
        echo "1.6-cube-load-deep-zero" > "$_svf" 2>/dev/null || true
      elif grep -a -F -q 'deep-zero' "$_stip" 2>/dev/null; then
        echo "1.5-cube-load-deep-zero" > "$_svf" 2>/dev/null || true
      else
        echo "cube-load-deep-park" > "$_svf" 2>/dev/null || true
      fi
      ;;
    sensor_privacy)
      if grep -a -F -q '1.9-cube-load-steady-on' "$_stip" 2>/dev/null; then
        echo "1.9-cube-load-steady-on" > "$_svf" 2>/dev/null || true
      elif grep -a -F -q '1.8-single-root' "$_stip" 2>/dev/null; then
        echo "1.8-single-root" > "$_svf" 2>/dev/null || true
      elif grep -a -F -q '1.7-cube-load-deep-park' "$_stip" 2>/dev/null; then
        echo "1.7-cube-load-deep-park" > "$_svf" 2>/dev/null || true
      else
        echo "1.6-cube-load-park" > "$_svf" 2>/dev/null || true
      fi
      ;;
    usb_hid)
      echo "0.16.18-cube-load-park" > "$_svf" 2>/dev/null || true
      ;;
    *)
      echo "cube-load-park" > "$_svf" 2>/dev/null || true
      ;;
  esac
  chmod 666 "$_svf" 2>/dev/null || true
}
# 2.124/2.125: prune dual sensor-privacy belts to n=1 (real svc only). Keep
# pidfile PID if real-live, else first real pgrep. Never kill probe -c shells.
_prune_sensor_privacy_duals() {
  _keep=`cat "$ST/titan2_sp.pid" 2>/dev/null | tr -d '\r\n '`
  case "$_keep" in ''|*[!0-9]*) _keep="" ;; esac
  if [ -n "$_keep" ] && _is_real_svc_pid "$_keep" titan2-sensor-privacy; then
    :
  elif [ -n "$_keep" ] && _is_real_svc_pid "$_keep" titan2_sensor_privacy/service; then
    :
  else
    _keep=""
    for _sp in `_real_svc_pids titan2-sensor-privacy` \
               `_real_svc_pids titan2_sensor_privacy/service`; do
      case "$_sp" in ''|*[!0-9]*) continue ;; esac
      _keep="$_sp"
      break
    done
  fi
  _nk=0
  for _sp in `_real_svc_pids titan2-sensor-privacy` \
             `_real_svc_pids titan2_sensor_privacy/service`; do
    case "$_sp" in ''|*[!0-9]*) continue ;; esac
    [ -n "$_keep" ] && [ "$_sp" = "$_keep" ] && continue
    kill -9 "$_sp" 2>/dev/null || true
    _nk=`expr $_nk + 1 2>/dev/null` || _nk=8
    [ "$_nk" -ge 12 ] 2>/dev/null && break
  done
  if [ -n "$_keep" ]; then
    echo "$_keep" > "$ST/titan2_sp.pid" 2>/dev/null || true
    chmod 666 "$ST/titan2_sp.pid" 2>/dev/null || true
  fi
  [ "$_nk" -gt 0 ] 2>/dev/null && \
    echo "prune sensor_privacy duals killed=$_nk keep=${_keep:-none} ts=`date +%s`" \
      >> "$ST/titan2_cube_load_tip_land.log" 2>/dev/null || true
  return 0
}
# 2.125: prune dual real PIDs for any tip-land service (keep first / pidfile).
_prune_svc_duals() {
  # $1=name $2=primary mark $3=optional second mark $4=optional pidfile
  _pn="$1"
  _pm="$2"
  _pm2="$3"
  _ppf="$4"
  _keep=""
  if [ -n "$_ppf" ] && [ -f "$_ppf" ]; then
    _keep=`cat "$_ppf" 2>/dev/null | tr -d '\r\n '`
    case "$_keep" in ''|*[!0-9]*) _keep="" ;; esac
    if [ -n "$_keep" ] && ! _is_real_svc_pid "$_keep" "$_pm"; then
      if [ -z "$_pm2" ] || ! _is_real_svc_pid "$_keep" "$_pm2"; then
        _keep=""
      fi
    fi
  fi
  if [ -z "$_keep" ]; then
    for _sp in `_real_svc_pids "$_pm"`; do
      _keep="$_sp"
      break
    done
  fi
  if [ -z "$_keep" ] && [ -n "$_pm2" ]; then
    for _sp in `_real_svc_pids "$_pm2"`; do
      _keep="$_sp"
      break
    done
  fi
  _nk=0
  for _sp in `_real_svc_pids "$_pm"`; do
    [ -n "$_keep" ] && [ "$_sp" = "$_keep" ] && continue
    kill -9 "$_sp" 2>/dev/null || true
    _nk=`expr $_nk + 1 2>/dev/null` || _nk=8
  done
  if [ -n "$_pm2" ]; then
    for _sp in `_real_svc_pids "$_pm2"`; do
      [ -n "$_keep" ] && [ "$_sp" = "$_keep" ] && continue
      kill -9 "$_sp" 2>/dev/null || true
      _nk=`expr $_nk + 1 2>/dev/null` || _nk=8
    done
  fi
  if [ -n "$_keep" ] && [ -n "$_ppf" ]; then
    echo "$_keep" > "$_ppf" 2>/dev/null || true
    chmod 666 "$_ppf" 2>/dev/null || true
  fi
  [ "$_nk" -gt 0 ] 2>/dev/null && \
    echo "prune $_pn duals killed=$_nk keep=${_keep:-none} ts=`date +%s`" \
      >> "$ST/titan2_cube_load_tip_land.log" 2>/dev/null || true
  return 0
}
_land_cube_load_park_tip() {
  # $1=name $2=tip $3=init svc $4=system path (file + cmdline mark)
  # 2.125 leave-alive (rootless heat):
  #  - real live tip/system + tip file park → stamp + prune duals only (never reexec)
  #  - hybrid system deep-park live alone → leave
  #  - n_real=0 + tip file park → start tip once (stamp all)
  # Never kill a sole healthy park tip because stamp token lagged.
  _ln="$1"
  _lt="$2"
  _ls="$3"
  _lm="$4"
  _FORCE_KCUBE_KEYLED=0
  [ -n "$_lt" ] && [ -x "$_lt" ] || return 0
  _tip_has_cube_load_park "$_lt" || return 0

  case "$_ln" in
    kernel_cube)
      _verf="$ST/titan2_kernel_cube_tip_ver"
      _mark_tip="titan2-kernel-cube"
      _mark_sys="/system/bin/titan2-kernel-cube"
      # 2.132: force tip keyled-enforce over live system 1.6 (keyled stuck at 3)
      # 2.138: also force 1.8-keyled-follow-want over 1.7 (dual-writer blink).
      if grep -a -F -q '1.8-keyled-follow-want' "$_lt" 2>/dev/null \
          || grep -a -F -q '1.7-keyled-enforce' "$_lt" 2>/dev/null; then
        _kcv=`cat "$_verf" 2>/dev/null | tr -d '\r\n '`
        case "$_kcv" in
          *1.8-keyled-follow-want*) ;;
          *)
            # Clear hybrid leave-alive so tip re-exec runs below.
            _FORCE_KCUBE_KEYLED=1
            echo "upgrade kernel_cube tip keyled-follow from=${_kcv:-none} ts=`date +%s`" \
              >> "$ST/titan2_cube_load_tip_land.log" 2>/dev/null || true
            ;;
        esac
      fi
      ;;
    sensor_privacy)
      _verf="$ST/titan2_sensor_privacy_tip_ver"
      _mark_tip="titan2-sensor-privacy"
      _mark_sys="/system/bin/titan2-sensor-privacy"
      ;;
    usb_hid)
      _verf="$ST/titan2_usb_hid_tip_ver"
      _mark_tip="titan2-usb-hid-service"
      _mark_sys="titan2_usb_hid/service"
      ;;
    *)
      _verf="$ST/titan2_${_ln}_tip_ver"
      _mark_tip="$_lt"
      _mark_sys="$_lm"
      ;;
  esac

  _tip_live=0
  _pgrep_real_has "$_lt" && _tip_live=1
  [ "$_tip_live" = "0" ] && _pgrep_real_has "$_mark_tip" && _tip_live=1
  _sys_deep=0
  if [ -n "$_lm" ] && [ -r "$_lm" ] \
      && { grep -a -F -q 'cube-load-deep-zero' "$_lm" 2>/dev/null \
        || grep -a -F -q 'cube-load-deep-park' "$_lm" 2>/dev/null \
        || grep -a -F -q '1.9-cube-load-steady-on' "$_lm" 2>/dev/null \
        || grep -a -F -q '1.8-single-root' "$_lm" 2>/dev/null \
        || grep -a -F -q 'cube-load-park' "$_lm" 2>/dev/null; }; then
    _sys_deep=1
  fi
  _sys_live=0
  [ -n "$_lm" ] && _pgrep_real_has "$_lm" && _sys_live=1
  [ "$_sys_live" = "0" ] && [ -n "$_mark_sys" ] && _pgrep_real_has "$_mark_sys" && _sys_live=1

  # Hybrid product: system park-family live + no tip → never tip thrash.
  # Exception: kernel_cube 1.7 keyled-enforce must replace system 1.6.
  if [ "$_sys_deep" = "1" ] && [ "$_sys_live" = "1" ] && [ "$_tip_live" != "1" ]; then
    if [ "${_FORCE_KCUBE_KEYLED:-0}" = "1" ]; then
      _sys_deep=0
      _tip_live=0
    else
      _stamp_cube_load_tip_ver "$_ln" "$_lt" "$_verf"
      return 0
    fi
  fi

  # 2.126: sensor tip file has 1.9-cube-load-steady-on but stamp is pre-1.9
  # (or missing) → reexec once so live belt drops blind 1.8 re-revoke residual.
  # Same-token 1.9 live still leave-alive (never thrash healthy tip).
  if [ "$_tip_live" = "1" ] && [ "$_ln" = "sensor_privacy" ]; then
    if grep -a -F -q '1.9-cube-load-steady-on' "$_lt" 2>/dev/null; then
      _curv=`cat "$_verf" 2>/dev/null | tr -d '\r\n '`
      case "$_curv" in
        *1.9-cube-load-steady-on*)
          : # already tip-ver 1.9
          ;;
        *)
          # Fall through to reexec path below (do not leave-alive).
          _tip_live=0
          echo "upgrade sensor_privacy tip=$_lt from=${_curv:-none} to=1.9 ts=`date +%s`" \
            >> "$ST/titan2_cube_load_tip_land.log" 2>/dev/null || true
          ;;
      esac
    fi
  fi

  # 2.125 leave-alive: any real tip live + tip file is park-family → stamp,
  # prune duals only, never kill+restart (2.124 reexec thrash residual).
  # Exception: kernel_cube upgrade to 1.7-keyled-enforce.
  if [ "$_tip_live" = "1" ] && [ "${_FORCE_KCUBE_KEYLED:-0}" != "1" ]; then
    case "$_ln" in
      sensor_privacy)
        _prune_sensor_privacy_duals
        ;;
      kernel_cube)
        _prune_svc_duals kernel_cube "$_mark_tip" "$_mark_sys" ""
        ;;
      usb_hid)
        _prune_svc_duals usb_hid "$_mark_tip" "$_mark_sys" ""
        ;;
    esac
    _stamp_cube_load_tip_ver "$_ln" "$_lt" "$_verf"
    echo "leave $_ln tip=$_lt live=1 ts=`date +%s`" \
      >> "$ST/titan2_cube_load_tip_land.log" 2>/dev/null || true
    chmod 666 "$ST/titan2_cube_load_tip_land.log" 2>/dev/null || true
    return 0
  fi

  # Tip dead: if system park-family already live, leave (product SoT).
  # Exception: kernel_cube keyled-enforce upgrade.
  if [ "$_sys_live" = "1" ] && [ "$_sys_deep" = "1" ] \
      && [ "${_FORCE_KCUBE_KEYLED:-0}" != "1" ]; then
    _stamp_cube_load_tip_ver "$_ln" "$_lt" "$_verf"
    return 0
  fi

  # n_real=0 (or only pre-park system): start tip once.
  _need_reexec=1

  # Stop init service first so it does not re-spawn /system path.
  if [ -n "$_ls" ]; then
    setprop ctl.stop "$_ls" 2>/dev/null || true
  fi
  _nk=0
  for _mark in "$_lm" "$_lt" "$_mark_tip" "$_mark_sys"; do
    [ -n "$_mark" ] || continue
    for _lp in `_real_svc_pids "$_mark"`; do
      case "$_lp" in ''|*[!0-9]*) continue ;; esac
      kill -9 "$_lp" 2>/dev/null || true
      _nk=`expr $_nk + 1 2>/dev/null` || _nk=8
      [ "$_nk" -ge 12 ] 2>/dev/null && break
    done
  done
  # Magisk sensor service dual mark (only when landing sensor).
  if [ "$_ln" = "sensor_privacy" ]; then
    for _lp in `_real_svc_pids titan2_sensor_privacy/service`; do
      kill -9 "$_lp" 2>/dev/null || true
      _nk=`expr $_nk + 1 2>/dev/null` || _nk=8
    done
  fi
  (
    export PATH=/system/bin:/system/xbin:/vendor/bin:$PATH
    case "$_ln" in
      usb_hid)
        export MODDIR=/system/etc/titan2_usb_hid
        ;;
    esac
    setsid /system/bin/sh "$_lt" </dev/null >/dev/null 2>&1 &
    _new=$!
    if [ "$_ln" = "sensor_privacy" ] && [ -n "$_new" ]; then
      echo "$_new" > "$ST/titan2_sp.pid" 2>/dev/null || true
      chmod 666 "$ST/titan2_sp.pid" 2>/dev/null || true
    fi
  )
  # 2.125: stamp ALL services on reexec (kernel/usb missed → land race residual).
  _stamp_cube_load_tip_ver "$_ln" "$_lt" "$_verf"
  echo "land $_ln tip=$_lt killed=$_nk reexec=$_need_reexec ts=`date +%s`" >> "$ST/titan2_cube_load_tip_land.log" 2>/dev/null || true
  chmod 666 "$ST/titan2_cube_load_tip_land.log" 2>/dev/null || true
  return 0
}
_maybe_land_cube_load_park_tips() {
  _stamp="$ST/titan2_cube_load_tip_land_s"
  _last=`cat "$_stamp" 2>/dev/null | tr -d '\r\n '`
  _now=`date +%s`
  case "$_last" in ''|*[!0-9]*) _last=0 ;; esac
  _age=`expr $_now - $_last 2>/dev/null` || _age=999
  # 2.125: under sticky heat prefer 180s (90s still re-probed too often while
  # leave-alive is free, but reexec path under load should stay rare).
  _cd=90
  _li=`cat /proc/loadavg 2>/dev/null | cut -d. -f1`
  case "$_li" in ''|*[!0-9]*) _li=0 ;; esac
  if [ "$_li" -ge 8 ] 2>/dev/null; then _cd=180; fi
  if [ "$_age" -lt "$_cd" ] 2>/dev/null; then
    return 0
  fi
  # Stamp first so concurrent belt cannot dual-land under heat.
  echo "$_now" > "$_stamp" 2>/dev/null || true
  chmod 666 "$_stamp" 2>/dev/null || true

  _u_tip=""
  if [ -x "$ST/titan2-usb-hid-service.sh" ] \
      && _tip_has_cube_load_park "$ST/titan2-usb-hid-service.sh"; then
    _u_tip="$ST/titan2-usb-hid-service.sh"
  elif [ -x "$ST/titan2-usb-hid-service-0.16.18.sh" ] \
      && _tip_has_cube_load_park "$ST/titan2-usb-hid-service-0.16.18.sh"; then
    _u_tip="$ST/titan2-usb-hid-service-0.16.18.sh"
  fi

  _land_cube_load_park_tip kernel_cube \
    "$ST/titan2-kernel-cube.sh" \
    titan2-kernel-cube \
    /system/bin/titan2-kernel-cube.sh
  _land_cube_load_park_tip sensor_privacy \
    "$ST/titan2-sensor-privacy.sh" \
    titan2-sensor-privacy \
    /system/bin/titan2-sensor-privacy.sh
  if [ -n "$_u_tip" ]; then
    _land_cube_load_park_tip usb_hid \
      "$_u_tip" \
      titan2-usb-hid \
      /system/etc/titan2_usb_hid/service.sh
  fi
  return 0
}
# Fire-and-forget — never block claim or main loop on tip land (stop/pgrep residual).
# 2.116: check 90s cooldown BEFORE bg spawn (2.115 always forked sh every ~20s
# under heat → short-lived dual pad-agent PIDs in top).
# 2.125: heat cooldown 180s (match _maybe).
_kick_cube_load_tip_land() {
  _kstamp="$ST/titan2_cube_load_tip_land_s"
  _klast=`cat "$_kstamp" 2>/dev/null | tr -d '\r\n '`
  _know=`date +%s`
  case "$_klast" in ''|*[!0-9]*) _klast=0 ;; esac
  _kage=`expr $_know - $_klast 2>/dev/null` || _kage=999
  _kcd=90
  _kli=`cat /proc/loadavg 2>/dev/null | cut -d. -f1`
  case "$_kli" in ''|*[!0-9]*) _kli=0 ;; esac
  if [ "$_kli" -ge 8 ] 2>/dev/null; then _kcd=180; fi
  if [ "$_kage" -lt "$_kcd" ] 2>/dev/null; then
    return 0
  fi
  ( _maybe_land_cube_load_park_tips ) &
  return 0
}


cmd=${1:-kick}
case "$cmd" in
  kick)
    _kick_cube_load_tip_land
    ;;
  land|run)
    _maybe_land_cube_load_park_tips
    ;;
  version|-v|--version)
    echo "$CUBE_LAND_VER"
    ;;
  *)
    echo "usage: titan2-cube-load-land.sh kick|land|version" >&2
    exit 2
    ;;
esac
