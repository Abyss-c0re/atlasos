# AtlasOS

**CyberDeck OS for the Unihertz Titan 2.**

A HybridOS: LineageOS GSI (MisterZtr product path) + stock vendor, because
Unihertz does not publish kernel or device sources. The Android kernel on the
device is the OEM kernel. This tree is the **product overlay** — patches,
apps, and a lockfile of upstreams. The Lineage tree is **linked, never uploaded**.

| Intended use | What that means here |
|--------------|----------------------|
| Smart-home input | Hardware keyboard, trackpad, USB/BT HID into a station |
| Development device | ADB-capable lab SKU; Atlas terminal; Debian plane |
| Grok + local models | Atlas / Nanobot talk to Grok and to on-device or LAN models |

Cube is **system chrome only** (square icon mask + night/cyan). This is not a
Cube OS product. Hive, prophecy, and robot-mesh apps stay out of this repo.

## Why hybrid

Unihertz refuses source. There is no from-source kernel or vendor. The honest
shape is:

1. **MisterZtr** LineageOS VANILLA EXT4 GSI, built from linked sources + our patches
2. **Stock vendor / boot** from a region-matching Unihertz zip you already own
3. **This overlay** compiled into the GSI (`PRODUCT_PACKAGES`) or residual pack

Output target: the current Titan 2 bench image (touchpadd, HID, Titan Controls,
Atlas, Nanobot, light Cube face, UI patches) plus the source fix series.

## Cubechain store

Upstream trees are **links**, not contents. The store is [`chain/sources.yaml`](chain/sources.yaml).

```bash
./scripts/link.sh          # resolve remotes / local trees (nothing uploaded)
./scripts/build.sh         # apply SERIES → stage → GSI → hybrid pack
./scripts/check_clean.sh   # refuse secrets / OEM blobs / LOS trees
```

Never commit `MISTERZTR_TREE`, stock firmware, or `.links/`.

## What ships in the image

| Surface | In this repo | On device |
|---------|--------------|-----------|
| Trackpad / Mouse Mode | `third_party/titan2-touchpadd` + `patches/gsi_source/0040` | `titan2-touchpadd` (INPROC_PARK) |
| Titan Controls | `apps/titan_controls` | Settings hub, Home/Recents |
| USB / BT HID | `apps/titan_usb_hid` + `packages/titan_usb_hid_system` | Host gadget |
| Atlas | `apps/titan_atlas` + `packages/titan_atlas` | Terminal + Debian plane |
| Nanobot | `apps/titan_nanobot` (source; models not in git) | Grok + LAN/on-device |
| CubeContact | `apps/cube_contact` | Optional rear lattice — no hive docs |
| FM / IR libs | `apps/titan_fm` | Hardware radio |
| UI | `patches/gsi_source` 0010–0070 + `titan2-cube-ux` + icon mask | System-wide only |
| Keylayout / IDC | `patches/keylayout`, `patches/idc` | TitanKey + pad |

## What this repo is not

- Not a LineageOS fork. Not Path-B `vendor/titanus2` bake.
- Not a Cube / hive / Clanker product.
- Not a redistributor of Unihertz firmware, IMS native libs, or stock camera.

Workshop (lab meta, serials, flash policy) stays in **titanus2**. AtlasOS is
the clean CyberDeck product split.

## License

MIT for original scripts, patches, and apps in this tree — see `LICENSE`.
Upstream projects keep their own licenses (`CREDITS.md`).
