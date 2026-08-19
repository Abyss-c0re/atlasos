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
SRC_BIN="$SRC_TP/titan2-touchpadd"
if [ ! -x "$SRC_BIN" ] && [ -x "$ROOT/scripts/build_touchpadd.sh" ]; then
  info "touchpadd ELF missing — pull submodule and pack"
  "$ROOT/scripts/build_touchpadd.sh"
fi
[ -x "$SRC_BIN" ] || die "missing touchpadd binary: $SRC_BIN (./scripts/build_touchpadd.sh)"
[ -f "$SRC_TP/Android.bp" ] || die "missing $SRC_TP/Android.bp"
[ -f "$SRC_TP/titan2-touchpadd.rc" ] || die "missing rc"
IDC_PAD="$SRC_TP/touchPad.idc"
IDC_SUB="$SRC_TP/sub_touch.idc"
IDC_MOUSE="$SRC_TP/titan2-virtual-mouse.idc"
[ -f "$IDC_PAD" ] || die "missing touchPad.idc"
[ -f "$IDC_SUB" ] || die "missing sub_touch.idc"
[ -f "$IDC_MOUSE" ] || die "missing titan2-virtual-mouse.idc (titanus2.mk PRODUCT_COPY_FILES)"
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
HWKB_APK=""
for c in \
  "$SRC_APPS/HwKeyboardLayouts.apk" \
  "$ROOT/out/titan_kb_pull_20260808/apk/HwKeyboardLayouts.apk"; do
  [ -f "$c" ] && HWKB_APK="$c" && break
done
WORKSHOP="${TITANUS2_WORKSHOP:-$ROOT/../titanus2}"
for c in \
  "$WORKSHOP/apps/titan_controls/TitanControls-v2.apk" \
  "$WORKSHOP/apps/titan_controls/TitanControls.apk" \
  "$WORKSHOP/apps/titan_usb_hid/TitanUsbHid.apk" \
  "$WORKSHOP/apps/cube_contact/CubeContact.apk"; do
  case "$c" in
    *TitanControls*) [ -z "$CTRL_APK" ] && [ -f "$c" ] && CTRL_APK="$c" ;;
    *TitanUsbHid*) [ -z "$USB_APK" ] && [ -f "$c" ] && USB_APK="$c" ;;
    *CubeContact*) [ -z "$CUBE_APK" ] && [ -f "$c" ] && CUBE_APK="$c" ;;
  esac
done
if [ "${SKIP_PREFLIGHT:-0}" = "1" ]; then
  [ -n "$CTRL_APK" ] || info "warn: no TitanControls.apk — staging mouse only"
  [ -n "$USB_APK" ] || info "warn: no TitanUsbHid.apk — staging mouse only"
  [ -n "$CUBE_APK" ] || info "warn: no CubeContact.apk — staging mouse only"
else
  [ -n "$CTRL_APK" ] || die "missing TitanControls.apk"
  [ -n "$USB_APK" ] || die "missing TitanUsbHid.apk"
  [ -n "$CUBE_APK" ] || die "missing CubeContact.apk"
fi
# Cube Experience SoT: black+crimson cube_gl. Refuse stale brick APKs.
if [ -n "$CUBE_APK" ]; then
  grep -q 'cube-gl-mono' "$ROOT/apps/cube_contact/AndroidManifest.xml" 2>/dev/null \
    || die "CubeContact source missing cube-gl-mono (Cube Experience)"
  strings "$CUBE_APK" 2>/dev/null | grep -q 'cube-gl-mono' \
    || die "CubeContact.apk is not cube-gl-mono ($CUBE_APK)"
fi
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
# Phase 1.5 touchpadd (ELF + init + InputReader IDC — GSI source, not inject)
stage_file "$SRC_BIN" "$DEST_PRE/titan2-touchpadd"
stage_file "$SRC_TP/Android.bp" "$DEST_PRE/Android.bp"
stage_file "$SRC_TP/titan2-touchpadd.rc" "$DEST_PRE/titan2-touchpadd.rc"
stage_file "$IDC_PAD" "$DEST_PRE/touchPad.idc"
stage_file "$IDC_SUB" "$DEST_PRE/sub_touch.idc"
stage_file "$IDC_MOUSE" "$DEST_PRE/titan2-virtual-mouse.idc"

# Phase 2 apps
if [ -n "$CTRL_APK" ] && [ -n "$USB_APK" ] && [ -n "$CUBE_APK" ]; then
  stage_file "$SRC_APPS/Android.bp" "$DEST_APPS/Android.bp"
  stage_file "$CTRL_APK" "$DEST_APPS/TitanControls.apk"
  stage_file "$USB_APK" "$DEST_APPS/TitanUsbHid.apk"
  stage_file "$CUBE_APK" "$DEST_APPS/CubeContact.apk"
  NETFW_APK=""
  for c in \
    "$ROOT/packages/titan_netfw/TitanNetFw.apk" \
    "$SRC_APPS/TitanNetFw.apk"; do
    [ -f "$c" ] && NETFW_APK="$c" && break
  done
  [ -n "$NETFW_APK" ] && stage_file "$NETFW_APK" "$DEST_APPS/TitanNetFw.apk"
  LUCI_APK=""
  for c in \
    "$ROOT/packages/titan_luci/TitanLuci.apk" \
    "$SRC_APPS/TitanLuci.apk"; do
    [ -f "$c" ] && LUCI_APK="$c" && break
  done
  [ -n "$LUCI_APK" ] && stage_file "$LUCI_APK" "$DEST_APPS/TitanLuci.apk"
  [ -n "$HWKB_APK" ] && stage_file "$HWKB_APK" "$DEST_APPS/HwKeyboardLayouts.apk"
  stage_file "$CTRL_PRIV" "$DEST_APPS/privapp-permissions-com.titanus2.controls.xml"
  stage_file "$USB_PRIV" "$DEST_APPS/privapp-permissions-com.titanus2.usbhid.xml"
  stage_file "$USB_DEF" "$DEST_APPS/default-permissions-com.titanus2.usbhid.xml"
  stage_file "$CUBE_PRIV" "$DEST_APPS/privapp-permissions-com.titanus2.cubecontact.xml"
else
  info "skip Phase 2 APK stage (missing tip APKs)"
fi

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
titan2-ims-simswitch-early.sh
titan2-ims-simswitch-hold.sh
titan2-ims-diag.sh
titan2-sensor-privacy.sh
titan2-fw
titan2-fw.sh
titan2-fw-observe
titan2-remote-adb.sh
titan2-vpn-hotspot.sh
titan2-tether.sh
"
refresh_sot() {
  local src=$1 dest=$2
  [ -f "$src" ] || return 0
  if [ -e "$dest" ] && [ "$src" -ef "$dest" ]; then
    return 0
  fi
  cp -f "$src" "$dest"
  chmod 755 "$dest" 2>/dev/null || true
}
for f in $_SYSBIN_SOT; do
  refresh_sot "$ROOT/patches/bin/$f" "$SRC_SYS/$f"
done
# Firewall CLI twin may live under magisk module if patches/bin lacks bare name
[ -f "$SRC_SYS/titan2-fw" ] || {
  [ -f "$ROOT/packages/magisk_titan2_fw/system/bin/titan2-fw" ] \
    && cp -f "$ROOT/packages/magisk_titan2_fw/system/bin/titan2-fw" "$SRC_SYS/titan2-fw" \
    && chmod 755 "$SRC_SYS/titan2-fw"
}

refresh_sot "$ROOT/packages/titan_ims/bin/titan2-ims-setup.sh" "$SRC_SYS/titan2-ims-setup.sh"
refresh_sot "$ROOT/patches/init/titan2-pad-agent.rc" "$SRC_SYS/titan2-pad-agent.rc"
refresh_sot "$ROOT/packages/titan_ims/init/titan2-ims.rc" "$SRC_SYS/titan2-ims.rc"
refresh_sot "$ROOT/patches/init/titan2-sensor-privacy.rc" "$SRC_SYS/titan2-sensor-privacy.rc"
refresh_sot "$ROOT/patches/init/titan2-privacy-overlay.rc" "$SRC_SYS/titan2-privacy-overlay.rc"
refresh_sot "$ROOT/patches/bin/titan2-bind-mtk-privacy-overlay.sh" "$SRC_SYS/titan2-bind-mtk-privacy-overlay.sh"
refresh_sot "$ROOT/patches/init/titan2-netfw.rc" "$SRC_SYS/titan2-netfw.rc"
refresh_sot "$ROOT/packages/titan_openwrt/titan2-openwrt.sh" "$SRC_SYS/titan2-openwrt.sh"
refresh_sot "$ROOT/packages/titan_openwrt/titan2-openwrt-boot.sh" "$SRC_SYS/titan2-openwrt-boot.sh"
refresh_sot "$ROOT/packages/titan_openwrt/titan2-openwrt.rc" "$SRC_SYS/titan2-openwrt.rc"
refresh_sot "$ROOT/packages/titan_openwrt/openwrt-lpctl" "$SRC_SYS/openwrt-lpctl"
[ -f "$SRC_SYS/titan2-netfw.rc" ] || die "missing titan2-netfw.rc (firewall init)"
[ -f "$SRC_SYS/titan2-fw-observe" ] || die "missing titan2-fw-observe ELF"
# Guard: ims-setup must not force location_mode
if grep -qE 'settings put secure location_mode' "$SRC_SYS/titan2-ims-setup.sh" 2>/dev/null; then
  die "titan2-ims-setup.sh still forces location_mode — product privacy violation"
fi
# Guard: sensor-privacy must protect Hostless_Spk_Init / FM (media silence fix)
if ! grep -q '_is_protected_capture_pcm\|Hostless_Spk' "$SRC_SYS/titan2-sensor-privacy.sh" 2>/dev/null; then
  die "titan2-sensor-privacy.sh missing hostless/spk protect (v13+) — media silence risk"
fi
if ! grep -q 'aux_pub_abort\|v34-aux-watch\|v35-aux-hold' "$SRC_SYS/titan2-sensor-privacy.sh" 2>/dev/null; then
  die "titan2-sensor-privacy.sh missing HI847S privacy-abort watchdog (v34+)"
fi
if ! grep -q 'v35-aux-hold' "$SRC_SYS/titan2-sensor-privacy.sh" 2>/dev/null; then
  die "titan2-sensor-privacy.sh missing v35-aux-hold (logcat recycle is heresy)"
fi
stage_file "$SRC_SYS/Android.bp" "$DEST_SYS/Android.bp"
for f in $_SYSBIN_SOT titan2-ims-setup.sh titan2-sensor-privacy.sh \
  titan2-pad-agent.rc titan2-ims.rc titan2-sensor-privacy.rc titan2-netfw.rc \
  titan2-openwrt.sh titan2-openwrt-boot.sh titan2-openwrt.rc openwrt-lpctl \
  titan2-bind-mtk-privacy-overlay.sh titan2-privacy-overlay.rc FrameworkResOverlay.apk; do
  [ -f "$SRC_SYS/$f" ] || continue
  stage_file "$SRC_SYS/$f" "$DEST_SYS/$f"
done
chmod 755 "$DEST_SYS"/*.sh 2>/dev/null || true
info "staged product sysbins → $DEST_SYS (sensor-privacy v13 hostless protect; no location force)"

# Live Titan AtlasOS overlays → vendor/titanus2/overlays (runtime_resource_overlay)
SRC_OV="$SRC_PROD/overlays"
DEST_OV="$MISTERZTR_TREE/vendor/titanus2/overlays"
if [ -f "$SRC_OV/Android.bp" ] || [ -d "$SRC_OV/TitanCubeIconMask" ]; then
  if [ "$DRY" = "1" ]; then
    info "dry-run would stage overlays → $DEST_OV"
  else
    mkdir -p "$DEST_OV"
    cp -a "$SRC_OV/." "$DEST_OV/"
    info "staged AtlasOS overlays → $DEST_OV"
  fi
else
  die "missing live Titan overlays at $SRC_OV"
fi

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
  for h in atlas-hybrid-ctl.sh atlas-hybrid-watch.sh; do
    src=""
    for c in "$SRC_ATLAS/$h" "$ROOT/packages/titan_atlas/scripts/$h" "$ROOT/patches/bin/$h"; do
      [ -f "$c" ] && [ -s "$c" ] && src="$c" && break
    done
    [ -n "$src" ] || die "missing Atlas hybrid helper $h"
    stage_file "$src" "$DEST_ATLAS/$h"
    [ "$DRY" != "1" ] && chmod 755 "$DEST_ATLAS/$h" 2>/dev/null || true
  done
  # ROM base ELFs (auth/sudo/repl/enter) — priv-app is UI; execute from /system/bin
  for elf in atlas-auth atlas-sudo atlas-auth-askpass atlas atlas-lpctl atlas-enter atlas-enterd atlas-android; do
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
  # enterd launcher + init — Android.bp lists these; ELF alone does not start @atlasenter
  for extra in atlas-enterd.sh atlas-enterd.rc; do
    src=""
    for c in "$SRC_ATLAS/$extra" "$ROOT/apps/titan_atlas/assets/bin/$extra"; do
      [ -f "$c" ] && [ -s "$c" ] && src="$c" && break
    done
    [ -n "$src" ] || die "missing Atlas $extra"
    stage_file "$src" "$DEST_ATLAS/$extra"
    [ "$DRY" != "1" ] && [ "$extra" = "atlas-enterd.sh" ] && chmod 755 "$DEST_ATLAS/$extra" 2>/dev/null || true
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
  for extra in BRIDGE policy; do
    if [ -f "$SRC_ATLAS/$extra" ]; then
      stage_file "$SRC_ATLAS/$extra" "$DEST_ATLAS/$extra"
    fi
  done
  chmod 755 "$DEST_ATLAS/atlas-hybrid.sh" "$DEST_ATLAS/atlas-net.sh" \
    "$DEST_ATLAS/atlas-hybrid-boot.sh" "$DEST_ATLAS/atlas-hybrid-ctl.sh" \
    "$DEST_ATLAS/atlas-hybrid-watch.sh" 2>/dev/null || true
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
STAMP_DAY=$(date +%Y%m%d)
STAMP_MK="$MISTERZTR_TREE/device/phh/treble/atlasos_stamp.mk"
if [ "$DRY" != "1" ]; then
  mkdir -p "$(dirname "$STAMP_MK")"
  cat >"$STAMP_MK" <<EOF
# Generated by stage_gsi_product.sh — do not edit.
PRODUCT_SYSTEM_PROPERTIES += \\
    ro.build.display.id=AtlasOS-23.2-${STAMP_DAY} \\
    ro.atlasos.build=${STAMP_DAY} \\
    ro.lineage.display.version=AtlasOS-23.2-${STAMP_DAY}
EOF
  info "AtlasOS stamp AtlasOS-23.2-${STAMP_DAY} → atlasos_stamp.mk"
fi

# Ensure product lunches inherit titanus2.mk (vanilla bvN4 + GApps bgN4).
INHERIT='$(call inherit-product, device/phh/treble/titanus2.mk)'
for lunch_mk in \
  "$MISTERZTR_TREE/device/phh/treble/lineage_arm64_bvN4.mk" \
  "$MISTERZTR_TREE/device/phh/treble/lineage_arm64_bgN4.mk"
do
  [ -f "$lunch_mk" ] || continue
  if grep -qF 'device/phh/treble/titanus2.mk' "$lunch_mk" 2>/dev/null; then
    info "$(basename "$lunch_mk") already inherits titanus2.mk"
  elif [ "$DRY" = "1" ]; then
    info "dry-run would append inherit to $(basename "$lunch_mk")"
  else
    {
      echo ""
      echo "# AtlasOS product packages (gsi_source / stage_gsi_product)"
      echo "$INHERIT"
    } >>"$lunch_mk"
    info "appended inherit-product titanus2.mk → $(basename "$lunch_mk")"
  fi
done

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
  # Keep prebuilt_apps mirror of APKs for offline stage (skip if same inode).
  _mirror() {
    [ -n "${1:-}" ] && [ -f "$1" ] || return 0
    [ -e "$2" ] && [ "$1" -ef "$2" ] && return 0
    cp -f "$1" "$2"
  }
  _mirror "$CTRL_APK" "$SRC_APPS/TitanControls.apk"
  _mirror "$USB_APK" "$SRC_APPS/TitanUsbHid.apk"
  _mirror "$CUBE_APK" "$SRC_APPS/CubeContact.apk"
  _mirror "$CTRL_PRIV" "$SRC_APPS/privapp-permissions-com.titanus2.controls.xml"
  _mirror "$USB_PRIV" "$SRC_APPS/privapp-permissions-com.titanus2.usbhid.xml"
  _mirror "$USB_DEF" "$SRC_APPS/default-permissions-com.titanus2.usbhid.xml"
  _mirror "$CUBE_PRIV" "$SRC_APPS/privapp-permissions-com.titanus2.cubecontact.xml"
fi

# Fail closed: every Android.bp src/apk must exist in the staged dest.
if [ "$DRY" != "1" ]; then
  verify_bp_srcs() {
    local dest=$1 bp="$1/Android.bp"
    [ -f "$bp" ] || return 0
    local missing=0 f
    while IFS= read -r f; do
      [ -n "$f" ] || continue
      if [ ! -e "$dest/$f" ]; then
        info "MISSING bp src: $dest/$f"
        missing=1
      fi
    done < <(python3 - "$bp" <<'PY'
import re, sys
text = open(sys.argv[1], encoding="utf-8").read()
for m in re.finditer(r'(?:src|apk)\s*:\s*"([^"]+)"', text):
    print(m.group(1))
for m in re.finditer(r'srcs\s*:\s*\[([^\]]*)\]', text, re.S):
    for s in re.findall(r'"([^"]+)"', m.group(1)):
        print(s)
PY
)
    [ "$missing" = 0 ] || die "staged dest missing Android.bp sources: $dest"
  }
  verify_bp_srcs "$DEST_PRE"
  verify_bp_srcs "$DEST_APPS"
  verify_bp_srcs "$DEST_HID"
  verify_bp_srcs "$DEST_SYS"
  verify_bp_srcs "$DEST_ATLAS"
  if [ -d "$DEST_OV" ]; then
    while IFS= read -r ovbp; do
      verify_bp_srcs "$(dirname "$ovbp")"
    done < <(find "$DEST_OV" -name Android.bp)
  fi
fi

info "stage_gsi_product OK (dry=$DRY) touchpadd+apps+usb_hid+sysbins+atlas"
info "next: lunch $LUNCH_TARGET && m systemimage"
info "      or: ./scripts/misterztr/pipeline.sh --from=patch"
