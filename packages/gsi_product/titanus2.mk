# Titan 2 product packages — compiled into MisterZtr GSI (not hybrid inject).
# Staged into MISTERZTR_TREE by scripts/misterztr/stage_gsi_product.sh
# SoT: docs/project/OPTIMIZE_SOURCE_PRODUCT.md · SOURCE_PRODUCT.md
#
# Live pin 20260804 systemimage does NOT contain these packages.
# Hybrid WITH_TOUCHPADD_INJECT=1 until a *new* exported GSI is boot-proven.
# Confirmed Mouse Mode ELF (2026-08-13): INPROC_PARK + pad-only + titan2-virtual-mouse.

# Pad runtime SoT (Phase 1.5): musl prebuilt + disabled init (pad-agent starts mouse).
PRODUCT_PACKAGES += \
    titan2-touchpadd \
    titan2-touchpadd.rc

# Phase 2 apps (prebuilt APKs + privapp XML). Staged by stage_gsi_product.sh.
# Hybrid APK inject residual: WITH_CONTROLS/USB_HID/CUBE=0 (GSI has apps).
PRODUCT_PACKAGES += \
    TitanControls \
    TitanUsbHid \
    CubeContact \
    PocketBoard \
    HwKeyboardLayouts \
    privapp-permissions-com.titanus2.controls.xml \
    privapp-permissions-com.titanus2.usbhid.xml \
    default-permissions-com.titanus2.usbhid.xml \
    privapp-permissions-com.titanus2.cubecontact.xml

# Atlas — terminal KEY + hybrid Debian plane (no proprietary CLIs in image).
# HW keyboard / IME / keylayout: product keyboard track — out of Atlas ROM scope.
# Rootfs seed (essentials: nano curl …): packages/titan_atlas build_debian_rootfs;
# staged under /system/etc/atlas/ when present (optional large artifact).
PRODUCT_PACKAGES += \
    TitanAtlas \
    privapp-permissions-com.titanus2.atlas.xml \
    atlas-hybrid.sh \
    atlas-net.sh \
    atlas-hybrid-boot.sh \
    atlas-hybrid-ctl.sh \
    atlas-hybrid-watch.sh \
    atlas-hybrid.rc \
    atlas-auth \
    atlas-sudo \
    atlas-auth-askpass \
    atlas-enter \
    atlas-enterd \
    atlas-lpctl \
    atlas-agent-status \
    atlas-screencap \
    atlas

# USB HID gadget stack (independent of APK inject). Hybrid residual:
# WITH_USB_HID_STACK=1 (default). REG 20260806: stack must never couple to APK=0.
PRODUCT_PACKAGES += \
    titan2-hid-bridge \
    titan2-usb-hid-service.sh \
    titan2_usb_hid_enable_hid.sh \
    titan2_usb_hid_service.sh \
    titan2-usb-hid.rc

# Product sysbins + init (Phase 3 peels + pad-agent residual + IMS setup).
# ims-setup: never forces location_mode (clears titan2_force_location_for_wfc).
# Peels 2.166–2.191: hybrid WITH_INPUT still injects same scripts until GSI pin has them.
PRODUCT_PACKAGES += \
    titan2-pad-agent.sh \
    titan2-typing-watch.sh \
    titan2-ims-heal.sh \
    titan2-keyled-write.sh \
    titan2-key-fire.sh \
    titan2-dev-action.sh \
    titan2-side-key.sh \
    titan2-subdisplay.sh \
    titan2-key-watch.sh \
    titan2-b1-kl.sh \
    titan2-keylayout.sh \
    titan2-keycode-inject.sh \
    titan2-dt2w.sh \
    titan2-pad-idc.sh \
    titan2-plane-heal.sh \
    titan2-cube-load-land.sh \
    titan2-cool-park.sh \
    titan2-ui-plane.sh \
    titan2-pad-apply.sh \
    titan2-ctrl-seed.sh \
    titan2-ims-setup.sh \
    titan2-sensor-privacy.sh \
    titan2-fw \
    titan2-fw.sh \
    titan2-remote-adb.sh \
    titan2-vpn-hotspot.sh \
    titan2-pad-agent.rc \
    titan2-ims.rc \
    titan2-sensor-privacy.rc
