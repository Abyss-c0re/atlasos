# Agent notes — AtlasOS

**Product:** CyberDeck HybridOS for Unihertz Titan 2.  
**Workshop (lab / flash / serials):** sibling `titanus2`. Do not copy lab
policy or device serials into this repo.

## SoT

1. [`README.md`](README.md) — what this OS is
2. [`chain/sources.yaml`](chain/sources.yaml) — linked upstreams (never upload)
3. [`docs/PRODUCT.md`](docs/PRODUCT.md) — image matrix
4. [`docs/UX.md`](docs/UX.md) — UI patches (system-wide only)
5. [`patches/gsi_source/SERIES`](patches/gsi_source/SERIES)

## Rules

- **HOLD_FLASH** is owned by the human / workshop. This repo does not flash.
- Link Lineage / MisterZtr / stock. Never `git add` those trees.
- Cube = icon mask + `titan2-cube-ux` only. No hive, prophecy, or Clanker.
- No secrets: `./scripts/check_clean.sh` must pass before commit.
- Live pin `20260804` still needs hybrid touchpadd inject until a *new* GSI
  export is boot-proven. Do not flip inject off on that pin.
- Product Recents / Home = Titan Controls `GLOBAL_ACTION_*`. Never
  `am start RecentsActivity`. Never `keyevent 187` as Recents.

## Build

```bash
./scripts/link.sh
./scripts/link_workshop.sh    # workshop paths → this tree (one inode)
./scripts/check_links.sh
./scripts/build.sh            # GSI from SERIES + hybrid pack via linked workshop
./scripts/build.sh --gsi-only
./scripts/check_clean.sh
```

Shared app/patch paths must stay symlinks. If AtlasOS drifts, an agent copied
instead of linking — run `link_workshop.sh` again. Do not maintain a second
`apps/titan_atlas/src`.
