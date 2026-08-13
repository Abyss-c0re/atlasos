#!/usr/bin/env bash
# Generate a git-diff patch from dirty files under MISTERZTR_TREE into patches/gsi_source/.
#
#   ./scripts/misterztr/new_source_patch.sh \
#     --name systemui-foo \
#     --project frameworks/base \
#     --paths packages/SystemUI/src/...
#
# Creates 00NN-<name>.patch and appends to SERIES with project header.
set -euo pipefail
source "$(cd "$(dirname "$0")" && pwd)/lib.sh"
require_tree

NAME=""
PROJECT="."
PATHS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --name) NAME="${2:-}"; shift 2 ;;
    --project) PROJECT="${2:-}"; shift 2 ;;
    --paths) shift; while [ $# -gt 0 ] && [[ "$1" != --* ]]; do PATHS+=("$1"); shift; done ;;
    -h|--help) sed -n '2,14p' "$0"; exit 0 ;;
    *) die "unknown arg: $1" ;;
  esac
done
[ -n "$NAME" ] || die "--name required"
NAME="${NAME// /-}"
SERIES_DIR="$ROOT/patches/gsi_source"
SERIES="$SERIES_DIR/SERIES"
mkdir -p "$SERIES_DIR"

# next index
n=10
if [ -f "$SERIES" ]; then
  last=$(grep -E '^[0-9]+-' "$SERIES" | tail -1 | cut -d- -f1 || true)
  if [[ "$last" =~ ^[0-9]+$ ]]; then
    n=$((10#$last + 10))
  fi
fi
nn=$(printf '%04d' "$n")
out="$SERIES_DIR/${nn}-${NAME}.patch"

cd "$MISTERZTR_TREE"
proj_dir="$MISTERZTR_TREE/$PROJECT"
[ -d "$proj_dir" ] || die "missing project dir: $PROJECT"

if [ "${#PATHS[@]}" -eq 0 ]; then
  die "pass --paths <relative to project> …"
fi

# Prefer git diff inside project if it is a git worktree
if [ -d "$proj_dir/.git" ] || [ -f "$proj_dir/.git" ]; then
  (
    cd "$proj_dir"
    git diff -- "${PATHS[@]}"
  ) >"$out"
else
  die "project $PROJECT is not a git checkout — use repo project path with .git"
fi

[ -s "$out" ] || die "empty diff — no uncommitted changes under paths?"

# SERIES entry
if ! grep -q "^# project:${PROJECT}$" "$SERIES" 2>/dev/null; then
  {
    echo ""
    echo "# project:${PROJECT}"
  } >>"$SERIES"
fi
base=$(basename "$out")
if ! grep -qxF "$base" "$SERIES"; then
  echo "$base" >>"$SERIES"
fi

info "wrote $out"
info "SERIES updated — review and commit in product git"
info "apply: FORCE_TITAN_REPATCH=1 ./scripts/misterztr/apply_titan_source_patches.sh"
