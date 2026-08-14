#!/usr/bin/env bash
# Full MisterZtr-faithful GSI pipeline → export → optional hybrid pack.
#
# SoT: https://github.com/MisterZtr/LineageOS_gsi (lineage-23.2 README)
# Product: docs/project/SOURCE_PRODUCT.md
#
#   ./scripts/misterztr/pipeline.sh              # init+sync+patch+build+export
#   ./scripts/misterztr/pipeline.sh --pack ship  # + hybrid pack ship preset
#   ./scripts/misterztr/pipeline.sh --from build # skip init/sync/patch
#   ./scripts/misterztr/pipeline.sh --from=patch # re-apply SERIES + rebuild
#   SKIP_SYNC=1 ./scripts/misterztr/pipeline.sh
#
# Tree is local (MISTERZTR_TREE). Titan AOSP deltas: patches/gsi_source/.
# AtlasOS mouse driver is in the GSI (not hybrid inject). OEM residual only.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
cd "$ROOT"
source "$HERE/lib.sh"

PACK=0
PRESET=lab_rootless
FROM=init
for a in "$@"; do
  case "$a" in
    --pack) PACK=1 ;;
    --pack=*) PACK=1; PRESET="${a#--pack=}" ;;
    ship|lab|lab_rootless) PACK=1; PRESET="$a" ;;
    --from=*) FROM="${a#--from=}" ;;
    --help|-h)
      sed -n '2,20p' "$0"
      exit 0
      ;;
  esac
done
# parse --pack ship as two args
prev=
for a in "$@"; do
  if [ "$prev" = "--pack" ]; then PRESET="$a"; fi
  prev=$a
done

LOGDIR="$ROOT/out/misterztr"
mkdir -p "$LOGDIR"
LOG="$LOGDIR/pipeline_$(date +%Y%m%d_%H%M%S).log"
ln -sfn "$(basename "$LOG")" "$LOGDIR/LATEST.log"
exec > >(tee -a "$LOG") 2>&1

# Always leave STATUS for monitors (success or fail).
trap 'ec=$?; if [ "$ec" -ne 0 ]; then echo "$(date -Iseconds) FAILED exit=$ec log=$LOG" >"$LOGDIR/STATUS"; fi' EXIT

info "=== MisterZtr source GSI pipeline (+ Titan gsi_source) ==="
info "TREE=$MISTERZTR_TREE lunch=$LUNCH_TARGET pack=$PACK preset=$PRESET from=$FROM"
info "log=$LOG"
info "recipe: https://github.com/MisterZtr/LineageOS_gsi/blob/lineage-23.2/README.md"
info "titan patches: $ROOT/patches/gsi_source/SERIES"

run_stage() {
  local name=$1
  shift
  info "--- stage: $name ---"
  "$@"
}

case "$FROM" in
  init)
    run_stage init "$HERE/init.sh"
    if [ "${SKIP_SYNC:-0}" != "1" ]; then
      run_stage sync "$HERE/sync.sh"
    fi
    run_stage apply_patches "$HERE/apply_patches.sh"
    run_stage build_gsi "$HERE/build_gsi.sh"
    ;;
  sync)
    run_stage sync "$HERE/sync.sh"
    run_stage apply_patches "$HERE/apply_patches.sh"
    run_stage build_gsi "$HERE/build_gsi.sh"
    ;;
  patch|apply)
    run_stage apply_patches "$HERE/apply_patches.sh"
    run_stage build_gsi "$HERE/build_gsi.sh"
    ;;
  build)
    run_stage build_gsi "$HERE/build_gsi.sh"
    ;;
  export)
    ;;
  *)
    die "unknown --from=$FROM (init|sync|patch|build|export)"
    ;;
esac

export PACK PRESET
if [ "$PACK" = "1" ]; then
  run_stage export env PACK=1 PRESET="$PRESET" "$HERE/export_gsi.sh"
else
  run_stage export "$HERE/export_gsi.sh"
fi

echo "$(date -Iseconds) SUCCESS log=$LOG" >"$LOGDIR/STATUS"
info "DONE — see $LOGDIR/STATUS"
