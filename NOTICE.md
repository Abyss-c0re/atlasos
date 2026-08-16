# Third-party notices

Original AtlasOS / Titan product code in this repository is MIT (`LICENSE`)
**except** where a subdirectory says otherwise. This file is the attribution
map. It is not legal advice.

## Do not ship (not in this git)

| Item | Why |
|------|-----|
| Unihertz stock zip, vendor, kernel, OEM camera/IMS native libs | Proprietary. You already own your device image. |
| Google Play / GApps blobs | Proprietary. `--flavor gapps` uses MisterZtr lunch only. |
| microG APKs | Fetched at build (`packages/microg/fetch.sh`). |
| GNU bash binary | GPLv3 — we do not ship the binary; no corresponding source tree here. |
| `quest-usbip-host` binary | Built from a crate not vendored here. |
| `titan2-touchpadd` prebuilt ELF | Rebuild from `third_party/titan2-touchpadd/patches` + upstream. |
| Debian rootfs tarball | Generated locally. |

## In this git (keep licenses with the files)

| Path | Project | License |
|------|---------|---------|
| `apps/titan_atlas/src/com/termux/` | [termux/termux-app](https://github.com/termux/termux-app) emulator | **GPLv3** — `apps/titan_atlas/src/com/termux/LICENSE` |
| `apps/titan_atlas/assets/term/xterm*.js` | [@xterm/xterm](https://github.com/xtermjs/xterm.js) 5.5.0 | MIT — `third_party/LICENSES/XTERMJS-MIT.txt` |
| `apps/titan_atlas/assets/fonts/DejaVuSansMono.ttf` | DejaVu Fonts | Bitstream Vera / Arev — `third_party/LICENSES/DEJAVU.txt` |
| `apps/titan_atlas/assets/ssl/cacert.pem` | Mozilla CA | MPL-2.0 — `third_party/LICENSES/CACERT-MPL.txt` |
| `titan2-touchpadd` (fetched) | [Abyss-c0re/titan2-touchpadd](https://github.com/Abyss-c0re/titan2-touchpadd) (PeterGSI original) | Upstream license — fetch, do not commit ELF |
| `third_party/openeuicc/` | [PeterCxy/OpenEUICC](https://gitea.angry.im/PeterCxy/OpenEUICC) | **GPLv3** (APK not committed) |
| `apps/titan_fm/` | Apache-2.0 wrapper | `apps/titan_fm/LICENSE` |
| `apps/titan_nanobot/` | project license | `apps/titan_nanobot/LICENSE` |

## Combined works

- A **Titan Atlas APK** that includes `com.termux` sources is a combined work
  with GPLv3 Termux code. Distribute that APK under GPLv3 (or do not ship
  those files in the APK).
- **OpenEUICC** remains GPLv3 when you fetch and ship the APK.

Our MIT grant does **not** apply to those components.
