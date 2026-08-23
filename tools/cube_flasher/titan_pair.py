"""Pull Atlas-gated nanobot pair receipt from Titan. Token stays local 0600."""
from __future__ import annotations

import json
import os
import stat
import subprocess
from pathlib import Path

REMOTE_RECEIPTS = (
    "/data/local/tmp/nanobot_home/pair.json",
    "/data/local/tmp/titan2_nanobot_pair.json",
    "/data/misc/titan2/titan2_nanobot_pair.json",
)
REMOTE_TOKEN = "/data/local/tmp/nanobot_home/peer_token"


def _home() -> Path:
    return Path.home() / ".nanobot"


def pull_pair(serial: str, adb: str, env: dict) -> dict:
    """USB-pull pair receipt. Never print the peer token."""
    out: dict = {"ok": False, "serial": serial or ""}
    if not serial or not adb:
        out["error"] = "no usb"
        return out
    dest = _home()
    dest.mkdir(parents=True, exist_ok=True)
    raw = ""
    for remote in REMOTE_RECEIPTS:
        try:
            p = subprocess.run(
                [adb, "-s", serial, "shell", "cat", remote],
                timeout=8,
                env=env,
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
            )
        except Exception:
            continue
        text = (p.stdout or b"").decode("utf-8", "replace").strip()
        if text.startswith("{") and "schema" in text:
            raw = text
            break
    if not raw:
        out["error"] = "no receipt - tap Nanobot auth on device"
        return out
    try:
        rec = json.loads(raw)
    except Exception:
        out["error"] = "bad receipt"
        return out
    if not isinstance(rec, dict):
        out["error"] = "bad receipt"
        return out
    rec.pop("token", None)
    rec.pop("peer_token", None)
    rec["serial"] = serial
    host_meta = {
        "schema": "nanobot.pair.v1",
        "ok": bool(rec.get("ok")),
        "url": rec.get("url") or "",
        "host": rec.get("host") or "",
        "port": rec.get("port") or 8787,
        "token_fp": rec.get("token_fp") or "",
        "via": rec.get("via") or rec.get("receipt_via") or "",
        "ts": rec.get("ts") or rec.get("receipt_ts") or "",
        "client": rec.get("client") or "",
        "serial": serial,
    }
    (dest / "titan_pair.json").write_text(json.dumps(host_meta, indent=2) + "\n")
    tok = _pull_token(serial, adb, env)
    if tok:
        tf = dest / "titan_peer_token"
        tf.write_text(tok + "\n")
        os.chmod(tf, stat.S_IRUSR | stat.S_IWUSR)
        host_meta["token_local"] = True
    else:
        host_meta["token_local"] = False
    out.update(host_meta)
    out["ok"] = True
    return out


def _pull_token(serial: str, adb: str, env: dict) -> str:
    try:
        p = subprocess.run(
            [adb, "-s", serial, "shell", "cat", REMOTE_TOKEN],
            timeout=8,
            env=env,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
        )
    except Exception:
        return ""
    line = (p.stdout or b"").decode("utf-8", "replace").strip().splitlines()
    if not line:
        return ""
    t = line[0].strip()
    if t.startswith("token="):
        t = t[6:].strip()
    if len(t) < 8:
        return ""
    return t


def format_pair(meta: dict) -> str:
    if not meta:
        return "nanobot pair: none"
    if not meta.get("ok"):
        return "nanobot pair: " + (meta.get("error") or "not ready")
    bits = ["nanobot pair ready"]
    if meta.get("url"):
        bits.append(str(meta["url"]))
    if meta.get("via"):
        bits.append("via=" + str(meta["via"]))
    if meta.get("token_fp"):
        bits.append("fp=" + str(meta["token_fp"]))
    bits.append("token_local=" + ("yes" if meta.get("token_local") else "no"))
    return "\n".join(bits)
