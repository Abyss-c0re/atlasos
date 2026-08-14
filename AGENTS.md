# Agent notes — AtlasOS

**Product:** CyberDeck HybridOS for Unihertz Titan 2. Standalone clone-to-build.  
**Workshop:** optional sibling `titanus2` (flash / serials only).

## SoT

1. [`README.md`](README.md) — what this OS is
2. [`chain/sources.yaml`](chain/sources.yaml) — linked upstreams (never upload)
3. [`docs/PRODUCT.md`](docs/PRODUCT.md) — image matrix
3a. [`docs/PRODUCT_LOCK.md`](docs/PRODUCT_LOCK.md) — working product gates (not a certified stamp)
4. [`docs/UX.md`](docs/UX.md) — UI patches (system-wide only)
5. [`patches/gsi_source/SERIES`](patches/gsi_source/SERIES)

## Rules

- **HOLD_FLASH** is owned by the human / workshop. This repo does not flash.
- Link Lineage / MisterZtr / stock. Never `git add` those trees.
- Cube = icon mask + `titan2-cube-ux` only. No hive, prophecy, or Clanker.
- No secrets: `./scripts/check_clean.sh` must pass before commit.
- AtlasOS mouse driver: stage `titan2-touchpadd` into `MISTERZTR_TREE` and
  compile it into the GSI (`PRODUCT_PACKAGES` + IDC). Do **not** hybrid-inject
  the ELF on an AtlasOS-built image. Workshop pin `20260804` still injects.
- Product Recents / Home = Titan Controls `GLOBAL_ACTION_*`. Never
  `am start RecentsActivity`. Never `keyevent 187` as Recents.

## Build

```bash
./scripts/bootstrap.sh
./scripts/build.sh --flavor vanilla|microg|gapps
./scripts/sync-modules.sh --status
./scripts/check_dupes.sh
./scripts/check_clean.sh
./scripts/check_publish.sh    # required before a public remote
```

Shared app/patch paths must stay symlinks. Layout: [`docs/LAYOUT.md`](docs/LAYOUT.md).
If AtlasOS drifts, an agent copied instead of linking — run `link_workshop.sh`
and `collapse_dupes.py`. Do not maintain a second `apps/titan_atlas/src`
or a second `titan2-pad-agent.sh`.
