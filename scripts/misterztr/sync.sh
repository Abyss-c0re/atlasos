#!/usr/bin/env bash
# MisterZtr README:
#   repo sync --force-sync --optimized-fetch --no-tags --no-clone-bundle --prune -j4
set -euo pipefail
source "$(cd "$(dirname "$0")" && pwd)/lib.sh"
require_tree
REPO=$(repo_bin)
cd "$MISTERZTR_TREE"
strip_titanus2_manifests

ATTEMPTS="${REPO_SYNC_ATTEMPTS:-5}"
SLEEP_SEC="${REPO_SYNC_SLEEP_SEC:-90}"
jobs="$REPO_JOBS"
attempt=1
ok=0
while [ "$attempt" -le "$ATTEMPTS" ]; do
  info "repo sync attempt $attempt/$ATTEMPTS -j$jobs (MisterZtr flags)"
  if "$REPO" sync --force-sync --optimized-fetch --no-tags --no-clone-bundle --prune -j"$jobs"; then
    ok=1
    break
  fi
  warn "sync failed — backoff ${SLEEP_SEC}s (often HTTP 429)"
  if [ "$jobs" -gt 4 ]; then
    jobs=$((jobs / 2))
    [ "$jobs" -lt 4 ] && jobs=4
  elif [ "$jobs" -gt 1 ]; then
    jobs=1
  fi
  sleep "$SLEEP_SEC"
  attempt=$((attempt + 1))
done
[ "$ok" = "1" ] || die "repo sync failed after $ATTEMPTS attempts"

# LineageOS_gsi is normally brought by treble_manifest; ensure present.
# repo may name the remote "origin" or "git-hub" (treble_manifest / repo tooling).
_lineage_gsi_update() {
  local br="$LINEAGE_GSI_REPO_BRANCH"
  local url="https://github.com/MisterZtr/LineageOS_gsi.git"
  local remote
  remote=$(git remote 2>/dev/null | head -1 || true)
  if [ -z "$remote" ]; then
    git remote add origin "$url"
    remote=origin
  fi
  # Prefer origin; fall back to whatever repo created (e.g. git-hub).
  if git remote get-url origin >/dev/null 2>&1; then
    remote=origin
  elif ! git remote get-url "$remote" >/dev/null 2>&1; then
    git remote add origin "$url" 2>/dev/null || true
    remote=origin
  fi
  info "LineageOS_gsi update remote=$remote branch=$br"
  git fetch "$remote" "$br" || git fetch "$remote"
  git checkout "$br" 2>/dev/null || git checkout -B "$br" "$remote/$br"
  git reset --hard "$remote/$br" 2>/dev/null \
    || git reset --hard "FETCH_HEAD" 2>/dev/null \
    || git pull --ff-only "$remote" "$br" || true
}

if [ ! -d LineageOS_gsi/patches ]; then
  info "clone MisterZtr/LineageOS_gsi ($LINEAGE_GSI_REPO_BRANCH)"
  rm -rf LineageOS_gsi
  git clone -b "$LINEAGE_GSI_REPO_BRANCH" \
    https://github.com/MisterZtr/LineageOS_gsi.git LineageOS_gsi
else
  info "LineageOS_gsi present — update"
  ( cd LineageOS_gsi && _lineage_gsi_update )
fi

info "sync OK"
echo "Next: ./scripts/misterztr/apply_patches.sh"
