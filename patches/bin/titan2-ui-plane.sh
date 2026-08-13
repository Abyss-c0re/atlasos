#!/system/bin/sh
# titan2-ui-plane — OPTIMIZE Phase 3 peel: Cube dual-plane tablet vs phone Launcher
# SoT: docs/project/OPTIMIZE_SOURCE_PRODUCT.md · PRODUCT_UX
# Invoked by pad-agent:
#   apply   — apply_ui_plane (wm density/size; time-bound)
#   version
export PATH=/system/bin:/system/xbin:/vendor/bin:$PATH
T2=/data/misc/titan2
ST=/data/local/tmp
UI_VER=2.186-ui-plane-peel
CUBE_TABLET_DENS=300
CUBE_PHONE_DENS=360
LAST_FILE=$ST/titan2_ui_plane_status

log() {
  mkdir -p "$ST" 2>/dev/null || true
  { echo "ui-plane: $*" >>"$ST/titan2_pad_agent.log"; } 2>/dev/null || true
}

_read_line_file() {
  f="$1"
  [ -f "$f" ] || { echo ""; return 1; }
  v=""
  IFS= read -r v < "$f" || true
  case "$v" in *$'\r') v=${v%$'\r'} ;; esac
  echo "$v"
  return 0
}

read_first() {
  _n=$1
  best_mt=-1
  best_v=""
  found=0
  for f in "$T2/$_n" "$ST/$_n"; do
    [ -f "$f" ] || continue
    v=`_read_line_file "$f"`
    v=`echo "$v" | tr -d '\r\n \t'`
    case "$v" in ''|null|NULL|-|clear|CLEAR) continue ;; esac
    mt=`stat -c %Y "$f" 2>/dev/null` || mt=0
    case "$mt" in ''|*[!0-9]*) mt=0;; esac
    if [ "$found" = "0" ] || [ "$mt" -ge "$best_mt" ] 2>/dev/null; then
      best_mt=$mt
      best_v=$v
      found=1
    fi
  done
  [ "$found" = "1" ] && echo "$best_v" || echo ""
}

_ui_wm() {
  if command -v timeout >/dev/null 2>&1; then
    timeout 2 "$@" >/dev/null 2>&1 || true
  else
    "$@" >/dev/null 2>&1 || true
  fi
}

apply_ui_plane() {
  want=`read_first titan2_ui_plane`
  case "$want" in
    phone_launcher|phone|launcher) want=phone_launcher ;;
    *) want=tablet ;;
  esac
  last=`_read_line_file "$LAST_FILE" 2>/dev/null | tr -d '\r\n '`
  [ "$want" = "$last" ] && return 0
  if [ "$want" = "phone_launcher" ]; then
    dens=$CUBE_PHONE_DENS
    px=$((580 * dens / 160))
    px=$(( (px / 8) * 8 ))
    [ "$px" -ge 720 ] || px=720
    [ "$px" -le 1440 ] || px=1440
    _ui_wm wm density "$dens"
    _ui_wm cmd window density "$dens"
    _ui_wm wm size "${px}x${px}"
    _ui_wm cmd window size "${px}x${px}"
    _ui_wm am force-stop com.android.launcher3
    log "ui_plane=phone_launcher dens=$dens size=${px}x${px}"
  else
    dens=$CUBE_TABLET_DENS
    _ui_wm wm density "$dens"
    _ui_wm cmd window density "$dens"
    _ui_wm wm size reset
    _ui_wm cmd window size reset
    log "ui_plane=tablet dens=$dens size=physical"
  fi
  echo "$want" > "$LAST_FILE" 2>/dev/null
  chmod 666 "$LAST_FILE" 2>/dev/null || true
  return 0
}

cmd=${1:-apply}
case "$cmd" in
  apply|run|"") apply_ui_plane ;;
  version|-v|--version) echo "$UI_VER" ;;
  *)
    echo "usage: titan2-ui-plane.sh apply|version" >&2
    exit 2
    ;;
esac
