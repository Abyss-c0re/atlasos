#!/usr/bin/env bash
# MisterZtr README: mkdir + repo init + treble_manifest local_manifests
# https://github.com/MisterZtr/LineageOS_gsi/blob/lineage-23.2/README.md
set -euo pipefail
source "$(cd "$(dirname "$0")" && pwd)/lib.sh"

REPO=$(repo_bin)
mkdir -p "$MISTERZTR_TREE"
cd "$MISTERZTR_TREE"

if [ ! -d .repo/manifests ]; then
  info "repo init LineageOS/$LINEAGE_BRANCH (MisterZtr README)"
  INIT_ARGS=(-u https://github.com/LineageOS/android.git -b "$LINEAGE_BRANCH" --git-lfs)
  if [ "$SHALLOW_INIT" = "1" ]; then
    warn "SHALLOW_INIT=1 — not in MisterZtr README; use only for disk-starved lab"
    INIT_ARGS+=(--depth=1)
  fi
  "$REPO" init "${INIT_ARGS[@]}"
else
  info "repo already initialized at $MISTERZTR_TREE"
fi

if [ ! -d .repo/local_manifests/.git ]; then
  info "clone MisterZtr/treble_manifest ($TREBLE_MANIFEST_BRANCH)"
  rm -rf .repo/local_manifests
  git clone -b "$TREBLE_MANIFEST_BRANCH" \
    https://github.com/MisterZtr/treble_manifest.git .repo/local_manifests
else
  info "treble_manifest present — reset to origin/$TREBLE_MANIFEST_BRANCH"
  (
    cd .repo/local_manifests
    git fetch origin "$TREBLE_MANIFEST_BRANCH"
    git checkout "$TREBLE_MANIFEST_BRANCH"
    git reset --hard "origin/$TREBLE_MANIFEST_BRANCH"
  )
fi

strip_titanus2_manifests

info "init OK"
echo "Tree: $MISTERZTR_TREE"
echo "Next: ./scripts/misterztr/sync.sh"
