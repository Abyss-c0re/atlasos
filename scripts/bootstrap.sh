#!/usr/bin/env bash
# Clone-to-tree: tools check, module fetch, Lineage+MisterZtr init/sync, apply SERIES.
# Does not flash. Does not upload LOS.
#
#   ./scripts/bootstrap.sh
#   ./scripts/bootstrap.sh --skip-sync     # tree already present
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SKIP_SYNC=0
for a in "$@"; do
  case "$a" in --skip-sync) SKIP_SYNC=1 ;; -h|--help) sed -n '2,10p' "$0"; exit 0 ;; esac
done

info() { echo "==> $*"; }
die() { echo "ERROR: $*" >&2; exit 1; }

need() { command -v "$1" >/dev/null 2>&1 || die "missing tool: $1"; }

info "tools"
need git
need python3
need curl
if ! command -v repo >/dev/null 2>&1 && [ ! -x "$HOME/.local/bin/repo" ]; then
  info "installing Google repo → ~/.local/bin/repo"
  mkdir -p "$HOME/.local/bin"
  curl -fsSL https://storage.googleapis.com/git-repo-downloads/repo -o "$HOME/.local/bin/repo"
  chmod +x "$HOME/.local/bin/repo"
fi
need python3

if [ -f "$ROOT/config/misterztr.local.env" ]; then
  # shellcheck source=/dev/null
  source "$ROOT/config/misterztr.local.env"
fi

# Standalone default: tree next to this clone, not the lab workshop path.
export MISTERZTR_TREE="${MISTERZTR_TREE:-$ROOT/.links/lineage}"
mkdir -p "$ROOT/.links"

info "modules (upstream fetch, overlays kept)"
"$ROOT/scripts/sync-modules.sh" || info "module fetch had warnings (offline remotes are OK)"

info "Lineage + MisterZtr tree → $MISTERZTR_TREE"
if [ ! -d "$MISTERZTR_TREE/device/phh/treble" ]; then
  "$ROOT/scripts/misterztr/init.sh"
  if [ "$SKIP_SYNC" != "1" ]; then
    "$ROOT/scripts/misterztr/sync.sh"
  fi
else
  info "tree already present"
  if [ "$SKIP_SYNC" != "1" ] && [ "${FORCE_SYNC:-0}" = "1" ]; then
    "$ROOT/scripts/misterztr/sync.sh"
  fi
fi

info "MisterZtr apply-patches + AtlasOS SERIES + stage"
"$ROOT/scripts/misterztr/apply_patches.sh"

info "bootstrap OK"
echo "Next: ./scripts/build.sh --flavor vanilla"
echo "      ./scripts/build.sh --flavor microg"
echo "      ./scripts/build.sh --flavor gapps"
