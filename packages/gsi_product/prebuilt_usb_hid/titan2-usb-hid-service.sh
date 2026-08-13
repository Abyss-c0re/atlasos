#!/system/bin/sh
# Hybrid ROM entry: in-ROM stack is canonical (no Magisk required).
# Magisk module is optional only when system stack is absent.
# 0.16.19 / pad-agent 2.114: prefer staged tip with cube-load-park when system
# service is still pre-park (rootless lab residual after tip stage only).
SYS=/system/etc/titan2_usb_hid
MAG=/data/adb/modules/titan2_usb_hid
# Stack OK when enable_hid present and bridge is etc/hid_bridge OR GSI bin.
if [ -x "$SYS/enable_hid.sh" ] \
    && { [ -x "$SYS/hid_bridge" ] || [ -x /system/bin/titan2-hid-bridge ]; }; then
  export MODDIR=$SYS
elif [ -x "$MAG/enable_hid.sh" ] && [ -x "$MAG/hid_bridge" ] && [ ! -f "$MAG/disable" ]; then
  export MODDIR=$MAG
else
  export MODDIR=$SYS
fi
# Tip-first when tip has load-park and system/module does not (or tip is explicit).
_tip=""
if [ -x /data/local/tmp/titan2-usb-hid-service.sh ] \
    && grep -aqF 'cube-load-park' /data/local/tmp/titan2-usb-hid-service.sh 2>/dev/null; then
  _tip=/data/local/tmp/titan2-usb-hid-service.sh
else
  for _c in /data/local/tmp/titan2-usb-hid-service-*.sh; do
    [ -x "$_c" ] || continue
    grep -aqF 'cube-load-park' "$_c" 2>/dev/null || continue
    _tip="$_c"
    break
  done
fi
if [ -n "$_tip" ]; then
  _sys_has=0
  if [ -x "$SYS/service.sh" ] && grep -aqF 'cube-load-park' "$SYS/service.sh" 2>/dev/null; then
    _sys_has=1
  fi
  if [ "$_sys_has" != "1" ]; then
    exec /system/bin/sh "$_tip"
  fi
fi
if [ -x "$SYS/service.sh" ]; then
  exec /system/bin/sh "$SYS/service.sh"
fi
if [ -x "$MODDIR/service.sh" ]; then
  exec /system/bin/sh "$MODDIR/service.sh"
fi
echo "titan2-usb-hid: no service.sh" >&2
exit 1
