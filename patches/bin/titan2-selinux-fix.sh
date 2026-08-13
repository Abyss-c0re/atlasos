#!/system/bin/sh
# Ensure TitanControls + pad binaries are system_file / phhsu_exec labeled.
export PATH=/system/bin:/system/xbin:$PATH

fix_tree() {
  p="$1"
  ctx="${2:-u:object_r:system_file:s0}"
  [ -e "$p" ] || return 0
  chcon -R "$ctx" "$p" 2>/dev/null || true
  restorecon -RF "$p" 2>/dev/null || true
}

fix_tree /system/priv-app/TitanControls
fix_tree /system/app/TitanControls
fix_tree /product/priv-app/TitanControls
fix_tree /system/etc/permissions/privapp-permissions-com.titanus2.controls.xml
fix_tree /system/etc/init/titan2-pad-agent.rc
fix_tree /system/etc/init/titan2-touchpadd.rc
fix_tree /system/etc/init/titan2-selinux.rc
fix_tree /system/etc/init/titan2_display.rc
fix_tree /system/bin/titan2-touchpadd
fix_tree /system/bin/titan2-selinux-fix.sh
# scripts executed via init as root
[ -f /system/bin/titan2-pad-agent.sh ] && chcon u:object_r:phhsu_exec:s0 /system/bin/titan2-pad-agent.sh 2>/dev/null || true
[ -f /system/bin/titan2-display.sh ] && chcon u:object_r:phhsu_exec:s0 /system/bin/titan2-display.sh 2>/dev/null || true
[ -f /system/bin/titan2-selinux-fix.sh ] && chcon u:object_r:phhsu_exec:s0 /system/bin/titan2-selinux-fix.sh 2>/dev/null || true
[ -f /system/bin/phh-on-boot.sh ] && chcon u:object_r:phhsu_exec:s0 /system/bin/phh-on-boot.sh 2>/dev/null || true
exit 0
