#!/system/bin/sh
# titan2-remote-adb — single state machine for Remote TCP ADB
#
# DESIRE (SoT for what human wants):
#   /data/misc/titan2/remote_adb.desire   →  "on" | "off"
#
# STATUS (SoT for UI — only this file, write-once per transition):
#   /data/local/tmp/remote_adb.status
#   also mirrored to titan2_wireless_adb_status (legacy readers)
#
# PAIR (ephemeral while PIN session open):
#   /data/local/tmp/remote_adb.pair      →  "PIN host:port" or empty
#   /data/local/tmp/titan2_adb_pair_*    →  legacy paths kept in sync
#
# Commands: on [port] | off | pair [port] | pair_cancel | apply | status | version
#
# Rules (no logic gaps):
#  1) desire is written BEFORE any adbd thrash
#  2) apply() makes reality match desire (listen or not)
#  3) adb_bootstrap / other peels NEVER call off unless desire=off
#  4) one adbd bounce max per apply (ctl.restart; stop/start fallback once)
#  5) USB composite always re-pinned with adb after bounce
#  6) pair is orthogonal: cancel does not force desire off
#  7) desire=off ALWAYS tears TCP down (delete hid_tcp_keep; never skip)
#
export PATH=/system/bin:/system/xbin:/vendor/bin:$PATH
T2=/data/misc/titan2
ST=/data/local/tmp
DESIRE_FILE=$T2/remote_adb.desire
WANT_LEGACY=$T2/wireless_adb_wanted
STATUS_FILE=$ST/remote_adb.status
STATUS_LEGACY=$ST/titan2_wireless_adb_status
STATUS_LEGACY2=$T2/titan2_wireless_adb_status
PAIR_FILE=$ST/remote_adb.pair
PAIR_PIN=$ST/titan2_adb_pair_pin
PAIR_HOST=$ST/titan2_adb_pair_host
PAIR_STATE=$ST/titan2_adb_pair_state
PAIR_CMD=$ST/titan2_adb_pair_cmd
PORT_FILE=$T2/remote_adb_port
PORT_TMP=$ST/remote_adb_port
LOCK=$ST/titan2_remote_adb.lock
VER=3.3-off-wins

log() {
  mkdir -p "$ST" 2>/dev/null || true
  { echo "remote-adb: $*" >>"$ST/titan2_pad_agent.log"; } 2>/dev/null || true
}

_lock() {
  mkdir -p "$ST" 2>/dev/null || true
  exec 8>"$LOCK" 2>/dev/null || return 0
  if command -v flock >/dev/null 2>&1; then
    flock 8 2>/dev/null || true
  fi
}

_write() {
  # $1=path $2=body
  mkdir -p "$(dirname "$1")" 2>/dev/null || true
  { echo "$2" >"$1"; } 2>/dev/null || true
  chmod 666 "$1" 2>/dev/null || true
}

set_status() {
  # single-line status for UI
  _write "$STATUS_FILE" "$1"
  _write "$STATUS_LEGACY" "$1"
  _write "$STATUS_LEGACY2" "$1"
}

set_desire() {
  # on|off
  mkdir -p "$T2" 2>/dev/null || true
  _write "$DESIRE_FILE" "$1"
  if [ "$1" = "on" ]; then
    _write "$WANT_LEGACY" "on"
  else
    rm -f "$WANT_LEGACY" 2>/dev/null || true
  fi
}

get_desire() {
  if [ -f "$DESIRE_FILE" ]; then
    tr -d '\r\n ' <"$DESIRE_FILE" 2>/dev/null
  elif [ -f "$WANT_LEGACY" ]; then
    echo on
  else
    echo off
  fi
}

port_norm() {
  p="$1"
  case "$p" in ''|*[!0-9]*) p=5555 ;; esac
  if [ "$p" -lt 1024 ] 2>/dev/null || [ "$p" -gt 65535 ] 2>/dev/null; then p=5555; fi
  echo "$p"
}

read_port() {
  p=
  [ -f "$PORT_FILE" ] && p=`tr -d '\r\n ' <"$PORT_FILE" 2>/dev/null`
  [ -z "$p" ] && [ -f "$PORT_TMP" ] && p=`tr -d '\r\n ' <"$PORT_TMP" 2>/dev/null`
  port_norm "${p:-5555}"
}

write_port() {
  p=`port_norm "$1"`
  _write "$PORT_FILE" "$p"
  _write "$PORT_TMP" "$p"
  echo "$p"
}

best_ip() {
  v=$(ip -4 -o addr show up 2>/dev/null | awk '/ tailscale|ts-/{print $4}' | cut -d/ -f1 | head -1)
  [ -n "$v" ] && { echo "$v"; return 0; }
  v=$(ip -4 -o addr show up 2>/dev/null | awk '{print $4}' | cut -d/ -f1 | grep -E '^100\.' | head -1)
  [ -n "$v" ] && { echo "$v"; return 0; }
  for ifc in wlan0 wlan1 eth0; do
    v=$(ip -4 -o addr show "$ifc" up 2>/dev/null | awk '{print $4}' | cut -d/ -f1 | head -1)
    [ -n "$v" ] && { echo "$v"; return 0; }
  done
  def_if=$(ip route 2>/dev/null | awk '/^default/{print $5; exit}')
  if [ -n "$def_if" ]; then
    v=$(ip -4 -o addr show "$def_if" 2>/dev/null | awk '{print $4}' | cut -d/ -f1 | head -1)
    [ -n "$v" ] && [ "$v" != "127.0.0.1" ] && { echo "$v"; return 0; }
  fi
  v=$(ip -4 -o addr show up 2>/dev/null | awk '!/ lo / && $4 !~ /^127\./ && $4 !~ /^169\.254\./ {print $4; exit}' | cut -d/ -f1)
  [ -n "$v" ] && { echo "$v"; return 0; }
  echo "127.0.0.1"
}

port_listen() {
  p="$1"
  [ -n "$p" ] || return 1
  ss -ltn 2>/dev/null | grep -E "[:*]${p}([[:space:]]|$)" | grep -q LISTEN \
    || ss -ltn 2>/dev/null | grep -qE "[:.]${p}[[:space:]]"
}

hid_usb_live() {
  u=$(getprop sys.usb.config 2>/dev/null | tr -d '\r')
  case "$u" in titan_hid) return 0 ;; esac
  [ -e /dev/hidg0 ] && return 0
  return 1
}

pin_usb() {
  u=$(getprop sys.usb.config 2>/dev/null | tr -d '\r')
  # HID owns the gadget. Wireless ADB is TCP-only — do not rewrite USB.
  if hid_usb_live; then
    echo "$u"
    return 0
  fi
  [ -n "$u" ] || u=$(getprop persist.sys.usb.config 2>/dev/null | tr -d '\r')
  [ -n "$u" ] || u="mtp,adb"
  case "$u" in *adb*) ;; *) u="mtp,adb" ;; esac
  setprop persist.sys.usb.config "$u" 2>/dev/null || true
  setprop sys.usb.config "$u" 2>/dev/null || true
  settings put global adb_enabled 1 2>/dev/null || true
  settings put global development_settings_enabled 1 2>/dev/null || true
  echo "$u"
}

set_tcp_prop() {
  # $1 port or -1
  if [ "$1" = "-1" ] || [ -z "$1" ]; then
    setprop service.adb.tcp.port -1 2>/dev/null || true
    setprop persist.adb.tcp.port "" 2>/dev/null || true
    command -v resetprop >/dev/null 2>&1 && {
      resetprop service.adb.tcp.port -1 2>/dev/null || true
      resetprop --delete persist.adb.tcp.port 2>/dev/null || true
    }
  else
    setprop service.adb.tcp.port "$1" 2>/dev/null || true
    setprop persist.adb.tcp.port "$1" 2>/dev/null || true
    command -v resetprop >/dev/null 2>&1 && {
      resetprop service.adb.tcp.port "$1" 2>/dev/null || true
      resetprop persist.adb.tcp.port "$1" 2>/dev/null || true
    }
  fi
}

wait_adbd() {
  i=0
  while [ "$i" -lt 25 ]; do
    svc=$(getprop init.svc.adbd 2>/dev/null | tr -d '\r')
    pid=`pidof adbd 2>/dev/null | awk '{print $1}'`
    [ "$svc" = "running" ] && [ -n "$pid" ] && return 0
    sleep 0.12
    i=$((i + 1))
  done
  return 1
}

# Bounce adbd so it re-reads TCP prop. ONE primary + ONE fallback.
# $1 = port or -1
bounce_tcp() {
  want="$1"
  usb=`pin_usb`
  set_tcp_prop "$want"
  setprop sys.usb.config "$usb" 2>/dev/null || true

  setprop ctl.restart adbd 2>/dev/null || true
  wait_adbd || true
  set_tcp_prop "$want"
  setprop sys.usb.config "$usb" 2>/dev/null || true
  settings put global adb_enabled 1 2>/dev/null || true

  j=0
  while [ "$j" -lt 16 ]; do
    if [ "$want" = "-1" ]; then
      port_listen 5555 || return 0
    else
      port_listen "$want" && return 0
    fi
    sleep 0.15
    j=$((j + 1))
  done

  # fallback once
  log "ctl.restart insufficient want=$want — ctl.stop/start"
  set_tcp_prop "$want"
  setprop ctl.stop adbd 2>/dev/null || true
  sleep 0.3
  set_tcp_prop "$want"
  setprop sys.usb.config "$usb" 2>/dev/null || true
  setprop ctl.start adbd 2>/dev/null || true
  wait_adbd || true
  set_tcp_prop "$want"
  setprop sys.usb.config "$usb" 2>/dev/null || true
  settings put global adb_enabled 1 2>/dev/null || true
  sleep 0.4

  if [ "$want" = "-1" ]; then
    port_listen 5555 && { log "OFF still listening"; return 1; }
    return 0
  fi
  port_listen "$want" || { log "ON not listening :$want"; return 1; }
  return 0
}

clear_pair_files() {
  rm -f "$PAIR_FILE" "$PAIR_PIN" "$PAIR_HOST" "$PAIR_CMD" 2>/dev/null || true
  _write "$PAIR_STATE" "idle"
}

# Make reality match desire.
# Never clobber an active PIN session (waiting|starting) — that stole Cancel from the UI.
apply() {
  d=`get_desire`
  p=`read_port`
  pst=$(tr -d '\r\n' <"$PAIR_STATE" 2>/dev/null)
  case "$pst" in
    waiting|starting)
      # Pairing in progress: do not rewrite status / do not clear pair files.
      return 0
      ;;
  esac
  case "$d" in
    on)
      if port_listen "$p"; then
        ip=`best_ip`
        set_status "on ${ip}:${p}"
        return 0
      fi
      set_status "busy on"
      if bounce_tcp "$p"; then
        ip=`best_ip`
        set_status "on ${ip}:${p}"
        log "apply ON ok ${ip}:${p}"
        return 0
      fi
      set_status "error on-no-listen"
      log "apply ON fail"
      return 1
      ;;
    *)
      # desire=off is SoT. Leftover hid_tcp_keep or a live exclusive HID
      # session must not keep :5555 after the human asked for OFF.
      rm -f /data/misc/titan2/hid_tcp_keep 2>/dev/null || true
      if hid_usb_live; then
        log "apply OFF while HID live — dropping TCP anyway (desire=off)"
      fi
      clear_pair_files
      service call adb 11 >/dev/null 2>&1 || true
      settings put global adb_wifi_enabled 0 2>/dev/null || true
      if ! port_listen 5555; then
        cur=$(getprop service.adb.tcp.port 2>/dev/null | tr -d '\r\n')
        case "$cur" in ""|"-1"|"0")
          set_tcp_prop -1
          set_status "off"
          return 0
          ;;
        esac
      fi
      set_status "busy off"
      if bounce_tcp -1; then
        set_status "off"
        log "apply OFF ok"
        return 0
      fi
      set_status "error off-still-listening"
      log "apply OFF fail"
      return 1
      ;;
  esac
}

cmd_on() {
  _lock
  [ -n "$1" ] && write_port "$1" >/dev/null
  set_desire on
  # clear stale pair UI
  clear_pair_files
  apply
}

cmd_off() {
  _lock
  set_desire off
  clear_pair_files
  apply
}

cmd_pair() {
  _lock
  [ -n "$1" ] && write_port "$1" >/dev/null
  p=`read_port`
  settings put global development_settings_enabled 1 2>/dev/null || true
  settings put global adb_enabled 1 2>/dev/null || true
  settings put global adb_wifi_enabled 1 2>/dev/null || true
  setprop persist.adb.tls_server.enable 1 2>/dev/null || true

  set_status "busy pair"
  _write "$PAIR_STATE" "starting"
  logcat -c 2>/dev/null || true
  # IAdbManager.enablePairingByPairingCode = tx 8
  service call adb 8 >/dev/null 2>&1 || {
    set_status "error pair-service"
    _write "$PAIR_STATE" "fail"
    return 1
  }

  pin=; pport=; i=0
  while [ "$i" -lt 40 ]; do
    # Cancel wins mid-PIN fetch (cancel runs unlocked first)
    pst=$(tr -d '\r\n' <"$PAIR_STATE" 2>/dev/null)
    case "$pst" in cancelled|idle)
      log "pair aborted during pin wait state=$pst"
      return 0
      ;;
    esac
    lg=`logcat -d 2>/dev/null | tail -n 400`
    pin=`echo "$lg" | grep -oE 'updateUIPairCode: [0-9]{6}' | tail -1 | awk '{print $2}'`
    pport=`echo "$lg" | grep -oE 'Pairing server started on port [0-9]+' | tail -1 | awk '{print $NF}'`
    [ -n "$pin" ] && [ -n "$pport" ] && break
    sleep 0.15
    i=$((i + 1))
  done

  pst=$(tr -d '\r\n' <"$PAIR_STATE" 2>/dev/null)
  case "$pst" in cancelled|idle)
    log "pair aborted after pin wait state=$pst"
    return 0
    ;;
  esac

  ip=`best_ip`
  if [ -z "$pin" ] || [ -z "$pport" ]; then
    set_status "error pair-no-pin"
    _write "$PAIR_STATE" "fail"
    log "pair no pin/port"
    return 1
  fi

  _write "$PAIR_PIN" "$pin"
  _write "$PAIR_HOST" "${ip}:${pport}"
  _write "$PAIR_CMD" "adb pair ${ip}:${pport} ${pin}"
  _write "$PAIR_FILE" "${pin} ${ip}:${pport}"
  # Final cancel check before publishing PIN (cancel must not lose the race)
  pst=$(tr -d '\r\n' <"$PAIR_STATE" 2>/dev/null)
  case "$pst" in cancelled|idle)
    log "pair aborted before status publish state=$pst"
    service call adb 11 >/dev/null 2>&1 || true
    return 0
    ;;
  esac
  _write "$PAIR_STATE" "waiting"
  # status written ONCE (no 1Hz rewrite — that flickered the UI)
  set_status "pairing ${pin} ${ip}:${pport}"
  log "pair show PIN=$pin host=${ip}:${pport}"

  # Durable wait — must survive parent shell exit (pad-agent / su -c).
  # Stinky turd (3.0): desire defaults off, so "abort if desire=off" killed the
  # waiter immediately and left status stuck on "pairing …".
  # Stinky turd 2: bare ( )& dies when peel's shell exits before adb pair.
  PAIR_WAIT_SH=$ST/titan2_remote_adb_pair_wait.sh
  cat >"$PAIR_WAIT_SH" <<'WAITEOF'
#!/system/bin/sh
export PATH=/system/bin:/system/xbin:/vendor/bin:$PATH
ST=/data/local/tmp
T2=/data/misc/titan2
PAIR_STATE=$ST/titan2_adb_pair_state
STATUS_FILE=$ST/remote_adb.status
DESIRE_FILE=$T2/remote_adb.desire
WANT_LEGACY=$T2/wireless_adb_wanted
PORT="${1:-5555}"
log() { echo "remote-adb-pair-wait: $*" >>$ST/titan2_pad_agent.log 2>/dev/null; }
_write() { mkdir -p "$(dirname "$1")" 2>/dev/null; echo "$2" >"$1" 2>/dev/null; chmod 666 "$1" 2>/dev/null; }
set_status() {
  _write "$STATUS_FILE" "$1"
  _write "$ST/titan2_wireless_adb_status" "$1"
  _write "$T2/titan2_wireless_adb_status" "$1"
}
set_desire_on() {
  _write "$DESIRE_FILE" "on"
  _write "$WANT_LEGACY" "on"
}
best_ip() {
  v=$(ip -4 -o addr show up 2>/dev/null | awk '/ tailscale|ts-/{print $4}' | cut -d/ -f1 | head -1)
  [ -n "$v" ] && { echo "$v"; return; }
  v=$(ip -4 -o addr show up 2>/dev/null | awk '{print $4}' | cut -d/ -f1 | grep -E '^100\.' | head -1)
  [ -n "$v" ] && { echo "$v"; return; }
  ip -4 -o addr show up 2>/dev/null | awk '!/ lo / && $4 !~ /^127\./ {print $4; exit}' | cut -d/ -f1
  [ -n "$(ip -4 -o addr show up 2>/dev/null | head -1)" ] || echo 127.0.0.1
}
port_listen() { ss -ltn 2>/dev/null | grep -qE "[:*]${1}([[:space:]]|$)"; }
# Call system remote-adb on for TCP (reuse bounce)
arm_tcp() {
  if [ -x /system/bin/titan2-remote-adb.sh ]; then
    /system/bin/sh /system/bin/titan2-remote-adb.sh on "$PORT" >/dev/null 2>&1 && return 0
  fi
  if [ -f /data/local/tmp/titan2-remote-adb.sh ]; then
    /system/bin/sh /data/local/tmp/titan2-remote-adb.sh on "$PORT" >/dev/null 2>&1 && return 0
  fi
  setprop service.adb.tcp.port "$PORT"
  setprop persist.adb.tcp.port "$PORT"
  setprop ctl.restart adbd
  sleep 0.8
}
deadline=$(( $(date +%s) + 180 ))
log "wait start port=$PORT"
while [ "$(date +%s)" -lt "$deadline" ]; do
  st=$(tr -d '\r\n' <"$PAIR_STATE" 2>/dev/null)
  case "$st" in
    cancelled|idle|fail|timeout|paired) log "exit state=$st"; exit 0 ;;
  esac
  # Explicit cancel only via pair_state — NOT desire=off (pair starts with desire off)
  lg=$(logcat -d 2>/dev/null | tail -n 500)
  # Re-check cancel after slow logcat read (human Cancel must win)
  st=$(tr -d '\r\n' <"$PAIR_STATE" 2>/dev/null)
  case "$st" in
    cancelled|idle) log "exit state=$st (post-logcat)"; exit 0 ;;
  esac
  if echo "$lg" | grep -qE 'Pairing succeeded|pair success|AdbPairingThread: Pairing succeeded'; then
    st=$(tr -d '\r\n' <"$PAIR_STATE" 2>/dev/null)
    case "$st" in cancelled|idle) log "exit state=$st (pre-success)"; exit 0 ;; esac
    log "detected Pairing succeeded"
    _write "$PAIR_STATE" "paired"
    set_desire_on
    # Status FIRST so UI leaves pairing immediately
    ip2=$(best_ip); [ -n "$ip2" ] || ip2=127.0.0.1
    set_status "on ${ip2}:${PORT}"
    arm_tcp || true
    ip2=$(best_ip); [ -n "$ip2" ] || ip2=127.0.0.1
    set_status "on ${ip2}:${PORT}"
    log "pair success → on ${ip2}:${PORT}"
    exit 0
  fi
  if echo "$lg" | grep -qE 'Pairing failed|Unable to start pairing'; then
    st=$(tr -d '\r\n' <"$PAIR_STATE" 2>/dev/null)
    case "$st" in cancelled|idle) log "exit state=$st (pre-fail)"; exit 0 ;; esac
    _write "$PAIR_STATE" "fail"
    set_status "error pair-failed"
    log "pair failed"
    exit 1
  fi
  sleep 0.4
done
st=$(tr -d '\r\n' <"$PAIR_STATE" 2>/dev/null)
case "$st" in cancelled|idle) log "exit state=$st (timeout-path)"; exit 0 ;; esac
_write "$PAIR_STATE" "timeout"
service call adb 11 >/dev/null 2>&1 || true
set_status "error pair-timeout"
log "pair timeout"
exit 1
WAITEOF
  chmod 755 "$PAIR_WAIT_SH" 2>/dev/null || true
  # Kill prior waiter
  for d in /proc/[0-9]*; do
    c=$(tr '\0' ' ' <"$d/cmdline" 2>/dev/null) || continue
    case "$c" in *titan2_remote_adb_pair_wait*) kill "${d#/proc/}" 2>/dev/null ;; esac
  done
  nohup /system/bin/sh "$PAIR_WAIT_SH" "$p" >>"$ST/titan2_pad_agent.log" 2>&1 &
  echo $! >"$ST/titan2_remote_adb_pair_wait.pid" 2>/dev/null || true
  log "pair waiter pid=$(cat $ST/titan2_remote_adb_pair_wait.pid 2>/dev/null)"
  return 0
}

cmd_pair_cancel() {
  # INSTANT UI exit — before lock (pair may hold flock while reading PIN).
  # Order: state → status → kill server → lock cleanup.
  _write "$PAIR_STATE" "cancelled"
  d=`get_desire`
  p=`read_port`
  ip=`best_ip`
  if [ "$d" = "on" ]; then
    set_status "on ${ip}:${p}"
  else
    set_status "off"
  fi
  # Stop TLS pairing server hard (disablePairing = tx 11)
  service call adb 11 >/dev/null 2>&1 || true
  service call adb 11 >/dev/null 2>&1 || true
  # stop durable waiter (pid file + scan)
  if [ -f "$ST/titan2_remote_adb_pair_wait.pid" ]; then
    kill `tr -d ' \r\n' <"$ST/titan2_remote_adb_pair_wait.pid"` 2>/dev/null || true
    rm -f "$ST/titan2_remote_adb_pair_wait.pid" 2>/dev/null || true
  fi
  for d in /proc/[0-9]*; do
    c=$(tr '\0' ' ' <"$d/cmdline" 2>/dev/null) || continue
    case "$c" in *titan2_remote_adb_pair_wait*) kill "${d#/proc/}" 2>/dev/null ;; esac
  done
  rm -f "$PAIR_FILE" "$PAIR_PIN" "$PAIR_HOST" "$PAIR_CMD" 2>/dev/null || true
  # Serialize any late pair() still holding the lock
  _lock
  # Re-assert after lock (pair may have rewritten status while we waited)
  _write "$PAIR_STATE" "cancelled"
  service call adb 11 >/dev/null 2>&1 || true
  rm -f "$PAIR_FILE" "$PAIR_PIN" "$PAIR_HOST" "$PAIR_CMD" 2>/dev/null || true
  d=`get_desire`
  p=`read_port`
  ip=`best_ip`
  if [ "$d" = "on" ]; then
    set_status "on ${ip}:${p}"
  else
    set_status "off"
  fi
  log "pair cancelled desire=$d"
  return 0
}

cmd_status() {
  echo "desire=$(get_desire)"
  echo "status=$(cat "$STATUS_FILE" 2>/dev/null)"
  echo "tcp=$(getprop service.adb.tcp.port)"
  echo "pair=$(cat "$PAIR_STATE" 2>/dev/null)"
  ss -ltn 2>/dev/null | grep -E '5555' || echo "listen=none"
}

# --- entry ---
cmd="${1:-}"
case "$cmd" in
  version|-v|--version) echo "$VER"; exit 0 ;;
  on|remote_adb_on|arm_wireless_adb_trusted|enable_wireless_adb_trusted|remote_adb_arm_trusted)
    cmd_on "${2:-}"
    ;;
  off|remote_adb_off|disable_wireless_adb|wireless_adb_off)
    cmd_off
    ;;
  pair|remote_adb_pair|pair_remote_adb_trusted|pair_wireless_adb_trusted)
    cmd_pair "${2:-}"
    ;;
  pair_cancel|cancel_pair|cancel_pair_remote_adb|remote_adb_pair_cancel)
    cmd_pair_cancel
    ;;
  apply|enforce|enforce_wireless_adb_policy|wireless_adb_policy)
    _lock
    apply
    ;;
  status) cmd_status ;;
  *)
    echo "usage: titan2-remote-adb.sh on|off|pair|pair_cancel|apply|status|version" >&2
    exit 2
    ;;
esac
