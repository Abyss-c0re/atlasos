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
  if [ -n "$code" ] && [ "$code" -ge 218 ] 2>/dev/null && [[ "$name" == 2.1[7-9]* || "$name" == 2.2* ]]; then
    ok "TitanUsbHid tip $v"
  else
    bad "TitanUsbHid tip want ≥218/2.17+ got '$v' ($USB)"
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

# --- pad-apply / peels ---
PA="${ROOT}/patches/bin/titan2-pad-apply.sh"
grep -q '2.215-rot-0-3' "$PA" 2>/dev/null \
  && ok "pad-apply 2.215-rot-0-3" \
  || bad "pad-apply not 2.215-rot-0-3 (Surface 0..3 follow-orient)"
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
else
  bad "missing ims-setup"
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

echo "---"
if [ "$ec" -eq 0 ]; then
  echo "PREFLIGHT PASS — stage + MisterZtr pipeline safe to proceed"
  echo "  Residual hybrid (not GSI): OEM vendor, OpenEUICC, USB audio bind,"
  echo "  phh-on-boot, keylayout/idc files, SAFE IMS overlays, stock camera"
else
  echo "PREFLIGHT FAIL — fix tip SoT before stage_gsi_product / pipeline"
fi
exit "$ec"
