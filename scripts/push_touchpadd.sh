#!/usr/bin/env bash
# Push titan2-touchpadd to GitHub and Gitea (gitea-ssh.angry.im:2222).
# Finds the product checkout, then runs scripts/push_both.sh there.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
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
    [ -n "$d" ] && [ -d "$d/.git" ] && [ -f "$d/Cargo.toml" ] && printf '%s\n' "$d" && return 0
  done
  return 1
}

SRC="$(src_candidates || true)"
if [ -z "$SRC" ]; then
  info "no checkout — fetch"
  "$ROOT/third_party/titan2-touchpadd/fetch.sh"
  SRC="$(src_candidates || true)"
fi
[ -n "$SRC" ] || die "no titan2-touchpadd checkout (set TITAN2_TOUCHPADD_SRC=)"

PUSH="$SRC/scripts/push_both.sh"
if [ ! -x "$PUSH" ]; then
  die "missing $PUSH — update the checkout"
fi
info "checkout $SRC"
exec "$PUSH" "$@"
