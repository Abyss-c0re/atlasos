#!/usr/bin/env python3
"""
PC-side Bluetooth HID *host* harness for Titan USB HID app.

  Phone  = Bluetooth HID Device (keyboard + mouse)
  This PC = Bluetooth HID Host (BlueZ + this script)

What it automates:
  1. BlueZ agent (auto-accept pair / AuthorizeService HID)
  2. Adapter pairable + discoverable
  3. adb lab_auto → preferred host = this PC, start BT session
  4. Device.ConnectProfile(HID) so BlueZ finishes HID setup
     (phone hid.connect alone often stalls with "setup in progress")
  5. Soft-inject keys via /data/local/tmp/titan2_hid_hw.out (FGS drain)
  6. Assert "Titan 2 Keyboard" appears (sysfs) + optional evdev keys
     + logcat sendReport ok=true

Usage:
  python3 apps/titan_usb_hid/tools/bt_host_test.py
  python3 apps/titan_usb_hid/tools/bt_host_test.py --serial <device-serial>
  python3 apps/titan_usb_hid/tools/bt_host_test.py --cleanup
  python3 apps/titan_usb_hid/tools/bt_host_test.py --keep-session

Deps: python3-dbus, python3-gobject, python3-evdev, adb, BlueZ
evdev key assert needs membership in group `input` (or uaccess):
  sudo usermod -aG input $USER   # re-login after
"""
from __future__ import annotations

import argparse
import os
import re
import select
import subprocess
import sys
import threading
import time
from dataclasses import dataclass, field
from typing import Optional

DEFAULT_PHONE_MAC = "7E:77:B1:39:3E:09"
PKG = "com.titanus2.usbhid"
ACTIVITY = f"{PKG}/.MainActivity"
TRANSPORT_BT = 2
HID_UUID = "00001124-0000-1000-8000-00805f9b34fb"
HID_TO_EV = {0x04: 30, 0x05: 48, 0x06: 46, 0x07: 32, 0x08: 18, 0x28: 28, 0x2c: 57, 0x52: 103}


def log(msg: str) -> None:
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


def die(msg: str, code: int = 1) -> None:
    log(f"FAIL: {msg}")
    sys.exit(code)


def run(cmd, *, timeout=30.0, input_text=None) -> subprocess.CompletedProcess:
    return subprocess.run(
        cmd if isinstance(cmd, list) else cmd,
        shell=not isinstance(cmd, list),
        input=input_text,
        text=True,
        capture_output=True,
        timeout=timeout,
    )


# ---------------------------------------------------------------------------
# BlueZ agent (thread + GLib mainloop)
# ---------------------------------------------------------------------------
class AgentThread(threading.Thread):
    def __init__(self) -> None:
        super().__init__(daemon=True, name="bluez-agent")
        self._loop = None
        self.ready = threading.Event()
        self.error: Optional[str] = None

    def run(self) -> None:
        try:
            import dbus
            import dbus.service
            import dbus.mainloop.glib
            from gi.repository import GLib

            dbus.mainloop.glib.DBusGMainLoop(set_as_default=True)
            bus = dbus.SystemBus()
            path = "/titanus2/bt_host_test/agent"

            class Agent(dbus.service.Object):
                @dbus.service.method("org.bluez.Agent1", in_signature="", out_signature="")
                def Release(self):  # noqa: N802
                    pass

                @dbus.service.method("org.bluez.Agent1", in_signature="os", out_signature="")
                def AuthorizeService(self, device, uuid):  # noqa: N802
                    log(f"agent AuthorizeService {uuid}")

                @dbus.service.method("org.bluez.Agent1", in_signature="o", out_signature="s")
                def RequestPinCode(self, device):  # noqa: N802
                    return "0000"

                @dbus.service.method("org.bluez.Agent1", in_signature="o", out_signature="u")
                def RequestPasskey(self, device):  # noqa: N802
                    return dbus.UInt32(0)

                @dbus.service.method("org.bluez.Agent1", in_signature="ouq", out_signature="")
                def DisplayPasskey(self, device, passkey, entered):  # noqa: N802
                    log(f"agent DisplayPasskey {passkey}")

                @dbus.service.method("org.bluez.Agent1", in_signature="os", out_signature="")
                def DisplayPinCode(self, device, pincode):  # noqa: N802
                    log(f"agent DisplayPinCode {pincode}")

                @dbus.service.method("org.bluez.Agent1", in_signature="ou", out_signature="")
                def RequestConfirmation(self, device, passkey):  # noqa: N802
                    log(f"agent Confirm passkey={passkey}")

                @dbus.service.method("org.bluez.Agent1", in_signature="o", out_signature="")
                def RequestAuthorization(self, device):  # noqa: N802
                    log("agent RequestAuthorization")

                @dbus.service.method("org.bluez.Agent1", in_signature="", out_signature="")
                def Cancel(self):  # noqa: N802
                    log("agent Cancel")

            self._agent = Agent(bus, path)
            mgr = dbus.Interface(
                bus.get_object("org.bluez", "/org/bluez"), "org.bluez.AgentManager1"
            )
            try:
                mgr.UnregisterAgent(path)
            except Exception:
                pass
            mgr.RegisterAgent(path, "NoInputNoOutput")
            mgr.RequestDefaultAgent(path)
            self._loop = GLib.MainLoop()
            self.ready.set()
            self._loop.run()
        except Exception as e:
            self.error = str(e)
            self.ready.set()

    def stop(self) -> None:
        if self._loop is not None:
            try:
                self._loop.quit()
            except Exception:
                pass


# ---------------------------------------------------------------------------
# BlueZ helpers
# ---------------------------------------------------------------------------
class BlueZ:
    def __init__(self) -> None:
        import dbus

        self.dbus = dbus
        self.bus = dbus.SystemBus()
        self.om = dbus.Interface(
            self.bus.get_object("org.bluez", "/"),
            "org.freedesktop.DBus.ObjectManager",
        )

    def objects(self):
        return self.om.GetManagedObjects()

    def adapter_path(self) -> str:
        for path, ifaces in self.objects().items():
            if "org.bluez.Adapter1" in ifaces:
                return str(path)
        raise RuntimeError("no adapter")

    def props(self, path: str):
        return self.dbus.Interface(
            self.bus.get_object("org.bluez", path), "org.freedesktop.DBus.Properties"
        )

    def prepare_adapter(self) -> tuple[str, str]:
        path = self.adapter_path()
        p = self.props(path)
        for k, v in (
            ("Powered", True),
            ("Pairable", True),
            ("Discoverable", True),
        ):
            try:
                p.Set("org.bluez.Adapter1", k, self.dbus.Boolean(v))
            except Exception as e:
                log(f"  set {k}: {e}")
        try:
            p.Set("org.bluez.Adapter1", "DiscoverableTimeout", self.dbus.UInt32(0))
        except Exception:
            pass
        a = dict(self.objects()[path]["org.bluez.Adapter1"])
        mac = str(a.get("Address", "")).upper()
        name = str(a.get("Alias") or a.get("Name") or "PC")
        log(f"adapter {name} {mac} pairable/discoverable")
        return mac, name

    def find_device(self, mac: str) -> Optional[str]:
        mac = mac.upper()
        for path, ifaces in self.objects().items():
            d = ifaces.get("org.bluez.Device1")
            if d and str(d.get("Address", "")).upper() == mac:
                return str(path)
        return None

    def device_dict(self, mac: str) -> dict:
        path = self.find_device(mac)
        if not path:
            return {}
        return dict(self.objects()[path].get("org.bluez.Device1", {}))

    def trust(self, mac: str) -> None:
        path = self.find_device(mac)
        if not path:
            return
        try:
            self.props(path).Set("org.bluez.Device1", "Trusted", self.dbus.Boolean(True))
            log(f"trusted {mac}")
        except Exception as e:
            log(f"trust: {e}")

    def connect_profile_hid(self, mac: str) -> bool:
        path = self.find_device(mac)
        if not path:
            log("ConnectProfile: device not in cache")
            return False
        dev = self.dbus.Interface(
            self.bus.get_object("org.bluez", path), "org.bluez.Device1"
        )
        # Ensure ACL first
        try:
            info = self.device_dict(mac)
            if not info.get("Connected"):
                log("BlueZ Device.Connect (ACL)…")
                dev.Connect()
                time.sleep(1.0)
        except Exception as e:
            log(f"ACL Connect: {e}")
        try:
            log(f"BlueZ ConnectProfile(HID) {mac}…")
            dev.ConnectProfile(HID_UUID)
            log("ConnectProfile returned")
            return True
        except Exception as e:
            log(f"ConnectProfile: {e}")
            # already connected is ok
            if "InProgress" in str(e) or "Already Connected" in str(e):
                return True
            return False

    def wait_paired(self, mac: str, timeout: float = 40) -> bool:
        deadline = time.time() + timeout
        while time.time() < deadline:
            d = self.device_dict(mac)
            if d.get("Paired") or d.get("Bonded"):
                return True
            time.sleep(0.5)
        return bool((self.device_dict(mac) or {}).get("Paired"))


# ---------------------------------------------------------------------------
# Phone / adb
# ---------------------------------------------------------------------------
class Phone:
    def __init__(self, serial: Optional[str] = None) -> None:
        self.serial = serial or os.environ.get("SERIAL") or self._auto()

    @staticmethod
    def _auto() -> Optional[str]:
        p = run(["adb", "devices"])
        lines = [ln for ln in (p.stdout or "").splitlines() if "\tdevice" in ln]
        for ln in lines:
            s = ln.split("\t")[0]
            if "TITAN" in s.upper():
                return s
        return lines[0].split("\t")[0] if lines else None

    def adb(self, *args: str, timeout: float = 30) -> subprocess.CompletedProcess:
        cmd = ["adb"]
        if self.serial:
            cmd += ["-s", self.serial]
        cmd += list(args)
        return run(cmd, timeout=timeout)

    def shell(self, cmdline: str, timeout: float = 30) -> str:
        p = self.adb("shell", cmdline, timeout=timeout)
        return (p.stdout or "") + (p.stderr or "")

    def su(self, cmdline: str, timeout: float = 30) -> str:
        # Pass command as a single argv element (no nested quoting hell).
        p = self.adb("shell", "su", "-c", cmdline, timeout=timeout)
        return (p.stdout or "") + (p.stderr or "")

    def ensure(self) -> None:
        if not self.serial:
            die("no adb device")
        if "device" not in (self.adb("get-state").stdout or ""):
            die(f"adb not ready: {self.serial}")
        log(f"adb {self.serial}")

    def bt_mac(self) -> Optional[str]:
        out = self.shell("settings get secure bluetooth_address 2>/dev/null")
        m = re.search(r"([0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5})", out or "")
        return m.group(1).upper() if m else None

    def lab(self, host_mac: str, host_name: str, action: str = "start") -> None:
        self.shell("input keyevent KEYCODE_WAKEUP 2>/dev/null; true")
        p = self.adb(
            "shell",
            "am",
            "start",
            "-n",
            ACTIVITY,
            "--ez",
            "lab_auto",
            "true",
            "--es",
            "lab_action",
            action,
            "--es",
            "host_mac",
            host_mac,
            "--es",
            "host_name",
            host_name,
            "--ei",
            "transport",
            str(TRANSPORT_BT),
            "--ez",
            "screen_off",
            "true",
        )
        log(f"lab_auto {action} → rc={p.returncode}")

    def stop(self) -> None:
        self.lab("00:00:00:00:00:00", "none", action="stop")
        self.su(
            "printf 0 > /data/local/tmp/titan2_usb_hid_session; "
            "printf 0 > /data/local/tmp/titan2_usb_hid_on; true"
        )

    def flags(self) -> dict[str, str]:
        out = {}
        for n in ("titan2_usb_hid_on", "titan2_usb_hid_session", "titan2_usb_hid_bt"):
            out[n] = self.shell(f"cat /data/local/tmp/{n} 2>/dev/null").strip()
        return out

    def wait_session(self, timeout: float = 15) -> bool:
        deadline = time.time() + timeout
        while time.time() < deadline:
            f = self.flags()
            if f.get("titan2_usb_hid_session") == "1":
                return True
            time.sleep(0.4)
        return self.flags().get("titan2_usb_hid_session") == "1"

    def inject_key(self, usage: int, press: bool) -> None:
        """Append one 4-byte HID record for FGS drain → BluetoothHidClient."""
        # toybox printf does not honor \\xHH; use xxd -r -p (available on Titan).
        hx = "".join(f"{b:02x}" for b in (0x01, 0x00, usage & 0xFF, 1 if press else 0))
        cmd = (
            f"echo {hx} | xxd -r -p >> /data/local/tmp/titan2_hid_hw.out; "
            f"echo {hx} | xxd -r -p >> /data/user/0/{PKG}/files/titan2_hid_hw.out; "
            f"chmod 666 /data/local/tmp/titan2_hid_hw.out "
            f"/data/user/0/{PKG}/files/titan2_hid_hw.out 2>/dev/null; true"
        )
        self.su(cmd)

    def inject_tap(self, usage: int) -> None:
        self.inject_key(usage, True)
        time.sleep(0.07)
        self.inject_key(usage, False)

    def logcat_bt(self) -> str:
        p = self.adb("logcat", "-d", "-t", "120", "-s", "TitanBtHid:I", "TitanUsbHid:I")
        return p.stdout or ""

    def wait_connected_log(self, timeout: float = 25) -> bool:
        deadline = time.time() + timeout
        while time.time() < deadline:
            lc = self.logcat_bt()
            if re.search(r"onConnectionStateChanged .* state=2\b", lc) or re.search(
                r"connected LabHost|connected .+", lc, re.I
            ):
                if "onConnectionStateChanged" in lc and "state=2" in lc:
                    return True
                if re.search(r"I TitanBtHid: connected ", lc):
                    return True
            time.sleep(0.6)
        return False


# ---------------------------------------------------------------------------
# Input discovery
# ---------------------------------------------------------------------------
def sysfs_input_names() -> dict[str, str]:
    """eventN → name via sysfs (no /dev perms needed)."""
    out = {}
    base = "/sys/class/input"
    if not os.path.isdir(base):
        return out
    for ent in os.listdir(base):
        if not ent.startswith("event"):
            continue
        npath = os.path.join(base, ent, "device", "name")
        try:
            with open(npath) as f:
                out[ent] = f.read().strip()
        except OSError:
            pass
    return out


def find_titan_keyboard(baseline: Optional[set[str]] = None) -> Optional[tuple[str, str]]:
    names = sysfs_input_names()
    for ev, name in names.items():
        nl = name.lower()
        if "titan" in nl and "key" in nl:
            return f"/dev/input/{ev}", name
    for ev, name in names.items():
        if baseline is not None and ev in baseline:
            continue
        nl = name.lower()
        if "titan" in nl:
            return f"/dev/input/{ev}", name
    return None


def wait_titan_input(timeout: float = 30, baseline: Optional[set[str]] = None):
    deadline = time.time() + timeout
    while time.time() < deadline:
        hit = find_titan_keyboard(baseline)
        if hit:
            return hit
        time.sleep(0.4)
    return find_titan_keyboard(baseline)


def try_evdev_keys(path: str, usages: list[int], phone: Phone) -> tuple[bool, str]:
    try:
        from evdev import InputDevice, ecodes
    except ImportError:
        return False, "evdev not installed"
    try:
        dev = InputDevice(path)
    except PermissionError:
        return False, f"EACCES on {path} (add user to group input)"
    except OSError as e:
        return False, str(e)

    expect = [HID_TO_EV[u] for u in usages if u in HID_TO_EV]
    seen: list[int] = []
    try:
        for u in usages:
            phone.inject_tap(u)
            end = time.time() + 1.5
            while time.time() < end:
                r, _, _ = select.select([dev.fd], [], [], 0.15)
                if not r:
                    continue
                for event in dev.read():
                    if event.type == ecodes.EV_KEY and event.value == 1:
                        seen.append(event.code)
                        log(f"  KEY {ecodes.KEY.get(event.code, event.code)}")
    finally:
        dev.close()

    ok = bool(seen) and (not expect or any(c in expect for c in seen))
    return ok, f"seen={seen} expect={expect}"


# ---------------------------------------------------------------------------
# Result
# ---------------------------------------------------------------------------
@dataclass
class Result:
    steps: list[tuple[str, bool, str]] = field(default_factory=list)

    def add(self, name: str, ok: bool, detail: str = "") -> None:
        self.steps.append((name, ok, detail))
        log(f"{'PASS' if ok else 'FAIL'}  {name}" + (f" — {detail}" if detail else ""))

    def ok(self) -> bool:
        return all(s[1] for s in self.steps)

    def summary(self) -> str:
        lines = ["", "=== bt_host_test summary ==="]
        for n, ok, d in self.steps:
            lines.append(f"  [{'OK' if ok else '!!'}] {n}" + (f" ({d})" if d else ""))
        lines.append("RESULT: " + ("PASS" if self.ok() else "FAIL"))
        return "\n".join(lines)


def parse_seq(s: str) -> list[int]:
    out = []
    for tok in s.split(","):
        tok = tok.strip()
        if not tok:
            continue
        if tok.lower().startswith("0x"):
            out.append(int(tok, 16))
        elif len(tok) == 1 and tok.isalpha():
            out.append(0x04 + (ord(tok.upper()) - ord("A")))
        else:
            out.append(int(tok))
    return out or [0x04, 0x05, 0x04]


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="PC HID-host automation for Titan USB HID")
    ap.add_argument("--serial", default=None)
    ap.add_argument("--phone-mac", default=DEFAULT_PHONE_MAC)
    ap.add_argument("--timeout", type=float, default=55)
    ap.add_argument("--no-inject", action="store_true")
    ap.add_argument("--cleanup", action="store_true")
    ap.add_argument("--keep-session", action="store_true")
    ap.add_argument("--seq", default="A,B,A")
    args = ap.parse_args(argv)

    phone = Phone(args.serial)
    phone.ensure()
    if args.cleanup:
        phone.stop()
        log("stopped")
        return 0

    agent = AgentThread()
    agent.start()
    if not agent.ready.wait(5):
        die("BlueZ agent start timeout")
    if agent.error:
        log(f"agent warning: {agent.error}")

    try:
        bz = BlueZ()
    except Exception as e:
        die(f"BlueZ: {e}")

    host_mac, host_name = bz.prepare_adapter()
    phone_mac = (phone.bt_mac() or args.phone_mac).upper()
    log(f"phone {phone_mac}  host {host_name} {host_mac}")

    res = Result()
    res.add("host_adapter", bool(host_mac), f"{host_name} {host_mac}")

    baseline_ev = set(sysfs_input_names().keys())

    # Start phone BT HID session targeting this PC
    phone.lab(host_mac, host_name, action="start")
    sess = phone.wait_session(12)
    flags = phone.flags()
    res.add("phone_session", sess, str(flags))
    res.add("phone_bt_flag", flags.get("titan2_usb_hid_bt") == "1", flags.get("titan2_usb_hid_bt", ""))

    # Give HID registerApp a moment
    time.sleep(2.0)
    bz.trust(phone_mac)

    # Host-driven HID profile connect (critical for BlueZ)
    cp_ok = bz.connect_profile_hid(phone_mac)
    # Retry once after more register time
    if not cp_ok:
        time.sleep(2)
        phone.lab(host_mac, host_name, action="start")
        time.sleep(2)
        cp_ok = bz.connect_profile_hid(phone_mac)
    res.add("connect_profile_hid", cp_ok)

    # Wait for Titan keyboard node
    hit = wait_titan_input(timeout=min(35, args.timeout), baseline=baseline_ev)
    if hit:
        path, name = hit
        res.add("hid_input_node", True, f"{name} @ {path}")
    else:
        res.add("hid_input_node", False, f"names={sysfs_input_names()}")
        path, name = None, None

    # Phone-side connected (required for soft inject / sendReport)
    connected_phone = phone.wait_connected_log(18)
    lc = phone.logcat_bt()
    res.add(
        "phone_hid_connected",
        connected_phone,
        "need onConnectionStateChanged state=2 or status connected",
    )

    for line in lc.splitlines()[-12:]:
        if "Titan" in line:
            log(f"  {line[-160:]}")

    if args.no_inject:
        if not args.keep_session:
            phone.stop()
        print(res.summary())
        agent.stop()
        return 0 if res.ok() else 2

    usages = parse_seq(args.seq)

    # Prove inject writes bytes (or FGS drains them)
    before = phone.su(
        f"wc -c < /data/user/0/{PKG}/files/titan2_hid_hw.out 2>/dev/null || echo 0"
    ).strip()
    phone.inject_tap(usages[0])
    time.sleep(0.5)
    after = phone.su(
        f"wc -c < /data/user/0/{PKG}/files/titan2_hid_hw.out 2>/dev/null || echo 0"
    ).strip()
    lc_inj = phone.logcat_bt()
    send_ok = "sendReport kbd ok=true" in lc_inj
    try:
        grew = int(re.sub(r"\D", "", after) or "0") > int(re.sub(r"\D", "", before) or "0")
    except ValueError:
        grew = False
    res.add(
        "inject_path",
        send_ok or grew,
        f"before={before!r} after={after!r} sendReport={send_ok}",
    )

    if path:
        ok_ev, detail = try_evdev_keys(path, usages, phone)
        if ok_ev:
            res.add("evdev_keys", True, detail)
        else:
            log(f"evdev: {detail}")
            if send_ok or "sendReport kbd ok=true" in phone.logcat_bt():
                res.add("sendReport_logcat", True, "evdev blocked; reports OK")
            else:
                for u in usages:
                    phone.inject_tap(u)
                    time.sleep(0.12)
                time.sleep(0.6)
                send_ok2 = "sendReport kbd ok=true" in phone.logcat_bt()
                res.add("sendReport_logcat", send_ok2, detail if not send_ok2 else "ok")
    else:
        for u in usages:
            phone.inject_tap(u)
            time.sleep(0.12)
        time.sleep(0.6)
        res.add("sendReport_logcat", "sendReport kbd ok=true" in phone.logcat_bt())

    print(res.summary())
    if not args.keep_session:
        phone.stop()
        log("session stopped")
    agent.stop()
    return 0 if res.ok() else 2


if __name__ == "__main__":
    sys.exit(main())
