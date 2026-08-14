#!/system/bin/sh
# Mount atlas_openwrt LP and start the plane after userdata exists.
# Wipe-survive: OpenWrt root is on super. This only recreates /data mountpoints.
export PATH=/system/bin:/system/xbin:/vendor/bin:$PATH
LOG=/data/local/tmp/titan2-openwrt-boot.log
SCRIPT=/system/bin/titan2-openwrt.sh

i=0
while [ $i -lt 90 ]; do
  [ -d /data/local ] && break
  sleep 1
  i=$((i + 1))
done
mkdir -p /data/local/tmp /data/local/atlas-openwrt /data/misc/titan2 2>/dev/null || true
touch "$LOG" 2>/dev/null || true
chmod 644 "$LOG" 2>/dev/null || true

[ -f "$SCRIPT" ] || {
  echo "=== $(date) no $SCRIPT ===" >>"$LOG"
  exit 0
}

{
  echo "=== $(date) titan2-openwrt-boot uid=$(id -u) ==="
  setprop persist.sys.titan2.tether_ipv4 192.168.6.1/24 2>/dev/null || true
  m=0
  while [ $m -lt 30 ]; do
    if [ -b /dev/block/mapper/atlas_openwrt_a ] || [ -b /dev/block/mapper/atlas_openwrt ] \
      || [ -b /dev/block/by-name/atlas_openwrt_a ]; then
      echo "mapper present after ${m}s"
      break
    fi
    m=$((m + 1))
    sleep 1
  done
  ls -la /dev/block/mapper/atlas_openwrt* /dev/block/by-name/atlas_openwrt* 2>&1 | head -8 || true
  if [ -x /system/bin/openwrt-lpctl ]; then
    /system/bin/openwrt-lpctl mount 2>&1 || true
    /system/bin/openwrt-lpctl status 2>&1 || true
  fi
  /system/bin/sh "$SCRIPT" start
  echo "=== $(date) titan2-openwrt-boot daemons armed ==="
} >>"$LOG" 2>&1 || true
# Stay in the service so init does not reap the cgroup.
while true; do
  if ! pgrep -x uhttpd >/dev/null 2>&1; then
    echo "=== $(date) uhttpd down — restart ===" >>"$LOG"
    /system/bin/sh "$SCRIPT" start >>"$LOG" 2>&1 || true
  fi
  sleep 8
done
