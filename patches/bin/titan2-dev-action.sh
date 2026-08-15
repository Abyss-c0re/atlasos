#!/system/bin/sh
# titan2-dev-action — OPTIMIZE Phase 3 peel from pad-agent tower
# SoT: docs/project/OPTIMIZE_SOURCE_PRODUCT.md
# Invoked by pad-agent: <action> [args]
# Agent keeps: reload_agent (exec) + heal_b1 (B1 KL ensure_*).
# Never downloads third-party binaries.
export PATH=/system/bin:/system/xbin:/vendor/bin:$PATH
T2=/data/misc/titan2
ST=/data/local/tmp
DEV_VER=3.0-remote-sm

log() {
  mkdir -p "$ST" 2>/dev/null || true
  { echo "dev-action: $*" >>"$ST/titan2_pad_agent.log"; } 2>/dev/null || true
  { echo "pad-agent dev_action $*" >"$ST/titan2_agent_status"; } 2>/dev/null || true
  chmod 666 "$ST/titan2_agent_status" 2>/dev/null || true
}

_cmdline_has() {
  _ch_pid="$1"; _ch_pat="$2"
  [ -n "$_ch_pid" ] && [ -n "$_ch_pat" ] || return 1
  [ -r "/proc/$_ch_pid/cmdline" ] || return 1
  grep -a -F -q "$_ch_pat" "/proc/$_ch_pid/cmdline" 2>/dev/null
}

_graceful_kill_bridge_pid() {
  _gkp="$1"
  [ -n "$_gkp" ] || return 0
  kill "$_gkp" 2>/dev/null || true
  _gki=0
  while [ "$_gki" -lt 20 ]; do
    kill -0 "$_gkp" 2>/dev/null || return 0
    sleep 0.01
    _gki=$((_gki + 1))
  done
  kill -9 "$_gkp" 2>/dev/null || true
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

_any_sh_cmdline_match() {
  _pat="$1"
  [ -n "$_pat" ] || return 1
  for p in `pgrep -f "$_pat" 2>/dev/null`; do
    case "$p" in ''|*[!0-9]*) continue ;; esac
    comm=`cat "/proc/$p/comm" 2>/dev/null` || continue
    [ "$comm" = "sh" ] || continue
    _cmdline_has "$p" "$_pat" && return 0
  done
  return 1
}

# Human desired state (not "listening right now"):
#   present  → Remote ADB should stay ON (re-assert after adbd thrash)
#   missing  → must stay OFF (boot + enforce clear TCP)
# KEEP_DATA used to leave persist.adb.tcp.port=5555 as a permanent backdoor.
WIRELESS_ADB_WANT=/data/misc/titan2/wireless_adb_wanted
# TCP listen port (default 5555). Controls writes this; arm/pair read it.
REMOTE_ADB_PORT_FILE=/data/misc/titan2/remote_adb_port
# Serialize arm/disarm so UI + pad-agent + AuthPrompt cannot thrash on/off.
REMOTE_ADB_LOCK=/data/local/tmp/titan2_remote_adb.lock

write_wireless_adb_status() {
  st="$1"
  for d in /data/local/tmp /data/misc/titan2; do
    mkdir -p "$d" 2>/dev/null || true
    { echo "$st" >"$d/titan2_wireless_adb_status"; } 2>/dev/null || true
    chmod 666 "$d/titan2_wireless_adb_status" 2>/dev/null || true
  done
}

# Clamp port to usable non-privileged range (or 5555 default).
normalize_adb_port() {
  p="$1"
  case "$p" in ''|*[!0-9]*) p=5555 ;; esac
  if [ "$p" -lt 1024 ] 2>/dev/null || [ "$p" -gt 65535 ] 2>/dev/null; then
    p=5555
  fi
  echo "$p"
}

read_remote_adb_port() {
  p=
  if [ -f "$REMOTE_ADB_PORT_FILE" ]; then
    p=`tr -d '\r\n ' <"$REMOTE_ADB_PORT_FILE" 2>/dev/null`
  fi
  if [ -z "$p" ] && [ -f /data/local/tmp/remote_adb_port ]; then
    p=`tr -d '\r\n ' </data/local/tmp/remote_adb_port 2>/dev/null`
  fi
  normalize_adb_port "${p:-5555}"
}

write_remote_adb_port() {
  p=`normalize_adb_port "$1"`
  mkdir -p /data/misc/titan2 /data/local/tmp 2>/dev/null || true
  echo "$p" >"$REMOTE_ADB_PORT_FILE" 2>/dev/null || true
  echo "$p" >/data/local/tmp/remote_adb_port 2>/dev/null || true
  chmod 666 "$REMOTE_ADB_PORT_FILE" /data/local/tmp/remote_adb_port 2>/dev/null || true
  echo "$p"
}

remote_adb_lock() {
  mkdir -p /data/local/tmp 2>/dev/null || true
  exec 8>"$REMOTE_ADB_LOCK" 2>/dev/null || return 0
  if command -v flock >/dev/null 2>&1; then
    flock 8 2>/dev/null || true
  fi
}

# Prefer Tailscale / VPN / LTE / Wi‑Fi — NOT wifi-only. Human arms when they want
# remote adb (e.g. Tailscale over LTE); never auto from boot services.
best_ipv4() {
  # Prefer reachable uplinks that a host can actually use for adb connect.
  # 1) Tailscale CGNAT 100.x (only if iface up)
  v=$(ip -4 -o addr show up 2>/dev/null | awk '/ tailscale|ts-/{print $4}' | cut -d/ -f1 | head -1)
  if [ -n "$v" ]; then echo "$v"; return 0; fi
  v=$(ip -4 -o addr show up 2>/dev/null | awk '{print $4}' | cut -d/ -f1 | grep -E '^100\.' | head -1)
  if [ -n "$v" ]; then echo "$v"; return 0; fi
  # 2) Wi‑Fi
  for ifc in wlan0 wlan1 eth0; do
    v=$(ip -4 -o addr show "$ifc" up 2>/dev/null | awk '{print $4}' | cut -d/ -f1 | head -1)
    if [ -n "$v" ]; then echo "$v"; return 0; fi
  done
  # 3) Cellular / USB net (ccmni / rmnet) — only if default route uses it
  def_if=$(ip route 2>/dev/null | awk '/^default/{print $5; exit}')
  if [ -n "$def_if" ]; then
    v=$(ip -4 -o addr show "$def_if" 2>/dev/null | awk '{print $4}' | cut -d/ -f1 | head -1)
    if [ -n "$v" ] && [ "$v" != "127.0.0.1" ]; then echo "$v"; return 0; fi
  fi
  # 4) Any non-loopback non-link-local UP
  ip -4 -o addr show up 2>/dev/null | awk '!/ lo / && $4 !~ /^127\./ && $4 !~ /^169\.254\./ {print $4; exit}' | cut -d/ -f1
}

# Atlas Authentication Agent (product OS service) — biometrics for Remote ADB.
find_atlas_auth() {
  for a in \
    /system/bin/atlas-auth \
    /product/bin/atlas-auth \
    /data/data/com.titanus2.atlas/files/bin/atlas-auth \
    /data/user/0/com.titanus2.atlas/files/bin/atlas-auth
  do
    [ -x "$a" ] && echo "$a" && return 0
  done
  return 1
}

# Ensure Atlas FGS is up so AuthPrompt/FileObserver can run (Wi‑Fi off OK).
wake_atlas_auth_agent() {
  am start-foreground-service -n com.titanus2.atlas/.AtlasSessionService \
    -a com.titanus2.atlas.ENSURE_AUTH_AGENT 2>/dev/null \
    || am startservice -n com.titanus2.atlas/.AtlasSessionService \
      -a com.titanus2.atlas.ENSURE_AUTH_AGENT 2>/dev/null \
    || true
}

# Remote TCP ADB (Tailscale/LTE/any iface). Not stock "Wireless debugging".
# Product law: biometrics before open; optional port; pair = bio + wipe adb_keys.
# Never auto-arm from boot without wireless_adb_wanted.
arm_wireless_adb() {
  if [ -n "$1" ]; then
    port=`write_remote_adb_port "$1"`
  else
    port=`read_remote_adb_port`
  fi
  settings put global development_settings_enabled 1 2>/dev/null || true
  settings put global adb_enabled 1 2>/dev/null || true

  wake_atlas_auth_agent
  sleep 0.4
  AUTH=`find_atlas_auth` || {
    log "remote_adb: atlas-auth missing — cannot gate with Authentication Agent"
    write_wireless_adb_status "fail no-auth-agent"
    return 1
  }
  export ATLAS_FORCE_AUTH=1
  # Clear ticket so host agent cannot silent-grant Remote ADB
  rm -f /data/data/com.titanus2.atlas/files/auth/ticket \
    /data/user/0/com.titanus2.atlas/files/auth/ticket 2>/dev/null || true
  ip_hint=$(best_ipv4)
  [ -n "$ip_hint" ] || ip_hint="?"
  if ! "$AUTH" request "Remote ADB · TCP :${port} · ${ip_hint} · biometrics"; then
    log "remote_adb: Authentication Agent denied/timeout"
    write_wireless_adb_status "fail auth-denied"
    return 1
  fi
  arm_wireless_adb_trusted "$port"
}

# Inner arm (caller holds lock if needed).
# Listens on ALL interfaces (0.0.0.0 / ::) — Wi‑Fi not required (TS / LTE / USB net / lo).
_arm_wireless_adb_body() {
  if [ -n "$1" ]; then
    port=`write_remote_adb_port "$1"`
  else
    port=`read_remote_adb_port`
  fi
  settings put global development_settings_enabled 1 2>/dev/null || true
  settings put global adb_enabled 1 2>/dev/null || true
  rm -f /data/local/tmp/titan2_dev_action /data/misc/titan2/titan2_dev_action 2>/dev/null || true
  settings put global titan2_dev_action "" 2>/dev/null || true
  mkdir -p /data/misc/titan2 2>/dev/null || true
  echo "on" >"$WIRELESS_ADB_WANT" 2>/dev/null || true
  chmod 644 "$WIRELESS_ADB_WANT" 2>/dev/null || true
  ip=$(best_ipv4)
  [ -n "$ip" ] || ip="127.0.0.1"
  write_wireless_adb_status "on ${ip}:${port}"
  _usb=$(getprop sys.usb.config 2>/dev/null | tr -d '\r')
  [ -n "$_usb" ] || _usb="mtp,adb"
  case "$_usb" in *adb*) ;; *) _usb="mtp,adb" ;; esac
  setprop persist.sys.usb.config "$_usb" 2>/dev/null || true
  setprop service.adb.tcp.port "$port" 2>/dev/null || true
  setprop persist.adb.tcp.port "$port" 2>/dev/null || true
  if command -v resetprop >/dev/null 2>&1; then
    resetprop service.adb.tcp.port "$port" 2>/dev/null || true
    resetprop persist.adb.tcp.port "$port" 2>/dev/null || true
  fi
  # Already listening on this port → no bounce (keep USB)
  if ss -ltn 2>/dev/null | grep -qE "[:.]${port} |:${port}\$"; then
    ip=$(best_ipv4)
    [ -n "$ip" ] || ip="127.0.0.1"
    write_wireless_adb_status "on ${ip}:${port} · also 127.0.0.1:${port}"
    log "remote_adb ON (already) ${ip}:${port}"
    return 0
  fi
  # Open TCP on all ifaces via ctl.restart (stop/start hangs on this stack)
  if ! _apply_tcp_port_via_adbd_restart "$port"; then
    write_wireless_adb_status "fail on-no-listen"
    log "remote_adb ON FAIL no listen :$port"
    return 1
  fi
  ip=$(best_ipv4)
  [ -n "$ip" ] || ip="127.0.0.1"
  write_wireless_adb_status "on ${ip}:${port} · also 127.0.0.1:${port}"
  log "remote_adb ON ${ip}:${port} tcp=$(getprop service.adb.tcp.port) listen=$(ss -ltn 2>/dev/null | grep -E \":${port}\" || echo none)"
  return 0
}

# Arm TCP after biometrics already succeeded in Atlas AuthPrompt (Controls path).
arm_wireless_adb_trusted() {
  remote_adb_lock
  _arm_wireless_adb_body "$1"
}

# Real Android 11+ wireless pairing (adb pair IP:PORT PIN) — not classic RSA.
# IAdbManager.enablePairingByPairingCode → 6-digit PIN + ephemeral pairing port.
# Status stays "pairing …" until host finishes adb pair (or timeout).
PAIR_PIN_FILE=/data/local/tmp/titan2_adb_pair_pin
PAIR_HOST_FILE=/data/local/tmp/titan2_adb_pair_host
PAIR_STATE_FILE=/data/local/tmp/titan2_adb_pair_state
PAIR_CMD_FILE=/data/local/tmp/titan2_adb_pair_cmd

write_pair_files() {
  # $1=state $2=pin $3=host(ip:port)
  mkdir -p /data/local/tmp /data/misc/titan2 2>/dev/null || true
  echo "$1" >"$PAIR_STATE_FILE" 2>/dev/null || true
  [ -n "${2-}" ] && echo "$2" >"$PAIR_PIN_FILE" 2>/dev/null || true
  [ -n "${3-}" ] && echo "$3" >"$PAIR_HOST_FILE" 2>/dev/null || true
  chmod 666 "$PAIR_STATE_FILE" "$PAIR_PIN_FILE" "$PAIR_HOST_FILE" 2>/dev/null || true
  for d in /data/local/tmp /data/misc/titan2; do
    cp -f "$PAIR_STATE_FILE" "$d/titan2_adb_pair_state" 2>/dev/null || true
    [ -f "$PAIR_PIN_FILE" ] && cp -f "$PAIR_PIN_FILE" "$d/titan2_adb_pair_pin" 2>/dev/null || true
    [ -f "$PAIR_HOST_FILE" ] && cp -f "$PAIR_HOST_FILE" "$d/titan2_adb_pair_host" 2>/dev/null || true
    chmod 666 "$d/titan2_adb_pair_state" "$d/titan2_adb_pair_pin" "$d/titan2_adb_pair_host" 2>/dev/null || true
  done
}

clear_pair_files() {
  rm -f "$PAIR_PIN_FILE" "$PAIR_HOST_FILE" "$PAIR_CMD_FILE" 2>/dev/null || true
  echo "idle" >"$PAIR_STATE_FILE" 2>/dev/null || true
  chmod 666 "$PAIR_STATE_FILE" 2>/dev/null || true
}

# Poll logcat for PIN + pairing port after enablePairingByPairingCode.
# PIN tag: AdbDebuggingManager "updateUIPairCode: NNNNNN"
# Port tag: system_server "Pairing server started on port N" (NOT AdbDebuggingManager)
# Never use random ss high ports — stale pair servers lie.
_read_pair_from_log() {
  # sets: _ppin _pport
  _ppin=
  _pport=
  _lg=`logcat -d 2>/dev/null | tail -n 400`
  _ppin=`echo "$_lg" | grep -oE 'updateUIPairCode: [0-9]{6}' | tail -1 | awk '{print $2}'`
  _pport=`echo "$_lg" | grep -oE 'Pairing server started on port [0-9]+' | tail -1 | awk '{print $NF}'`
}

_pair_wait_background() {
  # Args: pin host_ip pair_port connect_port
  _wpin="$1"; _wip="$2"; _wpp="$3"; _wcp="$4"
  _deadline=$(( $(date +%s) + 180 ))
  # Write status ONCE — rewriting every second made the Controls UI flicker
  write_wireless_adb_status "pairing PIN=${_wpin} ${_wip}:${_wpp} · adb pair ${_wip}:${_wpp} ${_wpin}"
  write_pair_files "waiting" "$_wpin" "${_wip}:${_wpp}"
  while [ "$(date +%s)" -lt "$_deadline" ]; do
    _pst=`tr -d '\r\n' <"$PAIR_STATE_FILE" 2>/dev/null`
    case "$_pst" in
      cancelled|cancel|idle|fail|timeout)
        log "remote_adb PAIR wait aborted (state=$_pst)"
        return 1
        ;;
    esac
    _st=`tr -d '\r\n' </data/local/tmp/titan2_wireless_adb_status 2>/dev/null`
    case "$_st" in
      off|off*) log "remote_adb PAIR wait aborted (status off)"; return 1 ;;
    esac
    _lg=`logcat -d 2>/dev/null | tail -n 400`
    if echo "$_lg" | grep -q 'Pairing succeeded'; then
      write_pair_files "paired" "$_wpin" "${_wip}:${_wpp}"
      write_wireless_adb_status "paired ${_wip} · adb connect ${_wip}:${_wcp}"
      _arm_wireless_adb_body "$_wcp"
      log "remote_adb PAIR success pin=$_wpin host=${_wip}:${_wpp}"
      return 0
    fi
    if echo "$_lg" | grep -q 'Pairing failed'; then
      write_pair_files "fail" "$_wpin" "${_wip}:${_wpp}"
      write_wireless_adb_status "fail pair-failed"
      log "remote_adb PAIR failed pin=$_wpin"
      return 1
    fi
    sleep 1
  done
  write_pair_files "timeout" "$_wpin" "${_wip}:${_wpp}"
  write_wireless_adb_status "fail pair-timeout · PIN was ${_wpin}"
  log "remote_adb PAIR timeout pin=$_wpin"
  service call adb 11 >/dev/null 2>&1 || true
  return 1
}

# Human cancel from Controls — stop pairing server, clear PIN UI state.
cancel_pair_remote_adb() {
  remote_adb_lock
  service call adb 11 >/dev/null 2>&1 || true
  write_pair_files "cancelled" "" ""
  rm -f "$PAIR_PIN_FILE" "$PAIR_HOST_FILE" "$PAIR_CMD_FILE" 2>/dev/null || true
  # Do not touch classic TCP want/on — cancel only ends the PIN session
  _st=`tr -d '\r\n' </data/local/tmp/titan2_wireless_adb_status 2>/dev/null`
  case "$_st" in
    pairing*|pair*)
      if [ -f "$WIRELESS_ADB_WANT" ]; then
        ip=$(best_ipv4)
        [ -n "$ip" ] || ip="127.0.0.1"
        p=`read_remote_adb_port`
        write_wireless_adb_status "on ${ip}:${p}"
      else
        write_wireless_adb_status "off"
      fi
      ;;
  esac
  log "remote_adb PAIR cancelled"
  return 0
}

# Pair a new host (bio already done in UI): real 6-digit PIN + wait for adb pair.
pair_remote_adb_trusted() {
  remote_adb_lock
  if [ -n "$1" ]; then
    cport=`write_remote_adb_port "$1"`
  else
    cport=`read_remote_adb_port`
  fi
  settings put global development_settings_enabled 1 2>/dev/null || true
  settings put global adb_enabled 1 2>/dev/null || true
  # Stock wireless debugging path (TLS). May no-op if no Wi‑Fi; pairing server
  # still starts on this GSI (proved 2026-08-10).
  settings put global adb_wifi_enabled 1 2>/dev/null || true
  setprop persist.adb.tls_server.enable 1 2>/dev/null || true
  if command -v resetprop >/dev/null 2>&1; then
    resetprop persist.adb.tls_server.enable 1 2>/dev/null || true
  fi

  write_pair_files "starting" "" ""
  write_wireless_adb_status "pairing starting…"
  logcat -c 2>/dev/null || true
  # IAdbManager.enablePairingByPairingCode = transaction 8 (AIDL order)
  service call adb 8 >/dev/null 2>&1 || {
    write_pair_files "fail" "" ""
    write_wireless_adb_status "fail pair-service"
    log "remote_adb PAIR: service call enablePairingByPairingCode failed"
    return 1
  }

  pin=; pport=
  i=0
  while [ "$i" -lt 40 ]; do
    _read_pair_from_log
    pin="$_ppin"; pport="$_pport"
    if [ -n "$pin" ] && [ -n "$pport" ]; then
      break
    fi
    sleep 0.15
    i=$((i + 1))
  done

  ip=$(best_ipv4)
  [ -n "$ip" ] || ip="?"
  if [ -z "$pin" ] || [ -z "$pport" ]; then
    write_pair_files "fail" "${pin:-}" "${ip}:${pport:-?}"
    write_wireless_adb_status "fail pair-no-pin (wifi/TLS?)"
    log "remote_adb PAIR: no PIN/port from log (pin=$pin port=$pport)"
    return 1
  fi

  echo "adb pair ${ip}:${pport} ${pin}" >"$PAIR_CMD_FILE" 2>/dev/null || true
  chmod 666 "$PAIR_CMD_FILE" 2>/dev/null || true
  write_pair_files "waiting" "$pin" "${ip}:${pport}"
  write_wireless_adb_status "pairing PIN=${pin} ${ip}:${pport} · adb pair ${ip}:${pport} ${pin}"
  log "remote_adb PAIR show PIN=${pin} host=${ip}:${pport} (wait up to 180s)"

  # Wait in background so pad-agent tick is not blocked; UI polls status.
  (
    _pair_wait_background "$pin" "$ip" "$pport" "$cport"
  ) >/dev/null 2>&1 &
  return 0
}

# True if adbd is listening for classic TCP ADB.
_tcp_adb_listening() {
  _p=$(getprop service.adb.tcp.port 2>/dev/null | tr -d '\r\n')
  case "$_p" in ""|"-1"|"0") ;; *)
    ss -ltn 2>/dev/null | grep -qE "[:.]${_p} |:${_p}\$" && return 0
    ;;
  esac
  ss -ltn 2>/dev/null | grep -qE '[:.]5555 |:5555$' && return 0
  return 1
}

_pin_usb_adb() {
  _usb=$(getprop sys.usb.config 2>/dev/null | tr -d '\r')
  [ -n "$_usb" ] || _usb=$(getprop persist.sys.usb.config 2>/dev/null | tr -d '\r')
  [ -n "$_usb" ] || _usb="mtp,adb"
  case "$_usb" in *adb*) ;; *) _usb="mtp,adb" ;; esac
  setprop persist.sys.usb.config "$_usb" 2>/dev/null || true
  setprop sys.usb.config "$_usb" 2>/dev/null || true
  settings put global adb_enabled 1 2>/dev/null || true
  settings put global development_settings_enabled 1 2>/dev/null || true
  echo "$_usb"
}

_set_tcp_port_prop() {
  # $1 = port or -1
  _tp="$1"
  if [ "$_tp" = "-1" ] || [ -z "$_tp" ]; then
    setprop service.adb.tcp.port -1 2>/dev/null || true
    setprop persist.adb.tcp.port "" 2>/dev/null || true
    if command -v resetprop >/dev/null 2>&1; then
      resetprop service.adb.tcp.port -1 2>/dev/null || true
      resetprop persist.adb.tcp.port "" 2>/dev/null || true
      resetprop --delete persist.adb.tcp.port 2>/dev/null || true
    fi
  else
    setprop service.adb.tcp.port "$_tp" 2>/dev/null || true
    setprop persist.adb.tcp.port "$_tp" 2>/dev/null || true
    if command -v resetprop >/dev/null 2>&1; then
      resetprop service.adb.tcp.port "$_tp" 2>/dev/null || true
      resetprop persist.adb.tcp.port "$_tp" 2>/dev/null || true
    fi
  fi
}

# True if port is open for TCP (any ss format: *:5555 0.0.0.0:5555 [::]:5555)
_port_is_listen() {
  _lp="$1"
  [ -n "$_lp" ] || return 1
  ss -ltn 2>/dev/null | grep -E "[:.]${_lp}([[:space:]]|$)" | grep -q LISTEN \
    || ss -ltn 2>/dev/null | grep -qE "[:*]${_lp}[[:space:]]"
}

_wait_adbd_running() {
  _i=0
  while [ "$_i" -lt 25 ]; do
    _svc=$(getprop init.svc.adbd 2>/dev/null | tr -d '\r')
    _npid=`pidof adbd 2>/dev/null | awk '{print $1}'`
    if [ "$_svc" = "running" ] && [ -n "$_npid" ]; then
      return 0
    fi
    sleep 0.12
    _i=$((_i + 1))
  done
  return 1
}

# $1 = desired port or -1. Success only if listen state matches.
_apply_tcp_port_via_adbd_restart() {
  _want_port="$1"
  [ -n "$_want_port" ] || _want_port=-1
  _usb=`_pin_usb_adb`

  _bounce_once() {
    _set_tcp_port_prop "$_want_port"
    setprop sys.usb.config "$_usb" 2>/dev/null || true
    setprop persist.sys.usb.config "$_usb" 2>/dev/null || true
    # Prefer ctl.restart — stop/start can hang under KSU
    setprop ctl.restart adbd 2>/dev/null || true
    _wait_adbd_running || true
    # Props again AFTER running (some builds drop them on gadget rebind)
    _set_tcp_port_prop "$_want_port"
    setprop sys.usb.config "$_usb" 2>/dev/null || true
    settings put global adb_enabled 1 2>/dev/null || true
    # If ON and still not listening, second restart now that props stuck
    if [ "$_want_port" != "-1" ]; then
      _j=0
      while [ "$_j" -lt 15 ]; do
        _port_is_listen "$_want_port" && return 0
        sleep 0.15
        _j=$((_j + 1))
      done
      log "adbd listen miss after restart — second ctl.restart :$_want_port"
      _set_tcp_port_prop "$_want_port"
      setprop ctl.restart adbd 2>/dev/null || true
      _wait_adbd_running || true
      _set_tcp_port_prop "$_want_port"
      setprop sys.usb.config "$_usb" 2>/dev/null || true
      _j=0
      while [ "$_j" -lt 15 ]; do
        _port_is_listen "$_want_port" && return 0
        sleep 0.15
        _j=$((_j + 1))
      done
      return 1
    fi
    # OFF: wait until not listening
    _j=0
    while [ "$_j" -lt 15 ]; do
      _port_is_listen 5555 || _tcp_adb_listening || return 0
      sleep 0.15
      _j=$((_j + 1))
    done
    # still listening?
    if _port_is_listen 5555 || _tcp_adb_listening; then
      return 1
    fi
    return 0
  }

  if _bounce_once; then
    log "adbd restart ok want=$_want_port pid=$(pidof adbd) listen=$(ss -ltn 2>/dev/null | grep -E '5555' || echo none)"
    return 0
  fi

  log "adbd ctl.restart failed want=$_want_port — ctl.stop/start once"
  _set_tcp_port_prop "$_want_port"
  setprop ctl.stop adbd 2>/dev/null || true
  sleep 0.35
  _set_tcp_port_prop "$_want_port"
  setprop sys.usb.config "$_usb" 2>/dev/null || true
  setprop ctl.start adbd 2>/dev/null || true
  _wait_adbd_running || true
  _set_tcp_port_prop "$_want_port"
  setprop sys.usb.config "$_usb" 2>/dev/null || true
  settings put global adb_enabled 1 2>/dev/null || true
  sleep 0.5

  if [ "$_want_port" = "-1" ]; then
    if _port_is_listen 5555 || _tcp_adb_listening; then
      log "adbd OFF FAIL still listening"
      return 1
    fi
  else
    if ! _port_is_listen "$_want_port"; then
      log "adbd ON FAIL not listening :$_want_port ss=$(ss -ltn 2>/dev/null | head -5)"
      return 1
    fi
  fi
  log "adbd stop/start ok want=$_want_port pid=$(pidof adbd)"
  return 0
}

# Back-compat name used by disarm
_restart_adbd_keep_usb() {
  _apply_tcp_port_via_adbd_restart -1
}

disarm_wireless_adb() {
  remote_adb_lock
  # 1) Desired OFF first — pad-agent / AuthPrompt / pair-wait must not re-arm
  rm -f "$WIRELESS_ADB_WANT" 2>/dev/null || true
  rm -f /data/local/tmp/titan2_remote_adb_grant 2>/dev/null || true
  rm -f /data/local/tmp/titan2_dev_action /data/misc/titan2/titan2_dev_action 2>/dev/null || true
  : > /data/local/tmp/titan2_dev_action 2>/dev/null || true
  : > /data/misc/titan2/titan2_dev_action 2>/dev/null || true
  settings put global titan2_dev_action "" 2>/dev/null || true
  write_wireless_adb_status "off"
  clear_pair_files

  # 2) Keep USB debugging enabled in settings (never turn global ADB off)
  settings put global development_settings_enabled 1 2>/dev/null || true
  settings put global adb_enabled 1 2>/dev/null || true

  # 3) Cancel wireless pairing / wifi-debug
  service call adb 11 >/dev/null 2>&1 || true
  settings put global adb_wifi_enabled 0 2>/dev/null || true
  setprop persist.adb.tls_server.enable 0 2>/dev/null || true
  if command -v resetprop >/dev/null 2>&1; then
    resetprop persist.adb.tls_server.enable 0 2>/dev/null || true
  fi

  # 4–5) Drop remote TCP for real: setprop alone does not unbind or drop clients.
  #     Always apply -1 via ONE adbd restart when listening OR prop still open.
  _pnow=$(getprop service.adb.tcp.port 2>/dev/null | tr -d '\r\n')
  _need=0
  case "$_pnow" in ""|"-1"|"0") ;; *) _need=1 ;; esac
  _tcp_adb_listening && _need=1
  ss -ltn 2>/dev/null | grep -qE '5555' && _need=1
  if [ "$_need" = "1" ]; then
    _apply_tcp_port_via_adbd_restart -1 || true
  else
    _set_tcp_port_prop -1
  fi

  # 6) Final truth
  _set_tcp_port_prop -1
  write_wireless_adb_status "off"
  if _tcp_adb_listening || ss -ltn 2>/dev/null | grep -qE '5555'; then
    log "remote_adb OFF FAIL still listening tcp=$(getprop service.adb.tcp.port)"
    write_wireless_adb_status "fail off-still-listening"
    return 1
  fi
  log "remote_adb OFF ok tcp=$(getprop service.adb.tcp.port) persist=$(getprop persist.adb.tcp.port) usb=$(getprop sys.usb.config) adb=$(settings get global adb_enabled 2>/dev/null)"
  return 0
}

# Policy: human desire is SoT (remote_adb.desire=on or wireless_adb_wanted).
#   desired ON   → Remote ADB should be listening (re-arm only if not)
#   desired OFF  → Remote ADB must not listen (disarm sticky TCP only)
# Never thrash: do not bounce when already correct.
enforce_wireless_adb_policy() {
  _desire=
  if [ -f /data/misc/titan2/remote_adb.desire ]; then
    _desire=$(tr -d '\r\n ' </data/misc/titan2/remote_adb.desire 2>/dev/null)
  elif [ -f "$WIRELESS_ADB_WANT" ]; then
    _desire=on
  fi
  if [ "$_desire" = "on" ]; then
    if _tcp_adb_listening; then
      # Already correct — leave USB/TCP alone
      return 0
    fi
    log "wireless_adb policy: desired ON but not listening — arm"
    arm_wireless_adb_trusted
    return 0
  fi
  # Desired OFF
  cur=$(getprop service.adb.tcp.port 2>/dev/null | tr -d '\r\n')
  per=$(getprop persist.adb.tcp.port 2>/dev/null | tr -d '\r\n')
  if ! _tcp_adb_listening; then
    case "$cur$per" in
      ""|"-1"|"0"|*-1*)
        write_wireless_adb_status "off"
        return 0
        ;;
    esac
  fi
  log "wireless_adb policy: desired OFF — clear sticky TCP (cur=$cur persist=$per)"
  disarm_wireless_adb
}

stage_bridge() {
  # Quiet cp product hid_bridge → /data/local/tmp (rootless B2 map+phone-nav).
  # Never swap_hid_stack / dual bridge / exclusive arm.
  _has_prod() {
    [ -x "$1" ] || return 1
    strings "$1" 2>/dev/null | grep -q 'specials layer in-bridge' || return 1
    strings "$1" 2>/dev/null | grep -q 'titan2-phone-nav'
  }
  if _has_prod /data/local/tmp/hid_bridge; then
    return 0
  fi
  _src=""
  for _c in \
    /data/adb/modules/titan2_usb_hid/hid_bridge \
    /system/etc/titan2_usb_hid/hid_bridge
  do
    if _has_prod "$_c"; then
      _src="$_c"
      break
    fi
  done
  [ -n "$_src" ] || return 0
  mkdir -p /data/local/tmp 2>/dev/null || true
  cp -f "$_src" /data/local/tmp/hid_bridge 2>/dev/null || return 0
  chmod 755 /data/local/tmp/hid_bridge 2>/dev/null || true
  log "stage product hid_bridge → tmp from $_src (2.189 rootless B2)"
}

act0="${1-}"
# Allow "action" or "action <nonce>"

# Dispatch Remote ADB to dedicated state-machine script (3.0 redesign).
_remote_adb_dispatch() {
  for _ra in \
    /data/local/tmp/titan2-remote-adb.sh \
    /system/bin/titan2-remote-adb.sh \
    /product/bin/titan2-remote-adb.sh
  do
    if [ -f "$_ra" ]; then
      /system/bin/sh "$_ra" "$@"
      return $?
    fi
  done
  log "remote-adb script missing — cannot $*"
  return 1
}

# Lab soft ADB bootstrap (2.199 peel from pad-agent boot).
# CRITICAL: never thrash sys.usb.config once adb is already in the composite.
# RSA authorize / cable re-plug often leaves config as plain "adb" briefly; rewriting
# mtp,adb restarts the gadget, tears down loop mounts, and Atlas hybrid ensure then
# auto-bootstraps → "hybrid image restarted" after USB authorize.
adb_bootstrap() {
  _adb_boot=$(getprop ro.titanus2.adb_bootstrap 2>/dev/null | tr -d '\r')
  _profile=$(getprop ro.titanus2.profile 2>/dev/null | tr -d '\r')
  _lab_adb=0
  case "$_adb_boot" in 1|true|on|yes) _lab_adb=1 ;; esac
  case "$_profile" in dev|lab_rootless) _lab_adb=1 ;; esac
  if [ "$_lab_adb" != "1" ]; then
    log "adb_bootstrap skipped (release/custom profile=$_profile boot=$_adb_boot)"
    return 0
  fi
  settings put global development_settings_enabled 1 2>/dev/null || true
  settings put global adb_enabled 1 2>/dev/null || true
  curusb=$(getprop sys.usb.config 2>/dev/null | tr -d '\r')
  case "$curusb" in
    *adb*)
      # Already exposing adb (adb | mtp,adb | adb,mtp | …). Do NOT setprop —
      # gadget restart kills /data/local/atlas-hybrid loop+overlay.
      return 0
      ;;
    *)
      setprop persist.sys.usb.config mtp,adb 2>/dev/null || true
      setprop sys.usb.config mtp,adb 2>/dev/null || true
      log "adb_bootstrap profile=$_profile boot=$_adb_boot usb=$(getprop sys.usb.config) (was ${curusb:-empty})"
      ;;
  esac
}


case "$act0" in
  ""|version|-v|--version)
    if [ "$act0" = "version" ] || [ "$act0" = "-v" ] || [ "$act0" = "--version" ]; then
      echo "$DEV_VER"; exit 0
    fi
    log "usage: titan2-dev-action.sh <action>"
    exit 0
    ;;
esac
act0=$(echo "$act0" | awk '{print $1}')
log "dev_action=$act0"

  case "$act0" in
    stage_bridge|stage_hid_bridge)
      stage_bridge
      ;;
    adb_bootstrap|lab_adb_bootstrap)
      # USB-only. Never touch Remote ADB desire/apply here.
      adb_bootstrap
      ;;
    enforce_wireless_adb_policy|wireless_adb_policy)
      # Reconcile desire→reality only (new state machine).
      _remote_adb_dispatch apply
      ;;
    enterd_reload|atlas_enterd_reload|reload_enterd)
      # Rootless tip: sole atlas-enterd = tip ELF (multi-listen abstract+TCP).
      # Dual system+tip left Deb ENTER on abstract (old) + elevate on TCP — broken.
      TIP=/data/local/tmp/atlas-enterd-tip
      SYS=/system/bin/atlas-enterd
      ED=$SYS
      [ -x "$TIP" ] && ED=$TIP
      if [ ! -x "$ED" ]; then
        log "enterd_reload missing bin tip=$TIP sys=$SYS"
        exit 1
      fi
      setprop ctl.stop atlas-enterd 2>/dev/null || true
      # multi-pass kill: system service reparents / watch restarts under heat
      _k=0
      while [ "$_k" -lt 8 ]; do
        for p in `pidof atlas-enterd 2>/dev/null`; do
          kill -9 "$p" 2>/dev/null || true
        done
        # tip path basename may be atlas-enterd-tip
        for p in `pidof atlas-enterd-tip 2>/dev/null`; do
          kill -9 "$p" 2>/dev/null || true
        done
        sleep 0.15
        _k=$((_k + 1))
      done
      sleep 0.2
      "$ED" >>/data/local/tmp/atlas-enterd.log 2>&1 &
      sleep 0.5
      # prune any system enterd that raced back (keep tip only)
      for p in `pidof atlas-enterd 2>/dev/null`; do
        exe=`readlink /proc/$p/exe 2>/dev/null` || continue
        case "$exe" in
          *atlas-enterd-tip*) ;;
          *) kill -9 "$p" 2>/dev/null || true ;;
        esac
      done
      if pidof atlas-enterd >/dev/null 2>&1 || pidof atlas-enterd-tip >/dev/null 2>&1; then
        log "enterd_reload OK ed=$ED"
        echo "enterd_reload ok ed=$ED" >"$ST/titan2_enterd_reload_status" 2>/dev/null || true
      else
        log "enterd_reload FAIL ed=$ED"
        echo "enterd_reload fail ed=$ED" >"$ST/titan2_enterd_reload_status" 2>/dev/null || true
        setprop ctl.start atlas-enterd 2>/dev/null || true
        exit 1
      fi
      chmod 666 "$ST/titan2_enterd_reload_status" 2>/dev/null || true
      ;;
    atlas_auth_ticket|plant_auth_ticket)
      # Root heal: ensure Atlas auth/ dir + short ticket (lab prove / enterd elevate).
      # Production path: atlas-auth request → AuthPrompt → writeResult ticket.
      TTL=90
      case "$2" in ''|*[!0-9]*) ;; *) TTL=$2 ;; esac
      [ "$TTL" -gt 0 ] 2>/dev/null || TTL=90
      [ "$TTL" -gt 600 ] 2>/dev/null && TTL=600
      EXP=`date +%s`
      EXP=$((EXP + TTL))
      for home in /data/user/0/com.titanus2.atlas/files /data/data/com.titanus2.atlas/files; do
        [ -d "$home" ] || continue
        mkdir -p "$home/auth" 2>/dev/null || true
        printf '%s %s\n' "$EXP" "$TTL" >"$home/auth/ticket" 2>/dev/null || true
        chmod 644 "$home/auth" "$home/auth/ticket" 2>/dev/null || true
        # app-owned
        _u=`stat -c %u "$home" 2>/dev/null` || _u=
        _g=`stat -c %g "$home" 2>/dev/null` || _g=
        case "$_u" in ''|*[!0-9]*) ;; *)
          chown "$_u:$_g" "$home/auth" "$home/auth/ticket" 2>/dev/null || true
          ;;
        esac
        log "auth ticket planted home=$home exp=$EXP ttl=$TTL"
      done
      # world-readable mirror (SELinux blocks shell from app CE auth/)
      printf '%s %s\n' "$EXP" "$TTL" >"$ST/atlas_auth.ticket" 2>/dev/null || true
      chmod 644 "$ST/atlas_auth.ticket" 2>/dev/null || true
      echo "ticket exp=$EXP ttl=$TTL" >"$ST/titan2_auth_ticket_status" 2>/dev/null || true
      chmod 666 "$ST/titan2_auth_ticket_status" 2>/dev/null || true
      ;;
    atlas_sudo_tip|stage_atlas_sudo)
      # Root land elevate client into Atlas app bin + hybrid PATH shims.
      SRC=/data/local/tmp/atlas-sudo-tip
      [ -x "$SRC" ] || SRC=/data/local/tmp/atlas-sudo
      if [ ! -x "$SRC" ]; then
        log "atlas_sudo_tip missing $SRC"
        exit 1
      fi
      for home in /data/user/0/com.titanus2.atlas/files /data/data/com.titanus2.atlas/files; do
        [ -d "$home" ] || continue
        mkdir -p "$home/bin" 2>/dev/null || true
        cp -f "$SRC" "$home/bin/atlas-sudo" 2>/dev/null || true
        cp -f "$SRC" "$home/bin/sudo" 2>/dev/null || true
        cp -f "$SRC" "$home/bin/su" 2>/dev/null || true
        chmod 755 "$home/bin/atlas-sudo" "$home/bin/sudo" "$home/bin/su" 2>/dev/null || true
        _u=`stat -c %u "$home" 2>/dev/null` || _u=
        _g=`stat -c %g "$home" 2>/dev/null` || _g=
        case "$_u" in ''|*[!0-9]*) ;; *)
          chown "$_u:$_g" "$home/bin/atlas-sudo" "$home/bin/sudo" "$home/bin/su" 2>/dev/null || true
          ;;
        esac
        log "atlas_sudo_tip staged $home/bin"
      done
      # hybrid merge PATH gates (root can write upper)
      MERGE=/data/local/atlas-hybrid/merge
      if [ -d "$MERGE/usr/local/bin" ]; then
        for name in sudo su; do
          cat >"$MERGE/usr/local/bin/$name" <<EOF
#!/bin/sh
# Atlas elevate gate → agent client → enterd elevate (no KernelSU)
exec /data/local/tmp/atlas-sudo-tip "\$@"
EOF
          chmod 755 "$MERGE/usr/local/bin/$name" 2>/dev/null || true
        done
        log "atlas_sudo_tip merge shims usr/local/bin"
      fi
      echo "atlas_sudo_tip ok" >"$ST/titan2_atlas_sudo_tip_status" 2>/dev/null || true
      chmod 666 "$ST/titan2_atlas_sudo_tip_status" 2>/dev/null || true
      ;;
    enable_adb)
      settings put global development_settings_enabled 1 2>/dev/null || true
      settings put global adb_enabled 1 2>/dev/null || true
      # Keep ADB across reboots / USB re-plug
      setprop persist.sys.usb.config adb 2>/dev/null || true
      setprop sys.usb.config adb 2>/dev/null || true
      # Common composite that still exposes adb
      setprop sys.usb.config mtp,adb 2>/dev/null || true
      setprop persist.sys.usb.config mtp,adb 2>/dev/null || true
      log "adb enabled dev=$(settings get global development_settings_enabled 2>/dev/null) adb=$(settings get global adb_enabled 2>/dev/null) usb=$(getprop sys.usb.config)"
      ;;
    enable_wireless_adb|wireless_adb_on)
      _remote_adb_dispatch on "${2:-}"
      ;;
    arm_wireless_adb_trusted|enable_wireless_adb_trusted|remote_adb_arm_trusted|remote_adb_on)
      _remote_adb_dispatch on "${2:-}"
      ;;
    pair_remote_adb_trusted|remote_adb_pair_trusted|pair_wireless_adb_trusted|remote_adb_pair)
      _remote_adb_dispatch pair "${2:-}"
      ;;
    cancel_pair_remote_adb|remote_adb_pair_cancel|pair_cancel)
      _remote_adb_dispatch pair_cancel
      ;;
    set_remote_adb_port|remote_adb_port)
      p=`write_remote_adb_port "${2:-5555}"`
      log "remote_adb port=$p"
      echo "$p"
      ;;
    disable_wireless_adb|wireless_adb_off|remote_adb_off)
      _remote_adb_dispatch off
      ;;
    prune_hid_bridges|dual_bridge_prune)
      # 2.09/2.16: cool lab — orphan-only prune (keep BPIDF / first). No remount.
      # TERM-wait each orphan so empty-report lands (bare -9 sticky host mods).
      n_br=`pidof hid_bridge 2>/dev/null | wc -w | tr -d ' '`
      case "$n_br" in ''|*[!0-9]*) n_br=0 ;; esac
      if [ "$n_br" -le 1 ] 2>/dev/null; then
        log "prune_hid_bridges skip (n=$n_br)"
      else
        keep=`cat /data/local/tmp/t2uhid_bridge.pid 2>/dev/null | tr -d ' \r\n'`
        if [ -z "$keep" ] || ! kill -0 "$keep" 2>/dev/null; then
          keep=`pidof hid_bridge 2>/dev/null | awk '{print $1; exit}'`
        fi
        for p in `pidof hid_bridge 2>/dev/null`; do
          [ -n "$p" ] || continue
          [ -n "$keep" ] && [ "$p" = "$keep" ] && continue
          _graceful_kill_bridge_pid "$p"
        done
        n_after=`pidof hid_bridge 2>/dev/null | wc -w | tr -d ' '`
        log "prune_hid_bridges keep=${keep:-?} was=$n_br now=${n_after:-0}"
      fi
      ;;
    swap_hid_stack|hid_bridge_swap|install_hid_bridge)
      # Lab: land staged hid_bridge + service over system paths (bind mount)
      # so exclusive Sym map works without Magisk reinstall / reflash.
      # Single owner only: never start a second service alongside init.
      # 2.07/2.08: exclusive map SoT = service path (mapped tmp preferred).
      # Skip kill/restart when tmp OR system already has in-bridge map and at
      # most one bridge — cool install must not thrash on every land.
      # 2.09: map live + dual bridges → prune orphans only (no full swap).
      SYS_BR=/system/etc/titan2_usb_hid/hid_bridge
      BR_SRC=/data/local/tmp/hid_bridge
      [ -x /data/local/tmp/t2uhid_hid_bridge ] && BR_SRC=/data/local/tmp/t2uhid_hid_bridge
      map_sys=0
      map_tmp=0
      strings "$SYS_BR" 2>/dev/null | grep -q 'specials layer in-bridge' && map_sys=1
      [ -x "$BR_SRC" ] && strings "$BR_SRC" 2>/dev/null | grep -q 'specials layer in-bridge' && map_tmp=1
      n_br=`pidof hid_bridge 2>/dev/null | wc -w | tr -d ' '`
      case "$n_br" in ''|*[!0-9]*) n_br=0 ;; esac
      # Dual with map live: prune orphans first so cool install stays skip path.
      # 2.16: TERM-wait orphans (same empty-report SoT as full swap kill).
      if { [ "$map_tmp" = "1" ] || [ "$map_sys" = "1" ]; } && [ "$n_br" -gt 1 ] 2>/dev/null; then
        keep=`cat /data/local/tmp/t2uhid_bridge.pid 2>/dev/null | tr -d ' \r\n'`
        if [ -z "$keep" ] || ! kill -0 "$keep" 2>/dev/null; then
          keep=`pidof hid_bridge 2>/dev/null | awk '{print $1; exit}'`
        fi
        for p in `pidof hid_bridge 2>/dev/null`; do
          [ -n "$p" ] || continue
          [ -n "$keep" ] && [ "$p" = "$keep" ] && continue
          _graceful_kill_bridge_pid "$p"
        done
        n_br=`pidof hid_bridge 2>/dev/null | wc -w | tr -d ' '`
        case "$n_br" in ''|*[!0-9]*) n_br=0 ;; esac
        log "swap_hid_stack dual prune keep=${keep:-?} now=$n_br (map live)"
      fi
      md5_sys=`md5sum "$SYS_BR" 2>/dev/null | awk '{print $1}'`
      md5_tmp=`md5sum "$BR_SRC" 2>/dev/null | awk '{print $1}'`
      swap_skip=0
      # Mapped tmp is product SoT (service always prefers it) — never killall.
      if [ "$map_tmp" = "1" ] && [ "$n_br" -le 1 ] 2>/dev/null; then
        swap_skip=1
        # Quiet bind so system path matches tmp without restart (post-fs also binds).
        if [ "$map_sys" != "1" ] && [ -x "$BR_SRC" ] && [ -e "$SYS_BR" ]; then
          mount --bind "$BR_SRC" "$SYS_BR" 2>/dev/null || true
        fi
      elif [ "$map_sys" = "1" ] && [ "$n_br" -le 1 ] 2>/dev/null; then
        # System mapped; tmp missing/same → skip. If tmp has different unmapped
        # tip, still skip thrash — service map-first pick uses system map.
        if [ "$map_tmp" = "0" ] || [ -z "$md5_tmp" ] || [ "$md5_tmp" = "$md5_sys" ]; then
          swap_skip=1
        fi
      fi
      if [ "$swap_skip" = "1" ]; then
        printf 1 >/data/local/tmp/titan2_hid_excl_sym 2>/dev/null || true
        chmod 666 /data/local/tmp/titan2_hid_excl_sym 2>/dev/null || true
        settings put global titan2_hid_excl_sym 1 2>/dev/null || true
        # 2.50: never force specials_method on swap skip (phone path; exclusive = in-bridge map)
        log "swap_hid_stack skip (map live tmp=$map_tmp sys=$map_sys md5=${md5_sys:-?} bridges=$n_br)"
      else
      log "swap_hid_stack start"
      # Graceful TERM first so hid_bridge 0.16.2+ empty-report flush runs
      # (immediate -9 left sticky host Shift/Alt). Wait ~200ms then hard-kill.
      killall hid_bridge 2>/dev/null || true
      _sw_i=0
      while [ "$_sw_i" -lt 20 ]; do
        pidof hid_bridge >/dev/null 2>&1 || break
        sleep 0.01
        _sw_i=$((_sw_i + 1))
      done
      killall -9 hid_bridge 2>/dev/null || true
      # Stop init HID service + any staged service copies (dual → grab EBUSY)
      setprop ctl.stop titan2-usb-hid 2>/dev/null || true
      # 2.44: sh-only /proc match — never ps -A under heat (1.65 hang residual)
      _kill_sh_cmdline_match 'titan2_usb_hid/service'
      _kill_sh_cmdline_match 't2uhid_service'
      _kill_sh_cmdline_match 'titan2-usb-hid'
      sleep 0.4
      killall -9 hid_bridge 2>/dev/null || true
      SVC_SRC=/data/local/tmp/t2uhid_service.sh
      EN_SRC=/data/local/tmp/t2uhid_enable_hid.sh
      if [ -x "$BR_SRC" ] && [ -e "$SYS_BR" ]; then
        mount --bind "$BR_SRC" "$SYS_BR" 2>/dev/null \
          || cp -f "$BR_SRC" /data/local/tmp/hid_bridge_live 2>/dev/null || true
        log "swap_hid_stack bridge=$BR_SRC"
      else
        log "swap_hid_stack no bridge src"
      fi
      if [ -x "$SVC_SRC" ] && [ -e /system/etc/titan2_usb_hid/service.sh ]; then
        mount --bind "$SVC_SRC" /system/etc/titan2_usb_hid/service.sh 2>/dev/null || true
        log "swap_hid_stack service=$SVC_SRC"
      fi
      if [ -x "$EN_SRC" ] && [ -e /system/etc/titan2_usb_hid/enable_hid.sh ]; then
        mount --bind "$EN_SRC" /system/etc/titan2_usb_hid/enable_hid.sh 2>/dev/null || true
      fi
      # One service only: restart init titan2-usb-hid (bound to staged files).
      # Do not nohup a second service — dual bridges → key grab errno=16.
      setprop ctl.start titan2-usb-hid 2>/dev/null || true
      sleep 0.3
      # 2.44: pidof + sh cmdline match — never ps -A (heat hang residual)
      if ! pidof titan2-usb-hid-service.sh >/dev/null 2>&1 \
          && ! _any_sh_cmdline_match 'titan2_usb_hid/service.sh' \
          && ! _any_sh_cmdline_match 't2uhid_service'; then
        # init may not restart (disabled); fall back to single staged instance
        if [ -x "$SVC_SRC" ]; then
          nohup /system/bin/sh "$SVC_SRC" >>/data/local/tmp/titan2_usb_hid.log 2>&1 &
          echo $! >/data/local/tmp/t2uhid.pid 2>/dev/null || true
          log "swap_hid_stack started staged service pid=$!"
        else
          nohup /system/bin/sh /system/etc/titan2_usb_hid/service.sh >>/data/local/tmp/titan2_usb_hid.log 2>&1 &
          log "swap_hid_stack restarted system service pid=$!"
        fi
      else
        log "swap_hid_stack init titan2-usb-hid only (no dual stage)"
      fi
      # 2.50: do not force specials_method (kcm product; inject human opt-in; exclusive in-bridge)
      if strings /system/etc/titan2_usb_hid/hid_bridge 2>/dev/null | grep -q 'specials layer in-bridge'; then
        printf 1 >/data/local/tmp/titan2_hid_excl_sym 2>/dev/null || true
        chmod 666 /data/local/tmp/titan2_hid_excl_sym 2>/dev/null || true
        settings put global titan2_hid_excl_sym 1 2>/dev/null || true
      else
        printf 0 >/data/local/tmp/titan2_hid_excl_sym 2>/dev/null || true
        settings put global titan2_hid_excl_sym 0 2>/dev/null || true
      fi
      log "swap_hid_stack done md5=$(md5sum /system/etc/titan2_usb_hid/hid_bridge 2>/dev/null | awk '{print $1}') bridges=$(pidof hid_bridge 2>/dev/null | tr ' ' ,)"
      fi
      ;;
    force_restore_usb|usb_restore)
      EN=/data/local/tmp/t2uhid_enable_hid.sh
      [ -x "$EN" ] || EN=/system/etc/titan2_usb_hid/enable_hid.sh
      if [ -x "$EN" ]; then
        sh "$EN" force_restore >>/data/local/tmp/titan2_usb_hid.log 2>&1 || sh "$EN" off >>/data/local/tmp/titan2_usb_hid.log 2>&1 || true
        log "force_restore_usb done cfg=$(getprop sys.usb.config)"
      fi
      ;;
    bare_ui|drop_square|stock_chrome)
      # Strip Titan square RROs + monochromatic cube seed → stock LOS chrome.
      # 2.91: NEVER remount EROFS on hot path (hung agent multi-sec). Bg only.
      (
        for _ov in com.titanus2.overlay.iconshape \
            com.titanus2.overlay.settings_square \
            com.titanus2.overlay.systemui_square; do
          if command -v timeout >/dev/null 2>&1; then
            timeout 2 cmd overlay disable --user 0 "$_ov" >/dev/null 2>&1 || true
            timeout 2 cmd overlay disable "$_ov" >/dev/null 2>&1 || true
          else
            cmd overlay disable --user 0 "$_ov" >/dev/null 2>&1 || true
            cmd overlay disable "$_ov" >/dev/null 2>&1 || true
          fi
        done
        settings delete secure theme_customization_overlay_packages 2>/dev/null || true
        settings put secure theme_customization_overlay_packages "" 2>/dev/null || true
        settings delete global titan2_ui_accent_argb 2>/dev/null || true
        settings delete global titan2_ui_day_night 2>/dev/null || true
        settings put secure ui_night_mode 0 2>/dev/null || true
        settings put system ui_night_mode 0 2>/dev/null || true
        cmd uimode night auto >/dev/null 2>&1 || true
        printf bare >"$ST/titan2_ui_chrome" 2>/dev/null || true
        printf bare >"$T2/titan2_ui_chrome" 2>/dev/null || true
        chmod 666 "$ST/titan2_ui_chrome" "$T2/titan2_ui_chrome" 2>/dev/null || true
        settings put global titan2_ui_chrome bare 2>/dev/null || true
        if [ -d /data/adb/modules ]; then
          MOD=/data/adb/modules/titan2_drop_square
          rm -rf /data/adb/modules/titan2_square_geometry 2>/dev/null || true
          mkdir -p "$MOD/system/product/overlay" 2>/dev/null || true
          printf '%s\n' \
            'id=titan2_drop_square' \
            'name=Titan2 Drop Square Chrome' \
            'version=1.2-bare-ui-dev-action' \
            'versionCode=3' \
            'author=titanus2' \
            'description=Whiteout square RROs bare Settings/SystemUI' \
            >"$MOD/module.prop" 2>/dev/null || true
          for f in TitanIconShapeOverlay.apk TitanSettingsSquareOverlay.apk TitanSystemUISquareOverlay.apk; do
            rm -f "$MOD/system/product/overlay/$f" 2>/dev/null || true
            mknod "$MOD/system/product/overlay/$f" c 0 0 2>/dev/null || true
          done
          rm -f "$MOD/disable" "$MOD/remove" 2>/dev/null || true
        fi
        # Hide product RO APKs via bind-mount empty (EROFS cannot rm; no Magisk).
        # OMS then drops the package → stock Settings/SystemUI geometry.
        mkdir -p /data/local/tmp/titan2_bare_ui 2>/dev/null || true
        : >/data/local/tmp/titan2_bare_ui/empty.apk 2>/dev/null || true
        chmod 644 /data/local/tmp/titan2_bare_ui/empty.apk 2>/dev/null || true
        for f in TitanIconShapeOverlay.apk TitanSettingsSquareOverlay.apk TitanSystemUISquareOverlay.apk; do
          for _base in /system/product/overlay /product/overlay; do
            [ -f "$_base/$f" ] || continue
            umount "$_base/$f" 2>/dev/null || true
            if mount --bind /data/local/tmp/titan2_bare_ui/empty.apk "$_base/$f" 2>/dev/null; then
              echo "pad-agent bare_ui bind-hid $_base/$f" >"$ST/titan2_agent_status" 2>/dev/null || true
            fi
          done
        done
        # Re-disable after hide (OMS may re-read)
        for _ov in com.titanus2.overlay.iconshape \
            com.titanus2.overlay.settings_square \
            com.titanus2.overlay.systemui_square; do
          if command -v timeout >/dev/null 2>&1; then
            timeout 2 cmd overlay disable --user 0 "$_ov" >/dev/null 2>&1 || true
          else
            cmd overlay disable --user 0 "$_ov" >/dev/null 2>&1 || true
          fi
        done
        # Restart SystemUI so chrome reloads without square RROs
        if command -v timeout >/dev/null 2>&1; then
          timeout 2 pkill -f com.android.systemui 2>/dev/null || true
        else
          pkill -f com.android.systemui 2>/dev/null || true
        fi
        _ov_st=`cmd overlay list 2>/dev/null | grep 'titanus2.overlay' | tr '\n' ';'`
        echo "pad-agent bare_ui done overlays=[$_ov_st]" >"$ST/titan2_agent_status" 2>/dev/null || true
      ) &
      log "bare_ui scheduled (bg)"
      ;;
    kw_restart|key_watch_restart)
      # Replace stale key-watch (2.191 am RecentsActivity) with tip peel.
      _kw=`cat /data/local/tmp/titan2_key_watch.pid 2>/dev/null | tr -d '\r\n '`
      case "$_kw" in ''|*[!0-9]*) ;; *) kill -9 "$_kw" 2>/dev/null || true ;; esac
      for p in `pgrep -f titan2-key-watch 2>/dev/null`; do
        kill -9 "$p" 2>/dev/null || true
      done
      rmdir /data/local/tmp/titan2_key_watch.lock.d 2>/dev/null || \
        rm -rf /data/local/tmp/titan2_key_watch.lock.d 2>/dev/null || true
      rm -f /data/local/tmp/titan2_key_watch.lock /data/local/tmp/titan2_key_watch.pid 2>/dev/null || true
      KW=/data/local/tmp/titan2-key-watch.sh
      [ -x "$KW" ] || KW=/system/bin/titan2-key-watch.sh
      /system/bin/sh "$KW" run >>/data/local/tmp/titan2_key_watch.log 2>&1 &
      sleep 0.3
      echo "kw_restart tip=$(grep -m1 KW_VER= "$KW") live=$(cat /data/local/tmp/titan2_key_watch_status 2>/dev/null)" \
        >/data/local/tmp/titan2_kw_restart_status 2>/dev/null || true
      chmod 666 /data/local/tmp/titan2_kw_restart_status 2>/dev/null || true
      log "kw_restart done"
      ;;
    kl_rebind|titankey_rebind)
      # Reopen TitanKey so EventHub reloads KL (580 F24 vs Generic APP_SWITCH).
      printf %s 1 >"$ST/titan2_force_titankey_uevent" 2>/dev/null || true
      KLSH="$ST/titan2-keylayout.sh"
      [ -f "$KLSH" ] || KLSH=/system/bin/titan2-keylayout.sh
      FORCE_TITANKEY_UEVENT=1 /system/bin/sh "$KLSH" fn
      ue=""
      for d in /sys/class/input/input*; do
        [ -e "$d/name" ] || continue
        n=`cat "$d/name" 2>/dev/null` || continue
        [ "$n" = "TitanKey" ] || continue
        if [ -e "$d/inhibited" ]; then
          echo 1 > "$d/inhibited" 2>/dev/null || true
          sleep 0.05
          echo 0 > "$d/inhibited" 2>/dev/null || true
        fi
        echo remove > "$d/uevent" 2>/dev/null || true
        sleep 0.08
        echo add > "$d/uevent" 2>/dev/null || true
        echo change > "$d/uevent" 2>/dev/null || true
        ue="$ue $d"
      done
      log "kl_rebind done klsh=$KLSH ue=$ue"
      echo "kl_rebind done ue=$ue" >"$ST/titan2_kl_rebind_status" 2>/dev/null || true
      chmod 666 "$ST/titan2_kl_rebind_status" 2>/dev/null || true
      ;;
    *)
      log "dev_action unknown: $act"
      ;;
  esac
exit 0
