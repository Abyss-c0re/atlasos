# TitanUsbHid

Bluetooth (and optional USB) HID device for Titan 2 — soft pad + keys as a PC keyboard/mouse.

## Rootless (hybrid default)

Packed as **system priv-app** + **in-ROM USB stack** (`WITH_USB_HID=1`):

| Path | Needs Magisk/`su`? |
|------|---------------------|
| **Bluetooth HID** | No — app-private state + `BluetoothHidDevice` |
| **USB gadget** | No — kernel `CONFIG_USB_F_HID` + configfs; init service `titan2-usb-hid` (root) attaches `/dev/hidg*`; app only writes control files |

System files:

- `/system/etc/titan2_usb_hid/{enable_hid.sh,service.sh,hid_bridge}`
- `/system/bin/titan2-usb-hid-service.sh` + `/system/etc/init/titan2-usb-hid.rc`
- `/system/bin/titan2-ctrl-seed.sh` — pre-creates `0666` shells under `/data/misc/titan2` (pad=off, session=0)

Control protocol (rootless): app writes `titan2_usb_hid_*` and `titan2_hid.inj` under
`/data/misc/titan2` + `/data/local/tmp` + app-private files. No ADB composite while HID is on
(`sys.usb.config=titan_hid`). See `docs/project/CONTROL_PLANE.md`.

UI: **Pad** | **Keys** | **Type** (stock IME payload + optional Enter) | ⚙  
**UI policy:** OS Material/DeviceDefault — Cube is system-wide only ([`docs/project/PRODUCT_UX.md`](../../docs/project/PRODUCT_UX.md)).  
Keys: core arrows/edit + favorites list + **More** (nav/F-keys).  
Settings: Link/target first; mouse/session under progressive disclosure.

Default transport is **BT+USB** when the stack is present. Magisk module remains an optional override.

## Build

```bash
./apps/titan_usb_hid/build.sh
# → apps/titan_usb_hid/TitanUsbHid.apk
```

## Hybrid pack

```bash
# via variant matrix (default on)
./scripts/rom_variant.py build --preset lab

# or low-level
WITH_USB_HID=1 GSI_IMG=… ./scripts/build_minimal_bootable.sh
```

Injects:

- `/system/priv-app/TitanUsbHid/TitanUsbHid.apk`
- `privapp-permissions-com.titanus2.usbhid.xml`
- `default-permissions-com.titanus2.usbhid.xml` (BT/location auto-grant)

Prop: `ro.titanus2.usb_hid=1`

## Lab / host harness

See `tools/README.md` — BlueZ PC as HID host + adb lab intents.

**Wireless ADB required for agents** when USB gadget HID is on (USB adb dies).
Procedure: [`docs/project/WIRELESS_ADB.md`](../../docs/project/WIRELESS_ADB.md) ·
`./scripts/host/adb_wireless_titan.sh IP:5555`.

## Credits

USB gadget path: PeterGSI (CREDITS.md). BT path is in-tree.
