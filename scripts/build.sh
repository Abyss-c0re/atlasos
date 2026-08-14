#!/usr/bin/env bash
# Standalone image build. Clone → bootstrap → this.
# Does not flash. Does not require the lab workshop.
#
#   ./scripts/build.sh --flavor vanilla
#   ./scripts/build.sh --flavor microg
#   ./scripts/build.sh --flavor gapps
#   ./scripts/build.sh --flavor vanilla --gsi-only
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FLAVOR="${ATLASOS_FLAVOR:-vanilla}"
GSI_ONLY=0
for a in "$@"; do
  case "$a" in
    --flavor=*) FLAVOR="${a#--flavor=}" ;;
    --flavor) : ;;
    vanilla|microg|gapps) FLAVOR="$a" ;;
    --gsi-only) GSI_ONLY=1 ;;
    -h|--help)
      sed -n '2,14p' "$0"
      exit 0
      ;;
  esac
done
prev=
for a in "$@"; do
  [ "$prev" = "--flavor" ] && FLAVOR="$a"
  prev=$a
done

info() { echo "==> $*"; }
die() { echo "ERROR: $*" >&2; exit 1; }

eval "$(python3 - "$ROOT/config/flavors.yaml" "$FLAVOR" << 'PY'
import sys
from pathlib import Path
flav, want = Path(sys.argv[1]), sys.argv[2]
cur, block, in_f = {}, None, False
for raw in flav.read_text().splitlines():
    line = raw.split("#", 1)[0].rstrip()
    if line.startswith("  ") and not line.startswith("    ") and line.strip().endswith(":"):
        if block == want:
            cur = dict(cur)
        name = line.strip().rstrip(":")
        if name in ("vanilla", "microg", "gapps"):
            block = name
            if name == want:
                cur = {}
        continue
    if block == want and line.startswith("    ") and ":" in line:
        k, _, v = line.strip().partition(":")
        cur[k.strip()] = v.strip().strip('"')
if not cur:
    print(f"echo ERROR: unknown flavor {want!r} >&2; exit 1")
    sys.exit(0)
print(f"LUNCH={cur.get('lunch','')}")
print(f"LUNCH_MK={cur.get('lunch_mk','')}")
print(f"FLAVOR_MICROG={1 if cur.get('microg')=='true' else 0}")
print(f"FLAVOR_GAPPS={1 if cur.get('gapps')=='true' else 0}")
PY
)"

[ -n "${LUNCH:-}" ] || die "unknown flavor: $FLAVOR"

if [ -f "$ROOT/config/misterztr.local.env" ]; then
  # shellcheck source=/dev/null
  source "$ROOT/config/misterztr.local.env"
fi
export MISTERZTR_TREE="${MISTERZTR_TREE:-$ROOT/.links/lineage}"
export LUNCH_TARGET="$LUNCH"
export ATLASOS_FLAVOR="$FLAVOR"
export WITH_MICROG="${WITH_MICROG:-$FLAVOR_MICROG}"
export WITH_GAPPS="${WITH_GAPPS:-$FLAVOR_GAPPS}"

info "flavor=$FLAVOR lunch=$LUNCH_TARGET tree=$MISTERZTR_TREE"

if [ ! -d "$MISTERZTR_TREE/device/phh/treble" ]; then
  info "no Lineage tree — running bootstrap"
  "$ROOT/scripts/bootstrap.sh"
fi

if [ "$WITH_MICROG" = "1" ]; then
  if [ ! -f "$ROOT/packages/microg/prebuilt/GmsCore.apk" ]; then
    info "microg flavor: fetching APKs (not committed)"
    [ -x "$ROOT/packages/microg/fetch.sh" ] || die "packages/microg/fetch.sh missing"
    "$ROOT/packages/microg/fetch.sh"
  fi
fi

if [ "$WITH_GAPPS" = "1" ]; then
  mk="$MISTERZTR_TREE/device/phh/treble/${LUNCH_MK}"
  if [ ! -f "$mk" ]; then
    die "GApps lunch mk missing ($mk). This MisterZtr tree has no bgN4 — use --flavor vanilla or microg."
  fi
fi

if [ ! -x "$ROOT/third_party/titan2-touchpadd/bin/titan2-touchpadd" ]; then
  info "building titan2-touchpadd (musl)"
  "$ROOT/scripts/build_touchpadd.sh"
fi
"$ROOT/scripts/check_clean.sh"
FORCE_TITAN_REPATCH="${FORCE_TITAN_REPATCH:-0}" \
  "$ROOT/scripts/misterztr/apply_titan_source_patches.sh"

info "GSI systemimage ($FLAVOR)"
"$ROOT/scripts/misterztr/pipeline.sh" --from=patch

if [ "$GSI_ONLY" = "1" ]; then
  info "gsi-only done"
  exit 0
fi

if [ -n "${STOCK_ZIP:-}" ] && [ -f "${STOCK_ZIP}" ]; then
  info "STOCK_ZIP set — hybrid pack is still a local vendor attach"
  info "AtlasOS does not ship Unihertz firmware. Packer: workshop kitchen if present."
  WORKSHOP="${TITANUS2_WORKSHOP:-$ROOT/../titanus2}"
  if [ -x "$WORKSHOP/scripts/rom_variant.py" ]; then
    exec "$WORKSHOP/scripts/rom_variant.py" build --preset lab_rootless
  fi
  info "no packer in this clone — GSI export is the standalone product"
else
  info "no STOCK_ZIP — GSI only (hybrid needs your region-matching Unihertz zip)"
fi
