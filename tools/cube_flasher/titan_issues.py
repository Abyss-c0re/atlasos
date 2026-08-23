"""Post sanitized Cube Flasher findings as GitHub issues. Uses bundled git for remotes."""
from __future__ import annotations

import json
import os
import re
import subprocess
import urllib.error
import urllib.request
from pathlib import Path

from titan_diag import redact

HERE = Path(__file__).resolve().parent
GITHUB = re.compile(r"github\.com[:/](?P<owner>[^/]+)/(?P<repo>[^/.]+)")


def git_bin() -> str:
    p = HERE / "git" / "git"
    return str(p) if p.is_file() else "git"


def git_env() -> dict:
    env = dict(os.environ)
    bindir = str(HERE / "git")
    if (HERE / "git" / "git").is_file():
        env["PATH"] = bindir + os.pathsep + env.get("PATH", "")
    return env


def origin_repo(path: Path) -> tuple[str, str]:
    """(owner, repo) from origin, or empty."""
    try:
        out = subprocess.check_output(
            [git_bin(), "-C", str(path), "remote", "get-url", "origin"],
            text=True,
            timeout=5,
            env=git_env(),
        ).strip()
    except Exception:
        return "", ""
    m = GITHUB.search(out.replace(".git", ""))
    if not m:
        return "", ""
    return m.group("owner"), m.group("repo")


def github_token() -> str:
    for k in ("GH_TOKEN", "GITHUB_TOKEN"):
        v = (os.environ.get(k) or "").strip()
        if v:
            return v
    try:
        out = subprocess.check_output(
            ["gh", "auth", "token"], text=True, timeout=5, env=os.environ
        )
        return out.strip()
    except Exception:
        return ""


def _api(method: str, url: str, token: str, payload: dict | None = None) -> tuple[int, dict | list | str]:
    data = None if payload is None else json.dumps(payload).encode()
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("User-Agent", "cube-flasher")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    if data is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            body = r.read().decode("utf-8", "replace")
            try:
                return r.status, json.loads(body)
            except Exception:
                return r.status, body
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, raw
    except Exception as e:
        return 0, {"error": str(e)}


def existing_fingerprints(owner: str, repo: str, token: str) -> set[str]:
    code, body = _api(
        "GET",
        "https://api.github.com/repos/%s/%s/issues?state=open&per_page=50" % (owner, repo),
        token,
    )
    found: set[str] = set()
    if code != 200 or not isinstance(body, list):
        return found
    rx = re.compile(r"cube-diag:([a-z0-9-]+)")
    for it in body:
        blob = "%s\n%s" % (it.get("title") or "", it.get("body") or "")
        found.update(rx.findall(blob))
    return found


def issue_body(finding: dict, snap: dict) -> str:
    return (
        "<!-- cube-diag:%s -->\n"
        "Auto-filed by Cube Flasher from a USB diagnostic. "
        "No user data (no contacts, media, accounts, serials).\n\n"
        "**id:** `%s`\n"
        "**device_tag:** `%s`\n"
        "**ROM:** %s / %s\n"
        "**detail:** %s\n"
        % (
            finding["id"],
            finding["id"],
            snap.get("device_tag") or "",
            snap.get("model") or snap.get("device") or "",
            snap.get("lineage") or "",
            redact(finding.get("detail") or ""),
        )
    )


def post_finding(
    finding: dict,
    snap: dict,
    atlasos: Path,
    seen: set[str] | None = None,
) -> str:
    """Create one issue. Returns url or skip reason."""
    owner, repo = origin_repo(atlasos)
    if not owner:
        return "no github origin"
    token = github_token()
    if not token:
        return "no github token (gh auth or GH_TOKEN)"
    fid = finding["id"]
    have = seen if seen is not None else existing_fingerprints(owner, repo, token)
    if fid in have:
        return "already open %s" % fid
    payload = {
        "title": "[cube-diag:%s] %s" % (fid, finding["title"]),
        "body": issue_body(finding, snap),
    }
    code, body = _api(
        "POST",
        "https://api.github.com/repos/%s/%s/issues" % (owner, repo),
        token,
        payload,
    )
    if code in (200, 201) and isinstance(body, dict) and body.get("html_url"):
        if seen is not None:
            seen.add(fid)
        return str(body["html_url"])
    return "post failed %s %s" % (code, body if isinstance(body, str) else body.get("message", body))


def nanobot_classify(snap: dict, findings: list[dict]) -> list[dict]:
    """Ask host nanobot to confirm/extend findings. Sanitized payload only."""
    from pathlib import Path as P

    home = P.home() / ".nanobot"
    base = (os.environ.get("NANOBOT_PEER_URL") or "").strip()
    if not base and (home / "peer_url").is_file():
        base = (home / "peer_url").read_text().strip().splitlines()[0].strip()
    if not base:
        base = "http://127.0.0.1:18787"
    base = base.rstrip("/")
    tok = (os.environ.get("NANOBOT_PEER_TOKEN") or "").strip()
    if not tok and (home / "peer_token").is_file():
        tok = (home / "peer_token").read_text().strip().splitlines()[0].strip()
    payload = {
        "prompt": (
            "Classify this sanitized Titan USB diagnostic. "
            "Reply ONLY JSON list of {id,title,detail,repo}. "
            "repo must be atlasos. No user data. Do not invent serials.\n"
            + json.dumps({"snap": {k: snap.get(k) for k in (
                "device_tag", "device", "lineage", "profile", "usb_audio",
                "analog", "acc_sh", "acc_dex", "policy", "wired",
            )}, "findings": findings}, indent=2)
        )
    }
    req = urllib.request.Request(
        base + "/peer/v1/prompt",
        data=json.dumps(payload).encode(),
        method="POST",
        headers={
            "Content-Type": "application/json",
            "X-Nanobot-Peer-Token": tok,
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=25) as r:
            raw = r.read().decode("utf-8", "replace")
        obj = json.loads(raw)
        text = obj.get("output") or obj.get("text") or obj.get("reply") or raw
        if isinstance(text, dict):
            text = json.dumps(text)
        m = re.search(r"\[.*\]", str(text), re.S)
        if not m:
            return []
        extra = json.loads(m.group(0))
        out = []
        for it in extra:
            if not isinstance(it, dict) or not it.get("id"):
                continue
            out.append(
                {
                    "id": re.sub(r"[^a-z0-9-]", "", str(it["id"]).lower())[:48],
                    "title": str(it.get("title") or it["id"])[:120],
                    "detail": str(it.get("detail") or "")[:400],
                    "repo": "atlasos",
                    "severity": "bug",
                }
            )
        return out
    except Exception:
        return []
