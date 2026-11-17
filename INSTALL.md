# Install the current version

**2026-10-07 EEA.** Hybrid super plus the stock Unihertz kernel.
Prerequisite: the bootloader is already unlocked.

This page is the current version only. What the image contains:
[releases/2026-10-07-eea.md](releases/2026-10-07-eea.md).
Older download links stay in [releases.md](releases.md).

## Download

Copy the whole line. The part after `#` is the link password.

```
https://drive.proton.me/urls/YD8FFWB8MC#JXFn3V4xuQuu
```

Unpack so these files sit in one directory:

| File | Role |
| --- | --- |
| `super.img` | Hybrid system + stock vendor (6,766,274,892 bytes) |
| `boot.img` | Stock EEA kernel, firmware train `2026041315` |
| `vendor_boot.img`, `init_boot.img`, `dtbo.img` | Matching stock boot images |
| `vbmeta.img`, `vbmeta_system.img`, `vbmeta_vendor.img` | Stock AVB images |
| `vbmeta_disabled.img` | Fallback only if `--disable-verity` fails |

`init_boot.img` is stock. Do not replace it with a Magisk or KernelSU image,
and do not replace `boot.img` with any other kernel.

Check the download:

```bash
sha256sum -c SHA256SUMS.txt
```

If that file is not in the download, check the images themselves:

```
0228a36db6520d1371983db42efdd1c1f8d9a2c78a37504f24c465e9aa530b0b  super.img
2c307d8958c16c145e284efb5bb967c497827b6c6951f42319c1ef038c2d47df  boot.img
21eee60bc3b6fd2ccd38de58338c73a0f8e9efe941a1ff031cc440f5cf93b3c0  vendor_boot.img
a19c4877b3e1bc009d7238d39f41732097708dcab85a8be2b1e1471d887bbce5  init_boot.img
eea5b8c05f96502a5b5ec9f471e52dc11052b7f0489dd91f12d232880cea6cd1  dtbo.img
e38910dc84d21645a723a408094251dfd3eb9a2f24ec411f286ed706678554e4  vbmeta.img
10847c6c4ec2cfba2fc836a5fb80162796efa5fee74d546cc244303edba674d9  vbmeta_system.img
bf0c5c1f72f341fa053bea908ec609497f084d9cc70401b5f9db811f629f2490  vbmeta_vendor.img
8f70c5b73592e5797c4fedf0d847550151d18f40810222dcec0a8e3209a6be33  vbmeta_disabled.img
```

## Before you flash

- Unihertz Titan 2 **EEA**. In the bootloader, `fastboot getvar product` prints `g71v78c2k_dfl_eea`.
- Bootloader **unlocked**: `fastboot getvar unlocked` prints `yes`. If it prints `no`, stop. This package does not unlock the phone. Unlocking wipes user data.
- A PC with Android platform-tools (`adb`, `fastboot`) and a data USB cable.
- A charged phone. This flash **erases all user data**.

The image is LineageOS 23.2 VANILLA (no GApps) on the stock EEA vendor.
USB debugging stays on after the wipe, and the setup wizard is skipped.
There is no app root.

## Flash

Run this from the directory that contains `super.img` and `boot.img`.

```bash
adb reboot bootloader
fastboot devices
fastboot getvar unlocked    # yes
fastboot getvar product     # g71v78c2k_dfl_eea
```

```bash
fastboot --disable-verity --disable-verification flash vbmeta_a vbmeta.img
fastboot --disable-verity --disable-verification flash vbmeta_b vbmeta.img
fastboot --disable-verity --disable-verification flash vbmeta_system_a vbmeta_system.img
fastboot --disable-verity --disable-verification flash vbmeta_system_b vbmeta_system.img
fastboot --disable-verity --disable-verification flash vbmeta_vendor_a vbmeta_vendor.img
fastboot --disable-verity --disable-verification flash vbmeta_vendor_b vbmeta_vendor.img
```

If a vbmeta command fails with `Failed to find AVB_MAGIC`, write the fallback
and continue:

```bash
fastboot flash vbmeta_a vbmeta_disabled.img
fastboot flash vbmeta_b vbmeta_disabled.img
```

```bash
fastboot flash boot_a boot.img
fastboot flash boot_b boot.img
fastboot flash vendor_boot_a vendor_boot.img
fastboot flash vendor_boot_b vendor_boot.img
fastboot flash init_boot_a init_boot.img
fastboot flash init_boot_b init_boot.img
fastboot flash dtbo_a dtbo.img
fastboot flash dtbo_b dtbo.img

fastboot set_active a
fastboot flash super super.img
```

If the bootloader refuses `super`, switch to fastbootd and write it there:

```bash
fastboot reboot fastboot
fastboot flash super super.img
```

`erase` leaves no filesystem. Format instead:

```bash
fastboot format:f2fs userdata || fastboot format userdata
fastboot format:f2fs metadata || fastboot format metadata
fastboot reboot
```

First boot can take several minutes.
