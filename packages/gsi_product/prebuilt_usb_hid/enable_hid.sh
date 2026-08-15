#!/system/bin/sh
# Attach/detach USB HID keyboard + mouse on Titan 2 (configfs).
# Pure HID composite — ADB is NOT required and is not linked while HID is on.
# Never soft_connect=0. Never stop USB HAL.
set -u

G=/config/usb_gadget/g1
FK=$G/functions/hid.gs0
FM=$G/functions/hid.usb0
CFG=$G/configs/b.1
ABS_K=/config/usb_gadget/g1/functions/hid.gs0
ABS_M=/config/usb_gadget/g1/functions/hid.usb0
UDC_NAME=$(getprop sys.usb.controller)
[ -n "$UDC_NAME" ] || UDC_NAME=$(getprop vendor.usb.controller)
[ -n "$UDC_NAME" ] || UDC_NAME=11201000.usb0
LOG=/data/local/tmp/titan2_usb_hid.log
STATE=/data/local/tmp/titan2_usb_hid_prev_config
OWN_CFG=titan_hid
# Host file-transfer default when session ends (dev lab usually mtp,adb).
DEFAULT_RESTORE=mtp,adb

log() {
  echo "$(date +%H:%M:%S) enable_hid: $*" >>"$LOG" 2>/dev/null || true
  chmod 666 "$LOG" 2>/dev/null || true
}

write_kbd() {
  mkdir -p "$FK" 2>/dev/null || true
  printf '\x05\x01\x09\x06\xa1\x01\x05\x07\x19\xe0\x29\xe7\x15\x00\x25\x01\x75\x01\x95\x08\x81\x02\x95\x01\x75\x08\x81\x01\x95\x05\x75\x01\x05\x08\x19\x01\x29\x05\x91\x02\x95\x01\x75\x03\x91\x01\x95\x06\x75\x08\x15\x00\x25\x65\x05\x07\x19\x00\x29\x65\x81\x00\xc0' >"$FK/report_desc" 2>/dev/null || true
  echo 8 >"$FK/report_length" 2>/dev/null || true
  echo 1 >"$FK/protocol" 2>/dev/null || true
  echo 1 >"$FK/subclass" 2>/dev/null || true
  echo 1 >"$FK/no_out_endpoint" 2>/dev/null || true
}

write_mouse() {
  mkdir -p "$FM" 2>/dev/null || true
  printf '\x05\x01\x09\x02\xa1\x01\x09\x01\xa1\x00\x05\x09\x19\x01\x29\x03\x15\x00\x25\x01\x95\x03\x75\x01\x81\x02\x95\x01\x75\x05\x81\x01\x05\x01\x09\x30\x09\x31\x09\x38\x15\x81\x25\x7f\x75\x08\x95\x03\x81\x06\xc0\xc0' >"$FM/report_desc" 2>/dev/null || true
  echo 4 >"$FM/report_length" 2>/dev/null || true
  echo 2 >"$FM/protocol" 2>/dev/null || true
  echo 1 >"$FM/subclass" 2>/dev/null || true
  echo 1 >"$FM/no_out_endpoint" 2>/dev/null || true
}

linked_of() {
  want=$1
  for l in "$CFG"/*; do
    [ -L "$l" ] || continue
    t=$(readlink "$l" 2>/dev/null || true)
    case "$t" in *"$want"*) return 0 ;; esac
  done
  return 1
}

both_linked() { linked_of hid.gs0 && linked_of hid.usb0; }
any_hid_linked() { linked_of hid.gs0 || linked_of hid.usb0; }

mknod_hidg() {
  idx=0
  for F in "$FK" "$FM"; do
    [ -r "$F/dev" ] || continue
    majmin=$(cat "$F/dev" 2>/dev/null)
    maj=${majmin%%:*}; min=${majmin##*:}
    node=/dev/hidg$idx
    if [ -n "$maj" ] && [ -n "$min" ]; then
      rm -f "$node" 2>/dev/null || true
      mknod "$node" c "$maj" "$min" 2>/dev/null || true
      chmod 666 "$node" 2>/dev/null || true
    fi
    idx=$((idx + 1))
  done
}

udc_is_free() {
  u=$(cat "$G/UDC" 2>/dev/null | tr -d ' \t\r\n')
  case "$u" in ""|none) return 0 ;; esac
  return 1
}

# Prefer a sane host mode; never persist titan_hid across reboot.
normalize_persist_host() {
  p=$(getprop persist.sys.usb.config)
  case "$p" in
    "$OWN_CFG"|""|none)
      setprop persist.sys.usb.config "$DEFAULT_RESTORE"
      ;;
  esac
}

own_config() {
  # Session-only sys.usb.config. Never write OWN_CFG into persist.
  cur=$(getprop sys.usb.config)
  case "$cur" in
    "$OWN_CFG"|""|none) ;;
    *) echo "$cur" >"$STATE" 2>/dev/null || true ;;
  esac
  normalize_persist_host
  setprop sys.usb.config "$OWN_CFG"
  sleep 0.2
}

# Lab-only helpers. USB HID itself never requires ADB — pure kbd+mouse composite.
# ADB is only for host-side recovery while USB is pure HID (no ffs.adb on the wire).
lab_wants_adb() {
  case "$(getprop ro.titanus2.adb_bootstrap)" in 1|true|on) return 0 ;; esac
  case "$(getprop ro.debuggable)" in 1) return 0 ;; esac
  case "$(getprop persist.sys.titan2.lab_adb)" in 1|true|on) return 0 ;; esac
  return 1
}

restore_config() {
  prev=$(cat "$STATE" 2>/dev/null | tr -d '\r\n' || true)
  case "$prev" in ""|none|"$OWN_CFG") prev=$DEFAULT_RESTORE ;; esac
  if lab_wants_adb; then
    # Dev/lab: keep ADB available after session for file transfer / debug.
    case "$prev" in
      *adb*) ;;
      mtp|ptp|midi|rndis) prev="$prev,adb" ;;
      *) prev=$DEFAULT_RESTORE ;;
    esac
    log "restore config $prev (lab adb)"
    settings put global adb_enabled 1 2>/dev/null || true
    settings put global development_settings_enabled 1 2>/dev/null || true
  else
    # Release: restore host mode only — do not force ADB for HID to work.
    case "$prev" in
      titan_hid|none|"") prev=mtp ;;
    esac
    log "restore config $prev (no forced adb)"
  fi
  # Force a full gadget rebuild. setprop alone can leave pure-HID interfaces
  # on the wire with a stale iConfiguration string "mtp,adb" (lab 2026-07-18:
  # host saw kbd+mouse only after session thrash; adb never returned).
  setprop persist.sys.usb.config "$prev"
  setprop vendor.usb.config none 2>/dev/null || true
  setprop sys.usb.config none
  sleep 0.25
  # Ensure UDC unbound so framework can re-link ffs.adb / mtp
  printf 'none\n' >"$G/UDC" 2>/dev/null || true
  echo none >"$G/UDC" 2>/dev/null || true
  sleep 0.1
  setprop vendor.usb.config "$prev" 2>/dev/null || true
  setprop sys.usb.config "$prev"
  # Second bounce — MTK init sometimes ignores first config after titan_hid
  sleep 0.35
  cur=$(getprop sys.usb.config | tr -d '\r\n')
  hid_still=0
  any_hid_linked && hid_still=1
  if [ "$cur" != "$prev" ] || [ "$hid_still" = "1" ]; then
    log "restore bounce (cfg=$cur hid_still=$hid_still)"
    clear_all_links
    printf 'none\n' >"$G/UDC" 2>/dev/null || true
    setprop sys.usb.config none
    sleep 0.2
    setprop sys.usb.config "$prev"
  fi
  # USB bounce kills adbd. Re-open classic TCP only if Controls Dev already
  # armed Remote ADB — never invent ON.
  ensure_tcp_adb
  sleep 0.2
  log "restore done config=$(getprop sys.usb.config) state=$(getprop sys.usb.state) tcp=$(getprop service.adb.tcp.port) hidg0=$([ -e /dev/hidg0 ] && echo y || echo n)"
}

# Product Remote ADB (Controls → Developer). USB gadget on/off stops adbd;
# persist alone does not reopen :5555 on this stack. Re-apply if desired.
# Never pin_usb here — exclusive HID owns the gadget during session.
# Never require biometrics (already gated at first ON).
ensure_tcp_adb() {
  d=
  if [ -f /data/misc/titan2/remote_adb.desire ]; then
    d=$(tr -d '\r\n ' </data/misc/titan2/remote_adb.desire 2>/dev/null)
  elif [ -f /data/misc/titan2/wireless_adb_wanted ]; then
    d=on
  fi
  if [ "$d" != "on" ]; then
    log "ensure_tcp_adb: skip (desire=${d:-off})"
    return 0
  fi
  port=5555
  if [ -f /data/misc/titan2/remote_adb_port ]; then
    p=$(tr -d '\r\n ' </data/misc/titan2/remote_adb_port 2>/dev/null)
    case "$p" in [1-9]*[0-9]|[1-9][0-9][0-9][0-9]) port=$p ;; esac
  fi
  if ss -ltn 2>/dev/null | grep -qE "[:.]${port}[[:space:]]|:${port}\$"; then
    log "ensure_tcp_adb: already :$port"
    return 0
  fi
  settings put global adb_enabled 1 2>/dev/null || true
  settings put global development_settings_enabled 1 2>/dev/null || true
  setprop persist.adb.tcp.port "$port" 2>/dev/null || true
  setprop service.adb.tcp.port "$port" 2>/dev/null || true
  setprop ctl.restart adbd 2>/dev/null || true
  i=0
  while [ "$i" -lt 16 ]; do
    if ss -ltn 2>/dev/null | grep -qE "[:.]${port}[[:space:]]|:${port}\$"; then
      log "ensure_tcp_adb: listening :$port"
      return 0
    fi
    sleep 0.12
    i=$((i + 1))
  done
  setprop persist.adb.tcp.port "$port" 2>/dev/null || true
  setprop service.adb.tcp.port "$port" 2>/dev/null || true
  setprop ctl.stop adbd 2>/dev/null || true
  sleep 0.25
  setprop persist.adb.tcp.port "$port" 2>/dev/null || true
  setprop service.adb.tcp.port "$port" 2>/dev/null || true
  setprop ctl.start adbd 2>/dev/null || true
  sleep 0.4
  log "ensure_tcp_adb: bounced tcp=$(getprop service.adb.tcp.port) listen=$(ss -ltn 2>/dev/null | grep -E \":${port}\" || echo none)"
}

# Force USB stack down so configfs links can change.
usb_down() {
  # unknown config name stops init from instantly rebuilding mtp/adb
  setprop sys.usb.config none
  sleep 0.15
  setprop sys.usb.config "$OWN_CFG"
  sleep 0.1
  i=0
  while [ "$i" -lt 60 ]; do
    printf 'none\n' >"$G/UDC" 2>/dev/null || true
    echo none >"$G/UDC" 2>/dev/null || true
    udc_is_free && return 0
    sleep 0.05
    i=$((i + 1))
  done
  udc_is_free && return 0
  log "usb_down fail UDC=$(cat $G/UDC 2>/dev/null) cfg=$(getprop sys.usb.config)"
  return 1
}

clear_all_links() {
  for l in "$CFG"/f*; do
    [ -e "$l" ] || [ -L "$l" ] || continue
    if [ -L "$l" ]; then
      rm -f "$l" 2>/dev/null || true
    elif [ -d "$l" ]; then
      for n in "$l"/*; do
        [ -e "$n" ] || [ -L "$n" ] || continue
        rm -f "$n" 2>/dev/null || true
      done
      rmdir "$l" 2>/dev/null || rm -rf "$l" 2>/dev/null || true
    else
      rm -f "$l" 2>/dev/null || true
    fi
  done
  # also clear non-f* function links some vendors use
  for l in "$CFG"/*; do
    [ -L "$l" ] || continue
    t=$(readlink "$l" 2>/dev/null || true)
    case "$t" in
      *functions/*|*hid.*|*ffs.*|*mtp*|*ptp*|*acm*|*rndis*|*ncm*)
        rm -f "$l" 2>/dev/null || true
        ;;
    esac
  done
}

# configfs: a function may only be linked once — strip any existing link to it
unlink_function() {
  want=$1
  for l in "$CFG"/*; do
    [ -L "$l" ] || continue
    t=$(readlink "$l" 2>/dev/null || true)
    case "$t" in *"$want"*)
      rm -f "$l" 2>/dev/null || true
      log "unlinked $want from $l"
      ;;
    esac
  done
}

links_cleared() {
  for l in "$CFG"/f*; do
    [ -e "$l" ] || [ -L "$l" ] && return 1
  done
  return 0
}

link_abs() {
  target=$1
  slot=$2
  base=$(basename "$target")
  if [ ! -d "$target" ] && [ ! -e "$target" ]; then
    log "link_abs missing target $target"
    return 1
  fi
  unlink_function "$base"
  if [ -L "$CFG/$slot" ] || [ -e "$CFG/$slot" ]; then
    rm -f "$CFG/$slot" 2>/dev/null || rm -rf "$CFG/$slot" 2>/dev/null || true
  fi
  if ! ln -s "$target" "$CFG/$slot" 2>>"$LOG"; then
    log "ln -s $target -> $CFG/$slot failed"
    return 1
  fi
  return 0
}

attach_once() {
  log "attach try (pure HID, no ADB)"
  if ! usb_down; then
    log "usb_down fail"
    return 1
  fi

  write_kbd
  write_mouse

  clear_all_links
  unlink_function ffs.adb
  unlink_function hid.gs0
  unlink_function hid.usb0
  unlink_function mtp.gs0
  unlink_function ptp.gs1
  sleep 0.05

  if ! udc_is_free; then
    log "UDC rebound during clear"
    usb_down || true
  fi
  if ! links_cleared; then
    log "links remain after clear:"
    ls -la "$CFG" >>"$LOG" 2>&1 || true
    clear_all_links
  fi
  if ! links_cleared; then
    log "cannot clear links (UDC=$(cat $G/UDC 2>/dev/null))"
    return 1
  fi

  if ! udc_is_free; then
    usb_down || true
  fi
  if ! udc_is_free; then
    log "UDC busy before link"
    return 1
  fi

  # Pure HID only — keyboard + mouse. No ffs.adb dependency.
  if ! link_abs "$ABS_K" f1; then
    log "kbd link fail"
    ls -la "$CFG" >>"$LOG" 2>&1 || true
    return 1
  fi
  if ! link_abs "$ABS_M" f2; then
    log "mouse link fail"
    return 1
  fi

  if ! udc_is_free; then
    log "UDC reclaimed before bind — try bind anyway"
  fi
  if ! echo "$UDC_NAME" >"$G/UDC" 2>>"$LOG"; then
    log "bind fail"
    return 1
  fi
  sleep 0.4
  mknod_hidg
  setprop sys.usb.state "$OWN_CFG"
  normalize_persist_host
  setprop sys.usb.config "$OWN_CFG"

  if both_linked && [ -e /dev/hidg0 ]; then
    log "OK kbd+mouse (no adb)"
    # USB flip can kill adbd after pre-arm — re-open TCP for lab recovery
    ensure_tcp_adb
    return 0
  fi
  sleep 0.2
  mknod_hidg
  if both_linked; then
    log "OK kbd+mouse links (hidg late)"
    ensure_tcp_adb
    return 0
  fi
  log "FAIL after bind"
  ls -l "$CFG" >>"$LOG" 2>&1 || true
  ls -l /dev/hidg* >>"$LOG" 2>&1 || true
  return 1
}

do_on() {
  [ -d "$G" ] || { log "no $G"; return 1; }
  # Lab: keep a non-USB adb channel before pure-HID steals the port
  ensure_tcp_adb
  own_config

  if both_linked && [ -e /dev/hidg0 ] && [ -e /dev/hidg1 ]; then
    mknod_hidg
    log "on already both linked"
    ensure_tcp_adb
    return 0
  fi

  attempt=1
  while [ "$attempt" -le 5 ]; do
    if attach_once; then
      ensure_tcp_adb
      return 0
    fi
    log "retry $attempt"
    sleep 0.3
    attempt=$((attempt + 1))
  done
  log "FAIL after retries"
  # Don't leave USB dead — hand back to mtp,adb
  restore_config || true
  return 1
}

do_off() {
  [ -d "$G" ] || return 0
  # Always tear down HID even when config prop already says mtp,adb — lab saw
  # pure-HID endpoints still enumerated with iConfiguration "mtp,adb".
  log "off detaching HID (linked=$(any_hid_linked && echo y || echo n) hidg0=$([ -e /dev/hidg0 ] && echo y || echo n) cfg=$(getprop sys.usb.config))"
  usb_down || true
  for l in "$CFG"/*; do
    [ -L "$l" ] || continue
    t=$(readlink "$l" 2>/dev/null || true)
    case "$t" in *hid.gs0*|*hid.usb0*|*hid.gs1*) rm -f "$l"; log "unlinked $l" ;; esac
  done
  clear_all_links
  unlink_function hid.gs0
  unlink_function hid.usb0
  # Hand USB back to Android init — restore_config rebuilds mtp/adb functions.
  printf 'none\n' >"$G/UDC" 2>/dev/null || true
  echo none >"$G/UDC" 2>/dev/null || true
  sleep 0.1
  restore_config
  # If HID still linked or hidg nodes linger, force a second restore pass.
  if any_hid_linked || [ -e /dev/hidg0 ]; then
    log "off residual HID — second restore pass"
    usb_down || true
    clear_all_links
    unlink_function hid.gs0
    unlink_function hid.usb0
    printf 'none\n' >"$G/UDC" 2>/dev/null || true
    restore_config
  fi
  normalize_persist_host
  log "off done linked=$(any_hid_linked && echo y || echo n) hidg0=$([ -e /dev/hidg0 ] && echo y || echo n)"
}

# Emergency: always restore mtp,adb regardless of link state (stuck pure-HID).
do_force_restore() {
  log "force_restore start"
  [ -d "$G" ] && {
    usb_down || true
    clear_all_links
    unlink_function hid.gs0
    unlink_function hid.usb0
    printf 'none\n' >"$G/UDC" 2>/dev/null || true
  }
  echo "$DEFAULT_RESTORE" >"$STATE" 2>/dev/null || true
  restore_config
  log "force_restore done"
}

case "${1:-}" in
  on|1|enable) do_on ;;
  off|0|disable) do_off ;;
  force_restore|restore|fix) do_force_restore ;;
  ensure_tcp|tcp|ensure_tcp_adb) ensure_tcp_adb ;;
  status)
    echo "UDC=$(cat $G/UDC 2>/dev/null)"
    echo "config=$(getprop sys.usb.config) state=$(getprop sys.usb.state)"
    echo "persist=$(getprop persist.sys.usb.config)"
    ls -l "$CFG" 2>/dev/null || true
    ls -l /dev/hidg* 2>/dev/null || echo "no hidg"
    both_linked && echo "hid=both" || {
      linked_of hid.gs0 && echo "hid=kbd-only" || echo "hid=none"
    }
    linked_of ffs.adb && echo "adb=linked" || echo "adb=not-linked"
    ;;
  *) echo "usage: $0 on|off|force_restore|ensure_tcp|status" >&2; exit 2 ;;
esac
