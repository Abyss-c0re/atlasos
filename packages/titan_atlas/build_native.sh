#!/usr/bin/env bash
# Build atlas pure-C core + ptyexec for Android aarch64 (NDK).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
OUT_DIR="$ROOT/out"
APP_ASSETS="$(cd "$ROOT/../.." && pwd)/apps/titan_atlas/assets/bin"
mkdir -p "$OUT_DIR" "$APP_ASSETS"

# Link-farm / same-inode: cp -f errors if src and dest are one file.
# Dangling dest symlink (workshop → missing AtlasOS asset): replace with real copy.
_cp() {
  local src=$1 dest=$2
  if [ -L "$dest" ]; then
    if [ -e "$dest" ] && [ "$src" -ef "$dest" ]; then
      return 0
    fi
    rm -f "$dest"
  elif [ -e "$dest" ] && [ "$src" -ef "$dest" ]; then
    return 0
  fi
  cp -f "$src" "$dest"
}

NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK:-}}"
if [ -z "$NDK" ] || [ ! -d "$NDK" ]; then
  NDK=$(ls -d "$HOME/Android/Sdk/ndk"/* 2>/dev/null | sort -V | tail -1 || true)
fi
[ -n "$NDK" ] && [ -d "$NDK" ] || { echo "need Android NDK"; exit 1; }

API="${ATLAS_API_LEVEL:-28}"
HOST_TAG=linux-x86_64
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG"
CC="$TOOLCHAIN/bin/aarch64-linux-android${API}-clang"
[ -x "$CC" ] || { echo "missing $CC"; exit 1; }

# Product package version (UI/tools). enterd has its own peel string — never stomp it
# with 1.0.0 (lab 2026-08-13: VER=1.0.0 overwrote 1.2.4-enter-fast in product ship).
VER="${ATLAS_VERSION:-1.0.0}"
ENTERD_VER="${ATLAS_ENTERD_VERSION:-1.2.7-resume}"
LPCTL_VER="${ATLAS_LPCTL_VERSION:-1.0.1-wipe-home}"
echo "CC=$CC VER=$VER ENTERD_VER=$ENTERD_VER LPCTL_VER=$LPCTL_VER"

build_one() {
  local name=$1 src=$2
  local use_ver="${3:-$VER}"
  local out="$OUT_DIR/$name"
  "$CC" -O2 -fPIE -pie -D_GNU_SOURCE -DATLAS_VERSION=\"$use_ver\" \
    -I"$ROOT/native" \
    -o "$out" "$src" -llog 2>/dev/null || \
  "$CC" -O2 -fPIE -pie -D_GNU_SOURCE -DATLAS_VERSION=\"$use_ver\" \
    -I"$ROOT/native" \
    -o "$out" "$src"
  chmod 755 "$out"
  _cp "$out" "$APP_ASSETS/$name"
  echo "OK $out ($(stat -c%s "$out") bytes) VER=$use_ver → assets/bin/$name"
}

build_one atlas "$ROOT/native/atlas.c"
build_one ptyexec "$ROOT/native/ptyexec.c"

# Product rootless Deb enter: client + init-root daemon (never Magisk / setuid)
if [ -f "$ROOT/native/atlas_enter.c" ]; then
  build_one atlas-enter "$ROOT/native/atlas_enter.c" "$ENTERD_VER"
fi
if [ -f "$ROOT/native/atlas_enterd.c" ]; then
  build_one atlas-enterd "$ROOT/native/atlas_enterd.c" "$ENTERD_VER"
fi

# Auth + biometric sudo gate (all modes: android / debian / hybrid)
if [ -f "$ROOT/native/atlas_auth.c" ]; then
  build_one atlas-auth "$ROOT/native/atlas_auth.c"
fi
if [ -f "$ROOT/native/atlas_auth_askpass.c" ]; then
  build_one atlas-auth-askpass "$ROOT/native/atlas_auth_askpass.c"
fi
if [ -f "$ROOT/native/atlas_sudo.c" ]; then
  build_one atlas-sudo "$ROOT/native/atlas_sudo.c"
  # PATH names sudo + su = agent clients only. Real KernelSU is always absolute
  # /system/bin/su after grant — never free interactive root without biometrics.
  _cp "$OUT_DIR/atlas-sudo" "$APP_ASSETS/sudo"
  _cp "$OUT_DIR/atlas-sudo" "$APP_ASSETS/su"
  chmod 755 "$APP_ASSETS/sudo" "$APP_ASSETS/su"
  echo "OK sudo/su agent-clients → assets/bin (real su only after agent grant)"
fi

# Super LP mount helper — Debian on atlas_linux_a; home on /data (see ATLAS_SUPER_LP.md)
if [ -f "$ROOT/native/atlas_lpctl.c" ]; then
  build_one atlas-lpctl "$ROOT/native/atlas_lpctl.c" "$LPCTL_VER"
fi

# Experimental seat bins (not product path) — build only if ATLAS_BUILD_SEAT=1
if [ "${ATLAS_BUILD_SEAT:-0}" = "1" ]; then
  if [ -f "$ROOT/native/atlas_seatd.c" ]; then
    build_one atlas-seatd "$ROOT/native/atlas_seatd.c"
  fi
  if [ -f "$ROOT/native/atlas_seat_push.c" ]; then
    build_one atlas-seat-push "$ROOT/native/atlas_seat_push.c"
  fi
  if [ -f "$ROOT/native/atlas_seat_pull.c" ]; then
    build_one atlas-seat-pull "$ROOT/native/atlas_seat_pull.c"
  fi
  if [ -f "$ROOT/native/atlas_seat_input.c" ]; then
    build_one atlas-seat-input "$ROOT/native/atlas_seat_input.c"
  fi
fi
# pam_exec helper for Debian hybrid real sudo
if [ -f "$ROOT/scripts/atlas-auth-pam.sh" ]; then
  _cp "$ROOT/scripts/atlas-auth-pam.sh" "$APP_ASSETS/atlas-auth-pam"
  chmod 755 "$APP_ASSETS/atlas-auth-pam"
fi
rm -f "$APP_ASSETS/grok-atlas"


# Shared lib: C termgrid + JNI (Canvas paints cells — no WebView)
SO_OUT="$OUT_DIR/libatlasterm.so"
"$CC" -O2 -fPIC -shared -D_GNU_SOURCE \
  -o "$SO_OUT" \
  "$ROOT/native/termgrid.c" "$ROOT/native/termgrid_jni.c" \
  -llog 2>/dev/null || \
"$CC" -O2 -fPIC -shared -D_GNU_SOURCE \
  -o "$SO_OUT" \
  "$ROOT/native/termgrid.c" "$ROOT/native/termgrid_jni.c"
chmod 755 "$SO_OUT"
_cp "$SO_OUT" "$APP_ASSETS/libatlasterm.so"
echo "OK $SO_OUT ($(stat -c%s "$SO_OUT") bytes) → assets/bin/libatlasterm.so"

# Termux-derived PTY JNI (C) — real /dev/ptmx for TerminalSession
PTY_SO="$OUT_DIR/libatlaspty.so"
"$CC" -O2 -fPIC -shared -D_GNU_SOURCE \
  -o "$PTY_SO" "$ROOT/native/termux_pty.c" -llog
chmod 755 "$PTY_SO"
_cp "$PTY_SO" "$APP_ASSETS/libatlaspty.so"
echo "OK $PTY_SO ($(stat -c%s "$PTY_SO") bytes) → assets/bin/libatlaspty.so"

# Keep hybrid script staged
if [ -f "$ROOT/scripts/atlas-hybrid.sh" ]; then
  _cp "$ROOT/scripts/atlas-hybrid.sh" "$APP_ASSETS/atlas-hybrid.sh"
  chmod 755 "$APP_ASSETS/atlas-hybrid.sh"
fi

REPO="$(cd "$ROOT/../.." && pwd)"
if [ -f "$REPO/packages/titan2_nanobot/bin/nanobot" ]; then
  _cp "$REPO/packages/titan2_nanobot/bin/nanobot" "$APP_ASSETS/nanobot"
  echo "staged nanobot"
fi
if [ -f "$REPO/packages/quest_usbip_host/assets/quest-usbip-host" ]; then
  _cp "$REPO/packages/quest_usbip_host/assets/quest-usbip-host" "$APP_ASSETS/quest-usbip-host"
  echo "staged quest-usbip-host"
fi
# static bash if present
if [ -x "$APP_ASSETS/bash" ]; then
  echo "bash already in assets"
elif [ -f /tmp/bash_stage/bash.cand ]; then
  _cp /tmp/bash_stage/bash.cand "$APP_ASSETS/bash"
  chmod 755 "$APP_ASSETS/bash"
  echo "staged bash"
fi
