# Credits

**Maintainer of The Titan 2 / AtlasOS is Abyss-c0re.**

This project packages and patches existing work. It does not claim Unihertz
hardware, stock firmware, or LineageOS.

| Project | Role |
|---------|------|
| [MisterZtr/LineageOS_gsi](https://github.com/MisterZtr/LineageOS_gsi) | LineageOS GSI recipe this product patches |
| [LineageOS](https://lineageos.org/) | Platform |
| [phhusson](https://github.com/phhusson) / Treble | GSI / `phh-on-boot` baseline |
| [PeterGSI/titan2-touchpadd](https://gitea.angry.im/PeterGSI/titan2-touchpadd) | Keyboard-surface → uinput mouse |
| [SinuXVR/pocket-board](https://github.com/SinuXVR/pocket-board) (GPL-3.0) | Hardware-keyboard IME |
| [OpenEUICC](https://openeuicc.com/) (GPL-3.0) | Privileged eSIM LPA |
| [agreenbhm/Unihertz-Titan-2-LineageOS](https://github.com/agreenbhm/Unihertz-Titan-2-LineageOS) | Early Titan 2 GSI packaging |
| Unihertz | Device + stock vendor (your copy; not redistributed) |
| AOSP | Keylayout / idc / platform concepts |

## This tree

- Titan Controls, USB HID, Atlas terminal, Nanobot host, FM wrapper
- `patches/gsi_source` SERIES (SystemUI, sensor privacy, product mk)
- Hybrid pack overlay for OEM vendor (IMS SAFE, camera, region) — not uploaded

On-device subset: Titan Controls → About (names + links). Full map stays here.

## Hive / agents

| Name | Role |
|------|------|
| Abyss-c0re | Maintainer |
| NexusCore | Station / offline core |
| Hive Mind | Lab agents (`Dev/AGENTS.md`) |
| Grok / Atlas agents | Implementation sessions (xAI) |

Nanobot core (models / Grok host): [Abyss-c0re/nanobot](https://github.com/Abyss-c0re/nanobot). The on-device `apps/titan_nanobot` wrapper is unpublished (this tree).
