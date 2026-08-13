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
    + residual OEM (IMS SAFE, camera, eSIM, keylayout/idc until in GSI)
        → hybrid super.img
```

## Match list (current device)

| Class | Owner | Notes |
|-------|-------|-------|
| Mouse Mode / pad | `titan2-touchpadd` INPROC_PARK, pad-only | GSI 0040 + stage; inject on pin 20260804 |
| Home short | Controls `GLOBAL_ACTION_HOME` | TitanKey scan 580 |
| Recents long | Controls `GLOBAL_ACTION_RECENTS` | Never `am start RecentsActivity` |
| HID | USB gadget + HID app | Independent of APK inject |
| Atlas | Terminal + Debian plane | No proprietary CLIs in image |
| Nanobot | Grok + LAN / on-device models | Model weights not in git |
| CubeContact | Optional rear lattice | No hive |
| UI | cube-ux + square icon mask + gsi_source 0010/0050/0060/0070 | System-wide |
| FM / IR | libs + wrapper | No China IR app |
| IMS | SAFE v2 only | No native-lib dual inject |

## Not in the product image

Clanker Commander, robot mesh, Cube lore, Magisk-as-UX, Path-B trees,
stock firmware, kernel sources (none exist).
