#!/usr/bin/env python3
"""Cube Flasher — pin/GSI manager + kitchen. Live percent only."""
from __future__ import annotations

import json
import os
import pty
import re
import select
import subprocess
import sys
import threading
import time
from datetime import datetime
from pathlib import Path

from PyQt5.QtCore import Qt, QTimer, pyqtSignal, QObject
from PyQt5.QtGui import QColor, QFont, QPalette
from PyQt5.QtWidgets import (
    QAbstractItemView,
    QApplication,
    QCheckBox,
    QComboBox,
    QGridLayout,
    QHBoxLayout,
    QLabel,
    QListWidget,
    QListWidgetItem,
    QMainWindow,
    QMessageBox,
    QPlainTextEdit,
    QProgressBar,
    QPushButton,
    QScrollArea,
    QStyleFactory,
    QTabWidget,
    QVBoxLayout,
    QWidget,
)

from cube_widget import CrimsonCube
from titan_diag import collect as diag_collect, detect as diag_detect, format_diag
from titan_issues import nanobot_classify, post_finding, existing_fingerprints, github_token, origin_repo
from titan_fix import develop_and_ship, nanobot_ready
from titan_reports import pull_reports, fill_selected_logs, reports_as_findings
from titan_pair import pull_pair, format_pair
from titan_rom import (
    LEDGER_NAME,
    PROP_KEYS,
    STAMP_DEV,
    format_rom_report,
    format_rom_summary,
    git_head,
    git_log_range,
    last_flash_for,
    newer_pins,
    parse_prop_blob,
    record_flash,
)

HERE = Path(__file__).resolve().parent
TOOLS = HERE / "platform-tools"
ROOT = Path("/home/voldemar/Dev/device-workshop/products/titanus2")
OUT = ROOT / "out"
GSI_DIR = ROOT / "gsi"
KITCHEN = ROOT / "scripts" / "kitchen.py"
WRITER = ROOT / "scripts" / ("flash" + "_titan2_eea.sh")
ETA_FILE = OUT / "cube_flasher_eta.json"


def _atlasos() -> Path:
    for cand in (ROOT / "product", ROOT.parent / "atlasos"):
        if (cand / "scripts" / "build.sh").is_file():
            return cand
    return ROOT.parent / "atlasos"


ATLASOS = _atlasos()

BRACKET_PCT = re.compile(r"\[\s*(\d{1,3})\s*%")
FASTBOOT_PCT = re.compile(r"(?:Sending|Writing).{0,80}?(?<![0-9])(\d{1,3})\s*%")
CR_PCT = re.compile(r"^\s*(\d{1,3})\s*%\s*$")
SPARSE = re.compile(r"sparse\s+'[^']+'\s+(\d+)\s*/\s*(\d+)", re.I)
COOK_MARKERS = (
    (re.compile(r"kitchen cook", re.I), 0.03),
    (re.compile(r"rom_variant build", re.I), 0.05),
    (re.compile(r"Extracting stock", re.I), 0.08),
    (re.compile(r"simg2img stock", re.I), 0.12),
    (re.compile(r"lpunpack", re.I), 0.18),
    (re.compile(r"Preparing GSI", re.I), 0.26),
    (re.compile(r"Patching system", re.I), 0.34),
    (re.compile(r"Injecting", re.I), 0.44),
    (re.compile(r"lpmake|Packing super|img2simg|Building super", re.I), 0.58),
    (re.compile(r"=== built ", re.I), 0.90),
    (re.compile(r'"ok"\s*:\s*true'), 0.94),
    (re.compile(r"Flashing super", re.I), 0.18),
    (re.compile(r"dirty flash: skipping kernel", re.I), 0.14),
    (re.compile(r"KEEP_DATA", re.I), 0.92),
    (re.compile(r"^Done\. Flashed"), 0.96),
    (re.compile(r"GSI systemimage", re.I), 0.12),
    (re.compile(r"ninja: no work", re.I), 0.72),
    (re.compile(r"target .*systemimage|build_gsi", re.I), 0.55),
    (re.compile(r"exported pin", re.I), 0.92),
    (re.compile(r"gsi-only done", re.I), 0.96),
)

PRESET_FALLBACK = [
    "lab_rootless",
    "lab",
    "ship",
    "lab_non_eea",
    "ship_non_eea",
    "lab_rootless_non_eea",
    "lab_rootless_restless",
    "ship_restless",
]
FEATURE_FALLBACK = [
    ("with_atlas", "Atlas Debian app", True),
    ("with_atlas_lp", "Debian plane (atlas_linux LP)", True),
    ("with_openwrt_lp", "OpenWrt plane (atlas_openwrt LP)", True),
    ("with_cube_icons", "Cube square icons", True),
    ("with_input", "Pad / keyboard", True),
    ("with_controls", "Titan Controls", True),
    ("with_usb_hid", "USB / BT HID", True),
    ("with_display", "Display dens", True),
    ("with_ims_treble", "IMS / VoLTE", True),
    ("with_openeuicc", "OpenEUICC eSIM", True),
    ("with_microg", "microG", True),
    ("with_stock_fm_ir", "FM radio (OSS TitanFm)", True),
    ("with_usb_audio", "USB audio (OEM analog + host)", True),
    ("with_stock_camera", "Prefer stock camera", True),
    ("with_square_chrome", "Square chrome RROs (lab)", False),
    ("with_nanobot", "Nanobot agent (lab)", False),
]
ROOT_ENGINES = ["none", "magisk_release", "magisk_source", "kernelsu_source"]
GSI_FLAVORS = ["vanilla", "microg", "gapps"]
SORTS = ("newest", "oldest", "name", "size")
EXPECT_SUPER = 4.4 * 1024 * 1024 * 1024
EXPECT_GSI = 2.75 * 1024 * 1024 * 1024
ETA_DEFAULTS = {"cook_s": 720, "flash_s": 180, "gsi_s": 2700, "gsi_fresh_s": 7200}
GSI_LATEST = "__latest__"


def kitchen_presets() -> list[str]:
    try:
        if str(ROOT) not in sys.path:
            sys.path.insert(0, str(ROOT))
        from kitchen.api import list_presets

        names = sorted(list_presets().keys())
        return names or list(PRESET_FALLBACK)
    except Exception:
        return list(PRESET_FALLBACK)


def kitchen_features() -> list[tuple[str, str, bool]]:
    try:
        if str(ROOT) not in sys.path:
            sys.path.insert(0, str(ROOT))
        from kitchen.features import FEATURES

        return [(f.key, f.label, bool(f.ship_default)) for f in FEATURES]
    except Exception:
        return list(FEATURE_FALLBACK)


def speak(text: str) -> None:
    try:
        subprocess.Popen(
            ["espeak-ng", "-v", "en-us", "-s", "138", "-p", "28", text],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
    except Exception:
        pass


def _sort_imgs(imgs: list[Path], how: str) -> list[Path]:
    if how == "name":
        imgs.sort(key=lambda p: p.name.lower())
    elif how == "size":
        imgs.sort(key=lambda p: p.stat().st_size, reverse=True)
    elif how == "oldest":
        imgs.sort(key=lambda p: p.stat().st_mtime)
    else:
        imgs.sort(key=lambda p: p.stat().st_mtime, reverse=True)
    return imgs


def list_supers(how: str = "date ↓") -> list[Path]:
    if not OUT.is_dir():
        return []
    imgs = [p for p in OUT.glob("*super*.img") if p.is_file()]
    return _sort_imgs(imgs, how)


def list_gsi(how: str = "date ↓") -> list[Path]:
    """Unique inodes; prefer LineageOS-23.2-* names. Skip aliases/symlinks."""
    dirs = []
    for d in (GSI_DIR, ATLASOS / "gsi"):
        if d.is_dir() and d not in dirs:
            dirs.append(d)
    by_ino: dict[int, Path] = {}
    for d in dirs:
        for p in d.glob("*.img"):
            if not p.is_file() or p.is_symlink():
                continue
            if "misterztr-src" in p.name:
                continue
            try:
                ino = p.stat().st_ino
            except OSError:
                continue
            cur = by_ino.get(ino)
            if cur is None or p.name.startswith("LineageOS-23.2"):
                by_ino[ino] = p
    return _sort_imgs(list(by_ino.values()), how)


def latest_gsi() -> Path | None:
    imgs = list_gsi("newest")
    return imgs[0] if imgs else None


def resolve_gsi(sel: str) -> str:
    """Map combo value to a real image. latest = newest on disk."""
    if sel == GSI_LATEST:
        p = latest_gsi()
        return str(p) if p else ""
    return sel or ""


def adb_bin() -> str:
    p = TOOLS / "adb"
    return str(p) if p.is_file() else "adb"


def fastboot_bin() -> str:
    p = TOOLS / "fastboot"
    return str(p) if p.is_file() else "fastboot"


def tool_env(base: dict | None = None) -> dict:
    env = dict(base or os.environ)
    env["PATH"] = str(TOOLS) + os.pathsep + env.get("PATH", "")
    return env


def is_usb_serial(serial: str) -> bool:
    """USB serials have no host:port. Never treat tcpip as USB."""
    if not serial or ":" in serial:
        return False
    if serial.startswith("emulator-"):
        return False
    return True


def usb_adb() -> list[str]:
    """Live USB adb only. No hardcoded serials."""
    try:
        out = subprocess.check_output(
            [adb_bin(), "devices", "-l"],
            text=True,
            timeout=5,
            env=tool_env(),
        )
    except Exception:
        return []
    ser = []
    for line in out.splitlines()[1:]:
        p = line.split()
        if len(p) < 2 or p[1] != "device":
            continue
        if "usb:" not in line:
            continue
        if is_usb_serial(p[0]):
            ser.append(p[0])
    return ser


def usb_fastboot() -> list[str]:
    """Live USB fastboot only. No hardcoded serials."""
    try:
        out = subprocess.check_output(
            [fastboot_bin(), "devices", "-l"],
            text=True,
            timeout=5,
            env=tool_env(),
        )
    except Exception:
        return []
    ser = []
    for line in out.splitlines():
        p = line.split()
        if len(p) < 2:
            continue
        if not is_usb_serial(p[0]):
            continue
        if "usb:" in line or p[1] in ("fastboot", "bootloader"):
            ser.append(p[0])
    return ser


def usb_targets() -> list[str]:
    seen: list[str] = []
    for s in usb_adb() + usb_fastboot():
        if s not in seen:
            seen.append(s)
    return seen


def pick_usb(prefer: str = "") -> str:
    live = usb_targets()
    if prefer and prefer in live:
        return prefer
    return live[0] if live else ""


def adb_shell(serial: str, script: str, timeout: int = 12) -> str:
    if not serial:
        return ""
    try:
        out = subprocess.check_output(
            [adb_bin(), "-s", serial, "shell", script],
            text=True,
            timeout=timeout,
            env=tool_env(),
        )
        return out.replace("\r", "")
    except Exception:
        return ""


def ledger_path() -> Path:
    return OUT / LEDGER_NAME


def receipt_dir() -> Path:
    return OUT / "rd_lead" / "receipts"


def probe_usb_rom(serial: str) -> dict:
    """Live USB adb getprop. Empty if no device."""
    if not serial:
        return {}
    script = ";".join("echo %s=$(getprop %s)" % (k, k) for k in PROP_KEYS)
    script += "; echo STAMP=$(cat %s 2>/dev/null || cat %s 2>/dev/null)" % (
        STAMP_DEV,
        "/data/adb/titan2_last_flash.json",
    )
    try:
        out = subprocess.check_output(
            [adb_bin(), "-s", serial, "shell", script],
            text=True,
            timeout=6,
            env=tool_env(),
        )
    except Exception:
        return {}
    return parse_prop_blob(out.replace("\r", ""))


def push_flash_stamp(serial: str, rec: dict) -> None:
    if not serial or not rec:
        return
    blob = json.dumps(
        {k: rec.get(k, "") for k in ("ts", "pin", "atlasos", "titanus2", "wipe")},
        indent=2,
    ) + "\n"
    tmp = OUT / ".titan2_last_flash.json"
    try:
        tmp.write_text(blob)
        subprocess.run(
            [adb_bin(), "-s", serial, "push", str(tmp), STAMP_DEV],
            timeout=6,
            env=tool_env(),
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
    except Exception:
        pass


def rom_changelog(flash: dict) -> list[str]:
    sha = (flash or {}).get("atlasos") or ""
    ts = (flash or {}).get("ts") or ""
    return git_log_range(ATLASOS, since_sha=sha, since_ts="" if sha else ts)


def fmt_img(p: Path) -> str:
    st = p.stat()
    mb = st.st_size / (1024 * 1024)
    ts = datetime.fromtimestamp(st.st_mtime).strftime("%Y-%m-%d %H:%M")
    return f"{p.name}   {mb:.0f}M   {ts}"


def fmt_secs(sec: float) -> str:
    sec = max(0, int(sec))
    if sec < 60:
        return "%ss" % sec
    m, s = divmod(sec, 60)
    if m < 60:
        return "%dm%02ds" % (m, s) if s else "%dm" % m
    h, m = divmod(m, 60)
    return "%dh%02dm" % (h, m)


def load_eta() -> dict:
    data = dict(ETA_DEFAULTS)
    if ETA_FILE.is_file():
        try:
            got = json.loads(ETA_FILE.read_text())
            for k in ETA_DEFAULTS:
                if k in got and isinstance(got[k], (int, float)) and got[k] > 10:
                    data[k] = float(got[k])
        except Exception:
            pass
    return data


def save_eta(kind: str, seconds: float) -> None:
    if seconds < 15:
        return
    data = load_eta()
    key = {"cook": "cook_s", "write": "flash_s", "gsi": "gsi_s", "gsi_fresh": "gsi_fresh_s"}.get(kind)
    if not key:
        return
    prev = data[key]
    data[key] = prev * 0.6 + float(seconds) * 0.4
    try:
        OUT.mkdir(parents=True, exist_ok=True)
        ETA_FILE.write_text(json.dumps(data, indent=2) + "\n")
    except Exception:
        pass


def _eta_learned(data: dict, key: str) -> bool:
    cold = float(ETA_DEFAULTS.get(key, 0))
    got = float(data.get(key, 0) or 0)
    return abs(got - cold) > 5


def estimate_cook(features: dict, root: str, also_gsi: bool = False) -> int:
    data = load_eta()
    sec = data["cook_s"]
    # Feature add-ons only on cold defaults. Learned cook_s already includes them.
    if not _eta_learned(data, "cook_s"):
        if features.get("with_nanobot"):
            sec += 120
        if features.get("with_square_chrome"):
            sec += 60
        if features.get("with_atlas_lp"):
            sec += 480
        if features.get("with_openwrt_lp"):
            sec += 240
        if features.get("with_stock_fm_ir"):
            sec += 90
        if (root or "none") != "none":
            sec += 180
    if also_gsi:
        sec += data["gsi_s"]
    return int(sec)


def normalize_planes(feats: dict) -> dict:
    """Debian LP without OpenWrt is product-lock heresy."""
    out = dict(feats)
    if out.get("with_atlas_lp"):
        out["with_atlas"] = True
        out["with_openwrt_lp"] = True
    return out


def estimate_flash(path: str = "", keep_data: bool = True) -> int:
    data = load_eta()
    sec = data["flash_s"]
    if path:
        try:
            size = Path(path).stat().st_size
            sec = 45 + int(size / EXPECT_SUPER * 120)
        except OSError:
            pass
    if not keep_data:
        sec += 40
    return int(sec)


def estimate_gsi(flavor: str, fresh: bool = False) -> int:
    data = load_eta()
    if fresh:
        sec = data.get("gsi_fresh_s") or (data["gsi_s"] * 2.2)
        if not _eta_learned(data, "gsi_fresh_s") and sec < 3600:
            sec = 3600
    else:
        sec = data["gsi_s"]
    if not (fresh and _eta_learned(data, "gsi_fresh_s")) and not (
        (not fresh) and _eta_learned(data, "gsi_s")
    ):
        if flavor == "microg":
            sec += 180
        elif flavor == "gapps":
            sec += 420
    return int(sec)


def live_unit(line: str) -> float | None:
    """Map one live line to 0.00..0.99. Never 1.0. Ignore leftover 100%."""
    m = SPARSE.search(line)
    if m:
        n, d = int(m.group(1)), int(m.group(2))
        if d > 0 and 0 < n <= d:
            return min(0.99, n / float(d))
    m = BRACKET_PCT.search(line)
    if m:
        raw = int(m.group(1))
        if 0 <= raw <= 99:
            return raw / 100.0
        return None
    m = FASTBOOT_PCT.search(line) or CR_PCT.match(line.strip())
    if m:
        raw = int(m.group(1))
        if 0 <= raw <= 99:
            return raw / 100.0
    return None


def marker_unit(line: str) -> float | None:
    for rx, frac in COOK_MARKERS:
        if rx.search(line):
            return frac
    return None


def extract_super(blob: str) -> str:
    i = blob.rfind("{")
    if i < 0:
        return ""
    try:
        obj = json.loads(blob[i:])
    except Exception:
        return ""
    p = obj.get("super_img") or ""
    if p and Path(p).is_file():
        return str(Path(p))
    return ""


def extract_gsi(blob: str) -> str:
    m = re.search(r"exported pin:\s+(\S+\.img)", blob)
    if m and Path(m.group(1)).is_file():
        return m.group(1)
    ptr = ATLASOS / "out" / "misterztr_exported_gsi.path"
    if ptr.is_file():
        p = ptr.read_text(errors="replace").strip()
        if p and Path(p).is_file():
            return p
    gs = list_gsi()
    return str(gs[0]) if gs else ""


def growing_bytes(since: float) -> int:
    best = 0
    paths: list[Path] = []
    if OUT.is_dir():
        paths += list(OUT.glob("*super*.img"))
    build = ROOT / "build"
    if build.is_dir():
        paths += list(build.glob("super*.img"))
    for d in (GSI_DIR, ATLASOS / "gsi"):
        if d.is_dir():
            paths += list(d.glob("*.img"))
    lineage = ATLASOS / ".links" / "lineage" / "out" / "target" / "product" / "tdgsi_arm64_ab" / "system.img"
    if lineage.is_file():
        paths.append(lineage)
    for p in paths:
        try:
            st = p.stat()
        except OSError:
            continue
        if st.st_mtime >= since - 2 and st.st_size > best:
            best = st.st_size
    return best


def mirror_gsi(src: str) -> str:
    """Hard-link an AtlasOS GSI export into the workshop gsi/ dir."""
    p = Path(src)
    if not p.is_file():
        return src
    GSI_DIR.mkdir(parents=True, exist_ok=True)
    dest = GSI_DIR / p.name
    if dest.resolve() == p.resolve():
        return str(dest)
    try:
        if dest.exists():
            dest.unlink()
        os.link(p, dest)
    except OSError:
        import shutil

        shutil.copy2(p, dest)
    alias = GSI_DIR / ("LineageOS-misterztr-src-" + p.name.replace("LineageOS-23.2-", ""))
    try:
        if alias.exists() or alias.is_symlink():
            alias.unlink()
        os.link(dest, alias)
    except OSError:
        pass
    latest = GSI_DIR / "LineageOS-misterztr-src-latest-VANILLA-EXT4-GSI.img"
    try:
        if latest.exists() or latest.is_symlink():
            latest.unlink()
        latest.symlink_to(dest.name)
    except OSError:
        pass
    return str(dest)


class Bridge(QObject):
    status = pyqtSignal(str)
    phase = pyqtSignal(str, float)
    progress = pyqtSignal(float)
    spoken = pyqtSignal(str)
    built = pyqtSignal(str)
    gsi_built = pyqtSignal(str)
    finished = pyqtSignal(bool, str)
    diag = pyqtSignal(str)


class Worker(threading.Thread):
    def __init__(self, bridge: Bridge):
        super().__init__(daemon=True)
        self.bridge = bridge
        self._stop = threading.Event()
        self._job = None
        self._lock = threading.Lock()
        self._proc = None

    def submit(self, job: dict) -> None:
        with self._lock:
            self._job = job

    def stop(self) -> None:
        self._stop.set()
        proc = self._proc
        if proc and proc.poll() is None:
            try:
                proc.terminate()
            except Exception:
                pass

    def _st(self, m: str) -> None:
        self.bridge.status.emit(m)

    def _ph(self, n: str, g: float) -> None:
        self.bridge.phase.emit(n, g)

    def _pct(self, f: float) -> None:
        if f < 0:
            f = 0.0
        if f > 0.99:
            f = 0.99
        self.bridge.progress.emit(f)

    def _done_pct(self) -> None:
        self.bridge.progress.emit(1.0)

    def _say(self, t: str) -> None:
        self.bridge.spoken.emit(t)
        speak(t)

    def run(self) -> None:
        while not self._stop.is_set():
            with self._lock:
                job = self._job
                self._job = None
            if job is None:
                self._stop.wait(0.15)
                continue
            kind = job.get("kind")
            t0 = time.time()
            try:
                if kind == "cook":
                    self._cook(job, emit_done=True)
                    save_eta("cook", time.time() - t0)
                elif kind == "write":
                    self._write(job)
                    save_eta("write", time.time() - t0)
                elif kind == "cook_write":
                    img = self._cook(job, emit_done=False)
                    if not img:
                        continue
                    save_eta("cook", time.time() - t0)
                    if not usb_targets():
                        self._st("cooked — connect Titan, then FLASH SELECTED")
                        self._say("Pin cooked. Connect the Titan to flash.")
                        self.bridge.finished.emit(True, img)
                        continue
                    t1 = time.time()
                    job = dict(job)
                    job["image"] = img
                    self._write(job)
                    save_eta("write", time.time() - t1)
                elif kind == "gsi":
                    self._gsi(job)
                    save_eta("gsi_fresh" if job.get("fresh") else "gsi", time.time() - t0)
                elif kind == "pull":
                    self._pull()
            except Exception as e:
                self._ph("fail", 0.05)
                self._st("error: %s" % e)
                self._say("Failed.")
                self.bridge.finished.emit(False, str(e))

    def _pipe(self, cmd: list[str], env: dict, lo: float, hi: float, cwd: Path | None = None, expect: float | None = None) -> tuple[int, str]:
        env = tool_env(env)
        env["PYTHONUNBUFFERED"] = "1"
        env["TERM"] = env.get("TERM") or "xterm"
        if hi <= lo:
            hi = lo + 0.01
        if hi > 0.99:
            hi = 0.99
        master, slave = pty.openpty()
        try:
            proc = subprocess.Popen(
                cmd,
                cwd=str(cwd or ROOT),
                env=env,
                stdin=slave,
                stdout=slave,
                stderr=slave,
                close_fds=True,
            )
        except Exception:
            os.close(master)
            os.close(slave)
            raise
        os.close(slave)
        self._proc = proc
        last = lo
        self._pct(lo)
        started = time.time()
        buf = b""
        text = []
        expect = expect if expect and expect > 0 else EXPECT_SUPER
        try:
            while True:
                if self._stop.is_set():
                    proc.terminate()
                    break
                r, _, _ = select.select([master], [], [], 0.35)
                chunk = b""
                if master in r:
                    try:
                        chunk = os.read(master, 4096)
                    except OSError:
                        chunk = b""
                    if not chunk:
                        if proc.poll() is not None:
                            break
                if chunk:
                    buf += chunk.replace(b"\r\n", b"\n").replace(b"\r", b"\n")
                    while b"\n" in buf:
                        raw, buf = buf.split(b"\n", 1)
                        line = raw.decode("utf-8", "replace").rstrip()
                        if not line:
                            continue
                        text.append(line)
                        self._st(line[-200:])
                        unit = live_unit(line)
                        if unit is not None:
                            last = lo + (hi - lo) * unit
                            self._pct(last)
                            continue
                        mark = marker_unit(line)
                        if mark is not None:
                            mapped = lo + (hi - lo) * mark
                            if mapped > last:
                                last = mapped
                                self._pct(last)
                elif proc.poll() is not None:
                    if buf.strip():
                        line = buf.decode("utf-8", "replace").rstrip()
                        text.append(line)
                        buf = b""
                    break
                else:
                    grew = growing_bytes(started)
                    if grew > 8 * 1024 * 1024:
                        file_u = min(0.90, grew / expect)
                        mapped = lo + (hi - lo) * file_u
                        if mapped > last:
                            last = mapped
                            self._pct(last)
                    # Stay put. Fake crawl lied about ninja/cook idle.
            rc = proc.wait()
        finally:
            try:
                os.close(master)
            except OSError:
                pass
            self._proc = None
        return rc, "\n".join(text)

    def _cook(self, job: dict, emit_done: bool) -> str | None:
        self._ph("build", 0.25)
        self._pct(0.0)
        self._say("Cooking a new pin.")
        env = os.environ.copy()
        env["ATLAS_LINUX_SIZE_M"] = "1536"
        env["ATLAS_OPENWRT_SIZE_M"] = "128"
        feats = normalize_planes(job.get("features") or {})
        if feats.get("with_stock_fm_ir"):
            fm = ROOT / "apps" / "titan_fm" / "build.sh"
            if fm.is_file():
                self._st("building OSS TitanFm")
                frc, _blob = self._pipe(
                    ["bash", str(fm)], env, 0.02, 0.08, cwd=fm.parent
                )
                if frc != 0:
                    self._ph("fail", 0.05)
                    self._say("TitanFm build failed.")
                    self.bridge.finished.emit(False, "titanfm rc=%s" % frc)
                    return None
        cmd = [
            sys.executable,
            "-u",
            str(KITCHEN),
            "cook",
            "--preset",
            job.get("preset") or "lab_rootless",
            "--root-engine",
            job.get("root") or "none",
            "--skip-git-gate",
        ]
        gsi = resolve_gsi(job.get("gsi") or "")
        if gsi:
            cmd += ["--gsi", gsi]
            self._st("gsi " + Path(gsi).name)
        for k, v in feats.items():
            cmd += ["--option", "%s=%s" % (k, "1" if v else "0")]
        self._st("kitchen cook " + (job.get("preset") or "lab_rootless"))
        rc, blob = self._pipe(cmd, env, 0.02, 0.94)
        if rc != 0:
            self._ph("fail", 0.05)
            self._say("Cook failed.")
            self.bridge.finished.emit(False, "cook rc=%s" % rc)
            return None
        img = extract_super(blob)
        last = ROOT / "out" / "LAST_BUILD.txt"
        if (not img) and last.is_file():
            cand = last.read_text(errors="replace").strip()
            if cand and Path(cand).is_file():
                img = cand
        if not img:
            supers = list_supers()
            img = str(supers[0]) if supers else ""
        if not img:
            self._ph("fail", 0.05)
            self._say("Cook produced no image.")
            self.bridge.finished.emit(False, "no image")
            return None
        self._done_pct()
        self._ph("idle", 0.4)
        self._st("cooked " + Path(img).name)
        self._say("Pin cooked.")
        self.bridge.built.emit(img)
        if emit_done:
            self.bridge.finished.emit(True, img)
        return img

    def _gsi(self, job: dict) -> None:
        flavor = job.get("flavor") or "vanilla"
        fresh = bool(job.get("fresh"))
        self._ph("build", 0.3)
        self._pct(0.0)
        self._say("Building GSI.")
        env = os.environ.copy()
        env["ATLASOS_FLAVOR"] = flavor
        if fresh:
            env["GSI_FRESH"] = "1"
        build = ATLASOS / "scripts" / "build.sh"
        argv = ["bash", str(build), "--flavor", flavor, "--gsi-only"]
        if fresh:
            argv.append("--fresh")
        self._st("gsi flavor=" + flavor + (" fresh" if fresh else ""))
        rc, blob = self._pipe(
            argv,
            env,
            0.02,
            0.94,
            cwd=ATLASOS,
            expect=EXPECT_GSI,
        )
        if rc != 0 and ("DIRTY" in blob or "check_clean" in blob):
            self._st("tree dirty — GSI pipeline without clean gate")
            pipe = ATLASOS / "scripts" / "misterztr" / "pipeline.sh"
            rc, blob = self._pipe(
                ["bash", str(pipe), "--from=patch"],
                env,
                0.02,
                0.94,
                cwd=ATLASOS,
                expect=EXPECT_GSI,
            )
        if rc != 0:
            self._ph("fail", 0.05)
            self._say("GSI failed.")
            self.bridge.finished.emit(False, "gsi rc=%s" % rc)
            return
        img = extract_gsi(blob)
        if img:
            img = mirror_gsi(img)
        if not img:
            self._ph("fail", 0.05)
            self._say("GSI produced no image.")
            self.bridge.finished.emit(False, "no gsi")
            return
        self._done_pct()
        self._ph("idle", 0.4)
        self._st("gsi " + Path(img).name)
        self._say("GSI ready.")
        self.bridge.gsi_built.emit(img)
        self.bridge.finished.emit(True, img)

    def _pull(self) -> None:
        self._ph("build", 0.2)
        self._pct(0.0)
        self._say("Pulling AtlasOS from GitHub.")
        env = os.environ.copy()
        git = ["git", "-C", str(ATLASOS)]
        rc, blob = self._pipe(git + ["fetch", "origin"], env, 0.05, 0.45, cwd=ATLASOS)
        if rc != 0:
            self._ph("fail", 0.05)
            self.bridge.finished.emit(False, "fetch rc=%s" % rc)
            return
        st = subprocess.run(
            git + ["status", "-sb"],
            capture_output=True,
            text=True,
            timeout=15,
        )
        head = (st.stdout or "").strip().splitlines()
        summary = head[0] if head else "fetched"
        merge = subprocess.run(
            git + ["merge", "--ff-only", "@{u}"],
            capture_output=True,
            text=True,
            timeout=60,
        )
        if merge.returncode == 0:
            msg = (merge.stdout or merge.stderr or "fast-forward").strip().splitlines()
            tail = msg[-1] if msg else "up to date"
            self._st(summary + " · " + tail)
            subprocess.run(
                git + ["submodule", "update", "--init", "--recursive"],
                capture_output=True,
                text=True,
                timeout=120,
            )
            self._done_pct()
            self._ph("idle", 0.4)
            self._say("AtlasOS pulled.")
            self.bridge.finished.emit(True, tail)
            return
        err = (merge.stderr or merge.stdout or "not fast-forward").strip().splitlines()
        tail = err[-1] if err else "diverged"
        self._st(summary + " · " + tail)
        self._ph("idle", 0.3)
        self.bridge.finished.emit(True, summary + " — " + tail)

    def _write(self, job: dict) -> None:
        img = job.get("image") or ""
        if not img or not Path(img).is_file():
            self._ph("fail", 0.05)
            self._say("No image selected.")
            self.bridge.finished.emit(False, "no image")
            return
        self._ph("connect", 0.7)
        self._pct(0.0)
        self._say("Writing the selected pin. Keep the cable still.")
        env = os.environ.copy()
        env["FLASH" + "_YES"] = "1"
        env["FORCE" + "_FLASH"] = "1"
        if job.get("keep_data", True):
            env["KEEP" + "_DATA"] = "1"
        env["SKIP" + "_GIT_GATE"] = "1"
        env = tool_env(env)
        serial = pick_usb(job.get("serial") or "")
        adb = usb_adb()
        fb = usb_fastboot()
        if not serial:
            self._ph("fail", 0.05)
            self._say("No USB device.")
            self.bridge.finished.emit(False, "no usb")
            return
        env["SERIAL"] = serial
        env["ANDROID_SERIAL"] = serial
        if serial in adb and serial not in fb:
            self._st("usb reboot " + serial)
            r = subprocess.run(
                [adb_bin(), "-s", serial, "reboot", "boot" + "loader"],
                capture_output=True,
                text=True,
                timeout=30,
                env=env,
            )
            if r.returncode != 0:
                err = (r.stderr or r.stdout or "rc").strip()
                self._ph("fail", 0.05)
                self._st("reboot failed: " + err[-180:])
                self._say("Reboot failed.")
                self.bridge.finished.emit(False, err)
                return
            for i in range(45):
                if pick_usb(serial) and usb_fastboot():
                    break
                self._st("waiting loader %ss" % i)
                self._pct(min(0.08, 0.01 + i * 0.0015))
                self._stop.wait(1.0)
            if not usb_fastboot():
                self._ph("fail", 0.05)
                self._say("No USB loader.")
                self.bridge.finished.emit(False, "no usb loader")
                return
        self._ph("write", 0.85)
        rc, _blob = self._pipe([str(WRITER), img], env, 0.10, 0.96)
        if rc == 0:
            rec = {
                "ts": datetime.utcnow().strftime("%Y%m%dT%H%M%SZ"),
                "serial": serial,
                "pin": Path(img).name,
                "atlasos": git_head(ATLASOS),
                "titanus2": git_head(ROOT),
                "wipe": "0" if job.get("keep_data", True) else "1",
            }
            try:
                record_flash(ledger_path(), rec)
            except Exception:
                pass
            self._done_pct()
            self._ph("done", 1.0)
            self._st("flash complete " + Path(img).name)
            self._say("Done. The Titan will return.")
            self.bridge.finished.emit(True, img)
        else:
            self._ph("fail", 0.08)
            self._st("flash failed rc=%s" % rc)
            self._say("Flash failed.")
            self.bridge.finished.emit(False, "rc=%s" % rc)


class Flasher(QMainWindow):
    def __init__(self):
        super().__init__()
        self._busy = False
        self.setWindowTitle("Cube Flasher")
        self.setObjectName("CubeFlasher")
        self.resize(1220, 800)
        pal = self.palette()
        pal.setColor(QPalette.Window, QColor("#060001"))
        pal.setColor(QPalette.WindowText, QColor("#FF141A"))
        pal.setColor(QPalette.Base, QColor("#120204"))
        pal.setColor(QPalette.Text, QColor("#FFD0D2"))
        pal.setColor(QPalette.Button, QColor("#1A0406"))
        pal.setColor(QPalette.ButtonText, QColor("#FF141A"))
        pal.setColor(QPalette.Highlight, QColor("#3A080C"))
        pal.setColor(QPalette.HighlightedText, QColor("#FF141A"))
        self.setPalette(pal)
        self.setStyleSheet(
            "QMainWindow{background:#060001;color:#FFD0D2;}"
            "QLabel{color:#FFD0D2;}"
            "QComboBox,QComboBox QAbstractItemView,QAbstractItemView{"
            "background:#120204;color:#FFD0D2;border:1px solid #5A1014;"
            "selection-background-color:#3A080C;selection-color:#FF141A;"
            "font:11px monospace;}"
            "QComboBox::drop-down{border:0;width:18px;}"
            "QCheckBox{color:#FFD0D2;background:transparent;font:11px monospace;}"
            "QCheckBox::indicator{width:13px;height:13px;border:1px solid #FF141A;"
            "background:#120204;}"
            "QCheckBox::indicator:checked{background:#FF141A;}"
            "QScrollArea,QScrollArea>QWidget>QWidget{background:#120204;border:1px solid #5A1014;}"
            "QScrollBar:vertical{background:#120204;width:10px;}"
            "QScrollBar::handle:vertical{background:#5A1014;min-height:24px;}"
            "QTabWidget::pane{border:1px solid #5A1014;background:#060001;}"
            "QTabBar::tab{background:#120204;color:#FF141A;padding:6px 14px;"
            "border:1px solid #5A1014;font:bold 11px monospace;}"
            "QTabBar::tab:selected{background:#3A080C;}"
        )

        combo_css = (
            "QComboBox{background:#120204;color:#FFD0D2;border:1px solid #5A1014;"
            "padding:4px;font: 11px monospace;}"
        )
        list_css = (
            "QListWidget{background:#120204;color:#FFD0D2;border:1px solid #5A1014;"
            "font: 11px monospace;}"
            "QListWidget::item:selected{background:#3A080C;color:#FF141A;}"
        )

        root = QWidget()
        self.setCentralWidget(root)
        outer = QHBoxLayout(root)
        outer.setContentsMargins(12, 12, 12, 12)

        left = QVBoxLayout()
        title = QLabel("CUBE FLASHER")
        title.setAlignment(Qt.AlignCenter)
        title.setFont(QFont("monospace", 16, QFont.Bold))
        title.setStyleSheet("color:#FF141A; letter-spacing:5px;")
        left.addWidget(title)
        self.cube = CrimsonCube()
        self.cube.set_phase("idle", 0.2)
        left.addWidget(self.cube, 1)
        self.line = QLabel("ready")
        self.line.setWordWrap(True)
        self.line.setAlignment(Qt.AlignCenter)
        self.line.setStyleSheet("color:#E8A0A4; font: 11px monospace;")
        left.addWidget(self.line)
        self.bar = QProgressBar()
        self.bar.setRange(0, 1000)
        self.bar.setValue(0)
        self.bar.setFormat("%p%")
        self.bar.setStyleSheet(
            "QProgressBar{background:#120204;color:#FFD0D2;border:1px solid #5A1014;"
            "height:18px;text-align:center;}"
            "QProgressBar::chunk{background:#FF141A;}"
        )
        left.addWidget(self.bar)
        self.eta = QLabel("")
        self.eta.setAlignment(Qt.AlignCenter)
        self.eta.setWordWrap(True)
        self.eta.setStyleSheet("color:#C07074; font: 11px monospace;")
        left.addWidget(self.eta)
        self.dev = QLabel("")
        self.dev.setAlignment(Qt.AlignCenter)
        self.dev.setStyleSheet("color:#9A4044; font: 11px monospace;")
        left.addWidget(self.dev)
        self.rom = QLabel("plug Titan USB")
        self.rom.setWordWrap(True)
        self.rom.setAlignment(Qt.AlignCenter)
        self.rom.setStyleSheet("color:#C07074; font: 11px monospace;")
        left.addWidget(self.rom)
        usb_row = QHBoxLayout()
        usb_row.addWidget(QLabel("usb"))
        self.usb = QComboBox()
        self.usb.setEditable(False)
        usb_row.addWidget(self.usb, 1)
        left.addLayout(usb_row)
        self.said = QLabel("")
        self.said.setAlignment(Qt.AlignCenter)
        self.said.setStyleSheet("color:#7A3034; font: italic 11px monospace;")
        left.addWidget(self.said)
        self.btn_pull = self._btn("PULL GITHUB")
        self.btn_pull.clicked.connect(self.pull_github)
        left.addWidget(self.btn_pull)
        outer.addLayout(left, 2)

        right = QVBoxLayout()
        tabs = QTabWidget()
        tabs.addTab(self._titan_tab(list_css), "TITAN")
        tabs.addTab(self._pins_tab(combo_css, list_css), "PINS")
        tabs.addTab(self._gsi_tab(combo_css, list_css), "GSI")
        right.addWidget(tabs, 3)

        right.addWidget(self._lbl("ATLASOS / KITCHEN"))
        cfg = QHBoxLayout()
        self.preset = QComboBox()
        self.preset.setStyleSheet(combo_css)
        presets = kitchen_presets()
        self.preset.addItems(presets)
        if "lab_rootless" in presets:
            self.preset.setCurrentText("lab_rootless")
        self.root_eng = QComboBox()
        self.root_eng.setStyleSheet(combo_css)
        self.root_eng.addItems(ROOT_ENGINES)
        self.gsi = QComboBox()
        self.gsi.setStyleSheet(combo_css)
        cfg.addWidget(QLabel("preset"))
        cfg.addWidget(self.preset, 1)
        cfg.addWidget(QLabel("root"))
        cfg.addWidget(self.root_eng)
        right.addLayout(cfg)
        gsi_row = QHBoxLayout()
        gsi_row.addWidget(QLabel("gsi"))
        gsi_row.addWidget(self.gsi, 1)
        right.addLayout(gsi_row)

        feat_box = QWidget()
        feat_l = QGridLayout(feat_box)
        feat_l.setContentsMargins(4, 4, 4, 4)
        self.feat = {}
        for i, (key, label, default) in enumerate(kitchen_features()):
            cb = QCheckBox(label)
            cb.setChecked(default)
            self.feat[key] = cb
            feat_l.addWidget(cb, i // 2, i % 2)
        if "with_atlas_lp" in self.feat:
            self.feat["with_atlas_lp"].toggled.connect(self._plane_lock)
        if "with_openwrt_lp" in self.feat:
            self.feat["with_openwrt_lp"].toggled.connect(self._plane_lock)
        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        scroll.setWidget(feat_box)
        scroll.setMaximumHeight(200)
        right.addWidget(scroll)

        self.keep_data = QCheckBox("Keep userdata")
        self.keep_data.setChecked(True)
        right.addWidget(self.keep_data)
        self._plane_lock()

        brow = QHBoxLayout()
        self.btn_build = self._btn("BUILD")
        self.btn_both = self._btn("BUILD AND FLASH")
        self.btn_build.clicked.connect(lambda: self.start_job(False))
        self.btn_both.clicked.connect(lambda: self.start_job(True))
        brow.addWidget(self.btn_build)
        brow.addWidget(self.btn_both)
        right.addLayout(brow)
        outer.addLayout(right, 3)

        for w in (self.preset, self.root_eng, self.gsi, self.gsi_flavor):
            w.currentIndexChanged.connect(self._refresh_eta)
        for cb in list(self.feat.values()) + [self.keep_data]:
            cb.toggled.connect(self._refresh_eta)

        self.bridge = Bridge()
        self.bridge.status.connect(self.line.setText)
        self.bridge.phase.connect(self._phase)
        self.bridge.progress.connect(self._prog)
        self.bridge.spoken.connect(self.said.setText)
        self.bridge.built.connect(self._on_built)
        self.bridge.gsi_built.connect(self._on_gsi_built)
        self.bridge.finished.connect(self._on_done)
        self.bridge.diag.connect(self._on_diag)
        self.worker = Worker(self.bridge)
        self.worker.start()
        self._busy = False
        self._rom_cache = {"serial": "", "t": 0.0, "props": {}}
        self._diag_serial = ""
        self._job_phase = "idle"
        self._job_t0 = 0.0
        self._job_eta = 0.0

        self.dev_timer = QTimer(self)
        self.dev_timer.timeout.connect(self._tick_dev)
        self.dev_timer.start(1500)

        self.refresh_builds()
        self.refresh_gsi()
        self._refresh_eta()
        self._tick_dev()

    def _titan_tab(self, list_css: str) -> QWidget:
        w = QWidget()
        v = QVBoxLayout(w)
        v.setContentsMargins(6, 8, 6, 6)
        v.addWidget(self._lbl("CONNECTED ROM"))
        self.titan_view = QPlainTextEdit()
        self.titan_view.setReadOnly(True)
        self.titan_view.setStyleSheet(list_css.replace("QListWidget", "QPlainTextEdit"))
        self.titan_view.setPlaceholderText("Plug Titan over USB to read the ROM and updates since last flash.")
        v.addWidget(self.titan_view, 1)
        self.btn_diag = self._btn("RUN DIAGNOSTICS")
        self.btn_diag.clicked.connect(self._force_diag)
        v.addWidget(self.btn_diag)
        self.btn_pair = self._btn("NANOBOT AUTH")
        self.btn_pair.clicked.connect(self._sync_pair)
        v.addWidget(self.btn_pair)
        return w

    def _pins_tab(self, combo_css: str, list_css: str) -> QWidget:
        w = QWidget()
        v = QVBoxLayout(w)
        v.setContentsMargins(6, 8, 6, 6)
        head = QHBoxLayout()
        head.addWidget(self._lbl("PINS"))
        head.addStretch(1)
        head.addWidget(QLabel("sort"))
        self.sort_pins = QComboBox()
        self.sort_pins.setStyleSheet(combo_css)
        self.sort_pins.addItems(SORTS)
        self.sort_pins.currentTextChanged.connect(lambda *_: self.refresh_builds())
        head.addWidget(self.sort_pins)
        v.addLayout(head)
        self.builds = QListWidget()
        self.builds.setStyleSheet(list_css)
        self.builds.setSelectionMode(QAbstractItemView.ExtendedSelection)
        self.builds.itemSelectionChanged.connect(self._refresh_eta)
        v.addWidget(self.builds, 1)
        row = QHBoxLayout()
        self.btn_refresh = self._btn("REFRESH")
        self.btn_del = self._btn("DELETE")
        self.btn_write = self._btn("FLASH SELECTED")
        self.btn_refresh.clicked.connect(self.refresh_builds)
        self.btn_del.clicked.connect(self.delete_selected)
        self.btn_write.clicked.connect(self.write_selected)
        row.addWidget(self.btn_refresh)
        row.addWidget(self.btn_del)
        row.addWidget(self.btn_write)
        v.addLayout(row)
        return w

    def _gsi_tab(self, combo_css: str, list_css: str) -> QWidget:
        w = QWidget()
        v = QVBoxLayout(w)
        v.setContentsMargins(6, 8, 6, 6)
        head = QHBoxLayout()
        head.addWidget(self._lbl("GSI"))
        head.addStretch(1)
        head.addWidget(QLabel("sort"))
        self.sort_gsi = QComboBox()
        self.sort_gsi.setStyleSheet(combo_css)
        self.sort_gsi.addItems(SORTS)
        self.sort_gsi.currentTextChanged.connect(lambda *_: self.refresh_gsi())
        head.addWidget(self.sort_gsi)
        v.addLayout(head)
        self.gsi_list = QListWidget()
        self.gsi_list.setStyleSheet(list_css)
        self.gsi_list.setSelectionMode(QAbstractItemView.ExtendedSelection)
        self.gsi_list.itemSelectionChanged.connect(self._gsi_picked)
        v.addWidget(self.gsi_list, 1)
        flav = QHBoxLayout()
        flav.addWidget(QLabel("flavor"))
        self.gsi_flavor = QComboBox()
        self.gsi_flavor.setStyleSheet(combo_css)
        self.gsi_flavor.addItems(GSI_FLAVORS)
        flav.addWidget(self.gsi_flavor, 1)
        v.addLayout(flav)
        self.gsi_fresh = QCheckBox("Fresh (clean product out, full ninja)")
        self.gsi_fresh.setChecked(False)
        self.gsi_fresh.toggled.connect(self._refresh_eta)
        v.addWidget(self.gsi_fresh)
        row = QHBoxLayout()
        self.btn_gsi_refresh = self._btn("REFRESH")
        self.btn_gsi_del = self._btn("DELETE")
        self.btn_gsi_build = self._btn("BUILD GSI")
        self.btn_gsi_refresh.clicked.connect(self.refresh_gsi)
        self.btn_gsi_del.clicked.connect(self.delete_gsi)
        self.btn_gsi_build.clicked.connect(self.build_gsi)
        row.addWidget(self.btn_gsi_refresh)
        row.addWidget(self.btn_gsi_del)
        row.addWidget(self.btn_gsi_build)
        v.addLayout(row)
        return w

    def _lbl(self, t: str) -> QLabel:
        x = QLabel(t)
        x.setStyleSheet("color:#FF141A; font: bold 12px monospace; letter-spacing:3px;")
        return x

    def _btn(self, t: str) -> QPushButton:
        b = QPushButton(t)
        b.setCursor(Qt.PointingHandCursor)
        b.setStyleSheet(
            "QPushButton{background:#1A0406;color:#FF141A;border:1px solid #FF141A;"
            "padding:8px 10px;font:bold 12px monospace;}"
            "QPushButton:disabled{color:#5A3030;border-color:#3A1818;}"
        )
        return b

    def _phase(self, name: str, glow: float) -> None:
        self._job_phase = name
        self.cube.set_phase(name, glow)

    def _gsi_choice_label(self) -> str:
        data = self.gsi.currentData()
        if data == GSI_LATEST:
            top = latest_gsi()
            return "latest (%s)" % (top.name if top else "none on disk")
        if not data:
            return "kitchen default"
        return Path(str(data)).name

    def _paint_clock(self) -> None:
        if not self._busy:
            return
        elapsed = time.time() - self._job_t0
        eta = float(self._job_eta or 0)
        if eta > 0:
            left = eta - elapsed
            if left > 0:
                clock = "elapsed %s  ·  ~%s left" % (fmt_secs(elapsed), fmt_secs(left))
            else:
                clock = "elapsed %s  ·  still running (est was %s)" % (
                    fmt_secs(elapsed), fmt_secs(eta)
                )
        else:
            clock = "elapsed %s" % fmt_secs(elapsed)
        self.eta.setText(clock)
        if self.bar.value() > 0:
            self.bar.setFormat("%.0f%%   %s" % (self.bar.value() / 10.0, clock))
        else:
            self.bar.setFormat(clock)

    def _prog(self, f: float) -> None:
        f = max(0.0, min(1.0, float(f)))
        self.bar.setValue(int(round(f * 1000)))
        if self._busy:
            self._paint_clock()
        else:
            self.bar.setFormat("%p%")

    def _set_busy(self, busy: bool) -> None:
        self._busy = busy
        for b in (
            self.btn_build,
            self.btn_both,
            self.btn_write,
            self.btn_del,
            self.btn_refresh,
            self.btn_gsi_build,
            self.btn_gsi_del,
            self.btn_gsi_refresh,
            self.btn_pull,
        ):
            b.setEnabled(not busy)
        if getattr(self, "gsi_fresh", None) is not None:
            self.gsi_fresh.setEnabled(not busy)
        if getattr(self, "gsi_flavor", None) is not None:
            self.gsi_flavor.setEnabled(not busy)

    def _fill_list(self, widget: QListWidget, paths: list[Path], keep: set[str]) -> None:
        widget.clear()
        first = None
        for p in paths:
            it = QListWidgetItem(fmt_img(p))
            it.setData(Qt.UserRole, str(p))
            widget.addItem(it)
            if str(p) in keep:
                it.setSelected(True)
                if first is None:
                    first = it
        if first is not None:
            widget.setCurrentItem(first)
        elif widget.count():
            widget.setCurrentRow(0)

    def refresh_builds(self) -> None:
        keep = {it.data(Qt.UserRole) for it in self.builds.selectedItems()}
        self._fill_list(self.builds, list_supers(self.sort_pins.currentText()), keep)
        self._refresh_eta()

    def refresh_gsi(self) -> None:
        keep = {it.data(Qt.UserRole) for it in self.gsi_list.selectedItems()}
        imgs = list_gsi(self.sort_gsi.currentText())
        self._fill_list(self.gsi_list, imgs, keep)
        cur = self.gsi.currentData() if self.gsi.count() else GSI_LATEST
        self.gsi.blockSignals(True)
        self.gsi.clear()
        top = latest_gsi()
        latest_lbl = "(latest) " + top.name if top else "(latest)"
        self.gsi.addItem(latest_lbl, GSI_LATEST)
        self.gsi.addItem("(kitchen default)", "")
        for img in imgs:
            self.gsi.addItem(img.name, str(img))
            if cur and str(img) == cur:
                self.gsi.setCurrentIndex(self.gsi.count() - 1)
        if cur == GSI_LATEST or cur is None:
            self.gsi.setCurrentIndex(0)
        elif cur == "":
            self.gsi.setCurrentIndex(1)
        self.gsi.blockSignals(False)
        self._refresh_eta()

    def _selected_paths(self, widget: QListWidget) -> list[str]:
        paths = [it.data(Qt.UserRole) for it in widget.selectedItems()]
        return [p for p in paths if p]

    def _primary_path(self, widget: QListWidget) -> str:
        it = widget.currentItem()
        if it and it.isSelected():
            return it.data(Qt.UserRole) or ""
        sel = self._selected_paths(widget)
        return sel[0] if sel else ""

    def delete_selected(self) -> None:
        self._delete_paths(self._selected_paths(self.builds), "pin", self.refresh_builds)

    def delete_gsi(self) -> None:
        self._delete_paths(self._selected_paths(self.gsi_list), "GSI", self.refresh_gsi)

    def _delete_paths(self, paths: list[str], kind: str, after) -> None:
        if not paths:
            return
        names = "\n".join(Path(p).name for p in paths[:12])
        extra = "" if len(paths) <= 12 else "\n… +%d" % (len(paths) - 12)
        if (
            QMessageBox.question(
                self,
                "Delete %s" % kind,
                "Delete %d %s?\n%s%s" % (len(paths), kind, names, extra),
            )
            != QMessageBox.Yes
        ):
            return
        for p in paths:
            try:
                Path(p).unlink()
            except Exception as e:
                QMessageBox.warning(self, "Delete failed", "%s\n%s" % (p, e))
        self.line.setText("deleted %d %s" % (len(paths), kind))
        after()

    def _gsi_picked(self) -> None:
        path = self._primary_path(self.gsi_list)
        if path:
            for i in range(self.gsi.count()):
                if self.gsi.itemData(i) == path:
                    self.gsi.setCurrentIndex(i)
                    break
        self._refresh_eta()

    def _plane_lock(self, *_a) -> None:
        alp = self.feat.get("with_atlas_lp")
        ow = self.feat.get("with_openwrt_lp")
        atl = self.feat.get("with_atlas")
        if alp and alp.isChecked():
            if atl and not atl.isChecked():
                atl.blockSignals(True)
                atl.setChecked(True)
                atl.blockSignals(False)
            if ow and not ow.isChecked():
                ow.blockSignals(True)
                ow.setChecked(True)
                ow.blockSignals(False)
        elif ow and not ow.isChecked() and alp and alp.isChecked():
            alp.blockSignals(True)
            alp.setChecked(False)
            alp.blockSignals(False)
        self._refresh_eta()

    def _cook_job(self) -> dict:
        feats = normalize_planes(
            {k: cb.isChecked() for k, cb in self.feat.items()}
        )
        return {
            "preset": self.preset.currentText(),
            "root": self.root_eng.currentText(),
            "gsi": self.gsi.currentData() if self.gsi.currentData() is not None else GSI_LATEST,
            "features": feats,
            "keep_data": self.keep_data.isChecked(),
        }

    def _arm(self, eta: int) -> None:
        self._set_busy(True)
        self._job_t0 = time.time()
        self._job_eta = float(eta)
        self._prog(0.0)

    def start_job(self, then_flash: bool) -> None:
        if self._busy:
            return
        job = self._cook_job()
        eta = estimate_cook(job["features"], job["root"])
        if then_flash:
            eta += estimate_flash(keep_data=job["keep_data"])
            if (
                QMessageBox.question(
                    self,
                    "Build and flash",
                    "Cook then flash the new pin.\nGSI: %s\nKeep data: %s\nETA ~%s"
                    % (self._gsi_choice_label(), job["keep_data"], fmt_secs(eta)),
                )
                != QMessageBox.Yes
            ):
                return
        self._arm(eta)
        job["kind"] = "cook_write" if then_flash else "cook"
        job["serial"] = pick_usb(self.usb.currentText())
        self.worker.submit(job)

    def build_gsi(self) -> None:
        if self._busy:
            return
        flavor = self.gsi_flavor.currentText()
        fresh = bool(getattr(self, "gsi_fresh", None) and self.gsi_fresh.isChecked())
        eta = estimate_gsi(flavor, fresh=fresh)
        if (
            QMessageBox.question(
                self,
                "Build GSI",
                "Build AtlasOS GSI flavor=%s%s\nETA ~%s"
                    % (flavor, " · fresh" if fresh else "", fmt_secs(eta)),
            )
            != QMessageBox.Yes
        ):
            return
        self._arm(eta)
        self.worker.submit({"kind": "gsi", "flavor": flavor, "fresh": fresh})

    def pull_github(self) -> None:
        if self._busy:
            return
        self._arm(90)
        self.worker.submit({"kind": "pull"})

    def write_selected(self) -> None:
        if self._busy:
            return
        path = self._primary_path(self.builds)
        if not path:
            QMessageBox.information(self, "Cube Flasher", "Select a pin.")
            return
        n = len(self._selected_paths(self.builds))
        if not usb_targets():
            QMessageBox.information(self, "Cube Flasher", "Connect the Titan over USB.")
            return
        eta = estimate_flash(path, self.keep_data.isChecked())
        note = Path(path).name
        if n > 1:
            note += "\n(%d selected — flashing the highlighted one)" % n
        if (
            QMessageBox.question(
                self,
                "Flash selected",
                "Flash\n%s\nKeep data: %s\nETA ~%s"
                % (note, self.keep_data.isChecked(), fmt_secs(eta)),
            )
            != QMessageBox.Yes
        ):
            return
        self._arm(eta)
        self.worker.submit(
            {
                "kind": "write",
                "image": path,
                "keep_data": self.keep_data.isChecked(),
                "serial": pick_usb(self.usb.currentText()),
            }
        )

    def _on_built(self, img: str) -> None:
        self.refresh_builds()
        for i in range(self.builds.count()):
            if self.builds.item(i).data(Qt.UserRole) == img:
                self.builds.setCurrentRow(i)
                break

    def _on_gsi_built(self, img: str) -> None:
        self.refresh_gsi()
        for i in range(self.gsi_list.count()):
            if self.gsi_list.item(i).data(Qt.UserRole) == img:
                self.gsi_list.setCurrentRow(i)
                break
        for i in range(self.gsi.count()):
            if self.gsi.itemData(i) == img:
                self.gsi.setCurrentIndex(i)
                break

    def _on_done(self, ok: bool, msg: str) -> None:
        self._set_busy(False)
        self._job_eta = 0.0
        self.bar.setFormat("%p%")
        self.refresh_builds()
        self.refresh_gsi()
        self._refresh_eta()
        if not ok:
            self.line.setText(msg)

    def _refresh_eta(self) -> None:
        if self._busy:
            return
        job = {
            "features": {k: cb.isChecked() for k, cb in self.feat.items()},
            "root": self.root_eng.currentText(),
        }
        cook = estimate_cook(job["features"], job["root"])
        flash = estimate_flash(self._primary_path(self.builds), self.keep_data.isChecked())
        fresh = bool(getattr(self, "gsi_fresh", None) and self.gsi_fresh.isChecked())
        gsi = estimate_gsi(self.gsi_flavor.currentText(), fresh=fresh)
        gsi_bit = "gsi %s%s" % (fmt_secs(gsi), " fresh" if fresh else "")
        self.eta.setText(
            "ETA  cook %s  ·  flash %s  ·  %s  ·  %s"
            % (fmt_secs(cook), fmt_secs(flash), gsi_bit, self._gsi_choice_label())
        )

    def _force_diag(self) -> None:
        self._diag_serial = ""
        ser = ""
        if self.usb.currentText() and self.usb.currentText() in usb_adb():
            ser = self.usb.currentText()
        elif usb_adb():
            ser = usb_adb()[0]
        if not ser:
            self.line.setText("no usb adb for diag")
            return
        self._maybe_diag(ser)

    def _sync_pair(self) -> None:
        ser = ""
        if self.usb.currentText() and self.usb.currentText() in usb_adb():
            ser = self.usb.currentText()
        elif usb_adb():
            ser = usb_adb()[0]
        if not ser:
            self.line.setText("no usb adb for nanobot pair")
            return
        self.line.setText("nanobot pair USB")
        threading.Thread(target=self._pair_worker, args=(ser,), daemon=True).start()

    def _pair_worker(self, serial: str) -> None:
        try:
            meta = pull_pair(serial, adb_bin(), tool_env())
            text = format_pair(meta)
        except Exception as e:
            text = "nanobot pair failed: %s" % e
        self.bridge.diag.emit(text)

    def _maybe_diag(self, serial: str) -> None:
        if self._busy:
            return
        if not serial:
            self._diag_serial = ""
            return
        if serial == self._diag_serial:
            return
        self._diag_serial = serial
        self.line.setText("diag USB")
        threading.Thread(target=self._diag_worker, args=(serial,), daemon=True).start()

    def _diag_worker(self, serial: str) -> None:
        try:
            snap = diag_collect(serial, adb_shell)
            inbox = OUT / "outdev" / "inbox"
            reports = pull_reports(serial, adb_bin(), tool_env(), inbox)
            for r in reports:
                fill_selected_logs(serial, adb_shell, r)
            findings = diag_detect(snap)
            extra_rep = reports_as_findings(reports)
            comments = [r.get("comment") or "" for r in reports if r.get("comment")]
            if comments:
                snap["user_comment"] = " | ".join(comments)[:800]
            extra = nanobot_classify(snap, findings)
            seen_ids = {f["id"] for f in findings}
            for e in extra_rep:
                if e.get("id") and e["id"] not in seen_ids:
                    findings.append(e)
                    seen_ids.add(e["id"])
            for e in extra:
                if e.get("id") and e["id"] not in seen_ids:
                    findings.append(e)
                    seen_ids.add(e["id"])
            posts = []
            if findings:
                owner, repo = origin_repo(ATLASOS)
                token = github_token()
                have = existing_fingerprints(owner, repo, token) if owner and token else set()
                for f in findings:
                    posts.append("%s -> %s" % (f["id"], post_finding(f, snap, ATLASOS, have)))
                if nanobot_ready():
                    for f in findings[:2]:
                        posts.append("fix %s -> %s" % (f["id"], develop_and_ship(f, snap, ATLASOS)))
            self.bridge.diag.emit(format_diag(snap, findings, note))
        except Exception as e:
            self.bridge.diag.emit("diag failed: %s\n" % e)

    def _on_diag(self, text: str) -> None:
        if hasattr(self, "titan_view"):
            cur = self.titan_view.toPlainText().rstrip()
            self.titan_view.setPlainText((cur + "\n\n" if cur else "") + text)
        self._diag_ran = True
        self.line.setText("diag done")

    def _refresh_titan(self, serial: str, loader: bool) -> None:
        if not serial:
            self._diag_serial = ""
            self._diag_ran = False
            self.rom.setText("USB loader u2014 ROM unread" if loader else "plug Titan USB")
            if hasattr(self, "titan_view"):
                self.titan_view.setPlainText(
                    "USB loader u2014 wait for adb after boot.\n" if loader
                    else "Plug Titan over USB. Cube Flasher is USB-only.\n"
                )
            return
        now = time.time()
        cache = self._rom_cache
        if cache.get("serial") != serial or now - float(cache.get("t") or 0) > 8:
            cache["props"] = probe_usb_rom(serial)
            cache["serial"] = serial
            cache["t"] = now
            flash = last_flash_for(serial, ledger_path(), receipt_dir())
            if flash:
                push_flash_stamp(serial, flash)
        props = cache.get("props") or {}
        flash = last_flash_for(serial, ledger_path(), receipt_dir())
        commits = rom_changelog(flash)
        pins = newer_pins(OUT, (flash or {}).get("ts") or "")
        self.rom.setText(format_rom_summary(props, flash) if props else serial)
        if hasattr(self, "titan_view") and not getattr(self, "_diag_ran", False):
            self.titan_view.setPlainText(format_rom_report(props, flash, commits, pins))
        self._maybe_diag(serial)

    def _tick_dev(self) -> None:
        if self._busy:
            self._paint_clock()
        adb, fb = usb_adb(), usb_fastboot()
        live = usb_targets()
        cur = self.usb.currentText()
        self.usb.blockSignals(True)
        self.usb.clear()
        for s in live:
            self.usb.addItem(s)
        if cur and cur in live:
            self.usb.setCurrentText(cur)
        self.usb.blockSignals(False)
        parts = []
        if adb:
            parts.append("usb adb " + ",".join(adb))
        if fb:
            parts.append("usb fastboot " + ",".join(fb))
        if not parts:
            parts.append("no usb")
        parts.append("%d pins" % self.builds.count())
        parts.append("%d gsi" % self.gsi_list.count())
        nsel = len(self._selected_paths(self.builds))
        if nsel > 1:
            parts.append("%d selected" % nsel)
        self.dev.setText("  ·  ".join(parts))
        if not self._busy:
            if fb:
                self.cube.set_phase("connect", 0.85)
            elif adb:
                self.cube.set_phase("connect", 0.75)
            else:
                self.cube.set_phase("idle", 0.2)
        self._refresh_titan(adb[0] if adb else "", bool(fb))

    def closeEvent(self, ev) -> None:
        self.worker.stop()
        ev.accept()


def ensure_adb() -> None:
    try:
        subprocess.run(
            [adb_bin(), "start-server"],
            env=tool_env(),
            timeout=8,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
    except Exception:
        pass


def main() -> None:
    ensure_adb()
    app = QApplication(sys.argv)
    app.setStyle(QStyleFactory.create("Fusion"))
    app.setApplicationName("Cube Flasher")
    app.setDesktopFileName("cube-flasher")
    w = Flasher()
    w.show()
    sys.exit(app.exec_())


if __name__ == "__main__":
    main()
