#!/bin/sh
# Plant BlackCube for the Atlas Grok user.
# Home (~/.grok, ~/.nanobot) is wiped with userdata. The bridge, the peer
# token, and this script live on the Debian LP and are copied back.
# The shell tool is not filtered. The peer token stays out of config.toml.
#
#   ATLAS_LINUX_ROOT       debian root (seed dir or /data/local/atlas-linux)
#   ATLAS_BLACKCUBE_SEED=1 cook: write the image only, do not start a daemon
#   ATLAS_BLACKCUBE_TOKEN  file to install (never printed)
#   ATLAS_ATLAS_UID        numeric uid of the atlas user
set -u

PEER_URL="${NANOBOT_PEER_URL:-http://192.168.8.100:18787}"
HERE=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
SRC_MCP=""
for _c in \
  "$HERE/atlas-blackcube-mcp" \
  /usr/local/libexec/atlas-blackcube-mcp \
  /data/local/atlas-linux/usr/local/libexec/atlas-blackcube-mcp \
  /home/atlas/.local/libexec/atlas-blackcube-mcp \
  /data/local/atlas-home/atlas/.local/libexec/atlas-blackcube-mcp
do
  if [ -f "$_c" ]; then
    SRC_MCP=$_c
    break
  fi
done

on_phone() {
  [ -n "${ATLAS_LINUX_ROOT:-}" ] && return 0
  [ -d /data/local/atlas-linux/usr ] && return 0
  [ -f /etc/debian_version ] && return 0
  return 1
}

if ! on_phone; then
  exit 0
fi

ROOT="${ATLAS_LINUX_ROOT:-}"
if [ -z "$ROOT" ] && [ -d /data/local/atlas-linux/usr ]; then
  ROOT=/data/local/atlas-linux
fi
# Inside the debian chroot the LP is /.
if [ -z "$ROOT" ] && [ -f /etc/debian_version ]; then
  ROOT=
fi

SEED="${ATLAS_BLACKCUBE_SEED:-0}"

lock() {
  if [ "$SEED" = "1" ]; then
    return 0
  fi
  command -v flock >/dev/null 2>&1 || return 0
  exec 9>/tmp/atlas-blackcube-install.lock
  flock -n 9 || exit 0
}
lock

uid_of_atlas() {
  if [ -n "${ATLAS_ATLAS_UID:-}" ]; then
    printf '%s\n' "$ATLAS_ATLAS_UID"
    return 0
  fi
  _u=$(stat -c %u /data/data/com.titanus2.atlas 2>/dev/null || true)
  case "$_u" in
    ''|0) ;;
    *) printf '%s\n' "$_u"; return 0 ;;
  esac
  _pw=${ROOT}/etc/passwd
  [ -f "$_pw" ] || _pw=/etc/passwd
  _u=$(awk -F: '$1=="atlas"{print $3; exit}' "$_pw" 2>/dev/null || true)
  case "$_u" in
    '') return 1 ;;
    *) printf '%s\n' "$_u" ;;
  esac
}

copy_exec() {
  _src=$1
  _dest=$2
  [ -n "$_src" ] && [ -f "$_src" ] || return 1
  mkdir -p "$(dirname "$_dest")" 2>/dev/null || return 1
  cp -f "$_src" "$_dest" 2>/dev/null || return 1
  chmod 755 "$_dest" 2>/dev/null || true
}

# Script + installer onto the LP when that tree is writable.
if [ -n "$ROOT" ]; then
  copy_exec "$SRC_MCP" "$ROOT/usr/local/libexec/atlas-blackcube-mcp" || true
  copy_exec "$0" "$ROOT/usr/local/libexec/atlas-blackcube-install.sh" || true
fi
if [ -z "$ROOT" ]; then
  copy_exec "$SRC_MCP" /usr/local/libexec/atlas-blackcube-mcp || true
  copy_exec "$0" /usr/local/libexec/atlas-blackcube-install.sh || true
fi

MCP_BIN=""
for _c in \
  ${ROOT:+$ROOT/usr/local/libexec/atlas-blackcube-mcp} \
  /usr/local/libexec/atlas-blackcube-mcp \
  "$SRC_MCP"
do
  [ -n "$_c" ] && [ -f "$_c" ] || continue
  MCP_BIN=$_c
  break
done

token_src() {
  if [ -n "${ATLAS_BLACKCUBE_TOKEN:-}" ] && [ -s "${ATLAS_BLACKCUBE_TOKEN}" ]; then
    printf '%s\n' "$ATLAS_BLACKCUBE_TOKEN"
    return 0
  fi
  # Cook must not pick up a token from the host filesystem.
  if [ "$SEED" = "1" ]; then
    return 1
  fi
  for _f in \
    ${ROOT:+$ROOT/usr/local/share/atlas/blackcube.token} \
    /usr/local/share/atlas/blackcube.token \
    /data/local/atlas-linux/usr/local/share/atlas/blackcube.token \
    /home/atlas/.nanobot/peer_token \
    /data/local/atlas-home/atlas/.nanobot/peer_token
  do
    [ -n "$_f" ] && [ -s "$_f" ] || continue
    printf '%s\n' "$_f"
    return 0
  done
  return 1
}

own_secret() {
  _f=$1
  chmod 600 "$_f" 2>/dev/null || true
  [ "$(id -u 2>/dev/null || echo 1)" = "0" ] || return 0
  _uid=$(uid_of_atlas 2>/dev/null || true)
  [ -n "$_uid" ] || return 0
  chown "$_uid:$_uid" "$_f" 2>/dev/null || true
}

TOK=$(token_src || true)
LP_TOK=""
if [ -n "$ROOT" ]; then
  LP_TOK=$ROOT/usr/local/share/atlas/blackcube.token
elif [ -f /etc/debian_version ]; then
  LP_TOK=/usr/local/share/atlas/blackcube.token
fi
if [ -n "$TOK" ] && [ -n "$LP_TOK" ] && [ "$TOK" != "$LP_TOK" ]; then
  if mkdir -p "$(dirname "$LP_TOK")" 2>/dev/null; then
    cp -f "$TOK" "$LP_TOK" 2>/dev/null && own_secret "$LP_TOK" || true
  fi
fi
if [ -n "$LP_TOK" ] && [ -s "$LP_TOK" ]; then
  own_secret "$LP_TOK" || true
  TOK=$LP_TOK
fi

# Homes to repair. Never $HOME of whoever ran the cook.
homes=""
add_home() {
  _h=$1
  [ -n "$_h" ] || return 0
  if [ "$SEED" = "1" ]; then
    return 0
  fi
  case " $_h " in
    *".."*) return 0 ;;
  esac
  if [ ! -d "$_h" ]; then
    mkdir -p "$_h" 2>/dev/null || return 0
  fi
  [ -d "$_h" ] || return 0
  case " $homes " in
    *" $_h "*) return 0 ;;
  esac
  homes="$homes $_h"
}
if [ -n "${ATLAS_BLACKCUBE_HOMES:-}" ]; then
  for _h in $ATLAS_BLACKCUBE_HOMES; do
    add_home "$_h"
  done
else
  add_home "${ATLAS_LINUX_HOME:-}"
  add_home /home/atlas
  add_home /data/local/atlas-home/atlas
fi

merge_cfg() {
  _cfg=$1
  _dir=$(dirname "$_cfg")
  mkdir -p "$_dir" 2>/dev/null || return 0
  _tmp=$_dir/.config.toml.blackcube.$$
  _block=$_dir/.blackcube.block.$$
  cat >"$_block" << EOF
# atlas-blackcube-begin
[mcp_servers.blackcube_nanobot_http]
url = "http://127.0.0.1:18790/mcp"
enabled = true
startup_timeout_sec = 15
tool_timeout_sec = 180
# atlas-blackcube-end
EOF
  if [ -f "$_cfg" ]; then
    awk '
      /^# atlas-blackcube-begin$/ {s=1; next}
      /^# atlas-blackcube-end$/ {s=0; next}
      /^\[mcp_servers\.blackcube_nanobot_http/ {s=1; next}
      s==1 && /^\[/ {s=0}
      s!=1 {print}
    ' "$_cfg" >"$_tmp" || { rm -f "$_tmp" "$_block"; return 0; }
  else
    : >"$_tmp"
  fi
  printf '\n' >>"$_tmp"
  cat "$_block" >>"$_tmp"
  rm -f "$_block"
  if ! grep -q 'MCPTool(blackcube_nanobot_http__\*)' "$_tmp"; then
    if grep -q '^\[permission\]' "$_tmp"; then
      awk '
        BEGIN {done=0; inperm=0}
        /^\[permission\][[:space:]]*$/ {inperm=1; print; next}
        /^\[/ {inperm=0}
        inperm && done==0 && $0 ~ /^[[:space:]]*allow[[:space:]]*=[[:space:]]*\[/ {
          sub(/\[/, "[ \"MCPTool(blackcube_nanobot_http__*)\", \"mcp__blackcube_nanobot_http__*\",")
          done=1
          print
          next
        }
        {print}
      ' "$_tmp" >"$_tmp.2" || { rm -f "$_tmp" "$_tmp.2"; return 0; }
      if ! grep -q 'MCPTool(blackcube_nanobot_http__\*)' "$_tmp.2"; then
        awk '
          /^\[permission\][[:space:]]*$/ {
            print
            print "allow = [\"MCPTool(blackcube_nanobot_http__*)\", \"mcp__blackcube_nanobot_http__*\"]"
            next
          }
          {print}
        ' "$_tmp.2" >"$_tmp.3" && mv "$_tmp.3" "$_tmp"
        rm -f "$_tmp.2"
      else
        mv "$_tmp.2" "$_tmp"
      fi
    else
      printf '\n[permission]\nallow = ["MCPTool(blackcube_nanobot_http__*)", "mcp__blackcube_nanobot_http__*"]\n' >>"$_tmp"
    fi
  fi
  if grep -q 'disabled_mcp_servers' "$_tmp"; then
    awk '
      /disabled_mcp_servers/ && /blackcube_nanobot_http/ {
        line=$0
        gsub(/"blackcube_nanobot_http"[[:space:]]*,[[:space:]]*/, "", line)
        gsub(/,[[:space:]]*"blackcube_nanobot_http"/, "", line)
        gsub(/"blackcube_nanobot_http"/, "", line)
        gsub(/,[[:space:]]*\]/, "]", line)
        print line
        next
      }
      {print}
    ' "$_tmp" >"$_tmp.d" && mv "$_tmp.d" "$_tmp"
  fi
  mv "$_tmp" "$_cfg" || { rm -f "$_tmp"; return 0; }
  chmod 644 "$_cfg" 2>/dev/null || true
  if [ "$(id -u 2>/dev/null || echo 1)" = "0" ]; then
    _uid=$(uid_of_atlas 2>/dev/null || true)
    if [ -n "$_uid" ]; then
      chown "$_uid:$_uid" "$_cfg" 2>/dev/null || true
      chown "$_uid:$_uid" "$_dir" 2>/dev/null || true
    fi
  fi
}

port_up() {
  [ -r /proc/net/tcp ] || return 1
  awk 'BEGIN{f=1} tolower($2)=="0100007f:4966" && $4=="0A" {f=0} END{exit f}' /proc/net/tcp
}

cfg_ready() {
  _cfg=$1
  [ -f "$_cfg" ] || return 1
  grep -q '# atlas-blackcube-begin' "$_cfg" || return 1
  grep -q 'url = "http://127.0.0.1:18790/mcp"' "$_cfg" || return 1
  grep -q 'enabled = true' "$_cfg" || return 1
  grep -q 'MCPTool(blackcube_nanobot_http__\*)' "$_cfg" || return 1
}

if [ "$SEED" != "1" ] && [ -n "$TOK" ] && [ -s "$TOK" ] && port_up; then
  _ready=1
  for _h in $homes; do
    cfg_ready "$_h/.grok/config.toml" || _ready=0
    [ -s "$_h/.nanobot/peer_token" ] || _ready=0
  done
  if [ "$_ready" = "1" ] && [ -n "$homes" ]; then
    skip_merge=1
  fi
fi
skip_merge="${skip_merge:-0}"

if [ "$skip_merge" != "1" ]; then
for _h in $homes; do
  if [ -n "$TOK" ] && [ -s "$TOK" ]; then
    mkdir -p "$_h/.nanobot" 2>/dev/null || true
    _ht=$_h/.nanobot/peer_token
    if [ "$TOK" != "$_ht" ]; then
      cp -f "$TOK" "$_ht" 2>/dev/null && own_secret "$_ht" || true
    else
      own_secret "$_ht" || true
    fi
    if [ "$(id -u 2>/dev/null || echo 1)" = "0" ]; then
      _uid=$(uid_of_atlas 2>/dev/null || true)
      [ -n "$_uid" ] && chown -R "$_uid:$_uid" "$_h/.nanobot" 2>/dev/null || true
    fi
  fi
  merge_cfg "$_h/.grok/config.toml"
  if [ "$(id -u 2>/dev/null || echo 1)" = "0" ]; then
    _uid=$(uid_of_atlas 2>/dev/null || true)
    [ -n "$_uid" ] && chown -R "$_uid:$_uid" "$_h/.grok" 2>/dev/null || true
  fi
done
fi

# Login and interactive bash both reseed after a wipe.
prof_dir=${ROOT}/etc/profile.d
[ -n "$ROOT" ] || prof_dir=/etc/profile.d
if mkdir -p "$prof_dir" 2>/dev/null; then
  cat >"$prof_dir/atlas-blackcube.sh" << 'EOF'
# BlackCube for Atlas Grok. This file lives on the Debian LP.
if [ -x /usr/local/libexec/atlas-blackcube-install.sh ]; then
  /usr/local/libexec/atlas-blackcube-install.sh >/dev/null 2>&1 || true
fi
EOF
  chmod 644 "$prof_dir/atlas-blackcube.sh" 2>/dev/null || true
fi
bashrc=${ROOT}/etc/bash.bashrc
[ -n "$ROOT" ] || bashrc=/etc/bash.bashrc
if [ -f "$bashrc" ] && ! grep -q atlas-blackcube "$bashrc" 2>/dev/null; then
  printf '\n# atlas-blackcube\n[ -r /etc/profile.d/atlas-blackcube.sh ] && . /etc/profile.d/atlas-blackcube.sh\n' >>"$bashrc" 2>/dev/null || true
fi

# Root on the phone: rerun after hybrid boot. Userdata wipe removes this.
if [ "$SEED" != "1" ] && [ "$(id -u 2>/dev/null || echo 1)" = "0" ] && [ -d /data/adb/service.d ]; then
  cat >/data/adb/service.d/atlas-blackcube.sh << 'EOF'
#!/system/bin/sh
# Reinstall BlackCube MCP after hybrid mount. Gone on userdata wipe;
# /etc/profile.d/atlas-blackcube.sh on the LP covers the next boot.
sleep 15
sh /data/local/atlas-linux/usr/local/libexec/atlas-blackcube-install.sh
sleep 40
sh /data/local/atlas-linux/usr/local/libexec/atlas-blackcube-install.sh
EOF
  chmod 755 /data/adb/service.d/atlas-blackcube.sh 2>/dev/null || true
fi

start_daemon() {
  [ "$SEED" = "1" ] && return 0
  port_up && return 0
  _py=""
  for _p in /usr/bin/python3 /usr/local/bin/python3; do
    [ -x "$_p" ] && _py=$_p && break
  done
  [ -n "$_py" ] && [ -n "$MCP_BIN" ] && [ -f "$MCP_BIN" ] || return 0
  _log=/tmp/atlas-blackcube-mcp.log
  if command -v setsid >/dev/null 2>&1; then
    setsid "$_py" "$MCP_BIN" --serve >>"$_log" 2>&1 </dev/null &
  else
    "$_py" "$MCP_BIN" --serve >>"$_log" 2>&1 </dev/null &
  fi
}

if [ -f /etc/debian_version ] && [ -z "${ATLAS_LINUX_ROOT:-}" ]; then
  start_daemon
elif [ "$SEED" != "1" ] && [ "$(id -u 2>/dev/null || echo 1)" = "0" ] \
  && [ -x /data/local/atlas-linux/usr/bin/python3 ] \
  && [ -f /data/local/atlas-linux/usr/local/libexec/atlas-blackcube-mcp ]; then
  if ! port_up; then
    _uid=$(uid_of_atlas 2>/dev/null || true)
    if [ -n "$_uid" ] && [ -x /data/local/atlas-linux/usr/bin/setpriv ]; then
      chroot /data/local/atlas-linux /usr/bin/setsid \
        /usr/bin/setpriv --reuid="$_uid" --regid="$_uid" --clear-groups \
        /usr/bin/python3 /usr/local/libexec/atlas-blackcube-mcp --serve \
        >>/data/local/atlas-linux/tmp/atlas-blackcube-mcp.log 2>&1 </dev/null &
    fi
  fi
fi

exit 0
