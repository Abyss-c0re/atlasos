#!/usr/bin/env bash
# Pull reusable remotes into .links/upstream/. Never uploads. Never wipes overlays.
#
#   ./scripts/sync-modules.sh           # fetch what has a remote
#   ./scripts/sync-modules.sh --status  # print module table
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MOD="$ROOT/config/modules.yaml"
UP="$ROOT/.links/upstream"
STATUS_ONLY=0
for a in "$@"; do
  case "$a" in --status|-s) STATUS_ONLY=1 ;; -h|--help) sed -n '2,8p' "$0"; exit 0 ;; esac
done
[ -f "$MOD" ] || { echo "ERROR: missing $MOD" >&2; exit 1; }
mkdir -p "$UP"

python3 - "$MOD" "$UP" "$STATUS_ONLY" << 'PY'
import os, subprocess, sys
from pathlib import Path

mod_path, up_root, status_only = Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3] == "1"

def load_modules(p: Path):
    # tiny YAML subset: list of maps under modules:
    text = p.read_text()
    mods, cur, in_m = [], {}, False
    for raw in text.splitlines():
        line = raw.split("#", 1)[0].rstrip()
        if not line.strip():
            continue
        if line.startswith("modules:"):
            in_m = True
            continue
        if in_m and line.startswith("upstreams:"):
            if cur:
                mods.append(cur)
            break
        if in_m and line.startswith("  - name:"):
            if cur:
                mods.append(cur)
            cur = {"name": line.split(":", 1)[1].strip()}
            continue
        if in_m and cur and line.startswith("    "):
            k, _, v = line.strip().partition(":")
            cur[k.strip()] = v.strip()
    if cur:
        mods.append(cur)
    return mods

def run(cmd, cwd=None):
    return subprocess.run(cmd, cwd=cwd, text=True, capture_output=True)

mods = load_modules(mod_path)
seen, uniq = set(), []
for m in mods:
    n = m.get("name")
    if not n or n in seen:
        continue
    seen.add(n)
    uniq.append(m)
mods = uniq
print(f"{'name':<22} {'status':<14} {'remote':<44} pull")
print("-" * 96)
ec = 0
for m in mods:
    name = m.get("name", "?")
    st = m.get("status", "?")
    remote = m.get("remote", "null")
    if remote in ("null", "", "None"):
        remote = ""
    pull = "-"
    if status_only:
        pull = "skip"
    elif remote and st in ("upstream", "vendored", "overlay", "fetch"):
        dest = up_root / name
        if not dest.exists():
            print(f"==> clone {name}")
            r = run(["git", "clone", "--depth=1", remote, str(dest)])
            pull = "cloned" if r.returncode == 0 else "CLONE_FAIL"
            if r.returncode != 0:
                print(r.stderr[-400:])
                ec = 1
        else:
            r = run(["git", "fetch", "--depth=1", "origin"], cwd=dest)
            pull = "fetched" if r.returncode == 0 else "FETCH_FAIL"
            if r.returncode != 0:
                print(r.stderr[-400:])
                ec = 1
    elif not remote:
        pull = "in-tree (no remote yet)"
    print(f"{name:<22} {st:<14} {(remote or '—'):<44} {pull}")

print()
print("Overlays stay in this git. Upstream trees stay under .links/upstream/ (gitignored).")
print("Unpublished modules stay in apps/ until their remotes exist — then add them to .gitmodules.")
sys.exit(ec)
PY
