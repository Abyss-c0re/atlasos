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
    + residual OEM (IMS SAFE, eSIM, keylayout/idc until in GSI)
        → hybrid super.img
```

Included classes are **on by default** and remain kitchen/flavor toggles.

## Match list (current device)

| Class | Default | Notes |
|-------|---------|-------|
| Mouse Mode / pad | on | `titan2-touchpadd` INPROC_PARK; inject on pin 20260804 |
| Home / Recents | on | Controls `GLOBAL_ACTION_*` only |
| HID | on | USB gadget + HID app |
| Atlas | on | Terminal + Debian plane |
| Nanobot | on | Grok + LAN / on-device; weights not in git |
| CubeContact | on | Rear lattice only |
| UI | on | cube-ux + square icon mask + gsi_source 0010/0050/0060/0070 |
| FM / IR | on | libs + wrapper; no China IR app |
| IMS | on | SAFE v2 only |
| OpenEUICC | on | fetched / your build; APK not in git |
| microG | on | flavor `microg`; APKs fetched |

## Not in the product image

Clanker Commander, robot mesh, Cube lore, Magisk-as-UX, Path-B trees,
stock firmware, kernel sources (none exist).
