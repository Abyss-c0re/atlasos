#!/usr/bin/env bash
# Build titan2-touchpadd (Abyss-c0re fork) the AtlasOS way: musl aarch64.
# Installs third_party/titan2-touchpadd/bin/titan2-touchpadd (gitignored).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BIN_DIR="$ROOT/third_party/titan2-touchpadd/bin"
OUT="$BIN_DIR/titan2-touchpadd"
info() { echo "==> $*"; }
die() { echo "ERROR: $*" >&2; exit 1; }

src_candidates() {
  local d
  for d in \
    "${TITAN2_TOUCHPADD_SRC:-}" \
    "$ROOT/third_party/titan2-touchpadd/checkout" \
    "$ROOT/.links/upstream/titan2-touchpadd" \
    "$HOME/Dev/titanus2-artifacts/titan2-touchpadd"
  do
    [ -n "$d" ] && [ -f "$d/Cargo.toml" ] && [ -d "$d/src" ] && printf '%s\n' "$d" && return 0
  done
  return 1
}

SRC="$(src_candidates || true)"
if [ -z "$SRC" ]; then
  info "no checkout — fetch Abyss-c0re fork"
  "$ROOT/third_party/titan2-touchpadd/fetch.sh"
  SRC="$(src_candidates || true)"
fi
[ -n "$SRC" ] || die "no titan2-touchpadd checkout (set TITAN2_TOUCHPADD_SRC=)"
[ -f "$SRC/src/pause.rs" ] || die "checkout missing pause.rs — not the product fork: $SRC"
info "source $SRC"

command -v cargo >/dev/null || die "cargo not found"
rustup target add aarch64-unknown-linux-musl >/dev/null 2>&1 || true

export CARGO_TARGET_AARCH64_UNKNOWN_LINUX_MUSL_LINKER="${CARGO_TARGET_AARCH64_UNKNOWN_LINUX_MUSL_LINKER:-$(rustc --print sysroot)/lib/rustlib/x86_64-unknown-linux-gnu/bin/rust-lld}"
[ -x "$CARGO_TARGET_AARCH64_UNKNOWN_LINUX_MUSL_LINKER" ] || \
  die "musl linker missing: $CARGO_TARGET_AARCH64_UNKNOWN_LINUX_MUSL_LINKER"

info "cargo build --target aarch64-unknown-linux-musl --release"
( cd "$SRC" && cargo build --target aarch64-unknown-linux-musl --release )

ELF="$SRC/target/aarch64-unknown-linux-musl/release/titan2-touchpadd"
[ -x "$ELF" ] || die "build produced no ELF: $ELF"
grep -aF 'INPROC_PARK' "$ELF" >/dev/null || die "ELF missing INPROC_PARK"
grep -aF 'Skipping TitanKey (KEYBOARD_FEATURES off)' "$ELF" >/dev/null \
  || die "ELF missing pad-only marker"

mkdir -p "$BIN_DIR"
cp -f "$ELF" "$OUT"
chmod 755 "$OUT"
info "installed $OUT ($(stat -c%s "$OUT") bytes)"

# Keep Soong prebuilt name as a link (not a second file).
PRE="$ROOT/packages/gsi_product/prebuilt_touchpadd/titan2-touchpadd"
mkdir -p "$(dirname "$PRE")"
ln -sfn ../../../third_party/titan2-touchpadd/bin/titan2-touchpadd "$PRE"
info "prebuilt_touchpadd → bin/titan2-touchpadd"
