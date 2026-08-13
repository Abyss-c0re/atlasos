#!/system/bin/sh
# Session-aware USB HID (hybrid in-ROM or Magisk). Single instance via pidfile.
# Prefer Magisk module (lab-updated) over stale in-ROM /system stack.
# MODDIR from Magisk wrapper wins when set and executable.
# GSI product may land bridge as /system/bin/titan2-hid-bridge (Soong) while
# scripts stay under /system/etc/titan2_usb_hid/ — accept either layout.
if [ -n "${MODDIR:-}" ] && [ -x "${MODDIR}/enable_hid.sh" ] \
    && { [ -x "${MODDIR}/hid_bridge" ] || [ -x /system/bin/titan2-hid-bridge ]; }; then
  :
elif [ -x /data/adb/modules/titan2_usb_hid/enable_hid.sh ] && [ -x /data/adb/modules/titan2_usb_hid/hid_bridge ]; then
  MODDIR=/data/adb/modules/titan2_usb_hid
elif [ -x "${0%/*}/enable_hid.sh" ] && [ -x "${0%/*}/hid_bridge" ]; then
  MODDIR=${0%/*}
elif [ -x /system/etc/titan2_usb_hid/enable_hid.sh ]; then
  MODDIR=/system/etc/titan2_usb_hid
else
  MODDIR=${0%/*}
fi
LOG=/data/local/tmp/titan2_usb_hid.log
PIDF=/data/local/tmp/t2uhid.pid
BPIDF=/data/local/tmp/t2uhid_bridge.pid
# Writable without Magisk (service creates; app may also use app-private files)
CTRL=/data/misc/titan2
EN=$MODDIR/enable_hid.sh
BR=$MODDIR/hid_bridge
# GSI Soong stem: /system/bin/titan2-hid-bridge when etc/hid_bridge missing
if [ ! -x "$BR" ] && [ -x /system/bin/titan2-hid-bridge ]; then
  BR=/system/bin/titan2-hid-bridge
fi
if [ ! -x "$EN" ] && [ -x /data/local/tmp/t2uhid_enable_hid.sh ]; then
  EN=/data/local/tmp/t2uhid_enable_hid.sh
fi
# Prefer lab-staged bridge when it carries exclusive Sym in-bridge map (B2 SoT).
# Never prefer a map-less tmp over a mapped system/module binary — that silent
# specials miss is why install used to thrash swap_hid_stack every land.
# 0.16.9: product bridge also needs titan2-phone-nav (0.16.8+) so exclusive
# upper Back/Home/Recents stay on phone. Map-only system residual (pre-0.16.8)
# would send Back→host ESC and leave phone nav dead under grab.
# 0.16.10: rootless hybrid (no Magisk post-fs) must still stage product →
# /data/local/tmp/hid_bridge so B2 SoT matches Magisk land (quiet cp only).
_bridge_has_map() {
  [ -x "$1" ] || return 1
  strings "$1" 2>/dev/null | grep -q 'specials layer in-bridge'
}
_bridge_has_product() {
  # B2 map + exclusive phone-nav SoT (0.16.8+)
  _bridge_has_map "$1" || return 1
  strings "$1" 2>/dev/null | grep -q 'titan2-phone-nav'
}
_pick_bridge() {
  # Prefer product (map+phone-nav), then map-only, then any staged tip.
  # Args: default BR path. Echoes chosen path.
  _def="${1:-}"
  if _bridge_has_product /data/local/tmp/hid_bridge; then
    echo /data/local/tmp/hid_bridge
  elif _bridge_has_product /data/local/tmp/t2uhid_hid_bridge; then
    echo /data/local/tmp/t2uhid_hid_bridge
  elif [ -n "$_def" ] && _bridge_has_product "$_def"; then
    echo "$_def"
  elif _bridge_has_map /data/local/tmp/hid_bridge; then
    echo /data/local/tmp/hid_bridge
  elif _bridge_has_map /data/local/tmp/t2uhid_hid_bridge; then
    echo /data/local/tmp/t2uhid_hid_bridge
  elif [ -n "$_def" ] && _bridge_has_map "$_def"; then
    echo "$_def"
  elif [ -x /data/local/tmp/hid_bridge ]; then
    echo /data/local/tmp/hid_bridge
  elif [ -x /data/local/tmp/t2uhid_hid_bridge ]; then
    echo /data/local/tmp/t2uhid_hid_bridge
  elif [ -n "$_def" ] && [ -x "$_def" ]; then
    echo "$_def"
  else
    echo "$_def"
  fi
}

mkdir -p /data/local/tmp "$CTRL" 2>/dev/null
chmod 777 "$CTRL" 2>/dev/null || true
# World-readable log so shell/lab can diagnose without su
: >"$LOG" 2>/dev/null || true
chmod 666 "$LOG" 2>/dev/null || true
log() {
  echo "$(date +%H:%M:%S) $*" >>"$LOG" 2>/dev/null || true
  chmod 666 "$LOG" 2>/dev/null || true
}

# 0.16.18 cube-load-park: cool idle 0.15s plane poll left continuous read_ctrl +
# host/dual-bridge work reheating lab under load≈17 after sensor-privacy 1.6 /
# kernel-cube 1.3 parks. load≥8 + session off → HEAT_INTERVAL_S (default 2s)
# session-only edge wait (no full plane thrash). Session on keeps 0.05s.
HEAT_INTERVAL_S=${HEAT_INTERVAL_S:-2}
HEAT_LOAD_GE=${HEAT_LOAD_GE:-8}
load_1m_int() {
  set -- $(cat /proc/loadavg 2>/dev/null)
  li=${1%%.*}
  case "$li" in ''|*[!0-9]*) li=0;; esac
  echo "$li"
}

# Rootless hybrid residual: Magisk post-fs-data stages product → tmp; pure
# hybrid boot left /data/local/tmp/hid_bridge missing (system had map only).
# Quiet cp — never killall / swap_hid_stack / exclusive arm.
_stage_product_bridge_tmp() {
  _src=""
  for _c in \
    "$MODDIR/hid_bridge" \
    /data/adb/modules/titan2_usb_hid/hid_bridge \
    /system/etc/titan2_usb_hid/hid_bridge
  do
    if _bridge_has_product "$_c"; then
      _src="$_c"
      break
    fi
  done
  [ -n "$_src" ] || return 0
  if [ -x /data/local/tmp/hid_bridge ] && _bridge_has_product /data/local/tmp/hid_bridge; then
    # Keep live product tmp unless source is newer (module/system update).
    if [ "$_src" -nt /data/local/tmp/hid_bridge ] 2>/dev/null; then
      :
    else
      return 0
    fi
  fi
  cp -f "$_src" /data/local/tmp/hid_bridge 2>/dev/null || return 0
  chmod 755 /data/local/tmp/hid_bridge 2>/dev/null || true
  log "stage product hid_bridge → tmp from $_src (0.16.10 rootless B2 SoT)"
}
_stage_product_bridge_tmp
BR="$(_pick_bridge "$BR")"

# Single instance. Do NOT exit 0 when a peer is live — init has no oneshot, so
# exit makes init.svc=restarting forever (Magisk module + in-ROM dual stack).
if [ -f "$PIDF" ]; then
  old=$(cat "$PIDF" 2>/dev/null | tr -d ' \r\n')
  if [ -n "$old" ] && [ "$old" != "$$" ] && kill -0 "$old" 2>/dev/null; then
    # 0.16.15: grep -a -F only — never tr '\0' on cmdline (toybox hang residual)
    if [ -r "/proc/$old/cmdline" ] && {
         grep -a -F -q 'titan2_usb_hid' "/proc/$old/cmdline" 2>/dev/null \
         || grep -a -F -q 'titan2-usb-hid' "/proc/$old/cmdline" 2>/dev/null
       }; then
      log "peer service pid=$old — wait (avoid init restart thrash)"
      while kill -0 "$old" 2>/dev/null; do sleep 15; done
      log "peer $old gone — becoming primary"
    fi
  fi
fi
echo $$ >"$PIDF"

# Graceful bridge stop: TERM → wait ~200ms for 0.16.2+ empty kbd/mouse report
# → SIGKILL hung only. Used at service start (orphan) and stop_bridge (session off).
# Immediate -9 after TERM skipped the flush and left boot-protocol hosts sticky Shift/Alt.
graceful_stop_bridges() {
  if [ -f "$BPIDF" ]; then
    kill "$(cat "$BPIDF" 2>/dev/null)" 2>/dev/null || true
    rm -f "$BPIDF"
  fi
  killall hid_bridge 2>/dev/null || true
  # poll loop is 8ms; allow ~200ms for empty report + ungrab
  _gsb_i=0
  while [ "$_gsb_i" -lt 20 ]; do
    pidof hid_bridge >/dev/null 2>&1 || break
    sleep 0.01
    _gsb_i=$((_gsb_i + 1))
  done
  if pidof hid_bridge >/dev/null 2>&1; then
    # Hung only — dual bridges still hard-kill for single-owner
    killall -9 hid_bridge 2>/dev/null || true
    sleep 0.03
  fi
}

# kill orphan bridge (service restart / Magisk boot / peer takeover)
graceful_stop_bridges
# Never coexist with a second stack (system hybrid + Magisk). Dual bridges
# double-grab input. NEVER kill self — cmdline is .../titan2_usb_hid/service.sh.
# 0.16.11: multi-pass peer kill + orphan ppid=1 service roots (same residual as
# pad-agent 2.37). Single SIGTERM left Magisk+hybrid dual service trees → dual
# bridge / grab thrash until mid-loop BPIDF prune (heat residual).
# 0.16.12: never ps -A under heat (1.65 hang residual; pad-agent Magisk 2.45 SoT).
# 0.16.15: NEVER tr '\0' on /proc/cmdline — toybox tr can spin forever (lab
# load~15, hidg never armed, session stuck). Peer match = grep -a -F only.
# Mid-loop `n=$(count)` subshell shares service cmdline — skip ppid=self and
# non-root peers so command-sub never self-counts as dual (false prune thrash).
# 0.16.19: pgrep -f candidates only — never full /proc/[0-9]* walk (touchpadd
# 1.15 / pad-agent 2.119 SoT; ~3k tasks under sticky load≈13 reheated cool plug
# already-tip probe + service start after 0.16.17 closed tr).
# Markers: 0.16.19-pgrep-peer pgrep-peer
_self=$$
_ppid=$PPID
# True if pid's cmdline is a usb_hid service entry (no NUL decode).
_is_usb_hid_svc_pid() {
  _ip="$1"
  [ -n "$_ip" ] && [ -r "/proc/$_ip/cmdline" ] || return 1
  # Binary grep on raw cmdline — embedded NULs are fine with -a.
  grep -a -F -q 'titan2_usb_hid/service' "/proc/$_ip/cmdline" 2>/dev/null && return 0
  grep -a -F -q 'titan2-usb-hid-service' "/proc/$_ip/cmdline" 2>/dev/null && return 0
  return 1
}
_foreach_peer_svc() {
  # $1 = action: echo | term | kill9 | count
  # Only *roots* (parent is not this service tree). Command-sub children of
  # main have ppid=$_self and must never count as dual peers (0.16.15).
  # 0.16.19: pgrep candidates only (never full /proc walk under sticky load).
  _act="$1"
  n=0
  for p in `pgrep -f titan2_usb_hid/service 2>/dev/null` \
           `pgrep -f titan2-usb-hid-service 2>/dev/null`; do
    case "$p" in ''|*[!0-9]*) continue ;; esac
    [ "$p" = "$_self" ] && continue
    [ "$p" = "$_ppid" ] && continue
    [ -d "/proc/$p" ] || continue
    comm=`cat "/proc/$p/comm" 2>/dev/null` || continue
    [ "$comm" = "sh" ] || continue
    st=`cat "/proc/$p/stat" 2>/dev/null` || continue
    rest=${st##*) }
    set -- $rest
    ppid="$2"
    case "$ppid" in ''|*[!0-9]*) ppid=0 ;; esac
    # Our workers / count subshells / background jobs — not dual stack
    [ "$ppid" = "$_self" ] && continue
    # Parent is another usb_hid service root → that tree's worker (leave)
    if [ "$ppid" != "0" ] && [ "$ppid" != "1" ] && _is_usb_hid_svc_pid "$ppid"; then
      continue
    fi
    _is_usb_hid_svc_pid "$p" || continue
    case "$_act" in
      echo) echo "$p" ;;
      term) kill "$p" 2>/dev/null || true ;;
      kill9) kill -9 "$p" 2>/dev/null || true ;;
      count) n=`expr $n + 1 2>/dev/null` || n=32 ;;
    esac
  done
  [ "$_act" = "count" ] && echo "$n"
  return 0
}
_kill_peer_svc() {
  _foreach_peer_svc term >/dev/null
  _ksi=0
  while [ "$_ksi" -lt 20 ]; do
    left=`_foreach_peer_svc count 2>/dev/null` || left=0
    case "$left" in ''|*[!0-9]*) left=0 ;; esac
    [ "$left" = 0 ] && break
    sleep 0.01
    _ksi=$((_ksi + 1))
  done
  _foreach_peer_svc kill9 >/dev/null
}
_prune_orphan_svc_roots() {
  # sh with ppid=1 running titan2_usb_hid/service (kill reparent residual)
  # 0.16.19: pgrep candidates only (0.16.17 full /proc under sticky load≈13 heat).
  n=0
  for p in `pgrep -f titan2_usb_hid/service 2>/dev/null` \
           `pgrep -f titan2-usb-hid-service 2>/dev/null`; do
    case "$p" in ''|*[!0-9]*) continue ;; esac
    [ "$p" = "$_self" ] && continue
    [ -d "/proc/$p" ] || continue
    comm=`cat "/proc/$p/comm" 2>/dev/null` || continue
    [ "$comm" = "sh" ] || continue
    st=`cat "/proc/$p/stat" 2>/dev/null` || continue
    rest=${st##*) }
    set -- $rest
    [ "$2" = "1" ] || continue
    _is_usb_hid_svc_pid "$p" || continue
    kill -9 "$p" 2>/dev/null || true
    n=`expr $n + 1 2>/dev/null` || n=32
    [ "$n" -ge 32 ] 2>/dev/null && break
  done
  [ "$n" -gt 0 ] 2>/dev/null && log "pruned orphan usb_hid service roots n=$n keep=$_self"
}
# 0.16.16: fast peer claim — at most one light pass at start (full multi-pass
# deferred to mid-loop). Full /proc scans at boot delayed service start by minutes
# so pad/HID redirect felt broken until unlock/feel-test long after boot.
if [ -f "$PIDF" ]; then
  _oldpf=$(cat "$PIDF" 2>/dev/null | tr -d ' \r\n')
  if [ -n "$_oldpf" ] && [ "$_oldpf" != "$$" ] && kill -0 "$_oldpf" 2>/dev/null \
      && _is_usb_hid_svc_pid "$_oldpf"; then
    kill "$_oldpf" 2>/dev/null || true
    sleep 0.05
    kill -9 "$_oldpf" 2>/dev/null || true
  fi
fi
_prune_orphan_svc_roots
# Skip second full kill pass at boot — mid-loop 0.16.15 root-only prune is enough.

read_ctrl() {
  name=$1
  # Newest mtime wins across ALL planes (app CE, external, OS, tmp).
  # Never prefer CE-first without mtime — stale session=0 blocked live Start.
  best_mt=-1
  best_v=""
  for f in \
    "/data/user/0/com.titanus2.usbhid/files/$name" \
    "/data/data/com.titanus2.usbhid/files/$name" \
    "/sdcard/Android/data/com.titanus2.usbhid/files/$name" \
    "/storage/emulated/0/Android/data/com.titanus2.usbhid/files/$name" \
    "$CTRL/$name" \
    "/data/misc/titan2/$name" \
    "/data/local/tmp/$name" \
    "/data/local/tmp/titan2/$name" \
    "/data/adb/titan2/$name"
  do
    [ -f "$f" ] || continue
    v=$(cat "$f" 2>/dev/null | tr -d '\r\n ')
    [ -n "$v" ] || continue
    mt=$(stat -c %Y "$f" 2>/dev/null) || mt=0
    case "$mt" in ''|*[!0-9]*) mt=0;; esac
    if [ "$mt" -ge "$best_mt" ] 2>/dev/null; then
      best_mt=$mt
      best_v=$v
    fi
  done
  echo "$best_v"
}

# Keep control shells world-writable so the app arms session without su.
# Apps can rewrite existing 0666 files but often cannot *create* new ones.
ensure_shell() {
  name=$1
  def=$2
  for d in "$CTRL" /data/local/tmp; do
    f="$d/$name"
    if [ ! -f "$f" ]; then
      printf '%s' "$def" > "$f" 2>/dev/null || true
    fi
    chmod 666 "$f" 2>/dev/null || true
  done
}
# Pad epoch / regrab for framework + hid_bridge fast re-intercept
ensure_shell titan2_pad_epoch 0
ensure_shell titan2_pad_regrab 0

bool01() {
  case "$1" in 1|true|on|ON|yes|YES) echo 1;; *) echo 0;; esac
}

hid_linked_kbd() {
  for l in /config/usb_gadget/g1/configs/b.1/*; do
    [ -L "$l" ] || continue
    t=$(readlink "$l" 2>/dev/null || true)
    case "$t" in *hid.gs0*) return 0 ;; esac
  done
  return 1
}

hid_linked_mouse() {
  for l in /config/usb_gadget/g1/configs/b.1/*; do
    [ -L "$l" ] || continue
    t=$(readlink "$l" 2>/dev/null || true)
    case "$t" in *hid.usb0*) return 0 ;; esac
  done
  return 1
}

hid_ready() {
  hid_linked_kbd || return 1
  hid_linked_mouse || return 1
  [ -e /dev/hidg0 ] || return 1
  [ -e /dev/hidg1 ] || return 1
  return 0
}

usb_data_up() {
  # Pure HID gadget fully linked counts as "up" even when host cable is out
  # (sys.usb.state may be DISCONNECTED / empty while hidg is live).
  if hid_ready; then return 0; fi
  st=$(getprop sys.usb.state)
  case "$st" in
    *adb*|*mtp*|*ptp*|*hid*|*midi*|*rndis*|*ncm*|*titan*) return 0 ;;
  esac
  cfg=$(getprop sys.usb.config)
  case "$cfg" in
    *titan_hid*|*hid*) return 0 ;;
  esac
  u=$(cat /config/usb_gadget/g1/UDC 2>/dev/null | tr -d ' \t\r\n')
  [ -n "$u" ] && [ "$u" != "none" ] && return 0
  return 1
}

stop_bridge() {
  # Shared with service-start orphan kill (empty-report SoT).
  graceful_stop_bridges
}

# Pad-agent is the lifecycle owner of titan2_pad_mode.
# HID only starts touchpadd when pad is off (temporary host mouse).
# trackpad = native ABS (no virtual mouse). mouse = pad-agent owns daemon.
pad_mode_now() {
  m=$(read_ctrl titan2_pad_mode)
  case "$m" in
    mouse|1|true|on|ON) echo mouse ;;
    trackpad) echo trackpad ;;
    *) echo off ;;
  esac
}

# If we started touchpadd only for HID and user pad mode is off, stop it so
# the hardware pad is not left live after session ends.
# Never kill when pad-agent owns mouse/trackpad.
stop_hid_touchpadd_if_pad_off() {
  mode=$(pad_mode_now)
  case "$mode" in
    mouse|trackpad) return 0 ;;
  esac
  if pidof titan2-touchpadd >/dev/null 2>&1; then
    log "pad_mode=off — stopping HID-only touchpadd"
    killall titan2-orient-rel 2>/dev/null || true
    killall titan2-touchpadd 2>/dev/null || true
  fi
  # Re-inhibit native pad when user mode is off
  for d in /sys/class/input/input*; do
    [ -e "$d/name" ] || continue
    n=$(cat "$d/name" 2>/dev/null)
    [ "$n" = "touchPad" ] || continue
    echo 1 > "$d/inhibited" 2>/dev/null || true
  done
  setprop persist.sys.agui.touchpad_function 0 2>/dev/null || true
}

# Uninhibit hardware pad (needed by touchpadd and native trackpad).
uninhibit_hw_pad() {
  for d in /sys/class/input/input*; do
    [ -e "$d/name" ] || continue
    n=$(cat "$d/name" 2>/dev/null)
    [ "$n" = "touchPad" ] || continue
    echo 0 > "$d/inhibited" 2>/dev/null || true
  done
}

# B4 / S-HID-08: true if titan2-touchpadd holds TitanKey (must not under exclusive keys).
touchpadd_holds_titankey() {
  pids=$(pidof titan2-touchpadd 2>/dev/null) || return 1
  [ -n "$pids" ] || return 1
  tk=
  for d in /sys/class/input/input*; do
    [ -e "$d/name" ] || continue
    n=$(cat "$d/name" 2>/dev/null) || continue
    [ "$n" = "TitanKey" ] || continue
    for e in "$d"/event*; do
      [ -e "$e" ] || continue
      tk="/dev/input/$(basename "$e")"
      break
    done
    [ -n "$tk" ] && break
  done
  [ -n "$tk" ] || tk=/dev/input/event7
  for p in $pids; do
    [ -d "/proc/$p/fd" ] || continue
    for f in /proc/$p/fd/*; do
      t=$(readlink "$f" 2>/dev/null) || continue
      [ "$t" = "$tk" ] && return 0
    done
  done
  return 1
}

# Prefer tip binary with INPROC_PARK (product SoT) over stale system.
touchpadd_bin() {
  if [ -x /data/local/tmp/titan2-touchpadd ] \
      && grep -aqF 'INPROC_PARK' /data/local/tmp/titan2-touchpadd 2>/dev/null; then
    echo /data/local/tmp/titan2-touchpadd
    return 0
  fi
  if [ -x /system/bin/titan2-touchpadd ]; then
    echo /system/bin/titan2-touchpadd
    return 0
  fi
  return 1
}

# Pad-agent singleton live? (lockdir pid) — mouse owner when mode=mouse.
pad_agent_live() {
  _p=$(cat /data/misc/titan2/pad-agent.lockdir/pid 2>/dev/null | tr -d '\r\n ')
  [ -n "$_p" ] && [ -d "/proc/$_p" ]
}

start_touchpadd_daemon() {
  uninhibit_hw_pad
  if pidof titan2-touchpadd >/dev/null 2>&1; then
    if touchpadd_holds_titankey; then
      log "touchpadd holds TitanKey — kill for pad-only restart (B4/S-HID-08)"
      killall titan2-orient-rel 2>/dev/null || true
      killall titan2-touchpadd 2>/dev/null || true
      i=0
      while [ $i -lt 10 ] && pidof titan2-touchpadd >/dev/null 2>&1; do
        if command -v usleep >/dev/null 2>&1; then usleep 20000; else sleep 0.02 2>/dev/null || true; fi
        i=$((i + 1))
      done
      killall -9 titan2-touchpadd 2>/dev/null || true
    else
      return 0
    fi
  fi
  _tpbin=$(touchpadd_bin) || {
    log "no titan2-touchpadd binary"
    return 1
  }
  : >>/data/local/tmp/titan2_touchpadd.log 2>/dev/null || true
  chmod 666 /data/local/tmp/titan2_touchpadd.log 2>/dev/null || true
  (
    # Pad only — never open TitanKey; hid_bridge owns exclusive keys=1.
    _click=$(cat /data/misc/titan2/titan2_pad_click 2>/dev/null || echo 1)
    case "$_click" in 0|false|off) _click=0;; *) _click=1;; esac
    _trc=$(cat /data/misc/titan2/titan2_pad_top_row_cursor 2>/dev/null || echo 1)
    case "$_trc" in 0|false|off) _trc=0;; *) _trc=1;; esac
    LOGCAT_OUTPUT=true KEYBOARD_FEATURES=false TAP_TO_CLICK="$_click" \
      TEXT_CARET_NAV="$_trc" TOP_ROW_CURSOR="$_trc" TOP_ROW_ONLY=0 \
      "$_tpbin" >>/data/local/tmp/titan2_touchpadd.log 2>&1
  ) &
  i=0
  while [ $i -lt 10 ]; do
    if pidof titan2-touchpadd >/dev/null 2>&1; then
      if touchpadd_holds_titankey; then
        log "started touchpadd still holds TitanKey — need patched binary (B4)"
        return 1
      fi
      log "started touchpadd pid=$(pidof titan2-touchpadd | head -n1) bin=$_tpbin"
      return 0
    fi
    if command -v usleep >/dev/null 2>&1; then usleep 20000; else sleep 0.02 2>/dev/null || true; fi
    i=$((i + 1))
  done
  log "started touchpadd pid=$(pidof titan2-touchpadd | head -n1) (slow)"
  return 0
}

# Shared driver ownership with pad-agent (OPTIMIZE 1.7 — zero dual start):
#   trackpad → never start touchpadd
#   mouse    → pad-agent owns; HID waits / never cooperative start if agent live
#   off      → HID-owned temporary touchpadd for host session only
ensure_touchpadd() {
  mode=$(pad_mode_now)
  case "$mode" in
    trackpad)
      log "pad_mode=trackpad — skip touchpadd (raw pad / no thrash)"
      return 1
      ;;
    mouse)
      if pidof titan2-touchpadd >/dev/null 2>&1; then
        if touchpadd_holds_titankey; then
          log "pad_mode=mouse — TitanKey dual-owner; pad-only restart (B4)"
          start_touchpadd_daemon
          return $?
        fi
        return 0
      fi
      # Wait for pad-agent (primary owner). Do NOT start a second TP while agent lives.
      i=0
      while [ $i -lt 25 ]; do
        if pidof titan2-touchpadd >/dev/null 2>&1; then
          if touchpadd_holds_titankey; then
            start_touchpadd_daemon
            return $?
          fi
          log "pad_mode=mouse — sharing pad-agent touchpadd"
          return 0
        fi
        if command -v usleep >/dev/null 2>&1; then usleep 20000; else sleep 0.02 2>/dev/null || true; fi
        i=$((i + 1))
      done
      if pad_agent_live; then
        log "pad_mode=mouse — pad-agent live, touchpadd still down (agent owns; no HID dual-start)"
        return 1
      fi
      log "pad_mode=mouse — pad-agent dead; HID last-resort start"
      start_touchpadd_daemon
      return $?
      ;;
    *)
      if pidof titan2-touchpadd >/dev/null 2>&1; then
        if touchpadd_holds_titankey; then
          log "pad_mode=off — HID touchpadd holds TitanKey; pad-only restart"
          start_touchpadd_daemon
          return $?
        fi
        return 0
      fi
      log "pad_mode=off — HID-owned touchpadd for session"
      start_touchpadd_daemon
      return $?
      ;;
  esac
}

# Health-loop: pad off = HID owner. mouse = pad-agent only (no dual thrash).
ensure_touchpadd_health() {
  mode=$(pad_mode_now)
  case "$mode" in
    trackpad) return 0 ;;
    mouse)
      if pidof titan2-touchpadd >/dev/null 2>&1; then
        if [ $((loop_n % 20)) -eq 0 ] && touchpadd_holds_titankey; then
          log "pad mouse touchpadd holds TitanKey — pad-only restart (B4)"
          start_touchpadd_daemon || true
        fi
        return 0
      fi
      # Never HID-start while pad-agent owns mouse (zero regression dual kill).
      if pad_agent_live; then
        return 0
      fi
      if [ $((loop_n % 20)) -eq 0 ]; then
        log "pad mouse + agent dead — last-resort ensure"
        ensure_touchpadd || true
      fi
      return 0
      ;;
    *)
      if ! pidof titan2-touchpadd >/dev/null 2>&1; then
        log "HID touchpadd dead — restart (keep bridge)"
        ensure_touchpadd || true
      elif touchpadd_holds_titankey; then
        log "HID touchpadd holds TitanKey — pad-only restart (B4)"
        ensure_touchpadd || true
      elif ! virt_mouse_present; then
        if [ $((loop_n % 10)) -eq 0 ]; then
          log "virt mouse missing — re-ensure HID touchpadd"
          ensure_touchpadd || true
        fi
      fi
      return 0
      ;;
  esac
}

virt_mouse_present() {
  for d in /sys/class/input/input*; do
    [ -e "$d/name" ] || continue
    n=$(cat "$d/name" 2>/dev/null)
    case "$n" in
      titan2-virtual-mouse|titan2-touchpadd|titan2_touchpadd|Titan2\ Touchpad) return 0 ;;
    esac
  done
  return 1
}

usb_host_linked() {
  # True only when a USB host has enumerated us. hidg nodes alone do NOT count.
  st=$(cat /sys/class/android_usb/android0/state 2>/dev/null || true)
  case "$st" in
    CONNECTED|CONFIGURED) return 0 ;;
    DISCONNECTED) return 1 ;;
  esac
  u=$(cat /sys/class/udc/*/state 2>/dev/null | head -1 | tr -d ' \t\r\n')
  case "$u" in
    configured|CONFIGURED) return 0 ;;
    *) return 1 ;;
  esac
}

# Phone has a focused text field / IME (launcher search, EditText, …).
# Root dumpsys is reliable; the app's LocalInputGuard often can't see
# mServedView (late in the dump) and dual-types into host + phone.
# Sets titan2_usb_hid_local_input so hid_bridge pauses keys+pad to host.
update_local_input_pause() {
  # Share-mode only (caller also gates grab=0). Exclusive never pauses.
  if [ "${grab:-0}" = "1" ]; then
    if [ "${last_lip:-}" != "0" ]; then
      write_local_input 0
      last_lip=0
    fi
    return 0
  fi
  # Only while a live session wants physical redirect
  if [ "$sess" != "1" ] || { [ "$keys" != "1" ] && [ "$mouse" != "1" ]; }; then
    if [ "${last_lip:-}" != "0" ]; then
      write_local_input 0
      last_lip=0
    fi
    return 0
  fi
  # Cheap focus probe (not full dumpsys)
  focus=$(dumpsys input_method 2>/dev/null \
    | grep -E 'mInputShown=|mShowRequested=|mServedView=' 2>/dev/null \
    | head -n 40)
  lip=0
  case "$focus" in
    *com.titanus2.usbhid*)
      # Our Type/Pad chrome — never pause for ourselves
      lip=0
      ;;
    *)
      # Require a real editor/search served view. Soft IME flags alone stay
      # true after dismiss and used to pause host keys mid-typing on HW kb.
      served=$(echo "$focus" | grep 'mServedView=' | grep -v 'mServedView=null' | tail -n 1)
      case "$served" in
        "") lip=0 ;;
        *DecorView*|*RecyclerView*|*ViewPager*|*FrameLayout\{*|*LinearLayout\{*|*ScrollView\{*)
          # Only if clearly a search/editor widget name too
          case "$served" in
            *[Ss]earch*|*[Ee]dit*|*[Ii]nput*) lip=1 ;;
            *) lip=0 ;;
          esac
          ;;
        *[Ee]dit[Tt]ext*|*[Ss]earch*|*[Ww]eb[Vv]iew*|*[Tt]ext[Ff]ield*|*[Aa]uto[Cc]omplete*)
          lip=1
          ;;
        *) lip=0 ;;
      esac
      ;;
  esac
  if [ "$lip" != "${last_lip:-}" ]; then
    write_local_input "$lip"
    log "local_input=$lip (phone field)"
    last_lip=$lip
  fi
}

write_local_input() {
  v=$1
  for d in "$CTRL" /data/local/tmp \
    /data/user/0/com.titanus2.usbhid/files \
    /data/data/com.titanus2.usbhid/files
  do
    mkdir -p "$d" 2>/dev/null || true
    printf '%s' "$v" > "$d/titan2_usb_hid_local_input" 2>/dev/null || true
  done
}

start_bridge() {
  grab=$1
  mouse=$2
  want_usb=$3   # 1 = need hidg gadget
  want_hw=$4    # 1 = mirror physical to app (BT)
  keys=${5:-1}  # 0 = soft keys only (Type tab); default open TitanKey
  stop_bridge
  # Residual race spawn after stop: re-use graceful_stop (empty report), never bare -9.
  # Bare kill -9 here left sticky host Shift/Alt when residual still held grab (0.16.5).
  if pidof hid_bridge >/dev/null 2>&1; then
    graceful_stop_bridges
  fi
  # Same product-first SoT as boot pick: map+phone-nav > map-only > tip.
  # Avoid map-less tmp over mapped system; avoid map-only system over staged
  # 0.16.8+ product when exclusive upper nav must stay on phone.
  BR_RUN="$(_pick_bridge "$BR")"
  [ -x "$BR_RUN" ] || { log "no bridge"; return 1; }
  if _bridge_has_product "$BR_RUN"; then
    log "bridge binary=$BR_RUN (map+phone-nav product)"
  elif [ "$BR_RUN" != "$BR" ]; then
    log "bridge binary=$BR_RUN (lab stage)"
  elif _bridge_has_map "$BR_RUN"; then
    log "bridge binary=$BR_RUN (in-bridge map, no phone-nav — exclusive Back residual)"
  fi
  # Never exclusive-grab TitanKey when USB host is absent — that steals phone
  # keys while hidg writes fail (endpoint shutdown) → "keyboard dead".
  # Exception: Screen-off OK sessions — display blank often makes UDC look
  # DISCONNECTED (USB autosuspend) while Quest is still the host. Forcing
  # --nograb there dumps keys onto Android and kills host HID (FB-HID-3).
  if [ "$grab" = "1" ] && [ "$want_usb" = "1" ] && [ "$want_hw" != "1" ]; then
    so_ok=$(bool01 "$(read_ctrl titan2_usb_hid_screen_off)")
    if ! usb_host_linked; then
      if [ "$so_ok" = "1" ]; then
        log "host link soft (screen_off) — keep --grab for HID-while-blank"
      else
        log "host unplugged — forcing --nograb (keep phone keys alive)"
        grab=0
      fi
    fi
  fi
  # Ensure touchpadd mouse mode for full gestures (scroll, L/R click, drag).
  # Bridge exclusive-grabs the virtual mouse only — never the raw pad.
  # Soft-only (mouse=0 keys=0): skip touchpadd — Type tab uses software pad.
  if [ "$mouse" = "1" ]; then
    ensure_touchpadd || true
  fi
  # typing palm-reject timeout (ms); 0 = off
  typing=$(read_ctrl titan2_usb_hid_typing_ms)
  case "$typing" in ""|*[!0-9]*) typing=600 ;; esac
  opts=""
  # Exclusive: always open TitanKey (keys=1) for in-bridge specials map.
  # 0.16.14: not gated on specials_method — plane is phone kcm product default
  # (FB-IN-1); exclusive map is in-bridge regardless. Old inject-only gate left
  # keys=0 under kcm → host no Sym specials. Stale keys=0 from inject_pause also.
  if [ "$grab" = "1" ] && [ "$keys" != "1" ]; then
    keys=1
    printf 1 > /data/local/tmp/titan2_usb_hid_keys 2>/dev/null || true
    printf 0 > /data/local/tmp/titan2_specials_inject_pause 2>/dev/null || true
    printf 0 > /data/local/tmp/titan2_host_layout_keys_pause 2>/dev/null || true
    log "exclusive — force keys=1 (specials layer in-bridge; method plane phone-only)"
  fi
  [ "$grab" = "1" ] && opts="$opts --grab" || opts="$opts --nograb"
  # mouse flag = physical pad/rel bridge; soft inject always uses open hidg1
  [ "$mouse" = "1" ] && opts="$opts --mouse" || opts="$opts --nomouse"
  [ "$keys" = "1" ] && opts="$opts --keys" || opts="$opts --nokeys"
  opts="$opts --typing-ms $typing"
  if [ "$want_hw" = "1" ]; then
    opts="$opts --hw-out"
    echo 1 > "$CTRL/titan2_usb_hid_hw_out" 2>/dev/null || true
  fi
  if [ "$want_usb" = "1" ]; then
    i=0
    while [ "$i" -lt 16 ]; do
      hid_ready && break
      sleep 0.05
      i=$((i + 1))
    done
    if ! hid_ready; then
      log "bridge skip: hid not ready"
      ls -l /config/usb_gadget/g1/configs/b.1/ >>"$LOG" 2>&1 || true
      return 1
    fi
  else
    # BT-only / no cable: physical events → hw_out only
    opts="$opts --nohidg"
    log "bridge nohidg (BT/physical mirror)"
  fi
  # App inject + hw-out (OS plane + app-owned)
  for f in \
    /data/misc/titan2/titan2_hid.inj \
    /data/user/0/com.titanus2.usbhid/files/titan2_hid.inj \
    /data/data/com.titanus2.usbhid/files/titan2_hid.inj \
    /sdcard/Android/data/com.titanus2.usbhid/files/titan2_hid.inj \
    /data/local/tmp/titan2_hid.inj \
    /data/user/0/com.titanus2.usbhid/files/titan2_hid_hw.out \
    /data/local/tmp/titan2_hid_hw.out
  do
    touch "$f" 2>/dev/null || true
    chmod 666 "$f" 2>/dev/null || true
  done
  # shellcheck disable=SC2086
  log "bridge bin=$BR_RUN"
  "$BR_RUN" $opts >>"$LOG" 2>&1 &
  echo $! >"$BPIDF"
  log "bridge pid=$!$opts"
}

ensure_hid_on() {
  if hid_ready; then return 0; fi
  stop_bridge
  sh "$EN" on >>"$LOG" 2>&1
  i=0
  # 20 × 50ms = 1s max (was 30×100ms = 3s) — BT fallback still available
  while [ "$i" -lt 20 ]; do
    hid_ready && return 0
    sleep 0.05
    i=$((i + 1))
  done
  return 1
}

# App-owned control files are the source of truth — never wipe them on
# service restart (that killed live HID sessions during lab redeploys).
# Seed 0666 shells so rootless app can rewrite OS plane without su.
mkdir -p "$CTRL" /data/local/tmp 2>/dev/null || true
if [ -x /system/bin/titan2-ctrl-seed.sh ]; then
  /system/bin/sh /system/bin/titan2-ctrl-seed.sh 2>/dev/null || true
fi
ensure_shell titan2_usb_hid_mouse 0
ensure_shell titan2_usb_hid_grab 0
ensure_shell titan2_usb_hid_keys 0
ensure_shell titan2_usb_hid_usb 1
ensure_shell titan2_usb_hid_bt 0
ensure_shell titan2_usb_hid_session 0
ensure_shell titan2_usb_hid_on 0
ensure_shell titan2_hid.inj ""
chmod 666 "$LOG" /data/local/tmp/titan2_hid.inj 2>/dev/null || true

# App CE session flag (can re-arm USB HID gadget after reboot — kills cable ADB).
app_sess=$(cat /data/user/0/com.titanus2.usbhid/files/titan2_usb_hid_session 2>/dev/null | tr -d ' \r\n')
[ -z "$app_sess" ] && app_sess=$(cat /data/data/com.titanus2.usbhid/files/titan2_usb_hid_session 2>/dev/null | tr -d ' \r\n')
: > /data/local/tmp/titan2_hid.inj 2>/dev/null || true
: > /data/misc/titan2/titan2_hid.inj 2>/dev/null || true
chmod 666 /data/local/tmp/titan2_hid.inj /data/misc/titan2/titan2_hid.inj 2>/dev/null || true

# 0.16.16: never arm HID while keyguard / CE locked (password screen residual).
# Keyboard must stay local until first unlock after boot/reboot.
user_unlocked() {
  case "$(getprop sys.user.0.ce_available 2>/dev/null | tr -d ' \r\n')" in
    1|true) return 0 ;;
  esac
  # Fallback: settings Secure only works once CE is available
  if settings get secure android_id >/dev/null 2>&1; then
    aid=$(settings get secure android_id 2>/dev/null | tr -d ' \r\n')
    case "$aid" in ""|null) return 1 ;; *) return 0 ;; esac
  fi
  return 1
}
clear_session_planes() {
  for d in \
    /data/user/0/com.titanus2.usbhid/files \
    /data/data/com.titanus2.usbhid/files \
    /sdcard/Android/data/com.titanus2.usbhid/files \
    /data/misc/titan2 \
    /data/local/tmp \
    "$CTRL"
  do
    [ -d "$d" ] || continue
    echo 0 > "$d/titan2_usb_hid_session" 2>/dev/null || true
    echo 0 > "$d/titan2_usb_hid_on" 2>/dev/null || true
    echo 0 > "$d/titan2_usb_hid_grab" 2>/dev/null || true
    echo 0 > "$d/titan2_usb_hid_keys" 2>/dev/null || true
  done
}

# Product default: do NOT resume HID after boot (lab ADB / automation).
# Opt-in: setprop persist.titanus2.hid_resume 1 — still blocked until unlocked.
hid_resume=$(getprop persist.titanus2.hid_resume 2>/dev/null | tr -d ' \r\n')
if [ "$hid_resume" != "1" ] || ! user_unlocked; then
  app_sess=0
  clear_session_planes
fi

if [ "$app_sess" = "1" ] && [ "$hid_resume" = "1" ] && user_unlocked; then
  log "service start mod=$MODDIR pid=$$ — resume app session (hid_resume=1 unlocked)"
else
  # Idle boot: clear OS-plane session leftovers; force mtp,adb
  clear_session_planes
  case "$(getprop persist.sys.usb.config)" in
    titan_hid|none|"") setprop persist.sys.usb.config mtp,adb 2>/dev/null || true ;;
  esac
  case "$(getprop sys.usb.config)" in
    titan_hid)
      if [ -x "$EN" ]; then
        sh "$EN" off >>"$LOG" 2>&1 || true
      else
        setprop sys.usb.config mtp,adb 2>/dev/null || true
      fi
      ;;
  esac
  if ! user_unlocked; then
    log "service start mod=$MODDIR pid=$$ (idle; locked — no HID until unlock)"
  else
    log "service start mod=$MODDIR pid=$$ (idle; no HID resume; USB mtp,adb)"
  fi
fi

last_on=""
last_sess=""
last_mouse=""
last_grab=""
last_keys=""
last_usb=""
last_bt=""
last_host=""
last_lip=""
fail_streak=0
loop_n=0
last_fail_reset=0
last_so_awake_s=0

# FB-HID-3: keep matrix/pad + HID gadget alive while display is blank.
# PARTIAL wake in the app alone does not stop USB autosuspend / input PM.
ensure_screen_off_hid_awake() {
  so=$(bool01 "$(read_ctrl titan2_usb_hid_screen_off)")
  [ "$so" = "1" ] || return 0
  se=$(bool01 "$(read_ctrl titan2_usb_hid_session)")
  [ "$se" = "1" ] || return 0
  # Input / i2c nodes (best-effort; missing paths are fine)
  for n in \
    /sys/devices/platform/soc/11e01000.i2c/i2c-6/6-0058/power/control \
    /sys/class/input/event7/device/power/control \
    /sys/devices/platform/soc/11c22000.i2c/i2c-2/2-0020/power/control \
    /sys/class/input/event6/device/power/control \
    /sys/bus/i2c/devices/6-0058/power/control \
    /sys/bus/i2c/devices/2-0020/power/control
  do
    [ -e "$n" ] || continue
    cur=$(cat "$n" 2>/dev/null | tr -d ' \r\n')
    [ "$cur" = "on" ] && continue
    echo on >"$n" 2>/dev/null || true
  done
  # UDC: prefer on while session wants USB HID with screen blank
  usb=$(bool01 "$(read_ctrl titan2_usb_hid_usb)")
  if [ "$usb" = "1" ]; then
    for n in /sys/class/udc/*/device/power/control /sys/class/udc/*/power/control; do
      [ -e "$n" ] || continue
      cur=$(cat "$n" 2>/dev/null | tr -d ' \r\n')
      [ "$cur" = "on" ] && continue
      echo on >"$n" 2>/dev/null || true
    done
    # If functions dropped while blanked, re-arm without full session churn
    if ! hid_ready 2>/dev/null; then
      if [ -x "$EN" ]; then
        log "screen_off awake — re-enable HID gadget"
        sh "$EN" on >>"$LOG" 2>&1 || true
      fi
    fi
  fi
}

while true; do
  loop_n=$((loop_n + 1))
  sess=$(bool01 "$(read_ctrl titan2_usb_hid_session)")
  # 0.16.16: never arm while CE locked (password/keyguard after reboot residual).
  if [ "$sess" = "1" ] && ! user_unlocked; then
    if [ "${_logged_locked:-0}" != "1" ]; then
      log "session requested but user locked — holding HID off"
      _logged_locked=1
    fi
    sess=0
    echo 0 > "$CTRL/titan2_usb_hid_session" 2>/dev/null || true
    echo 0 > "$CTRL/titan2_usb_hid_on" 2>/dev/null || true
  else
    _logged_locked=0
  fi
  # 0.16.18 cube-load-park: under load≥8 with session off, only edge-wait on
  # session plane (skip full mouse/grab/keys/host/dual-bridge thrash). Start
  # exclusive still lands within HEAT_INTERVAL_S.
  HEAT_PARK=0
  li=$(load_1m_int)
  case "$li" in ''|*[!0-9]*) li=0;; esac
  if [ "$li" -ge "$HEAT_LOAD_GE" ] 2>/dev/null; then HEAT_PARK=1; fi
  if [ "$HEAT_PARK" -eq 1 ] && [ "$sess" = "0" ]; then
    if [ "${_logged_heat_park:-0}" != "1" ]; then
      log "cube-load-park load=$li sess=0 interval=${HEAT_INTERVAL_S}s"
      _logged_heat_park=1
    fi
    # Keep last_* coherent so session edge after park still runs changed=1 path.
    last_sess=0
    sleep "$HEAT_INTERVAL_S"
    continue
  fi
  _logged_heat_park=0
  # Screen-off HID: keep input + gadget powered (every ~2s while session live)
  if [ "$sess" = "1" ]; then
    now_s=$(date +%s 2>/dev/null || echo 0)
    elapsed_so=$((now_s - ${last_so_awake_s:-0}))
    if [ "$elapsed_so" -ge 2 ] 2>/dev/null; then
      ensure_screen_off_hid_awake
      last_so_awake_s=$now_s
    fi
  fi
  # Empty plane → 0 (safe). App setSession always writes explicit 0/1; empty
  # used to default grab/keys/mouse=1 and exclusive-grab TitanKey on glitch.
  mouse=$(read_ctrl titan2_usb_hid_mouse)
  case "$mouse" in "") mouse=0;; *) mouse=$(bool01 "$mouse");; esac
  grab=$(read_ctrl titan2_usb_hid_grab)
  case "$grab" in "") grab=0;; *) grab=$(bool01 "$grab");; esac
  keys=$(read_ctrl titan2_usb_hid_keys)
  case "$keys" in "") keys=0;; *) keys=$(bool01 "$keys");; esac
  usb=$(read_ctrl titan2_usb_hid_usb)
  case "$usb" in "") usb=1;; *) usb=$(bool01 "$usb");; esac
  bt=$(read_ctrl titan2_usb_hid_bt)
  case "$bt" in "") bt=0;; *) bt=$(bool01 "$bt");; esac
  # USB gadget only when session + USB transport
  on=0
  [ "$sess" = "1" ] && [ "$usb" = "1" ] && on=1
  # Bridge when session + transport (Type soft keys=mouse=grab=0 still needs sock).
  want_bridge=0
  if [ "$sess" = "1" ] && { [ "$usb" = "1" ] || [ "$bt" = "1" ]; }; then
    want_bridge=1
  fi
  want_hw=0
  [ "$bt" = "1" ] && want_hw=1
  # Host cable state (independent of control files). Plug/unplug must
  # re-apply grab policy — exclusive with no host = dead keyboard.
  host=0
  usb_host_linked && host=1

  # Share only: pause host keys+pad while a phone editor/search is focused.
  # Exclusive (grab=1): host owns TitanKey — never local_input (dual-type /
  # dead host KB when IME focus glitches on launcher/search).
  # Root path — app-only detection missed launcher AppsSearchContainerLayout.
  if [ "$want_bridge" = "1" ]; then
    if [ "$grab" = "1" ]; then
      if [ "${last_lip:-}" != "0" ]; then
        write_local_input 0
        last_lip=0
      fi
    else
      update_local_input_pause
    fi
  elif [ -n "${last_lip:-}" ] && [ "$last_lip" != "0" ]; then
    write_local_input 0
    last_lip=0
  fi

  # Decay fail_streak every ~60s so a temporary USB glitch is not permanent
  if [ "$fail_streak" -gt 0 ] && [ $((loop_n - last_fail_reset)) -ge 120 ]; then
    log "fail_streak decay from $fail_streak"
    fail_streak=0
    last_fail_reset=$loop_n
  fi

  changed=0
  [ "$on" = "$last_on" ] || changed=1
  [ "$sess" = "$last_sess" ] || changed=1
  [ "$mouse" = "$last_mouse" ] || changed=1
  [ "$grab" = "$last_grab" ] || changed=1
  [ "$keys" = "$last_keys" ] || changed=1
  [ "$usb" = "$last_usb" ] || changed=1
  [ "$bt" = "$last_bt" ] || changed=1
  # host plug alone must NOT thrash USB re-enum (unstable cable feel)
  host_changed=0
  [ "$host" = "$last_host" ] || host_changed=1

  bridge_alive=0
  if [ -f "$BPIDF" ]; then
    kill -0 "$(cat "$BPIDF" 2>/dev/null)" 2>/dev/null && bridge_alive=1
  fi

  if [ "$changed" = "1" ]; then
    log "state on=$on sess=$sess usb=$usb bt=$bt mouse=$mouse grab=$grab keys=$keys host=$host"
    if [ "$want_bridge" != "1" ]; then
      stop_bridge
      stop_hid_touchpadd_if_pad_off
      # Always detach pure HID when session leaves USB path. last_on alone
      # missed stuck gadgets (cfg prop mtp,adb while host still sees HID only).
      if [ "$last_on" = "1" ] || [ -e /dev/hidg0 ] || [ -e /dev/hidg1 ]; then
        sh "$EN" off >>"$LOG" 2>&1 || true
        # Residual pure-HID after thrash — force mtp,adb rebuild
        if [ -e /dev/hidg0 ] || [ -e /dev/hidg1 ]; then
          log "HID residual after off — force_restore"
          sh "$EN" force_restore >>"$LOG" 2>&1 || true
        fi
      fi
      fail_streak=0
    else
      # Gadget flip only when USB on/off changes (not grab/keys chatter)
      gadget_flip=0
      [ "$on" = "$last_on" ] || gadget_flip=1
      if [ "$on" = "1" ]; then
        if [ "$gadget_flip" = "1" ] || ! hid_ready; then
          if ensure_hid_on; then
            fail_streak=0
          else
            fail_streak=$((fail_streak + 1))
            log "enable fail streak=$fail_streak"
          fi
        fi
        if hid_ready; then
          # Avoid kill/restart if bridge already healthy with same USB mode.
          # keys plane is hot-read by hid_bridge (keys_host_pause) — do NOT
          # kill bridge on keys 1↔0 alone (typing disconnect thrash).
          need_restart=1
          if [ "$bridge_alive" = "1" ] && [ "$gadget_flip" != "1" ] \
              && [ "$mouse" = "$last_mouse" ] && [ "$grab" = "$last_grab" ] \
              && [ "$bt" = "$last_bt" ]; then
            if [ "$keys" = "$last_keys" ]; then
              need_restart=0
            else
              if [ "$last_keys" = "1" ] || [ "$mouse" = "1" ] || [ "$grab" = "1" ]; then
                need_restart=0
                log "keys $last_keys→$keys hot (no bridge kill)"
              fi
            fi
          fi
          if [ "$need_restart" = "1" ]; then
            keys_cli=$keys
            if [ "$keys" = "0" ] && { [ "$mouse" = "1" ] || [ "$grab" = "1" ]; }; then
              keys_cli=1
            fi
            start_bridge "$grab" "$mouse" 1 "$want_hw" "$keys_cli" || true
          else
            log "keep bridge (opts unchanged)"
          fi
        else
          log "session on but hid not ready"
          if [ "$bt" = "1" ]; then
            start_bridge "$grab" "$mouse" 0 1 "$keys" || true
          fi
        fi
      else
        # BT-only: bridge first, defer gadget teardown (stability + speed)
        start_bridge "$grab" "$mouse" 0 "$want_hw" "$keys" || true
        if [ "$last_on" = "1" ]; then
          log "usb→bt: defer EN off (bridge already up)"
          ( sh "$EN" off >>"$LOG" 2>&1 || true ) &
        fi
      fi
    fi
    last_on=$on
    last_sess=$sess
    last_mouse=$mouse
    last_grab=$grab
    last_keys=$keys
    last_usb=$usb
    last_bt=$bt
    last_host=$host
  elif [ "$host_changed" = "1" ] && [ "$want_bridge" = "1" ]; then
    # Cable plug/unplug: do not restart bridge (USB re-enum = lag/disconnect).
    # Screen-off UDC blips (host 1→0→1) must NOT pad_regrab — that unplugs the
    # virt mouse to Android mid-session. Only regrab on real host *arrive*.
    so_ok=$(bool01 "$(read_ctrl titan2_usb_hid_screen_off)")
    log "host $last_host→$host — keep bridge (no re-enum thrash) so=$so_ok"
    if [ "$host" = "1" ] && [ "$last_host" = "0" ] && [ "$so_ok" != "1" ]; then
      printf '1' >"$CTRL/titan2_pad_regrab" 2>/dev/null || true
      printf '1' >/data/local/tmp/titan2_pad_regrab 2>/dev/null || true
    fi
    last_host=$host
  elif [ "$want_bridge" = "1" ]; then
    if [ "$on" = "1" ]; then
      if hid_ready; then
        if [ "$mouse" = "1" ]; then
          ensure_touchpadd_health
        fi
        bridge_alive=0
        if [ -f "$BPIDF" ]; then
          kill -0 "$(cat "$BPIDF" 2>/dev/null)" 2>/dev/null && bridge_alive=1
        fi
        if [ "$bridge_alive" != "1" ]; then
          start_bridge "$grab" "$mouse" 1 "$want_hw" "$keys" || true
        fi
      elif [ "$bt" = "1" ]; then
        bridge_alive=0
        if [ -f "$BPIDF" ]; then
          kill -0 "$(cat "$BPIDF" 2>/dev/null)" 2>/dev/null && bridge_alive=1
        fi
        if [ "$bridge_alive" != "1" ]; then
          start_bridge "$grab" "$mouse" 0 1 "$keys" || true
        fi
      else
        # USB wanted but gadget incomplete — slow re-arm (max 3, not 8×spin)
        if [ "$fail_streak" -lt 3 ] && [ $((loop_n % 25)) -eq 0 ]; then
          log "hid incomplete — slow re-enable"
          if ensure_hid_on; then
            fail_streak=0
            start_bridge "$grab" "$mouse" 1 "$want_hw" "$keys" || true
          else
            fail_streak=$((fail_streak + 1))
          fi
        fi
      fi
    else
      # BT-only keep-alive
      bridge_alive=0
      if [ -f "$BPIDF" ]; then
        kill -0 "$(cat "$BPIDF" 2>/dev/null)" 2>/dev/null && bridge_alive=1
      fi
      if [ "$bridge_alive" != "1" ]; then
        start_bridge "$grab" "$mouse" 0 "$want_hw" "$keys" || true
      fi
      if [ "$mouse" = "1" ]; then
        ensure_touchpadd_health
      fi
    fi
  fi

  # Pad mode change while session live: regrab. Epoch-only bumps are debounced
  # hard — HID prepareDriverPad used to spam epoch and cause virt mouse reopen
  # + host USB reconnect every few seconds.
  if [ "$want_bridge" = "1" ] && [ "$mouse" = "1" ]; then
    cur_pad=$(pad_mode_now)
    cur_epoch=$(read_ctrl titan2_pad_epoch)
    case "$cur_epoch" in ''|*[!0-9]*) cur_epoch=0;; esac
    now_s=$(date +%s 2>/dev/null || echo 0)
    if [ -z "${last_pad_mode:-}" ]; then
      last_pad_mode=$cur_pad
      last_pad_epoch=$cur_epoch
      last_pad_regrab_s=$now_s
    elif [ "$cur_pad" != "$last_pad_mode" ]; then
      # Real mode switch (off/trackpad/mouse) — always honor
      log "pad mode change $last_pad_mode→$cur_pad — re-ensure + regrab"
      ensure_touchpadd || true
      printf '1' >"$CTRL/titan2_pad_regrab" 2>/dev/null || true
      printf '1' >/data/local/tmp/titan2_pad_regrab 2>/dev/null || true
      chmod 666 "$CTRL/titan2_pad_regrab" /data/local/tmp/titan2_pad_regrab 2>/dev/null || true
      last_pad_mode=$cur_pad
      last_pad_epoch=$cur_epoch
      last_pad_regrab_s=$now_s
    elif [ "$cur_epoch" != "$last_pad_epoch" ]; then
      # Epoch-only: at most once per 3s, and only if bridge mouse path unhealthy
      last_pad_epoch=$cur_epoch
      elapsed=$((now_s - ${last_pad_regrab_s:-0}))
      if [ "$elapsed" -ge 3 ] 2>/dev/null; then
        if ! pidof titan2-touchpadd >/dev/null 2>&1; then
          log "pad epoch=$cur_epoch touchpadd dead — re-ensure (debounced ${elapsed}s)"
          ensure_touchpadd || true
          printf '1' >"$CTRL/titan2_pad_regrab" 2>/dev/null || true
          printf '1' >/data/local/tmp/titan2_pad_regrab 2>/dev/null || true
          last_pad_regrab_s=$now_s
        else
          log "pad epoch=$cur_epoch ignore (bridge ok, debounce)"
        fi
      fi
    fi
  fi

  # Dual-bridge residual (swap thrash / dual service): keep BPIDF owner only.
  # Prune orphans — never killall+restart here (cool lab heat).
  # 0.16.4: TERM → ~200ms → KILL hung orphan (bare -9 left sticky host mods).
  # 0.16.11: also re-prune peer service trees mid-loop (install race residual).
  # 0.16.12: peer count via /proc — never ps -A under heat.
  # 0.16.15: count is root-only (no self-subshell dual); grep -a match only.
  if [ $((loop_n % 12)) -eq 0 ]; then
    n_svc=`_foreach_peer_svc count 2>/dev/null` || n_svc=0
    case "$n_svc" in ''|*[!0-9]*) n_svc=0 ;; esac
    if [ "$n_svc" -gt 0 ] 2>/dev/null; then
      _kill_peer_svc
      _prune_orphan_svc_roots
      log "pruned dual usb_hid service peers n=$n_svc keep=$$ (0.16.15)"
    fi
    n_br=$(pidof hid_bridge 2>/dev/null | wc -w | tr -d ' ')
    case "$n_br" in ''|*[!0-9]*) n_br=0 ;; esac
    if [ "$n_br" -gt 1 ] 2>/dev/null; then
      keep=$(cat "$BPIDF" 2>/dev/null | tr -d ' \r\n')
      if [ -z "$keep" ] || ! kill -0 "$keep" 2>/dev/null; then
        keep=$(pidof hid_bridge 2>/dev/null | awk '{print $1; exit}')
      fi
      for p in $(pidof hid_bridge 2>/dev/null); do
        [ -n "$p" ] || continue
        [ -n "$keep" ] && [ "$p" = "$keep" ] && continue
        kill "$p" 2>/dev/null || true
        _opi=0
        while [ "$_opi" -lt 20 ]; do
          kill -0 "$p" 2>/dev/null || break
          sleep 0.01
          _opi=$((_opi + 1))
        done
        if kill -0 "$p" 2>/dev/null; then
          kill -9 "$p" 2>/dev/null || true
        fi
      done
      log "pruned dual hid_bridge keep=${keep:-?} was_n=$n_br (graceful orphan)"
    fi
  fi

  # Instant plane reaction: short poll when live OR session edge pending.
  # (0.4s idle made pad mode / Start feel multi-second; use 0.05s when plane hot.)
  # Cool session under load already continued above (0.16.18 cube-load-park).
  if [ "$want_bridge" = "1" ] || [ "$changed" = "1" ]; then
    sleep 0.05
  else
    sleep 0.15
  fi
done
