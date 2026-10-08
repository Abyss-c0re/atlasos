#!/usr/bin/env bash
# Preflight: tip SoT ready for MisterZtr GSI product stage (no hybrid inject).
# Exit 0 only if tip APKs/binaries/scripts match expected product markers.
#
#   ./scripts/misterztr/preflight_gsi_product.sh
#   ./scripts/misterztr/stage_gsi_product.sh   # after pass
#   ./scripts/misterztr/pipeline.sh --from=patch --pack lab_rootless
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

ec=0
ok() { echo "OK  $*"; }
bad() { echo "FAIL $*"; ec=1; }

AAPT=""
for _sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Android/Sdk" \
    /opt/android-sdk /usr/lib/android-sdk; do
  [ -n "$_sdk" ] || continue
  AAPT=$(ls -d "$_sdk"/build-tools/*/aapt 2>/dev/null | sort -V | tail -1 || true)
  [ -n "$AAPT" ] && [ -x "$AAPT" ] && break
done
[ -n "$AAPT" ] || AAPT=$(command -v aapt 2>/dev/null || true)
export AAPT_BIN="${AAPT:-}"

apk_ver() {
  # prints versionCode versionName
  [ -f "$1" ] || return 1
  [ -n "${AAPT_BIN:-}" ] && [ -x "$AAPT_BIN" ] || return 1
  local line
  line=$("$AAPT_BIN" dump badging "$1" 2>/dev/null | head -1) || return 1
  # package: name='…' versionCode='N' versionName='…'
  local code name
  code=$(printf '%s' "$line" | sed -n "s/.*versionCode='\([0-9][0-9]*\)'.*/\1/p")
  name=$(printf '%s' "$line" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p")
  [ -n "$code" ] && [ -n "$name" ] || return 1
  printf '%s %s\n' "$code" "$name"
}

# --- Tip APKs (product session SoT) ---
CTRL="${ROOT}/apps/titan_controls/TitanControls-v2.apk"
[ -f "$CTRL" ] || CTRL="${ROOT}/apps/titan_controls/TitanControls.apk"
USB="${ROOT}/apps/titan_usb_hid/TitanUsbHid.apk"
CUBE="${ROOT}/apps/cube_contact/CubeContact.apk"

if [ -f "$CTRL" ]; then
  v=$(apk_ver "$CTRL" || true)
  case " $v " in
    *" 614 16.14"*|*" 614 "*) ok "TitanControls tip $v" ;;
    *)
      # accept any ≥614 with 16.x (16.14 pad-orient + chords + act-as-key)
      # or leftover ≥540 / 15.x only if 16.x APK is missing (should not ship)
      code=${v%% *}; name=${v#* }
      if [ -n "$code" ] && [ "$code" -ge 614 ] 2>/dev/null && [[ "$name" == 16.* ]]; then
        ok "TitanControls tip $v"
      else
        bad "TitanControls tip want ≥614/16.* got '$v' ($CTRL)"
      fi
      ;;
  esac
else
  bad "missing Controls APK"
fi

if [ -f "$USB" ]; then
  v=$(apk_ver "$USB" || true)
  code=${v%% *}; name=${v#* }
  if [ -n "$code" ] && [ "$code" -ge 218 ] 2>/dev/null && [[ "$name" == 2.* ]]; then
    ok "TitanUsbHid tip $v"
  else
    bad "TitanUsbHid tip want ≥218/2.* got '$v' ($USB)"
  fi
else
  bad "missing TitanUsbHid.apk"
fi

if [ -f "$CUBE" ]; then
  v=$(apk_ver "$CUBE" || true)
  code=${v%% *}; name=${v#* }
  if [ -n "$code" ] && [ "$code" -ge 94 ] 2>/dev/null; then
    ok "CubeContact tip $v"
  else
    bad "CubeContact tip want ≥94 got '$v'"
  fi
else
  bad "missing CubeContact.apk"
fi

# --- touchpadd ---
TP="${ROOT}/packages/gsi_product/prebuilt_touchpadd/titan2-touchpadd"
if [ ! -x "$TP" ] && [ -x "$ROOT/scripts/build_touchpadd.sh" ]; then
  echo "==> touchpadd ELF missing — AtlasOS musl build"
  "$ROOT/scripts/build_touchpadd.sh" || true
fi
if [ -x "$TP" ]; then
  grep -aF 'INPROC_PARK' "$TP" >/dev/null && ok "touchpadd INPROC_PARK" \
    || bad "touchpadd missing INPROC_PARK"
  grep -aF 'left latch ON' "$TP" >/dev/null && ok "touchpadd double-tap left latch" \
    || bad "touchpadd missing left latch — rebuild patches"
  grep -aF 'Skipping TitanKey (KEYBOARD_FEATURES off)' "$TP" >/dev/null \
    && ok "touchpadd pad-only TitanKey skip" \
    || bad "touchpadd missing pad-only marker"
else
  bad "missing $TP"
fi

A11Y="${ROOT}/apps/titan_controls/src/com/titanus2/controls/TrackpadAccessService.java"
if grep -q 'PWM TitanNavKeyRule owns factory' "$A11Y" 2>/dev/null \
    && grep -q 'return false;' "$A11Y" 2>/dev/null; then
  # only fail if the yield-to-PWM block is still next to that comment
  if grep -A6 'PWM TitanNavKeyRule owns factory' "$A11Y" | grep -q 'return false'; then
    bad "a11y yields Home/Recents to missing PWM rule (dead nav on kitchen GSI)"
  else
    ok "a11y does not yield Home/Recents to PWM"
  fi
else
  ok "a11y owns Home/Recents (no PWM yield)"
fi
if grep -q 'Hide IME' "${ROOT}/apps/titan_controls/src/com/titanus2/controls/MainActivity.java" \
    && grep -q 'Hide IME' "${ROOT}/apps/titan_controls/src/com/titanus2/controls/NetworkActivity.java" \
    && grep -q 'titan2-ime-bar.sh' "${ROOT}/patches/bin/titan2-pad-agent.sh" \
    && grep -q 'titan2-ime-bar.sh' "${ROOT}/packages/gsi_product/titanus2.mk" \
    && grep -q 'want_hide' "${ROOT}/patches/bin/titan2-ime-bar.sh" \
    && grep -q 'config_imeDrawsImeNavBar">false' "${ROOT}/packages/gsi_product/overlays/TitanImeNavBarOverlay/res/values/config.xml"; then
  ok "Hide IME toggle drives the nav-bar overlay"
else
  bad "Hide IME toggle is not wired to the overlay"
fi
KL="${ROOT}/patches/keylayout/TitanKey.kl"
if grep -qE '^key 580[[:space:]]+F24' "$KL" 2>/dev/null \
    && grep -qE '^key 158[[:space:]]+BACK' "$KL" 2>/dev/null; then
  ok "TitanKey.kl 580=F24 158=BACK"
else
  bad "TitanKey.kl missing essential 580 F24 / 158 BACK"
fi

# --- pad-apply / peels ---
PA="${ROOT}/patches/bin/titan2-pad-apply.sh"
AG="${ROOT}/patches/bin/titan2-pad-agent.sh"
TW="${ROOT}/patches/bin/titan2-typing-watch.sh"
# 2.215-rot-0-3 landed; tip is 2.234-login-gate (pad off until login).
# 2.251 keeps the 2.237-sub-hid token so the agent still selects this apply.
if grep -qE 'PAD_APPLY_VER=2\.(21[5-9]|22[0-9]|23[0-9])' "$PA" 2>/dev/null \
    || grep -q '2.215-rot-0-3' "$PA" 2>/dev/null \
    || grep -q '2.234-login-gate' "$PA" 2>/dev/null; then
  ok "pad-apply tip $(grep -m1 '^PAD_APPLY_VER=' "$PA" | cut -d= -f2-)"
else
  bad "pad-apply missing 2.215+ / 2.234-login-gate marker"
fi
if grep -q 'PAD_APPLY_VER=2.237-sub-hid' "$PA" 2>/dev/null \
    && grep -q 'Off first' "$PA" 2>/dev/null; then
  ok "pad-apply Off is applied before settings"
else
  bad "pad-apply can swallow Off (need 2.237-sub-hid and Off-first)"
fi
if grep -q '2.251-heat-pad-switch' "$AG" 2>/dev/null \
    && grep -q '_heat_pad_switch' "$AG" 2>/dev/null \
    && grep -q '_pad_spare_daemon' "$AG" 2>/dev/null; then
  ok "pad-agent applies a mode edge while hot"
else
  bad "pad-agent heat path skips Off/Trackpad/Mouse"
fi
if grep -q 'TW_VER=2.251-pad-mode-mtime' "$TW" 2>/dev/null; then
  ok "typing-watch follows the newest pad mode file"
else
  bad "typing-watch can stamp mouse over a newer Off"
fi
HC="${ROOT}/apps/titan_usb_hid/src/com/titanus2/usbhid/HidControl.java"
if grep -A20 'void endSessionAndRestore' "$HC" 2>/dev/null | grep -q 'ensureTouchpaddAlive'; then
  bad "HID restore starts touchpadd (trackpad must stay native)"
else
  ok "HID restore leaves touchpadd to pad-agent"
fi
PC="${ROOT}/apps/titan_controls/src/com/titanus2/controls/PadModeController.java"
if grep -q 'C7 mouse-only touchpadd' "$PC" 2>/dev/null \
    && grep -q 'S-PAD-01 spare' "$PC" 2>/dev/null \
    && grep -A30 'void ensureTouchpaddProcess(Context' "$PC" 2>/dev/null \
        | grep -q 'MOUSE.equals'; then
  ok "Controls starts touchpadd only for mouse"
else
  bad "Controls starts touchpadd on trackpad (C7)"
fi
if grep -A40 'void prepareDriverPad' "$HC" 2>/dev/null | grep -q 'pad-agent.lockdir'; then
  ok "HID does not start touchpadd while pad-agent owns mouse"
else
  bad "HID starts touchpadd while pad-agent is live (S-PAD-02)"
fi
[ -f "${ROOT}/packages/gsi_product/prebuilt_touchpadd/titan2-virtual-mouse.idc" ] \
  && ok "titan2-virtual-mouse.idc present" \
  || bad "missing titan2-virtual-mouse.idc"

SP="${ROOT}/patches/bin/titan2-sensor-privacy.sh"
grep -qE 'Hostless_Spk|_is_protected_capture' "$SP" 2>/dev/null \
  && ok "sensor-privacy hostless/spk protect" \
  || bad "sensor-privacy missing hostless protect"

IMS="${ROOT}/packages/titan_ims/bin/titan2-ims-setup.sh"
if [ -f "$IMS" ]; then
  if grep -qE 'settings put secure location_mode' "$IMS" 2>/dev/null; then
    bad "ims-setup forces location_mode (privacy)"
  else
    ok "ims-setup does not force location_mode"
  fi
  if grep -qE 'mcc_string=310|epdg.epc.mnc260' "$IMS" 2>/dev/null; then
    bad "ims-setup pins a carrier ePDG or MCC row"
  else
    ok "ims-setup has no carrier ePDG/MCC pin"
  fi
  if grep -qF 'cmd phone ims disable' "$IMS" 2>/dev/null; then
    bad "ims-setup still runs ims disable (kills incoming on this SoC)"
  else
    ok "ims-setup does not ims disable"
  fi
  if grep -q 'ims_bind_target_slots' "$IMS" 2>/dev/null; then
    ok "ims-setup binds Calls tray when two SIMs loaded"
  else
    bad "ims-setup missing ims_bind_target_slots"
  fi
else
  bad "missing ims-setup"
fi

NETFW_MAN="${ROOT}/packages/titan_netfw/AndroidManifest.xml"
if [ -f "$NETFW_MAN" ]; then
  if grep -qE 'android:sharedUserId=' "$NETFW_MAN" 2>/dev/null; then
    bad "TitanNetFw claims sharedUserId — kitchen inject bootloops on signed GSI"
  else
    ok "TitanNetFw has no sharedUserId"
  fi
else
  bad "missing TitanNetFw manifest"
fi

# --- Controls source features for API remap ---
grep -q 'API client layers' \
  "$ROOT/apps/titan_controls/src/com/titanus2/controls/TempKeyMapStack.java" 2>/dev/null \
  && ok "API map priority (TempKeyMapStack)" \
  || bad "API map priority missing in Controls source"
grep -q 'ACT_MOUSE_SCROLL_UP' \
  "$ROOT/apps/titan_controls/src/com/titanus2/controls/KeyMapPrefs.java" 2>/dev/null \
  && ok "scroll remap actions in KeyMapPrefs" \
  || bad "scroll actions missing"

# --- PRODUCT_PACKAGES ↔ Android.bp ---
python3 - <<'PY' || bad "mk/bp package matrix failed"
from pathlib import Path
import re
root = Path(".")
mk = (root / "packages/gsi_product/titanus2.mk").read_text()
pkgs = set()
for m in re.finditer(r"PRODUCT_PACKAGES\s*\+=\s*\\?\n((?:\s+\S+.*\n)+)", mk):
    for line in m.group(1).splitlines():
        line = line.strip().rstrip("\\").strip()
        if line and not line.startswith("#"):
            pkgs.add(line)
bps = set()
import os
for dirpath, _, filenames in os.walk(root / "packages/gsi_product", followlinks=True):
    for fn in filenames:
        if fn != "Android.bp" or fn.endswith(".example"):
            continue
        text = Path(dirpath, fn).read_text()
        for m in re.finditer(r'name:\s*"([^"]+)"', text):
            bps.add(m.group(1))
missing = sorted(pkgs - bps)
if missing:
    print("FAIL PRODUCT_PACKAGES missing Android.bp:", ", ".join(missing))
    raise SystemExit(1)
print("OK  PRODUCT_PACKAGES (%d) ⊆ Android.bp modules" % len(pkgs))
PY

# --- USB HID bridge present ---
if [ -x "$ROOT/packages/titan_usb_hid_system/hid_bridge" ] \
  || [ -x "$ROOT/packages/magisk_titan2_usb_hid/hid_bridge" ]; then
  ok "hid_bridge prebuilt present"
else
  bad "missing hid_bridge (packages/titan_usb_hid_system or magisk_titan2_usb_hid)"
fi
if [ -x "$ROOT/packages/titan_usb_hid_system/titan2-keys" ]; then
  ok "titan2-keys prebuilt present"
else
  bad "missing titan2-keys (packages/titan_usb_hid_system)"
fi
if [ -x "$ROOT/packages/titan_usb_hid_system/titan2-keys" ] \
  && [ -f "$ROOT/packages/gsi_product/prebuilt_usb_hid/titan2-keys" ] \
  && ! cmp -s "$ROOT/packages/titan_usb_hid_system/titan2-keys" \
       "$ROOT/packages/gsi_product/prebuilt_usb_hid/titan2-keys"; then
  bad "titan2-keys system binary differs from prebuilt_usb_hid"
fi
if [ -x "$ROOT/packages/titan_usb_hid_system/hid_bridge" ] \
  && [ -f "$ROOT/packages/gsi_product/prebuilt_usb_hid/hid_bridge" ] \
  && ! cmp -s "$ROOT/packages/titan_usb_hid_system/hid_bridge" \
       "$ROOT/packages/gsi_product/prebuilt_usb_hid/hid_bridge"; then
  bad "hid_bridge system binary differs from the committed prebuilt"
fi

# Plasma reads passwd as uid atlas. The seed bake and the LP packer copy
# this script. A session without the guard, or a desk open that execs su,
# must not reach stage.
_desk="$ROOT/packages/titan_atlas/native/x11/atlas-desk-session.sh"
if grep -q 'chmod 644 /etc/passwd' "$_desk" 2>/dev/null; then
  ok "desk session restores passwd mode 644"
else
  bad "atlas-desk-session.sh lost the passwd mode guard"
fi
if cmp -s "$_desk" "$ROOT/apps/titan_atlas/assets/bin/atlas-desk-session" 2>/dev/null; then
  ok "assets desk session matches native source"
else
  bad "assets/bin/atlas-desk-session drifted from native/x11"
fi
_start=$(sed -n '/public static String start(Context/,/private static String readPhase/p' \
  "$ROOT/apps/titan_atlas/src/com/titanus2/atlas/DeskSession.java" 2>/dev/null || true)
if printf '%s' "$_start" | grep -q 'desktop is up' \
    && printf '%s' "$_start" | grep -q 'restart(c)' \
    && ! printf '%s' "$_start" | grep -q '"su"'; then
  ok "DeskSession.start uses the root helper"
else
  bad "DeskSession.start execs su or dropped the live-desk check"
fi

echo "---"
if [ "$ec" -eq 0 ]; then
  echo "PREFLIGHT PASS — stage + MisterZtr pipeline safe to proceed"
  echo "  Residual hybrid (not GSI): OEM vendor, OpenEUICC, USB audio bind,"
  echo "  phh-on-boot, keylayout/idc files, SAFE IMS overlays, stock camera"
else
  echo "PREFLIGHT FAIL — fix tip SoT before stage_gsi_product / pipeline"
fi
exit "$ec"
