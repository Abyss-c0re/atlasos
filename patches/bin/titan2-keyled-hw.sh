#!/system/bin/sh
# Expose keyboard backlight as a hardware-class LED for Android SystemUI options.
#
# Titan has no discrete RGB notification LED. We:
#   1) Prefer real /sys/class/leds/* if OEM already registered keypad_led
#   2) Else bind-mount keypad sysfs into /sys/class/leds/notifications
#   3) Enable Settings "Pulse notification light" so SystemUI treats it as present
#   4) Pad-agent + NotifLed still drive brightness; this makes the OS option real
#
# Safe to re-run. Never dumpsys. Root via init.

export PATH=/system/bin:/system/xbin:/vendor/bin:$PATH
LOG=/data/local/tmp/titan2_keyled_hw.log
log() { echo "$(date +%Y%m%dT%H%M%S) $*" >>"$LOG" 2>/dev/null; chmod 644 "$LOG" 2>/dev/null; }

SRC=""
for c in \
  /sys/devices/platform/keypad_led/keyled_brightness \
  /sys/class/misc/keypad_led/keyled_brightness \
  /sys/class/leds/keypad_led/brightness \
  /sys/class/leds/keyboard_backlight/brightness
do
  [ -e "$c" ] || continue
  SRC=$c
  break
done
[ -n "$SRC" ] || { log "no keypad led sysfs — skip"; exit 0; }

# Ensure a class/leds node named notifications (Lights HAL / Settings look here)
LED_DIR=/sys/class/leds/notifications
if [ ! -e "$LED_DIR/brightness" ]; then
  # Create under /data and bind if class is not writable
  STAGE=/data/local/tmp/titan2_leds/notifications
  mkdir -p "$STAGE" 2>/dev/null
  # shadow brightness file that writes through to real keypad
  cat >"$STAGE/brightness" <<'SH' 2>/dev/null || true
0
SH
  # Use a small helper FIFO/proxy: write path file for agent
  echo "$SRC" > /data/local/tmp/titan2_keyled_path 2>/dev/null
  chmod 666 /data/local/tmp/titan2_keyled_path 2>/dev/null
  # Best-effort bind of parent class
  if [ -d /sys/class/leds ]; then
    # Cannot create new class entries without kernel; leave path for pad-agent.
    log "keypad src=$SRC (class/leds/notifications not creatable — agent uses SRC)"
  fi
else
  log "have $LED_DIR"
fi

# Expose that a notif LED exists for Settings UI, but do NOT enable pulse by
# default — SystemUI pulse re-drives keypad_led and leaves the keyboard glowing
# against pad-agent idle/off (QA 2026-07-27).
settings put system notification_light_pulse 0 2>/dev/null || true
settings put system notification_light_pulse_custom_enable 0 2>/dev/null || true
setprop persist.sys.notification_light 1 2>/dev/null || true
setprop ro.hardware.notification_led 1 2>/dev/null || true

# Default: do not leave keyboard on at boot (double-write — MTK flaky first 0)
echo 0 >"$SRC" 2>/dev/null || true
echo 0 >"$SRC" 2>/dev/null || true
echo "$SRC" > /data/local/tmp/titan2_keyled_path 2>/dev/null || true
chmod 666 /data/local/tmp/titan2_keyled_path 2>/dev/null || true
log "keyled-hw ready src=$SRC pulse=0"
exit 0
