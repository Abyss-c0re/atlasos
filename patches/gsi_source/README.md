# Titan GSI source patches (on MisterZtr tree)

These patches apply **inside** the local Android tree (`MISTERZTR_TREE`, not in
this git) **after** MisterZtr’s own `LineageOS_gsi/patches/apply-patches.sh`.

The LOS tree is **never** uploaded: tens of GB under
`~/Dev/titanus2-artifacts/misterztr_lineage` (see `config/misterztr.local.env`).

**Policy 2026-08-06:** product we own **compiles into the GSI**. Hybrid inject is
residual (OEM vendor train + temporary dual-run).  
SoT: `docs/project/SOURCE_PRODUCT.md` · program: `docs/project/OPTIMIZE_SOURCE_PRODUCT.md`.

## Apply order

```
1. repo sync (MisterZtr recipe)
2. LineageOS_gsi/patches/apply-patches.sh   # upstream GSI
3. patches/gsi_source/SERIES               # Titan product (this dir)
4. make systemimage   # includes PRODUCT_PACKAGES / prebuilts when SERIES lands them
5. hybrid pack (stock vendor + OEM residual inject only)
```

```bash
./scripts/misterztr/apply_patches.sh          # MisterZtr + Titan SERIES
FORCE_REPATCH=1 ./scripts/misterztr/apply_patches.sh
./scripts/misterztr/apply_titan_source_patches.sh --dry-run
```

## Active SERIES (ship with source GSI rebuild)

| Patch | Replaces hybrid inject |
|-------|------------------------|
| `0010-systemui-no-secondary-keyguard-and-aod-suppress` | `WITH_SYSTEMUI_PATCH` APK + `TitanSubdisplayOverlay` RRO |
| `0020-phh-product-maintainer-gsi-source-marker` | Maintainer identity on GSI product.prop |
| `0040-prebuilt-titan2-touchpadd-product-mk` | PRODUCT_PACKAGES + `titanus2.mk` (binary via `stage_gsi_product.sh`) |
| `0050-framework-sensor-privacy-toggles-cube` | config cam/mic software toggles + power≠camera double-tap |
| `0060-framework-force-software-sensor-toggles-cube` | **system_server** always supports software cam/mic (no vendor RRO win, no overlay thrash) |
| `packages/gsi_product` belt `titan2-sensor-privacy` | Node fail-closed companion (PRODUCT_PACKAGES) |

**Banned:** MTK FrameworkResOverlay bind, Magisk privacy modules as product.

## Stage prebuilts (binary not in git patches)

```bash
./scripts/misterztr/stage_gsi_product.sh   # vendor/titanus2/prebuilts/titan2-touchpadd
# also run automatically from apply_titan_source_patches.sh
```

## Planned (see SERIES comments)

| Target | Replaces |
|--------|----------|
| PRODUCT_PACKAGES Controls/HID/Cube | hybrid APK inject |
| sepolicy pad plane | root/tip workarounds |

## What belongs where

| **GSI / gsi_source (default)** | **Hybrid residual only** |
|--------------------------------|---------------------------|
| Framework / SystemUI fixes | Stock vendor / boot helpers |
| `device/phh` product.prop / defaults | OEM IMS APKs, camera, eSIM |
| SELinux in tree | Magisk **lab** modules |
| **Our** priv-apps (Controls, HID, Cube, nanobot opt) | Temporary dual-run during migrate |
| **touchpadd** prebuilt | — |
| Thin init for pad/keyled | Growing pad-agent shell (**delete**, don't inject forever) |

Scaffold: `packages/gsi_product/`.
