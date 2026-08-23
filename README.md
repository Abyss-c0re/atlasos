# AtlasOS

**A portable lab on the Unihertz Titan 2.**

Hardware keyboard, trackpad, rear display, USB/BT HID, a Debian plane, and
an OpenWrt router for the hotspot — in a pocket. You compile it. Kernel and
vendor stay stock: Unihertz publishes no device sources.

```
LineageOS GSI (MisterZtr + our patches)
  + stock vendor / boot (your region zip)
  = hybrid super
```

Flavors: **vanilla** / **microg** / **gapps**.

## What it is

### Titan Controls

Settings hub (not a launcher toy). Pad mode, key remap, keyboard light,
privacy, sub display, developer ADB.

- **Key mapping** — physical keys to actions (Home/Recents via
  `GLOBAL_ACTION_*` only, never a ghost Recents activity).
- **Sub display** — rear panel as a real surface (backlight + composition).
  Cube lattice is system chrome / optional rear face, not a private theme
  inside Controls.
- **Pad** — `titan2-touchpadd` in the GSI. **Original author:
  [PeterGSI](https://gitea.angry.im/PeterGSI/titan2-touchpadd).** Product
  **fork** is a git submodule
  ([Abyss-c0re/titan2-touchpadd](https://github.com/Abyss-c0re/titan2-touchpadd)):
  pad-only, INPROC park. `./scripts/build.sh` pulls that SHA, compiles it,
  and packs the ELF. We did not invent the driver.

### HID (USB / Bluetooth)

Titan as a keyboard/mouse for a PC, tablet, or glasses.

**Shared mode** — HID is armed and the phone stays yours: you type and
move on the guest **and** keep using Titan (multitask). Exclusive grab is
the other mode (guest owns the pad). Shared is the lab default when you
still need the device.

### Atlas (Debian plane)

Terminal + wipe-surviving Debian on super LP `atlas_linux`.

Debian **cannot** see Android Binder or user files as if they were local.
One bridge:

```text
android <tool> [args]
android cat|write|ls <android-path>
```

Policy is Magisk-style **allow / ask / deny** (Settings → Atlas → Access).
Sudo/wipe stay gated. Seeing the screen is `android screencap`, not a fake
`screencap` on PATH.

### OpenWrt (hotspot)

Official OpenWrt on super LP `atlas_openwrt`. The phone hotspot is a
router you can actually manage: LuCI + Titan **Router** wrapper — clients,
leases, firewall. Not a Settings toggle that pretends to be a lab.

### Portable lab

This is the point: a Titan you can sit down with and work.

| Piece | Job |
|-------|-----|
| HW keyboard + pad | Type and point without a glass slab |
| Sub display | Second surface, not a toy |
| HID shared | Drive a host, keep the phone |
| Atlas | Real Debian, secure Android IPC |
| OpenWrt | Own the hotspot clients |
| ADB / Atlas | Agents and humans use the same plane |


Working-product gates: [`docs/PRODUCT_LOCK.md`](docs/PRODUCT_LOCK.md).
Credits: [`CREDITS.md`](CREDITS.md). On-device: Controls → **About**.

## Build (standalone)

```bash
git clone https://github.com/Abyss-c0re/AtlasOS.git AtlasOS && cd AtlasOS
./scripts/bootstrap.sh                 # Lineage + MisterZtr + our SERIES
./scripts/build.sh --flavor vanilla    # GSI; mouse driver compiled in
./scripts/build.sh --flavor microg     # + fetched microG (not in git)
./scripts/build.sh --flavor gapps      # MisterZtr bgN4 lunch, if the tree has it
```

Fetch list: [`DEPENDENCIES.md`](DEPENDENCIES.md).

The Android tree is **local** (`.links/lineage`, gitignored). Tens of GB.

Hybrid super needs **your** region-matching Unihertz zip (`STOCK_ZIP=`).
That firmware is not in this git. Cross-region vendor is forbidden.

## Cube Flasher (host)

Desktop manager for pins and GSI. Ships its own `adb` / `fastboot`
(`tools/cube_flasher/platform-tools/`). **USB only** — no tcpip ADB, no
hardcoded serials. The device list is whatever is on the cable right now.

```bash
./tools/cube_flasher/cube-flasher
```

| Control | Job |
|---------|-----|
| **BUILD** | Kitchen-cook a hybrid super (Debian + OpenWrt + OSS FM + USB audio by default) |
| **BUILD AND FLASH** | Cook, then write the new pin over USB |
| **FLASH SELECTED** | Write a listed pin |
| **BUILD GSI** | AtlasOS GSI (`vanilla` / `microg` / `gapps`) |
| **PULL GITHUB** | Fast-forward this repo from `origin` |
| **TITAN** tab | Live USB ROM + changelog since last flash |
| Pins / GSI tabs | Sort, multi-select, delete |

ETA is estimated from the selected options and last real cook/flash/GSI
times. Progress is live from the child process — leftover `100%` logs are
ignored.

Hybrid cook and flash need the optional workshop sibling `titanus2` (stock
zip, kitchen, flash script). GSI build and Git pull work from this clone.
Nothing writes a device until you press a flash button.

## Owned apps from source

```bash
./scripts/build_owned_apps.sh
```

`scripts/build.sh` runs that unless `SKIP_OWNED_APPS=1`. TitanFm is a
priv-app (`libaguifmjni` needs `libcutils` u2014 a user update cannot load it).
Output is **Speaker** or **Default** (A2DP when a headset is connected).
USB-C analog is the FM antenna, not a PCM sink.

## Layout

| Path | Role |
|------|------|
| `apps/` | Controls, HID, Atlas, Nanobot, CubeContact, OSS FM |
| `scripts/build_owned_apps.sh` | Compile owned apps from source on a clone |
| `patches/gsi_source/` | Lineage SERIES |
| `patches/bin/` | Shippable scripts (one copy) |
| `packages/gsi_product/` | `PRODUCT_PACKAGES` + prebuilts |
| `config/flavors.yaml` | vanilla / microg / gapps |
| `config/modules.yaml` | Remotes + unpublished in-tree |
| `scripts/sync-modules.sh` | Pull remotes into `.links/upstream/` |
| `tools/cube_flasher/` | Host Cube Flasher + bundled USB adb/fastboot |

Apps without a public remote stay here until split (see modules.yaml).

```bash
./scripts/sync-modules.sh --status
./scripts/check_dupes.sh
./scripts/check_clean.sh
./scripts/check_publish.sh    # required before a public push
```

## License

Original work: MIT (`LICENSE`). Third-party map: [`NOTICE.md`](NOTICE.md).  
What must not be in git: [`docs/LEGAL.md`](docs/LEGAL.md).

Termux emulator sources in Atlas are **GPLv3**. microG / OpenEUICC APKs
are fetched at build time.
