#!/usr/bin/env bash
# Verify workshop shared paths are symlinks into this AtlasOS tree.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LIST="$ROOT/chain/sync_paths.txt"
WORKSHOP="${WORKSHOP:-${TITANUS2_WORKSHOP:-$ROOT/../titanus2}}"
ec=0
ok() { echo "OK  $*"; }
bad() { echo "BAD $*"; ec=1; }

want="$(readlink -f "$ROOT")"

check() {
  local rel=$1
  local src="$ROOT/$rel"
  local dst="$WORKSHOP/$rel"
  [ -e "$src" ] || [ -L "$src" ] || return 0
  if [ ! -L "$dst" ]; then
    bad "not a symlink: $dst"
    return
  fi
  local got
  got="$(readlink -f "$dst" 2>/dev/null || true)"
  local exp
  exp="$(readlink -f "$src" 2>/dev/null || true)"
  if [ -n "$got" ] && [ "$got" = "$exp" ]; then
    ok "$rel"
  else
    bad "wrong target $rel → $got (want $exp)"
  fi
}

[ -d "$WORKSHOP" ] || { echo "ERROR: workshop missing $WORKSHOP" >&2; exit 1; }

while IFS= read -r line || [ -n "$line" ]; do
  line="${line%%#*}"
  line="${line#"${line%%[![:space:]]*}"}"
  line="${line%"${line##*[![:space:]]}"}"
  [ -z "$line" ] && continue
  check "$line"
done <"$LIST"

echo "---"
if [ "$ec" -eq 0 ]; then
  echo "LINKS OK — workshop → AtlasOS ($want)"
else
  echo "LINKS FAIL — run ./scripts/link_workshop.sh"
fi
exit "$ec"
