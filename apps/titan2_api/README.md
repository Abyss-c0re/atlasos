# titan2_api — Titan2 framework client

Shared Java sources (not a Gradle AAR). Compile into:

- `apps/titan_controls` (hosts `Titan2CoreService`)
- `apps/titan_usb_hid` (client)
- any third-party app that needs pad / keymap / LED

## Docs

- [docs/project/TITAN2_FRAMEWORK_API.md](../../docs/project/TITAN2_FRAMEWORK_API.md)
- [docs/project/PAD_HID_HARMONY.md](../../docs/project/PAD_HID_HARMONY.md)

## Root?

**No Magisk / `su` required** for product automation. The Controls priv-app
runs as a privileged process; pad-agent (init root) owns sysfs. Clients only
bind Messenger or write the control plane.
