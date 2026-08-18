#!/system/bin/sh
# atlas-hybrid-ctl — product lifecycle for Debian container (ROM system, root only).
# Invoked by init (atlas-hybrid-boot / atlas-hybrid-watch) — never app su.
#
# Ops:
#   ensure   mount or bootstrap from product seed
#   reset    destroy image + bootstrap from seed (wipe container data)
#   rebuild  --preserve|ensure heal  OR  --wipe (=reset)
#   status   write world-readable status file
#
export PATH=/system/bin:/system/xbin:/vendor/bin:/product/bin:$PATH
SCRIPT=/system/bin/atlas-hybrid.sh
LOG=/data/local/tmp/atlas-hybrid-service.log
ST=/data/local/tmp/atlas_hybrid.status
export ATLAS_AUTO_BOOTSTRAP="${ATLAS_AUTO_BOOTSTRAP:-1}"
export HOME="${ATLAS_HOME:-/data/local/atlas-home/atlas}"
export ATLAS_HOME="$HOME"
export ATLAS_LINUX_HOME="${ATLAS_LINUX_HOME:-/data/local/atlas-home/atlas}"
export ATLAS_HYBRID_SIZE_G="${ATLAS_HYBRID_SIZE_G:-8}"

log() { echo "atlas-hybrid-ctl: $*" >>"$LOG" 2>/dev/null || true; }

write_status() {
  ready=0
  overlay=0
  boot=0
  img=0
  deb=
  enter=0
  [ -x /system/bin/atlas-enter ] && enter=1
  [ -f /data/local/atlas-hybrid.img ] && img=1
  if grep -q ' /data/local/atlas-hybrid/merge ' /proc/mounts 2>/dev/null; then
    overlay=1
  fi
  if [ -f /data/local/atlas-hybrid/lower/etc/os-release ] \
    || [ -f /data/local/atlas-hybrid/merge/etc/os-release ]; then
    boot=1
  fi
  if [ "$overlay" = "1" ]; then
    if [ -x /data/local/atlas-hybrid/merge/usr/bin/bash ] \
      || [ -x /data/local/atlas-hybrid/merge/bin/bash ] \
      || [ -x /data/local/atlas-hybrid/lower/bin/bash ] \
      || [ -x /data/local/atlas-hybrid/lower/usr/bin/bash ]; then
      ready=1
    fi
    deb=`cat /data/local/atlas-hybrid/merge/etc/debian_version 2>/dev/null | head -1 | tr -d '\r\n'`
    [ -z "$deb" ] && deb=`cat /data/local/atlas-hybrid/lower/etc/debian_version 2>/dev/null | head -1 | tr -d '\r\n'`
  fi
  # lower present counts as bootstrapped for status even if merge 0700
  if [ -x /data/local/atlas-hybrid/lower/bin/bash ] \
    || [ -f /data/local/atlas-hybrid/lower/etc/os-release ]; then
    boot=1
  fi
  {
    echo "ready=$ready"
    echo "overlay=$overlay"
    echo "bootstrapped=$boot"
    echo "img=$img"
    echo "debian=$deb"
    echo "enter_bin=$enter"
    echo "ts=$(date +%s 2>/dev/null || echo 0)"
  } >"$ST" 2>/dev/null || true
  chmod 644 "$ST" 2>/dev/null || true
  # App-visible ready flag (merge may stay 0700)
  if [ "$ready" = "1" ]; then
    echo 1 >/data/local/tmp/atlas_hybrid.ready 2>/dev/null || true
  else
    echo 0 >/data/local/tmp/atlas_hybrid.ready 2>/dev/null || true
  fi
  chmod 644 /data/local/tmp/atlas_hybrid.ready 2>/dev/null || true
}

[ -f "$SCRIPT" ] || {
  log "missing $SCRIPT"
  write_status
  exit 1
}

uid=`id -u 2>/dev/null`
if [ "$uid" != "0" ]; then
  log "refuse non-root (use init watch / boot service)"
  exit 1
fi

op="${1:-ensure}"
shift 2>/dev/null || true

case "$op" in
  ensure|mount)
    log "ensure begin"
    /system/bin/sh "$SCRIPT" ensure >>"$LOG" 2>&1
    rc=$?
    write_status
    log "ensure rc=$rc"
    exit $rc
    ;;
  reset|rebuild-wipe)
    log "reset: destroy + ensure (seed bootstrap)"
    /system/bin/sh "$SCRIPT" destroy >>"$LOG" 2>&1 || true
    export ATLAS_AUTO_BOOTSTRAP=1
    /system/bin/sh "$SCRIPT" ensure >>"$LOG" 2>&1
    rc=$?
    write_status
    log "reset rc=$rc"
    exit $rc
    ;;
  rebuild)
    mode="${1:---preserve}"
    case "$mode" in
      --wipe|wipe) exec /system/bin/sh "$0" reset ;;
      *)
        log "rebuild preserve"
        /system/bin/sh "$SCRIPT" rebuild --preserve >>"$LOG" 2>&1
        rc=$?
        write_status
        exit $rc
        ;;
    esac
    ;;
  status)
    /system/bin/sh "$SCRIPT" status 2>/dev/null | head -40
    write_status
    cat "$ST" 2>/dev/null
    exit 0
    ;;
  *)
    log "usage: ensure|reset|rebuild [--preserve|--wipe]|status"
    exit 2
    ;;
esac
