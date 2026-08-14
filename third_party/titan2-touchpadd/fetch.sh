#!/usr/bin/env bash
# Clone / update Abyss-c0re/titan2-touchpadd (product fork).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
REMOTE="${TITAN2_TOUCHPADD_REMOTE:-https://github.com/Abyss-c0re/titan2-touchpadd.git}"
DEST="${TITAN2_TOUCHPADD_SRC:-$ROOT/.links/upstream/titan2-touchpadd}"
info() { echo "==> $*"; }

if [ -z "${TITAN2_TOUCHPADD_SRC:-}" ] && [ -f "$HOME/Dev/titanus2-artifacts/titan2-touchpadd/Cargo.toml" ]; then
  DEST="$HOME/Dev/titanus2-artifacts/titan2-touchpadd"
fi

mkdir -p "$(dirname "$DEST")"
if [ -d "$DEST/.git" ]; then
  info "fetch $DEST"
  git -C "$DEST" fetch --tags origin 2>/dev/null || true
  git -C "$DEST" merge --ff-only origin/main 2>/dev/null || true
else
  info "clone $REMOTE → $DEST"
  git clone "$REMOTE" "$DEST"
fi

rel="$(python3 -c 'import os,sys; print(os.path.relpath(sys.argv[1], sys.argv[2]))' "$DEST" "$HERE")"
ln -sfn "$rel" "$HERE/checkout"
ln -sfn checkout/src "$HERE/src"
if [ -L "$HERE/patches" ] || [ -e "$HERE/patches" ]; then
  rm -rf "$HERE/patches"
fi
ln -sfn src "$HERE/patches"
info "linked checkout=$DEST"
echo "Next: $ROOT/scripts/build_touchpadd.sh"
