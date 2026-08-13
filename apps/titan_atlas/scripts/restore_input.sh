#!/usr/bin/env bash
# Restore Atlas input stack from a known_good snapshot.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REV="${1:-}"
if [[ -z "$REV" ]]; then
  REV="$(cat "$ROOT/known_good/VERSION" 2>/dev/null || true)"
fi
[[ -n "$REV" ]] || { echo "usage: $0 <rev>  (or set known_good/VERSION)"; exit 1; }
SRC="$ROOT/known_good/$REV"
[[ -d "$SRC" ]] || { echo "missing snapshot: $SRC"; ls "$ROOT/known_good" 2>/dev/null || true; exit 1; }

echo "Restoring input rev $REV from $SRC"
cp -a "$SRC/AtlasTermClient.java" "$ROOT/src/com/titanus2/atlas/AtlasTermClient.java"
cp -a "$SRC/ExtraKeysView.java"   "$ROOT/src/com/titanus2/atlas/ExtraKeysView.java"
cp -a "$SRC/MainActivity.java"    "$ROOT/src/com/titanus2/atlas/MainActivity.java"
cp -a "$SRC/TerminalView.java"    "$ROOT/src/com/termux/view/TerminalView.java"
if [[ -f "$SRC/KeyHandler.java" ]]; then
  cp -a "$SRC/KeyHandler.java"    "$ROOT/src/com/termux/terminal/KeyHandler.java"
fi
echo "$REV" > "$ROOT/known_good/VERSION"
echo "OK — restored $REV. Rebuild: ATLAS_SKIP_NATIVE=1 bash $ROOT/build.sh"
