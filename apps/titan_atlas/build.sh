#!/usr/bin/env bash
# Build TitanAtlas.apk — pure C core + thin Java host
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$ROOT/../.." && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
BT=$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -1)
PLATFORM=$(ls -d "$SDK"/platforms/android-* 2>/dev/null | sort -V | tail -1)
[ -n "$BT" ] && [ -n "$PLATFORM" ] || { echo "need Android SDK build-tools + platforms"; exit 1; }

export PATH="$HOME/.local/jdk-17/bin:$HOME/.local/jdk/bin:/usr/lib/jvm/java-17-openjdk/bin:/usr/lib/jvm/java-11-openjdk/bin:$PATH"
export LANG="${LANG:-C.UTF-8}"
export LC_ALL="${LC_ALL:-C.UTF-8}"
JAVAC=$(command -v javac)
JAR=$(command -v jar)
[ -n "$JAVAC" ] || { echo "need javac"; exit 1; }

# 1) native core → assets/bin/atlas (+ optional nanobot / usbip host)
if [ "${ATLAS_SKIP_NATIVE:-0}" != "1" ]; then
  bash "$REPO/packages/titan_atlas/build_native.sh"
fi
[ -f "$ROOT/assets/bin/atlas" ] || {
  echo "missing assets/bin/atlas — run packages/titan_atlas/build_native.sh"
  exit 1
}

# launcher icon fallback
if [ ! -f "$ROOT/res/mipmap-hdpi/ic_launcher.png" ]; then
  mkdir -p "$ROOT/res/mipmap-hdpi"
  # copy from Controls if present, else 1x1 via python
  if [ -f "$REPO/apps/titan_controls/res/mipmap-hdpi/ic_launcher.png" ]; then
    cp -f "$REPO/apps/titan_controls/res/mipmap-hdpi/ic_launcher.png" \
      "$ROOT/res/mipmap-hdpi/ic_launcher.png"
  else
    python3 - <<'PY'
import struct, zlib, pathlib
# minimal 48x48 dark PNG
w=h=48
raw=b"".join(b"\x00"+bytes([0x10,0x10,0x12])*w for _ in range(h))
def chunk(t,d):
    return struct.pack(">I",len(d))+t+d+struct.pack(">I",zlib.crc32(t+d)&0xffffffff)
png=b"\x89PNG\r\n\x1a\n"+chunk(b"IHDR",struct.pack(">IIBBBBB",w,h,8,2,0,0,0))+chunk(b"IDAT",zlib.compress(raw))+chunk(b"IEND",b"")
pathlib.Path("res/mipmap-hdpi/ic_launcher.png").write_bytes(png)
PY
  fi
fi

BUILD=$(mktemp -d)
trap 'rm -rf "$BUILD"' EXIT
mkdir -p "$BUILD"/{gen,obj}

"$BT/aapt" package -f -m -J "$BUILD/gen" -M "$ROOT/AndroidManifest.xml" -S "$ROOT/res" \
  -I "$PLATFORM/android.jar" -F "$BUILD/resources.ap_"

FIND="${FIND:-/usr/bin/find}"
[ -x "$FIND" ] || FIND=find
mapfile -t _JAVAS < <("$FIND" -H "$BUILD/gen" "$ROOT/src" -name '*.java')
if [ "${#_JAVAS[@]}" -lt 8 ]; then
  echo "build.sh: only ${#_JAVAS[@]} java files — refusing hollow APK (src symlink?)" >&2
  exit 1
fi
"$JAVAC" --release 17 -encoding UTF-8 -cp "$PLATFORM/android.jar" -d "$BUILD/obj" \
  "${_JAVAS[@]}"
(cd "$BUILD/obj" && "$JAR" cf "$BUILD/classes.jar" .)
"$BT/d8" --min-api 28 --output "$BUILD" "$BUILD/classes.jar"

cp "$BUILD/resources.ap_" "$BUILD/unsigned.apk"
(cd "$BUILD" && "$BT/aapt" add unsigned.apk classes.dex)

# Embed tools only (no WebView xterm assets). Never ship grok shell wrappers.
while IFS= read -r -d '' f; do
  base="$(basename "$f")"
  case "$base" in
    grok|grok-android.sh) continue ;; # pure ELF from ~/.grok/downloads only
  esac
  rel="${f#"$ROOT/"}"
  mkdir -p "$BUILD/$(dirname "$rel")"
  cp -f "$f" "$BUILD/$rel"
  (cd "$BUILD" && "$BT/aapt" add unsigned.apk "$rel")
done < <(find "$ROOT/assets/bin" -type f -print0 2>/dev/null)

# TLS CA for static Linux ELFs (grok)
if [ -f "$ROOT/assets/ssl/cacert.pem" ]; then
  mkdir -p "$BUILD/assets/ssl"
  cp -f "$ROOT/assets/ssl/cacert.pem" "$BUILD/assets/ssl/cacert.pem"
  (cd "$BUILD" && "$BT/aapt" add unsigned.apk "assets/ssl/cacert.pem")
  echo "embedded ssl/cacert.pem"
fi

# Monospace with box-drawing for grok/ratatui TUI (system mono often blank frames)
if [ -f "$ROOT/assets/fonts/DejaVuSansMono.ttf" ]; then
  mkdir -p "$BUILD/assets/fonts"
  cp -f "$ROOT/assets/fonts/DejaVuSansMono.ttf" "$BUILD/assets/fonts/DejaVuSansMono.ttf"
  (cd "$BUILD" && "$BT/aapt" add unsigned.apk "assets/fonts/DejaVuSansMono.ttf")
  echo "embedded fonts/DejaVuSansMono.ttf"
fi

# Debian 13 trixie rootfs for hybrid bootstrap.
# Default OFF for hybrid ROM inject (system.img fills → guestfish write errors).
# Fat sideload: ATLAS_EMBED_ROOTFS=1 ./build.sh  → ~32M APK with assets/rootfs.
# Product bootstrap still finds tarball under app files, /data/local/tmp, or push.
REPO_ROOT="$(cd "$ROOT/../.." && pwd)"
ROOTFS_SRC="${ATLAS_ROOTFS_TAR:-$REPO_ROOT/out/atlas_rootfs/debian-trixie-arm64-rootfs.tar.gz}"
if [ "${ATLAS_EMBED_ROOTFS:-0}" = "1" ] && [ -f "$ROOTFS_SRC" ] && [ "$(stat -c%s "$ROOTFS_SRC")" -gt 1000000 ]; then
  mkdir -p "$ROOT/assets/rootfs" "$BUILD/assets/rootfs"
  cp -f "$ROOTFS_SRC" "$ROOT/assets/rootfs/debian-trixie-arm64-rootfs.tar.gz"
  cp -f "$ROOTFS_SRC" "$BUILD/assets/rootfs/debian-trixie-arm64-rootfs.tar.gz"
  (cd "$BUILD" && "$BT/aapt" add unsigned.apk "assets/rootfs/debian-trixie-arm64-rootfs.tar.gz")
  echo "embedded debian-trixie-arm64-rootfs.tar.gz ($(du -h "$ROOTFS_SRC" | awk '{print $1}')) — FAT apk"
else
  # Keep staged file on disk for data bootstrap; do not pack into system APK.
  if [ -f "$ROOTFS_SRC" ]; then
    mkdir -p "$ROOT/assets/rootfs"
    # cheap hardlink/cp for tooling that looks under assets/rootfs
    cp -f "$ROOTFS_SRC" "$ROOT/assets/rootfs/debian-trixie-arm64-rootfs.tar.gz" 2>/dev/null || true
    echo "slim APK: rootfs NOT embedded (stage on /data via bootstrap; ATLAS_EMBED_ROOTFS=1 for fat)"
  else
    echo "WARN: no rootfs tarball at $ROOTFS_SRC — hybrid bootstrap needs stage/push"
  fi
fi

# Native JNI libs (C)
mkdir -p "$BUILD/lib/arm64-v8a"
for so in libatlasterm.so libatlaspty.so; do
  SO_SRC="$(cd "$ROOT/../.." && pwd)/packages/titan_atlas/out/$so"
  if [ -f "$SO_SRC" ]; then
    cp -f "$SO_SRC" "$BUILD/lib/arm64-v8a/$so"
    (cd "$BUILD" && "$BT/aapt" add unsigned.apk "lib/arm64-v8a/$so")
    echo "embedded jni $so"
  else
    echo "WARN: missing $SO_SRC"
  fi
done

"$BT/zipalign" -f -p 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"
KS="$ROOT/debug.keystore"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Titan2,O=titanus2,C=EU"
fi
"$BT/apksigner" sign --min-sdk-version 28 \
  --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --out "$ROOT/TitanAtlas.apk" "$BUILD/aligned.apk"
echo "OK $ROOT/TitanAtlas.apk ($(stat -c%s "$ROOT/TitanAtlas.apk") bytes)"
