# UI patches

Keyboard-first CyberDeck. Simple intent = result. Apps look like Lineage
Settings (`Theme.DeviceDefault`). No private design language.

## System-wide only

| Patch / tool | What |
|--------------|------|
| `patches/gsi_source/0010-…keyguard…` | No secondary keyguard / AOD suppress |
| `0050` / `0060` | Sensor privacy toggles (fail closed) |
| `0070` | Torch sysfs under camera privacy |
| `titan2-cube-ux.sh` | Night + cyan accent seed, IME-with-HW |
| `packages/titan_icon_mask_only` | Square adaptive icon mask |

Full zero-radius Settings/SystemUI RROs are **off** (they break notifications
and Settings). Do not turn them on for ship.

## Apps

Labels = control name or action. State lines = short facts. Credits only in
`CREDITS.md`, never on device.

CubeContact is a rear-display lattice, not a second OS theme.
