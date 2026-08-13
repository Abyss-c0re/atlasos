# Titan USB HID — host lab tools

## `bt_host_test.py`

Uses **this PC’s Bluetooth (BlueZ)** as the HID **host** and drives the phone app over **adb**.

```
Phone  = HID Device (keyboard/mouse)   apps/titan_usb_hid
PC     = HID Host                      BlueZ + this script
```

### Setup

```bash
# Fedora/Arch-ish
sudo dnf install python3-dbus python3-gobject python3-evdev   # or pacman -S …
# optional for real key asserts (not only sysfs/logcat):
sudo usermod -aG input "$USER"   # re-login
```

### Run

```bash
# phone on adb, BT on, app installed
python3 apps/titan_usb_hid/tools/bt_host_test.py
python3 apps/titan_usb_hid/tools/bt_host_test.py --serial <device-serial> --keep-session
python3 apps/titan_usb_hid/tools/bt_host_test.py --cleanup
```

### What it does

1. Registers a BlueZ agent (auto-accept pair / HID authorize)
2. Makes the adapter pairable + discoverable
3. `adb am start … --ez lab_auto true` → preferred host = this PC, transport=BT
4. `Device.ConnectProfile(HID)` — required; phone-only `hid.connect` often stalls
5. Waits for sysfs node **Titan 2 Keyboard**
6. Injects keys via `titan2_hid_hw.out` (FGS drains to `BluetoothHidClient`)
7. Asserts via evdev (if permitted) or `sendReport kbd ok=true` in logcat

### Lab intent (app 0.12+)

```bash
adb shell am start -n com.titanus2.usbhid/.MainActivity \
  --ez lab_auto true --es lab_action start \
  --es host_mac AA:BB:CC:DD:EE:FF --es host_name LabHost \
  --ei transport 2 --ez screen_off true
```
