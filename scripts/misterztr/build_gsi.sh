#!/usr/bin/env bash
# MisterZtr README — VANILLA EXT4:
#   . build/envsetup.sh
#   ccache -M 50G -F 0
#   breakfast lineage_arm64_bvN4-bp4a-userdebug
#   make systemimage -j$(nproc --all)
set -euo pipefail
source "$(cd "$(dirname "$0")" && pwd)/lib.sh"
require_tree
cd "$MISTERZTR_TREE"

if [ "${GSI_FRESH:-0}" = "1" ]; then
  PROD_OUT="$MISTERZTR_TREE/out/target/product/tdgsi_arm64_ab"
  info "fresh: wipe $PROD_OUT"
  rm -rf "$PROD_OUT"
fi

[ -f .misterztr_patches_applied ] \
  || warn "no .misterztr_patches_applied marker — did you run apply_patches.sh?"

export USE_CCACHE
export CCACHE_DIR
if have ccache; then
  ccache -M "$CCACHE_MAXSIZE" -F 0 || true
fi

# Do NOT set ALLOW_MISSING_DEPENDENCIES / SOONG_ALLOW_* — not in MisterZtr recipe.
# Those flags were used in cancelled titanus2 source experiments and hide real breaks.

info "Java: $(java -version 2>&1 | head -1 || echo missing)"
info "lunch=$LUNCH_TARGET jobs=$BUILD_JOBS"

# MisterZtr README: treble_app is used from your compiled version (TrebleApp.apk
# at tree root path treble_app/TrebleApp.apk).
if [ ! -f treble_app/TrebleApp.apk ]; then
  [ -x treble_app/build.sh ] || die "missing treble_app/build.sh — incomplete sync"
  info "building treble_app → TrebleApp.apk (Java 17 preferred)"
  (
    cd treble_app
    # Prefer JDK 17 for TrebleApp gradle (MisterZtr note)
    if [ -n "${JAVA_HOME:-}" ]; then
      export PATH="$JAVA_HOME/bin:$PATH"
    fi
    if [ -z "${ANDROID_HOME:-}" ] || [ ! -d "${ANDROID_HOME:-/nonexistent}" ]; then
      if [ -d "$HOME/Android/Sdk" ]; then
        export ANDROID_HOME="$HOME/Android/Sdk"
      else
        export ANDROID_HOME="$PWD/sdk"
      fi
    fi
    bash ./build.sh
  ) || die "treble_app/build.sh failed"
  [ -f treble_app/TrebleApp.apk ] || die "treble_app/build.sh did not produce TrebleApp.apk"
fi
info "TrebleApp.apk present ($(stat -c%s treble_app/TrebleApp.apk) bytes)"

set +u
# shellcheck disable=SC1091
source build/envsetup.sh
# breakfast is the README command; lunch fallback if breakfast wrapper fails
if ! breakfast "$LUNCH_TARGET"; then
  warn "breakfast failed — try lunch $LUNCH_TARGET"
  lunch "$LUNCH_TARGET" || die "lunch/breakfast failed for $LUNCH_TARGET"
fi
make systemimage -j"$BUILD_JOBS"
set -u

OUT_IMG="$MISTERZTR_TREE/out/target/product/tdgsi_arm64_ab/system.img"
if [ ! -f "$OUT_IMG" ]; then
  OUT_IMG=$(find "$MISTERZTR_TREE/out/target/product" -name system.img 2>/dev/null | head -1 || true)
fi
[ -f "${OUT_IMG:-}" ] || die "system.img not found under out/target/product"

info "built: $OUT_IMG ($(stat -c%s "$OUT_IMG") bytes)"
mkdir -p "$MISTERZTR_TREE/out"
echo "$OUT_IMG" >"$MISTERZTR_TREE/out/misterztr_last_systemimage.path"
echo "$OUT_IMG" >"$ROOT/out/misterztr_last_systemimage.path" 2>/dev/null || true

echo "Next: ./scripts/misterztr/export_gsi.sh"
echo "OUT_IMG=$OUT_IMG"
