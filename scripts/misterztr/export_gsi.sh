#!/usr/bin/env bash
# Copy built system.img into product gsi/ and optionally pack hybrid super.
# Writes the canonical pin name rom_variant prefers (LineageOS-23.2-DATE-…)
# plus misterztr-src aliases. Tree stays outside git.
set -euo pipefail
source "$(cd "$(dirname "$0")" && pwd)/lib.sh"
require_tree

PATH_FILE="$MISTERZTR_TREE/out/misterztr_last_systemimage.path"
if [ -n "${OUT_IMG:-}" ] && [ -f "$OUT_IMG" ]; then
  :
elif [ -f "$PATH_FILE" ]; then
  OUT_IMG=$(cat "$PATH_FILE")
else
  OUT_IMG="$MISTERZTR_TREE/out/target/product/tdgsi_arm64_ab/system.img"
fi
[ -f "${OUT_IMG:-}" ] || die "no system.img — run build_gsi.sh first"

STAMP=$(date +%Y%m%d)
# Canonical product pin (matches config/variants.yaml gsi_preference)
PIN_NAME="LineageOS-23.2-${STAMP}-VANILLA-EXT4-GSI.img"
ALIAS_NAME="LineageOS-misterztr-src-${STAMP}-VANILLA-EXT4-GSI.img"
PIN_DEST="$ROOT/gsi/$PIN_NAME"
ALIAS_DEST="$ROOT/gsi/$ALIAS_NAME"
mkdir -p "$ROOT/gsi"

info "export $OUT_IMG → $PIN_DEST"
cp -f "$OUT_IMG" "$PIN_DEST"
# Hard-link or copy alias (same bytes; hard-link saves disk when possible)
if ln -f "$PIN_DEST" "$ALIAS_DEST" 2>/dev/null; then
  info "alias hard-link $ALIAS_NAME"
else
  cp -f "$PIN_DEST" "$ALIAS_DEST"
fi
ln -sfn "$PIN_NAME" "$ROOT/gsi/LineageOS-misterztr-src-latest-VANILLA-EXT4-GSI.img"
ln -sfn "$PIN_NAME" "$ROOT/gsi/LineageOS-misterztr-src-VANILLA-EXT4-GSI.img"

echo "$PIN_DEST" >"$ROOT/out/misterztr_exported_gsi.path" 2>/dev/null || true
info "exported pin: $PIN_DEST"

# Optional hybrid pack: PACK=1 PRESET=ship|lab_rootless|lab
PACK="${PACK:-0}"
PRESET="${PRESET:-lab_rootless}"
if [ "$PACK" = "1" ]; then
  info "PACK=1 → hybrid pack preset=$PRESET with this GSI"
  cd "$ROOT"
  ALLOW_UNTRACKED="${ALLOW_UNTRACKED:-1}" \
    ./scripts/rom_variant.py build --preset "$PRESET" --gsi "$PIN_DEST"
fi

echo "Product GSI pin (source-built):"
echo "  $PIN_DEST"
echo "Pack hybrid:"
echo "  ./scripts/rom_variant.py build --preset lab_rootless --gsi $PIN_DEST"
echo "  FORCE_DIRTY_SHIP=1 GSI_IMG=$PIN_DEST ./scripts/host/ship_lab_fix.sh"
