# Titan FM — open-source FM for Titan 2

**Package:** `com.android.fmradio` (MediaTek/Unihertz package id; **OEM Agui app
is not shipped** — only this OSS app + `libaguifmjni` blob).

**App code:** Apache-2.0 (this tree).  
**Chip blob:** `libaguifmjni.so` — proprietary MediaTek/Unihertz, binary only.

Hybrid path: `/system/priv-app/TitanFm/TitanFm.apk` (not `AguiFMRadio/`).

## Why not thrash Agui?

Stock Agui is a closed APK with cube-broken layout, ADSP audio-patch crashes,
and Agui framework stubs. We keep only the **hardware contract** the chip
already speaks:

| Layer | Ours |
|-------|------|
| UI | DeviceDefault Settings-like (freq, power, seek, speaker, presets, RDS line) |
| Engine | `FmEngine` — software render only, **48 kHz**, no `createAudioPatch` |
| JNI | `com.android.fmradio.FmNative` — exact names `libaguifmjni` RegisterNatives |
| Device | `/dev/fm` + `AudioRecord(source=1998 / RADIO_TUNER)` |

UX ideas (presets, antenna, RDS) loosely inspired by open
[RFM-Radio](https://github.com/vladislav805/RFM-Radio) — **not** its Qualcomm
backend (useless on MT6635).

## Build

```bash
./apps/titan_fm/build.sh
# → apps/titan_fm/TitanFm.apk
```

## Lab install (KSU/Magisk)

Replaces `/system/priv-app/AguiFMRadio/AguiFMRadio.apk` (existing path so
magic mount works):

```bash
packages/magisk_titan2_titan_fm/install_to_device.sh
# optional durable:
FORCE_RESTART=1 packages/magisk_titan2_titan_fm/install_to_device.sh
```

First time after signature change may need:

```bash
cmd package install-existing com.android.fmradio
pm grant com.android.fmradio android.permission.RECORD_AUDIO
```

## Live ops

```bash
# unlock phone first (audio may mute while keyguard)
am start -n com.android.fmradio/.MainActivity --ez auto_on true
am start -n com.android.fmradio/.MainActivity --ez auto_off true
adb logcat -s TitanFm:D
```

## Hybrid ROM

`WITH_STOCK_FM_IR=1` prefers `apps/titan_fm/TitanFm.apk` when built, else
falls back to patched/stock Agui prebuilts. Same path + `libaguifmjni`.

## USB-C audio / antenna

Separate problem from “does FM start”. See
[`docs/project/USB_C_AUDIO_FM_ANTENNA.md`](../../docs/project/USB_C_AUDIO_FM_ANTENNA.md).

- **Analog** USB-C→3.5 (Type-C accessory mode) = FM wire antenna (`switchAntenna(0)`).
- **Digital** USB DAC = may play music; not an RF antenna.
- Probe: `./scripts/host/probe_usb_c_audio.sh` (wireless ADB + dongle, PC unplugged).
- Digital host policy experiment: `packages/magisk_titan2_usb_audio/`.

## Proven (lab 2026-08-03)

```
TitanFm: openDev ok
TitanFm: antenna 1 rc=0
TitanFm: render 48k stereo
TitanFm: on 97.5 Hz  spk=true
AudioFlinger: Active AudioTrack 48000 Hz underruns=0 (com.android.fmradio)
RADIO_TUNER capture not silenced
```
