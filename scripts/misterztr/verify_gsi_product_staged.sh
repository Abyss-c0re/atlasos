#!/usr/bin/env bash
# Verify Phase 1.5 GSI product staging without a full pipeline build.
#
#   ./scripts/misterztr/verify_gsi_product_staged.sh
# Exit 0 if staged tree looks ready for pipeline/systemimage.
set -euo pipefail
source "$(cd "$(dirname "$0")" && pwd)/lib.sh"
# Tip SoT first (optional if already staged)
if [ "${SKIP_PREFLIGHT:-0}" != "1" ] && [ -x "$ROOT/scripts/misterztr/preflight_gsi_product.sh" ]; then
  "$ROOT/scripts/misterztr/preflight_gsi_product.sh" || exit 1
fi
require_tree

ec=0
ok() { echo "OK  $*"; }
bad() { echo "BAD $*"; ec=1; }

DEST_PRE="$MISTERZTR_TREE/vendor/titanus2/prebuilts/titan2-touchpadd"
DEST_APPS="$MISTERZTR_TREE/vendor/titanus2/prebuilts/apps"
DEST_MK="$MISTERZTR_TREE/device/phh/treble/titanus2.mk"
BVN4="$MISTERZTR_TREE/device/phh/treble/lineage_arm64_bvN4.mk"
SRC_BIN="$ROOT/third_party/titan2-touchpadd/bin/titan2-touchpadd"

[ -x "$DEST_PRE/titan2-touchpadd" ] && ok "staged binary $DEST_PRE/titan2-touchpadd" || bad "missing staged binary"
[ -f "$DEST_PRE/Android.bp" ] && ok "staged Android.bp" || bad "missing Android.bp"
[ -f "$DEST_PRE/titan2-touchpadd.rc" ] && ok "staged init rc" || bad "missing rc"
[ -f "$DEST_MK" ] && ok "titanus2.mk" || bad "missing titanus2.mk"
grep -qF 'titan2-touchpadd' "$DEST_MK" 2>/dev/null && ok "PRODUCT_PACKAGES lists titan2-touchpadd" || bad "mk missing PRODUCT_PACKAGES"
grep -qF 'device/phh/treble/titanus2.mk' "$BVN4" 2>/dev/null && ok "bvN4 inherits titanus2.mk" || bad "bvN4 missing inherit"
SRC_MK="$ROOT/packages/gsi_product/titanus2.mk"
if [ -f "$SRC_MK" ] && [ -f "$DEST_MK" ]; then
  if cmp -s "$SRC_MK" "$DEST_MK"; then
    ok "tree titanus2.mk matches packages/gsi_product/titanus2.mk"
  else
    bad "tree titanus2.mk != packages/gsi_product/titanus2.mk — re-run stage_gsi_product.sh"
  fi
fi
# Phase 2 apps
[ -f "$DEST_APPS/Android.bp" ] && ok "staged apps Android.bp" || bad "missing apps Android.bp"
[ -f "$DEST_APPS/TitanControls.apk" ] && ok "staged TitanControls.apk" || bad "missing TitanControls.apk"
[ -f "$DEST_APPS/TitanUsbHid.apk" ] && ok "staged TitanUsbHid.apk" || bad "missing TitanUsbHid.apk"
[ -f "$DEST_APPS/CubeContact.apk" ] && ok "staged CubeContact.apk" || bad "missing CubeContact.apk"
[ -f "$DEST_APPS/privapp-permissions-com.titanus2.controls.xml" ] && ok "staged controls privapp xml" || bad "missing controls privapp xml"
grep -qF 'TitanControls' "$DEST_MK" 2>/dev/null && ok "PRODUCT_PACKAGES lists TitanControls" || bad "mk missing TitanControls"
grep -qF 'TitanUsbHid' "$DEST_MK" 2>/dev/null && ok "PRODUCT_PACKAGES lists TitanUsbHid" || bad "mk missing TitanUsbHid"
grep -qF 'CubeContact' "$DEST_MK" 2>/dev/null && ok "PRODUCT_PACKAGES lists CubeContact" || bad "mk missing CubeContact"
# USB HID stack (independent of APK inject)
DEST_HID="$MISTERZTR_TREE/vendor/titanus2/prebuilts/usb_hid"
[ -f "$DEST_HID/Android.bp" ] && ok "staged usb_hid Android.bp" || bad "missing usb_hid Android.bp (stage_gsi_product)"
[ -x "$DEST_HID/hid_bridge" ] && ok "staged hid_bridge" || bad "missing staged hid_bridge"
[ -f "$DEST_HID/enable_hid.sh" ] && ok "staged enable_hid.sh" || bad "missing enable_hid.sh"
[ -f "$DEST_HID/service.sh" ] && ok "staged service.sh" || bad "missing service.sh"
[ -f "$DEST_HID/titan2-usb-hid.rc" ] && ok "staged titan2-usb-hid.rc" || bad "missing usb-hid rc"
grep -qF 'titan2-hid-bridge' "$DEST_MK" 2>/dev/null && ok "PRODUCT_PACKAGES lists titan2-hid-bridge" || bad "mk missing titan2-hid-bridge"
grep -qF 'titan2-usb-hid.rc' "$DEST_MK" 2>/dev/null && ok "PRODUCT_PACKAGES lists titan2-usb-hid.rc" || bad "mk missing titan2-usb-hid.rc"
# Product sysbins (peels + ims-setup privacy)
DEST_SYS="$MISTERZTR_TREE/vendor/titanus2/prebuilts/sysbin"
[ -f "$DEST_SYS/Android.bp" ] && ok "staged sysbin Android.bp" || bad "missing sysbin Android.bp"
[ -f "$DEST_SYS/titan2-ims-setup.sh" ] && ok "staged titan2-ims-setup.sh" || bad "missing ims-setup"
[ -f "$DEST_SYS/titan2-pad-agent.sh" ] && ok "staged pad-agent" || bad "missing pad-agent"
[ -f "$DEST_SYS/titan2-keyled-write.sh" ] && ok "staged keyled-write" || bad "missing keyled-write"
[ -f "$DEST_SYS/titan2-sensor-privacy.sh" ] && ok "staged sensor-privacy" || bad "missing sensor-privacy"
[ -f "$DEST_SYS/titan2-sensor-privacy.rc" ] && ok "staged sensor-privacy.rc" || bad "missing sensor-privacy.rc"
grep -qF 'titan2-sensor-privacy.sh' "$DEST_MK" 2>/dev/null && ok "PRODUCT_PACKAGES lists sensor-privacy" || bad "mk missing sensor-privacy"
grep -qF 'titan2-sensor-privacy.rc' "$DEST_MK" 2>/dev/null && ok "PRODUCT_PACKAGES lists sensor-privacy.rc" || bad "mk missing sensor-privacy.rc"
if grep -qE '_is_protected_capture_pcm|Hostless_Spk' "$DEST_SYS/titan2-sensor-privacy.sh" 2>/dev/null; then
  ok "sensor-privacy protects hostless/spk (v13 media fix)"
else
  bad "sensor-privacy missing hostless/spk protect — media silence risk"
fi
grep -qF 'titan2-ims-setup.sh' "$DEST_MK" 2>/dev/null && ok "PRODUCT_PACKAGES lists ims-setup" || bad "mk missing ims-setup"
if grep -qE 'settings put secure location_mode' "$DEST_SYS/titan2-ims-setup.sh" 2>/dev/null; then
  bad "ims-setup still forces location_mode (privacy)"
else
  ok "ims-setup does not force location_mode"
fi
grep -q 'titan2_force_location_for_wfc' "$DEST_SYS/titan2-ims-setup.sh" 2>/dev/null \
  && grep -q 'settings delete global titan2_force_location_for_wfc' "$DEST_SYS/titan2-ims-setup.sh" 2>/dev/null \
  && ok "ims-setup clears sticky force_location flag" \
  || bad "ims-setup should delete titan2_force_location_for_wfc"
if [ -x "$SRC_BIN" ] && [ -x "$DEST_PRE/titan2-touchpadd" ]; then
  if grep -aF 'INPROC_PARK' "$DEST_PRE/titan2-touchpadd" >/dev/null 2>&1; then
    ok "staged binary has INPROC_PARK"
  else
    bad "staged binary missing INPROC_PARK — re-run stage_gsi_product.sh after rebuild"
  fi
  if grep -aF 'Skipping TitanKey (KEYBOARD_FEATURES off)' "$DEST_PRE/titan2-touchpadd" >/dev/null 2>&1; then
    ok "staged binary has pad-only (KEYBOARD_FEATURES off)"
  else
    bad "staged binary missing pad-only marker — rebuild third_party/titan2-touchpadd/patches"
  fi
  if grep -aF 'titan2-virtual-mouse' "$DEST_PRE/titan2-touchpadd" >/dev/null 2>&1; then
    ok "staged binary has titan2-virtual-mouse"
  else
    bad "staged binary missing titan2-virtual-mouse"
  fi
  # Mouse double-tap left latch — product 2026-08-07 (not hybrid inject)
  if grep -aF 'left latch ON' "$DEST_PRE/titan2-touchpadd" >/dev/null 2>&1; then
    ok "staged binary has double-tap left latch"
  else
    bad "staged binary missing left latch — rebuild third_party/titan2-touchpadd + stage"
  fi
  sb=$(stat -c%s "$SRC_BIN" 2>/dev/null || echo 0)
  db=$(stat -c%s "$DEST_PRE/titan2-touchpadd" 2>/dev/null || echo 0)
  [ "$sb" = "$db" ] && ok "binary size match product SoT ($sb)" || bad "size mismatch SoT=$sb staged=$db"
  if command -v md5sum >/dev/null 2>&1 && [ -x "$SRC_BIN" ]; then
    sm=$(md5sum "$SRC_BIN" | awk '{print $1}')
    dm=$(md5sum "$DEST_PRE/titan2-touchpadd" | awk '{print $1}')
    [ "$sm" = "$dm" ] && ok "binary md5 match product SoT ($sm)" || bad "md5 mismatch SoT=$sm staged=$dm"
  fi
fi
if [ -f "$ROOT/patches/gsi_source/SERIES" ] && grep -qE '^0040-' "$ROOT/patches/gsi_source/SERIES"; then
  ok "SERIES enables 0040 product-mk patch"
else
  bad "SERIES missing 0040"
fi
[ -f "$MISTERZTR_TREE/.titanus2_gsi_product_staged" ] && ok "stage marker present" || bad "no .titanus2_gsi_product_staged marker"

echo "---"
if [ "$ec" -eq 0 ]; then
  echo "VERIFY PASS — ready for: ./scripts/misterztr/pipeline.sh --from=patch"
else
  echo "VERIFY FAIL — fix stage (./scripts/misterztr/stage_gsi_product.sh)"
fi
exit "$ec"
