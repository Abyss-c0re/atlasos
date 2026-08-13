#!/usr/bin/env bash
# Resolve chain/sources.yaml into local paths. Never uploads anything.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CHAIN="$ROOT/chain/sources.yaml"
LINKS="$ROOT/.links"
mkdir -p "$LINKS"

info() { echo "==> $*"; }
warn() { echo "WARN: $*" >&2; }

# Optional local env (gitignored)
if [ -f "$ROOT/config/misterztr.local.env" ]; then
  # shellcheck source=/dev/null
  source "$ROOT/config/misterztr.local.env"
fi

expand() {
  local p="$1"
  p="${p/#\~/$HOME}"
  printf '%s' "$p"
}

TREE="${MISTERZTR_TREE:-$HOME/Dev/titanus2-artifacts/misterztr_lineage}"
TREE="$(expand "$TREE")"
WORKSHOP="${TITANUS2_WORKSHOP:-$ROOT/../titanus2}"
WORKSHOP="$(expand "$WORKSHOP")"

ln -sfn "$TREE" "$LINKS/misterztr_tree"
ln -sfn "$WORKSHOP" "$LINKS/workshop"

{
  echo "schema=atlasos.links.v1"
  date -Iseconds
  echo "misterztr_tree=$TREE"
  echo "misterztr_tree_ok=$([ -d "$TREE/device/phh/treble" ] && echo yes || echo no)"
  echo "workshop=$WORKSHOP"
  echo "workshop_ok=$([ -x "$WORKSHOP/scripts/misterztr/pipeline.sh" ] && echo yes || echo no)"
  echo "stock_zip=${STOCK_ZIP:-}"
} >"$LINKS/resolved.txt"

info "chain store: $CHAIN"
info "MisterZtr tree: $TREE ($([ -d "$TREE/device/phh/treble" ] && echo present || echo MISSING))"
info "workshop: $WORKSHOP ($([ -x "$WORKSHOP/scripts/misterztr/pipeline.sh" ] && echo present || echo MISSING))"
[ -n "${STOCK_ZIP:-}" ] && info "stock zip: $STOCK_ZIP" || info "stock zip: unset (needed for hybrid pack)"
info "resolved → $LINKS/resolved.txt"
info "nothing uploaded"
