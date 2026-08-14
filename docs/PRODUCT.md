# Product — image matrix

AtlasOS output should match the current Titan 2 CyberDeck bench (the “slavic
engineering” hybrid) plus the source fix series already in this tree.

## Layers

```
linked MisterZtr Lineage tree
    + patches/gsi_source SERIES
    + packages/gsi_product  (PRODUCT_PACKAGES)
        → systemimage (GSI)
stock Unihertz vendor (your zip, linked)
    + residual OEM (IMS SAFE, eSIM — not the mouse driver)
        → hybrid super.img
```

**Mouse Mode** is compiled into the GSI: musl ELF + `titan2-touchpadd.rc` +
`/system/usr/idc/touchPad.idc` (ignore). AtlasOS does not inject that ELF
onto a finished image. `./scripts/build.sh` verifies the tree is staged
before `m systemimage`.

Included classes are **on by default** and remain kitchen/flavor toggles.

## Match list (current device)

| Class | Default | Notes |
|-------|---------|-------|
| Mouse Mode / pad | on | GSI `PRODUCT_PACKAGES` `titan2-touchpadd` (INPROC_PARK). Inject off on AtlasOS builds. |
| Home / Recents | on | Controls `GLOBAL_ACTION_*` only |
| HID | on | USB gadget + HID app |
| Atlas | on | Terminal + Debian on super `atlas_linux_a` (wipe-survive) |
| OpenWrt | on | Official 24.10.4 on super `atlas_openwrt_a` (wipe-survive) |
| Router | on | Settings wrapper over OpenWrt + LuCI. No Firewall tile. |
| Nanobot | on | On-device peer `:8787` + MCP; weights not in git |
| CubeContact | on | Rear lattice from on-device peer; fail closed if dead |
| UI | on | cube-ux + square icon mask + gsi_source 0010/0050/0060/0070 |
| FM / IR | on | libs + wrapper; no China IR app |
| IMS | on | SAFE v2 only |
| OpenEUICC | on | fetched / your build; APK not in git |
| microG | on | flavor `microg`; APKs fetched |

## Not in the product image

Clanker Commander, robot mesh, Cube lore, Magisk-as-UX, Path-B trees,
stock firmware, kernel sources (none exist), Settings/Controls Firewall pages.

Working-product contract (not a certified stamp): [`PRODUCT_LOCK.md`](PRODUCT_LOCK.md).
