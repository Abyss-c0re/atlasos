#!/usr/bin/env bash
# Build safe Cube icon-mask RRO: square adaptive icons only (no radius/FGS thrash).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
BT=$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -1)
PLATFORM=$(ls -d "$SDK"/platforms/android-* 2>/dev/null | sort -V | tail -1)
[ -n "$BT" ] && [ -n "$PLATFORM" ] || { echo "need SDK"; exit 1; }
KEYS="${PLATFORM_KEYS:-$HOME/Dev/titanus2-artifacts/lineage/build/make/target/product/security}"
BUILD=$(mktemp -d)
trap 'rm -rf "$BUILD"' EXIT
"$BT/aapt" package -f -M "$ROOT/AndroidManifest.xml" -S "$ROOT/res" \
  -I "$PLATFORM/android.jar" -F "$BUILD/raw.apk"
"$BT/zipalign" -f -p 4 "$BUILD/raw.apk" "$BUILD/aligned.apk"
if [ -f "$KEYS/platform.pk8" ] && [ -f "$KEYS/platform.x509.pem" ]; then
  openssl pkcs8 -in "$KEYS/platform.pk8" -inform DER -outform PEM -nocrypt -out "$BUILD/platform.pem"
  openssl pkcs12 -export -in "$KEYS/platform.x509.pem" -inkey "$BUILD/platform.pem" \
    -out "$BUILD/platform.p12" -password pass:android -name android 2>/dev/null
  keytool -importkeystore -deststorepass android -destkeypass android \
    -destkeystore "$BUILD/platform.jks" -srckeystore "$BUILD/platform.p12" \
    -srcstoretype PKCS12 -srcstorepass android -alias android -noprompt >/dev/null 2>&1 || true
  "$BT/apksigner" sign --ks "$BUILD/platform.jks" --ks-pass pass:android --ks-key-alias android \
    --key-pass pass:android --out "$ROOT/TitanCubeIconMask.apk" "$BUILD/aligned.apk"
else
  KS="${KS:-$ROOT/../titan_ims/debug.keystore}"
  [ -f "$KS" ] || KS="$ROOT/../../apps/titan_controls/debug.keystore"
  "$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --out "$ROOT/TitanCubeIconMask.apk" "$BUILD/aligned.apk"
fi
echo "OK $ROOT/TitanCubeIconMask.apk"
