#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
FIND="${FIND:-/usr/bin/find}"
[ -x "$FIND" ] || FIND=find
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
BT=$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -1)
PLATFORM=$(ls -d "$SDK"/platforms/android-* 2>/dev/null | sort -V | tail -1)
[ -n "$BT" ] && [ -n "$PLATFORM" ] || { echo "need Android SDK"; exit 1; }
export PATH="$HOME/.local/jdk-17/bin:$HOME/.local/jdk/bin:/usr/lib/jvm/java-17-openjdk/bin:$PATH"
JAVAC=$(command -v javac)
JAR=$(command -v jar)
BUILD=$(mktemp -d)
trap 'rm -rf "$BUILD"' EXIT
mkdir -p "$BUILD"/{gen,obj}
"$BT/aapt" package -f -m -J "$BUILD/gen" -M "$ROOT/AndroidManifest.xml" -S "$ROOT/res" \
  -I "$PLATFORM/android.jar" -F "$BUILD/resources.ap_"
mapfile -t _JAVAS < <("$FIND" -H "$BUILD/gen" "$ROOT/src" -name '*.java')
if [ "${#_JAVAS[@]}" -lt 20 ]; then
  echo "build.sh: only ${#_JAVAS[@]} java files — refusing hollow APK (src symlink?)" >&2
  exit 1
fi
"$JAVAC" --release 17 -cp "$PLATFORM/android.jar" -d "$BUILD/obj" \
  "${_JAVAS[@]}"
(cd "$BUILD/obj" && "$JAR" cf "$BUILD/classes.jar" .)
"$BT/d8" --min-api 28 --output "$BUILD" "$BUILD/classes.jar"
cp "$BUILD/resources.ap_" "$BUILD/unsigned.apk"
(cd "$BUILD" && "$BT/aapt" add unsigned.apk classes.dex)
"$BT/zipalign" -f -p 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"
KS="$ROOT/debug.keystore"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=CubeContact,O=titanus2,C=EU"
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --out "$ROOT/CubeContact.apk" "$BUILD/aligned.apk"
echo "OK $ROOT/CubeContact.apk ($(stat -c%s "$ROOT/CubeContact.apk") bytes)"
