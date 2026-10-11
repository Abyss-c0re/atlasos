#!/system/bin/sh
# Boot bridge for an image whose init unit still execs titan2-wifi-heal.sh.
# Init parses that unit before service.d. Run this from post-fs-data, before
# boot_completed starts the unit. It does not read or write Wi-Fi.
# A cooked image without the unit exits immediately.
MARK=/data/adb/titan2
muzzle=$MARK/titan2-wifi-muzzle.sh

bind_over() {
  src=$1
  dst=$2
  [ -f "$src" ] || return 0
  [ -e "$dst" ] || return 0
  mount --bind "$src" "$dst" 2>/dev/null || mount -o bind "$src" "$dst" 2>/dev/null || true
}

if grep -q titan2-wifi-heal /system/etc/init/titan2-wifi.rc 2>/dev/null \
    || grep -q titan2-wifi-heal /system/bin/titan2-pad-agent.sh 2>/dev/null; then
  mkdir -p "$MARK" 2>/dev/null || true
  if ! grep -q 'exec /system/bin/sleep 86400' "$muzzle" 2>/dev/null; then
    printf '%s\n' '#!/system/bin/sh' 'exec /system/bin/sleep 86400' > "$muzzle"
    chmod 755 "$muzzle" 2>/dev/null || true
  fi
  if [ -f "$muzzle" ]; then
    bind_over "$muzzle" /system/bin/titan2-wifi-heal.sh
    bind_over "$muzzle" /data/local/atlas-hybrid/lower/android/system/bin/titan2-wifi-heal.sh
    bind_over "$muzzle" /data/local/atlas-linux/android/system/bin/titan2-wifi-heal.sh
    bind_over "$muzzle" /data/local/atlas-hybrid/merge/android/system/bin/titan2-wifi-heal.sh
  fi
fi

# The image agent still launches that path. Use the userdata copy once it
# no longer names the unit.
if [ -f "$MARK/titan2-pad-agent.sh" ] \
    && ! grep -q titan2-wifi-heal "$MARK/titan2-pad-agent.sh" 2>/dev/null \
    && grep -q titan2-wifi-heal /system/bin/titan2-pad-agent.sh 2>/dev/null; then
  bind_over "$MARK/titan2-pad-agent.sh" /system/bin/titan2-pad-agent.sh
fi
exit 0
