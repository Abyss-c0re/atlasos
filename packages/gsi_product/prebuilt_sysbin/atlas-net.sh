#!/system/bin/sh
# atlas-net — Atlas PTY entry (ROM hybrid OS surface).
#
# Product model:
#   Base ELFs live on the system image (/system, /system_ext, /product).
#   Priv-app is the Terminal + Auth Agent UI; $HOME/bin is user overlay only.
#   Never exec app-private ELFs as the primary shell — SELinux blocks
#   priv_app execute_no_trans on privapp_data_file (EACCES / exit 126).
#
# Identity:
#   Interactive shell is always **admin** = Android app UID (non-root).
#   Root only via: sudo/su → Authentication Agent → real KernelSU / setuid path.
#   Hybrid: root mounts once, then drops to admin before the shell.
#
export PATH="/system/bin:/system/xbin:/system_ext/bin:/product/bin:/vendor/bin:/data/adb/ksu/bin:$PATH"

# Prefer /data/data (canonical). /data/user/0 is often mode 700 → EACCES for app UID.
# Never pick a root-owned shadow tree (post-crash thrash).
_atlas_resolve_home() {
  for h in \
    /data/data/com.titanus2.atlas/files \
    /data/user/0/com.titanus2.atlas/files
  do
    [ -d "$h" ] || continue
    # skip unreadable (parent mode 700) or root-owned shadows
    [ -r "$h" ] || continue
    ou=`stat -c %u "$h" 2>/dev/null`
    [ -n "$ou" ] && [ "$ou" != "0" ] && echo "$h" && return 0
  done
  # last resort
  [ -d /data/data/com.titanus2.atlas/files ] && echo /data/data/com.titanus2.atlas/files && return 0
  echo /data/data/com.titanus2.atlas/files
}

# Deb hybrid: product HOME on Android data (not CE files — chdir EACCES after enter).
_atlas_linux_home() {
  h="${ATLAS_LINUX_HOME:-/data/local/atlas-home/atlas}"
  mkdir -p "$h" 2>/dev/null || true
  [ -d "$h" ] && [ -r "$h" ] && echo "$h" && return 0
  echo "$h"
}

HOME="${HOME:-$ATLAS_HOME}"
# Deb/hybrid session: always use linux home when plane is hybrid
case "${ATLAS_SESSION:-}${ATLAS_MODE:-}${ATLAS_HYBRID:-0}" in
  *hybrid*|*debian*|1)
    HOME=`_atlas_linux_home`
    ;;
esac
if [ -z "$HOME" ] || [ ! -d "$HOME" ] || [ ! -r "$HOME" ]; then
  case "${ATLAS_HYBRID:-0}${ATLAS_MODE:-}" in
    1*|debian*) HOME=`_atlas_linux_home` ;;
    *) HOME=`_atlas_resolve_home` ;;
  esac
fi
# Never keep unreadable CE path for Deb (Permission denied chdir)
case "$HOME" in
  /data/data/com.titanus2.atlas/*|/data/user/*/com.titanus2.atlas/*)
    if [ "${ATLAS_HYBRID:-0}" = "1" ] || [ "${ATLAS_MODE:-}" = "debian" ] \
        || [ "${ATLAS_SESSION:-}" = "hybrid" ]; then
      HOME=`_atlas_linux_home`
    elif [ ! -r "$HOME" ] || [ "$(stat -c %u "$HOME" 2>/dev/null)" = "0" ]; then
      HOME=`_atlas_resolve_home`
    fi
    ;;
esac
export HOME
export ATLAS_HOME="${ATLAS_HOME:-$HOME}"
# Auth plane: LP when available, else under linux home (until LP remount rw)
if [ -z "${ATLAS_AUTH_DIR:-}" ]; then
  if [ -d /data/local/atlas-linux/var/lib/atlas-auth ]; then
    export ATLAS_AUTH_DIR=/data/local/atlas-linux/var/lib/atlas-auth
  elif [ -d /var/lib/atlas-auth ]; then
    export ATLAS_AUTH_DIR=/var/lib/atlas-auth
  else
    export ATLAS_AUTH_DIR="$HOME/auth"
    mkdir -p "$ATLAS_AUTH_DIR" 2>/dev/null || true
  fi
fi
# User overlay (curl installs, tip scripts). Not the ROM base.
export ATLAS_USER_BIN="${ATLAS_USER_BIN:-$HOME/bin}"
# ROM / system Atlas tool dir (SoT for auth/sudo/hybrid helpers once injected).
export ATLAS_SYSBIN="${ATLAS_SYSBIN:-/system/bin}"

# DNS for static Linux ELFs (grok/musl). Android apps use netd (VPN/Private DNS);
# glibc/musl only read resolv.conf + UDP/53. Always follow Android's *active*
# network DNS — VPN first (Tailscale MagicDNS, WireGuard, etc.). Never pin
# public 8.8.8.8 when a VPN is up (that bypasses/breaks the VPN path).
atlas_android_dns_body() {
  dump=`dumpsys connectivity 2>/dev/null` || dump=
  emit_ips() {
    tr ',' '\n' | sed 's|^[[:space:]]*/*||;s|[[:space:]]||g' | \
      grep -E '^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$|^[0-9a-fA-F:]+$'
  }
  pick() {
    mode="$1"  # vpn|net
    echo "$dump" | sed 's/NetworkAgentInfo{/\nNetworkAgentInfo{/g' | while IFS= read -r block; do
      [ -n "$block" ] || continue
      case "$block" in *DnsAddresses:*) ;; *) continue ;; esac
      if [ "$mode" = vpn ]; then
        # Strict: IS_VPN / ni{VPN — never match NOT_VPN substring.
        case "$block" in
          *IS_VPN*) ;;
          *'ni{VPN '*) ;;
          *'ni{VPN}'*) ;;
          *'ni{VPN:'*) ;;
          *) continue ;;
        esac
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
  # 1) Android VPN (Tailscale 100.100.100.100, Proton, WG, …)
  for ip in `pick vpn`; do add "$ip"; done
  [ "$n" -gt 0 ] && return 0
  # 2) Non-VPN validated internet (carrier / Wi‑Fi DnsAddresses)
  for ip in `pick net`; do add "$ip"; done
  [ "$n" -gt 0 ] && return 0
  # 3) Legacy props (often empty on modern Android)
  for p in net.dns1 net.dns2 net.dns3 net.dns4; do
    v=`getprop "$p" 2>/dev/null`
    [ -n "$v" ] && add "$v"
  done
  [ "$n" -gt 0 ] && return 0
  # 4) If a tun is up we still refuse public DNS (would ignore VPN).
  if ip -o link show 2>/dev/null | grep -q ' tun[0-9][0-9]*:'; then
    return 1
  fi
  # 5) Last resort only when no Android DNS and no tun.
  echo "nameserver 8.8.8.8"
  echo "nameserver 1.1.1.1"
}

atlas_dns_heal() {
  # Stage DNS quietly. App UID never touches hybrid merge/lower (root-owned) —
  # shell "can't create … Permission denied" was spam after flash on Deb enter.
  # Hybrid resolv is written only under su 0 (and again by atlas-hybrid enter).
  # Always refresh (VPN connect/disconnect must update resolv).
  body=`atlas_android_dns_body 2>/dev/null` || body=
  [ -n "$body" ] || return 0
  # Writable app-side candidates only (probe write; never print failures)
  for d in \
    "$HOME/etc" \
    /data/data/com.titanus2.atlas/files/etc \
    /data/local/tmp/atlas-dns
  do
    [ -n "$d" ] || continue
    cand="$d/resolv.conf"
    if mkdir -p "$d" >/dev/null 2>&1 \
      && ( umask 022; : >"$cand" ) >/dev/null 2>&1
    then
      ( printf '%s\n' "$body" >"$cand" ) >/dev/null 2>&1 || true
    fi
  done
  # Root: hybrid merge/lower + layout heal + stage + optional system bind
  for s in /system/bin/su /system/xbin/su /data/adb/ksu/bin/su; do
    [ -x "$s" ] || continue
    # Pass body via env to avoid nested-quote breakage; silence all child noise
    ATLAS_DNS_BODY="$body" "$s" 0 sh -c '
      # Multi-user path walk (app UID cannot enter mode-700 /data/user/0)
      chmod 711 /data/user /data/user/0 2>/dev/null || true
      REAL=/data/data/com.titanus2.atlas
      FAKE=/data/user/0/com.titanus2.atlas
      if [ -d "$REAL" ]; then
        [ -L "$FAKE" ] && rm -f "$FAKE" 2>/dev/null || true
        if ! mount 2>/dev/null | grep -q " $FAKE "; then
          if [ -d "$FAKE" ]; then
            ri=`stat -c %i "$REAL" 2>/dev/null`
            fi_=`stat -c %i "$FAKE" 2>/dev/null`
            if [ -n "$ri" ] && [ "$ri" != "$fi_" ]; then
              rm -rf /data/local/tmp/atlas-user0-shadow.bak 2>/dev/null || true
              mv "$FAKE" /data/local/tmp/atlas-user0-shadow.bak 2>/dev/null || rm -rf "$FAKE"
            fi
          fi
          mkdir -p "$FAKE" 2>/dev/null || true
          mount --bind "$REAL" "$FAKE" 2>/dev/null || true
        fi
      fi
      mkdir -p /data/local/tmp/atlas-dns 2>/dev/null || true
      chmod 777 /data/local/tmp/atlas-dns 2>/dev/null || true
      SRC=/data/local/tmp/atlas-dns/resolv.conf
      BODY="${ATLAS_DNS_BODY:-}"
      if [ -z "$BODY" ]; then
        dump=`dumpsys connectivity 2>/dev/null` || dump=
        emit_ips() {
          tr "," "\n" | sed "s|^[[:space:]]*/*||;s|[[:space:]]||g" | \
            grep -E "^[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+$|^[0-9a-fA-F:]+$"
        }
        pick() {
          mode="$1"
          echo "$dump" | sed "s/NetworkAgentInfo{/\\nNetworkAgentInfo{/g" | while IFS= read -r block; do
            [ -n "$block" ] || continue
            case "$block" in *DnsAddresses:*) ;; *) continue ;; esac
            if [ "$mode" = vpn ]; then
              case "$block" in *IS_VPN*|*"ni{VPN "*|*"ni{VPN}"*|*"ni{VPN:"*) ;; *) continue ;; esac
            else
              case "$block" in *IS_VPN*|*ni{VPN*) continue ;; esac
              case "$block" in *INTERNET*|*TRANSPORT_PRIMARY*) ;; *) continue ;; esac
            fi
            echo "$block" | sed -n "s/.*DnsAddresses: \\[ *\\([^]]*\\)\\].*/\\1/p" | emit_ips
          done
        }
        seen=; n=0
        add() {
          ip="$1"; [ -n "$ip" ] || return 0
          case " $seen " in *" $ip "*) return 0 ;; esac
          seen="$seen $ip"; BODY="${BODY}nameserver $ip
"; n=$((n+1))
        }
        for ip in `pick vpn`; do add "$ip"; done
        if [ "$n" -eq 0 ]; then
          for ip in `pick net`; do add "$ip"; done
        fi
        if [ "$n" -eq 0 ]; then
          for p in net.dns1 net.dns2 net.dns3 net.dns4; do
            v=`getprop "$p" 2>/dev/null`; [ -n "$v" ] && add "$v"
          done
        fi
        if [ "$n" -eq 0 ]; then
          if ! ip -o link show 2>/dev/null | grep -q " tun[0-9]"; then
            BODY="nameserver 8.8.8.8
nameserver 1.1.1.1
"
          fi
        fi
      fi
      [ -n "$BODY" ] || exit 0
      printf "%s\n" "$BODY" >"$SRC" 2>/dev/null || true
      chmod 644 "$SRC" 2>/dev/null || true
      # Hybrid tree — root only; world-readable for admin drop inside chroot
      for dest in \
        /data/local/atlas-hybrid/merge/etc/resolv.conf \
        /data/local/atlas-hybrid/lower/etc/resolv.conf
      do
        ddir=`dirname "$dest" 2>/dev/null`
        [ -d "$ddir" ] || mkdir -p "$ddir" 2>/dev/null || continue
        printf "%s\n" "$BODY" >"$dest" 2>/dev/null || true
        chmod 644 "$dest" 2>/dev/null || true
      done
      AH=/data/data/com.titanus2.atlas/files
      AU=$(stat -c %u /data/data/com.titanus2.atlas 2>/dev/null || echo 10198)
      if [ -d "$AH" ] && [ -n "$AU" ] && [ "$AU" != "0" ]; then
        mkdir -p "$AH/etc" 2>/dev/null || true
        [ -s "$SRC" ] && cp -f "$SRC" "$AH/etc/resolv.conf" 2>/dev/null || true
        chown "$AU:$AU" "$AH/etc/resolv.conf" 2>/dev/null || true
        chmod 644 "$AH/etc/resolv.conf" 2>/dev/null || true
      fi
      [ -s "$SRC" ] || exit 0
      if [ -e /system/etc/resolv.conf ] || touch /system/etc/resolv.conf 2>/dev/null; then
        mount --bind "$SRC" /system/etc/resolv.conf 2>/dev/null && exit 0
      fi
      mount -o rw,remount /system 2>/dev/null || mount -o rw,remount / 2>/dev/null || true
      if [ -d /system/etc ]; then
        cp -f "$SRC" /system/etc/resolv.conf
        chmod 644 /system/etc/resolv.conf
      fi
    ' >/dev/null 2>&1 && break
  done
}
atlas_dns_heal


# Resolve core tools: system image first, user overlay last (never preferred for base).
find_tool() {
  name="$1"
  for c in \
    "$ATLAS_SYSBIN/$name" \
    /system/bin/"$name" \
    /system_ext/bin/"$name" \
    /product/bin/"$name" \
    /vendor/bin/"$name" \
    "$ATLAS_USER_BIN/$name" \
    "$HOME/bin/$name"
  do
    [ -n "$c" ] && [ -x "$c" ] && echo "$c" && return 0
  done
  return 1
}

# Interactive shell: Android/system bash only. App-data static bash is last resort.
find_bash() {
  for c in \
    /system/bin/bash \
    /system_ext/bin/bash \
    /product/bin/bash \
    /vendor/bin/bash \
    "$ATLAS_SYSBIN/bash"
  do
    [ -x "$c" ] && echo "$c" && return 0
  done
  # Last resort: user overlay (may fail SELinux on privapp_data_file)
  if [ -x "$ATLAS_USER_BIN/bash" ]; then
    echo "$ATLAS_USER_BIN/bash"
    return 0
  fi
  return 1
}

# ATLAS_BIN: PATH search for user tools + thin wrappers. Prefer user dir if present
# so curl-installed bins win over nothing, but system bins stay on PATH after.
export ATLAS_BIN="${ATLAS_BIN:-$ATLAS_USER_BIN}"
mkdir -p "$HOME/bin" "$HOME/.local/bin" "$HOME/.cargo/bin" \
  "$HOME/.npm-global/bin" "$HOME/.grok/bin" 2>/dev/null || true

BASH_BIN=`find_bash`
ATLAS_AUTH_BIN=`find_tool atlas-auth`
ATLAS_ASKPASS_BIN=`find_tool atlas-auth-askpass`
ATLAS_SUDO_BIN=`find_tool atlas-sudo`
# Fallbacks for auth path when only user overlay has the ELFs (dev tip)
[ -z "$ATLAS_AUTH_BIN" ] && [ -x "$ATLAS_USER_BIN/atlas-auth" ] && ATLAS_AUTH_BIN="$ATLAS_USER_BIN/atlas-auth"
[ -z "$ATLAS_ASKPASS_BIN" ] && [ -x "$ATLAS_USER_BIN/atlas-auth-askpass" ] && ATLAS_ASKPASS_BIN="$ATLAS_USER_BIN/atlas-auth-askpass"
[ -z "$ATLAS_SUDO_BIN" ] && [ -x "$ATLAS_USER_BIN/atlas-sudo" ] && ATLAS_SUDO_BIN="$ATLAS_USER_BIN/atlas-sudo"

export PATH="$ATLAS_USER_BIN:$HOME/bin:$HOME/.local/bin:$HOME/.cargo/bin:$HOME/.npm-global/bin:$HOME/.grok/bin:/system/bin:/system_ext/bin:/product/bin:/system/xbin:/vendor/bin"
export TERM="${TERM:-xterm-256color}"
export LANG="${LANG:-C.UTF-8}"
export COLORTERM="${COLORTERM:-truecolor}"
export USER="${USER:-admin}"
export LOGNAME="${LOGNAME:-admin}"
export ATLAS_ROLE=admin
[ -f "$HOME/cacert.pem" ] && export SSL_CERT_FILE="$HOME/cacert.pem"
[ -d /apex/com.android.conscrypt/cacerts ] && export SSL_CERT_DIR=/apex/com.android.conscrypt/cacerts

# Admin uid = package owner (never 0)
ADMIN_UID=`id -u 2>/dev/null`
if [ -z "$ADMIN_UID" ] || [ "$ADMIN_UID" = "0" ]; then
  ADMIN_UID=`stat -c %u "$HOME" 2>/dev/null || stat -c %u /data/data/com.titanus2.atlas 2>/dev/null || echo ""`
fi

find_hybrid() {
  # ROM system script is product SoT; user overlay only for tip-test.
  for c in \
    /system/bin/atlas-hybrid.sh \
    /system_ext/bin/atlas-hybrid.sh \
    /product/bin/atlas-hybrid.sh \
    "$ATLAS_USER_BIN/atlas-hybrid.sh" \
    "$HOME/bin/atlas-hybrid.sh" \
    /data/local/tmp/atlas-hybrid.sh
  do
    [ -f "$c" ] && [ -s "$c" ] && echo "$c" && return 0
  done
  return 1
}

find_real_su() {
  # Absolute KernelSU / Magisk only — never PATH su (agent client).
  # Product rootless Deb enter prefers /system/bin/atlas-enter (setuid ROM helper).
  for s in /system/bin/su /system/xbin/su /data/adb/ksu/bin/su; do
    [ -x "$s" ] && echo "$s" && return 0
  done
  return 1
}

find_atlas_enter() {
  # Tip first (PTY / home fixes) then product ROM binary — no Magisk.
  for s in \
    /data/local/tmp/atlas-enter \
    "${ATLAS_BIN:-}/atlas-enter" \
    "${HOME:-}/bin/atlas-enter" \
    /system/bin/atlas-enter \
    /system_ext/bin/atlas-enter \
    /product/bin/atlas-enter
  do
    case "$s" in ""|"/atlas-enter") continue ;; esac
    [ -x "$s" ] && echo "$s" && return 0
  done
  return 1
}

# Run interactive bash as admin (app UID). Never leave an interactive root shell.
exec_admin_bash() {
  export PATH="$ATLAS_USER_BIN:$HOME/bin:$HOME/.local/bin:$HOME/.cargo/bin:$HOME/.npm-global/bin:$HOME/.grok/bin:/system/bin:/system_ext/bin:/product/bin:/system/xbin:/vendor/bin"
  if [ -n "$BASH_BIN" ]; then
    export SHELL="$BASH_BIN"
  else
    export SHELL=/system/bin/sh
  fi
  export INPUTRC="$HOME/.inputrc"
  export BASH_ENV="$HOME/.bash_env"
  export ATLAS_AUTH_DIR="$HOME/auth"
  [ -n "$ATLAS_ASKPASS_BIN" ] && export SUDO_ASKPASS="$ATLAS_ASKPASS_BIN"
  export ATLAS_BIN ATLAS_HOME HOME ATLAS_SYSBIN ATLAS_USER_BIN
  export USER=admin LOGNAME=admin ATLAS_ROLE=admin
  stty sane 2>/dev/null || true
  stty erase '^?' 2>/dev/null || true
  cd "$HOME" 2>/dev/null || true
  if [ -n "$BASH_BIN" ]; then
    exec "$BASH_BIN" -il
  fi
  ATLAS_REPL=`find_tool atlas`
  if [ -n "$ATLAS_REPL" ]; then
    exec "$ATLAS_REPL" -i
  fi
  exec /system/bin/sh
}

# If somehow started as root, drop to admin before any interactive shell.
if [ "$(id -u)" = "0" ]; then
  if [ -n "$ADMIN_UID" ] && [ "$ADMIN_UID" != "0" ]; then
    RSU=`find_real_su`
    if [ -n "$RSU" ] && [ -n "$BASH_BIN" ]; then
      export ATLAS_DROP_UID="$ADMIN_UID"
      exec "$RSU" "$ADMIN_UID" -c "export HOME='$HOME' ATLAS_HOME='$HOME' ATLAS_BIN='$ATLAS_BIN' ATLAS_SYSBIN='$ATLAS_SYSBIN' ATLAS_USER_BIN='$ATLAS_USER_BIN' ATLAS_AUTH_DIR='$HOME/auth' SUDO_ASKPASS='${ATLAS_ASKPASS_BIN:-}' PATH='$ATLAS_USER_BIN:/system/bin:/system_ext/bin:/product/bin:/system/xbin' USER=admin LOGNAME=admin ATLAS_ROLE=admin; cd '$HOME' 2>/dev/null; exec '$BASH_BIN' -il"
    fi
  fi
  echo "atlas-net: FATAL refuse interactive root shell" >&2
  exit 77
fi

# Hybrid only when Deb/hybrid session is requested.
# NEVER silent-fallback to Android when Deb was asked — that left strip "Deb"
# while PTY was pure Android (blank grok, Android PATH). Fail loud instead.
HYB=`find_hybrid`
ENTER=`find_atlas_enter`
RSU=`find_real_su`
want_hybrid=0
case "${ATLAS_SESSION:-}" in
  hybrid|debian|linux) want_hybrid=1 ;;
  atlas|user|repl|admin) want_hybrid=0 ;;
  *) [ "${ATLAS_PRIV:-0}" = "1" ] && want_hybrid=1 ;;
esac

hybrid_ready() {
  # Status file from system ensure (merge is often 0700 — app cannot probe bash)
  if [ -f /data/local/tmp/atlas_hybrid.ready ]; then
    r=`cat /data/local/tmp/atlas_hybrid.ready 2>/dev/null | tr -d '\r\n'`
    [ "$r" = "1" ] && return 0
  fi
  if grep -q 'ready=1' /data/local/tmp/atlas_hybrid.status 2>/dev/null; then
    return 0
  fi
  if grep -q ' /data/local/atlas-hybrid/merge ' /proc/mounts 2>/dev/null; then
    [ -x /data/local/atlas-hybrid/merge/bin/bash ] \
      || [ -x /data/local/atlas-hybrid/merge/usr/bin/bash ] \
      || [ -x /data/local/atlas-hybrid/merge/bin/sh ] && return 0
  fi
  [ -f /data/local/atlas-hybrid/lower/etc/os-release ] && return 0
  [ -f /data/local/atlas-hybrid/lower/usr/bin/bash ] && return 0
  return 1
}

if [ "$want_hybrid" = "1" ]; then
  if [ -z "$HYB" ] && [ -z "$ENTER" ]; then
    echo "atlas-net: FATAL Deb requested but hybrid tools missing" >&2
    echo "hint: ROM must ship atlas-hybrid.sh + atlas-enter" >&2
    exit 78
  fi

  DROP_UID=`id -u`

  # Product path: setuid /system/bin/atlas-enter (no Magisk/KSU).
  if [ -n "$ENTER" ]; then
    if ! hybrid_ready; then
      # Ask init watch / leave marker; atlas-enter --ensure also tries as root
      touch /data/local/tmp/atlas-hybrid-ensure-request 2>/dev/null || true
    fi
    export HOME ATLAS_HOME ATLAS_BIN ATLAS_SYSBIN ATLAS_USER_BIN
    export TERM LANG COLORTERM
    export SSL_CERT_FILE="${SSL_CERT_FILE:-}" SSL_CERT_DIR="${SSL_CERT_DIR:-}"
    export ATLAS_DROP_UID="$DROP_UID"
    # Auth plane on LP when present (wipe-survive law)
    if [ -d /data/local/atlas-linux/var/lib/atlas-auth ]; then
      export ATLAS_AUTH_DIR=/data/local/atlas-linux/var/lib/atlas-auth
    elif [ -d /var/lib/atlas-auth ]; then
      export ATLAS_AUTH_DIR=/var/lib/atlas-auth
    else
      export ATLAS_AUTH_DIR="${ATLAS_AUTH_DIR:-$HOME/auth}"
    fi
    export SUDO_ASKPASS="${ATLAS_ASKPASS_BIN:-}"
    export USER=atlas LOGNAME=atlas ATLAS_ROLE=admin
    export ATLAS_SESSION=hybrid ATLAS_PRIV=1 ATLAS_PLANE=hybrid ATLAS_MODE=debian ATLAS_HYBRID=1
    # Prefer product linux home for Deb (not CE files)
    case "$HOME" in
      /data/data/*|/data/user/*)
        if [ -d /data/local/atlas-home/atlas ]; then
          HOME=/data/local/atlas-home/atlas
          ATLAS_HOME=$HOME
          export HOME ATLAS_HOME
        fi
        ;;
    esac
    # --no-ensure: enterd already has plane; avoid hang on ensure thrash
    exec "$ENTER" --uid "$DROP_UID" --home "$HOME" --no-ensure --
  fi

  # Lab fallback only: KernelSU / Magisk su (not product rootless claim)
  if [ -z "$RSU" ]; then
    echo "atlas-net: FATAL Deb requested but product atlas-enter missing and no su" >&2
    echo "hint: ROM must ship setuid /system/bin/atlas-enter for rootless Deb" >&2
    echo "hint: refusing fake Android shell under Deb label" >&2
    exit 79
  fi
  if ! hybrid_ready; then
    "$RSU" 0 /system/bin/sh "$HYB" ensure >/dev/null 2>&1 || true
  fi
  if ! hybrid_ready; then
    echo "atlas-net: FATAL Deb requested but hybrid rootfs not ready" >&2
    echo "hint: hybrid ensure/bootstrap (overlay down / empty lower)" >&2
    exit 80
  fi

  exec "$RSU" 0 sh -c "
    export HOME='$HOME' ATLAS_HOME='$ATLAS_HOME' ATLAS_BIN='$ATLAS_BIN'
    export ATLAS_SYSBIN='$ATLAS_SYSBIN' ATLAS_USER_BIN='$ATLAS_USER_BIN'
    export TERM='$TERM' LANG='$LANG' COLORTERM='$COLORTERM'
    export SSL_CERT_FILE='${SSL_CERT_FILE:-}' SSL_CERT_DIR='${SSL_CERT_DIR:-}'
    export ATLAS_DROP_UID='$DROP_UID'
    export ATLAS_AUTH_DIR='$HOME/auth'
    export SUDO_ASKPASS='${ATLAS_ASKPASS_BIN:-}'
    export PATH='$ATLAS_USER_BIN:/usr/local/bin:/usr/bin:/bin:/system/bin:/system_ext/bin:/product/bin:/system/xbin:/vendor/bin'
    export USER=admin LOGNAME=admin ATLAS_ROLE=admin
    export ATLAS_SESSION=hybrid ATLAS_PRIV=1 ATLAS_PLANE=hybrid ATLAS_MODE=debian ATLAS_HYBRID=1
    HYB='$HYB'
    export ATLAS_INTERNAL_SU=1
    if grep -q ' /data/local/atlas-hybrid/merge ' /proc/mounts 2>/dev/null \
      && [ -x /data/local/atlas-hybrid/merge/bin/bash -o -x /data/local/atlas-hybrid/merge/usr/bin/bash -o -x /data/local/atlas-hybrid/merge/bin/sh ]; then
      :
    else
      if ! /system/bin/sh \"\$HYB\" ensure; then
        echo \"atlas-net: FATAL hybrid ensure failed — refusing Android fallback\" >&2
        exit 80
      fi
    fi
    unset ATLAS_INTERNAL_SU
    if [ -x /system/bin/atlas-enter ]; then
      exec /system/bin/atlas-enter --uid '$DROP_UID' --home '$HOME' --no-ensure --
    fi
    exec /system/bin/sh \"\$HYB\" enter
  "
fi

# Default: Android admin shell (app UID) — only when And/session=atlas.
exec_admin_bash
