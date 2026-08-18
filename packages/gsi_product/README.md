# GSI product packages

**Status:** Phase **1.5 touchpadd** in GSI pin **20260806** (boot-proven).  
Phase **2 apps** Android.bp + stage + PRODUCT_PACKAGES landed.  
**Atlas** GSI package: `prebuilt_atlas/` (terminal + hybrid scripts; no HW-kb scope; no proprietary CLI).
`systemimage` + KEEP_DATA prove before dropping hybrid inject.  
Program: [OPTIMIZE_SOURCE_PRODUCT.md](../../docs/project/OPTIMIZE_SOURCE_PRODUCT.md).

## Intent

Ship **our** APKs and native binaries as **Soong modules** in the MisterZtr
systemimage via `patches/gsi_source/` product deltas — **not** permanent hybrid
inject.

| Module | Source of truth in product git | GSI install |
|--------|-------------------------------|-------------|
| TitanControls | `apps/titan_controls/` | priv-app |
| TitanUsbHid | `apps/titan_usb_hid/` | priv-app |
| USB HID stack | `packages/titan_usb_hid_system/` + `hid_bridge` | `prebuilt_usb_hid/` (gadget + init; **not** coupled to APK inject) |
| Sysbins | pad-agent, peels, ims-setup, **sensor-privacy** | `prebuilt_sysbin/` — **ims-setup never forces location**; **sensor-privacy v13+ never revokes Hostless_Spk_Init/FM** |
| CubeContact | `apps/cube_contact/` | priv-app |
| titan2-touchpadd | submodule `third_party/titan2-touchpadd/` → packed `prebuilt_touchpadd/` | `/system/bin` + init.rc |
| TitanAtlas | `apps/titan_atlas/` + `packages/titan_atlas/` | priv-app + hybrid scripts + boot mount |
| TitanNanobot (opt) | `apps/titan_nanobot/` | optional PRODUCT_PACKAGES |
| AtlasOS overlays | `packages/gsi_product/overlays/` (live Titan 2026-08-18) | `/product/overlay` via `runtime_resource_overlay` |

## Land process

1. **touchpadd (Phase 1.5):** `./scripts/misterztr/stage_gsi_product.sh`  
   copies `prebuilt_touchpadd/` → `MISTERZTR_TREE/vendor/titanus2/prebuilts/…`  
   and installs `titanus2.mk` + inherit on `lineage_arm64_bvN4`.  
   SERIES `0040-…-product-mk.patch` carries the mk delta; **ELF never in patch**.
2. **Apps (Phase 2):** same stage script copies APKs + privapp XML into  
   `vendor/titanus2/prebuilts/apps/` + PRODUCT_PACKAGES in `titanus2.mk`.
3. `pipeline.sh` → KEEP_DATA boot-prove → **remove** matching inject  
   (`WITH_CONTROLS=0` `WITH_USB_HID=0` `WITH_CUBE_CONTACT=0`).

## Do not

- Revive Path B full `vendor/titanus2` framework bake.
- Put OEM IMS/camera APKs here (hybrid inject permanent).
- Dual-install: after GSI ships apps, hybrid must not also inject same packages.
