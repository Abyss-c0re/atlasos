#!/system/bin/sh
# Titan 2 post-boot display + UI defaults (SHIPPED IN ROM via hybrid pack).
#
# Product (bundled): tablet OS plane — Settings two-pane, no dens thrash.
#   - dens 300 @ physical 1440×1440 → SW≈768 (≥600 tablet)
#   - no phone-mode size shrink (would kill dual-pane Settings)
#   - no foreground dens switch (visible flash into Settings)
#   - no home-clock overlay
#   - gestural nav + taskbar off + square icons + cube UX
#
# Opt-in only:
#   persist.titanus2.launcher_phone=1 → phone size SW<600 (Settings single-pane)
#   persist.titanus2.cube_density=N   → override dens (200–480)
#   persist.titanus2.nav=3button

export PATH=/system/bin:/system/xbin:$PATH

LOGTAG=titan2-display
MARKER=/data/misc/titan2/ui_defaults_v4_tablet
LOG=/data/local/tmp/titan2-display.log
CUBE_DENS_DEFAULT=300

logm() {
  echo "$1" >>"$LOG" 2>/dev/null || true
  log -t "$LOGTAG" "$1" 2>/dev/null || true
}

: >"$LOG" 2>/dev/null || true
logm "start uid=$(id -u 2>/dev/null) boot=$(getprop sys.boot_completed)"

i=0
while [ $i -lt 60 ]; do
  if [ "$(getprop sys.boot_completed)" = "1" ]; then
    break
  fi
  i=$((i+1))
  sleep 1
done

sleep 2

mkdir -p /data/misc/titan2 2>/dev/null || true
chmod 777 /data/misc/titan2 2>/dev/null || true

# --- USB safety ---
case "$(getprop persist.sys.usb.config)" in
  titan_hid|none|"")
    setprop persist.sys.usb.config mtp,adb 2>/dev/null || true
    logm "reset persist.sys.usb.config -> mtp,adb"
    ;;
esac
case "$(getprop sys.usb.config)" in
  titan_hid)
    if [ -x /system/etc/titan2_usb_hid/enable_hid.sh ]; then
      /system/bin/sh /system/etc/titan2_usb_hid/enable_hid.sh off 2>/dev/null || true
    else
      setprop sys.usb.config mtp,adb 2>/dev/null || true
    fi
    logm "restored sys.usb.config from titan_hid"
    ;;
esac

# --- Product tablet dens (bundled) ---
cube_dens=$(getprop persist.titanus2.cube_density 2>/dev/null | tr -d '\r')
case "$cube_dens" in
  ''|*[!0-9]*) cube_dens=$CUBE_DENS_DEFAULT ;;
esac
[ "$cube_dens" -ge 200 ] 2>/dev/null || cube_dens=$CUBE_DENS_DEFAULT
[ "$cube_dens" -le 480 ] 2>/dev/null || cube_dens=$CUBE_DENS_DEFAULT
# Persist product default so reboots stay consistent without host scripts
setprop persist.titanus2.cube_density "$cube_dens" 2>/dev/null || true

settings delete secure display_density_forced 2>/dev/null || true
settings delete system display_density_forced 2>/dev/null || true
cmd window density "$cube_dens" 2>/dev/null || wm density "$cube_dens" 2>/dev/null || true
logm "cube density=$cube_dens (tablet SW≈$((1440 * 160 / cube_dens)))"

# --- Physical size (Settings two-pane) ---
launcher_phone=$(getprop persist.titanus2.launcher_phone 2>/dev/null | tr -d '\r')
if [ "$launcher_phone" = "1" ]; then
  dens=$cube_dens
  px=$((580 * dens / 160))
  px=$(( (px / 8) * 8 ))
  [ "$px" -ge 720 ] || px=720
  [ "$px" -le 1440 ] || px=1440
  wm size "${px}x${px}" 2>/dev/null || cmd window size "${px}x${px}" 2>/dev/null || true
  logm "launcher_phone=1 wm size ${px}x${px} (Settings two-pane OFF)"
  changed=1
else
  wm size reset 2>/dev/null || cmd window size reset 2>/dev/null || true
  logm "wm size physical (tablet Settings two-pane)"
  changed=1
fi

# Kill dens-thrash / overlay experiments (product)
settings put secure titan2_ui_plane_switch 0 2>/dev/null || true
settings put secure titan2_home_clock 0 2>/dev/null || true

# --- Navigation: user Settings SoT only ---
# Never rewrite navigation_mode on boot. Optional one-shot via prop only when
# human set persist.titanus2.nav=3button|gestural explicitly (lab/tooling).
nav_pref=$(getprop persist.titanus2.nav 2>/dev/null | tr -d '\r')
if [ "$nav_pref" = "3button" ]; then
  cmd overlay disable com.android.internal.systemui.navbar.gestural 2>/dev/null || true
  cmd overlay enable com.android.internal.systemui.navbar.threebutton 2>/dev/null || true
  settings put secure navigation_mode 0 2>/dev/null || true
  logm "nav=3button (persist.titanus2.nav)"
elif [ "$nav_pref" = "gestural" ] || [ "$nav_pref" = "gesture" ]; then
  cmd overlay disable com.android.internal.systemui.navbar.threebutton 2>/dev/null || true
  cmd overlay disable com.android.internal.systemui.navbar.twobutton 2>/dev/null || true
  cmd overlay enable com.android.internal.systemui.navbar.gestural 2>/dev/null || true
  settings put secure navigation_mode 2 2>/dev/null || true
  logm "nav=gestural (persist.titanus2.nav)"
else
  logm "nav=user ($(settings get secure navigation_mode 2>/dev/null | tr -d '\r')) — not rewritten"
fi

settings put global settings_enable_monitor_phantom_procs false 2>/dev/null || true
setprop persist.sys.display.clear_cover 0 2>/dev/null || true

settings put system enable_taskbar 0 2>/dev/null || true
settings put system lineage_enable_taskbar 0 2>/dev/null || true

# --- Full square chrome DROPPED (FGS pill NPE + mangled Settings) ---
# Product Cube = cubemask icon only; never re-arm Settings/SystemUI zero-radius RROs.
for _ov in com.titanus2.overlay.iconshape com.titanus2.overlay.settings_square com.titanus2.overlay.systemui_square; do
  cmd overlay disable --user current "$_ov" 2>/dev/null \
    || cmd overlay disable --user 0 "$_ov" 2>/dev/null \
    || cmd overlay disable "$_ov" 2>/dev/null || true
done
cmd overlay enable --user current com.titanus2.overlay.cubemask 2>/dev/null \
  || cmd overlay enable --user 0 com.titanus2.overlay.cubemask 2>/dev/null \
  || cmd overlay enable com.titanus2.overlay.cubemask 2>/dev/null || true
unset _ov

if [ "$changed" = "1" ] || [ ! -f "$MARKER" ]; then
  am force-stop com.android.launcher3 2>/dev/null || true
  logm "restarted launcher3 for device profile"
fi

if [ ! -f "$MARKER" ]; then
  for db in \
    /data/user_de/0/com.android.launcher3/databases/launcher.db \
    /data/user_de/0/com.android.launcher3/databases/app_icons.db \
    /data/data/com.android.launcher3/databases/launcher.db
  do
    [ -f "$db" ] || continue
    sqlite3 "$db" "DELETE FROM favorites WHERE container=-101;" 2>/dev/null || true
    logm "cleared hotseat in $db"
  done
  date -u +%Y%m%dT%H%M%SZ >"$MARKER" 2>/dev/null || echo 1 >"$MARKER"
  logm "ui defaults applied (tablet dens $cube_dens, physical size, no square RROs)"
fi

# Cube UX runner. Prefer /system product script.
# 2026-08-03: KEEP_DATA left a July tip at /data/local/tmp/titan2-cube-ux.sh
# (v10 sole-LatinIME pin). Old logic preferred any tip with dim-with-uid →
# reboot re-forced AOSP keyboard over user choice. Tip only wins if its
# Version: N is strictly greater than system (lab hot-tip path).
_cube_ux_tip=/data/local/tmp/titan2-cube-ux.sh
_cube_ux_sys=/system/bin/titan2-cube-ux.sh
_cube_ux_ver() {
  # "Version: 12 (...)" in header → 12
  sed -n 's/.*Version: *\([0-9][0-9]*\).*/\1/p' "$1" 2>/dev/null | head -1
}
_sys_ver=$(_cube_ux_ver "$_cube_ux_sys")
_tip_ver=$(_cube_ux_ver "$_cube_ux_tip")
case "$_sys_ver" in ''|*[!0-9]*) _sys_ver=0 ;; esac
case "$_tip_ver" in ''|*[!0-9]*) _tip_ver=0 ;; esac
_run_ux=
if [ -f "$_cube_ux_tip" ] && [ "$_tip_ver" -gt "$_sys_ver" ] 2>/dev/null; then
  _run_ux=$_cube_ux_tip
  logm "cube-ux tip v$_tip_ver > system v$_sys_ver"
elif [ -f "$_cube_ux_sys" ]; then
  _run_ux=$_cube_ux_sys
  # Stale tip poison: drop if not newer (KEEP_DATA residual)
  if [ -f "$_cube_ux_tip" ] && [ "$_tip_ver" -le "$_sys_ver" ] 2>/dev/null; then
    rm -f "$_cube_ux_tip" 2>/dev/null || true
    logm "cube-ux dropped stale tip v$_tip_ver (system v$_sys_ver)"
  fi
fi
if [ -n "$_run_ux" ]; then
  if [ -x "$_run_ux" ]; then
    "$_run_ux" 2>/dev/null || true
  else
    /system/bin/sh "$_run_ux" 2>/dev/null || true
  fi
fi
unset _cube_ux_tip _cube_ux_sys _sys_ver _tip_ver _run_ux

logm "done: ROM tablet plane dens=$cube_dens launcher_phone=${launcher_phone:-0}"
exit 0
