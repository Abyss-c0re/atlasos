#!/usr/bin/env bash
# MisterZtr README:
#   bash LineageOS_gsi/patches/apply-patches.sh .
#
# Then Titan product source patches (patches/gsi_source/SERIES) when present.
# Hybrid inject still owns priv-apps / stock vendor (docs/project/SOURCE_PRODUCT.md).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
source "$HERE/lib.sh"
require_tree
cd "$MISTERZTR_TREE"

[ -f LineageOS_gsi/patches/apply-patches.sh ] \
  || die "missing LineageOS_gsi/patches/apply-patches.sh — run sync.sh first"

MARKER=".misterztr_patches_applied"
FORCE_REPATCH="${FORCE_REPATCH:-0}"
if [ -f "$MARKER" ] && [ "$FORCE_REPATCH" != "1" ]; then
  info "MisterZtr patches already applied ($MARKER). FORCE_REPATCH=1 to re-run."
  cat "$MARKER" || true
else
  # Refuse full Path-B bake leftovers
  if [ -d vendor/titanus2 ]; then
    warn "removing leftover vendor/titanus2 (not part of MisterZtr pure GSI)"
    rm -rf vendor/titanus2
  fi

  info "apply MisterZtr patches (apply-patches.sh .)"
  bash LineageOS_gsi/patches/apply-patches.sh .

  {
    date -Iseconds
    echo "recipe=MisterZtr/LineageOS_gsi@$LINEAGE_GSI_REPO_BRANCH"
    echo "tree=$MISTERZTR_TREE"
    echo "note=misterztr_upstream_then_titan_gsi_source"
  } >"$MARKER"
  info "MisterZtr patches OK"
fi

# Titan SERIES (git-tracked). Empty SERIES is a no-op success.
if [ "${SKIP_TITAN_SOURCE_PATCHES:-0}" = "1" ]; then
  info "SKIP_TITAN_SOURCE_PATCHES=1 — hybrid inject only for product"
else
  if [ "$FORCE_REPATCH" = "1" ]; then
    export FORCE_TITAN_REPATCH=1
  fi
  "$HERE/apply_titan_source_patches.sh"
fi

echo "Next: ./scripts/misterztr/build_gsi.sh"
