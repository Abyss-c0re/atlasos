# Nanobot (Android)

Reusable on-device agent UI + peer for **any** Android unit (phone, XR headset, lab boards).

- Package id: `com.titanus2.nanobot` (stable for updates; not a product brand)
- APK artifact: `Nanobot.apk` (compat copy `TitanNanobot.apk` for older ROM scripts)
- Cloud Grok, local GGUF, shell tools, optional Accessibility device control
- Ships **in** some ROMs as priv-app; same APK installs as a normal user/system app elsewhere

## Build

Rebuild the aarch64 engine from [`Abyss-c0re/nanobot`](https://github.com/Abyss-c0re/nanobot) first so `assets/nanobot.arm64` matches tip:

```bash
NANOBOT_SRC=/home/voldemar/Dev/AI/nanobot \
  bash ../../../titanus2/packages/titan2_nanobot/build_android.sh  # from atlasos/apps
# or: bash packages/titan2_nanobot/build_android.sh               # from titanus2
./build.sh
adb install -r Nanobot.apk
```

`build.sh` copies the largest `nanobot-aarch64` it finds into `assets/nanobot.arm64` (gitignored). Cube Flasher does the same when **Nanobot agent** is selected.

## Privacy

`device_control` / `a11y_control` default OFF. User enables Accessibility for UI control.
