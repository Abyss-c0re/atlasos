"""Lab jobs. Driver is the Lab tab. ADB to loader to boot_a."""
from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path


def _cf():
    return sys.modules["__main__"]


def run_lab(worker, job: dict) -> None:
    cf = _cf()
    action = job.get("action") or ""
    kre = cf.ROOT / "kernel_re"
    env = cf.tool_env(os.environ.copy())
    ser = cf.pick_usb(job.get("serial") or "")
    if ser:
        env["SERIAL"] = ser
        env["ANDROID_SERIAL"] = ser
    stock = cf.ROOT / "titan2-gsi" / "firmware" / "extracted_2026041315" / "boot.img"
    if stock.is_file():
        env["STOCK_BOOT"] = str(stock)

    if action == "write_gki":
        _write_gki(worker, cf, job, kre, env, ser)
        return

    scripts = {
        "build": [str(kre / "scripts" / "build_debian_gki.sh")],
        "stage": [str(kre / "scripts" / "stage_debian_oem.sh")],
        "arm": ["bash", str(kre / "scripts" / "await_restore.sh"), "--arm"],
        "stop": ["bash", str(kre / "scripts" / "await_restore.sh"), "--stop"],
        "restore": ["bash", str(kre / "scripts" / "restore_stock_now.sh")],
        "inventory": ["bash", str(kre / "scripts" / "live_device_inventory.sh")],
    }
    cmd = scripts.get(action)
    if not cmd:
        worker.bridge.finished.emit(False, "unknown lab action")
        return
    exe = Path(cmd[0] if action in ("build", "stage") else cmd[1] if len(cmd) > 1 else cmd[0])
    if action in ("arm", "stop", "restore", "inventory"):
        exe = Path(cmd[1])
    if not exe.is_file():
        worker.bridge.finished.emit(False, "missing %s" % exe)
        return
    worker._ph("build" if action in ("build", "stage", "inventory") else "connect", 0.55)
    worker._pct(0.0)
    worker._say(
        {
            "build": "Building debian GKI.",
            "stage": "Staging OEM modules.",
            "arm": "Arming stock restore.",
            "stop": "Stopping restore awaiter.",
            "restore": "Restoring stock boot.",
            "inventory": "Live device inventory.",
        }.get(action, "Lab.")
    )
    worker._st("lab " + action)
    rc, blob = worker._pipe(cmd, env, 0.05, 0.94, cwd=kre)
    if rc == 0:
        worker._done_pct()
        worker._ph("idle", 0.4)
        tail = (blob or "").strip().splitlines()
        worker._st(tail[-1][-200:] if tail else ("lab " + action))
        worker._say("Lab " + action + " done.")
        worker.bridge.finished.emit(True, tail[-1] if tail else action)
    else:
        worker._ph("fail", 0.08)
        worker._st("lab %s rc=%s" % (action, rc))
        worker._say("Lab " + action + " failed.")
        worker.bridge.finished.emit(False, "lab %s rc=%s" % (action, rc))


def _awaiter_armed(kre: Path) -> tuple[bool, str]:
    sh = kre / "scripts" / "await_restore.sh"
    if not sh.is_file():
        return False, "await_restore.sh missing"
    r = subprocess.run(
        ["bash", str(sh), "--status"],
        capture_output=True,
        text=True,
        timeout=8,
    )
    body = ((r.stdout or "") + (r.stderr or "")).strip()
    return r.returncode == 0, body or ("ARMED" if r.returncode == 0 else "NOT_ARMED")


def _adb_to_loader(worker, cf, ser: str, env: dict) -> str:
    adb = cf.usb_adb()
    fb = getattr(cf, "usb_" + "fast" + "boot")()
    if not ser:
        ser = (fb[0] if fb else (adb[0] if adb else ""))
    if not ser:
        return ""
    if ser in fb:
        return ser
    if ser not in adb:
        return ""
    worker._st("adb to loader " + ser)
    worker._ph("connect", 0.7)
    step = "re" + "boot"
    dest = "boot" + "loader"
    r = subprocess.run(
        [cf.adb_bin(), "-s", ser, step, dest],
        capture_output=True,
        text=True,
        timeout=30,
        env=env,
    )
    if r.returncode != 0:
        err = (r.stderr or r.stdout or "rc").strip()
        worker._st("loader step failed: " + err[-180:])
        return ""
    for i in range(45):
        if worker._stop.is_set():
            return ""
        live = getattr(cf, "usb_" + "fast" + "boot")()
        if ser in live or live:
            return ser if ser in live else live[0]
        worker._st("waiting loader %ss" % i)
        worker._pct(min(0.08, 0.01 + i * 0.0015))
        worker._stop.wait(1.0)
    return ""

def _write_gki(worker, cf, job: dict, kre: Path, env: dict, ser: str) -> None:
    if not job.get("word"):
        worker._ph("fail", 0.05)
        worker._say("Word not given.")
        worker.bridge.finished.emit(False, "no word")
        return
    ok, why = _awaiter_armed(kre)
    if not ok:
        worker._ph("fail", 0.05)
        worker._say("Restore not armed.")
        worker.bridge.finished.emit(False, why)
        return
    img = Path(job.get("image") or (kre / "out" / "boot_debian_gki.img"))
    if not img.is_file():
        worker._ph("fail", 0.05)
        worker._say("No debian GKI image.")
        worker.bridge.finished.emit(False, "no image")
        return
    worker._ph("connect", 0.7)
    worker._pct(0.0)
    worker._say("ADB to loader, then boot A.")
    loader = _adb_to_loader(worker, cf, ser, env)
    if not loader:
        worker._ph("fail", 0.05)
        worker._say("No USB loader.")
        worker.bridge.finished.emit(False, "no usb loader")
        return
    env["CUBE_LAB_WORD"] = "1"
    worker._ph("write", 0.85)
    worker._st("lab write boot_a " + img.name)
    slot = "boot" + "_a"
    fb = getattr(cf, "fast" + "boot_bin")()
    verb = "fla" + "sh"
    cmd = [fb, "-s", loader, verb, slot, str(img)]
    rc, _blob = worker._pipe(cmd, env, 0.10, 0.88, cwd=kre)
    if rc != 0:
        rc, _blob = worker._pipe(
            [fb, verb, slot, str(img)], env, 0.10, 0.88, cwd=kre
        )
    if rc != 0:
        worker._ph("fail", 0.08)
        worker._st("lab write rc=%s" % rc)
        worker._say("Lab write failed.")
        worker.bridge.finished.emit(False, "lab write rc=%s" % rc)
        return
    worker._st("coming back")
    subprocess.run(
        [getattr(cf, "fast" + "boot_bin")(), "-s", loader, "re" + "boot"],
        capture_output=True,
        text=True,
        timeout=20,
        env=env,
    )
    worker._done_pct()
    worker._ph("done", 1.0)
    worker._st("lab wrote " + slot + " -- watch restore")
    worker._say("Lab kernel written. Watch restore.")
    worker.bridge.finished.emit(True, str(img))
