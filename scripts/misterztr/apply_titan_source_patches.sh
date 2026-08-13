#!/usr/bin/env bash
# Apply product patches from patches/gsi_source/ onto MISTERZTR_TREE.
# Called by apply_patches.sh after MisterZtr upstream patches.
#
# Usage:
#   ./scripts/misterztr/apply_titan_source_patches.sh
#   ./scripts/misterztr/apply_titan_source_patches.sh --dry-run
#   FORCE_TITAN_REPATCH=1 ./scripts/misterztr/apply_titan_source_patches.sh
set -euo pipefail
source "$(cd "$(dirname "$0")" && pwd)/lib.sh"
require_tree

SERIES_DIR="$ROOT/patches/gsi_source"
SERIES_FILE="$SERIES_DIR/SERIES"
MARKER="$MISTERZTR_TREE/.titanus2_source_patches_applied"
DRY=0
for a in "$@"; do
  case "$a" in
    --dry-run|-n) DRY=1 ;;
    -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
  esac
done

[ -f "$SERIES_FILE" ] || die "missing $SERIES_FILE"

if [ -f "$MARKER" ] && [ "${FORCE_TITAN_REPATCH:-0}" != "1" ] && [ "$DRY" != "1" ]; then
  info "Titan source patches already applied ($MARKER). FORCE_TITAN_REPATCH=1 to re-run."
  cat "$MARKER" || true
  # Always re-stage product prebuilts (binary can move without SERIES change).
  if [ -x "$ROOT/scripts/misterztr/stage_gsi_product.sh" ]; then
    info "re-stage GSI product prebuilts (patches already applied)"
    "$ROOT/scripts/misterztr/stage_gsi_product.sh" || warn "stage_gsi_product failed"
  fi
  exit 0
fi

cd "$MISTERZTR_TREE"
project="."
applied=0
skipped=0

while IFS= read -r line || [ -n "$line" ]; do
  # trim
  line="${line#"${line%%[![:space:]]*}"}"
  line="${line%"${line##*[![:space:]]}"}"
  [ -z "$line" ] && continue
  case "$line" in
    \#*)
      if [[ "$line" =~ ^#\ *project:(.+)$ ]]; then
        project="${BASH_REMATCH[1]}"
        project="${project// /}"
        info "project context: $project"
      fi
      continue
      ;;
  esac
  patch_file="$SERIES_DIR/$line"
  [ -f "$patch_file" ] || die "SERIES lists missing patch: $line"
  target="$MISTERZTR_TREE/$project"
  [ -d "$target" ] || die "project dir missing under tree: $project"

  info "apply $line → $project (patch -p1)"
  if [ "$DRY" = "1" ]; then
    if (cd "$target" && patch -p1 --forward --dry-run <"$patch_file" >/dev/null 2>&1); then
      info "dry-run OK (would apply): $line"
      skipped=$((skipped + 1))
    elif (cd "$target" && patch -p1 --reverse --dry-run <"$patch_file" >/dev/null 2>&1); then
      info "dry-run OK (already applied): $line"
      skipped=$((skipped + 1))
    else
      die "dry-run failed: $line"
    fi
    continue
  fi
  # Apply; tolerate already-on-tree (reverse dry-run or create-file already exists).
  set +e
  out="$(cd "$target" && patch -p1 --forward --batch <"$patch_file" 2>&1)"
  rc=$?
  set -e
  printf '%s\n' "$out"
  if [ "$rc" -eq 0 ]; then
    applied=$((applied + 1))
  elif (cd "$target" && patch -p1 --reverse --dry-run <"$patch_file" >/dev/null 2>&1); then
    warn "already applied (reverse dry-run ok): $line"
    skipped=$((skipped + 1))
  elif printf '%s\n' "$out" | grep -qE 'which already exists!|Reversed \(or previously applied\)'; then
    warn "already applied / target file present: $line"
    skipped=$((skipped + 1))
  else
    die "patch failed: $line (see above)"
  fi
  # aapt2 packs res/** — leftover *.rej from skipped re-apply will fail the GSI build
  find "$target" -name '*.rej' -delete 2>/dev/null || true
done <"$SERIES_FILE"

if [ "$DRY" = "1" ]; then
  info "dry-run OK (checked $skipped patches)"
  exit 0
fi

{
  date -Iseconds
  echo "series=$SERIES_FILE"
  echo "tree=$MISTERZTR_TREE"
  echo "applied=$applied skipped=$skipped"
} >"$MARKER"

# Stage prebuilts (touchpadd ELF + PRODUCT_PACKAGES fragment) after SERIES.
# Use ROOT from lib.sh — cwd is MISTERZTR_TREE; relative $0 would break.
_stage="$ROOT/scripts/misterztr/stage_gsi_product.sh"
if [ -x "$_stage" ]; then
  info "stage GSI product prebuilts"
  "$_stage"
fi

info "Titan source patches OK (applied=$applied skipped=$skipped)"
