#!/system/bin/sh
# atlas-hybrid-watch — root request bridge + single atlas-enterd.
# NEVER force-fsck when overlay already live (that thrash-detached loop).
export PATH=/system/bin:/system/xbin:/vendor/bin:$PATH
CTL=/system/bin/atlas-hybrid-ctl.sh
[ -f "$CTL" ] || CTL=/system/bin/atlas-hybrid-ctl
ENTERD=/system/bin/atlas-enterd
# Tip land (rootless product): prefer /data/local/tmp tip ELF without remount.
ENTERD_TIP=/data/local/tmp/atlas-enterd-tip
LOG=/data/local/tmp/atlas-hybrid-service.log
ST=/data/local/tmp
export ATLAS_AUTO_BOOTSTRAP=1

log() { echo "atlas-hybrid-watch: $*" >>"$LOG" 2>/dev/null || true; }

pick_enterd() {
  if [ -x "$ENTERD_TIP" ]; then
    echo "$ENTERD_TIP"
  elif [ -x "$ENTERD" ]; then
    echo "$ENTERD"
  else
    echo ""
  fi
}

# Never SIGKILL tip enterd — that kills Deb bash (session exit -9 black term).
kill_system_enterd_only() {
  setprop ctl.stop atlas-enterd 2>/dev/null || true
  for p in `pidof atlas-enterd 2>/dev/null`; do
    # pidof exact name atlas-enterd (not tip)
    kill -9 "$p" 2>/dev/null || true
    log "pruned system enterd pid=$p"
  done
}

kill_all_enterd() {
  # reload only — tip + system
  kill_system_enterd_only
  for p in `pidof atlas-enterd-tip 2>/dev/null`; do
    kill "$p" 2>/dev/null || true
  done
  sleep 0.3
}

enterd_tip_live() {
  pidof atlas-enterd-tip >/dev/null 2>&1
}

enterd_live() {
  enterd_tip_live && return 0
  pidof atlas-enterd >/dev/null 2>&1
}

ensure_enterd() {
  ED=`pick_enterd`
  [ -n "$ED" ] || return 0
  # reload request: kill + restart (tip binary swap)
  if [ -f "$ST/atlas-enterd-reload-request" ]; then
    rm -f "$ST/atlas-enterd-reload-request" 2>/dev/null || true
    log "enterd-reload-request → $ED"
    kill_all_enterd
  fi
  # Tip preferred: keep tip forever; only stop system service (do not kill tip)
  if [ -x "$ENTERD_TIP" ]; then
    kill_system_enterd_only
    if enterd_tip_live; then
      return 0
    fi
    log "start tip enterd ($ENTERD_TIP)"
    "$ENTERD_TIP" >>/data/local/tmp/atlas-enterd.tip.log 2>&1 &
    sleep 0.3
    enterd_tip_live && return 0
  fi
  # System only when no tip
  enterd_live && return 0
  log "start atlas-enterd ($ED)"
  "$ED" >>/data/local/tmp/atlas-enterd.log 2>&1 &
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
