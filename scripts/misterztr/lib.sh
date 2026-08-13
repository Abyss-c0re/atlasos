#!/usr/bin/env bash
# Shared env for MisterZtr-faithful LineageOS GSI source builds.
# Recipe SoT: https://github.com/MisterZtr/LineageOS_gsi (branch lineage-23.2)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
export TITANUS2_ROOT="$ROOT"

if [ -f "$ROOT/config/misterztr.local.env" ]; then
  # shellcheck source=/dev/null
  source "$ROOT/config/misterztr.local.env"
elif [ -f "$ROOT/config/misterztr.env.example" ]; then
  # shellcheck source=/dev/null
  source "$ROOT/config/misterztr.env.example"
fi

# Tree lives outside the product git (tens of GB). Default under artifacts.
MISTERZTR_TREE="${MISTERZTR_TREE:-${TITANUS2_LINEAGE_ROOT:-$HOME/Dev/titanus2-artifacts/misterztr_lineage}}"
LINEAGE_BRANCH="${LINEAGE_BRANCH:-lineage-23.2}"
TREBLE_MANIFEST_BRANCH="${TREBLE_MANIFEST_BRANCH:-lineage-23.2}"
LINEAGE_GSI_REPO_BRANCH="${LINEAGE_GSI_REPO_BRANCH:-lineage-23.2}"
# VANILLA + EXT4 — matches product pin (BOOT_PATHS / known-good hybrid)
LUNCH_TARGET="${LUNCH_TARGET:-lineage_arm64_bvN4-bp4a-userdebug}"
# MisterZtr README uses -j4 for sync; allow override
REPO_JOBS="${REPO_JOBS:-4}"
BUILD_JOBS="${BUILD_JOBS:-$(nproc 2>/dev/null || echo 8)}"
# Do NOT default to shallow — shallow diverged from release trains before.
SHALLOW_INIT="${SHALLOW_INIT:-0}"
USE_CCACHE="${USE_CCACHE:-1}"
CCACHE_DIR="${CCACHE_DIR:-$HOME/.ccache}"
CCACHE_MAXSIZE="${CCACHE_MAXSIZE:-50G}"

# Java 17 required for treble_app (Gradle 7.5); AOSP lunch may use 17–21.
# Prefer portable Temurin under artifacts when distro has only Java 26+.
if [ -z "${JAVA_HOME:-}" ] || ! "$JAVA_HOME/bin/java" -version 2>&1 | grep -qE 'version "17\.|version "1[89]\.|version "2[01]\.'; then
  for cand in \
    "$HOME/Dev/titanus2-artifacts/jdk/usr/lib/jvm/java-17-temurin" \
    /usr/lib/jvm/java-17-openjdk \
    /usr/lib/jvm/java-17-temurin \
    "$HOME/Dev/titanus2-artifacts/jdk/usr/lib/jvm/java-21-temurin" \
    /usr/lib/jvm/java-21-temurin \
    /usr/lib/jvm/java-21-openjdk
  do
    if [ -x "$cand/bin/java" ]; then
      JAVA_HOME="$cand"
      break
    fi
  done
fi
if [ -n "${JAVA_HOME:-}" ]; then
  ANDROID_JAVA_HOME="${ANDROID_JAVA_HOME:-$JAVA_HOME}"
  PATH="$JAVA_HOME/bin:$PATH"
fi

export MISTERZTR_TREE LINEAGE_BRANCH TREBLE_MANIFEST_BRANCH LINEAGE_GSI_REPO_BRANCH \
  LUNCH_TARGET REPO_JOBS BUILD_JOBS SHALLOW_INIT \
  USE_CCACHE CCACHE_DIR CCACHE_MAXSIZE JAVA_HOME ANDROID_JAVA_HOME PATH

die() { echo "ERROR: $*" >&2; exit 1; }
info() { echo "==> $*"; }
warn() { echo "WARN: $*" >&2; }
have() { command -v "$1" >/dev/null 2>&1; }

if [ "${TITANUS2_RAISE_NOFILE:-1}" = "1" ]; then
  soft=$(ulimit -Sn 2>/dev/null || echo 0)
  if [ "${soft:-0}" -lt 65536 ] 2>/dev/null; then
    ulimit -Sn 65536 2>/dev/null || ulimit -Sn 16384 2>/dev/null || true
  fi
fi

repo_bin() {
  if have repo; then command -v repo
  elif [ -x "$HOME/.local/bin/repo" ]; then echo "$HOME/.local/bin/repo"
  else
    die "repo not found. Install: curl https://storage.googleapis.com/git-repo-downloads/repo -o ~/.local/bin/repo && chmod +x ~/.local/bin/repo"
  fi
}

# Guard: never install titanus2-only manifests into a pure MisterZtr tree.
strip_titanus2_manifests() {
  local d="$MISTERZTR_TREE/.repo/local_manifests"
  [ -d "$d" ] || return 0
  for man in titanus2.xml remove_non_gsi.xml; do
    if [ -f "$d/$man" ]; then
      warn "removing non-MisterZtr local manifest $man (pure recipe)"
      rm -f "$d/$man"
    fi
  done
}

require_tree() {
  [ -d "$MISTERZTR_TREE/.repo" ] || die "no tree at $MISTERZTR_TREE — run: ./scripts/misterztr/init.sh"
}
