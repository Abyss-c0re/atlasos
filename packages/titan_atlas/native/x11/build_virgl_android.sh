#!/bin/bash
# Bionic virgl vtest server. Debian Mesa virpipe talks to this socket;
# this process calls the phone EGL/GLES (Mali).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK:-$HOME/Android/Sdk/ndk/29.0.14206865}}"
PRE="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
CC="$PRE/bin/aarch64-linux-android28-clang"
CXX="$PRE/bin/aarch64-linux-android28-clang++"
AR="$PRE/bin/llvm-ar"
STRIP="$PRE/bin/llvm-strip"
[ -x "$CC" ] || { echo "need NDK clang"; exit 1; }
MESON="${MESON:-$(command -v meson || true)}"
if [ -z "$MESON" ] && [ -x "$HOME/.local/bin/meson" ]; then MESON="$HOME/.local/bin/meson"; fi
[ -n "$MESON" ] || { echo "need meson"; exit 1; }

SRC="${VIRGL_SRC:-/tmp/virgl-src}"
PREFIX="/tmp/virgl-android-prefix"
rm -rf "$PREFIX"
mkdir -p "$PREFIX" /tmp/virgl-cross
cat > /tmp/virgl-cross/android.ini <<EOF
[binaries]
c = '$CC'
cpp = '$CXX'
ar = '$AR'
strip = '$STRIP'
pkg-config = 'pkg-config'

[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'

[built-in options]
c_args = ['-DANDROID','-fPIC','-I$ROOT/android-shim']
cpp_args = ['-DANDROID','-fPIC','-I$ROOT/android-shim']
c_link_args = ['-llog','-landroid','-lEGL','-lGLESv2','-ldl']
cpp_link_args = ['-llog','-landroid','-lEGL','-lGLESv2','-ldl']
EOF

# Host pkg-config must not see host libgbm. Android EGL has no gbm.
export PKG_CONFIG_PATH="$PREFIX/lib/pkgconfig"
export PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig"
if ! grep -q 'Surfaceless GLES is the vtest path' "$SRC/virglrenderer/src/vrend/vrend_winsys.c"; then
    patch -p1 -d "$SRC/virglrenderer" < "$ROOT/android-egl.patch"
fi

"$MESON" setup "$SRC/libepoxy" /tmp/virgl-epoxy-build \
  --cross-file /tmp/virgl-cross/android.ini \
  --prefix="$PREFIX" --libdir=lib \
  -Degl=yes -Dglx=no -Dx11=false \
  -Dtests=false
ninja -C /tmp/virgl-epoxy-build install

"$MESON" setup "$SRC/virglrenderer" /tmp/virgl-rend-build \
  --cross-file /tmp/virgl-cross/android.ini \
  --prefix="$PREFIX" --libdir=lib \
  --pkg-config-path="$PREFIX/lib/pkgconfig" \
  -Dplatforms=egl \
  -Dvenus=false \
  -Dtests=false \
  -Dvideo=false \
  -Dcheck-gl-errors=false
ninja -C /tmp/virgl-rend-build

BIN="/tmp/virgl-rend-build/vtest/virgl_test_server"
REND="/tmp/virgl-rend-build/src/libvirglrenderer.so"
EPOXY="$PREFIX/lib/libepoxy.so"
[ -x "$BIN" ] && [ -f "$REND" ] && [ -f "$EPOXY" ] || { echo "virgl_test_server not built"; exit 1; }
DEST="$ROOT/../../../apps/titan_atlas/assets/bin"
mkdir -p "$DEST"
"$STRIP" --strip-unneeded -o "$DEST/virgl_test_server" "$BIN"
"$STRIP" --strip-unneeded -o "$DEST/libvirglrenderer.so" "$REND"
"$STRIP" --strip-unneeded -o "$DEST/libepoxy.so" "$EPOXY"
chmod 755 "$DEST/virgl_test_server" "$DEST/libvirglrenderer.so" "$DEST/libepoxy.so"
echo "OK $DEST/virgl_test_server"
file "$DEST/virgl_test_server"
