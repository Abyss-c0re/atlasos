#!/system/bin/sh
# grok-android — run Linux grok on Titan (KernelSU-aware, no /data/local/tmp write)
# Real ELF: $HOME/.grok/downloads/grok-linux-aarch64
#
export PATH="/system/bin:/system/xbin:/vendor/bin:$PATH"

HOME="${HOME:-$ATLAS_HOME}"
if [ -z "$HOME" ] || [ ! -d "$HOME" ]; then
  for h in /data/user/0/com.titanus2.atlas/files /data/data/com.titanus2.atlas/files; do
    [ -d "$h" ] && HOME=$h && break
  done
fi
export HOME
export ATLAS_HOME="${ATLAS_HOME:-$HOME}"
export ATLAS_BIN="${ATLAS_BIN:-$HOME/bin}"
export TERM="${TERM:-xterm-256color}"
export LANG="${LANG:-C.UTF-8}"
export PATH="$ATLAS_BIN:$HOME/.grok/bin:/system/bin:/system/xbin:/vendor/bin"

REAL="$HOME/.grok/downloads/grok-linux-aarch64"
if [ ! -x "$REAL" ]; then
  echo "grok: missing $REAL" >&2
  echo "install: bash -c 'curl -fsSL https://x.ai/cli/install.sh | bash'" >&2
  exit 127
fi

# All temps under app HOME (app UID can write; /data/local/tmp often denied)
mkdir -p "$HOME/.grok" "$HOME/tmp" 2>/dev/null || true
TMPD="$HOME/tmp"
URL_FILE="$HOME/.grok/atlas_login_url"
rm -f "$URL_FILE" 2>/dev/null || true

# Open URL in Android browser
OPEN="$ATLAS_BIN/atlas-open-url.sh"
printf '%s\n' '#!/system/bin/sh' \
  'url="$1"' \
  '[ -n "$url" ] || exit 0' \
  'am start -a android.intent.action.VIEW -d "$url" >/dev/null 2>&1' \
  'exit $?' >"$OPEN"
chmod 755 "$OPEN" 2>/dev/null || true
export BROWSER="$OPEN"

# KernelSU root helper
_su() {
  if [ "$(id -u 2>/dev/null)" = "0" ]; then
    "$@"
    return $?
  fi
  for su in /system/bin/su /system/xbin/su su; do
    if [ -x "$su" ] || command -v "$su" >/dev/null 2>&1; then
      "$su" 0 "$@" 2>/dev/null && return 0
      "$su" -c "$*" 2>/dev/null && return 0
    fi
  done
  return 127
}

# DNS: musl/static ELF needs resolv.conf. Prefer Android active network
# (VPN first — Tailscale MagicDNS etc.). Never hardcode public DNS when a
# VPN is present; that bypasses the tunnel and breaks hybrid networking.
_atlas_android_dns_body() {
  dump=`dumpsys connectivity 2>/dev/null` || dump=
  emit_ips() {
    tr ',' '\n' | sed 's|^[[:space:]]*/*||;s|[[:space:]]||g' | \
      grep -E '^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$|^[0-9a-fA-F:]+$'
  }
  pick() {
    mode="$1"
    echo "$dump" | sed 's/NetworkAgentInfo{/\nNetworkAgentInfo{/g' | while IFS= read -r block; do
      [ -n "$block" ] || continue
      case "$block" in *DnsAddresses:*) ;; *) continue ;; esac
      if [ "$mode" = vpn ]; then
        case "$block" in *IS_VPN*|*'ni{VPN '*) ;; *) continue ;; esac
      else
        case "$block" in *IS_VPN*|*ni{VPN*) continue ;; esac
        case "$block" in *INTERNET*|*TRANSPORT_PRIMARY*) ;; *) continue ;; esac
      fi
      echo "$block" | sed -n 's/.*DnsAddresses: \[ *\([^]]*\)\].*/\1/p' | emit_ips
    done
  }
  seen=; n=0
  add() {
    ip="$1"; [ -n "$ip" ] || return 0
    case " $seen " in *" $ip "*) return 0 ;; esac
    seen="$seen $ip"; echo "nameserver $ip"; n=$((n + 1))
  }
  for ip in `pick vpn`; do add "$ip"; done
  [ "$n" -gt 0 ] && return 0
  for ip in `pick net`; do add "$ip"; done
  [ "$n" -gt 0 ] && return 0
  for p in net.dns1 net.dns2; do
    v=`getprop "$p" 2>/dev/null`; [ -n "$v" ] && add "$v"
  done
  [ "$n" -gt 0 ] && return 0
  if ip -o link show 2>/dev/null | grep -q ' tun[0-9]'; then
    return 1
  fi
  echo "nameserver 8.8.8.8"
  echo "nameserver 1.1.1.1"
}

run_grok() {
  runner="$TMPD/atlas-grok-run.sh"
  dns_body=`_atlas_android_dns_body 2>/dev/null` || dns_body=
  {
    echo '#!/system/bin/sh'
    echo "export HOME='$HOME'"
    echo "export PATH='$PATH'"
    echo "export TERM='$TERM'"
    echo "export LANG='$LANG'"
    echo "export BROWSER='$OPEN'"
    echo "REAL='$REAL'"
    echo 'if [ "$(id -u)" = "0" ]; then'
    echo '  mount -t tmpfs -o size=2m tmpfs /system/etc 2>/dev/null || true'
    if [ -n "$dns_body" ]; then
      echo "  cat > /system/etc/resolv.conf <<'ATLAS_DNS_EOF'"
      echo "$dns_body"
      echo 'ATLAS_DNS_EOF'
    fi
    echo 'fi'
    echo 'exec "$REAL" "$@"'
  } >"$runner"
  chmod 755 "$runner" 2>/dev/null || true

  if [ "$(id -u)" = "0" ]; then
    unshare -m "$runner" "$@"
    return $?
  fi
  # KernelSU: su 0 unshare -m runner args…
  if _su unshare -m "$runner" "$@"; then
    return 0
  fi
  # Fallback: direct exec (no DNS fix) — better than permission denied
  echo "grok: warn — no root unshare; DNS may fail (grant KernelSU for Atlas/Shell)" >&2
  exec "$REAL" "$@"
}

case "${1:-}" in
  login)
    # login via same runner path (temps under $HOME)
    run_grok login --device-auth 2>&1 | tee "$TMPD/atlas-grok-login.out" &
    pid=$!
    i=0
    url=""
    while [ $i -lt 60 ]; do
      if [ -f "$TMPD/atlas-grok-login.out" ]; then
        url=`grep -o 'https://accounts.x.ai/oauth2/device?user_code=[A-Za-z0-9-]*' "$TMPD/atlas-grok-login.out" 2>/dev/null | head -1`
        if [ -z "$url" ]; then
          code=`grep -oE '[A-Z0-9]{4}-[A-Z0-9]{4}' "$TMPD/atlas-grok-login.out" 2>/dev/null | head -1`
          [ -n "$code" ] && url="https://accounts.x.ai/oauth2/device?user_code=$code"
        fi
        if [ -n "$url" ]; then
          printf '%s\n' "$url" >"$URL_FILE"
          chmod 666 "$URL_FILE" 2>/dev/null || true
          echo ""
          echo "############################################"
          echo "# LOGIN READY — tap cyan link in Atlas"
          echo "# $url"
          echo "############################################"
          echo ""
          /system/bin/sh "$OPEN" "$url" >/dev/null 2>&1 &
          break
        fi
      fi
      i=$((i + 1))
      sleep 0.5
    done
    wait $pid 2>/dev/null
    exit $?
    ;;
esac

run_grok "$@"
