#!/bin/bash
# Host build of atlas-x (glibc + libwayland). The phone binary is the same
# source built inside Debian, where /home/atlas/atlas-x is the present seat.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
PKG="$(cd "$ROOT/../.." && pwd)"
GEN="$PKG/out/x11/gen"
OUT="$PKG/out/x11"
mkdir -p "$GEN" "$OUT"

XDG_XML="${XDG_XML:-/usr/share/qt6/wayland/protocols/xdg-shell/xdg-shell.xml}"
DMA_XML="${DMA_XML:-/usr/share/qt6/wayland/protocols/linux-dmabuf/linux-dmabuf-unstable-v1.xml}"
if [ ! -f "$XDG_XML" ]; then
  XDG_XML="$(find /usr/share -name xdg-shell.xml | head -1)"
fi
if [ ! -f "$DMA_XML" ]; then
  DMA_XML="$(find /usr/share -name 'linux-dmabuf-unstable-v1.xml' | head -1)"
fi
[ -f "$XDG_XML" ] && [ -f "$DMA_XML" ] || { echo "missing wayland protocol xml"; exit 1; }

wayland-scanner server-header "$XDG_XML" "$GEN/xdg-shell-server.h"
wayland-scanner client-header "$XDG_XML" "$GEN/xdg-shell-client.h"
wayland-scanner private-code "$XDG_XML" "$GEN/xdg-shell.c"
wayland-scanner server-header "$DMA_XML" "$GEN/linux-dmabuf-server.h"
wayland-scanner client-header "$DMA_XML" "$GEN/linux-dmabuf-client.h"
wayland-scanner private-code "$DMA_XML" "$GEN/linux-dmabuf.c"

CC="${CC:-cc}"
CFLAGS="-O2 -Wall -Wextra -Wno-unused-parameter -I$GEN -I$ROOT -I$ROOT/../seat"
WAYLAND_SVR="$(pkg-config --cflags --libs wayland-server)"
WAYLAND_CLI="$(pkg-config --cflags --libs wayland-client)"
XKB="$(pkg-config --cflags --libs xkbcommon)"
X11="$(pkg-config --cflags --libs x11)"

$CC $CFLAGS -o "$OUT/atlas-x" "$ROOT/atlas_x.c" "$GEN/xdg-shell.c" "$GEN/linux-dmabuf.c" \
  $WAYLAND_SVR $XKB
$CC $CFLAGS -o "$OUT/atlas-x-paint" "$ROOT/atlas_x_paint.c" "$GEN/xdg-shell.c" "$GEN/linux-dmabuf.c" \
  $WAYLAND_CLI
$CC $CFLAGS -o "$OUT/atlas-x-xdraw" "$ROOT/atlas_x_xdraw.c" $X11
echo "OK $OUT/atlas-x"
