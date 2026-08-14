#!/usr/bin/env bash
# Build TitanNetFw.apk (aapt + javac + d8). Platform-signed at GSI import.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
FIND="${FIND:-/usr/bin/find}"
[ -x "$FIND" ] || FIND=find
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
BT=$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -1)
PLATFORM=$(ls -d "$SDK"/platforms/android-* 2>/dev/null | sort -V | tail -1)
[ -n "$BT" ] && [ -n "$PLATFORM" ] || { echo "need Android SDK"; exit 1; }
export PATH="$HOME/.local/jdk-17/bin:/usr/lib/jvm/java-17-openjdk/bin:$PATH"
OUT="$ROOT/out"
GEN="$OUT/gen"
CLS="$OUT/classes"
rm -rf "$CLS"
mkdir -p "$GEN" "$CLS"
AAPT_RES=()
[ -d "$ROOT/res" ] && AAPT_RES=(-S "$ROOT/res")
"$BT/aapt" package -f -m -J "$GEN" -M "$ROOT/AndroidManifest.xml" \
  "${AAPT_RES[@]}" -I "$PLATFORM/android.jar" -F "$OUT/res.apk"
mapfile -t JAVAS < <("$FIND" -H "$ROOT/src" "$GEN" -name '*.java')
if [ "${#JAVAS[@]}" -lt 8 ]; then
  echo "build.sh: only ${#JAVAS[@]} java files — refusing hollow APK" >&2
  exit 1
fi
javac --release 17 -cp "$PLATFORM/android.jar" -d "$CLS" "${JAVAS[@]}"
(cd "$CLS" && jar cf "$OUT/classes.jar" .)
"$BT/d8" --min-api 34 --output "$OUT" "$OUT/classes.jar"
cp -f "$OUT/res.apk" "$OUT/unsigned.apk"
(cd "$OUT" && zip -qj unsigned.apk classes.dex)
cp -f "$OUT/unsigned.apk" "$ROOT/TitanNetFw.apk"
PK8="${PLATFORM_PK8:-$HOME/Dev/device-workshop/products/titanus2/out/setupwizard_extract/keys/platform.pk8}"
CERT="${PLATFORM_CERT:-$HOME/Dev/device-workshop/products/titanus2/out/setupwizard_extract/keys/platform.x509.pem}"
if [ -f "$PK8" ] && [ -f "$CERT" ] && [ -x "$BT/apksigner" ]; then
  "$BT/apksigner" sign --key "$PK8" --cert "$CERT" --out "$OUT/TitanNetFw-platform.apk" "$ROOT/TitanNetFw.apk"
  cp -f "$OUT/TitanNetFw-platform.apk" "$ROOT/TitanNetFw.apk"
fi
sz=$(stat -c%s "$ROOT/TitanNetFw.apk")
[ "$sz" -ge 10000 ] || { echo "hollow APK $sz" >&2; exit 1; }
unzip -Z1 "$ROOT/TitanNetFw.apk" >"$OUT/apk.list"
grep -qx classes.dex "$OUT/apk.list" || { echo "no classes.dex" >&2; exit 1; }
echo "OK $ROOT/TitanNetFw.apk ($sz bytes)"
