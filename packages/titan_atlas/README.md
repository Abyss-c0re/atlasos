# titan_atlas — native core + rootfs plan

- **Core:** `native/atlas.c` → `out/atlas` (aarch64 Android)
- **Build:** `./build_native.sh` (also copies into `apps/titan_atlas/assets/bin/`)
- **Product design:** [`docs/project/ATLAS.md`](../../docs/project/ATLAS.md)

## Bundled tools (assets)

| Binary | Source |
|--------|--------|
| `atlas` | this package |
| `nanobot` | staged from `packages/titan2_nanobot/bin/` when present |
| `quest-usbip-host` | staged from `packages/quest_usbip_host/assets/` when present |
| `usbip` / `proot` | optional — add under `apps/titan_atlas/assets/bin/` |

## Debian rootfs

Not in git. On device: `ATLAS_HOME/debian` via `pkg rootfs bootstrap` (stub → real fetch next).

**Arch: aarch64 only for Titan 2.**
