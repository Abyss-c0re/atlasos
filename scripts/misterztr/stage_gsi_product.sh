#!/usr/bin/env bash
# Stage GSI product prebuilts into MISTERZTR_TREE (not committed to product git).
#
#   ./scripts/misterztr/stage_gsi_product.sh
#   ./scripts/misterztr/stage_gsi_product.sh --dry-run
#
# Phase 1.5: titan2-touchpadd musl binary + Android.bp + init.rc + PRODUCT_PACKAGES.
# Phase 2: TitanControls / TitanUsbHid / CubeContact prebuilt APKs + privapp XML.
# Binary/APK SoT lives in product git apps/ and third_party/ (never ELF in git patches).
set -euo pipefail
source "$(cd "$(dirname "$0")" && pwd)/lib.sh"
require_tree

# Fail closed if tip SoT is incomplete (apps/touchpadd/pad-apply markers).
if [ "${SKIP_PREFLIGHT:-0}" != "1" ] && [ -x "$ROOT/scripts/misterztr/preflight_gsi_product.sh" ]; then
  "$ROOT/scripts/misterztr/preflight_gsi_product.sh" || die "preflight_gsi_product failed"
fi

DRY=0
for a in "$@"; do
  case "$a" in
    --dry-run|-n) DRY=1 ;;
    -h|--help) sed -n '2,14p' "$0"; exit 0 ;;
  esac
done

SRC_PROD="$ROOT/packages/gsi_product"
SRC_TP="$SRC_PROD/prebuilt_touchpadd"
SRC_APPS="$SRC_PROD/prebuilt_apps"
SRC_BIN="$ROOT/third_party/titan2-touchpadd/bin/titan2-touchpadd"
# Prefer staged copy under gsi_product if present
if [ -x "$SRC_TP/titan2-touchpadd" ]; then
  SRC_BIN="$SRC_TP/titan2-touchpadd"
fi
[ -x "$SRC_BIN" ] || die "missing touchpadd binary: $SRC_BIN (rebuild third_party first)"
[ -f "$SRC_TP/Android.bp" ] || die "missing $SRC_TP/Android.bp"
[ -f "$SRC_TP/titan2-touchpadd.rc" ] || die "missing rc"
[ -f "$SRC_PROD/titanus2.mk" ] || die "missing $SRC_PROD/titanus2.mk"
[ -f "$SRC_APPS/Android.bp" ] || die "missing $SRC_APPS/Android.bp (Phase 2)"

# Resolve product APK SoT (prefer -v2 for Controls)
CTRL_APK=""
for c in \
  "$ROOT/apps/titan_controls/TitanControls-v2.apk" \
  "$ROOT/apps/titan_controls/TitanControls.apk" \
  "$SRC_APPS/TitanControls.apk"; do
  [ -f "$c" ] && CTRL_APK="$c" && break
done
USB_APK=""
for c in \
  "$ROOT/apps/titan_usb_hid/TitanUsbHid.apk" \
  "$SRC_APPS/TitanUsbHid.apk"; do
  [ -f "$c" ] && USB_APK="$c" && break
done
CUBE_APK=""
for c in \
  "$ROOT/apps/cube_contact/CubeContact.apk" \
  "$SRC_APPS/CubeContact.apk"; do
  [ -f "$c" ] && CUBE_APK="$c" && break
done
# PocketBoard default IME (EN+RU) — third_party/pocket-board or staged pull APK
POCKET_APK=""
for c in \
  "$SRC_APPS/PocketBoard.apk" \
  "$ROOT/out/titan_kb_pull_20260808/apk/PocketBoard.apk"; do
  [ -f "$c" ] && POCKET_APK="$c" && break
done
HWKB_APK=""
for c in \
  "$SRC_APPS/HwKeyboardLayouts.apk" \
  "$ROOT/out/titan_kb_pull_20260808/apk/HwKeyboardLayouts.apk"; do
  [ -f "$c" ] && HWKB_APK="$c" && break
done
[ -n "$CTRL_APK" ] || die "missing TitanControls.apk"
[ -n "$USB_APK" ] || die "missing TitanUsbHid.apk"
[ -n "$CUBE_APK" ] || die "missing CubeContact.apk"
[ -n "$POCKET_APK" ] || die "missing PocketBoard.apk (stage from Titan pull or build third_party/pocket-board)"
# HwKeyboardLayouts optional but recommended for RU system layout picker
if [ -z "$HWKB_APK" ]; then
  info "warn: HwKeyboardLayouts.apk missing — RU system HW layout pack skipped"
fi

CTRL_PRIV="$ROOT/apps/titan_controls/permissions/privapp-permissions-com.titanus2.controls.xml"
USB_PRIV="$ROOT/apps/titan_usb_hid/permissions/privapp-permissions-com.titanus2.usbhid.xml"
USB_DEF="$ROOT/apps/titan_usb_hid/permissions/default-permissions-com.titanus2.usbhid.xml"
CUBE_PRIV="$ROOT/apps/cube_contact/permissions/privapp-permissions-com.titanus2.cubecontact.xml"
for f in "$CTRL_PRIV" "$USB_PRIV" "$USB_DEF" "$CUBE_PRIV"; do
  [ -f "$f" ] || die "missing $f"
done

DEST_PRE="$MISTERZTR_TREE/vendor/titanus2/prebuilts/titan2-touchpadd"
DEST_APPS="$MISTERZTR_TREE/vendor/titanus2/prebuilts/apps"
DEST_MK="$MISTERZTR_TREE/device/phh/treble/titanus2.mk"
BVN4="$MISTERZTR_TREE/device/phh/treble/lineage_arm64_bvN4.mk"
[ -f "$BVN4" ] || die "missing product mk: $BVN4"

stage_file() {
  local src=$1 dest=$2
  if [ "$DRY" = "1" ]; then
    info "dry-run would: cp $src → $dest"
    return 0
  fi
  mkdir -p "$(dirname "$dest")"
  cp -f "$src" "$dest"
  if [ -x "$src" ] || [[ "$src" == *titan2-touchpadd && "$src" != *.rc && "$src" != *.bp && "$src" != *.apk && "$src" != *.xml ]]; then
    chmod 755 "$dest" 2>/dev/null || true
  fi
}

info "stage GSI product → $MISTERZTR_TREE"
# Phase 1.5 touchpadd
stage_file "$SRC_BIN" "$DEST_PRE/titan2-touchpadd"
stage_file "$SRC_TP/Android.bp" "$DEST_PRE/Android.bp"
stage_file "$SRC_TP/titan2-touchpadd.rc" "$DEST_PRE/titan2-touchpadd.rc"

# Phase 2 apps
stage_file "$SRC_APPS/Android.bp" "$DEST_APPS/Android.bp"
stage_file "$CTRL_APK" "$DEST_APPS/TitanControls.apk"
stage_file "$USB_APK" "$DEST_APPS/TitanUsbHid.apk"
stage_file "$CUBE_APK" "$DEST_APPS/CubeContact.apk"
stage_file "$POCKET_APK" "$DEST_APPS/PocketBoard.apk"
[ -n "$HWKB_APK" ] && stage_file "$HWKB_APK" "$DEST_APPS/HwKeyboardLayouts.apk"
stage_file "$CTRL_PRIV" "$DEST_APPS/privapp-permissions-com.titanus2.controls.xml"
stage_file "$USB_PRIV" "$DEST_APPS/privapp-permissions-com.titanus2.usbhid.xml"
stage_file "$USB_DEF" "$DEST_APPS/default-permissions-com.titanus2.usbhid.xml"
stage_file "$CUBE_PRIV" "$DEST_APPS/privapp-permissions-com.titanus2.cubecontact.xml"

# USB HID gadget stack (independent of APK inject)
SRC_HID="$SRC_PROD/prebuilt_usb_hid"
HID_SYS="$ROOT/packages/titan_usb_hid_system"
HID_BRIDGE="$ROOT/packages/magisk_titan2_usb_hid/hid_bridge"
[ -x "$HID_SYS/hid_bridge" ] && HID_BRIDGE="$HID_SYS/hid_bridge"
[ -x "$HID_BRIDGE" ] || die "missing hid_bridge: $HID_BRIDGE"
[ -f "$SRC_HID/Android.bp" ] || die "missing $SRC_HID/Android.bp"
DEST_HID="$MISTERZTR_TREE/vendor/titanus2/prebuilts/usb_hid"
stage_file "$SRC_HID/Android.bp" "$DEST_HID/Android.bp"
stage_file "$HID_BRIDGE" "$DEST_HID/hid_bridge"
stage_file "$HID_SYS/enable_hid.sh" "$DEST_HID/enable_hid.sh"
stage_file "$HID_SYS/service.sh" "$DEST_HID/service.sh"
stage_file "$HID_SYS/titan2-usb-hid-service.sh" "$DEST_HID/titan2-usb-hid-service.sh"
stage_file "$HID_SYS/titan2-usb-hid.rc" "$DEST_HID/titan2-usb-hid.rc"
chmod 755 "$DEST_HID/hid_bridge" "$DEST_HID/enable_hid.sh" "$DEST_HID/service.sh" \
  "$DEST_HID/titan2-usb-hid-service.sh" 2>/dev/null || true
info "staged USB HID stack → $DEST_HID"

# Product sysbins (peels + pad-agent + ims-setup without location force)
SRC_SYS="$SRC_PROD/prebuilt_sysbin"
DEST_SYS="$MISTERZTR_TREE/vendor/titanus2/prebuilts/sysbin"
[ -f "$SRC_SYS/Android.bp" ] || die "missing $SRC_SYS/Android.bp"
# Refresh SoT into gsi_product cache before stage (Phase 3 peels 2.166–2.191)
_SYSBIN_SOT="
titan2-pad-agent.sh
titan2-typing-watch.sh
titan2-ims-heal.sh
titan2-keyled-write.sh
titan2-key-fire.sh
titan2-dev-action.sh
titan2-side-key.sh
titan2-subdisplay.sh
titan2-key-watch.sh
titan2-b1-kl.sh
titan2-keylayout.sh
titan2-keycode-inject.sh
titan2-dt2w.sh
titan2-pad-idc.sh
titan2-plane-heal.sh
titan2-cube-load-land.sh
titan2-cool-park.sh
titan2-ui-plane.sh
titan2-pad-apply.sh
titan2-ctrl-seed.sh
titan2-sensor-privacy.sh
titan2-fw
titan2-fw.sh
titan2-remote-adb.sh
titan2-vpn-hotspot.sh
"
for f in $_SYSBIN_SOT; do
  [ -f "$ROOT/patches/bin/$f" ] && cp -f "$ROOT/patches/bin/$f" "$SRC_SYS/$f" && chmod 755 "$SRC_SYS/$f"
done
# Firewall CLI twin may live under magisk module if patches/bin lacks bare name
[ -f "$SRC_SYS/titan2-fw" ] || {
  [ -f "$ROOT/packages/magisk_titan2_fw/system/bin/titan2-fw" ] \
    && cp -f "$ROOT/packages/magisk_titan2_fw/system/bin/titan2-fw" "$SRC_SYS/titan2-fw" \
    && chmod 755 "$SRC_SYS/titan2-fw"
}

[ -f "$ROOT/packages/titan_ims/bin/titan2-ims-setup.sh" ] \
  && cp -f "$ROOT/packages/titan_ims/bin/titan2-ims-setup.sh" "$SRC_SYS/titan2-ims-setup.sh" \
  && chmod 755 "$SRC_SYS/titan2-ims-setup.sh"
[ -f "$ROOT/patches/init/titan2-pad-agent.rc" ] \
  && cp -f "$ROOT/patches/init/titan2-pad-agent.rc" "$SRC_SYS/titan2-pad-agent.rc"
[ -f "$ROOT/packages/titan_ims/init/titan2-ims.rc" ] \
  && cp -f "$ROOT/packages/titan_ims/init/titan2-ims.rc" "$SRC_SYS/titan2-ims.rc"
[ -f "$ROOT/patches/init/titan2-sensor-privacy.rc" ] \
  && cp -f "$ROOT/patches/init/titan2-sensor-privacy.rc" "$SRC_SYS/titan2-sensor-privacy.rc"
# Guard: ims-setup must not force location_mode
if grep -qE 'settings put secure location_mode' "$SRC_SYS/titan2-ims-setup.sh" 2>/dev/null; then
  die "titan2-ims-setup.sh still forces location_mode — product privacy violation"
fi
# Guard: sensor-privacy must protect Hostless_Spk_Init / FM (media silence fix)
if ! grep -q '_is_protected_capture_pcm\|Hostless_Spk' "$SRC_SYS/titan2-sensor-privacy.sh" 2>/dev/null; then
  die "titan2-sensor-privacy.sh missing hostless/spk protect (v13+) — media silence risk"
fi
stage_file "$SRC_SYS/Android.bp" "$DEST_SYS/Android.bp"
for f in $_SYSBIN_SOT titan2-ims-setup.sh titan2-pad-agent.rc titan2-ims.rc titan2-sensor-privacy.rc; do
  stage_file "$SRC_SYS/$f" "$DEST_SYS/$f"
done
chmod 755 "$DEST_SYS"/*.sh 2>/dev/null || true
info "staged product sysbins → $DEST_SYS (sensor-privacy v13 hostless protect; no location force)"

# Atlas APK is gitignored - rebuild when sources are newer so tip fixes
# (scroll 0.9.56+, auth bar) land in the next hybrid/GSI stage.
ATLAS_APP="$ROOT/apps/titan_atlas"
ATLAS_APK_TIP="$ATLAS_APP/TitanAtlas.apk"
ATLAS_REBUILD="${ATLAS_REBUILD:-auto}"
need_atlas_rebuild=0
if [ ! -f "$ATLAS_APK_TIP" ]; then
  need_atlas_rebuild=1
elif [ "$ATLAS_REBUILD" = "1" ] || [ "$ATLAS_REBUILD" = "force" ]; then
  need_atlas_rebuild=1
elif [ "$ATLAS_REBUILD" = "auto" ]; then
  # Rebuild if any tracked Atlas Java/manifest is newer than the tip APK.
  newer=$(find "$ATLAS_APP/src" "$ATLAS_APP/AndroidManifest.xml" \
    -type f \( -name '*.java' -o -name 'AndroidManifest.xml' \) \
    -newer "$ATLAS_APK_TIP" 2>/dev/null | head -1)
  [ -n "$newer" ] && need_atlas_rebuild=1
fi
if [ "$need_atlas_rebuild" = "1" ] && [ -x "$ATLAS_APP/build.sh" ]; then
  if [ "$DRY" = "1" ]; then
    info "would rebuild Atlas tip APK (ATLAS_REBUILD=$ATLAS_REBUILD)"
  else
    info "rebuilding Atlas tip APK for GSI stage..."
    (cd "$ATLAS_APP" && ATLAS_SKIP_NATIVE="${ATLAS_SKIP_NATIVE:-1}" ./build.sh) \
      || die "Atlas build.sh failed - fix apps/titan_atlas before stage"
  fi
fi

SRC_ATLAS="$SRC_PROD/prebuilt_atlas"
DEST_ATLAS="$MISTERZTR_TREE/vendor/titanus2/prebuilts/atlas"
ATLAS_APK=""
for c in \
  "$ROOT/apps/titan_atlas/TitanAtlas.apk" \
  "$SRC_ATLAS/TitanAtlas.apk"; do
  [ -f "$c" ] && ATLAS_APK="$c" && break
done
if [ -n "$ATLAS_APK" ] && [ -f "$SRC_ATLAS/Android.bp" ]; then
  ATLAS_PRIV="$ROOT/apps/titan_atlas/permissions/privapp-permissions-com.titanus2.atlas.xml"
  [ -f "$ATLAS_PRIV" ] || ATLAS_PRIV="$SRC_ATLAS/privapp-permissions-com.titanus2.atlas.xml"
  HYB_SH="$ROOT/packages/titan_atlas/scripts/atlas-hybrid.sh"
  NET_SH="$ROOT/packages/titan_atlas/scripts/atlas-net.sh"
  [ -f "$HYB_SH" ] || HYB_SH="$ROOT/apps/titan_atlas/assets/bin/atlas-hybrid.sh"
  [ -f "$NET_SH" ] || NET_SH="$ROOT/apps/titan_atlas/assets/bin/atlas-net.sh"
  stage_file "$SRC_ATLAS/Android.bp" "$DEST_ATLAS/Android.bp"
  stage_file "$ATLAS_APK" "$DEST_ATLAS/TitanAtlas.apk"
  [ -f "$ATLAS_PRIV" ] && stage_file "$ATLAS_PRIV" "$DEST_ATLAS/privapp-permissions-com.titanus2.atlas.xml"
  stage_file "$HYB_SH" "$DEST_ATLAS/atlas-hybrid.sh"
  stage_file "$NET_SH" "$DEST_ATLAS/atlas-net.sh"
  stage_file "$SRC_ATLAS/atlas-hybrid-boot.sh" "$DEST_ATLAS/atlas-hybrid-boot.sh"
  stage_file "$SRC_ATLAS/atlas-hybrid.rc" "$DEST_ATLAS/atlas-hybrid.rc"
  # ROM base ELFs (auth/sudo/repl) — priv-app is UI; execute from /system/bin
  for elf in atlas-auth atlas-sudo atlas-auth-askpass atlas atlas-lpctl; do
    src=""
    for c in "$ROOT/packages/titan_atlas/out/$elf" \
             "$ROOT/apps/titan_atlas/assets/bin/$elf" \
             "$SRC_ATLAS/$elf"; do
      [ -f "$c" ] && [ -s "$c" ] && src="$c" && break
    done
    if [ -n "$src" ]; then
      stage_file "$src" "$DEST_ATLAS/$elf"
      [ "$DRY" != "1" ] && chmod 755 "$DEST_ATLAS/$elf" 2>/dev/null || true
      cp -f "$src" "$SRC_ATLAS/$elf" 2>/dev/null || true
    else
      info "warn: missing Atlas system ELF $elf"
    fi
  done
  # Agent plane helpers (hybrid awareness / screencap nsenter)
  for h in atlas-agent-status.sh atlas-screencap.sh; do
    src=""
    for c in "$ROOT/apps/titan_atlas/assets/bin/$h" "$SRC_ATLAS/$h"; do
      [ -f "$c" ] && [ -s "$c" ] && src="$c" && break
    done
    if [ -n "$src" ]; then
      stage_file "$src" "$DEST_ATLAS/$h"
      [ "$DRY" != "1" ] && chmod 755 "$DEST_ATLAS/$h" 2>/dev/null || true
      cp -f "$src" "$SRC_ATLAS/$h" 2>/dev/null || true
    fi
  done
  chmod 755 "$DEST_ATLAS/atlas-hybrid.sh" "$DEST_ATLAS/atlas-net.sh" \
    "$DEST_ATLAS/atlas-hybrid-boot.sh" 2>/dev/null || true
  # Optional essentials rootfs (large) — not required for stage; device may bootstrap later
  ROOTFS_TAR="${ATLAS_ROOTFS_TAR:-$ROOT/out/atlas_rootfs/debian-trixie-arm64-rootfs.tar.gz}"
  if [ -f "$ROOTFS_TAR" ] && [ "$(stat -c%s "$ROOTFS_TAR" 2>/dev/null || echo 0)" -gt 1000000 ]; then
    stage_file "$ROOTFS_TAR" "$DEST_ATLAS/debian-trixie-arm64-rootfs.tar.gz"
    # Also stage into tree etc so product can install as /system/etc/atlas/…
    DEST_ATLAS_ETC="$MISTERZTR_TREE/vendor/titanus2/prebuilts/atlas/etc"
    if [ "$DRY" != "1" ]; then
      mkdir -p "$DEST_ATLAS_ETC"
      cp -f "$ROOTFS_TAR" "$DEST_ATLAS_ETC/debian-trixie-arm64-rootfs.tar.gz"
    fi
    info "staged Atlas essentials rootfs → $DEST_ATLAS (+ etc seed for /system/etc/atlas)"
  else
    info "warn: Atlas rootfs tarball missing — run: ./packages/titan_atlas/scripts/build_debian_rootfs.sh"
  fi
  # Keep gsi_product cache of APK/priv
  cp -f "$ATLAS_APK" "$SRC_ATLAS/TitanAtlas.apk" 2>/dev/null || true
  [ -f "$ATLAS_PRIV" ] && cp -f "$ATLAS_PRIV" "$SRC_ATLAS/privapp-permissions-com.titanus2.atlas.xml"
  cp -f "$HYB_SH" "$SRC_ATLAS/atlas-hybrid.sh" 2>/dev/null || true
  cp -f "$NET_SH" "$SRC_ATLAS/atlas-net.sh" 2>/dev/null || true
  info "staged Atlas → $DEST_ATLAS (terminal+hybrid; no proprietary CLI; no HW-kb scope)"
else
  info "warn: Atlas APK/Android.bp missing — skip Atlas GSI packages (build apps/titan_atlas first)"
fi

stage_file "$SRC_PROD/titanus2.mk" "$DEST_MK"

# Ensure product lunch inherits titanus2.mk (idempotent).
INHERIT='$(call inherit-product, device/phh/treble/titanus2.mk)'
if grep -qF 'device/phh/treble/titanus2.mk' "$BVN4" 2>/dev/null; then
  info "lineage_arm64_bvN4.mk already inherits titanus2.mk"
else
  if [ "$DRY" = "1" ]; then
    info "dry-run would append inherit to lineage_arm64_bvN4.mk"
  else
    {
      echo ""
      echo "# Titan 2 product packages (gsi_source / stage_gsi_product)"
      echo "$INHERIT"
    } >>"$BVN4"
    info "appended inherit-product titanus2.mk → lineage_arm64_bvN4.mk"
  fi
fi

# Marker for monitors / pipeline
if [ "$DRY" != "1" ]; then
  {
    date -Iseconds
    echo "binary=$DEST_PRE/titan2-touchpadd"
    echo "size=$(stat -c%s "$DEST_PRE/titan2-touchpadd" 2>/dev/null || echo '?')"
    echo "apps=$DEST_APPS"
    echo "controls=$(stat -c%s "$DEST_APPS/TitanControls.apk" 2>/dev/null || echo '?')"
    echo "mk=$DEST_MK"
    echo "phase=1.5+2"
  } >"$MISTERZTR_TREE/.titanus2_gsi_product_staged"
  # Keep gsi_product/ touchpadd copy in sync with third_party SoT
  if [ -x "$ROOT/third_party/titan2-touchpadd/bin/titan2-touchpadd" ]; then
    cp -f "$ROOT/third_party/titan2-touchpadd/bin/titan2-touchpadd" \
      "$SRC_TP/titan2-touchpadd"
    chmod 755 "$SRC_TP/titan2-touchpadd"
  fi
  # Keep prebuilt_apps mirror of APKs for offline stage (optional small cache)
  cp -f "$CTRL_APK" "$SRC_APPS/TitanControls.apk"
  cp -f "$USB_APK" "$SRC_APPS/TitanUsbHid.apk"
  cp -f "$CUBE_APK" "$SRC_APPS/CubeContact.apk"
  cp -f "$CTRL_PRIV" "$SRC_APPS/privapp-permissions-com.titanus2.controls.xml"
  cp -f "$USB_PRIV" "$SRC_APPS/privapp-permissions-com.titanus2.usbhid.xml"
  cp -f "$USB_DEF" "$SRC_APPS/default-permissions-com.titanus2.usbhid.xml"
  cp -f "$CUBE_PRIV" "$SRC_APPS/privapp-permissions-com.titanus2.cubecontact.xml"
fi

info "stage_gsi_product OK (dry=$DRY) touchpadd+apps+usb_hid+sysbins+atlas"
info "next: lunch $LUNCH_TARGET && m systemimage"
info "      or: ./scripts/misterztr/pipeline.sh --from=patch"
