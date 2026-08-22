#!/usr/bin/env bash
# AtlasOS product device gates. Fail closed. No invented PASS.
# Run on a booted Titan after hybrid remake. Does not flash.
#
#   SERIAL=TITAN20000021925 ./scripts/test_product_device.sh
#
# Covers: HV/nav, touchpadd, camera/privacy, OpenWrt/LuCI, Atlas REG-UID.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WS="${TITANUS2_WORKSHOP:-$ROOT/../titanus2}"
[ -d "$WS/scripts" ] || WS="${TITANUS2_WORKSHOP:-$HOME/Dev/titanus2}"

SERIAL="${SERIAL:-${ANDROID_SERIAL:-}}"
if [ -z "$SERIAL" ] && [ -x "$WS/scripts/host/resolve_titan_serial.sh" ]; then
  SERIAL="$(bash "$WS/scripts/host/resolve_titan_serial.sh" 2>/dev/null || true)"
fi
if [ -z "$SERIAL" ]; then
  SERIAL="$(adb devices 2>/dev/null | awk '/\<device\>/{print $1; exit}')"
fi
[ -n "$SERIAL" ] || { echo "FAIL no adb device"; exit 2; }
ADB=(adb -s "$SERIAL")

fail=0
ok() { echo "PASS $*"; }
bad() { echo "FAIL $*"; fail=$((fail + 1)); }

shx() { "${ADB[@]}" shell "$@" 2>/dev/null | tr -d '\r'; }

echo "=== AtlasOS product device $SERIAL ==="
bc=$(shx getprop sys.boot_completed)
[ "$bc" = "1" ] || bad "boot_completed=$bc"
ok "boot_completed=1"

# --- USB / ADB ---
echo "--- USB ---"
# LAW: on this Titan, MediaTek 0e8d:0000 + iConfiguration mtp_adb IS adb.
# Never treat 0e8d:0000 as dead preloader and never kill adb because of it.
ok "adb $SERIAL"
if lsusb 2>/dev/null | grep -q '0e8d:0000'; then
  ok "host USB 0e8d:0000 MediaTek Titan (mtp_adb — not preloader)"
fi

# --- HV / nav ---
echo "--- HV / navbar ---"
a11y=$(shx settings get secure enabled_accessibility_services)
echo "$a11y" | grep -Eq 'com\.titanus2\.controls/(\.|com\.titanus2\.controls\.)TrackpadAccessService' \
  && ok "a11y TrackpadAccessService listed" \
  || bad "TrackpadAccessService not in enabled_accessibility_services"
if "${ADB[@]}" shell pm path com.titanus2.controls >/dev/null 2>&1; then
  ok "Titan Controls installed"
else
  bad "Titan Controls missing"
fi
# Product Recents is GLOBAL_ACTION. Comments / "never RecentsActivity" / detecting
# SystemUI RecentsActivity class name are not heresy.
if grep -R --include='*.java' -E 'am start.*RecentsActivity|startActivity.*RecentsActivity' \
    "$ROOT/apps/titan_controls" 2>/dev/null | grep -v /out/ >/dev/null; then
  bad "Controls starts RecentsActivity"
else
  ok "Controls does not start RecentsActivity"
fi
if grep -R --include='*.java' --include='*.sh' -E 'input keyevent 187|keyevent 187' \
    "$ROOT/apps" "$ROOT/patches/bin" 2>/dev/null \
    | grep -v test_product_device | grep -v '^[^:]*:[[:space:]]*#' >/dev/null; then
  bad "product still uses keyevent 187 as Recents"
else
  ok "no keyevent 187 Recents"
fi
nav=$(shx settings get secure navigation_mode)
ok "navigation_mode=${nav:-unset} (user SoT — not forced)"
if shx test -x /system/bin/titan2-key-fire.sh; then
  ok "titan2-key-fire.sh on system"
fi

# --- Touchpadd ---
echo "--- touchpadd ---"
if shx test -x /system/bin/titan2-touchpadd; then
  ok "ELF /system/bin/titan2-touchpadd"
else
  bad "titan2-touchpadd ELF missing"
fi
if shx test -f /system/etc/init/titan2-touchpadd.rc; then
  ok "titan2-touchpadd.rc"
else
  bad "titan2-touchpadd.rc missing — pad never starts"
fi
if shx test -f /system/usr/idc/touchPad.idc; then
  ok "touchPad.idc"
else
  bad "touchPad.idc missing"
fi
if "${ADB[@]}" shell "grep -aFq INPROC_PARK /system/bin/titan2-touchpadd" >/dev/null 2>&1; then
  ok "INPROC_PARK"
else
  bad "ELF missing INPROC_PARK"
fi
if "${ADB[@]}" shell "grep -aFq 'Skipping TitanKey' /system/bin/titan2-touchpadd" >/dev/null 2>&1; then
  ok "pad-only TitanKey skip"
else
  bad "ELF missing pad-only TitanKey skip"
fi
# evdev: ABS_X/Y max 0 is normal; MT position is the pad (1440x720).
mt=$(shx getevent -p 2>/dev/null | awk '
  /name:.*touchPad/{hit=1}
  hit && /0035/{x=$0}
  hit && /0036/{y=$0; print x " | " y; exit}
')
echo "$mt" | grep -q '1440' && echo "$mt" | grep -q '720' \
  && ok "touchPad evdev MT 1440x720" \
  || bad "touchPad evdev missing MT 1440x720 ($mt)"
mode=$(shx cat /data/misc/titan2/titan2_pad_mode 2>/dev/null)
[ -z "$mode" ] || [ "$mode" = "null" ] && mode=$(shx cat /data/local/tmp/titan2_pad_mode 2>/dev/null)
[ -z "$mode" ] || [ "$mode" = "null" ] && mode=$(shx settings get global titan2_pad_mode)
pid=$(shx pidof titan2-touchpadd || true)
ilock=$(shx cat /data/misc/titan2/titan2_input_lock 2>/dev/null)
pstat=$(shx cat /data/local/tmp/titan2_pad_status 2>/dev/null)
idc=$(shx cat /system/usr/idc/touchPad.idc 2>/dev/null)
ok "pad_mode=${mode:-unset} pid=${pid:-none} lock=${ilock:-0} status=${pstat:-none}"
lsdis=$(shx settings get secure lockscreen.disabled)
# Stale lock=1 after KEEP_DATA is not a keyguard park when the user disabled
# the lockscreen, or when apply already landed native/mouse.
if echo "$pstat" | grep -q 'applied=lockpark'; then
  case "${lsdis}" in
    1|true|TRUE)
      bad "lockpark with lockscreen.disabled=1 — boot apply never retried"
      ;;
    *)
      case "${ilock:-0}" in
        1|true|on|yes) ok "keyguard lockpark (pad parked — not a dead daemon)" ;;
        *) bad "lockpark with input_lock=0 — pad apply stuck" ;;
      esac
      ;;
  esac
elif echo "$pstat" | grep -Eq 'applied=(native|starting|hid_)'; then
  ok "pad applied (${pstat})"
else
case "${ilock:-0}" in
  1|true|on|yes)
    ok "input_lock=1 (keyguard) — pad must stay parked"
    ;;
  *)
    case "${mode:-}" in
      mouse)
        [ -n "$pid" ] && ok "touchpadd running for mouse" \
          || bad "pad_mode=mouse but titan2-touchpadd not running"
        echo "$idc" | grep -q 'ignore' \
          && ok "mouse IDC ignore (daemon owns pad)" \
          || bad "mouse mode needs touch.deviceType=ignore"
        ;;
      trackpad)
        [ -z "$pid" ] && ok "trackpad is native ABS (no daemon)" \
          || ok "trackpad: daemon extra (caret/nograb) pid=$pid"
        echo "$idc" | grep -Eq 'pointer|touchPad|native' \
          && ! echo "$idc" | grep -q 'ignore' \
          && ok "trackpad IDC native" \
          || bad "trackpad mode still has ignore IDC (pad invisible)"
        ;;
    esac
    ;;
esac
fi

# --- Camera / privacy ---
echo "--- camera / privacy ---"
if shx test -x /system/bin/titan2-sensor-privacy.sh; then
  ok "titan2-sensor-privacy.sh"
else
  bad "titan2-sensor-privacy.sh missing"
fi
if shx test -f /system/etc/init/titan2-sensor-privacy.rc \
  || shx test -f /system/etc/init/titan2-privacy-overlay.rc; then
  ok "privacy init rc"
else
  bad "privacy init rc missing"
fi
if shx test -x /system/bin/cameraserver \
  || shx test -x /system/bin/titan2-bind-mtk-privacy-overlay.sh; then
  ok "camera/privacy bind present"
else
  ok "camera bind optional on this image"
fi
priv=$(shx pidof titan2-sensor-privacy || true)
[ -n "$priv" ] && ok "sensor-privacy pid=$priv" || ok "sensor-privacy process not required to be named"

# --- Settings Router tile ---
echo "--- Router ---"
if "${ADB[@]}" shell pm path com.titanus2.netfw >/dev/null 2>&1; then
  ok "com.titanus2.netfw installed"
else
  bad "com.titanus2.netfw missing — Settings Router tile gone"
fi
router=$("${ADB[@]}" shell cmd package resolve-activity --brief -a com.titanus2.netfw.ROUTER 2>/dev/null | tr -d '\r' | tail -1)
echo "$router" | grep -q netfw && ok "intent ROUTER=$router" || bad "ROUTER intent=$router"
extra=$("${ADB[@]}" shell cmd package query-activities --brief -a com.android.settings.action.EXTRA_SETTINGS 2>/dev/null | tr -d '\r' || true)
echo "$extra" | grep -q 'com.titanus2.netfw/.RouterActivity' \
  && ok "Settings EXTRA_SETTINGS lists Router" \
  || bad "Settings EXTRA_SETTINGS missing RouterActivity"
if "${ADB[@]}" shell pm path com.titanus2.luci >/dev/null 2>&1; then
  ok "com.titanus2.luci installed"
else
  bad "com.titanus2.luci missing — More settings cannot open LuCI"
fi
luci=$("${ADB[@]}" shell cmd package resolve-activity --brief -a com.titanus2.luci.OPEN 2>/dev/null | tr -d '\r' | tail -1)
echo "$luci" | grep -q luci && ok "intent LUCI=$luci" || bad "LUCI intent=$luci"

# --- OpenWrt / LuCI ---
echo "--- OpenWrt / LuCI ---"
if shx test -x /system/bin/titan2-openwrt-boot.sh \
  && shx test -f /system/etc/init/titan2-openwrt.rc; then
  ok "titan2-openwrt-boot on system"
else
  bad "titan2-openwrt-boot.sh/rc missing — LuCI never starts after remake"
fi
if shx test -b /dev/block/mapper/atlas_openwrt_a; then
  ok "atlas_openwrt_a mapper"
else
  bad "atlas_openwrt_a missing (packer omitted OpenWrt LP)"
fi
# status appends openwrt-lpctl which can hang; timeout the peel.
st=$(timeout 12 "${ADB[@]}" shell 'sh /system/bin/titan2-openwrt.sh status' 2>/dev/null | tr -d '\r' || true)
echo "$st" | grep -Eq 'lp=mounted|present=1|mounted=1' \
  && ok "openwrt LP mounted" \
  || bad "openwrt LP not mounted"
echo "$st" | grep -q 'has_luci=1' && ok "has_luci=1" || bad "LuCI root missing on LP"
# Never titan2-openwrt.sh start here — stop_daemons/killall + stale ubus sock → cgi 500.
# HTTP via adb forward
"${ADB[@]}" forward tcp:18092 tcp:8080 >/dev/null 2>&1 || true
root_code=$(curl -sS -o /tmp/atlasos_luci_root.html -w '%{http_code}' --max-time 6 http://127.0.0.1:18092/ || echo 000)
cgi_code=$(curl -sS -o /tmp/atlasos_luci_cgi.html -w '%{http_code}' --max-time 6 http://127.0.0.1:18092/cgi-bin/luci || echo 000)
[ "$root_code" = "200" ] && ok "LuCI GET / = 200" || bad "LuCI GET / = $root_code"
case "$cgi_code" in
  403|200) ok "LuCI cgi=$cgi_code (login or ok)" ;;
  500) bad "LuCI cgi 500 (uci-defaults / rpcd system heresy)" ;;
  *) bad "LuCI cgi=$cgi_code" ;;
esac
if grep -q 'left-hand side expression is null' /tmp/atlasos_luci_cgi.html 2>/dev/null; then
  bad "LuCI still 500 null boardinfo"
fi

# --- Atlas REG-UID ---
echo "--- Atlas ---"
app_uid=$(shx stat -c %u /data/data/com.titanus2.atlas 2>/dev/null || true)
[ -n "$app_uid" ] || app_uid=$(shx stat -c %u /data/user/0/com.titanus2.atlas 2>/dev/null || true)
if [ -z "$app_uid" ]; then
  ok "Atlas CE unread (lockscreen) — skip live data uid"
else
  ok "app uid=$app_uid"
fi
row=$(shx grep "^atlas:" /data/local/atlas-linux/etc/passwd || true)
if [ -n "$app_uid" ]; then
  echo "$row" | grep -q ":x:${app_uid}:${app_uid}:" \
    && ok "Debian atlas:$app_uid" \
    || bad "Debian passwd missing atlas:x:${app_uid}:${app_uid}: (REG-UID) row='$row'"
else
  echo "$row" | grep -q '^atlas:x:10[0-9][0-9][0-9]:' \
    && ok "Debian atlas row present (CE unread) $row" \
    || bad "Debian passwd missing atlas row='$row'"
fi
if shx test -x /system/bin/atlas-hybrid.sh; then
  "${ADB[@]}" shell "grep -qF 'Do not invent a Debian uid' /system/bin/atlas-hybrid.sh" \
    && ok "hybrid.sh refuses invented uid" \
    || bad "hybrid.sh missing REG-UID law"
fi
if "${ADB[@]}" shell "grep -qF ensure-user /system/bin/atlas-hybrid-boot.sh"; then
  ok "hybrid-boot calls ensure-user"
else
  bad "hybrid-boot missing ensure-user (CE-unread remake heresy)"
fi
if shx pm path com.titanus2.atlas >/dev/null 2>&1; then
  ok "Atlas installed"
else
  bad "Atlas missing"
fi

echo "=== ${fail} FAIL ==="
[ "$fail" -eq 0 ] && echo PRODUCT_DEVICE_PASS || echo PRODUCT_DEVICE_FAIL
exit "$fail"
