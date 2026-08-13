#!/system/bin/sh
# titan2-keylayout — OPTIMIZE Phase 3 peel: dynamic TitanKey KL + KCM ensure
# SoT: docs/project/OPTIMIZE_SOURCE_PRODUCT.md
# Invoked by pad-agent:
#   apply <specials_scan> <fn_mode> [host_layout]
#   heal [scan] [fn_mode]  — rare live_kl residual (2.194)
#   kcm
#   version
export PATH=/system/bin:/system/xbin:/vendor/bin:$PATH
T2=/data/misc/titan2
ST=/data/local/tmp
FN_STATUS=$ST/titan2_fn_status
CHAR_STATUS=$ST/titan2_char_status
KL_VER=2.195-recents-f24
KCM_ENSURED=0
FORCE_TITANKEY_UEVENT="${FORCE_TITANKEY_UEVENT:-0}"
LAST_TITANKEY_UEVENT_S=0
FN_APPLY_STATE=$ST/titan2_fn_apply_last

log() {
  mkdir -p "$ST" 2>/dev/null || true
  { echo "keylayout: $*" >>"$ST/titan2_pad_agent.log"; } 2>/dev/null || true
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

read_char_mod() {
  case "`read_first titan2_char_mod`" in
    alt|ALT|stock) echo alt ;;
    fn|FN|function) echo fn ;;
    custom|CUSTOM|scan|other) echo custom ;;
    sym|SYM|symbol|""|*) echo sym ;;
  esac
}

read_fn_mode() {
  case "`read_first titan2_fn_mode`" in
    stock|STOCK|normal|default_stock) echo stock ;;
    ctrl|CTRL|control|fn|fnctrl|"" ) echo ctrl ;;
    *) echo ctrl ;;
  esac
}

read_specials_method() {
  case "`read_first titan2_specials_method`" in
    inject|INJECT) echo inject ;;
    kcm|KCM|alt_kcm|legacy|""|*) echo kcm ;;
  esac
}

read_char_mod_scan_raw() {
  v=`read_first titan2_char_mod_scan`
  case "$v" in
    ''|0|none|off|null) echo "" ;;
    *)
      echo "$v" | grep -Eq '^[0-9]+$' && echo "$v" || echo ""
      ;;
  esac
}

resolve_specials_scan() {
  cm=`read_char_mod`
  case "$cm" in
    fn) echo 251; return ;;
    alt) echo 100; return ;;
    custom)
      raw=`read_char_mod_scan_raw`
      [ -n "$raw" ] && echo "$raw" && return
      echo 253
      ;;
    *) echo 253 ;;
  esac
}

_is_fn_sc() { [ "$1" = "183" ] || [ "$1" = "251" ]; }

read_host_layout() {
  best_mt=0
  best_v=""
  for f in "$T2/titan2_host_layout" "$ST/titan2_host_layout"; do
    [ -f "$f" ] || continue
    v=`_read_line_file "$f" | tr -d '\r\n ' | tr 'A-Z' 'a-z'`
    case "$v" in specials|arrows|off|c_*) ;; *) continue ;; esac
    mt=`stat -c %Y "$f" 2>/dev/null` || mt=0
    case "$mt" in ''|*[!0-9]*) mt=0;; esac
    if [ "$mt" -ge "$best_mt" ] 2>/dev/null; then
      best_mt=$mt
      best_v=$v
    fi
  done
  if [ -z "$best_v" ]; then
    g=`settings get global titan2_host_layout 2>/dev/null | tr -d '\r\n ' | tr 'A-Z' 'a-z'`
    case "$g" in specials|arrows|off|c_*) best_v=$g ;; esac
  fi
  case "$best_v" in specials|arrows) echo "$best_v" ;; *) echo off ;; esac
}

persist_ctrl() {
  name="$1"; val="$2"
  [ -n "$val" ] || return 0
  mkdir -p "$T2" 2>/dev/null || true
  cur=`cat "$T2/$name" 2>/dev/null | tr -d '\r\n '`
  if [ "$cur" = "$val" ]; then
    last=`cat "$T2/${name}_last" 2>/dev/null | tr -d '\r\n '`
    if [ "$last" != "$val" ]; then
      { echo "$val" >"$T2/${name}_last"; } 2>/dev/null || true
      chmod 666 "$T2/${name}_last" 2>/dev/null || true
    fi
    return 0
  fi
  { echo "$val" >"$T2/$name"; } 2>/dev/null || true
  chmod 666 "$T2/$name" 2>/dev/null || true
  { echo "$val" >"$T2/${name}_last"; } 2>/dev/null || true
  chmod 666 "$T2/${name}_last" 2>/dev/null || true
}

_load_fn_state() {
  LAST_FN=""; LAST_CHAR_MOD=""; LAST_CHAR_SCAN=""; LAST_HOST_LAYOUT=""
  LAST_SPECIALS_METHOD=""
  [ -f "$FN_APPLY_STATE" ] || return 0
  # shellcheck disable=SC1090
  . "$FN_APPLY_STATE" 2>/dev/null || true
}

_save_fn_state() {
  mkdir -p "$ST" 2>/dev/null || true
  {
    echo "LAST_FN='$LAST_FN'"
    echo "LAST_CHAR_MOD='$LAST_CHAR_MOD'"
    echo "LAST_CHAR_SCAN='$LAST_CHAR_SCAN'"
    echo "LAST_HOST_LAYOUT='$LAST_HOST_LAYOUT'"
    echo "LAST_SPECIALS_METHOD='$LAST_SPECIALS_METHOD'"
  } >"$FN_APPLY_STATE" 2>/dev/null || true
  chmod 666 "$FN_APPLY_STATE" 2>/dev/null || true
}

# Apply specials layout (pad-agent apply_fn peel).
apply_fn() {
  _load_fn_state
  tries=0
  while [ "$tries" -lt 3 ]; do
    tries=`expr $tries + 1 2>/dev/null` || tries=3
    mode=`read_fn_mode`
    cm=`read_char_mod`
    sp=`resolve_specials_scan`
    hlay=`read_host_layout`
    case "$hlay" in specials|arrows) ;; *) hlay=off ;; esac
    hlay_kl=off
    if _is_fn_sc "$sp"; then
      mode=stock
    fi
    if [ -f "$ST/titan2_force_titankey_uevent" ]; then
      FORCE_TITANKEY_UEVENT=1
      rm -f "$ST/titan2_force_titankey_uevent" 2>/dev/null || true
      log "force TitanKey uevent (plane request)"
    fi
    meth_now=`read_specials_method`
    if [ -z "${LAST_SPECIALS_METHOD:-}" ] && [ "$meth_now" = "kcm" ]; then
      LAST_SPECIALS_METHOD=kcm
    fi
    if [ "$meth_now" != "${LAST_SPECIALS_METHOD:-}" ]; then
      log "specials_method ${LAST_SPECIALS_METHOD:-?}→$meth_now force KL+uevent"
      LAST_SPECIALS_METHOD=$meth_now
      FORCE_TITANKEY_UEVENT=1
    elif [ "${FORCE_TITANKEY_UEVENT:-0}" != "1" ] \
        && [ "$LAST_FN" = "$mode" ] && [ "$LAST_CHAR_MOD" = "$cm" ] && [ "$LAST_CHAR_SCAN" = "$sp" ] \
        && [ "$LAST_HOST_LAYOUT" = "$hlay" ] \
        && live_kl_matches "$sp" "$mode" "$hlay_kl"; then
      want_st="fn=$mode char=$cm scan=$sp hlay=$hlay hlay_kl=$hlay_kl layout=dynamic skip=unchanged"
      for sf in "$FN_STATUS" "$CHAR_STATUS"; do
        cur_st=`_read_line_file "$sf" 2>/dev/null`
        [ "$cur_st" = "$want_st" ] && continue
        echo "$want_st" > "$sf" 2>/dev/null || true
        chmod 666 "$sf" 2>/dev/null || true
      done
      _save_fn_state
      return 0
    fi
    set_keylayout_dynamic "$sp" "$mode" "$hlay_kl"
    cm2=`read_char_mod`
    sp2=`resolve_specials_scan`
    mode2=`read_fn_mode`
    hlay2=`read_host_layout`
    case "$hlay2" in specials|arrows) ;; *) hlay2=off ;; esac
    if _is_fn_sc "$sp2"; then mode2=stock; fi
    if [ "$cm2" != "$cm" ] || [ "$sp2" != "$sp" ] || [ "$mode2" != "$mode" ] || [ "$hlay2" != "$hlay" ]; then
      continue
    fi
    LAST_FN=$mode
    LAST_CHAR_MOD=$cm
    LAST_CHAR_SCAN=$sp
    LAST_HOST_LAYOUT=$hlay
    if [ "`read_char_mod`" = "$cm" ]; then
      persist_ctrl titan2_fn_mode "$mode"
      persist_ctrl titan2_char_mod "$cm"
      raw=`read_char_mod_scan_raw`
      if [ -n "$raw" ]; then
        persist_ctrl titan2_char_mod_scan "$raw"
      else
        persist_ctrl titan2_char_mod_scan "0"
      fi
    fi
    _save_fn_state
    return 0
  done
  mode=`read_fn_mode`
  cm=`read_char_mod`
  sp=`resolve_specials_scan`
  hlay=`read_host_layout`
  case "$hlay" in specials|arrows) ;; *) hlay=off ;; esac
  if _is_fn_sc "$sp"; then mode=stock; fi
  set_keylayout_dynamic "$sp" "$mode" off
  LAST_FN=$mode
  LAST_CHAR_MOD=$cm
  LAST_CHAR_SCAN=$sp
  LAST_HOST_LAYOUT=$hlay
  _save_fn_state
  return 0
}


kcm_layer_count() {
  f="$1"
  [ -f "$f" ] || { echo 0; return; }
  # Product path: ralt: specials. Accept legacy alt:/meta: only as fallback.
  n=`grep -c "ralt:" "$f" 2>/dev/null` || n=0
  if [ "$n" -ge 20 ] 2>/dev/null; then
    echo "$n"
    return
  fi
  n=`grep -c "alt:" "$f" 2>/dev/null` || n=0
  if [ "$n" -ge 20 ] 2>/dev/null; then
    echo "$n"
    return
  fi
  n=`grep -c "meta:" "$f" 2>/dev/null` || n=0
  echo "$n"
}

# Bare "alt:" glyph columns steal free ALT_LEFT (dual-meta regression).
kcm_has_free_alt_leak() {
  f="$1"
  [ -f "$f" ] || return 1
  # Lines that start with alt: (not ralt / shift+alt) = free-Alt specials leak
  n=`grep -E '^[[:space:]]*alt:' "$f" 2>/dev/null | wc -l` || n=0
  [ "$n" -ge 10 ] 2>/dev/null
}

# Prefer ralt-only product KCM (no bare alt: specials columns).
kcm_is_product_ralt() {
  f="$1"
  [ -f "$f" ] || return 1
  n=`grep -c "ralt:" "$f" 2>/dev/null` || n=0
  [ "$n" -ge 20 ] 2>/dev/null || return 1
  kcm_has_free_alt_leak "$f" && return 1
  return 0
}

ensure_titan_kcm() {
  KC_DIR=/system/usr/keychars
  ETC=/system/etc/titan2_keylayout
  DATA_KC=/data/local/tmp/titan2_kcm
  DATA_SYS_KC=/data/system/devices/keychars
  mkdir -p "$DATA_KC" "$DATA_SYS_KC" 2>/dev/null
  # Already product ralt-only (no free-Alt leak)?
  good=0
  for base in Vendor_2533_Product_2533 TitanKey; do
    for f in $KC_DIR/${base}.kcm $DATA_SYS_KC/${base}.kcm; do
      if kcm_is_product_ralt "$f"; then
        good=1
        break
      fi
    done
    [ "$good" = "1" ] && break
  done
  [ "$good" = "1" ] && return 0
  src=""
  # Prefer ralt-only product sources first; fall back to any dual-meta KCM
  for c in \
    $ETC/Vendor_2533_Product_2533.kcm \
    $ETC/TitanKey.kcm \
    /data/adb/modules/titan2_keychars/system/usr/keychars/Vendor_2533_Product_2533.kcm \
    /data/adb/modules/titan2_keychars/system/usr/keychars/TitanKey.kcm \
    $DATA_KC/Vendor_2533_Product_2533.kcm \
    $DATA_KC/TitanKey.kcm \
    $KC_DIR/Vendor_2533_Product_2533.kcm \
    $KC_DIR/TitanKey.kcm
  do
    [ -f "$c" ] || continue
    if kcm_is_product_ralt "$c"; then
      src=$c
      break
    fi
  done
  if [ -z "$src" ]; then
    for c in \
      $ETC/Vendor_2533_Product_2533.kcm \
      $ETC/TitanKey.kcm \
      $DATA_KC/Vendor_2533_Product_2533.kcm \
      $DATA_KC/TitanKey.kcm \
      $KC_DIR/Vendor_2533_Product_2533.kcm \
      $KC_DIR/TitanKey.kcm
    do
      [ -f "$c" ] || continue
      n=`kcm_layer_count "$c"`
      [ "$n" -ge 20 ] 2>/dev/null || continue
      src=$c
      break
    done
  fi
  [ -n "$src" ] || return 1
  wrote=0
  for base in Vendor_2533_Product_2533 TitanKey; do
    cp "$src" "$DATA_KC/${base}.kcm" 2>/dev/null && chmod 0644 "$DATA_KC/${base}.kcm" 2>/dev/null
    cp "$src" "$DATA_SYS_KC/${base}.kcm" 2>/dev/null && chmod 0644 "$DATA_SYS_KC/${base}.kcm" 2>/dev/null
    mount -o remount,rw /system 2>/dev/null || mount -o remount,rw / 2>/dev/null || true
    if cp "$src" "$KC_DIR/${base}.kcm" 2>/dev/null; then
      chmod 0644 "$KC_DIR/${base}.kcm" 2>/dev/null
      wrote=1
    elif [ -f "$DATA_KC/${base}.kcm" ] && [ -e "$KC_DIR/${base}.kcm" ]; then
      umount "$KC_DIR/${base}.kcm" 2>/dev/null || true
      mount --bind "$DATA_KC/${base}.kcm" "$KC_DIR/${base}.kcm" 2>/dev/null && wrote=1
    fi
  done
  if kcm_is_product_ralt "$KC_DIR/Vendor_2533_Product_2533.kcm" \
      || kcm_is_product_ralt "$DATA_SYS_KC/Vendor_2533_Product_2533.kcm"; then
    return 0
  fi
  [ "$wrote" = "1" ] && return 0
  return 1
}

# ---- Movable specials modifier (stock-like) ---------------------------------
# Android types printed specials via KCM ralt: + ALT_RIGHT meta bits.
# Any physical scan can own specials by mapping that scan → ALT_RIGHT.
# Free Alt (scan 100) is ALT_LEFT when it is not the specials key (real Alt).
# Optional titan2_char_mod_scan=<linux scan> overrides named sym|fn|alt.

read_fn_mode() {
  # Default ctrl: Fn-as-Ctrl is the intended product behavior.
  case "`read_first titan2_fn_mode`" in
    stock|STOCK|normal|default_stock) echo stock ;;
    ctrl|CTRL|control|fn|fnctrl|"") echo ctrl ;;
    *) echo ctrl ;;
  esac
}

read_char_mod() {
  case "`read_first titan2_char_mod`" in
    alt|ALT|stock) echo alt ;;
    fn|FN|function) echo fn ;;
    custom|CUSTOM|scan|other) echo custom ;;
    sym|SYM|symbol|""|*) echo sym ;;
  esac
}

# inject = a11y KeyActions owns glyphs; kcm = ALT_RIGHT + TitanKey.kcm ralt.
# 2.52 FB-IN-1: empty/unknown → kcm product default (was inject residual).
read_specials_method() {
  case "`read_first titan2_specials_method`" in
    inject|INJECT) echo inject ;;
    kcm|KCM|alt_kcm|legacy|""|*) echo kcm ;;
  esac
}

# Numeric specials scan, or empty to use named char_mod preset.
read_char_mod_scan_raw() {
  v=`read_first titan2_char_mod_scan`
  case "$v" in
    ''|0|none|off|null) echo "" ;;
    *)
      echo "$v" | grep -Eq '^[0-9]+$' && echo "$v" || echo ""
      ;;
  esac
}

# Resolve specials owner scan (OEM: Alt=100, Fn=251/183, Sym=253/222).
# Named alt|sym|fn ALWAYS map to OEM scans — never let a stale titan2_char_mod_scan
# override (mtime races during UI Alt↔Fn made specials stick on Sym/Fn and look
# "unstable"). Custom scan only when char_mod=custom (Other… pick).
resolve_specials_scan() {
  cm=`read_char_mod`
  case "$cm" in
    fn) echo 251; return ;;
    alt) echo 100; return ;;
    custom)
      raw=`read_char_mod_scan_raw`
      [ -n "$raw" ] && echo "$raw" && return
      echo 253
      ;;
    *) echo 253 ;;
  esac
}

_is_fn_sc() { [ "$1" = "183" ] || [ "$1" = "251" ]; }
_is_sym_sc() { [ "$1" = "222" ] || [ "$1" = "253" ]; }
_is_alt_sc() { [ "$1" = "100" ]; }

# True if scan should be ALT_RIGHT for this specials owner.
_scan_is_specials() {
  sc="$1"
  sp="$2"
  [ -n "$sc" ] && [ -n "$sp" ] || return 1
  [ "$sc" = "$sp" ] && return 0
  # Expand OEM dual reports only when specials is that family
  if _is_fn_sc "$sp" && _is_fn_sc "$sc"; then return 0; fi
  if _is_sym_sc "$sp" && _is_sym_sc "$sc"; then return 0; fi
  return 1
}

# Label for a role scan in the generated layout.
_role_label() {
  sc="$1"
  sp="$2"
  fnm="$3"
  if _scan_is_specials "$sc" "$sp"; then
    # inject method: never ALT_RIGHT — host USB would show Alt (Mac/PC) while
    # a11y inject owns glyphs. kcm method keeps ALT_RIGHT for TitanKey.kcm.
    if [ "`read_specials_method`" = "inject" ]; then
      echo BUTTON_2
    else
      echo ALT_RIGHT
    fi
    return
  fi
  if _is_alt_sc "$sc"; then
    # Free Alt = real ALT_LEFT (menus, Alt+Tab). Must NOT be ALT_RIGHT (Sym
    # specials) and must NOT be META (Win-key feel). KCM is ralt-only so
    # ALT_LEFT never types printed specials.
    echo ALT_LEFT
    return
  fi
  if _is_fn_sc "$sc"; then
    if [ "$fnm" = "ctrl" ]; then echo CTRL_LEFT; else echo BUTTON_1; fi
    return
  fi
  if _is_sym_sc "$sc"; then
    # Sym when not specials owner (e.g. specials moved to Alt) — inert button
    echo BUTTON_2
    return
  fi
  # Extra custom specials scan already handled; leftover → inert
  echo BUTTON_1
}

# Letter row labels for sticky host layout (real Android KeyEvents for Termux /
# Moonlight / any onKeyDown app). a11y inject cannot do this on signature-only
# INJECT_EVENTS builds. Labels must be GSI KeyLayoutMap-valid.
_letter_label() {
  sc="$1"
  lay="$2"   # off | specials | arrows | …
  case "$lay" in
    specials)
      # Titan specials layer as real keycodes (closest GSI labels)
      case "$sc" in
        16) echo 0 ;;              # Q → 0
        17) echo 1 ;;              # W → 1
        18) echo 2 ;;              # E → 2
        19) echo 3 ;;              # R → 3
        20) echo LEFT_BRACKET ;;   # T → ( ≈ [
        21) echo RIGHT_BRACKET ;;  # Y → ) ≈ ]
        22) echo MINUS ;;          # U → -
        23) echo MINUS ;;          # I → _ ≈ -
        24) echo SLASH ;;          # O → /
        25) echo SEMICOLON ;;      # P → :
        30) echo AT ;;             # A → @
        31) echo 4 ;;              # S → 4
        32) echo 5 ;;              # D → 5
        33) echo 6 ;;              # F → 6
        34) echo STAR ;;           # G → *
        35) echo POUND ;;          # H → #
        36) echo PLUS ;;           # J → +
        37) echo APOSTROPHE ;;     # K → "
        38) echo APOSTROPHE ;;     # L → '
        44) echo 1 ;;              # Z → ! ≈ 1
        45) echo 7 ;;              # X → 7
        46) echo 8 ;;              # C → 8
        47) echo 9 ;;              # V → 9
        48) echo PERIOD ;;         # B → .
        49) echo COMMA ;;          # N → ,
        50) echo SLASH ;;          # M → ?
        *) echo "" ;;
      esac
      ;;
    arrows)
      case "$sc" in
        17|23) echo DPAD_UP ;;     # W I
        30|36) echo DPAD_LEFT ;;   # A J
        31|45|49|37) echo DPAD_DOWN ;; # S X N K
        32|38) echo DPAD_RIGHT ;;  # D L
        35) echo MOVE_HOME ;;      # H
        22|16) echo PAGE_UP ;;     # U Q
        24|18) echo PAGE_DOWN ;;   # O E
        *) echo "" ;;
      esac
      ;;
    *) echo "" ;;
  esac
}

# Write full TitanKey-compatible .kl with movable specials (GSI-valid labels only).
# Optional 4th arg: host layout sticky id (off|specials|arrows) → letter remaps.
write_dynamic_kl() {
  out="$1"
  sp="$2"
  fnm="$3"
  hlay="${4:-off}"
  case "$hlay" in
    specials|arrows) ;;
    *) hlay=off ;;
  esac
  l100=`_role_label 100 "$sp" "$fnm"`
  l183=`_role_label 183 "$sp" "$fnm"`
  l222=`_role_label 222 "$sp" "$fnm"`
  l251=`_role_label 251 "$sp" "$fnm"`
  l253=`_role_label 253 "$sp" "$fnm"`
  # Custom scan outside the known set: inject → BUTTON_2 (no host Alt); kcm → ALT_RIGHT
  extra=""
  case "$sp" in
    100|183|222|251|253) ;;
    ''|0) ;;
    *)
      if echo "$sp" | grep -Eq '^[0-9]+$'; then
        if [ "`read_specials_method`" = "inject" ]; then
          extra="key $sp   BUTTON_2"
        else
          extra="key $sp   ALT_RIGHT"
        fi
      fi
      ;;
  esac
  # Letter keys: sticky layout remap or normal QWERTY
  l16=`_letter_label 16 "$hlay"`; [ -n "$l16" ] || l16=Q
  l17=`_letter_label 17 "$hlay"`; [ -n "$l17" ] || l17=W
  l18=`_letter_label 18 "$hlay"`; [ -n "$l18" ] || l18=E
  l19=`_letter_label 19 "$hlay"`; [ -n "$l19" ] || l19=R
  l20=`_letter_label 20 "$hlay"`; [ -n "$l20" ] || l20=T
  l21=`_letter_label 21 "$hlay"`; [ -n "$l21" ] || l21=Y
  l22=`_letter_label 22 "$hlay"`; [ -n "$l22" ] || l22=U
  l23=`_letter_label 23 "$hlay"`; [ -n "$l23" ] || l23=I
  l24=`_letter_label 24 "$hlay"`; [ -n "$l24" ] || l24=O
  l25=`_letter_label 25 "$hlay"`; [ -n "$l25" ] || l25=P
  l30=`_letter_label 30 "$hlay"`; [ -n "$l30" ] || l30=A
  l31=`_letter_label 31 "$hlay"`; [ -n "$l31" ] || l31=S
  l32=`_letter_label 32 "$hlay"`; [ -n "$l32" ] || l32=D
  l33=`_letter_label 33 "$hlay"`; [ -n "$l33" ] || l33=F
  l34=`_letter_label 34 "$hlay"`; [ -n "$l34" ] || l34=G
  l35=`_letter_label 35 "$hlay"`; [ -n "$l35" ] || l35=H
  l36=`_letter_label 36 "$hlay"`; [ -n "$l36" ] || l36=J
  l37=`_letter_label 37 "$hlay"`; [ -n "$l37" ] || l37=K
  l38=`_letter_label 38 "$hlay"`; [ -n "$l38" ] || l38=L
  l44=`_letter_label 44 "$hlay"`; [ -n "$l44" ] || l44=Z
  l45=`_letter_label 45 "$hlay"`; [ -n "$l45" ] || l45=X
  l46=`_letter_label 46 "$hlay"`; [ -n "$l46" ] || l46=C
  l47=`_letter_label 47 "$hlay"`; [ -n "$l47" ] || l47=V
  l48=`_letter_label 48 "$hlay"`; [ -n "$l48" ] || l48=B
  l49=`_letter_label 49 "$hlay"`; [ -n "$l49" ] || l49=N
  l50=`_letter_label 50 "$hlay"`; [ -n "$l50" ] || l50=M
  {
    echo "# dynamic specials_scan=$sp fn_mode=$fnm host_layout=$hlay"
    echo "# Specials key → ALT_RIGHT (KCM ralt:). Sticky layout = real keycodes."
    echo ""
    echo "key 14    DEL"
    echo "key 16    $l16"
    echo "key 17    $l17"
    echo "key 18    $l18"
    echo "key 19    $l19"
    echo "key 20    $l20"
    echo "key 21    $l21"
    echo "key 22    $l22"
    echo "key 23    $l23"
    echo "key 24    $l24"
    echo "key 25    $l25"
    echo "key 28    ENTER"
    echo "key 30    $l30"
    echo "key 31    $l31"
    echo "key 32    $l32"
    echo "key 33    $l33"
    echo "key 34    $l34"
    echo "key 35    $l35"
    echo "key 36    $l36"
    echo "key 37    $l37"
    echo "key 38    $l38"
    echo "key 42    SHIFT_LEFT"
    echo "key 44    $l44"
    echo "key 45    $l45"
    echo "key 46    $l46"
    echo "key 47    $l47"
    echo "key 48    $l48"
    echo "key 49    $l49"
    echo "key 50    $l50"
    echo "key 52    PERIOD"
    echo "key 57    SPACE"
    echo "key 59    ESCAPE"
    echo "key 68    TAB"
    echo "key 87    GRAVE"
    echo "key 100   $l100"
    echo "key 103   DPAD_UP"
    echo "key 105   DPAD_LEFT"
    echo "key 106   DPAD_RIGHT"
    echo "key 108   DPAD_DOWN"
    echo "key 115   VOLUME_UP"
    echo "key 116   POWER   WAKE"
    echo "key 128   MEDIA_STOP"
    echo "key 133   COPY"
    echo "key 135   PASTE"
    echo "key 137   CUT"
    echo "key 158   BACK"
    echo "key 183   $l183"
    echo "key 222   $l222"
    # Side · top/bottom (249/250) must NEVER appear in TitanKey/Vendor kl.
    # Mapping BUTTON_* / APP_SWITCH / ALT here re-enables system Home/nav
    # gestures. Pad-agent side-watch owns those scans exclusively.
    # (Do not map even when specials_scan is 249/250 — use a letter key.)
    if [ -n "$extra" ] && [ "$sp" != "249" ] && [ "$sp" != "250" ]; then
      echo "$extra"
    fi
    echo "key 251   $l251"
    echo "key 253   $l253"
    # Scan 580 KEY_APPSELECT: omitting it is NOT unmapped — EventHub defaults
    # to APP_SWITCH (PWM hold-preview, release-dismiss). Map to F24 so PWM
    # never sees APP_SWITCH. key-watch getevent owns short=Home long=Recents.
    echo "key 580   F24"
  } > "$out" 2>/dev/null
  chmod 0644 "$out" 2>/dev/null
  [ -s "$out" ]
}

# True if live Vendor kl already has the expected role labels (no rebind needed).
# Optional 3rd arg: host layout sticky (off|specials|arrows).
live_kl_matches() {
  sp="$1"
  fnm="$2"
  hlay="${3:-off}"
  case "$hlay" in specials|arrows) ;; *) hlay=off ;; esac
  KL=/system/usr/keylayout/Vendor_2533_Product_2533.kl
  [ -f "$KL" ] || return 1
  # One grep pass — avoid per-scan grep|head|awk pipe storms (and hangs).
  kl_body=`grep -E '^key (100|183|222|251|253|16)[[:space:]]' "$KL" 2>/dev/null` || return 1
  for sc in 100 183 222 251 253; do
    want=`_role_label "$sc" "$sp" "$fnm"`
    got=`printf '%s\n' "$kl_body" | grep -E "^key $sc[[:space:]]" | head -1`
    set -- $got
    got=$3
    [ -n "$got" ] || return 1
    [ "$got" = "$want" ] || return 1
  done
  # Sticky layout probe: Q (scan 16) is 0 under specials, DPAD under arrows, else Q
  want16=`_letter_label 16 "$hlay"`
  [ -n "$want16" ] || want16=Q
  got16=`printf '%s\n' "$kl_body" | grep -E '^key 16[[:space:]]' | head -1`
  set -- $got16
  got16=$3
  [ -n "$got16" ] || return 1
  [ "$got16" = "$want16" ] || return 1
  # Recents peel: 580 must be F24 (omitted → EventHub APP_SWITCH hold-preview)
  got580=`grep -E '^key 580[[:space:]]' "$KL" 2>/dev/null | head -1`
  set -- $got580
  [ "$3" = "F24" ] || return 1
  return 0
}

# Install generated layout into paths InputReader actually loads.
# Magisk often makes /system/usr/keylayout a RO tmpfs overlay — prefer:
#   1) data-plane file (bind source), 2) bind-mount, 3) direct write if rw.
#
# CRITICAL: never `cp` over an active bind source — that replaces the inode and
# the system path keeps the old layout until umount/rebind (races → Alt↔Fn
# flaky + InputReader/UI restart). Update bind sources **in place** with cat.
set_keylayout_dynamic() {
  sp="$1"
  fnm="$2"
  hlay="${3:-off}"
  case "$hlay" in specials|arrows) ;; *) hlay=off ;; esac
  KL_DIR=/system/usr/keylayout
  ETC=/system/etc/titan2_keylayout
  DATA_KL=/data/local/tmp/titan2_kl
  DATA_SYS_KL=/data/system/devices/keylayout
  mkdir -p "$DATA_KL" "$DATA_SYS_KL" "$ETC" 2>/dev/null
  # Rootless product: pad-agent (root) must leave dir world-writable so shell/heal
  # and later peels can rewrite dynamic kl (lab: Permission denied on 700 root dir).
  chmod 0777 "$DATA_KL" 2>/dev/null || true
  gen=$DATA_KL/TitanKey.dynamic.kl
  # Fast path: already live — no write, no uevent (avoids UI restart).
  # FORCE_TITANKEY_UEVENT must still reopen EventHub (F24 vs Generic APP_SWITCH).
  if [ "${FORCE_TITANKEY_UEVENT:-0}" != "1" ] && live_kl_matches "$sp" "$fnm" "$hlay"; then
    live2=`grep -E "^key $sp[[:space:]]" "$KL_DIR/Vendor_2533_Product_2533.kl" 2>/dev/null | tr -s ' ' | head -1`
    want_st="layout=dynamic sp=$sp fn=$fnm hlay=$hlay wrote=0 skip=live_ok live=$live2"
    want_ch="fn=$fnm char=`read_char_mod` scan=$sp layout=dynamic hlay=$hlay skip=live_ok live=$live2"
    # Skip status rewrite if unchanged (mtime thrash residual)
    cur_st=`_read_line_file "$FN_STATUS" 2>/dev/null`
    if [ "$cur_st" != "$want_st" ]; then
      echo "$want_st" > "$FN_STATUS" 2>/dev/null || true
      chmod 666 "$FN_STATUS" 2>/dev/null || true
    fi
    cur_ch=`_read_line_file "$CHAR_STATUS" 2>/dev/null`
    if [ "$cur_ch" != "$want_ch" ]; then
      echo "$want_ch" > "$CHAR_STATUS" 2>/dev/null || true
      chmod 666 "$CHAR_STATUS" 2>/dev/null || true
    fi
    return 0
  fi
  if ! write_dynamic_kl "$gen" "$sp" "$fnm" "$hlay"; then
    echo "layout=dynamic wrote=0 err=gen sp=$sp hlay=$hlay" > "$FN_STATUS"
    chmod 666 "$FN_STATUS" 2>/dev/null
    return 1
  fi
  src=$gen
  # In-place refresh of data-plane copies (preserves bind-mount inodes)
  for base in Vendor_2533_Product_2533 TitanKey; do
    dest=$DATA_KL/${base}.kl
    if [ -f "$dest" ]; then
      cat "$src" > "$dest" 2>/dev/null || cp "$src" "$dest" 2>/dev/null
    else
      cp "$src" "$dest" 2>/dev/null
    fi
    chmod 0644 "$dest" 2>/dev/null
    # data/system + etc are not usually bind sources — cp is fine
    cp "$src" "$DATA_SYS_KL/${base}.kl" 2>/dev/null && chmod 0644 "$DATA_SYS_KL/${base}.kl" 2>/dev/null
    cp "$src" "$ETC/${base}.kl" 2>/dev/null || true
  done

  wrote=0
  rebound=0
  # If already bind-mounted to DATA_KL, in-place cat above updated live content
  if live_kl_matches "$sp" "$fnm" "$hlay"; then
    wrote=1
  else
    for base in Vendor_2533_Product_2533 TitanKey; do
      if [ -f "$DATA_KL/${base}.kl" ]; then
        umount "$KL_DIR/${base}.kl" 2>/dev/null || true
        if mount --bind "$DATA_KL/${base}.kl" "$KL_DIR/${base}.kl" 2>/dev/null; then
          wrote=1
          rebound=1
          continue
        fi
      fi
      mount -o remount,rw /system 2>/dev/null || mount -o remount,rw / 2>/dev/null || true
      # Prefer in-place write into system file when rw
      if [ -f "$KL_DIR/${base}.kl" ] && cat "$src" > "$KL_DIR/${base}.kl" 2>/dev/null; then
        chmod 0644 "$KL_DIR/${base}.kl" 2>/dev/null
        wrote=1
      elif cp "$src" "$KL_DIR/${base}.kl" 2>/dev/null; then
        chmod 0644 "$KL_DIR/${base}.kl" 2>/dev/null
        wrote=1
      fi
    done
    # Verify InputReader path matches specials owner (catches silent RO failures)
    if ! live_kl_matches "$sp" "$fnm" "$hlay"; then
      if [ -f "$DATA_KL/Vendor_2533_Product_2533.kl" ]; then
        umount "$KL_DIR/Vendor_2533_Product_2533.kl" 2>/dev/null || true
        if mount --bind "$DATA_KL/Vendor_2533_Product_2533.kl" \
            "$KL_DIR/Vendor_2533_Product_2533.kl" 2>/dev/null; then
          wrote=1
          rebound=1
        fi
      fi
    fi
  fi
  # KCM is independent of specials owner — ensure once per process, not every toggle
  # (Magisk overlay walks are expensive and starved the specials loop).
  kcm_ok=1
  if [ "${KCM_ENSURED:-0}" != "1" ]; then
    if ensure_titan_kcm; then
      KCM_ENSURED=1
      kcm_ok=1
    else
      kcm_ok=0
    fi
  fi
  # Uevent when content changed so EventHub reloads labels. Prefer single
  # device poke; avoid umount/rebind storms that restart Settings UI.
  # 1.79: hard rate-limit — each TitanKey uevent bumps InputReader Generation
  # and residual dual-DOWN multi-letter spam (QA, Controls 13.07). At most one
  # soft poke / 30s even if wrote/rebound (labels already live after in-place cat).
  # 1.92/1.93: FORCE_TITANKEY_UEVENT bypasses rate-limit and uses remove+add.
  # Lab proof: echo change left Generation stuck; KL said ALT_RIGHT while
  # EventHub kept BUTTON_2 → KCM Sym silent, free Alt still worked.
  if [ "$wrote" = "1" ] || [ "$rebound" = "1" ] || [ "${FORCE_TITANKEY_UEVENT:-0}" = "1" ]; then
    _now_ue=`date +%s 2>/dev/null` || _now_ue=0
    _last_ue=${LAST_TITANKEY_UEVENT_S:-0}
    case "$_last_ue" in ''|*[!0-9]*) _last_ue=0 ;; esac
    _age_ue=`expr $_now_ue - $_last_ue 2>/dev/null` || _age_ue=999
    _force_ue=0
    [ "${FORCE_TITANKEY_UEVENT:-0}" = "1" ] && _force_ue=1
    if [ "$_force_ue" = "1" ] || [ "$_age_ue" -ge 30 ] 2>/dev/null || [ "$_last_ue" = "0" ]; then
      for d in /sys/class/input/input*; do
        [ -e "$d/name" ] || continue
        n=`cat "$d/name" 2>/dev/null` || continue
        [ "$n" = "TitanKey" ] || continue
        if [ "$_force_ue" = "1" ]; then
          # Hard reopen so EventHub reloads KeyLayoutMap (change alone is noop
          # on TitanKey — Generation stuck). Controls 13.23 still inject-fallbacks
          # if specials key stays BUTTON_2 after this.
          if [ -e "$d/inhibited" ]; then
            echo 1 > "$d/inhibited" 2>/dev/null || true
            if command -v usleep >/dev/null 2>&1; then usleep 50000; else sleep 0.05; fi
            echo 0 > "$d/inhibited" 2>/dev/null || true
          fi
          echo remove > "$d/uevent" 2>/dev/null || true
          if command -v usleep >/dev/null 2>&1; then usleep 80000; else sleep 0.08; fi
          echo add > "$d/uevent" 2>/dev/null || true
          echo change > "$d/uevent" 2>/dev/null || true
        else
          echo change > "$d/uevent" 2>/dev/null || true
        fi
      done
      LAST_TITANKEY_UEVENT_S=$_now_ue
      if [ "$_force_ue" = "1" ]; then
        log "TitanKey uevent HARD rebind (specials_method)"
      fi
    else
      log "skip TitanKey uevent age=${_age_ue}s (rate-limit multi-letter)"
    fi
    FORCE_TITANKEY_UEVENT=0
  fi
  live2=`grep -E "^key $sp[[:space:]]" "$KL_DIR/Vendor_2533_Product_2533.kl" 2>/dev/null | tr -s ' ' | head -1`
  echo "layout=dynamic sp=$sp fn=$fnm wrote=$wrote rebind=$rebound kcm=$kcm_ok live=$live2" > "$FN_STATUS"
  chmod 666 "$FN_STATUS" 2>/dev/null
  # Status uses the decision we applied (sp/fnm), not a racy re-read
  echo "fn=$fnm char_scan=$sp layout=dynamic rebind=$rebound live=$live2" > "$CHAR_STATUS" 2>/dev/null
  chmod 666 "$CHAR_STATUS" 2>/dev/null
}



# Rare live_kl heal (2.194 peel from pad-agent cool path).
# Exit 0 if KL bad / mismatch and caller should re-apply fn; 1 if healthy.
# Prints force_uevent=1 on stdout when kcm BUTTON_2 residual needs hard rebind.
live_kl_heal() {
  want_sp="${1-}"
  want_fn="${2-stock}"
  [ -n "$want_sp" ] || want_sp=`resolve_specials_scan`
  [ -n "$want_fn" ] || want_fn=`read_fn_mode`
  _inject_kl_bad=0
  _kcm_kl_bad=0
  _meth_heal=`read_specials_method`
  if [ "$_meth_heal" = "inject" ]; then
    for _kl in \
      /system/usr/keylayout/Vendor_2533_Product_2533.kl \
      /system/usr/keylayout/TitanKey.kl \
      /data/local/tmp/titan2_kl/Vendor_2533_Product_2533.kl \
      /data/local/tmp/titan2_kl/TitanKey.kl \
      /data/system/devices/keylayout/Vendor_2533_Product_2533.kl \
      /data/system/devices/keylayout/TitanKey.kl
    do
      [ -f "$_kl" ] || continue
      if grep -E "^key ${want_sp}[[:space:]]+ALT_RIGHT" "$_kl" >/dev/null 2>&1; then
        _inject_kl_bad=1
        break
      fi
    done
  elif [ "$_meth_heal" = "kcm" ]; then
    for _kl in \
      /system/usr/keylayout/Vendor_2533_Product_2533.kl \
      /system/usr/keylayout/TitanKey.kl \
      /data/local/tmp/titan2_kl/Vendor_2533_Product_2533.kl \
      /data/local/tmp/titan2_kl/TitanKey.kl
    do
      [ -f "$_kl" ] || continue
      if grep -E "^key ${want_sp}[[:space:]]+BUTTON_2" "$_kl" >/dev/null 2>&1; then
        _kcm_kl_bad=1
        break
      fi
    done
  fi
  if [ "$_inject_kl_bad" = "1" ] || [ "$_kcm_kl_bad" = "1" ] \
      || ! live_kl_matches "$want_sp" "$want_fn" off; then
    [ "$_inject_kl_bad" = "1" ] && log "heal inject KL: specials $want_sp was ALT_RIGHT → BUTTON_2"
    if [ "$_kcm_kl_bad" = "1" ]; then
      log "heal kcm KL: specials $want_sp was BUTTON_2 → ALT_RIGHT"
      echo "force_uevent=1"
    else
      echo "force_uevent=0"
    fi
    echo "need_apply=1 sp=$want_sp fn=$want_fn inject_bad=$_inject_kl_bad kcm_bad=$_kcm_kl_bad" \
      >"$ST/titan2_kl_heal_status" 2>/dev/null || true
    chmod 666 "$ST/titan2_kl_heal_status" 2>/dev/null || true
    return 0
  fi
  echo "need_apply=0" >"$ST/titan2_kl_heal_status" 2>/dev/null || true
  return 1
}

cmd="${1-apply}"
case "$cmd" in
  apply)
    sp="${2-}"
    fnm="${3-ctrl}"
    hlay="${4-off}"
    # env FORCE_TITANKEY_UEVENT=1 for hard rebind
    set_keylayout_dynamic "$sp" "$fnm" "$hlay"
    ;;
  matches)
    sp="${2-}"
    fnm="${3-ctrl}"
    hlay="${4-off}"
    live_kl_matches "$sp" "$fnm" "$hlay"
    ;;
  fn|apply_fn)
    apply_fn
    ;;
  heal|live_kl_heal)
    # optional: heal [scan] [fn_mode]
    live_kl_heal "${2-}" "${3-}"
    exit $?
    ;;
  kcm)
    ensure_titan_kcm
    ;;
  version|-v|--version)
    echo "$KL_VER"
    ;;
  *)
    log "usage: titan2-keylayout.sh apply|matches|fn|heal|kcm|version"
    exit 1
    ;;
esac
exit 0
