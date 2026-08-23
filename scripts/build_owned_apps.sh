#!/usr/bin/env bash
# Compile AtlasOS-owned apps from source in this clone.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

APPS=(
  apps/titan_controls
  apps/titan_usb_hid
  apps/cube_contact
  apps/titan_atlas
  apps/titan_fm
  apps/titan_ime
  apps/titan_nanobot
  packages/titan_luci
  packages/titan_netfw
)

fail=0
for a in "${APPS[@]}"; do
  if [ -x "$ROOT/$a/build.sh" ]; then
    echo "owned-app $a"
    if ! "$ROOT/$a/build.sh"; then
      echo "owned-app FAIL $a"
      fail=1
    fi
  else
    echo "owned-app skip $a (no build.sh)"
  fi
done
exit "$fail"
