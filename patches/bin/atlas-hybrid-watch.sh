#!/system/bin/sh
# atlas-hybrid-watch — root request bridge + single atlas-enterd.
# NEVER force-fsck when overlay already live (that thrash-detached loop).
export PATH=/system/bin:/system/xbin:/vendor/bin:$PATH
CTL=/system/bin/atlas-hybrid-ctl.sh
[ -f "$CTL" ] || CTL=/system/bin/atlas-hybrid-ctl
ENTERD=/system/bin/atlas-enterd
LOG=/data/local/tmp/atlas-hybrid-service.log
ST=/data/local/tmp
export ATLAS_AUTO_BOOTSTRAP=1

log() { echo "atlas-hybrid-watch: $*" >>"$LOG" 2>/dev/null || true; }

# LAW: Deb enter is the ROM daemon. Never prefer /data/local/tmp tip —
# tip-prefer killed system enterd and Atlas fell back to Android toybox sh.
# Init must own the process so ANDROID_SOCKET_atlasenter (/dev/socket) exists.
# pidof is not live — hung `atlas-enterd --version` is still named atlas-enterd.
enterd_live() {
  grep -q '@atlasenter' /proc/net/unix 2>/dev/null \
    || [ -S /dev/socket/atlasenter ]
}

ensure_enterd() {
  [ -x "$ENTERD" ] || return 0
  # Drop leftover tip so it cannot steal the plane after a reboot.
  rm -f /data/local/tmp/atlas-enterd-tip /data/local/tmp/atlas-enterd-reload-request 2>/dev/null || true
  if enterd_live; then
    return 0
  fi
  log "ctl.start atlas-enterd"
  setprop sys.atlas.enterd 1 2>/dev/null || true
  setprop ctl.start atlas-enterd 2>/dev/null || true
  i=0
  while [ "$i" -lt 20 ]; do
    enterd_live && return 0
    i=$((i + 1))
    sleep 0.1
  done
  # Last resort only — no ANDROID_SOCKET. Abstract + fs sock still work.
  log "init miss — exec $ENTERD"
  "$ENTERD" >>/data/local/tmp/atlas-enterd.log 2>&1 &
  sleep 0.2
}

overlay_live() {
  grep -q ' /data/local/atlas-hybrid/merge ' /proc/mounts 2>/dev/null
}

do_ensure() {
  # Live overlay: never ATLAS_FORCE_FSCK (causes detach thrash)
  if overlay_live; then
    rm -f "$ST/atlas-hybrid-need-fsck" 2>/dev/null || true
    unset ATLAS_FORCE_FSCK
    /system/bin/sh "$CTL" ensure >>"$LOG" 2>&1 || true
    return 0
  fi
  # Cold: allow one fsck if flag present, else normal ensure
  if [ -f "$ST/atlas-hybrid-need-fsck" ]; then
    export ATLAS_FORCE_FSCK=1
  else
    unset ATLAS_FORCE_FSCK
  fi
  /system/bin/sh "$CTL" ensure >>"$LOG" 2>&1 || true
  unset ATLAS_FORCE_FSCK
}

if [ -f "$CTL" ]; then
  do_ensure
fi
ensure_enterd

n=0
while [ $n -lt 1200 ]; do
  n=$((n + 1))
  acted=0
  ensure_enterd

  if [ -f "$ST/atlas-hybrid-reset-request" ]; then
    rm -f "$ST/atlas-hybrid-reset-request" 2>/dev/null || true
    log "reset-request"
    /system/bin/sh "$CTL" reset >>"$LOG" 2>&1 || true
    acted=1
  fi
  if [ -f "$ST/atlas-hybrid-rebuild-request" ]; then
    mode=`cat "$ST/atlas-hybrid-rebuild-request" 2>/dev/null | tr -d '\r\n' | head -1`
    rm -f "$ST/atlas-hybrid-rebuild-request" 2>/dev/null || true
    log "rebuild-request $mode"
    case "$mode" in
      wipe|--wipe) /system/bin/sh "$CTL" reset >>"$LOG" 2>&1 || true ;;
      *) /system/bin/sh "$CTL" rebuild --preserve >>"$LOG" 2>&1 || true ;;
    esac
    acted=1
  fi
  if [ -f "$ST/atlas-hybrid-ensure-request" ]; then
    rm -f "$ST/atlas-hybrid-ensure-request" 2>/dev/null || true
    log "ensure-request"
    do_ensure
    acted=1
  fi
  # Clear stale need-fsck when overlay is healthy (stop thrash loops)
  if overlay_live && [ -f "$ST/atlas-hybrid-need-fsck" ]; then
    rm -f "$ST/atlas-hybrid-need-fsck" 2>/dev/null || true
    log "cleared need-fsck (overlay live)"
  fi
  if [ $((n % 10)) -eq 0 ] || [ "$acted" = "1" ]; then
    /system/bin/sh "$CTL" status >/dev/null 2>&1 || true
  fi
  if [ $n -lt 120 ]; then
    sleep 1
  else
    sleep 3
  fi
done
log "watch exit after poll budget"
