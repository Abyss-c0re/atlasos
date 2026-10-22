#!/system/bin/sh
# ATLAS_OVERLAY_STAMP=3
trap '' HUP
# Hide IME toggle → the nav-bar overlay.
# Controls writes titan2_tweaks/ime_switcher_hidden (and, on newer builds,
# settings global titan2_hide_ime_bar). Default is hide.
# Does not remove keyboards. A trimmed enabled_input_methods list is restored
# from the legacy switcher backup while the bar stays hidden.
#
# config_imeDrawsImeNavBar true makes InputMethodService draw the buttons.
# Turning that bool off leaves the 88px taskbar inset (taskbar_frame_height
# 56dp at 252dpi) and an empty strip. The product package
# com.titanus2.overlay.imenavbar sets the draw bool false, hides the nav bar
# while the keyboard is up, and sets taskbar_frame_height to 0dp.
# A live image without that package gets the bools from fabricated overlays.
# The 0dp inset is a dimension; cmd overlay fabricate cannot write one, so
# TitanImeNavInset.jar commits it. Shell fabricate stays the bool path.
# com.android.launcher3 caches the height until its process starts again.
VER=1.2-no-empty
export PATH=/system/bin:/system/xbin:/vendor/bin:$PATH
ST=/data/local/tmp
APP=/data/user/0/com.titanus2.controls
PREF=$APP/shared_prefs/titan2_tweaks.xml
WANT=$ST/titan2_ime_bar.want
LOCK=$ST/titan2_ime_bar.lock.d
STATIC=com.titanus2.overlay.imenavbar
FAB_NAME=TitanNoImeNavBar
FAB="com.android.shell:$FAB_NAME"
FAB_NAV_NAME=TitanHideNavForIme
FAB_NAV="com.android.shell:$FAB_NAV_NAME"
FAB_INSET_NAME=TitanImeNavInset
FAB_INSET="com.android.shell:$FAB_INSET_NAME"

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

# 1 when the keyboard-up nav policy is the hide value.
lookup_nav_hidden() {
  _v=`cmd overlay lookup android android:bool/config_hideNavBarForKeyboard 2>/dev/null | tr -d '\r\n '`
  case "$_v" in true|1) return 0 ;; esac
  return 1
}

# 1 when the taskbar inset dimen is zero. "56.0dip" is the empty strip.
lookup_inset_gone() {
  _v=`cmd overlay lookup android android:dimen/taskbar_frame_height 2>/dev/null | tr -d '\r\n '`
  _n=`printf '%s' "$_v" | sed 's/[^0-9.]//g'`
  case "$_n" in
    ''|*[1-9]*) return 1 ;;
  esac
  return 0
}

enable_id() {
  cmd overlay enable --user 0 "$1" >/dev/null 2>&1 \
    || cmd overlay enable --user current "$1" >/dev/null 2>&1 || true
}

disable_id() {
  cmd overlay disable --user 0 "$1" >/dev/null 2>&1 \
    || cmd overlay disable --user current "$1" >/dev/null 2>&1 || true
}

# 0x12 = TYPE_INT_BOOLEAN. false=0x0 (hide the bar), true=0xffffffff (draw it).
fab_draw() {
  if [ "$1" = 1 ]; then
    _enc=0xffffffff
  else
    _enc=0x0
  fi
  cmd overlay fabricate --target android --name "$FAB_NAME" \
    android:bool/config_imeDrawsImeNavBar 0x12 "$_enc" >/dev/null 2>&1 || true
  enable_id "$FAB"
}

# 1 = do not force the nav strip on while the keyboard is up.
fab_nav() {
  if [ "$1" = 1 ]; then
    _enc=0xffffffff
  else
    _enc=0x0
  fi
  cmd overlay fabricate --target android --name "$FAB_NAV_NAME" \
    android:bool/config_hideNavBarForKeyboard 0x12 "$_enc" >/dev/null 2>&1 || true
  enable_id "$FAB_NAV"
}

inset_jar() {
  for _d in /data/local/tmp/titan2-ime-nav-inset.jar /data/adb/titan2/titan2-ime-nav-inset.jar; do
    if [ -f "$_d" ]; then
      printf '%s\n' "$_d"
      return 0
    fi
  done
  return 1
}

# Enable the 0dp inset. Create it once per boot when the jar is present.
ensure_inset() {
  enable_id "$FAB_INSET"
  lookup_inset_gone && return 0
  _jar=`inset_jar` || _jar=
  [ -n "$_jar" ] || return 1
  _b=`boot_id`
  _tried=`cat "$ST/titan2_ime_bar.inset_try" 2>/dev/null | tr -d '\r\n'`
  if [ "$_tried" = "$_b" ]; then
    return 1
  fi
  printf '%s\n' "$_b" >"$ST/titan2_ime_bar.inset_try" 2>/dev/null || true
  CLASSPATH="$_jar" app_process /system/bin TitanImeNavInset \
    "$FAB_INSET_NAME" android:dimen/taskbar_frame_height 0 1 \
    >>"$ST/titan2-ime-bar.log" 2>&1 || true
  enable_id "$FAB_INSET"
  lookup_inset_gone
}

# The running taskbar keeps the old pixel height until launcher3 starts again.
reload_taskbar() {
  [ "$1" = "$2" ] && return 0
  am force-stop com.android.launcher3 >/dev/null 2>&1 || true
  log "taskbar reload inset=$2"
}

set_overlays() {
  if [ "$1" = 1 ]; then
    enable_id "$STATIC"
    lookup_hidden || fab_draw 0
    lookup_nav_hidden || fab_nav 1
    lookup_inset_gone || ensure_inset || true
  else
    disable_id "$STATIC"
    disable_id "$FAB"
    disable_id "$FAB_NAV"
    disable_id "$FAB_INSET"
    # A static product overlay can stay forced off. Fabricate the show values.
    lookup_hidden && fab_draw 1
    lookup_nav_hidden && fab_nav 0
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
  if lookup_hidden; then _draw=1; else _draw=0; fi
  if lookup_inset_gone; then _inset=1; else _inset=0; fi
  _prev=`cat "$WANT" 2>/dev/null | tr -d '\r\n'`
  # A hidden draw bool is not enough. The empty strip is the inset.
  if [ "$_prev" = "$_b $_w" ] && [ "$_draw" = "$_w" ] && [ "$_inset" = "$_w" ]; then
    return 0
  fi
  if [ "$_draw" = "$_w" ] && [ "$_inset" = "$_w" ]; then
    log "already hide=$_w"
    printf '%s %s\n' "$_b" "$_w" >"$WANT" 2>/dev/null || true
    chmod 666 "$WANT" 2>/dev/null || true
    return 0
  fi
  _inset_before=$_inset
  set_overlays "$_w"
  if lookup_hidden; then _draw=1; else _draw=0; fi
  if lookup_inset_gone; then _inset=1; else _inset=0; fi
  if [ "$_draw" != "$_w" ] || [ "$_inset" != "$_w" ]; then
    _fail="$ST/titan2_ime_bar.fail"
    _ft=`cat "$_fail" 2>/dev/null | tr -d '\r\n'`
    if [ "$_ft" != "$_b $_w" ]; then
      log "overlay unchanged hide=$_w draw=$_draw inset=$_inset"
      printf '%s %s\n' "$_b" "$_w" >"$_fail" 2>/dev/null || true
    fi
    return 0
  fi
  rm -f "$ST/titan2_ime_bar.fail" 2>/dev/null || true
  reload_taskbar "$_inset_before" "$_inset"
  if [ "$_w" = 1 ]; then
    restore_trimmed_imes
  fi
  restart_ime
  log "applied hide=$_w"
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
