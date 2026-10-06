#!/system/bin/sh
trap '' HUP
# Hide IME toggle → the nav-bar overlay.
# Controls writes titan2_tweaks/ime_switcher_hidden (and, on newer builds,
# settings global titan2_hide_ime_bar). Default is hide.
# Does not remove keyboards. A trimmed enabled_input_methods list is restored
# from the legacy switcher backup while the bar stays hidden.
VER=1.0-hide-ime
export PATH=/system/bin:/system/xbin:/vendor/bin:$PATH
ST=/data/local/tmp
APP=/data/user/0/com.titanus2.controls
PREF=$APP/shared_prefs/titan2_tweaks.xml
WANT=$ST/titan2_ime_bar.want
LOCK=$ST/titan2_ime_bar.lock.d
STATIC=com.titanus2.overlay.imenavbar
FAB='com.android.shell:TitanNoImeNavBar'

log() {
  echo "ime-bar $VER $*" >>"$ST/titan2-ime-bar.log" 2>/dev/null || true
}

boot_id() {
  _b=`cat /proc/sys/kernel/random/boot_id 2>/dev/null | tr -d '\r\n '`
  [ -n "$_b" ] || _b=noboot
  echo "$_b"
}

pref_value() {
  [ -f "$PREF" ] || return 1
  _line=`grep -m1 'name="ime_switcher_hidden"' "$PREF" 2>/dev/null` || return 1
  case "$_line" in
    *value=\"true\"*) echo 1; return 0 ;;
    *value=\"false\"*) echo 0; return 0 ;;
  esac
  return 1
}

# 1 = hide the bar. The switch the user sees wins. Global is only the
# mirror when that switch has never been written. Absent means hide.
want_hide() {
  _p=`pref_value` || _p=
  case "$_p" in
    0) echo 0; return ;;
    1) echo 1; return ;;
  esac
  _g=`settings get global titan2_hide_ime_bar 2>/dev/null | tr -d '\r'`
  case "$_g" in
    0|false|off) echo 0; return ;;
    1|true|on) echo 1; return ;;
  esac
  echo 1
}

lookup_hidden() {
  _v=`cmd overlay lookup android android:bool/config_imeDrawsImeNavBar 2>/dev/null | tr -d '\r\n '`
  case "$_v" in false|0) return 0 ;; esac
  return 1
}

set_overlays() {
  if [ "$1" = 1 ]; then
    cmd overlay enable --user 0 "$STATIC" >/dev/null 2>&1 || true
    cmd overlay enable --user 0 "$FAB" >/dev/null 2>&1 || true
  else
    cmd overlay disable --user 0 "$STATIC" >/dev/null 2>&1 || true
    cmd overlay disable --user 0 "$FAB" >/dev/null 2>&1 || true
  fi
}

restore_trimmed_imes() {
  [ -f "$PREF" ] || return 0
  _bak=`sed -n 's/.*name="enabled_imes_backup">\([^<]*\)<.*/\1/p' "$PREF" 2>/dev/null | head -1`
  [ -n "$_bak" ] || return 0
  _cur=`settings get secure enabled_input_methods 2>/dev/null | tr -d '\r'`
  [ -n "$_cur" ] || return 0
  [ "$_cur" = "$_bak" ] && return 0
  # Only undo the old switcher's subtype trim. A different keyboard stays.
  case "$_bak" in
    "$_cur"*) ;;
    *) return 0 ;;
  esac
  settings put secure enabled_input_methods "$_bak" >/dev/null 2>&1 || true
  log "restored keyboards"
}

restart_ime() {
  _id=`settings get secure default_input_method 2>/dev/null | tr -d '\r'`
  _pkg=${_id%%/*}
  case "$_pkg" in ''|null|*[!A-Za-z0-9._]*) return 0 ;; esac
  am force-stop "$_pkg" >/dev/null 2>&1 || true
  log "restarted $_pkg"
}

own_pref() {
  _uid=`stat -c %u "$APP" 2>/dev/null` || return 0
  _gid=`stat -c %g "$APP" 2>/dev/null` || _gid=$_uid
  chown "$_uid:$_gid" "$PREF" 2>/dev/null || true
  chmod 660 "$PREF" 2>/dev/null || true
  restorecon "$PREF" 2>/dev/null || true
}

# So the existing Tweaks switch shows On, matching the hidden bar.
seed_pref() {
  if [ -f "$PREF" ]; then
    pref_value >/dev/null 2>&1 && return 0
    _tmp=$PREF.imebar
    sed 's#</map>#    <boolean name="ime_switcher_hidden" value="true" />\
</map>#' "$PREF" >"$_tmp" 2>/dev/null || return 0
    grep -q 'ime_switcher_hidden' "$_tmp" 2>/dev/null || { rm -f "$_tmp"; return 0; }
    cat "$_tmp" >"$PREF" 2>/dev/null || { rm -f "$_tmp"; return 0; }
    rm -f "$_tmp"
    log "seeded switch on"
    return 0
  fi
  [ -d "$APP" ] || return 0
  mkdir -p "$APP/shared_prefs" 2>/dev/null || return 0
  cat >"$PREF" <<'EOF'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="ime_switcher_hidden" value="true" />
</map>
EOF
  own_pref
  log "created switch on"
}

apply() {
  _w=`want_hide`
  _b=`boot_id`
  _prev=`cat "$WANT" 2>/dev/null | tr -d '\r\n'`
  [ "$_prev" = "$_b $_w" ] && return 0
  if [ "$_w" = 1 ]; then
    lookup_hidden && _now=1 || _now=0
  else
    lookup_hidden && _now=0 || _now=1
  fi
  if [ "$_now" != "$_w" ]; then
    set_overlays "$_w"
    if [ "$_w" = 1 ]; then
      lookup_hidden && _ok=1 || _ok=0
    else
      lookup_hidden && _ok=0 || _ok=1
    fi
    if [ "$_ok" != 1 ]; then
      log "overlay unchanged hide=$_w"
      return 0
    fi
    if [ "$_w" = 1 ]; then
      restore_trimmed_imes
    fi
    restart_ime
    log "applied hide=$_w"
  else
    log "already hide=$_w"
  fi
  printf '%s %s\n' "$_b" "$_w" >"$WANT" 2>/dev/null || true
  chmod 666 "$WANT" 2>/dev/null || true
}

# Rewrite only the switch bit. Other tweak keys stay.
set_hide_pref() {
  case "$1" in
    0|false|off) _val=false ;;
    1|true|on) _val=true ;;
    *) return 2 ;;
  esac
  if [ -f "$PREF" ] && pref_value >/dev/null 2>&1; then
    _tmp=$PREF.imebar
    if [ "$_val" = true ]; then
      sed 's/name="ime_switcher_hidden" value="false"/name="ime_switcher_hidden" value="true"/' "$PREF" >"$_tmp" 2>/dev/null || return 1
    else
      sed 's/name="ime_switcher_hidden" value="true"/name="ime_switcher_hidden" value="false"/' "$PREF" >"$_tmp" 2>/dev/null || return 1
    fi
    grep -q "name=\"ime_switcher_hidden\" value=\"$_val\"" "$_tmp" 2>/dev/null || { rm -f "$_tmp"; return 1; }
    cat "$_tmp" >"$PREF" 2>/dev/null || { rm -f "$_tmp"; return 1; }
    rm -f "$_tmp"
  elif [ -f "$PREF" ]; then
    _tmp=$PREF.imebar
    sed "s#</map>#    <boolean name=\"ime_switcher_hidden\" value=\"$_val\" />\\
</map>#" "$PREF" >"$_tmp" 2>/dev/null || return 1
    grep -q 'ime_switcher_hidden' "$_tmp" 2>/dev/null || { rm -f "$_tmp"; return 1; }
    cat "$_tmp" >"$PREF" 2>/dev/null || { rm -f "$_tmp"; return 1; }
    rm -f "$_tmp"
  else
    [ -d "$APP" ] || return 1
    mkdir -p "$APP/shared_prefs" 2>/dev/null || return 1
    cat >"$PREF" <<EOF
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="ime_switcher_hidden" value="$_val" />
</map>
EOF
    own_pref
  fi
  rm -f "$WANT" 2>/dev/null || true
  log "switch $_val"
}

run_loop() {
  if ! mkdir "$LOCK" 2>/dev/null; then
    _op=`cat "$ST/titan2_ime_bar.pid" 2>/dev/null | tr -d '\r\n '`
    case "$_op" in
      ''|*[!0-9]*) rm -rf "$LOCK" 2>/dev/null || true ;;
      *)
        if kill -0 "$_op" 2>/dev/null; then
          exit 0
        fi
        rm -rf "$LOCK" 2>/dev/null || true
        ;;
    esac
    mkdir "$LOCK" 2>/dev/null || exit 0
  fi
  echo $$ >"$ST/titan2_ime_bar.pid" 2>/dev/null || true
  chmod 666 "$ST/titan2_ime_bar.pid" 2>/dev/null || true
  trap '' HUP
  trap 'rmdir "$LOCK" 2>/dev/null || rm -rf "$LOCK" 2>/dev/null || true' EXIT INT TERM
  seed_pref
  apply
  while true; do
    apply
    sleep 2
  done
}

cmd=${1:-run}
case "$cmd" in
  apply) apply ;;
  seed) seed_pref; apply ;;
  set-hide) set_hide_pref "$2" || exit 1; apply ;;
  run) run_loop ;;
  version|-v|--version) echo "$VER" ;;
  *)
    echo "usage: titan2-ime-bar.sh apply|seed|set-hide 0|1|run|version" >&2
    exit 2
    ;;
esac
