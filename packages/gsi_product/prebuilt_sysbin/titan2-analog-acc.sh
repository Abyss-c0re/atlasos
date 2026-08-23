#!/system/bin/sh
# OEM USB-C analog accessory is a codec headset (mt6369), not USB PCM.
DEX="${TITAN2_ANALOG_ACC_DEX:-/system/etc/titan2_audio/acc.dex}"
[ -f "$DEX" ] || DEX=/data/adb/modules/titan2_analog_acc/system/etc/titan2_audio/acc.dex
# Analog jack is wired HP. USB audio routing only produces a false error.
settings put secure usb_audio_automatic_routing_disabled 1 2>/dev/null || true
last=x
connect() {
  CLASSPATH="$DEX" /system/bin/app_process /system/bin AccConnect 0x8 "$1" >/dev/null 2>&1 || true
}
while true; do
  acc=""
  [ -r /sys/class/typec/port0-partner/accessory_mode ] && acc=$(cat /sys/class/typec/port0-partner/accessory_mode 2>/dev/null)
  want=0
  [ "$acc" = analog_audio ] && want=1
  if [ "$want" != "$last" ]; then
    connect "$want"
    last=$want
  fi
  sleep 2
done
