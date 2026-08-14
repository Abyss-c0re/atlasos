# Atlas — Titan ROM terminal

**Version:** 0.3.0 · **Design:** [`docs/project/ATLAS.md`](../../docs/project/ATLAS.md)

Keyboard-first terminal for Titan 2 (Termux replacement path):

- **Pure C core** (`packages/titan_atlas/native/atlas.c`)
- Thin Java host (Material / DeviceDefault — no private Cube chrome)
- Builtins: `paths`, `install`, `lsbin`, `priv`, `run`, `usbip`, `pkg`, `modules`, `sandbox`, `nanobot`
- **Install plane:** app `files/bin` only — **do not remount /system** (GSI erofs RO)
- UI: Copy (selection or all), Clear, Restart, minimal Settings
- Bundled (our NDK builds): `atlas`, `atlas-sudo` / `su`  
- Not in git: GNU bash, `quest-usbip-host` (no corresponding source here)

## Build

```bash
./apps/titan_atlas/build.sh
adb install -r apps/titan_atlas/TitanAtlas.apk
```

## Install Grok CLI (no system mount)

```
paths
# push binary to device first, then:
install /sdcard/Download/grok
run grok
# or curl into BIN:
# curl -L -o $ATLAS_BIN/grok URL && chmod +x $ATLAS_BIN/grok
```

## First session

```
help
paths
lsbin
priv on          # only if you need su — not for CLI install
modules list
nanobot status
```

## Ship

**App-only** tip install is enough for Atlas. Hybrid priv-app inject is **optional**
(not default until lab-proven). Never flash solely for this APK.
