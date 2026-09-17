#!/system/bin/sh
# Vendor NVRAM rewrites persist.vendor.radio.simswitch to tray 1.
# This loop only runs titan2-ims-simswitch-early.sh. It does not
# touch ImsService, set-ims-service, or carrier config.
export PATH=/system/bin:/system/xbin:/vendor/bin:$PATH
INTERVAL_S=${INTERVAL_S:-5}
EARLY=/system/bin/titan2-ims-simswitch-early.sh
[ -x "$EARLY" ] || EARLY=/data/local/tmp/titan2-ims-simswitch-early.sh
echo $$ >/data/local/tmp/titan2_ims_simswitch_hold.pid 2>/dev/null || true
while true; do
  [ -x "$EARLY" ] && "$EARLY"
  sleep "$INTERVAL_S"
done
