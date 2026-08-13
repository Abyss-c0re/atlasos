#!/usr/bin/env bash
# Fail if AtlasOS looks like a dump of trees, secrets, or OEM blobs.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ec=0
ok() { echo "OK  $*"; }
bad() { echo "BAD $*"; ec=1; }

cd "$ROOT"

# Trees that must never live in this git
for p in \
  misterztr_lineage lineage .repo \
  gsi/*.img firmware out/*.img \
  '*.tar.gz' debian-*-rootfs.tar.gz
do
  if compgen -G "$p" >/dev/null 2>&1; then
    bad "forbidden path present: $p"
  fi
done

if [ -d .git ]; then
  if git ls-files --error-unmatch '*.apk' >/dev/null 2>&1; then
    bad "APK tracked in git"
  else
    ok "no tracked APKs"
  fi
  if git ls-files | grep -qE 'debug\.keystore|\.idsig$|\.tar\.gz$'; then
    bad "keystore / idsig / tarball tracked"
  else
    ok "no tracked keystores / tarballs"
  fi
fi

# Secrets / lab identity (exclude this checker — it names the banned tokens)
if rg -n --hidden -g '!.git' -g '!.links' -g '!scripts/check_clean.sh' \
    'TITAN20000021925|BEGIN (RSA|OPENSSH) PRIVATE|sk-[a-zA-Z0-9]{16}' . >/dev/null 2>&1; then
  bad "lab serial or private key / token leaked"
  rg -n --hidden -g '!.git' -g '!.links' -g '!scripts/check_clean.sh' \
    'TITAN20000021925|BEGIN (RSA|OPENSSH) PRIVATE|sk-[a-zA-Z0-9]{16}' . || true
else
  ok "no lab serial / private key / token"
fi

# Cube lore must stay out of product docs (code comments may mention nanobot mesh)
if rg -l -i 'cube prophecy|all hail the cube' \
    --glob '!third_party/**' --glob '!.git/**' --glob '!scripts/check_clean.sh' . >/dev/null 2>&1; then
  bad "cube lore leaked into product docs"
  rg -l -i 'cube prophecy|all hail the cube' \
    --glob '!third_party/**' --glob '!.git/**' --glob '!scripts/check_clean.sh' . || true
else
  ok "cube kept light"
fi

[ -f chain/sources.yaml ] && ok "chain store present" || bad "missing chain/sources.yaml"
[ -f patches/gsi_source/SERIES ] && ok "gsi_source SERIES present" || bad "missing SERIES"
[ -d apps/titan_controls ] && ok "Titan Controls present" || bad "missing Controls"
[ -d apps/titan_atlas ] && ok "Atlas present" || bad "missing Atlas"
[ -x third_party/titan2-touchpadd/bin/titan2-touchpadd ] \
  && ok "touchpadd ELF present" || bad "missing touchpadd ELF"

echo "---"
if [ "$ec" -eq 0 ]; then
  echo "CLEAN — safe to commit AtlasOS (still never upload linked trees)"
else
  echo "DIRTY — fix before commit"
fi
exit "$ec"
