# PocketBoard — product integration (Titan hybrid ROM)

**Upstream:** https://github.com/SinuXVR/pocket-board (GPL-3.0)  
**Clone:** this directory (shallow; not Pastiera)

## Role

| Mode | Behavior |
|------|----------|
| **Android** | **Default IME** — EN + RU (subtypes 1;2 = en_US + ru BB Passport) |
| **Atlas / Debian** | Same physical key positions via Titan maps → XKB/symbols (generator next) |

## Titan-specific maps (layout match SoT)

| File | Language |
|------|----------|
| `app/src/main/res/xml/keyboard_mapping_en_us_titan.xml` | English |
| `app/src/main/res/xml/keyboard_mapping_ru_bbp_titan.xml` | Russian BB Passport |
| `app/src/main/res/xml/keyboard_mapping_ru_t_titan.xml` | Russian Translit |
| `app/src/main/res/xml/keyboard_mapping_ru_alt.xml` | Russian Alt |
| `app/src/main/res/xml/method.xml` | IME subtypes |

## GSI

- Prebuilt APK: `packages/gsi_product/prebuilt_apps/PocketBoard.apk` (from lab Titan pull until we build from this tree in CI)
- `PRODUCT_PACKAGES += PocketBoard`
- Default seed: `titan2-plane-heal.sh` → `heal_default_ime` prefers PocketBoard when empty

## Build from source (later)

```bash
cd third_party/pocket-board && ./gradlew :app:assembleRelease
# stage APK → packages/gsi_product/prebuilt_apps/PocketBoard.apk
```

## Layout match rule

Android PocketBoard Titan XML keycodes must equal Debian hybrid XKB for EN/RU so mode switch does not remap muscle memory.
