#!/system/bin/sh
# Atlas hybrid ROM — late boot install path (NOT optional tip theater).
#
# Product: this is a hybrid ROM. On every boot after data is ready:
#   1) If Debian lower already present → mount + bind Android
#   2) Else if rootfs seed is staged → bootstrap once (extract) then mount
#   3) Never mke2fs/destroy an existing image here
#
# Seeds (first match wins — find_rootfs_tarball in atlas-hybrid.sh):
#   /system/etc/atlas/debian-trixie-arm64-rootfs.tar.gz
#   /data/local/tmp/atlas-hybrid-dl/rootfs.tar.gz
#   $ATLAS_HOME/rootfs/…
#
# Logs: /data/local/tmp/atlas-hybrid-service.log
export PATH=/system/bin:/system/xbin:/vendor/bin:/product/bin:$PATH
LOG=/data/local/tmp/atlas-hybrid-service.log
SCRIPT=/system/bin/atlas-hybrid.sh
# Default Atlas app home (admin identity)
export HOME="${ATLAS_HOME:-/data/user/0/com.titanus2.atlas/files}"
export ATLAS_HOME="$HOME"
export ATLAS_HYBRID_SIZE_G="${ATLAS_HYBRID_SIZE_G:-8}"
# Hybrid ROM: auto bootstrap when seed present
export ATLAS_AUTO_BOOTSTRAP=1

i=0
while [ $i -lt 90 ]; do
  [ -d /data/local ] && [ -d /data/user/0 ] && break
  sleep 1
  i=$((i + 1))
done

[ -f "$SCRIPT" ] || {
  echo "=== $(date) no $SCRIPT ===" >>"$LOG"
  exit 0
}

{
  echo "=== $(date) atlas-hybrid-boot (hybrid ROM install path) ==="
  echo "ATLAS_AUTO_BOOTSTRAP=$ATLAS_AUTO_BOOTSTRAP HOME=$HOME"
  # Super LP first (product Deb root)
  if [ -x /system/bin/atlas-lpctl ]; then
    echo "atlas-lpctl mount…"
    /system/bin/atlas-lpctl mount 2>&1 || echo "atlas-lpctl mount failed (may be empty/absent)"
    /system/bin/atlas-lpctl status 2>&1 || true
  fi
  # App may leave ensure-request after unlock; dirty flag after crash.
  if [ -f /data/local/tmp/atlas-hybrid-need-fsck ] \
    || [ -f /data/local/tmp/atlas-hybrid-ensure-request ]; then
    export ATLAS_FORCE_FSCK=1
    echo "ATLAS_FORCE_FSCK=1 (need-fsck or app request)"
  fi

  # Prefer mount; hybrid.sh will auto-bootstrap when empty + seed + AUTO=1
  ok=0
  n=0
  while [ $n -lt 4 ]; do
    n=$((n + 1))
    if /system/bin/sh "$SCRIPT" ensure; then
      echo "ensure: OK (try $n)"
      ok=1
      break
    fi
    echo "ensure: failed try $n — retry"
    sleep $((n * 2))
  done
  [ "$ok" = "1" ] || echo "ensure: still failed after $n tries"
  rm -f /data/local/tmp/atlas-hybrid-ensure-request 2>/dev/null || true

  if [ -f /system/bin/atlas-hybrid-ctl.sh ]; then
    /system/bin/sh /system/bin/atlas-hybrid-ctl.sh status | head -40
  else
    /system/bin/sh "$SCRIPT" status | head -30
  fi
  /system/bin/atlas-lpctl status 2>&1 || true
  echo "=== $(date) atlas-hybrid-boot done ==="
} >>"$LOG" 2>&1 || true
