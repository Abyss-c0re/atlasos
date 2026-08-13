# Titan USB HID — in-ROM (hybrid) stack

Shipped into system when **`WITH_USB_HID_STACK=1`** (default **1**).  
Independent of `WITH_USB_HID` (APK inject; default 0 when GSI has the priv-app).  
**REG 20260806:** setting `WITH_USB_HID=0` used to drop this stack — USB mode dead.  
App stays rootless; this service runs as root via init and drives configfs + `hid_bridge`.

| File | Role |
|------|------|
| `enable_hid.sh` | Attach/detach **pure HID** (kbd+mouse) on `usb_gadget/g1` — **no ADB required** |
| `hid_bridge` | Built from `../magisk_titan2_usb_hid/hid_bridge.c` |
| `service.sh` | Session loop; reads app-private ctrl files |
| `titan2-usb-hid-service.sh` | `/system/bin` entry |
| `titan2-usb-hid.rc` | start on `sys.boot_completed` |

**USB policy:** HID session = pure keyboard+mouse only. **ADB is not required for HID.**  
While session is on, `ffs.adb` is unlinked (host PC sees HID only). Lab/dev ROMs
(`ro.titanus2.adb_bootstrap=1`) optionally arm TCP ADB `:5555` so the *lab* can
still shell the phone without USB ADB; release does not force that.  
`titan_hid` is **never** written to `persist.sys.usb.config`. Stop restores the
previous host mode (lab: often `mtp,adb`; release: no forced ADB).

Control plane: `/data/misc/titan2` (OS). App-owned dirs also polled; see `docs/project/CONTROL_PLANE.md`.
