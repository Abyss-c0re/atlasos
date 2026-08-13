# MisterZtr GSI source pipeline

**Faithful wrapper** around [MisterZtr/LineageOS_gsi](https://github.com/MisterZtr/LineageOS_gsi)
(`lineage-23.2` README). Builds a **VANILLA EXT4** `system.img`, optionally applies
**Titan source patches** from the product git, then hybrid-packs stock vendor + apps.

The Android tree is **local only** (`MISTERZTR_TREE` under artifacts — not uploaded).

## Why this exists

| Failed approach | Why |
|-----------------|-----|
| Own “titanus2” Lineage tree + custom manifests (Path B) | Bootloop; not the MisterZtr binary surface |
| `vendor/titanus2` full framework bake as sole product | Diverges hard; bisect nightmare |
| `ALLOW_MISSING_DEPENDENCIES` / slim trees | Hides real breaks |

| This pipeline | Why |
|---------------|-----|
| Exact MisterZtr init / sync / upstream `apply-patches.sh` / lunch | Same recipe as release GSI that boots on Titan 2 |
| Titan deltas as small git-tracked patches (`patches/gsi_source/`) | Compile-in fixes without uploading the tree |
| Hybrid inject for product APKs / OEM / keylayout | Fast iteration; stays out of `systemimage` when it should |

SoT: [`docs/project/SOURCE_PRODUCT.md`](../../docs/project/SOURCE_PRODUCT.md).

## One command

```bash
# Full source GSI (hours–days of disk + CPU on first sync)
./scripts/misterztr/pipeline.sh

# GSI + hybrid
./scripts/misterztr/pipeline.sh --pack lab_rootless

# After export only:
./scripts/rom_variant.py build --preset lab_rootless \
  --gsi gsi/LineageOS-23.2-YYYYMMDD-VANILLA-EXT4-GSI.img
```

Stages:

| Script | Role |
|--------|------|
| `init.sh` | `repo init` + treble_manifest |
| `sync.sh` | `repo sync` + ensure `LineageOS_gsi` |
| `apply_patches.sh` | MisterZtr patches → `patches/gsi_source/SERIES` |
| `apply_titan_source_patches.sh` | Titan SERIES only |
| `new_source_patch.sh` | Capture dirty project diff → SERIES |
| `build_gsi.sh` | breakfast + `make systemimage` |
| `export_gsi.sh` | → `gsi/LineageOS-23.2-DATE-…` + misterztr-src alias |
| `pipeline.sh` | all of the above |

## Titan source patches

```bash
# Edit under MISTERZTR_TREE, then:
./scripts/misterztr/new_source_patch.sh \
  --name systemui-secondary-no-keyguard \
  --project frameworks/base \
  --paths packages/SystemUI

# Re-apply SERIES onto tree
FORCE_TITAN_REPATCH=1 ./scripts/misterztr/apply_titan_source_patches.sh

# Dry-run
./scripts/misterztr/apply_titan_source_patches.sh --dry-run
```

Empty SERIES is valid — product still ships via hybrid inject until a class is migrated.

## Config

```bash
cp config/misterztr.env.example config/misterztr.local.env
# MISTERZTR_TREE=… (default ~/Dev/titanus2-artifacts/misterztr_lineage)
```

## Deployment model

```
MISTERZTR_TREE (local, not in git)
  repo sync + LineageOS_gsi apply-patches
  + patches/gsi_source/* (product git)
  → make systemimage
  → gsi/LineageOS-23.2-DATE-VANILLA-EXT4-GSI.img
        +
  hybrid pack (stock vendor + apps/patches/packages)
        =
  super.img → flash Titan (lab: KEEP_DATA, no kernel day-to-day)
```

**Never** flash a raw source `system.img` alone on Titan 2 without hybrid pack.

## Forbidden

- Path B: `lineage/local_manifests/titanus2.xml`, `remove_non_gsi.xml` into this tree  
- `vendor/titanus2` product bake as the only ship path  
- `scripts/lineage_source/*` for product GSI (archive only)  
- Committing `MISTERZTR_TREE` into product git  

Old pure script: `scripts/lineage_source/build_misterztr_pure.sh` → prefer **this** directory.
