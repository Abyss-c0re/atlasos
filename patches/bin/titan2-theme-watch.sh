#!/system/bin/sh
# ROM theme apply + boot restore. Root via init. No Magisk. No bind.
# Apply: Controls writes /data/misc/titan2/titan2_theme_wake
# Boot: chrome-restore after Monet so user picks survive reboot.
export PATH=/system/bin:/system/xbin:/product/bin:$PATH
ICONS=/system/bin/titan2-cube-icons.sh
T2=/data/misc/titan2
WAKE=$T2/titan2_theme_wake
WAKE2=/data/local/tmp/titan2_sp_wake
LOG=/data/local/tmp/titan2-theme-watch.log

logm() { echo "$(date '+%H:%M:%S') $*" >>"$LOG" 2>/dev/null || true; }

mkdir -p "$T2" /data/local/tmp 2>/dev/null || true
chmod 777 "$T2" 2>/dev/null || true
# Do not truncate pending Apply wakes. Only ensure the files exist.
[ -e "$WAKE" ] || : >"$WAKE"
[ -e "$WAKE2" ] || : >"$WAKE2"
chmod 666 "$WAKE" "$WAKE2" "$LOG" 2>/dev/null || true

# KEEP_DATA leftover: KSU/Magisk runs every executable in post-fs-data.d,
# including *.off. Those bind /data/adb/titan2/*.sh over ROM /system/bin
# so the packed display/icons never run. Product path is the image.
# This binary is new (not in the leftover bind list) — it must unbind first.
_drop_leftover_binds() {
  _dis=/data/adb/titan2/disabled
  mkdir -p "$_dis" 2>/dev/null || true
  for _s in /data/adb/post-fs-data.d/titan2-honor-dpi.sh \
            /data/adb/post-fs-data.d/titan2-honor-dpi.sh.off \
            /data/adb/post-fs-data.d/titan2-honor-dpi.sh.bak \
            /data/adb/post-fs-data.d/titan2-honor-dpi.sh.disabled; do
    [ -e "$_s" ] || continue
    chmod 000 "$_s" 2>/dev/null || true
    mv -f "$_s" "$_dis/" 2>/dev/null || rm -f "$_s" 2>/dev/null || true
    logm "moved leftover $(basename "$_s") out of post-fs-data.d"
  done
  for _n in titan2-display.sh titan2-ui-plane.sh titan2-cube-icons.sh \
            titan2-sensor-privacy.sh titan2-dens-sanitize.sh; do
    if mount | grep -q " /system/bin/$_n "; then
      umount "/system/bin/$_n" 2>/dev/null || umount -l "/system/bin/$_n" 2>/dev/null || true
      logm "umount leftover bind /system/bin/$_n"
    fi
    if [ -f "/data/adb/titan2/$_n" ]; then
      chmod 000 "/data/adb/titan2/$_n" 2>/dev/null || true
      mv -f "/data/adb/titan2/$_n" "$_dis/$_n" 2>/dev/null || true
      logm "disabled leftover /data/adb/titan2/$_n"
    fi
  done
}

_drop_leftover_binds

_hex6() {
  s=$(echo "$1" | tr -d '\r' | tr 'A-F' 'a-f' | sed 's/^0x//;s/^#//')
  case "$s" in
    null|""|*" "*) echo "" ;;
    ???????? ) echo "$s" | cut -c3-8 ;;
    ?????? ) echo "$s" ;;
    *) echo "" ;;
  esac
}

apply_wake() {
  w=$(echo "$1" | tr -d '\r\n \t')
  [ -x "$ICONS" ] || return 0
  case "$w" in
    app_icons|icons_preset|icons) sh "$ICONS" icons-preset >>"$LOG" 2>&1 ;;
    os_accent|accent_preset|accent) sh "$ICONS" accent-preset >>"$LOG" 2>&1 ;;
    nav_tint|nav_preset|navbar) sh "$ICONS" nav-preset >>"$LOG" 2>&1 ;;
    qs_bg|qs_preset|qs) sh "$ICONS" qs-bg >>"$LOG" 2>&1 ;;
    glass|glass_on) sh "$ICONS" glass >>"$LOG" 2>&1 ;;
    solid|glass_off) sh "$ICONS" solid >>"$LOG" 2>&1 ;;
    settings_icons) sh "$ICONS" settings-on >>"$LOG" 2>&1 ;;
    settings_off|settings-off) sh "$ICONS" settings-off >>"$LOG" 2>&1 ;;
    "") ;;
    *) NAV_RECREATE=0 sh "$ICONS" chrome-restore >>"$LOG" 2>&1 ;;
  esac
  logm "wake=$w"
}

# Boot: chrome + app icons. icons-preset wipes launcher cache only on mismatch.
# OverlayManager is often not ready at +4s — retry after boot_completed.
sleep 4
if [ -x "$ICONS" ]; then
  NAV_RECREATE=0 sh "$ICONS" chrome-restore >>"$LOG" 2>&1 || true
  logm "boot chrome-restore"
fi
_n=0
while [ $_n -lt 30 ]; do
  case "`getprop sys.boot_completed 2>/dev/null | tr -d '\r'`" in 1) break ;; esac
  sleep 1
  _n=$((_n + 1))
done
if [ -x "$ICONS" ]; then
  NAV_RECREATE=0 sh "$ICONS" chrome-restore >>"$LOG" 2>&1 || true
  logm "boot_completed chrome-restore"
  _sm=$(settings get global titan2_settings_mono 2>/dev/null | tr -d '\r')
  case "$_sm" in
    1|true|on)
      _cs=$(cmd overlay list --user 0 2>/dev/null | grep -c 'csimp_' || true)
      if [ "${_cs:-0}" -lt 5 ]; then
        sh "$ICONS" settings-on >>"$LOG" 2>&1 || true
        logm "boot settings-on retry csimp=$_cs"
      fi
      ;;
  esac
fi

TICK=0
while true; do
  for f in "$WAKE" "$WAKE2"; do
    if [ -s "$f" ]; then
      _w=$(tr -d '\r\n \t' <"$f" 2>/dev/null)
      : >"$f" 2>/dev/null || true
      chmod 666 "$f" 2>/dev/null || true
      apply_wake "$_w"
    fi
  done
  TICK=$((TICK + 1))
  # Monet systemui:accent comes back grey. If the human saved a pick, hold it.
  if [ $((TICK % 8)) -eq 0 ] && [ -x "$ICONS" ]; then
    if cmd overlay list --user 0 2>/dev/null | grep -q '\[x\].*com.android.systemui:accent'; then
      acc=$(_hex6 "$(settings get global titan2_ui_accent_argb 2>/dev/null)")
      case "$acc" in
        ??????)
          sh "$ICONS" accent-preset >>"$LOG" 2>&1 || true
          logm "monet accent overlay re-disabled"
          ;;
      esac
    fi
  fi
  sleep 1
done
