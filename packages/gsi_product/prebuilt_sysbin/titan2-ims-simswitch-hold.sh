#!/system/bin/sh
# Standing hold: vendor NVRAM resets persist.vendor.radio.simswitch to tray 1.
# Re-read Settings → SIMs → Calls every tick. Never invent a tray.
export PATH=/system/bin:/system/xbin:/vendor/bin:$PATH
INTERVAL_S=${INTERVAL_S:-5}
EARLY=/system/bin/titan2-ims-simswitch-early.sh
[ -x "$EARLY" ] || EARLY=/data/local/tmp/titan2-ims-simswitch-early.sh
PIDF=/data/local/tmp/titan2_ims_simswitch_hold.pid
echo $$ >"$PIDF" 2>/dev/null || true
chmod 644 "$PIDF" 2>/dev/null || true
while true; do
  [ -x "$EARLY" ] && "$EARLY"
  sleep "$INTERVAL_S"
done
