#!/usr/bin/env bash
# Fail if this git is not safe to push to a public remote.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
ec=0
ok() { echo "OK  $*"; }
bad() { echo "BAD $*"; ec=1; }

[ -d .git ] || { echo "ERROR: not a git repo" >&2; exit 2; }

tracked=$(git ls-files)

echo "$tracked" | grep -qiE '\.apk$' && bad "APK tracked" || ok "no APKs"
echo "$tracked" | grep -qiE 'debug\.keystore|\.keystore$' && bad "keystore tracked" || ok "no keystores"
echo "$tracked" | grep -qiE '\.tar\.gz$|debian-.*rootfs\.tar' && bad "rootfs tarball tracked" || ok "no rootfs tarball"
echo "$tracked" | grep -qiE 'libimsma|libeap-aka|GmsCore\.apk|FakeStore\.apk|vending-.*\.apk' \
  && bad "Play/IMS blob tracked" || ok "no Play/IMS blobs"

if echo "$tracked" | grep -qx 'apps/titan_atlas/assets/bin/bash'; then
  bad "GNU bash ELF tracked (GPLv3, no source tree here)"
else
  ok "no bash ELF"
fi
if echo "$tracked" | grep -qx 'apps/titan_atlas/assets/bin/quest-usbip-host'; then
  bad "quest-usbip-host ELF tracked (source not in this repo)"
else
  ok "no quest-usbip-host ELF"
fi
if echo "$tracked" | grep -qx 'third_party/titan2-touchpadd/bin/titan2-touchpadd'; then
  bad "touchpadd ELF tracked — rebuild from patches, do not upload prebuilt"
else
  ok "no touchpadd ELF"
fi

# Tracked ELF must be ours (NDK pie from our .c) or have a sibling .c
while IFS= read -r f; do
  [ -z "$f" ] && continue
  [ -f "$f" ] || continue
  ft=$(file -b "$f" 2>/dev/null || true)
  case "$ft" in
    ELF*)
      base=$(basename "$f")
      case "$base" in
        atlas|atlas-*|ptyexec|su|sudo|hid_bridge|openwrt-lpctl|titan2-*)
          ok "own ELF $f"
          ;;
        *)
          bad "unexpected ELF tracked: $f ($ft)"
          ;;
      esac
      ;;
  esac
done <<EOF
$(echo "$tracked")
EOF

[ -f NOTICE.md ] && ok "NOTICE.md" || bad "missing NOTICE.md"
[ -f apps/titan_atlas/src/com/termux/LICENSE ] && ok "Termux GPLv3" || bad "Termux sources without LICENSE"
[ -f third_party/pocket-board/LICENSE ] && ok "PocketBoard LICENSE" || bad "PocketBoard without LICENSE"
[ -f third_party/LICENSES/XTERMJS-MIT.txt ] && ok "xterm.js MIT" || bad "missing xterm.js notice"
[ -f third_party/LICENSES/DEJAVU.txt ] && ok "DejaVu notice" || bad "missing DejaVu notice"

if git grep -q 'TITAN20000021925' -- ':!scripts/check_clean.sh' ':!scripts/check_publish.sh'; then
  bad "lab serial in git"
else
  ok "no lab serial"
fi

echo "---"
if [ "$ec" -eq 0 ]; then
  echo "PUBLISH OK — no known license/blob blockers in git"
else
  echo "PUBLISH FAIL — do not push"
fi
exit "$ec"
