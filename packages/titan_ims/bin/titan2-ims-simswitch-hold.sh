#!/system/bin/sh
# Standing hold: vendor NVRAM resets persist.vendor.radio.simswitch to tray 1.
# Re-read Settings → SIMs → Calls every tick. Never invent a tray.
# On SIM state change: VoLTE + ImsService on every present tray. Incoming
# is not Settings Calls and does not care which physical slot a SIM is in.
export PATH=/system/bin:/system/xbin:/vendor/bin:$PATH
INTERVAL_S=${INTERVAL_S:-5}
EARLY=/system/bin/titan2-ims-simswitch-early.sh
[ -x "$EARLY" ] || EARLY=/data/local/tmp/titan2-ims-simswitch-early.sh
PIDF=/data/local/tmp/titan2_ims_simswitch_hold.pid
LAST_SIM=
echo $$ >"$PIDF" 2>/dev/null || true
chmod 644 "$PIDF" 2>/dev/null || true

ims_slot_loaded() {
  _i=$1
  _st=`getprop gsm.sim.state 2>/dev/null | tr -d '\r\n '`
  _n=0
  _oldifs=$IFS
  IFS=,
  for _p in $_st; do
    if [ "$_n" = "$_i" ]; then
      IFS=$_oldifs
      case "$_p" in LOADED|READY|IMSI) return 0 ;; *) return 1 ;; esac
    fi
    _n=$((_n + 1))
  done
  IFS=$_oldifs
  return 1
}

ims_dual_present() {
  for _s in 0 1; do
    ims_slot_loaded "$_s" || continue
    cmd phone cc set-value -s "$_s" -p carrier_volte_available_bool true 2>/dev/null || true
    cmd phone cc set-value -s "$_s" -p carrier_volte_provisioned_bool true 2>/dev/null || true
    cmd phone ims set-ims-service -s "$_s" -c com.mediatek.ims 2>/dev/null || true
    cmd phone ims set-ims-service -s "$_s" -d com.mediatek.ims 2>/dev/null || true
    cmd phone ims enable -s "$_s" 2>/dev/null || true
  done
}

while true; do
  [ -x "$EARLY" ] && "$EARLY"
  _now=`getprop gsm.sim.state 2>/dev/null | tr -d '\r\n '`
  if [ "$_now" != "$LAST_SIM" ]; then
    LAST_SIM=$_now
    ims_dual_present
  fi
  sleep "$INTERVAL_S"
done
