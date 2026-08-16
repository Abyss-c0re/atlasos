#!/usr/bin/env bash
# Push the titan2-touchpadd submodule to GitHub (and Gitea if configured).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/third_party/titan2-touchpadd"
info() { echo "==> $*"; }
die() { echo "ERROR: $*" >&2; exit 1; }

[ -d "$SRC/.git" ] || [ -f "$SRC/.git" ] || die "submodule missing — git submodule update --init"
[ -f "$SRC/Cargo.toml" ] || die "not a touchpadd checkout: $SRC"
info "checkout $SRC @ $(git -C "$SRC" rev-parse --short HEAD)"

if [ -x "$SRC/scripts/push_both.sh" ]; then
  exec "$SRC/scripts/push_both.sh" "$@"
fi
git -C "$SRC" push origin HEAD
if git -C "$SRC" remote get-url gitea >/dev/null 2>&1; then
  git -C "$SRC" push gitea HEAD
fi
