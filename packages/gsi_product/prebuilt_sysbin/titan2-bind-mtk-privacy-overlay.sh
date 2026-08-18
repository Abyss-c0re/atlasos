#!/system/bin/sh
# Bind Cube privacy-fixed FrameworkResOverlay over stock MTK (EROFS vendor).
# Stock forces config_supportsCamToggle=false — kills OS camera privacy.
# Must run early enough that OverlayManager sees the bound file (phh-on-boot +
# init titan2-privacy-overlay.rc). Safe to re-run (idempotent).
export PATH=/system/bin:/system/xbin:/vendor/bin:$PATH

# Prefer tip/data override (lab rebind without super flash), then system inject.
SRC=/data/local/tmp/titan2_privacy/FrameworkResOverlay.apk
[ -f "$SRC" ] || SRC=/system/etc/titan2_privacy/FrameworkResOverlay.apk
[ -f "$SRC" ] || SRC=/system/product/overlay/FrameworkResOverlay.cube-privacy.apk
[ -f "$SRC" ] || SRC=/product/overlay/FrameworkResOverlay.cube-privacy.apk
DST=/vendor/overlay/FrameworkResOverlay/FrameworkResOverlay.apk

[ -f "$SRC" ] || exit 0
[ -f "$DST" ] || exit 0

# Optional kill-switch
case "$(getprop persist.titan2.mtk_privacy_overlay 2>/dev/null | tr -d '\r')" in
  0|false|off|OFF) exit 0 ;;
esac

# Rebind if not mounted, or tip SRC md5 differs from currently visible DST.
need=1
if mount | grep -q " $DST "; then
  sm=$(md5sum "$SRC" 2>/dev/null | awk '{print $1}')
  dm=$(md5sum "$DST" 2>/dev/null | awk '{print $1}')
  if [ -n "$sm" ] && [ "$sm" = "$dm" ]; then
    need=0
  else
    umount "$DST" 2>/dev/null || umount -l "$DST" 2>/dev/null || true
  fi
fi
[ "$need" = 1 ] || exit 0

mount --bind "$SRC" "$DST" 2>/dev/null || exit 1
# SELinux: keep vendor overlay label if possible
chcon u:object_r:vendor_overlay_file:s0 "$DST" 2>/dev/null \
  || chcon u:object_r:system_file:s0 "$DST" 2>/dev/null \
  || true
exit 0
