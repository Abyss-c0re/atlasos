#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
# Workshop tree src/api are symlinks into AtlasOS. find(1) does not follow a
# starting-point symlink, so a workshop rebuild compiled only aapt R.java and
# shipped a 2556-byte classes.dex — live Titan ClassNotFound MainActivity.
SRC="$(cd "$ROOT/src" && pwd -P)"
API_SRC="$(cd "$ROOT/../titan2_api/src" && pwd -P)"
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
AAPT_ASSETS=()
if [ -d "$ROOT/assets" ]; then AAPT_ASSETS=(-A "$ROOT/assets"); fi
"$BT/aapt" package -f -m -J "$BUILD/gen" -M "$ROOT/AndroidManifest.xml" -S "$ROOT/res" \
  "${AAPT_ASSETS[@]}" -I "$PLATFORM/android.jar" -F "$BUILD/resources.ap_"
mapfile -t JAVA < <(find "$BUILD/gen" "$SRC" "$API_SRC" -name '*.java' | sort)
[ "${#JAVA[@]}" -ge 20 ] || { echo "too few java files (${#JAVA[@]}) — src not followed? SRC=$SRC"; exit 1; }
printf '%s\n' "${JAVA[@]}" | grep -q '/MainActivity.java$' \
  || { echo "MainActivity.java missing from compile set"; exit 1; }
"$JAVAC" --release 17 -cp "$PLATFORM/android.jar" -d "$BUILD/obj" "${JAVA[@]}"
[ -f "$BUILD/obj/com/titanus2/nanobot/MainActivity.class" ] \
  || { echo "javac did not emit MainActivity.class"; exit 1; }
(cd "$BUILD/obj" && "$JAR" cf "$BUILD/classes.jar" .)
"$BT/d8" --min-api 28 --output "$BUILD" "$BUILD/classes.jar"
DEX_SZ=$(stat -c%s "$BUILD/classes.dex")
[ "$DEX_SZ" -ge 50000 ] || { echo "classes.dex hollow ($DEX_SZ bytes)"; exit 1; }
# grep -a on the dex (not strings|grep -q): pipefail + SIGPIPE false-fails the gate.
grep -a -F 'Lcom/titanus2/nanobot/MainActivity;' "$BUILD/classes.dex" >/dev/null \
  || { echo "MainActivity missing from classes.dex"; exit 1; }
cp "$BUILD/resources.ap_" "$BUILD/unsigned.apk"
(cd "$BUILD" && "$BT/aapt" add unsigned.apk classes.dex)
"$BT/zipalign" -f -p 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"
KS="$ROOT/debug.keystore"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Titan2,O=titanus2,C=EU"
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --out "$ROOT/Nanobot.apk" "$BUILD/aligned.apk"
# Compat filename for older ROM / Magisk scripts (same bytes)
cp -f "$ROOT/Nanobot.apk" "$ROOT/TitanNanobot.apk"
echo "OK $ROOT/Nanobot.apk ($(stat -c%s "$ROOT/Nanobot.apk") bytes) dex=$DEX_SZ + TitanNanobot.apk compat"
