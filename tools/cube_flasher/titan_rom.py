"""USB Titan ROM identity + changelog since last flash. No hardcoded serials."""
from __future__ import annotations

import json
import os
import subprocess
from datetime import datetime, timezone
from pathlib import Path

LEDGER_NAME = "cube_flasher_flashes.json"
STAMP_DEV = "/data/local/tmp/titan2_last_flash.json"
STAMP_ADB = "/data/adb/titan2_last_flash.json"
PROP_KEYS = (
    "ro.product.device",
    "ro.product.model",
    "ro.lineage.version",
    "ro.lineage.display.version",
    "ro.lineage.releasetype",
    "ro.lineage.maintainer",
    "ro.build.date",
    "ro.build.date.utc",
    "ro.build.version.release",
    "ro.build.version.security_patch",
)


def parse_flash_ts(ts: str) -> datetime | None:
    raw = (ts or "").strip()
    if not raw:
        return None
    for fmt in ("%Y%m%dT%H%M%SZ", "%Y-%m-%dT%H:%M:%SZ", "%Y-%m-%d %H:%M:%S"):
        try:
            return datetime.strptime(raw, fmt).replace(tzinfo=timezone.utc)
        except ValueError:
            continue
    return None


def fmt_ts(ts: str) -> str:
    dt = parse_flash_ts(ts)
    if not dt:
        return ts or "?"
    return dt.strftime("%Y-%m-%d %H:%M UTC")


def parse_receipt_text(text: str) -> dict:
    rec = {"ts": "", "pin": "", "super": "", "wipe": ""}
    for line in (text or "").splitlines():
        line = line.strip()
        if line.startswith("# FLASH_DONE"):
            rec["ts"] = line.split()[-1]
        elif line.startswith("super="):
            rec["super"] = line.split("=", 1)[1].strip()
            rec["pin"] = Path(rec["super"]).name
        elif line.startswith("wipe="):
            rec["wipe"] = line.split("=", 1)[1].strip()
        elif line.startswith("atlasos="):
            rec["atlasos"] = line.split("=", 1)[1].strip()
        elif line.startswith("titanus2="):
            rec["titanus2"] = line.split("=", 1)[1].strip()
    return rec


def latest_receipt(rec_dir: Path) -> dict:
    if not rec_dir.is_dir():
        return {}
    files = sorted(rec_dir.glob("FLASH_DONE_*.md"))
    if not files:
        return {}
    p = files[-1]
    rec = parse_receipt_text(p.read_text(errors="replace"))
    rec["path"] = str(p)
    if not rec.get("ts"):
        rec["ts"] = p.stem.replace("FLASH_DONE_", "")
    return rec


def git_head(repo: Path) -> str:
    if not (repo / ".git").exists() and not (repo / ".git").is_file():
        return ""
    try:
        out = subprocess.check_output(
            ["git", "-C", str(repo), "rev-parse", "--short", "HEAD"],
            text=True,
            timeout=5,
        )
        return out.strip()
    except Exception:
        return ""


def git_log_range(repo: Path, since_sha: str = "", since_ts: str = "", limit: int = 24) -> list[str]:
    if not repo.is_dir():
        return []
    cmd = ["git", "-C", str(repo), "log", "--oneline", "--no-decorate", "-n", str(limit)]
    if since_sha:
        cmd.append("%s..HEAD" % since_sha)
    elif since_ts:
        dt = parse_flash_ts(since_ts)
        if dt:
            cmd.append("--since=%s" % dt.strftime("%Y-%m-%d %H:%M:%S UTC"))
        else:
            return []
    else:
        return []
    try:
        out = subprocess.check_output(cmd, text=True, timeout=8)
    except Exception:
        return []
    lines = [ln.strip() for ln in out.splitlines() if ln.strip()]
    return lines


def newer_pins(out_dir: Path, since_ts: str) -> list[str]:
    dt = parse_flash_ts(since_ts)
    if not dt or not out_dir.is_dir():
        return []
    cut = dt.timestamp()
    names = []
    for p in out_dir.glob("*super*.img"):
        try:
            if p.stat().st_mtime > cut + 2:
                names.append(p.name)
        except OSError:
            continue
    names.sort(reverse=True)
    return names[:8]


def load_ledger(path: Path) -> list[dict]:
    if not path.is_file():
        return []
    try:
        data = json.loads(path.read_text())
    except Exception:
        return []
    return data if isinstance(data, list) else []


def save_ledger(path: Path, rows: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(rows[-40:], indent=2) + "\n")


def record_flash(path: Path, rec: dict) -> None:
    rows = load_ledger(path)
    rows.append(rec)
    save_ledger(path, rows)


def last_flash_for(serial: str, ledger_path: Path, rec_dir: Path) -> dict:
    rows = load_ledger(ledger_path)
    if serial:
        for rec in reversed(rows):
            if rec.get("serial") == serial:
                return rec
    if rows:
        return rows[-1]
    return latest_receipt(rec_dir)


def parse_prop_blob(blob: str) -> dict:
    props: dict[str, str] = {}
    for line in (blob or "").splitlines():
        if "=" not in line:
            continue
        k, v = line.split("=", 1)
        props[k.strip()] = v.strip()
    return props


def format_rom_summary(props: dict, flash: dict | None = None) -> str:
    dev = props.get("ro.product.device") or props.get("ro.product.model") or "Titan"
    ver = props.get("ro.lineage.display.version") or props.get("ro.lineage.version") or "ROM unread"
    bits = [dev, ver]
    if flash and flash.get("pin"):
        bits.append("last " + flash["pin"][:42])
    return " u00b7 ".join(bits)


def format_rom_report(props: dict, flash: dict, commits: list[str], pins: list[str]) -> str:
    lines = []
    if not props:
        lines.append("No USB adb u2014 plug Titan to read the ROM.")
    else:
        lines.append(
            "%s  %s"
            % (
                props.get("ro.product.model") or props.get("ro.product.device") or "device",
                props.get("ro.lineage.display.version") or props.get("ro.lineage.version") or "",
            )
        )
        rel = props.get("ro.build.version.release") or ""
        patch = props.get("ro.build.version.security_patch") or ""
        built = props.get("ro.build.date") or ""
        extra = "  ".join(x for x in ("Android " + rel if rel else "", "patch " + patch if patch else "", built) if x)
        if extra:
            lines.append(extra)
        kind = props.get("ro.lineage.releasetype") or ""
        who = props.get("ro.lineage.maintainer") or ""
        if kind or who:
            lines.append("  ".join(x for x in (kind, who) if x))
    lines.append("")
    if flash and (flash.get("pin") or flash.get("ts")):
        wipe = flash.get("wipe")
        keep = "KEEP_DATA" if str(wipe) in ("0", "", "None") else "wipe"
        lines.append("Last flash  %s  %s" % (fmt_ts(flash.get("ts") or ""), keep))
        if flash.get("pin"):
            lines.append(flash["pin"])
        sha = flash.get("atlasos") or ""
        if sha:
            lines.append("atlasos @ %s" % sha)
    else:
        lines.append("Last flash  unknown (no receipt on this host)")
    lines.append("")
    if commits:
        lines.append("Updates since last flash (%d)" % len(commits))
        lines.extend("  " + c for c in commits)
    else:
        if flash:
            lines.append("No AtlasOS commits since last flash.")
        else:
            lines.append("No baseline u2014 flash once so updates can be compared.")
    if pins:
        lines.append("")
        lines.append("Newer pins on disk")
        lines.extend("  " + n for n in pins)
    return "\n".join(lines).rstrip() + "\n"
