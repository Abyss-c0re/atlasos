"""Kick or resume a grok session for the selected Titan lab experiment."""
from __future__ import annotations

import json
import os
import subprocess
import urllib.parse
from datetime import datetime, timezone
from pathlib import Path

from lab_store import lab_dir


LAW = (
    "Titan 2 kernel R&D. Stay in kernel_re and kernel_re/lab. "
    "Do not merge into atlasos main. Do not write boot unless the commander says word. "
    "Record outcomes in kernel_re/lab/experiments. Nanobots fill gaps only."
)


def grok_bin() -> str:
    for p in (
        Path.home() / ".grok" / "bin" / "grok",
        Path.home() / ".local" / "bin" / "grok",
    ):
        if p.is_file():
            return str(p)
    return "grok"


def encode_cwd(cwd: Path) -> str:
    return urllib.parse.quote(str(cwd), safe="")


def session_root(cwd: Path) -> Path:
    return Path.home() / ".grok" / "sessions" / encode_cwd(cwd)


def newest_sid(cwd: Path) -> str:
    root = session_root(cwd)
    if not root.is_dir():
        return ""
    kids = [p for p in root.iterdir() if p.is_dir() and p.name.startswith("01")]
    if not kids:
        return ""
    kids.sort(key=lambda p: p.stat().st_mtime, reverse=True)
    return kids[0].name


def lab_cwds(root: Path) -> list[Path]:
    return [
        root / "kernel_re",
        root,
    ]


def pick_sid(root: Path, bound: str = "") -> tuple[str, Path]:
    if bound:
        for cwd in lab_cwds(root):
            if (session_root(cwd) / bound).is_dir():
                return bound, cwd
    for cwd in lab_cwds(root):
        sid = newest_sid(cwd)
        if sid:
            return sid, cwd
    return "", root / "kernel_re"


def append_chat(root: Path, eid: str, role: str, text: str) -> None:
    p = lab_dir(root) / "chat" / ("%s.jsonl" % (eid or "loose"))
    rec = {
        "ts": datetime.now(timezone.utc).strftime("%Y%m%dT%H%MZ"),
        "role": role,
        "text": text[:4000],
    }
    with p.open("a") as f:
        f.write(json.dumps(rec) + "\n")


def load_chat(root: Path, eid: str, limit: int = 80) -> list[dict]:
    p = lab_dir(root) / "chat" / ("%s.jsonl" % (eid or "loose"))
    if not p.is_file():
        return []
    rows = []
    for line in p.read_text().splitlines():
        try:
            rows.append(json.loads(line))
        except Exception:
            continue
    return rows[-limit:]


def _prompt_file(root: Path, eid: str, title: str, user: str) -> Path:
    p = lab_dir(root) / "chat" / "last_prompt.txt"
    body = (
        LAW
        + "\n\nExperiment: %s — %s\n\n"
        "The operator in the Cube LAB tab says:\n\n%s\n"
        % (eid or "(none)", title or "", user.strip())
    )
    p.write_text(body)
    return p


def kick(root: Path, eid: str, title: str, user: str, resume: bool) -> str:
    """Start or resume grok. Returns a one-line status. Never writes partitions."""
    append_chat(root, eid, "user", user)
    cwd = root / "kernel_re"
    bound = ""
    try:
        from lab_store import load

        exp = load(root, eid) if eid else None
        if exp:
            bound = exp.get("grok_session") or ""
    except Exception:
        exp = None
    sid, cwd = pick_sid(root, bound)
    pf = _prompt_file(root, eid, title, user)
    env = os.environ.copy()
    cmd = [grok_bin()]
    if resume and sid:
        cmd += ["--resume", sid]
    cmd += ["--cwd", str(cwd), "--yolo", "--verbatim", "--prompt-file", str(pf)]
    try:
        proc = subprocess.Popen(
            cmd,
            cwd=str(cwd),
            env=env,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            start_new_session=True,
        )
    except Exception as e:
        msg = "kick failed: %s" % e
        append_chat(root, eid, "system", msg)
        return msg
    if not sid:
        sid = "new-pid-%s" % proc.pid
    if eid:
        try:
            from lab_store import bind_session

            bind_session(root, eid, sid)
        except Exception:
            pass
    msg = "kicked %s cwd=%s pid=%s" % (sid, cwd.name, proc.pid)
    append_chat(root, eid, "system", msg)
    return msg
