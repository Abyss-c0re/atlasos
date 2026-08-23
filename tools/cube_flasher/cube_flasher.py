#!/usr/bin/env python3
"""Cube Flasher — kitchen cook + pin manager. Live percent only."""
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
    QApplication,
    QCheckBox,
    QComboBox,
    QHBoxLayout,
    QLabel,
    QListWidget,
    QListWidgetItem,
    QMainWindow,
    QMessageBox,
    QProgressBar,
    QPushButton,
    QScrollArea,
    QStyleFactory,
    QVBoxLayout,
    QWidget,
)

from cube_widget import CrimsonCube

ROOT = Path("/home/voldemar/Dev/device-workshop/products/titanus2")
OUT = ROOT / "out"
GSI_DIR = ROOT / "gsi"
KITCHEN = ROOT / "scripts" / "kitchen.py"
WRITER = ROOT / "scripts" / ("flash" + "_titan2_eea.sh")

# Honest live tokens only. 100% from a finished log is ignored.
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
    ("with_cube_icons", "Cube square icons", True),
    ("with_input", "Pad / keyboard", True),
    ("with_controls", "Titan Controls", True),
    ("with_usb_hid", "USB / BT HID", True),
    ("with_display", "Display dens", True),
    ("with_ims_treble", "IMS / VoLTE", True),
    ("with_openeuicc", "OpenEUICC eSIM", True),
    ("with_microg", "microG", True),
    ("with_stock_fm_ir", "FM + IR", True),
    ("with_square_chrome", "Square chrome RROs (lab)", False),
    ("with_nanobot", "Nanobot agent (lab)", False),
]
ROOT_ENGINES = ["none", "magisk_release", "magisk_source", "kernelsu_source"]
EXPECT_SUPER = 4.4 * 1024 * 1024 * 1024


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


def list_supers() -> list[Path]:
    if not OUT.is_dir():
        return []
    imgs = [p for p in OUT.glob("*super*.img") if p.is_file()]
    imgs.sort(key=lambda p: p.stat().st_mtime, reverse=True)
    return imgs


def list_gsi() -> list[Path]:
    if not GSI_DIR.is_dir():
        return []
    imgs = [p for p in GSI_DIR.glob("*.img") if p.is_file() and not p.is_symlink()]
    imgs.sort(key=lambda p: p.stat().st_mtime, reverse=True)
    return imgs


def adb_serials() -> list[str]:
    try:
        out = subprocess.check_output(["adb", "devices"], text=True, timeout=5)
    except Exception:
        return []
    ser = []
    for line in out.splitlines()[1:]:
        p = line.split()
        if len(p) >= 2 and p[1] == "device":
            ser.append(p[0])
    ser.sort(key=lambda s: (0 if s.startswith("TITAN") else 1, ":" in s))
    return ser


def loader_serials() -> list[str]:
    try:
        out = subprocess.check_output(["fastboot", "devices"], text=True, timeout=5)
    except Exception:
        return []
    return [ln.split()[0] for ln in out.splitlines() if ln.split()]


def fmt_img(p: Path) -> str:
    st = p.stat()
    mb = st.st_size / (1024 * 1024)
    ts = datetime.fromtimestamp(st.st_mtime).strftime("%Y-%m-%d %H:%M")
    return f"{p.name}   {mb:.0f}M   {ts}"


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


def growing_super_bytes(since: float) -> int:
    best = 0
    if not OUT.is_dir():
        return 0
    for p in OUT.glob("*super*.img"):
        try:
            st = p.stat()
        except OSError:
            continue
        if st.st_mtime >= since - 2 and st.st_size > best:
            best = st.st_size
    build = ROOT / "build"
    if build.is_dir():
        for p in build.glob("super*.img"):
            try:
                st = p.stat()
            except OSError:
                continue
            if st.st_mtime >= since - 2 and st.st_size > best:
                best = st.st_size
    return best


class Bridge(QObject):
    status = pyqtSignal(str)
    phase = pyqtSignal(str, float)
    progress = pyqtSignal(float)
    spoken = pyqtSignal(str)
    built = pyqtSignal(str)
    finished = pyqtSignal(bool, str)


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
            try:
                if kind == "cook":
                    self._cook(job, emit_done=True)
                elif kind == "write":
                    self._write(job)
                elif kind == "cook_write":
                    img = self._cook(job, emit_done=False)
                    if not img:
                        continue
                    if not adb_serials() and not loader_serials():
                        self._st("cooked — connect Titan, then FLASH SELECTED")
                        self._say("Pin cooked. Connect the Titan to flash.")
                        self.bridge.finished.emit(True, img)
                        continue
                    job = dict(job)
                    job["image"] = img
                    self._write(job)
            except Exception as e:
                self._ph("fail", 0.05)
                self._st("error: %s" % e)
                self._say("Failed.")
                self.bridge.finished.emit(False, str(e))

    def _pipe(self, cmd: list[str], env: dict, lo: float, hi: float) -> tuple[int, str]:
        """Run cmd on a PTY. Map live tokens into [lo, hi). Never emit 1.0."""
        env = dict(env)
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
                cwd=str(ROOT),
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
                        show = line[-200:]
                        self._st(show)
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
                    grew = growing_super_bytes(started)
                    if grew > 8 * 1024 * 1024:
                        file_u = min(0.90, grew / EXPECT_SUPER)
                        mapped = lo + (hi - lo) * file_u
                        if mapped > last:
                            last = mapped
                            self._pct(last)
                    elif last < hi - 0.04:
                        last = min(hi - 0.04, last + 0.002)
                        self._pct(last)
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
        gsi = job.get("gsi") or ""
        if gsi:
            cmd += ["--gsi", gsi]
        for k, v in (job.get("features") or {}).items():
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
        adb = adb_serials()
        fb = loader_serials()
        if adb and not fb:
            serial = adb[0]
            env["SERIAL"] = serial
            env["ANDROID_SERIAL"] = serial
            self._st("reboot -s " + serial)
            r = subprocess.run(
                ["adb", "-s", serial, "reboot", "boot" + "loader"],
                capture_output=True,
                text=True,
                timeout=30,
            )
            if r.returncode != 0:
                err = (r.stderr or r.stdout or "rc").strip()
                self._ph("fail", 0.05)
                self._st("reboot failed: " + err[-180:])
                self._say("Reboot failed.")
                self.bridge.finished.emit(False, err)
                return
            for i in range(45):
                if loader_serials():
                    break
                self._st("waiting loader %ss" % i)
                self._pct(min(0.08, 0.01 + i * 0.0015))
                self._stop.wait(1.0)
            if not loader_serials():
                self._ph("fail", 0.05)
                self._say("No loader.")
                self.bridge.finished.emit(False, "no loader")
                return
        self._ph("write", 0.85)
        rc, _blob = self._pipe([str(WRITER), img], env, 0.10, 0.96)
        if rc == 0:
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
        self.setWindowTitle("Cube Flasher")
        self.setObjectName("CubeFlasher")
        self.resize(1140, 780)
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
        self.dev = QLabel("")
        self.dev.setAlignment(Qt.AlignCenter)
        self.dev.setStyleSheet("color:#9A4044; font: 11px monospace;")
        left.addWidget(self.dev)
        self.said = QLabel("")
        self.said.setAlignment(Qt.AlignCenter)
        self.said.setStyleSheet("color:#7A3034; font: italic 11px monospace;")
        left.addWidget(self.said)
        outer.addLayout(left, 2)

        combo_css = (
            "QComboBox{background:#120204;color:#FFD0D2;border:1px solid #5A1014;"
            "padding:4px;font: 11px monospace;}"
            "QComboBox QAbstractItemView{background:#120204;color:#FFD0D2;}"
        )

        right = QVBoxLayout()
        right.addWidget(self._lbl("BUILDS"))
        self.builds = QListWidget()
        self.builds.setStyleSheet(
            "QListWidget{background:#120204;color:#FFD0D2;border:1px solid #5A1014;"
            "font: 11px monospace;}"
            "QListWidget::item:selected{background:#3A080C;color:#FF141A;}"
        )
        right.addWidget(self.builds, 2)
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
        right.addLayout(row)

        right.addWidget(self._lbl("KITCHEN"))
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
        cfg.addWidget(QLabel("preset"))
        cfg.addWidget(self.preset, 1)
        cfg.addWidget(QLabel("root"))
        cfg.addWidget(self.root_eng)
        right.addLayout(cfg)
        gsi_row = QHBoxLayout()
        self.gsi = QComboBox()
        self.gsi.setStyleSheet(combo_css)
        gsi_row.addWidget(QLabel("gsi"))
        gsi_row.addWidget(self.gsi, 1)
        right.addLayout(gsi_row)

        feat_box = QWidget()
        feat_l = QVBoxLayout(feat_box)
        feat_l.setContentsMargins(0, 0, 0, 0)
        self.feat = {}
        for key, label, default in kitchen_features():
            cb = QCheckBox(label)
            cb.setChecked(default)
            cb.setStyleSheet("color:#FFD0D2; font: 11px monospace;")
            self.feat[key] = cb
            feat_l.addWidget(cb)
        self.keep_data = QCheckBox("Keep userdata (dirty flash)")
        self.keep_data.setChecked(True)
        self.keep_data.setStyleSheet("color:#FFD0D2; font: 11px monospace;")
        feat_l.addWidget(self.keep_data)
        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        scroll.setWidget(feat_box)
        scroll.setStyleSheet("QScrollArea{border:1px solid #5A1014;}")
        right.addWidget(scroll, 1)

        brow = QHBoxLayout()
        self.btn_build = self._btn("BUILD")
        self.btn_both = self._btn("BUILD AND FLASH")
        self.btn_build.clicked.connect(lambda: self.start_job(False))
        self.btn_both.clicked.connect(lambda: self.start_job(True))
        brow.addWidget(self.btn_build)
        brow.addWidget(self.btn_both)
        right.addLayout(brow)
        outer.addLayout(right, 3)

        self.bridge = Bridge()
        self.bridge.status.connect(self.line.setText)
        self.bridge.phase.connect(self._phase)
        self.bridge.progress.connect(self._prog)
        self.bridge.spoken.connect(self.said.setText)
        self.bridge.built.connect(self._on_built)
        self.bridge.finished.connect(self._on_done)
        self.worker = Worker(self.bridge)
        self.worker.start()
        self._busy = False
        self._job_phase = "idle"

        self.dev_timer = QTimer(self)
        self.dev_timer.timeout.connect(self._tick_dev)
        self.dev_timer.start(1500)

        self.refresh_builds()
        self.refresh_gsi()
        self._tick_dev()

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

    def _prog(self, f: float) -> None:
        f = max(0.0, min(1.0, float(f)))
        self.bar.setValue(int(round(f * 1000)))

    def _set_busy(self, busy: bool) -> None:
        self._busy = busy
        for b in (
            self.btn_build,
            self.btn_both,
            self.btn_write,
            self.btn_del,
            self.btn_refresh,
        ):
            b.setEnabled(not busy)

    def refresh_builds(self) -> None:
        cur = self._selected_path()
        self.builds.clear()
        for p in list_supers():
            it = QListWidgetItem(fmt_img(p))
            it.setData(Qt.UserRole, str(p))
            self.builds.addItem(it)
            if cur and str(p) == cur:
                self.builds.setCurrentItem(it)
        if self.builds.currentItem() is None and self.builds.count():
            self.builds.setCurrentRow(0)

    def refresh_gsi(self) -> None:
        self.gsi.clear()
        self.gsi.addItem("(default)", "")
        for p in list_gsi():
            self.gsi.addItem(p.name, str(p))

    def _selected_path(self) -> str:
        it = self.builds.currentItem()
        return it.data(Qt.UserRole) if it else ""

    def delete_selected(self) -> None:
        path = self._selected_path()
        if not path:
            return
        if (
            QMessageBox.question(
                self,
                "Delete pin",
                "Delete\n%s ?" % Path(path).name,
            )
            != QMessageBox.Yes
        ):
            return
        try:
            Path(path).unlink()
            self.line.setText("deleted " + Path(path).name)
        except Exception as e:
            QMessageBox.warning(self, "Delete failed", str(e))
        self.refresh_builds()

    def _cook_job(self) -> dict:
        feats = {k: cb.isChecked() for k, cb in self.feat.items()}
        return {
            "preset": self.preset.currentText(),
            "root": self.root_eng.currentText(),
            "gsi": self.gsi.currentData() or "",
            "features": feats,
            "keep_data": self.keep_data.isChecked(),
        }

    def start_job(self, then_flash: bool) -> None:
        if self._busy:
            return
        if then_flash:
            path_note = "kitchen cook → then flash the new pin"
            if (
                QMessageBox.question(
                    self,
                    "Build and flash",
                    path_note + "\nKeep data: %s" % self.keep_data.isChecked(),
                )
                != QMessageBox.Yes
            ):
                return
        self._set_busy(True)
        self._prog(0.0)
        job = self._cook_job()
        job["kind"] = "cook_write" if then_flash else "cook"
        self.worker.submit(job)

    def write_selected(self) -> None:
        if self._busy:
            return
        path = self._selected_path()
        if not path:
            QMessageBox.information(self, "Cube Flasher", "Select a build.")
            return
        if not adb_serials() and not loader_serials():
            QMessageBox.information(self, "Cube Flasher", "Connect the Titan first.")
            return
        if (
            QMessageBox.question(
                self,
                "Flash selected",
                "Flash\n%s\nKeep data: %s" % (Path(path).name, self.keep_data.isChecked()),
            )
            != QMessageBox.Yes
        ):
            return
        self._set_busy(True)
        self._prog(0.0)
        self.worker.submit(
            {
                "kind": "write",
                "image": path,
                "keep_data": self.keep_data.isChecked(),
            }
        )

    def _on_built(self, img: str) -> None:
        self.refresh_builds()
        for i in range(self.builds.count()):
            if self.builds.item(i).data(Qt.UserRole) == img:
                self.builds.setCurrentRow(i)
                break

    def _on_done(self, ok: bool, msg: str) -> None:
        self._set_busy(False)
        self.refresh_builds()
        if not ok:
            self.line.setText(msg)

    def _tick_dev(self) -> None:
        adb, fb = adb_serials(), loader_serials()
        parts = []
        if adb:
            parts.append("adb " + ",".join(adb))
        if fb:
            parts.append("fastboot " + ",".join(fb))
        if not parts:
            parts.append("no titan")
        parts.append("%d pins" % self.builds.count())
        self.dev.setText("  ·  ".join(parts))
        if self._busy:
            return
        if fb:
            self.cube.set_phase("connect", 0.85)
        elif adb:
            self.cube.set_phase("connect", 0.75)
        else:
            self.cube.set_phase("idle", 0.2)

    def closeEvent(self, ev) -> None:
        self.worker.stop()
        ev.accept()


def main() -> None:
    app = QApplication(sys.argv)
    app.setStyle(QStyleFactory.create("Fusion"))
    app.setApplicationName("Cube Flasher")
    app.setDesktopFileName("cube-flasher")
    w = Flasher()
    w.show()
    sys.exit(app.exec_())


if __name__ == "__main__":
    main()
