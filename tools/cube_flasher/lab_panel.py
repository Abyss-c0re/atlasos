"""LAB tab: run, R&D sessions, chat. Titan kernel_re/lab only."""
from __future__ import annotations

import subprocess
from pathlib import Path

from PyQt5.QtCore import Qt
from PyQt5.QtWidgets import (
    QAbstractItemView,
    QCheckBox,
    QComboBox,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QListWidget,
    QListWidgetItem,
    QMessageBox,
    QPlainTextEdit,
    QSplitter,
    QTabWidget,
    QVBoxLayout,
    QWidget,
)

from lab_store import (
    OUTCOMES,
    add_action,
    conflicts,
    file_sha,
    list_exps,
    new_exp,
    save,
    set_outcome,
)
from lab_chat import kick, load_chat


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
        v.setContentsMargins(4, 6, 4, 4)
        inner = QTabWidget()
        inner.addTab(self._lab_run_tab(list_css), "RUN")
        inner.addTab(self._lab_rd_tab(list_css), "R&D")
        inner.addTab(self._lab_chat_tab(list_css), "CHAT")
        v.addWidget(inner, 1)
        self._refresh_lab()
        return w

    def _lab_run_tab(self, list_css: str) -> QWidget:
        w = QWidget()
        v = QVBoxLayout(w)
        v.setContentsMargins(6, 8, 6, 6)
        v.addWidget(self._lbl("LAB"))
        note = QLabel(
            "Plug Titan USB (adb). Build and stage never write partitions.\n"
            "Word + armed restore: ADB to the loader, boot_a only. R&D stays off main."
        )
        note.setWordWrap(True)
        note.setStyleSheet("color:#C07074; font: 11px monospace;")
        v.addWidget(note)
        self.lab_view = QPlainTextEdit()
        self.lab_view.setReadOnly(True)
        self.lab_view.setStyleSheet(list_css.replace("QListWidget", "QPlainTextEdit"))
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
        return w

    def _lab_rd_tab(self, list_css: str) -> QWidget:
        w = QWidget()
        v = QVBoxLayout(w)
        v.setContentsMargins(6, 8, 6, 6)
        v.addWidget(self._lbl("SESSIONS"))
        hint = QLabel("Off main. Combine and conflict here. Promote by hand only.")
        hint.setStyleSheet("color:#C07074; font: 11px monospace;")
        v.addWidget(hint)
        split = QSplitter(Qt.Vertical)
        self.lab_exps = QListWidget()
        self.lab_exps.setStyleSheet(list_css)
        self.lab_exps.setSelectionMode(QAbstractItemView.SingleSelection)
        self.lab_exps.currentItemChanged.connect(lambda *_: self._lab_show_exp())
        split.addWidget(self.lab_exps)
        self.lab_exp_view = QPlainTextEdit()
        self.lab_exp_view.setReadOnly(True)
        self.lab_exp_view.setStyleSheet(list_css.replace("QListWidget", "QPlainTextEdit"))
        split.addWidget(self.lab_exp_view)
        v.addWidget(split, 1)
        row = QHBoxLayout()
        self.btn_lab_new = self._btn("NEW")
        self.btn_lab_new.clicked.connect(self.lab_new_exp)
        self.lab_outcome = QComboBox()
        self.lab_outcome.addItems(list(OUTCOMES))
        self.btn_lab_out = self._btn("SET OUTCOME")
        self.btn_lab_out.clicked.connect(self.lab_set_outcome)
        self.btn_lab_mix = self._btn("COMBINE / CONFLICTS")
        self.btn_lab_mix.clicked.connect(self.lab_show_conflicts)
        row.addWidget(self.btn_lab_new)
        row.addWidget(self.lab_outcome, 1)
        row.addWidget(self.btn_lab_out)
        row.addWidget(self.btn_lab_mix)
        v.addLayout(row)
        self.lab_notes = QPlainTextEdit()
        self.lab_notes.setPlaceholderText("notes for selected session — saved with outcome")
        self.lab_notes.setFixedHeight(72)
        self.lab_notes.setStyleSheet(list_css.replace("QListWidget", "QPlainTextEdit"))
        v.addWidget(self.lab_notes)
        return w

    def _lab_chat_tab(self, list_css: str) -> QWidget:
        w = QWidget()
        v = QVBoxLayout(w)
        v.setContentsMargins(6, 8, 6, 6)
        v.addWidget(self._lbl("CHAT"))
        hint = QLabel("Resumes the bound grok session, or starts one in kernel_re. Nanobots fill gaps.")
        hint.setWordWrap(True)
        hint.setStyleSheet("color:#C07074; font: 11px monospace;")
        v.addWidget(hint)
        self.lab_chat_view = QPlainTextEdit()
        self.lab_chat_view.setReadOnly(True)
        self.lab_chat_view.setStyleSheet(list_css.replace("QListWidget", "QPlainTextEdit"))
        v.addWidget(self.lab_chat_view, 1)
        self.lab_chat_line = QLineEdit()
        self.lab_chat_line.setPlaceholderText("talk to the lab session…")
        self.lab_chat_line.setStyleSheet(
            "QLineEdit{background:#120204;color:#FFD0D2;border:1px solid #5A1014;padding:4px;font:11px monospace;}"
        )
        self.lab_chat_line.returnPressed.connect(self.lab_chat_send)
        v.addWidget(self.lab_chat_line)
        row = QHBoxLayout()
        self.btn_lab_send = self._btn("SEND / RESUME")
        self.btn_lab_newchat = self._btn("NEW SESSION")
        self.btn_lab_send.clicked.connect(self.lab_chat_send)
        self.btn_lab_newchat.clicked.connect(lambda: self.lab_chat_send(new=True))
        row.addWidget(self.btn_lab_send)
        row.addWidget(self.btn_lab_newchat)
        v.addLayout(row)
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
                "btn_lab_new",
                "btn_lab_out",
                "btn_lab_mix",
                "btn_lab_send",
                "btn_lab_newchat",
            )
        ]

    def _lab_root(self) -> Path:
        import sys

        return sys.modules["__main__"].ROOT

    def _lab_eid(self) -> str:
        it = getattr(self, "lab_exps", None)
        if it is None:
            return ""
        cur = it.currentItem()
        return (cur.data(Qt.UserRole) if cur else "") or ""

    def _refresh_lab(self) -> None:
        if hasattr(self, "lab_view"):
            try:
                self.lab_view.setPlainText(lab_status_text(self._lab_root()))
            except Exception as e:
                self.lab_view.setPlainText("lab status error: %s\n" % e)
        self._lab_fill_exps()
        self._lab_fill_chat()

    def _lab_fill_exps(self) -> None:
        if not hasattr(self, "lab_exps"):
            return
        keep = self._lab_eid()
        self.lab_exps.blockSignals(True)
        self.lab_exps.clear()
        pick = 0
        for i, e in enumerate(list_exps(self._lab_root())):
            label = "%s  [%s/%s]  %s" % (
                e.get("id", "")[:18],
                e.get("status", "?"),
                e.get("outcome", "?"),
                e.get("title", ""),
            )
            it = QListWidgetItem(label)
            it.setData(Qt.UserRole, e.get("id") or "")
            self.lab_exps.addItem(it)
            if e.get("id") == keep:
                pick = i
        if self.lab_exps.count():
            self.lab_exps.setCurrentRow(pick)
        self.lab_exps.blockSignals(False)
        self._lab_show_exp()

    def _lab_show_exp(self) -> None:
        if not hasattr(self, "lab_exp_view"):
            return
        eid = self._lab_eid()
        rows = {e["id"]: e for e in list_exps(self._lab_root())}
        e = rows.get(eid)
        if not e:
            self.lab_exp_view.setPlainText("no session selected\n")
            return
        acts = e.get("actions") or []
        tail = "\n".join(
            "  %s  %s  %s" % (a.get("ts", ""), a.get("action", ""), a.get("detail", ""))
            for a in acts[-12:]
        )
        self.lab_exp_view.setPlainText(
            "id       %s\ntitle    %s\nstatus   %s\noutcome  %s\nuname    %s\n"
            "artifact %s\nsha      %s\ngrok     %s\npromote  %s\n\nnotes\n%s\n\nactions\n%s\n"
            % (
                e.get("id"),
                e.get("title"),
                e.get("status"),
                e.get("outcome"),
                e.get("uname") or "-",
                e.get("artifact") or "-",
                e.get("sha") or "-",
                e.get("grok_session") or "-",
                e.get("promote"),
                e.get("notes") or "-",
                tail or "  (none)",
            )
        )
        if hasattr(self, "lab_notes") and not self.lab_notes.hasFocus():
            self.lab_notes.setPlainText(e.get("notes") or "")
        oc = e.get("outcome") or "pending"
        if hasattr(self, "lab_outcome"):
            i = self.lab_outcome.findText(oc)
            if i >= 0:
                self.lab_outcome.setCurrentIndex(i)
        self._lab_fill_chat()

    def lab_new_exp(self) -> None:
        title, ok = "untitled", True
        from PyQt5.QtWidgets import QInputDialog

        title, ok = QInputDialog.getText(self, "Lab", "Session title")
        if not ok or not str(title).strip():
            return
        e = new_exp(self._lab_root(), str(title).strip())
        self._refresh_lab()
        for i in range(self.lab_exps.count()):
            if self.lab_exps.item(i).data(Qt.UserRole) == e["id"]:
                self.lab_exps.setCurrentRow(i)
                break

    def lab_set_outcome(self) -> None:
        eid = self._lab_eid()
        if not eid:
            QMessageBox.information(self, "Lab", "Select a session.")
            return
        notes = self.lab_notes.toPlainText() if hasattr(self, "lab_notes") else ""
        set_outcome(self._lab_root(), eid, self.lab_outcome.currentText(), notes)
        self._refresh_lab()

    def lab_show_conflicts(self) -> None:
        lines = conflicts(self._lab_root())
        if hasattr(self, "lab_exp_view"):
            self.lab_exp_view.setPlainText("combine / conflicts\n\n" + "\n".join("- " + x for x in lines) + "\n")

    def _lab_ensure_exp(self, title: str) -> str:
        eid = self._lab_eid()
        if eid:
            return eid
        e = new_exp(self._lab_root(), title)
        self._lab_fill_exps()
        return e["id"]

    def lab_job_done(self, ok: bool, msg: str) -> None:
        eid = getattr(self, "_lab_pending_eid", "") or self._lab_eid()
        action = getattr(self, "_lab_pending_action", "")
        if not eid or not action:
            return
        root = self._lab_root()
        add_action(root, eid, action, msg or ("ok" if ok else "fail"))
        if action == "build" and ok:
            img = root / "kernel_re" / "out" / "boot_debian_gki.img"
            uname_p = root / "kernel_re" / "out" / "debian-gki" / "meta" / "uname.txt"
            exp_rows = {e["id"]: e for e in list_exps(root)}
            e = exp_rows.get(eid)
            if e:
                e["artifact"] = "boot_debian_gki.img"
                e["sha"] = file_sha(img)
                if uname_p.is_file():
                    e["uname"] = uname_p.read_text(errors="replace").strip()[:180]
                e["outcome"] = "built"
                save(root, e)
        if action == "write_gki":
            set_outcome(root, eid, "pending" if ok else "refused", None)
        self._lab_pending_eid = ""
        self._lab_pending_action = ""
        self._refresh_lab()

    def _lab_fill_chat(self) -> None:
        if not hasattr(self, "lab_chat_view"):
            return
        eid = self._lab_eid()
        rows = load_chat(self._lab_root(), eid)
        lines = []
        for r in rows:
            lines.append("%s  %s\n%s" % (r.get("ts", ""), r.get("role", ""), r.get("text", "")))
        self.lab_chat_view.setPlainText("\n\n".join(lines) if lines else "(no chat yet)\n")
        sb = self.lab_chat_view.verticalScrollBar()
        sb.setValue(sb.maximum())

    def lab_chat_send(self, new: bool = False) -> None:
        text = ""
        if hasattr(self, "lab_chat_line"):
            text = self.lab_chat_line.text().strip()
        if not text:
            return
        eid = self._lab_ensure_exp("chat")
        rows = {e["id"]: e for e in list_exps(self._lab_root())}
        title = (rows.get(eid) or {}).get("title") or ""
        self.lab_chat_line.clear()
        st = kick(self._lab_root(), eid, title, text, resume=not new)
        self.line.setText(st)
        self._lab_fill_chat()
        self._lab_fill_exps()

    def start_lab(self, action: str) -> None:
        import sys

        cf = sys.modules["__main__"]
        if self._busy:
            return
        if action == "restore" and not getattr(cf, "usb_" + "fast" + "boot")() and not cf.usb_adb():
            QMessageBox.information(self, "Lab", "Connect Titan USB (adb or loader).")
            return
        eta = {
            "build": 400,
            "stage": 40,
            "arm": 20,
            "stop": 8,
            "restore": 90,
            "inventory": 30,
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
        eid = self._lab_ensure_exp(action)
        add_action(self._lab_root(), eid, action, "queued")
        self._lab_pending_eid = eid
        self._lab_pending_action = action
        self._arm(eta)
        self.worker.submit(
            {
                "kind": "lab",
                "action": action,
                "serial": cf.pick_usb(self.usb.currentText()),
            }
        )

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
        eid = self._lab_ensure_exp("debian GKI write")
        add_action(self._lab_root(), eid, "write_gki", "queued word")
        self._lab_pending_eid = eid
        self._lab_pending_action = "write_gki"
        self._arm(180)
        self.worker.submit(
            {
                "kind": "lab",
                "action": "write_gki",
                "word": True,
                "image": str(img),
                "serial": cf.pick_usb(self.usb.currentText()),
            }
        )
