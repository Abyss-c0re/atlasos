#!/system/bin/sh
# Pre-cook bridge for the live phone. KernelSU service.d.
# titan2-overlay-retire.sh is the one decider: a userdata script stays
# bind-mounted only while its ATLAS_OVERLAY_STAMP is newer than the image.
# Once the image has that generation, the userdata and tmp copies move
# aside so the cooked script is the only one. This file then rebinds a
# bad home. It does not copy scripts into /data/local/tmp.
# SAE upgrade is a static overlay. While this image's init unit still
# names titan2-wifi-heal, 83 binds a sleeper over that path.
MARK=/data/adb/titan2
RETIRE=$MARK/titan2-overlay-retire.sh
[ -f "$RETIRE" ] || RETIRE=/system/bin/titan2-overlay-retire.sh
if [ -f "$RETIRE" ]; then
  /system/bin/sh "$RETIRE" || true
fi
if [ -x "$MARK/83-atlas-wifi-muzzle.sh" ]; then
  /system/bin/sh "$MARK/83-atlas-wifi-muzzle.sh"
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
