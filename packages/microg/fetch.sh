#!/usr/bin/env bash
# Download microG GmsCore + Companion + GsfProxy into prebuilt/ (not committed).
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=/dev/null
source "$DIR/PIN"
PRE="$DIR/prebuilt"
mkdir -p "$PRE"

fetch_one() {
  local url="$1" out="$2" sha="${3:-}"
  echo "Fetching $(basename "$out") ..."
  curl -fL --retry 3 -o "$out.partial" "$url"
  mv -f "$out.partial" "$out"
  if [ -n "$sha" ]; then
    echo "$sha  $out" | sha256sum -c -
  else
    echo "  sha256 $(sha256sum "$out" | awk '{print $1}')  (record in PIN after first good fetch)"
  fi
  echo "OK $out ($(stat -c%s "$out") bytes)"
}

fetch_one "$GMSCORE_URL" "$PRE/GmsCore.apk" "${GMSCORE_SHA256:-}"
fetch_one "$COMPANION_URL" "$PRE/FakeStore.apk" "${COMPANION_SHA256:-}"
# FakeStore.apk name kept for partner_gms naming; content is microG Companion (com.android.vending)
fetch_one "$GSFPROXY_URL" "$PRE/GsfProxy.apk" "${GSFPROXY_SHA256:-}"

echo "microG prebuilts ready under $PRE"
ls -lh "$PRE"/*.apk
