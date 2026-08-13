#!/usr/bin/env bash
# AtlasOS image: MisterZtr tree (linked) + SERIES + product stage [+ hybrid pack].
# This repo does not flash.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GSI_ONLY=0
for a in "$@"; do
  case "$a" in
    --gsi-only) GSI_ONLY=1 ;;
    -h|--help)
      sed -n '2,8p' "$0"
      echo "usage: $0 [--gsi-only]"
      exit 0
      ;;
  esac
done

"$ROOT/scripts/link.sh"
"$ROOT/scripts/check_clean.sh"

if [ -f "$ROOT/config/misterztr.local.env" ]; then
  # shellcheck source=/dev/null
  source "$ROOT/config/misterztr.local.env"
fi

export TITANUS2_ROOT="$ROOT"
export MISTERZTR_TREE="${MISTERZTR_TREE:-$HOME/Dev/titanus2-artifacts/misterztr_lineage}"

if [ ! -d "$MISTERZTR_TREE/device/phh/treble" ]; then
  echo "ERROR: MISTERZTR_TREE missing or not a Lineage GSI tree: $MISTERZTR_TREE" >&2
  echo "       Sync it yourself (MisterZtr README). AtlasOS will not upload it." >&2
  exit 1
fi

# Apply Titan/AtlasOS SERIES + stage PRODUCT_PACKAGES into the linked tree.
FORCE_TITAN_REPATCH="${FORCE_TITAN_REPATCH:-0}" \
  "$ROOT/scripts/misterztr/apply_titan_source_patches.sh"

if [ "$GSI_ONLY" = "1" ]; then
  echo "==> gsi-only: lunch + m systemimage on linked tree"
  exec "$ROOT/scripts/misterztr/pipeline.sh" --from=patch
fi

WORKSHOP="${TITANUS2_WORKSHOP:-$ROOT/../titanus2}"
if [ -x "$WORKSHOP/scripts/host/ship_lab_fix.sh" ] || [ -x "$WORKSHOP/scripts/rom_variant.py" ]; then
  echo "==> hybrid pack via linked workshop (matches live bench cook)"
  echo "    workshop=$WORKSHOP"
  echo "    AtlasOS already staged into MISTERZTR_TREE"
  echo "    HOLD_FLASH / flash policy stay in the workshop — this script does not flash"
  if [ -x "$WORKSHOP/scripts/rom_variant.py" ]; then
    exec "$WORKSHOP/scripts/rom_variant.py" build --preset lab_rootless
  fi
fi

echo "WARN: workshop packer not found — GSI stage is done; hybrid pack skipped"
echo "      next: point TITANUS2_WORKSHOP at the lab tree or pack vendor yourself"
echo "      GSI rebuild: $ROOT/scripts/misterztr/pipeline.sh --from=patch"
