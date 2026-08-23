#!/system/bin/sh
# Settings → SIMs → Calls is the only IMS tray switch.
# persist.radio.titan2_simswitch is a boot-early cache, never a second SoT.
# Vendor NVRAM resets persist.vendor.radio.simswitch to tray 1 — we put it back.
# Do not restart RIL. Do not write multi_sim_voice_call. No dumpsys (hangs).
export PATH=/system/bin:/system/xbin:/vendor/bin:$PATH

logt() { log -t titan2-ims-early "$1" 2>/dev/null || true; }

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

# 1-indexed tray from Settings Calls, or empty if phone/settings not up yet.
calls_want() {
  _sub=`settings get global multi_sim_voice_call 2>/dev/null | tr -d '\r\n '`
  case "$_sub" in
    [1-9]|[1-9][0-9]|[1-9][0-9][0-9]|[1-9][0-9][0-9][0-9]) ;;
    *) echo ""; return 0 ;;
  esac
  _slot=`content query --uri content://telephony/siminfo --projection sim_id \
    --where "_id=$_sub" 2>/dev/null \
    | sed -n 's/.*sim_id=\([0-9][0-9]*\).*/\1/p' | head -1`
  case "$_slot" in
    0|1) echo $((_slot + 1)) ;;
    *) echo "" ;;
  esac
}

want=
src=
_calls=`calls_want`
case "$_calls" in
  [12]) want=$_calls; src=settings_calls ;;
esac

# Boot-early only: phone/settings may be down at post-fs-data.
# After boot, persist is cache only. Do not poke a ghost tray if Calls mapping failed.
if [ -z "$want" ]; then
  _bc=$(getprop sys.boot_completed 2>/dev/null | tr -d '\r\n ')
  if [ "$_bc" = "1" ]; then
    logt "Calls tray gone after boot; not using persist cache"
    exit 0
  fi
  _p=$(getprop persist.radio.titan2_simswitch 2>/dev/null | tr -d '\r\n ')
  case "$_p" in
    [12]) want=$_p; src=persist.radio.titan2_simswitch ;;
  esac
fi
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
  [12]) ;;
  *)
    logt "no Calls tray yet"
    exit 0
    ;;
esac

have=$(getprop persist.vendor.radio.simswitch 2>/dev/null | tr -d '\r\n ')
cap=$(getprop persist.vendor.radio.c_capability_slot 2>/dev/null | tr -d '\r\n ')
radio=$(getprop persist.radio.simswitch 2>/dev/null | tr -d '\r\n ')
t2=$(getprop persist.radio.titan2_simswitch 2>/dev/null | tr -d '\r\n ')

# Cache Settings onto the persist the next post-fs can read.
if [ "$src" = "settings_calls" ] && [ "$t2" != "$want" ]; then
  setprop persist.radio.titan2_simswitch "$want" 2>/dev/null || true
  mkdir -p /data/unencrypted /data/misc/titan2 2>/dev/null || true
  echo "$want" > /data/unencrypted/titan2_tel_simswitch 2>/dev/null || true
  echo "$want" > /data/misc/titan2/titan2_tel_simswitch 2>/dev/null || true
  chmod 644 /data/unencrypted/titan2_tel_simswitch 2>/dev/null || true
  chmod 666 /data/misc/titan2/titan2_tel_simswitch 2>/dev/null || true
fi

if [ "$have" = "$want" ] && [ "$cap" = "$want" ] && [ "$radio" = "$want" ]; then
  exit 0
fi

set_v persist.vendor.radio.simswitch "$want"
set_v persist.vendor.radio.c_capability_slot "$want"
setprop persist.radio.simswitch "$want" 2>/dev/null || true
setprop persist.radio.titan2_simswitch "$want" 2>/dev/null || true
mkdir -p /data/unencrypted /data/misc/titan2 2>/dev/null || true
echo "$want" > /data/unencrypted/titan2_tel_simswitch 2>/dev/null || true
echo "$want" > /data/misc/titan2/titan2_tel_simswitch 2>/dev/null || true
chmod 644 /data/unencrypted/titan2_tel_simswitch 2>/dev/null || true
chmod 666 /data/misc/titan2/titan2_tel_simswitch 2>/dev/null || true
logt "simswitch=$want (from $src; was vendor=$have cap=$cap radio=$radio)"
exit 0
