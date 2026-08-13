#!/usr/bin/env bash
# Build TitanFm.apk — open FM app (aapt + javac + d8 + apksigner)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
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
[ -n "$JAR" ] || { echo "need jar"; exit 1; }

JNI_SRC="$ROOT/jniLibs/arm64-v8a/libaguifmjni.so"
[ -f "$JNI_SRC" ] || {
  STOCK="$ROOT/../../packages/titan_stock_fm_ir/prebuilt/fmradio/AguiFMRadio/lib/arm64/libaguifmjni.so"
  if [ -f "$STOCK" ]; then
    mkdir -p "$ROOT/jniLibs/arm64-v8a"
    cp -f "$STOCK" "$JNI_SRC"
  else
    echo "missing libaguifmjni.so — stage via packages/titan_stock_fm_ir/stage_from_stock.sh"
    exit 1
  fi
}

BUILD=$(mktemp -d)
trap 'rm -rf "$BUILD"' EXIT
mkdir -p "$BUILD"/{gen,obj}

"$BT/aapt" package -f -m -J "$BUILD/gen" -M "$ROOT/AndroidManifest.xml" -S "$ROOT/res" \
  -I "$PLATFORM/android.jar" -F "$BUILD/resources.ap_"

"$JAVAC" --release 17 -encoding UTF-8 -cp "$PLATFORM/android.jar" -d "$BUILD/obj" \
  $(find "$BUILD/gen" "$ROOT/src" -name '*.java')
(cd "$BUILD/obj" && "$JAR" cf "$BUILD/classes.jar" .)
"$BT/d8" --min-api 28 --output "$BUILD" "$BUILD/classes.jar"

cp "$BUILD/resources.ap_" "$BUILD/unsigned.apk"
(cd "$BUILD" && "$BT/aapt" add unsigned.apk classes.dex)

# Embed native lib (system install also places it under priv-app/lib/arm64).
# aapt add keeps the APK table intact (plain zip -u can drop AndroidManifest).
mkdir -p "$BUILD/lib/arm64-v8a"
cp -f "$JNI_SRC" "$BUILD/lib/arm64-v8a/libaguifmjni.so"
(cd "$BUILD" && "$BT/aapt" add unsigned.apk lib/arm64-v8a/libaguifmjni.so)

"$BT/zipalign" -f -p 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"
KS="$ROOT/debug.keystore"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Titan2,O=titanus2,C=EU"
fi
"$BT/apksigner" sign --min-sdk-version 28 \
  --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --out "$ROOT/TitanFm.apk" "$BUILD/aligned.apk"
echo "OK $ROOT/TitanFm.apk ($(stat -c%s "$ROOT/TitanFm.apk") bytes)"
