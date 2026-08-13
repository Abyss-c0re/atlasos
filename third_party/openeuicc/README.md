# OpenEUICC (third_party)

Upstream: [OpenEUICC](https://openeuicc.com/) · source [PeterCxy/OpenEUICC](https://gitea.angry.im/PeterCxy/OpenEUICC)  
License: **GNU GPL v3 only** (no “or later”). Native `lpac-jni`: LGPL-2.1.

Open-source eSIM Local Profile Assistant (LPA). **Privileged** variant manages internal and removable eUICC chips without Google Play Services.

## Role on Titanus2

| Item | Value |
| --- | --- |
| Package | `im.angry.openeuicc` |
| Install path | `/system/priv-app/OpenEUICC/OpenEUICC.apk` |
| Privapp whitelist | `/system/etc/permissions/privapp_whitelist_im.angry.openeuicc.xml` |
| Product flag | `WITH_OPENEUICC=1` (default **on** for `dev` / `lab_rootless` / `release`) |
| Prop | `ro.titanus2.openeuicc=1` when packed |

Unmodified upstream package name (we do not rebrand). Ship as **priv-app** so privileged telephony / SE permissions apply.

## Prebuilt source of truth

Official **privileged** APKs are not published as store releases; CI publishes debug Magisk modules:

- Magisk ZIP: <https://openeuicc.com/magisk/latest.zip>
- Site: <https://openeuicc.com/>

```bash
./third_party/openeuicc/fetch.sh
```

Writes:

| Path | Role |
| --- | --- |
| `prebuilt/priv-app/OpenEUICC/OpenEUICC.apk` | Hybrid inject payload |
| `permissions/privapp_whitelist_im.angry.openeuicc.xml` | Upstream whitelist |
| `prebuilt/VERSION` | Version + sha256 pin |
| `magisk_meta/module.prop` | Upstream Magisk metadata |

APKs are **gitignored** (`**/*.apk`). Fetch before pack if missing; `build_minimal_bootable.sh` will auto-fetch when `WITH_OPENEUICC=1`.

## Release vs debug

Magisk CI builds are labeled **debug** by upstream and are **not** ideal long-term for consumer images. For a self-built **release** APK:

```bash
git clone --recurse-submodules https://gitea.angry.im/PeterCxy/OpenEUICC.git
# keystore.properties required — see upstream README
./gradlew :app:assembleRelease
# copy app/build/outputs/apk/release/*.apk → prebuilt/priv-app/OpenEUICC/OpenEUICC.apk
```

Prefer release when lab keys exist; keep Magisk CI pin for reproducible hybrid packs.

## GPL boundary

If you redistribute a ROM containing this APK, you must make the corresponding **source** available under GPLv3 (upstream tree + any patches). This repo does not vendor the full OpenEUICC source; use the pinned commit in `prebuilt/VERSION` / Magisk `module.prop`.

## Disable

```bash
WITH_OPENEUICC=0 ./scripts/rom_variant.py build --preset ship
# or in config/variants.yaml per profile: with_openeuicc: false
```
