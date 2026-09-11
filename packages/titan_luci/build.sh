#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
BT=$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -1)
PLATFORM=$(ls -d "$SDK"/platforms/android-* 2>/dev/null | sort -V | tail -1)
export PATH="$HOME/.local/jdk-17/bin:/usr/lib/jvm/java-17-openjdk/bin:$PATH"
OUT="$ROOT/out"
rm -rf "$OUT/classes" "$OUT/gen"
mkdir -p "$OUT/gen" "$OUT/classes"
[ -d "$ROOT/res" ] || { echo "titan_luci: missing res/ — refuse hollow APK" >&2; exit 1; }
"$BT/aapt" package -f -m -J "$OUT/gen" -M "$ROOT/AndroidManifest.xml" \
  -S "$ROOT/res" -I "$PLATFORM/android.jar" -F "$OUT/res.apk"
mapfile -t JAVAS < <(find "$ROOT/src" "$OUT/gen" -name '*.java')
[ "${#JAVAS[@]}" -ge 1 ] || { echo "titan_luci: no java" >&2; exit 1; }
javac --release 17 -cp "$PLATFORM/android.jar" -d "$OUT/classes" "${JAVAS[@]}"
(cd "$OUT/classes" && jar cf "$OUT/classes.jar" .)
"$BT/d8" --min-api 29 --output "$OUT" "$OUT/classes.jar"
cp -f "$OUT/res.apk" "$OUT/unsigned.apk"
(cd "$OUT" && zip -qj unsigned.apk classes.dex)
unzip -Z1 "$OUT/unsigned.apk" | grep -qx resources.arsc \
  || { echo "titan_luci: no resources.arsc" >&2; exit 1; }
unzip -Z1 "$OUT/unsigned.apk" | grep -qx classes.dex \
  || { echo "titan_luci: no classes.dex" >&2; exit 1; }
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
KS="$HOME/.android/debug.keystore"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -v -keystore "$KS" -alias androiddebugkey \
    -storepass android -keypass android -keyalg RSA -keysize 2048 \
    -validity 10000 -dname "CN=Android Debug,O=Android,C=US" >/dev/null
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias androiddebugkey --out "$ROOT/TitanLuci.apk" "$OUT/aligned.apk"
echo "OK $ROOT/TitanLuci.apk ($(stat -c%s "$ROOT/TitanLuci.apk") bytes)"
