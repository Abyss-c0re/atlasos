#!/usr/bin/env bash
# Fail if shippable titan2-/atlas- names exist as two different real files.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORKSHOP="${WORKSHOP:-${TITANUS2_WORKSHOP:-$ROOT/../titanus2}}"
ec=0

python3 - "$ROOT" "$WORKSHOP" << 'PY'
import os, sys
from pathlib import Path
from collections import defaultdict

A, W = map(Path, sys.argv[1:3])
roots = [
    A / "patches/bin",
    A / "patches/init",
    A / "packages/gsi_product/prebuilt_sysbin",
    A / "packages/gsi_product/prebuilt_atlas",
    A / "packages/titan_atlas/scripts",
    A / "apps/titan_atlas/assets/bin",
    A / "packages/gsi_product/prebuilt_touchpadd",
    A / "third_party/titan2-touchpadd/bin",
    W / "packages/magisk_titan2_pad_agent/system/bin",
    W / "packages/magisk_titan2_atlas_hybrid/system/bin",
    W / "packages/magisk_titan2_touchpadd/system/bin",
]
want = set()
for r in roots:
    if not r.exists():
        continue
    for p in r.iterdir():
        n = p.name
        if n.startswith("titan2-") or n.startswith("atlas-") or n == "apt-hybrid.sh":
            want.add(n)

bad = 0
for name in sorted(want):
    inos = set()
    locs = []
    for r in roots:
        p = r / name
        if not p.exists():
            continue
        try:
            inos.add(p.stat().st_ino)
            locs.append(str(p))
        except OSError:
            pass
    if len(inos) > 1:
        bad += 1
        print(f"ASYNC {name}")
        for loc in locs:
            print(f"  {loc}")
if bad:
    print(f"FAIL {bad} names have more than one inode")
    sys.exit(1)
print(f"OK  {len(want)} shippable names share one inode each")
sys.exit(0)
PY
ec=$?
exit "$ec"
