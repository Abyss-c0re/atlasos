#!/usr/bin/env bash
# Snapshot current Atlas input stack as a restorable known_good rev.
# Usage: ./scripts/snapshot_input.sh [rev]
# Default rev = AtlasTermClient.INPUT_REV from source, or timestamp.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REV="${1:-}"
if [[ -z "$REV" ]]; then
  REV="$(grep -oE 'INPUT_REV = "[^"]+"' "$ROOT/src/com/titanus2/atlas/AtlasTermClient.java" \
    | head -1 | sed 's/.*"\([^"]*\)"/\1/')"
fi
[[ -n "$REV" ]] || REV="snap-$(date +%Y%m%d-%H%M%S)"
DEST="$ROOT/known_good/$REV"
mkdir -p "$DEST"
cp -a \
  "$ROOT/src/com/titanus2/atlas/AtlasTermClient.java" \
  "$ROOT/src/com/titanus2/atlas/ExtraKeysView.java" \
  "$ROOT/src/com/titanus2/atlas/MainActivity.java" \
  "$ROOT/src/com/termux/view/TerminalView.java" \
  "$ROOT/src/com/termux/terminal/KeyHandler.java" \
  "$DEST/"
echo "$REV" > "$DEST/INPUT_REV"
echo "$REV" > "$ROOT/known_good/VERSION"
(cd "$DEST" && sha256sum ./* > SHA256SUMS)
echo "snapshotted input → known_good/$REV (pin VERSION=$REV)"
echo "restore: $ROOT/scripts/restore_input.sh $REV"
