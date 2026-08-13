#!/usr/bin/env bash
# Refresh prebuilt titan2-touchpadd from PeterGSI.
# WARNING: upstream prebuilt is NOT pad-only — it can open TitanKey and fight
# hid_bridge exclusive grab (B4). Hybrid pack refuses unpatched binaries.
# Prefer rebuild from patches/ (see README.md). Set ALLOW_UPSTREAM_PREBUILT=1
# only for emergency bootstrap, then rebuild before pack/flash.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
if [ "${ALLOW_UPSTREAM_PREBUILT:-0}" != "1" ]; then
  echo "REFUSE: fetch.sh pulls unpatched upstream prebuilt." >&2
  echo "Rebuild from patches/ (README.md) or ALLOW_UPSTREAM_PREBUILT=1 $0" >&2
  exit 1
fi
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
git clone --depth 1 https://gitea.angry.im/PeterGSI/android_vendor_prebuilts_titan2-touchpadd.git "$TMP/pre"
cp -f "$TMP/pre/titan2-touchpadd" "$HERE/bin/titan2-touchpadd"
chmod 755 "$HERE/bin/titan2-touchpadd"
file "$HERE/bin/titan2-touchpadd"
echo "Updated $HERE/bin/titan2-touchpadd (UNPATCHED — rebuild before hybrid pack)"
