#!/system/bin/sh
# Apply persist.vendor.radio.simswitch BEFORE rild reads NVRAM tray 1.
# Settings → SIMs → Calls is the only switch. Phone writes
# persist.radio.titan2_simswitch (radio-owned). This script (root) copies
# that onto vendor simswitch via resetprop_phh. Do not restart RIL.
export PATH=/system/bin:/system/xbin:/vendor/bin:$PATH

logt() { log -t titan2-ims-early "$1" 2>/dev/null || true; }

want=
src=
# 1) Settings Calls SoT (phone process, survives reboot).
_p=$(getprop persist.radio.titan2_simswitch 2>/dev/null | tr -d '\r\n ')
case "$_p" in
  [1234]) want=$_p; src=persist.radio.titan2_simswitch ;;
esac
# 2) DE file (readable at post-fs-data). /data/misc is often CE.
if [ -z "$want" ]; then
  for f in /data/unencrypted/titan2_tel_simswitch /data/misc/titan2/titan2_tel_simswitch; do
    if [ -s "$f" ]; then
      want=$(tr -d '\r\n ' <"$f")
      src=$f
      break
    fi
  done
fi
case "$want" in
  [1234]) ;;
  *)
    logt "no Calls simswitch want yet"
    exit 0
    ;;
esac

have=$(getprop persist.vendor.radio.simswitch 2>/dev/null | tr -d '\r\n ')
cap=$(getprop persist.vendor.radio.c_capability_slot 2>/dev/null | tr -d '\r\n ')
radio=$(getprop persist.radio.simswitch 2>/dev/null | tr -d '\r\n ')
if [ "$have" = "$want" ] && [ "$cap" = "$want" ] && [ "$radio" = "$want" ]; then
  exit 0
fi

set_v() {
  _k=$1; _v=$2
  if [ -x /system/bin/resetprop_phh ]; then
    /system/bin/resetprop_phh "$_k" "$_v" 2>/dev/null || true
  elif [ -x /data/adb/ksu/bin/resetprop ]; then
    /data/adb/ksu/bin/resetprop "$_k" "$_v" 2>/dev/null || true
  else
    setprop "$_k" "$_v" 2>/dev/null || true
  fi
}

set_v persist.vendor.radio.simswitch "$want"
set_v persist.vendor.radio.c_capability_slot "$want"
setprop persist.radio.simswitch "$want" 2>/dev/null || true
mkdir -p /data/unencrypted /data/misc/titan2 2>/dev/null || true
echo "$want" > /data/unencrypted/titan2_tel_simswitch 2>/dev/null || true
echo "$want" > /data/misc/titan2/titan2_tel_simswitch 2>/dev/null || true
chmod 644 /data/unencrypted/titan2_tel_simswitch 2>/dev/null || true
chmod 666 /data/misc/titan2/titan2_tel_simswitch 2>/dev/null || true
logt "simswitch=$want (from $src; was vendor=$have cap=$cap radio=$radio)"
# Do not kill ImsService. SubscriptionManagerService.resetIms() recreates
# MMTEL on the Calls tray. Killing mid-registration drops Voice.
exit 0
