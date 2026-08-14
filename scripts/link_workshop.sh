#!/usr/bin/env bash
# Point workshop shared paths at AtlasOS (one inode). AtlasOS will not drift.
#
#   ./scripts/link_workshop.sh
#   WORKSHOP=/path/to/titanus2 ./scripts/link_workshop.sh
#
# 1) Copy workshop → AtlasOS for each listed path (latest wins).
# 2) Park workshop-only extras under $WORKSHOP/.atlasos_local/.
# 3) Replace the workshop path with a relative symlink into AtlasOS.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LIST="$ROOT/chain/sync_paths.txt"
WORKSHOP="${WORKSHOP:-${TITANUS2_WORKSHOP:-$ROOT/../titanus2}}"
LOCAL="$WORKSHOP/.atlasos_local"

info() { echo "==> $*"; }
warn() { echo "WARN: $*" >&2; }
die() { echo "ERROR: $*" >&2; exit 1; }

[ -f "$LIST" ] || die "missing $LIST"
[ -d "$WORKSHOP" ] || die "workshop not found: $WORKSHOP"
[ -d "$ROOT/apps/titan_atlas" ] || die "AtlasOS tree looks empty"

relpath() {
  python3 -c 'import os,sys; print(os.path.relpath(sys.argv[1], sys.argv[2]))' "$1" "$2"
}

park_extra() {
  local src=$1 rel=$2
  local dest="$LOCAL/$rel"
  mkdir -p "$(dirname "$dest")"
  if [ -e "$dest" ] || [ -L "$dest" ]; then
    rm -rf "$dest"
  fi
  mv "$src" "$dest"
  info "parked extra $rel → .atlasos_local/"
}

link_one() {
  local rel=$1
  local src="$ROOT/$rel"
  local dst="$WORKSHOP/$rel"

  [ -e "$src" ] || [ -L "$src" ] || { warn "skip missing AtlasOS path: $rel"; return 0; }

  # Already the right symlink
  if [ -L "$dst" ]; then
    local cur
    cur="$(readlink -f "$dst" 2>/dev/null || true)"
    local want
    want="$(readlink -f "$src" 2>/dev/null || true)"
    if [ -n "$cur" ] && [ "$cur" = "$want" ]; then
      info "ok $rel"
      return 0
    fi
    rm -f "$dst"
  fi

  if [ -d "$src" ] && [ -d "$dst" ] && [ ! -L "$dst" ]; then
    # Park files that exist only on the workshop side
    while IFS= read -r -d '' extra; do
      local erel="${extra#"$dst"/}"
      if [ ! -e "$src/$erel" ]; then
        park_extra "$extra" "$rel/$erel"
      fi
    done < <(find "$dst" -mindepth 1 \( -type f -o -type l \) -print0)

    # Latest workshop sources into AtlasOS (do not delete AtlasOS-only)
    rsync -a --exclude '.git' "$dst"/ "$src"/
    rm -rf "$dst"
  elif [ -f "$src" ]; then
    if [ -f "$dst" ] && [ ! -L "$dst" ]; then
      # workshop file is the live edit
      cp -a "$dst" "$src"
      rm -f "$dst"
    elif [ -e "$dst" ] && [ ! -L "$dst" ]; then
      park_extra "$dst" "$rel"
    fi
  elif [ -e "$dst" ] && [ ! -L "$dst" ]; then
    rsync -a "$dst" "$src"
    rm -rf "$dst"
  fi

  mkdir -p "$(dirname "$dst")"
  local target
  target="$(relpath "$src" "$(dirname "$dst")")"
  ln -sfn "$target" "$dst"
  info "link $rel → $target"
}

# Mixed asset/bin + prebuilt_atlas: link only names that exist on both sides.
link_matching_files() {
  local rel=$1
  local src="$ROOT/$rel"
  local dst="$WORKSHOP/$rel"
  [ -d "$src" ] || return 0
  mkdir -p "$dst"
  local f
  for f in "$src"/*; do
    [ -e "$f" ] || continue
    local name
    name="$(basename "$f")"
    link_one "$rel/$name"
  done
}

info "AtlasOS  $ROOT"
info "workshop $WORKSHOP"

while IFS= read -r line || [ -n "$line" ]; do
  line="${line%%#*}"
  line="${line#"${line%%[![:space:]]*}"}"
  line="${line%"${line##*[![:space:]]}"}"
  [ -z "$line" ] && continue
  link_one "$line"
done <"$LIST"

# Mixed dirs: share every AtlasOS name, leave workshop-only blobs
link_matching_files "apps/titan_atlas/assets/bin"
link_matching_files "packages/gsi_product/prebuilt_atlas"
link_matching_files "patches/bin"
link_matching_files "patches/keylayout"
link_matching_files "patches/idc"
link_matching_files "patches/init"
link_matching_files "patches/keyboard_layouts"
link_matching_files "packages/gsi_product/prebuilt_sysbin"
link_matching_files "packages/gsi_product/prebuilt_touchpadd"
link_matching_files "packages/gsi_product/prebuilt_usb_hid"
# product mk
if [ -f "$ROOT/packages/gsi_product/titanus2.mk" ]; then
  link_one "packages/gsi_product/titanus2.mk"
fi

info "done — workshop edits now land in AtlasOS"
info "extras parked at $LOCAL (not in AtlasOS git)"
