# Dependencies (clone-to-build)

AtlasOS does **not** upload Lineage, stock firmware, or large binaries.
You compile those locally.

## You must fetch (not in this git)

| Need | Where | How |
|------|--------|-----|
| Lineage + MisterZtr tree | tens of GB | `./scripts/bootstrap.sh` (`MISTERZTR_TREE` or `.links/lineage`) |
| Unihertz stock zip | your device region | `STOCK_ZIP=` for hybrid super — never committed |
| microG APKs | flavor `microg` | `./packages/microg/fetch.sh` |
| OpenEUICC APK | eSIM | `./third_party/openeuicc/fetch.sh` |
| `titan2-touchpadd` ELF | pad/mouse | `./scripts/build_touchpadd.sh` (source: Abyss-c0re fork) |
| Debian rootfs | Atlas hybrid | `packages/titan_atlas` `build_debian_rootfs.sh` |
| Android NDK + SDK | Atlas / Controls APKs | `apps/*/build.sh` |

## Public remotes

| Project | Remote | In this git? |
|---------|--------|----------------|
| MisterZtr GSI recipe | https://github.com/MisterZtr/LineageOS_gsi | no (clone) |
| LineageOS | https://github.com/LineageOS | no (repo sync) |
| PocketBoard | https://github.com/SinuXVR/pocket-board | vendored sources (`third_party/pocket-board`) |
| titan2-touchpadd | https://github.com/Abyss-c0re/titan2-touchpadd | overlay + patches; ELF rebuilt |
| OpenEUICC | https://gitea.angry.im/PeterCxy/OpenEUICC | fetch APK |
| nanobot (models) | https://github.com/Abyss-c0re/nanobot | **published separately** |
| Termux emulator | https://github.com/termux/termux-app | vendored `apps/titan_atlas/src/com/termux/` |

## Unpublished (this tree is SoT until split)

| Module | Path | Note |
|--------|------|------|
| Atlas terminal + Debian plane | `apps/titan_atlas` | no public repo yet |
| Titan Nanobot app | `apps/titan_nanobot` | wrapper; core nanobot is public |
| Titan Controls | `apps/titan_controls` | Titan-only |
| Titan USB HID | `apps/titan_usb_hid` | Titan-only |

`./scripts/sync-modules.sh --status` prints the same map.

## Checks before a public push

```bash
./scripts/check_clean.sh
./scripts/check_publish.sh
```
