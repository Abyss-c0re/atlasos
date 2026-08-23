"""Host nanobot develops a bounded patch; ship as PR or owner direct update."""
from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import tempfile
import urllib.request
from pathlib import Path

from titan_issues import _api, git_bin, git_env, github_token, origin_repo

ALLOW_PREFIX = (
    "patches/",
    "apps/titan_fm/",
    "apps/titan_controls/",
    "packages/gsi_product/",
    "tools/cube_flasher/",
    "scripts/",
    "README.md",
)
DENY_PART = (".git/", "token", "keystore", "secret", "auth/")
CONTEXT = {
    "analog-acc-missing": (
        "patches/bin/titan2-analog-acc.sh",
        "patches/init/titan2-analog-acc.rc",
        "packages/gsi_product/prebuilt_sysbin/Android.bp",
    ),
    "analog-not-routed": ("patches/bin/titan2-analog-acc.sh",),
    "usb-audio-policy-missing": ("patches/phh-on-boot.sh",),
    "fm-jni-namespace": (
        "apps/titan_fm/build.sh",
        "apps/titan_fm/AndroidManifest.xml",
    ),
    "controls-missing": ("packages/gsi_product/prebuilt_apps/Android.bp",),
}
DIFF_RE = re.compile(r"(?s)(diff --git a/.+?)(?:\Z|```)")

def nanobot_ready() -> bool:
    home = Path.home() / ".nanobot"
    if not (home / "peer_token").is_file() and not os.environ.get("NANOBOT_PEER_TOKEN"):
        return False
    base = (os.environ.get("NANOBOT_PEER_URL") or "").strip()
    if not base and (home / "peer_url").is_file():
        base = (home / "peer_url").read_text().strip().splitlines()[0].strip()
    for cand in (base, "http://127.0.0.1:18787", "http://127.0.0.1:28787"):
        if not cand:
            continue
        try:
            req = urllib.request.Request(cand.rstrip("/") + "/peer/v1/health", method="GET")
            tok = (os.environ.get("NANOBOT_PEER_TOKEN") or "").strip()
            if not tok and (home / "peer_token").is_file():
                tok = (home / "peer_token").read_text().strip().splitlines()[0].strip()
            if tok:
                req.add_header("X-Nanobot-Peer-Token", tok)
            with urllib.request.urlopen(req, timeout=4) as r:
                obj = json.loads(r.read().decode())
            if obj.get("ok"):
                return True
        except Exception:
            continue
    return False


def actor_login() -> str:
    env = (os.environ.get("CUBE_FLASHER_ACTOR") or "").strip()
    if env:
        return env
    try:
        out = subprocess.check_output(
            ["gh", "api", "user", "-q", ".login"], text=True, timeout=8, env=os.environ
        )
        if out.strip():
            return out.strip()
    except Exception:
        pass
    try:
        name = subprocess.check_output(
            [git_bin(), "config", "user.name"], text=True, timeout=4, env=git_env()
        ).strip()
        if name:
            return name
    except Exception:
        pass
    return ""


def is_abyss_core(login: str | None = None) -> bool:
    explicit = login is not None
    raw = (login if explicit else actor_login()).strip().lower()
    compact = raw.replace("_", "-").replace(" ", "")
    if compact in ("abyss-c0re", "abyss-core", "abysscore"):
        return True
    if explicit:
        return False
    email = ""
    try:
        email = subprocess.check_output(
            [git_bin(), "config", "user.email"], text=True, timeout=4, env=git_env()
        ).strip().lower()
    except Exception:
        pass
    if email.endswith("@abyss-core.com") or email.endswith("@abyss-c0re.com"):
        return True
    return False

def _allowed_path(rel: str) -> bool:
    rel = rel.lstrip("./").replace("\\", "/")
    if ".." in rel.split("/"):
        return False
    low = rel.lower()
    if any(p in low for p in DENY_PART):
        return False
    if rel.endswith((".apk", ".so", ".keystore")):
        return False
    return any(rel == p or rel.startswith(p) for p in ALLOW_PREFIX)


def extract_diff(text: str) -> str:
    if not text:
        return ""
    s = text.replace("```diff", "```").replace("```patch", "```")
    m = DIFF_RE.search(s)
    if m:
        return m.group(1).strip() + "\n"
    if "diff --git a/" in s:
        return s[s.index("diff --git a/") :].strip() + "\n"
    return ""


def validate_diff(diff: str) -> str:
    if not diff or "diff --git a/" not in diff:
        return "no unified diff"
    if len(diff) > 80000:
        return "diff too large"
    files = re.findall(r"^diff --git a/(\S+) b/(\S+)", diff, re.M)
    if not files:
        return "no files in diff"
    if len(files) > 8:
        return "too many files"
    for a, b in files:
        for rel in (a, b):
            if rel == "/dev/null":
                continue
            if not _allowed_path(rel):
                return "path not allowed: %s" % rel
    return ""


def _git(repo: Path, args: list[str], timeout: int = 30) -> tuple[int, str]:
    p = subprocess.run(
        [git_bin(), "-C", str(repo), *args],
        text=True,
        capture_output=True,
        timeout=timeout,
        env=git_env(),
    )
    return p.returncode, ((p.stdout or "") + (p.stderr or "")).strip()


def _read_context(root: Path, finding_id: str) -> str:
    paths = CONTEXT.get(finding_id) or ("patches/phh-on-boot.sh",)
    chunks = []
    total = 0
    for rel in paths:
        p = root / rel
        if not p.is_file() or not _allowed_path(rel):
            continue
        body = p.read_text(errors="replace")[:12000]
        chunks.append("=== %s ===\n%s" % (rel, body))
        total += len(body)
        if total > 40000:
            break
    return "\n".join(chunks)

def ask_nanobot_diff(finding: dict, snap: dict, context: str) -> str:
    home = Path.home() / ".nanobot"
    base = (os.environ.get("NANOBOT_PEER_URL") or "").strip()
    if not base and (home / "peer_url").is_file():
        base = (home / "peer_url").read_text().strip().splitlines()[0].strip()
    if not base:
        base = "http://127.0.0.1:18787"
    tok = (os.environ.get("NANOBOT_PEER_TOKEN") or "").strip()
    if not tok and (home / "peer_token").is_file():
        tok = (home / "peer_token").read_text().strip().splitlines()[0].strip()
    prompt = (
        "Write a unified git diff that fixes this Titan finding in AtlasOS. "
        "Reply with ONLY the diff (diff --git ...). No user data. "
        "Touch only allowlisted product paths.\n"
        + json.dumps(
            {
                "id": finding.get("id"),
                "title": finding.get("title"),
                "detail": finding.get("detail"),
                "snap": {
                    k: snap.get(k)
                    for k in (
                        "device_tag", "lineage", "profile", "analog",
                        "acc_sh", "policy", "wired", "user_comment",
                    )
                },
            },
            indent=2,
        )
        + "\n\nSOURCE\n"
        + context
    )
    req = urllib.request.Request(
        base.rstrip("/") + "/peer/v1/prompt",
        data=json.dumps({"prompt": prompt}).encode(),
        method="POST",
        headers={
            "Content-Type": "application/json",
            "X-Nanobot-Peer-Token": tok,
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=90) as r:
            raw = r.read().decode("utf-8", "replace")
        obj = json.loads(raw)
        text = obj.get("output") or obj.get("text") or obj.get("reply") or raw
        if isinstance(text, dict):
            text = json.dumps(text)
        return extract_diff(str(text))
    except Exception:
        return ""


def _open_pr(owner: str, repo: str, head: str, title: str, body: str) -> str:
    token = github_token()
    if not token:
        return "no token for PR"
    code, resp = _api(
        "POST",
        "https://api.github.com/repos/%s/%s/pulls" % (owner, repo),
        token,
        {"title": title, "head": head, "base": "main", "body": body},
    )
    if code in (200, 201) and isinstance(resp, dict) and resp.get("html_url"):
        return str(resp["html_url"])
    return "pr failed %s" % (resp if isinstance(resp, str) else resp.get("message", resp))

def develop_and_ship(finding: dict, snap: dict, atlasos: Path) -> str:
    if not nanobot_ready():
        return "nanobot not active"
    fid = re.sub(r"[^a-z0-9-]", "", str(finding.get("id") or "fix"))[:40] or "fix"
    ctx = _read_context(atlasos, fid)
    diff = ask_nanobot_diff(finding, snap, ctx)
    why = validate_diff(diff)
    if why:
        return "no patch (%s)" % why
    wt = Path(tempfile.mkdtemp(prefix="cube-diag-"))
    branch = "cube-diag/%s" % fid
    try:
        rc, out = _git(atlasos, ["worktree", "add", "-B", branch, str(wt), "HEAD"])
        if rc != 0:
            return "worktree failed: %s" % out[-180:]
        apply = subprocess.run(
            [git_bin(), "-C", str(wt), "apply", "--whitespace=nowarn"],
            input=diff,
            text=True,
            capture_output=True,
            timeout=20,
            env=git_env(),
        )
        if apply.returncode != 0:
            return "apply failed: %s" % ((apply.stderr or apply.stdout or "")[-180:])
        _git(wt, ["add", "-A"])
        rc, st = _git(wt, ["diff", "--cached", "--stat"])
        if not (st or "").strip():
            return "empty after apply"
        msg = "fix(cube-diag): %s" % fid
        rc, out = _git(wt, ["commit", "-m", msg])
        if rc != 0:
            return "commit failed: %s" % out[-180:]
        owner, repo = origin_repo(atlasos)
        if not owner:
            return "committed locally (no origin)"
        title = "[cube-diag:%s] %s" % (fid, finding.get("title") or fid)
        body = (
            "<!-- cube-diag:%s -->\n"
            "Nanobot-developed fix from Cube Flasher USB diagnostic. "
            "No user data.\n\n%s\n"
            % (fid, finding.get("detail") or "")
        )
        dest = "HEAD" + ":" + "main"
        if is_abyss_core():
            rc, out = _git(wt, ["push", "origin", dest], timeout=60)
            if rc == 0:
                return "direct update %s/%s main" % (owner, repo)
            rc2, out2 = _git(wt, ["push", "-u", "origin", branch], timeout=60)
            if rc2 != 0:
                return "update failed: %s" % (out or out2)[-180:]
            return "main blocked; %s" % _open_pr(owner, repo, branch, title, body)
        rc, out = _git(wt, ["push", "-u", "origin", branch], timeout=60)
        if rc != 0:
            return "branch update failed: %s" % out[-180:]
        return _open_pr(owner, repo, branch, title, body)
    finally:
        _git(atlasos, ["worktree", "remove", "--force", str(wt)])
        shutil.rmtree(wt, ignore_errors=True)
