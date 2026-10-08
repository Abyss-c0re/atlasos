#!/system/bin/sh
# Pre-cook bridge for the live phone. KernelSU service.d.
# Once /system ships rebind_deb_views and REMOTE_ADB_ADDR=lan, the binds
# and tmp overrides are removed and this script only rebinds a bad home.
MARK=/data/adb/titan2
ST=/data/local/tmp

bind_over() {
  src=$1
  dst=$2
  [ -f "$src" ] || return 0
  mount --bind "$src" "$dst" 2>/dev/null || mount -o bind "$src" "$dst" 2>/dev/null || true
}

if ! grep -q rebind_deb_views /system/bin/atlas-home-img.sh 2>/dev/null; then
  bind_over "$MARK/atlas-home-img.sh" /system/bin/atlas-home-img.sh
fi
if ! grep -q rebind_deb_path /system/bin/atlas-hybrid.sh 2>/dev/null; then
  bind_over "$MARK/atlas-hybrid.sh" /system/bin/atlas-hybrid.sh
fi
if ! grep -q 'REMOTE_ADB_ADDR=lan' /system/bin/titan2-remote-adb.sh 2>/dev/null; then
  bind_over "$MARK/titan2-remote-adb.sh" /system/bin/titan2-remote-adb.sh
  [ -f "$MARK/titan2-remote-adb.sh" ] && cp -f "$MARK/titan2-remote-adb.sh" "$ST/titan2-remote-adb.sh" && chmod 755 "$ST/titan2-remote-adb.sh"
else
  rm -f "$ST/titan2-remote-adb.sh"
fi
if ! grep -q 'REMOTE_ADB_ADDR=lan' /system/bin/titan2-dev-action.sh 2>/dev/null; then
  bind_over "$MARK/titan2-dev-action.sh" /system/bin/titan2-dev-action.sh
  [ -f "$MARK/titan2-dev-action.sh" ] && cp -f "$MARK/titan2-dev-action.sh" "$ST/titan2-dev-action.sh" && chmod 755 "$ST/titan2-dev-action.sh"
else
  rm -f "$ST/titan2-dev-action.sh" "$ST/titan2-dev-action-live.sh"
fi

# Never bind the userdata stub. late_start can run this before the image
# is mounted; wait, then rebind. A missing image is left alone.
i=0
while [ "$i" -lt 30 ]; do
  if awk '$2=="/data/local/atlas-home" && $3=="ext4" {found=1} END{exit !found}' /proc/mounts; then
    if grep -q rebind_deb_views /system/bin/atlas-home-img.sh 2>/dev/null; then
      /system/bin/sh /system/bin/atlas-home-img.sh rebind
    elif [ -f "$MARK/atlas-home-img.sh" ]; then
      /system/bin/sh "$MARK/atlas-home-img.sh" rebind
    fi
    exit 0
  fi
  i=$((i + 1))
  sleep 2
done
