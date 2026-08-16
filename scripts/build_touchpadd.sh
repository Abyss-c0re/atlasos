#!/usr/bin/env bash
# Pull the titan2-touchpadd submodule and pack a musl aarch64 ELF into the GSI.
# Source SoT: https://github.com/Abyss-c0re/titan2-touchpadd (git submodule).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/third_party/titan2-touchpadd"
PRE="$ROOT/packages/gsi_product/prebuilt_touchpadd/titan2-touchpadd"
info() { echo "==> $*"; }
die() { echo "ERROR: $*" >&2; exit 1; }

[ -d "$ROOT/.git" ] || [ -f "$ROOT/.git" ] || die "not an AtlasOS git checkout"
info "submodule update third_party/titan2-touchpadd"
git -C "$ROOT" submodule update --init --checkout third_party/titan2-touchpadd
[ -f "$SRC/Cargo.toml" ] || die "submodule missing Cargo.toml — git submodule update --init"
[ -f "$SRC/src/pause.rs" ] || die "submodule is not the product fork (no pause.rs): $SRC"
info "source $SRC @ $(git -C "$SRC" rev-parse --short HEAD)"

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

mkdir -p "$(dirname "$PRE")"
rm -f "$PRE"
cp -f "$ELF" "$PRE"
chmod 755 "$PRE"
info "packed $PRE ($(stat -c%s "$PRE") bytes) from $(git -C "$SRC" rev-parse --short HEAD)"
