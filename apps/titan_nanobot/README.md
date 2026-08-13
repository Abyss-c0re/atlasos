# Nanobot (Android)

Reusable on-device agent UI + peer for **any** Android unit (phone, XR headset, lab boards).

- Package id: `com.titanus2.nanobot` (stable for updates; not a product brand)
- APK artifact: `Nanobot.apk` (compat copy `TitanNanobot.apk` for older ROM scripts)
- Cloud Grok, local GGUF, shell tools, optional Accessibility device control
- Ships **in** some ROMs as priv-app; same APK installs as a normal user/system app elsewhere

## Build

```bash
./build.sh
adb install -r Nanobot.apk
```

## Privacy

`device_control` / `a11y_control` default OFF. User enables Accessibility for UI control.
