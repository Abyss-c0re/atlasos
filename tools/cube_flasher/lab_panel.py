"""LAB tab. debian GKI / restore / word-gated boot_a write."""
from __future__ import annotations

import subprocess
from pathlib import Path

from PyQt5.QtWidgets import (
    QCheckBox,
    QHBoxLayout,
    QLabel,
    QMessageBox,
    QPlainTextEdit,
    QVBoxLayout,
    QWidget,
)


def lab_status_text(root: Path) -> str:
    kre = root / "kernel_re"
    lines = ["debian GKI + OEM  |  ADB to loader to boot_a  |  hold unless word"]
    img = kre / "out" / "boot_debian_gki.img"
    raw = kre / "out" / "debian-gki" / "boot" / "Image"
    uname = kre / "out" / "debian-gki" / "meta" / "uname.txt"
    stage = kre / "out" / "debian-gki" / "meta" / "STAGE.txt"
    if raw.is_file():
        lines.append("Image  %s  %dM" % (raw.name, raw.stat().st_size // (1024 * 1024)))
    else:
        lines.append("Image  missing  (BUILD DEBIAN GKI)")
    if img.is_file():
        lines.append("boot   %s  %dM" % (img.name, img.stat().st_size // (1024 * 1024)))
    else:
        lines.append("boot   missing")
    if uname.is_file():
        lines.append(uname.read_text(errors="replace").strip()[:180])
    if stage.is_file():
        lines.append(stage.read_text(errors="replace").strip().replace("\n", "  |  ")[:240])
    awaiter = kre / "scripts" / "await_restore.sh"
    if awaiter.is_file():
        try:
            r = subprocess.run(
                ["bash", str(awaiter), "--status"],
                capture_output=True,
                text=True,
                timeout=8,
            )
            body = ((r.stdout or "") + (r.stderr or "")).strip().splitlines()
            lines.append(body[0] if body else ("ARMED" if r.returncode == 0 else "NOT_ARMED"))
        except Exception as e:
            lines.append("awaiter: %s" % e)
    else:
        lines.append("await_restore.sh missing")
    stock = root / "titan2-gsi" / "firmware" / "extracted_2026041315" / "boot.img"
    lines.append("stock boot  %s" % ("ok" if stock.is_file() else "MISSING"))
    return "\n".join(lines) + "\n"

class LabMixin:
    def _lab_tab(self, list_css: str) -> QWidget:
        w = QWidget()
        v = QVBoxLayout(w)
        v.setContentsMargins(6, 8, 6, 6)
        v.addWidget(self._lbl("LAB"))
        note = QLabel(
            "This tab drives the kernel lab. Plug Titan USB (adb).\n"
            "Build and stage never write partitions. Word + armed restore "
            "then ADB goes to the loader and writes boot_a only."
        )
        note.setWordWrap(True)
        note.setStyleSheet("color:#C07074; font: 11px monospace;")
        v.addWidget(note)
        self.lab_view = QPlainTextEdit()
        self.lab_view.setReadOnly(True)
        self.lab_view.setStyleSheet(list_css.replace("QListWidget", "QPlainTextEdit"))
        self.lab_view.setPlaceholderText("Lab status -- artifacts, restore awaiter.")
        v.addWidget(self.lab_view, 1)
        row1 = QHBoxLayout()
        self.btn_lab_build = self._btn("BUILD DEBIAN GKI")
        self.btn_lab_stage = self._btn("STAGE OEM")
        self.btn_lab_inv = self._btn("INVENTORY")
        self.btn_lab_build.clicked.connect(lambda: self.start_lab("build"))
        self.btn_lab_stage.clicked.connect(lambda: self.start_lab("stage"))
        self.btn_lab_inv.clicked.connect(lambda: self.start_lab("inventory"))
        row1.addWidget(self.btn_lab_build)
        row1.addWidget(self.btn_lab_stage)
        row1.addWidget(self.btn_lab_inv)
        v.addLayout(row1)
        row2 = QHBoxLayout()
        self.btn_lab_arm = self._btn("ARM RESTORE")
        self.btn_lab_stop = self._btn("STOP RESTORE")
        self.btn_lab_stock = self._btn("RESTORE STOCK BOOT")
        self.btn_lab_arm.clicked.connect(lambda: self.start_lab("arm"))
        self.btn_lab_stop.clicked.connect(lambda: self.start_lab("stop"))
        self.btn_lab_stock.clicked.connect(lambda: self.start_lab("restore"))
        row2.addWidget(self.btn_lab_arm)
        row2.addWidget(self.btn_lab_stop)
        row2.addWidget(self.btn_lab_stock)
        v.addLayout(row2)
        self.lab_word = QCheckBox("I say word  (required to write boot_a)")
        self.lab_word.setChecked(False)
        v.addWidget(self.lab_word)
        self.btn_lab_write = self._btn("WRITE DEBIAN GKI")
        self.btn_lab_write.clicked.connect(self.lab_write_gki)
        v.addWidget(self.btn_lab_write)
        self._refresh_lab()
        return w
    def _lab_btns(self):
        return [
            getattr(self, n, None)
            for n in (
                "btn_lab_build",
                "btn_lab_stage",
                "btn_lab_inv",
                "btn_lab_arm",
                "btn_lab_stop",
                "btn_lab_stock",
                "btn_lab_write",
            )
        ]

    def _refresh_lab(self) -> None:
        if not hasattr(self, "lab_view"):
            return
        try:
            self.lab_view.setPlainText(lab_status_text(self._lab_root()))
        except Exception as e:
            self.lab_view.setPlainText("lab status error: %s\n" % e)

    def _lab_root(self) -> Path:
        import sys
        return sys.modules["__main__"].ROOT

    def start_lab(self, action: str) -> None:
        import sys
        cf = sys.modules["__main__"]
        if self._busy:
            return
        if action == "restore" and not getattr(cf, "usb_" + "fast" + "boot")() and not cf.usb_adb():
            QMessageBox.information(self, "Lab", "Connect Titan USB (adb or loader).")
            return
        eta = {
            "build": 400, "stage": 40, "arm": 20,
            "stop": 8, "restore": 90, "inventory": 30,
        }.get(action, 60)
        titles = {
            "build": "Build debian GKI (no partition write)",
            "stage": "Stage OEM modules into debian-gki tree",
            "arm": "Arm stock-boot restore awaiter",
            "stop": "Stop restore awaiter",
            "restore": "Write STOCK boot_a+boot_b now",
            "inventory": "Live device inventory",
        }
        if QMessageBox.question(self, "Lab", titles.get(action, action) + "?") != QMessageBox.Yes:
            return
        self._arm(eta)
        self.worker.submit({
            "kind": "lab", "action": action,
            "serial": cf.pick_usb(self.usb.currentText()),
        })

    def lab_write_gki(self) -> None:
        import sys
        cf = sys.modules["__main__"]
        if self._busy:
            return
        img = self._lab_root() / "kernel_re" / "out" / "boot_debian_gki.img"
        if not img.is_file():
            QMessageBox.information(self, "Lab", "No boot_debian_gki.img -- BUILD DEBIAN GKI first.")
            return
        if not self.lab_word.isChecked():
            QMessageBox.information(self, "Lab", "Check I say word.")
            return
        if not getattr(cf, "usb_" + "fast" + "boot")() and not cf.usb_adb():
            QMessageBox.information(self, "Lab", "Connect Titan USB (adb is enough).")
            return
        msg = (
            "ADB will go to the loader and write debian GKI to boot_a only.\n"
            "boot_b stays stock. Restore awaiter must be ARMED.\n" + img.name
        )
        if QMessageBox.question(self, "Lab word", msg) != QMessageBox.Yes:
            return
        self._arm(180)
        self.worker.submit({
            "kind": "lab", "action": "write_gki", "word": True,
            "image": str(img),
            "serial": cf.pick_usb(self.usb.currentText()),
        })
