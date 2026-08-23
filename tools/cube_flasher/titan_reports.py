"""Pull user-authored Titan Controls reports (selected logs + comments + shots)."""
from __future__ import annotations

import json
import subprocess
from pathlib import Path

REMOTE_DIRS = (
    "/data/misc/titan2/reports",
    "/data/local/tmp/titan2_reports",
    "/data/data/com.titanus2.controls/files/reports",
)

LOG_CMDS = {
    "crash": "logcat -d -b crash -t 40",
    "fm": "logcat -d -t 120 -s TitanFm:D TitanFm:I TitanFm:E AndroidRuntime:E",
    "audio": "logcat -d -t 120 | grep -iE 'usb|analog|WIRED_HEAD|titan2-analog|AudioFlinger'",
    "controls": "logcat -d -t 80",
}


def pull_reports(serial: str, adb: str, env: dict, dest: Path) -> list[dict]:
    dest.mkdir(parents=True, exist_ok=True)
    got: list[dict] = []
    for remote in REMOTE_DIRS:
        try:
            subprocess.run(
                [adb, "-s", serial, "pull", remote, str(dest)],
                timeout=20,
                env=env,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
        except Exception:
            continue
    for meta in dest.rglob("report.json"):
        try:
            obj = json.loads(meta.read_text())
        except Exception:
            continue
        if not isinstance(obj, dict):
            continue
        obj["_dir"] = str(meta.parent)
        got.append(obj)
    return got


def fill_selected_logs(serial: str, adb_run, report: dict) -> None:
    """Pull only log keys the user checked. Fresh buffers at USB collect time."""
    keys = report.get("logs") or []
    if not isinstance(keys, list):
        return
    folder = Path(report.get("_dir") or "")
    logdir = folder / "logs" if folder.is_dir() else None
    if logdir:
        logdir.mkdir(exist_ok=True)
    blobs = []
    for key in keys:
        cmd = LOG_CMDS.get(str(key))
        if not cmd:
            continue
        text = adb_run(serial, cmd) or ""
        if logdir:
            (logdir / ("%s.txt" % key)).write_text(text[-8000:])
        blobs.append("%s: %s" % (key, text[-1200:]))
    report["log_excerpt"] = "\n".join(blobs)[-4000:]


def reports_as_findings(reports: list[dict]) -> list[dict]:
    out = []
    for r in reports:
        rid = str(r.get("id") or "report").replace(" ", "")[:32]
        kind = r.get("kind") or "bug"
        title = (r.get("title") or rid)[:120]
        comment = (r.get("comment") or "")[:800]
        out.append(
            {
                "id": "report-%s" % rid.lower(),
                "title": title,
                "detail": comment or (r.get("log_excerpt") or "")[:400],
                "repo": "atlasos",
                "severity": "feature" if kind == "feature" else "bug",
                "comment": comment,
                "shots": r.get("shots") or [],
            }
        )
    return out
