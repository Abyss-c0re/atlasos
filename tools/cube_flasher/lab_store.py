"""Titan kernel R&D store. Lives under kernel_re/lab — not atlasos main."""
from __future__ import annotations

import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path

OUTCOMES = (
    "pending",
    "built",
    "refused",
    "boot_ok",
    "no_adb",
    "preloader",
    "restored",
    "aborted",
)

SEED = [
    {
        "id": "20260724-nothing-6168",
        "title": "Nothing 6.1.68 GKI",
        "status": "fail",
        "outcome": "preloader",
        "uname": "6.1.68-titanus2-re",
        "notes": "preloader loop. do not repeat major version mismatch.",
        "promote": False,
    },
    {
        "id": "20260724-gki145-localversion",
        "title": "AOSP 6.1.145 custom LOCALVERSION",
        "status": "fail",
        "outcome": "no_adb",
        "uname": "6.1.145-android14-11-titanus2",
        "notes": "came up without adb. stamp LOCALVERSION next time.",
        "promote": False,
    },
    {
        "id": "20260829-debian-gki-stamp",
        "title": "debian GKI 6.1.145 stamp match",
        "status": "open",
        "outcome": "pending",
        "uname": "6.1.145-android14-11-gbd17012cc2f7-ab14044926",
        "artifact": "boot_debian_gki.img",
        "notes": "Image built. not written. OEM 206+60 staged.",
        "promote": False,
    },
]


def lab_dir(root: Path) -> Path:
    d = root / "kernel_re" / "lab"
    (d / "experiments").mkdir(parents=True, exist_ok=True)
    (d / "chat").mkdir(parents=True, exist_ok=True)
    readme = d / "README.md"
    if not readme.is_file():
        readme.write_text(
            "# Titan kernel R&D (contained)\n\n"
            "This tree is the experiment log. It is **not** atlasos main.\n"
            "Promote only by hand after a green outcome.\n"
            "Chat and grok sessions bind here. Nanobots fill gaps.\n"
        )
    return d


def _now() -> str:
    return datetime.now(timezone.utc).strftime("%Y%m%dT%H%MZ")


def _exp_path(root: Path, eid: str) -> Path:
    return lab_dir(root) / "experiments" / ("%s.json" % eid)


def load(root: Path, eid: str) -> dict | None:
    p = _exp_path(root, eid)
    if not p.is_file():
        return None
    return json.loads(p.read_text())


def save(root: Path, exp: dict) -> dict:
    exp = dict(exp)
    exp.setdefault("id", "exp-" + _now())
    exp.setdefault("title", exp["id"])
    exp.setdefault("status", "open")
    exp.setdefault("outcome", "pending")
    exp.setdefault("created", _now())
    exp["updated"] = _now()
    exp.setdefault("actions", [])
    exp.setdefault("promote", False)
    exp.setdefault("grok_session", "")
    exp.setdefault("notes", "")
    p = _exp_path(root, exp["id"])
    p.write_text(json.dumps(exp, indent=2) + "\n")
    return exp


def list_exps(root: Path) -> list[dict]:
    lab_dir(root)
    seed_if_empty(root)
    rows = []
    for p in sorted((lab_dir(root) / "experiments").glob("*.json"), reverse=True):
        try:
            rows.append(json.loads(p.read_text()))
        except Exception:
            continue
    return rows


def seed_if_empty(root: Path) -> None:
    d = lab_dir(root) / "experiments"
    if any(d.glob("*.json")):
        return
    for raw in SEED:
        e = dict(raw)
        e.setdefault("created", e["id"][:8] + "T0000Z")
        e.setdefault("actions", [])
        save(root, e)


def new_exp(root: Path, title: str, **kw) -> dict:
    eid = _now() + "-" + "".join(c if c.isalnum() else "-" for c in title.lower())[:24].strip("-")
    exp = {"id": eid, "title": title, "status": "open", "outcome": "pending"}
    exp.update(kw)
    return save(root, exp)


def add_action(root: Path, eid: str, action: str, detail: str = "") -> dict | None:
    exp = load(root, eid)
    if not exp:
        return None
    exp.setdefault("actions", []).append(
        {"ts": _now(), "action": action, "detail": detail[:300]}
    )
    return save(root, exp)


def set_outcome(root: Path, eid: str, outcome: str, notes: str | None = None) -> dict | None:
    exp = load(root, eid)
    if not exp:
        return None
    if outcome not in OUTCOMES:
        outcome = "pending"
    exp["outcome"] = outcome
    if outcome in ("boot_ok",):
        exp["status"] = "pass"
    elif outcome in ("no_adb", "preloader", "aborted"):
        exp["status"] = "fail"
    elif outcome == "restored":
        exp["status"] = "fail"
    elif outcome == "built":
        exp["status"] = "open"
    if notes is not None:
        exp["notes"] = notes
    return save(root, exp)


def file_sha(path: Path) -> str:
    if not path.is_file():
        return ""
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()[:16]


def conflicts(root: Path) -> list[str]:
    rows = list_exps(root)
    out = []
    open_rows = [e for e in rows if e.get("status") == "open"]
    if len(open_rows) > 1:
        out.append(
            "several open rows: " + ", ".join(e["id"] for e in open_rows[:6])
        )
    by_art: dict[str, list[dict]] = {}
    by_un: dict[str, list[dict]] = {}
    for e in rows:
        a = e.get("artifact") or ""
        if a:
            by_art.setdefault(a, []).append(e)
        u = e.get("uname") or ""
        if u:
            by_un.setdefault(u, []).append(e)
    for art, group in by_art.items():
        shas = {e.get("sha") or "" for e in group if e.get("sha")}
        if len(shas) > 1:
            out.append("artifact %s has %d different shas" % (art, len(shas)))
    for u, group in by_un.items():
        oc = {e.get("outcome") for e in group}
        if len(oc) > 1 and oc - {"pending", "built"}:
            out.append("uname %s mixed outcomes: %s" % (u[:48], ",".join(sorted(oc))))
    green = [e for e in rows if e.get("outcome") == "boot_ok"]
    fail = [e for e in rows if e.get("status") == "fail"]
    if green and fail:
        out.append(
            "combine: %d green vs %d fail — do not merge fail LOCALVERSION into green"
            % (len(green), len(fail))
        )
    if not out:
        out.append("no conflicts. promote stays manual.")
    return out


def bind_session(root: Path, eid: str, sid: str) -> dict | None:
    exp = load(root, eid)
    if not exp:
        return None
    exp["grok_session"] = sid
    return save(root, exp)
