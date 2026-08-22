#!/system/bin/sh
# 2.212-dead-plane (drop dead residual helpers; densify plane readers / typing-watch live)
# Titan 2 control agent — pad/LED/specials supervisor (OPTIMIZE Phase 3 residual).
# Plane SoT: /data/misc/titan2 + /data/local/tmp. Peels: packages/gsi_product/prebuilt_sysbin.
# Keys: titan2_pad_mode, titan2_keyled_*, titan2_fn_mode, titan2_char_mod, titan2_ims_* (docs/project).

export PATH=/system/bin:/system/xbin:/vendor/bin:$PATH
# Canonical OS control plane (not MediaStore / Files root)
T2=/data/misc/titan2
# Phase 1.5+: GSI /system/bin/titan2-touchpadd (INPROC_PARK) SoT; tip if TITAN2_TOUCHPADD_TIP=1.
TOUCHPADD=/system/bin/titan2-touchpadd
if [ "${TITAN2_TOUCHPADD_TIP:-0}" = "1" ] && [ -x /data/local/tmp/titan2-touchpadd ] \
    && grep -aqF 'INPROC_PARK' /data/local/tmp/titan2-touchpadd 2>/dev/null; then
  TOUCHPADD=/data/local/tmp/titan2-touchpadd
elif [ -x /system/bin/titan2-touchpadd ] && grep -aqF 'INPROC_PARK' /system/bin/titan2-touchpadd 2>/dev/null; then
  TOUCHPADD=/system/bin/titan2-touchpadd
elif [ -x /data/adb/modules/titan2_touchpadd/system/bin/titan2-touchpadd ] \
    && grep -aqF 'INPROC_PARK' /data/adb/modules/titan2_touchpadd/system/bin/titan2-touchpadd 2>/dev/null; then
  TOUCHPADD=/data/adb/modules/titan2_touchpadd/system/bin/titan2-touchpadd
elif [ -x /data/local/tmp/titan2-touchpadd ] && grep -aqF 'PAD_SURFACE' /data/local/tmp/titan2-touchpadd 2>/dev/null; then
  TOUCHPADD=/data/local/tmp/titan2-touchpadd
fi

ST=/data/local/tmp
PAD_STATUS=$ST/titan2_pad_status
AGENT_STATUS=$ST/titan2_agent_status
ACTIVITY=$ST/titan2_key_activity
AGENT_LOCKDIR=$T2/pad-agent.lockdir

# Shared peel/sysbin resolver (2.193 densify): ST tip → Magisk module → /system/bin
_sysbin() {
  _bn="$1"
  [ -n "$_bn" ] || return 1
  # Tip/live names first so lab can override stale /system without remount.
  for _c in \
      "$ST/$_bn" \
      /data/local/tmp/"$_bn" \
      /data/local/tmp/"${_bn%.sh}-live.sh" \
      /data/local/tmp/titan2-dev-action-live.sh \
      /data/adb/modules/titan2_pad_agent/system/bin/"$_bn" \
      /system/bin/"$_bn"
  do
    # Only accept -live alias when looking up titan2-dev-action.sh
    case "$_c" in
      */titan2-dev-action-live.sh)
        [ "$_bn" = "titan2-dev-action.sh" ] || continue
        ;;
    esac
    [ -f "$_c" ] && [ -r "$_c" ] && { echo "$_c"; return 0; }
  done
  return 1
}

# Shared peel run (2.202 densify): resolve + exec peel. $1=basename $2=log-tag rest=args
_peel_run() {
  _bn="$1"; _tag="$2"; shift 2
  _s=`_sysbin "$_bn"` || {
    log "$_tag missing — install $_bn"
    return 1
  }
  /system/bin/sh "$_s" "$@"
  return $?
}

_run_ctrl_seed_agent() { _peel_run titan2-ctrl-seed.sh ctrl-seed agent; }

# Shared peel-daemon ensure (2.197 densify): live pidfile/pgrep + spawn script run.
# $1=script basename  $2=cmdline match  $3=pidfile base  $4=log base  $5=run args
_peel_daemon_live() {
  _match="$1"; _pf="$2"
  _wp=`cat "$ST/$_pf" 2>/dev/null | tr -d '\r\n '`
  case "$_wp" in ''|*[!0-9]*) ;; *)
    _cmdline_has "$_wp" "$_match" && return 0
    ;;
  esac
  for _wp in `pgrep -f "$_match" 2>/dev/null`; do
    case "$_wp" in ''|*[!0-9]*) continue ;; esac
    _cmdline_has "$_wp" "$_match" || continue
    echo "$_wp" >"$ST/$_pf" 2>/dev/null || true
    return 0
  done
  return 1
}
_ensure_peel_daemon() {
  _bn="$1"; _match="$2"; _pf="$3"; _lg="$4"; _args="${5:-run}"
  if _peel_daemon_live "$_match" "$_pf"; then
    return 0
  fi
  _script=`_sysbin "$_bn"` || {
    log "$_match missing $_bn"
    return 1
  }
  /system/bin/sh "$_script" $_args >>"$ST/$_lg" 2>&1 &
  echo $! >"$ST/$_pf" 2>/dev/null || true
  chmod 666 "$ST/$_pf" 2>/dev/null || true
  log "$_match pid=$! script=$_script"
}


# 2.167: typing-watch live = any parent (not only $$); write pid from status/pgrep.
#       Post-flash restart killed watch → sticky typing_lock=1 watch_dead_park.
# 2.166: IMS helpers+apply_ims_action peeled to titan2-ims-heal.sh (Phase 3).
# 2.163: typing-watch peeled to titan2-typing-watch.sh (OPTIMIZE Phase 3).
# Compat: TYPING_WATCH_ONLY=1 still works via exec of peeled script.
if [ "${TYPING_WATCH_ONLY:-0}" = "1" ]; then
  _tw=`_sysbin titan2-typing-watch.sh` || _tw=
  if [ -n "$_tw" ]; then
    exec /system/bin/sh "$_tw"
  fi
  echo "typing-watch missing titan2-typing-watch.sh" >"$ST/titan2_typing_watch_status" 2>/dev/null
  exit 1
fi
LAST_BRIGHT=""; LAST_TO=""; LAST_SCREEN=1; LAST_PAD=""
LAST_FN=""; LAST_CHAR_MOD=""; LAST_CHAR_SCAN=""; LAST_HOST_LAYOUT=""
LAST_CM_MT=""; LAST_SC_MT=""; LAST_FN_MT=""; LAST_HL_MT=""; LAST_SM_MT=""
LAST_IDC_KIND=""; LAST_PAD_MT=0; LAST_CLICK_MT=0; LAST_FOLLOW_MT=0; LAST_LOCK_MT=0; LAST_CE=""
# peels 2.160–2.212: see OPTIMIZE_SOURCE_PRODUCT.md
AGENT_VER="${AGENT_VER:-2.230-rom-lock}"
# Force pin: refuse non-2.x garbage + force upgrade sticky env older than 2.160
# (lab residual: sticky 2.6x/2.12x never picked tip peels). hot_reload still 2.NN*.
case "$AGENT_VER" in
  2.20[0-9]*|2.21[0-9]*|2.22[0-9]*|2.23[0-9]*|2.19[0-9]*|2.18[0-9]*|2.17[0-9]*|2.16[0-9]*) ;;
  *) AGENT_VER="2.230-rom-lock" ;;
esac
log() { echo "pad-agent $AGENT_VER live $1" > "$AGENT_STATUS" 2>/dev/null; chmod 666 "$AGENT_STATUS" 2>/dev/null; }
# Lightweight status stamp (no chmod every tick — 2.34+ heartbeat path).
_log_hb() { echo "pad-agent $AGENT_VER live $1" > "$AGENT_STATUS" 2>/dev/null; }
# Shared short sleep (2.211 densify): prefer usleep; fallback coarse sleep.
_usleep_us() {
  _u="${1:-20000}"
  if command -v usleep >/dev/null 2>&1; then usleep "$_u"; return 0; fi
  case "$_u" in
    5000|12000|15000) sleep 0.01 ;;
    50000) sleep 0.05 ;;
    100000) sleep 0.1 ;;
    *) sleep 0.02 ;;
  esac
}
_lock_pid() { cat "$AGENT_LOCKDIR/pid" 2>/dev/null | tr -d '\r\n '; }

_parse_agent_ver_line() {
  # stdin: one real assignment line → stdout: version token (2.NN-…)
  sed     -e 's/.*AGENT_VER:-//'     -e 's/.*AGENT_VER=//'     -e 's/}".*//'     -e 's/"//g'     -e 's/[[:space:]].*//'     -e 's/#.*//'
}
# Minor number from 2.NN-… (0 if unparsed). 2.206 densify.
_agent_ver_minor() {
  echo "$1" | sed -n 's/^2\.\([0-9][0-9]*\).*/\1/p'
}
_staged_tip_ver() {
  [ -x "$ST/titan2-pad-agent.sh" ] || return 1
  grep -E '^[[:space:]]*AGENT_VER=' "$ST/titan2-pad-agent.sh" 2>/dev/null | head -n1 | _parse_agent_ver_line
}
# 2.98: settings binder hangs multi-second on lab (i=20 freeze → pad mode lag).
# Never block the pad main loop on settings put — fire-and-forget only.
_settings_put_bg() {
  ( settings put "$@" >/dev/null 2>&1 ) &
}


# Product control plane under /data/misc is system_data_file by default — priv_app
# cannot open/write it. pad-agent (phhsu root) + boot seed relabel to shell_data_file
# (same type as /data/local/tmp). Idempotent; re-run after restorecon.
# Seed to current plane so first-boot apply_fn does not always HARD-rebind
# TitanKey (specials_method ?→kcm hung the main loop for minutes — caret dead).
FORCE_TITANKEY_UEVENT=0

# Root heat bug: exec/reload left getevent|while children reparented → 200+ procs.
# Never `ps -A` here — under load that hangs the agent mid-reload (lab 1.65).
# Only kill the known heat sources by name (pidof is cheap).
_kill_orphan_input_watchers() {
  n=0
  for p in `pidof getevent 2>/dev/null`; do
    kill -9 "$p" 2>/dev/null || true
    n=`expr $n + 1 2>/dev/null` || n=150
    [ "$n" -ge 150 ] 2>/dev/null && break
  done
  for p in `pidof titan2-side-watch 2>/dev/null`; do
    kill -9 "$p" 2>/dev/null || true
  done
}

# 2.34: kill direct children of $$ (side-watch shells, inject drain, nested sh).
# getevent-only teardown left pre-reload sh pipelines live → dual trees + load heat.
# 2.119: pgrep -P only — full /proc walk (~800+ cat stat) reheated under load≥8
# when claim/reload/teardown ran mid-heat (lab 2026-07-26 residual after 2.118).
_kill_direct_children() {
  me=$$
  for p in `pgrep -P "$me" 2>/dev/null`; do
    case "$p" in ''|*[!0-9]*) continue ;; esac
    kill -9 "$p" 2>/dev/null || true
  done
}

# 2.108: NEVER tr '\0' on /proc/cmdline — toybox tr spins forever (lab heat:
# load thrash, tr @100% under pad-agent). Match = grep -a -F only (usb_hid 0.16.15).
_cmdline_has() {
  _ch_pid="$1"
  _ch_pat="$2"
  [ -n "$_ch_pid" ] && [ -n "$_ch_pat" ] || return 1
  [ -r "/proc/$_ch_pid/cmdline" ] || return 1
  grep -a -F -q "$_ch_pat" "/proc/$_ch_pid/cmdline" 2>/dev/null
}

# 2.114–2.128 tip-land history: see OPTIMIZE + cube-load-land.sh (pgrep-only, no tr).
# --- Cube-load tip land (2.184): peeled to titan2-cube-load-land.sh ---
# Phase 5: tip land out of agent main body (rootless heat park still available).

_kick_cube_load_tip_land() {
  # missing peel — skip tip land (product system peels still run via hybrid)
  _peel_run titan2-cube-load-land.sh cube-load kick 2>/dev/null || true
}


# 2.56/2.108: true if pid is a pad-agent shell (comm=sh + cmdline match).
# Cheap — no ps -A (1.65 hang residual).
_is_pad_agent_pid() {
  _ip="$1"
  [ -n "$_ip" ] || return 1
  [ -d "/proc/$_ip" ] || return 1
  _ic=`cat "/proc/$_ip/comm" 2>/dev/null` || return 1
  [ "$_ic" = "sh" ] || return 1
  _cmdline_has "$_ip" "titan2-pad-agent" && return 0
  return 1
}

# 2.36/2.56: prune peer pad-agent roots (not us). Was ppid=1-only — dual
# Magisk+adbd roots never reparented to init while both live. Never kill our
# workers (parent=$$). Multi-pass caller reaps reparented grandchildren.
# 2.119: pgrep -f candidate list only — never walk all /proc (2.44 still did
# full /proc+comm under heat; lab load≈15 after 2.118 left prune sys-time heat).
# Never full `ps -A` (1.65 hang residual).
_prune_orphan_agent_roots() {
  me=$$
  n=0
  for p in `pgrep -f 'titan2-pad-agent' 2>/dev/null`; do
    case "$p" in ''|*[!0-9]*) continue ;; esac
    [ "$p" = "$me" ] && continue
    # Confirm pad-agent shell (pgrep can hit our own long cmdlines in rare cases)
    _is_pad_agent_pid "$p" || continue
    # Worker of any pad-agent tree → leave for that tree / reparent pass
    st=`cat "/proc/$p/stat" 2>/dev/null` || continue
    rest=${st##*) }
    set -- $rest
    ppid="$2"
    case "$ppid" in ''|*[!0-9]*) ppid=0 ;; esac
    if [ "$ppid" != "0" ] && [ "$ppid" != "1" ] && _is_pad_agent_pid "$ppid"; then
      # Keep our direct/indirect workers; peer workers die when peer root dies
      continue
    fi
    # Peer root (any parent) or reparented orphan (ppid=1) — not us
    kill -9 "$p" 2>/dev/null || true
    n=`expr $n + 1 2>/dev/null` || n=32
    [ "$n" -ge 32 ] 2>/dev/null && break
  done
  [ "$n" -gt 0 ] 2>/dev/null && log "pruned orphan pad-agent roots n=$n keep=$$"
  return 0
}

# Before re-exec: multi-pass drop getevent + child sh + peer roots (2.211 densify).
_teardown_for_reload() {
  _i=0
  while [ "$_i" -lt 2 ]; do
    _kill_orphan_input_watchers
    _kill_direct_children
    _prune_orphan_agent_roots
    _i=`expr $_i + 1 2>/dev/null` || _i=2
  done
  _prune_orphan_agent_roots
  _kill_orphan_input_watchers
}

# SIGTERM/HUP/EXIT (not SIGKILL): free lock AND drop getevent so death ≠ heat thrash.
_exit_cleanup() {
  cur=`_lock_pid`
  if [ "$cur" = "$$" ]; then
    _kill_orphan_input_watchers
    rm -rf "$AGENT_LOCKDIR" 2>/dev/null
  fi
}

# ---- Single instance: mkdir lock only (never mass-kill by name). ----
# Healthy holder status age <20s → exit 0; stale → steal.
mkdir -p "$T2" "$ST" 2>/dev/null || true

# 0 = dead/zombie/missing; 1 = live non-zombie
_pid_live() {
  p="$1"
  [ -n "$p" ] || return 1
  [ -d "/proc/$p" ] || return 1
  kill -0 "$p" 2>/dev/null || return 1
  # /proc/pid/stat: pid (comm) state ... — state after last ") "
  st=`cat "/proc/$p/stat" 2>/dev/null` || return 1
  state=${st##*) }
  state=${state%% *}
  [ "$state" = "Z" ] && return 1
  return 0
}

# Seconds since AGENT_STATUS mtime; huge if missing
_status_age() {
  mt=`stat -c %Y "$AGENT_STATUS" 2>/dev/null` || mt=0
  case "$mt" in ''|*[!0-9]*) mt=0;; esac
  n=`date +%s`
  if [ "$mt" -le 0 ] 2>/dev/null; then echo 9999; return; fi
  expr "$n" - "$mt" 2>/dev/null || echo 9999
}

_claim_agent_lock() {
  # Healthy holder (status age <20s) → leave quietly; stale → steal after short wait.
  tries=0
  while [ $tries -lt 40 ]; do
    tries=`expr $tries + 1 2>/dev/null` || tries=40
    if [ -d "$AGENT_LOCKDIR" ]; then
      op=`_lock_pid`
      if [ -n "$op" ] && [ "$op" != "$$" ] && _pid_live "$op"; then
        age=`_status_age`
        [ "$age" -lt 20 ] 2>/dev/null && return 1
        w=0
        while [ $w -lt 6 ]; do
          _pid_live "$op" || break
          age=`_status_age`
          [ "$age" -lt 20 ] 2>/dev/null && return 1
          sleep 1
          w=`expr $w + 1 2>/dev/null` || w=6
        done
        if _pid_live "$op"; then
          kill -9 "$op" 2>/dev/null || true
          _kill_orphan_input_watchers
          sleep 0.15 2>/dev/null || true
        fi
      fi
      rm -rf "$AGENT_LOCKDIR" 2>/dev/null || true
      continue
    fi
    if mkdir "$AGENT_LOCKDIR" 2>/dev/null; then
      echo $$ > "$AGENT_LOCKDIR/pid" 2>/dev/null
      echo $$ > "$ST/titan2_agent.pid" 2>/dev/null
      chmod 666 "$ST/titan2_agent.pid" 2>/dev/null || true
      return 0
    fi
    sleep 0.2 2>/dev/null || sleep 1
  done
  return 1
}
_claim_agent_lock || exit 0
# 2.108 FIRST: pure-exec staged tip BEFORE any /proc cmdline walk.
# System 2.107 used tr '\0' in prune → toybox spin 100% CPU / heat; never
# reached the later hot_reload call. Upgrade must run with zero tr.
if [ -z "$TITAN2_PURE_RELOAD" ]; then
  _early=`_staged_tip_ver` || _early=""
  case "$_early" in
    2.[0-9]*)
      _en=`_agent_ver_minor "$_early"`; _ln=`_agent_ver_minor "$AGENT_VER"`
      case "$_en" in ''|*[!0-9]*) _en=0 ;; esac
      case "$_ln" in ''|*[!0-9]*) _ln=0 ;; esac
      if [ "$_en" -gt "$_ln" ] 2>/dev/null; then
        log "early_hot_reload want=$_early live=$AGENT_VER"
        unset AGENT_VER 2>/dev/null || true
        export TITAN2_PURE_RELOAD=1 TITAN2_FAST_BOOT=1
        exec /system/bin/sh "$ST/titan2-pad-agent.sh"
      fi
      ;;
  esac
fi
# Free lock + kill getevent on clean exit/TERM (1.68). Reload path also calls _teardown.
trap '_exit_cleanup' EXIT INT TERM HUP
# After we own the lock: drop previous reload's getevent pile + dual roots.
if [ -n "$TITAN2_PURE_RELOAD" ]; then
  # 2.82/2.207: pure-exec — light prune only (one pass).
  unset TITAN2_PURE_RELOAD
  _kill_direct_children
  _kill_orphan_input_watchers
  log "pure_reload prune_light boot=$$"
else
  # Cold boot: dual-pass peer roots + getevent (2.211 densify).
  _i=0
  while [ "$_i" -lt 2 ]; do
    _kill_orphan_input_watchers; _kill_direct_children; _prune_orphan_agent_roots
    _i=`expr $_i + 1 2>/dev/null` || _i=2
  done
  log "orphan_getevent_cleared boot=$$"
fi
# Boot stamp: ignore reload storms for 45s after start (lifecycle thrash residual)
echo "`date +%s`" > "$ST/titan2_agent_boot_s" 2>/dev/null || true
chmod 666 "$ST/titan2_agent_boot_s" 2>/dev/null || true
# 2.114: rootless land staged heat-park tips (kernel-cube/sensor/usb_hid) once
# after claim — system pre-park residual under load≥8 without Magisk/flash.
# Background only — never block pure_reload / main loop (full /proc hang residual).
_kick_cube_load_tip_land

# Hot-stage without root: lab install pushes newer agent to /data/local/tmp.
# 1.65: ONLY re-exec when AGENT_VER default string changes — never on mtime/size.
# 2.70: NEVER downgrade — stale tmp 2.67 used to steal system 2.69 tip (caret dead).
# 2.72: parse bare pin and default-expand forms; never rm tip on garbage parse
#       (2.71 pin form self-deleted tip → caret/mode stuck on system 2.69).
_maybe_hot_reload_staged_agent() {
  staged=$ST/titan2-pad-agent.sh
  [ -x "$staged" ] || return 0
  staged_def=`_staged_tip_ver` || return 0
  [ -n "$staged_def" ] || return 0
  case "$staged_def" in
    2.[0-9]*) ;;
    *) log "hot_reload skip bad_ver staged=[$staged_def] live=$AGENT_VER"; return 0 ;;
  esac
  [ "$staged_def" = "$AGENT_VER" ] && return 0
  s_n=`_agent_ver_minor "$staged_def"`
  l_n=`_agent_ver_minor "$AGENT_VER"`
  case "$s_n" in ''|*[!0-9]*) s_n=0 ;; esac
  case "$l_n" in ''|*[!0-9]*) l_n=0 ;; esac
  if [ "$s_n" -le 0 ] 2>/dev/null; then
    log "hot_reload skip unparsed staged=$staged_def live=$AGENT_VER"
    return 0
  fi
  if [ "$s_n" -le "$l_n" ] 2>/dev/null; then
    log "hot_reload skip downgrade staged=$staged_def live=$AGENT_VER"
    [ "$s_n" -gt 0 ] && [ "$s_n" -lt "$l_n" ] 2>/dev/null && rm -f "$staged" 2>/dev/null || true
    return 0
  fi
  echo "want=$staged_def live=$AGENT_VER ts=`date +%s`" >"$ST/titan2_hot_reload_want" 2>/dev/null || true
  chmod 666 "$ST/titan2_hot_reload_want" 2>/dev/null || true
  log "hot_reload exec want=$staged_def live=$AGENT_VER"
  # 2.133: kill watchers/getevent before pure-exec (stale LED/write residual).
  _teardown_for_reload 2>/dev/null || true
  unset AGENT_VER 2>/dev/null || true
  # 2.83: DROP EXIT trap before exec (else getevent walk hangs status).
  trap - EXIT INT TERM HUP 2>/dev/null || true
  export TITAN2_PURE_RELOAD=1 TITAN2_FAST_BOOT=1
  exec /system/bin/sh "$staged" || {
    trap '_exit_cleanup' EXIT INT TERM HUP 2>/dev/null || true
    log "hot_reload exec_fail want=$staged_def err=$? — continue live"
    return 0
  }
}

_maybe_hot_reload_staged_agent

# True if we still own the singleton lock (exit main loop if not).
_still_lock_owner() {
  cur=`_lock_pid`
  [ "$cur" = "$$" ]
}

# --- Main-loop densify (2.201): shared reexec / lock / keys_pause / drain ---
_pure_reexec_self() {
  _why="${1:-reexec}"
  [ -n "$_why" ] && log "$_why" 2>/dev/null || true
  _teardown_for_reload 2>/dev/null || true
  unset AGENT_VER 2>/dev/null || true
  trap - EXIT INT TERM HUP 2>/dev/null || true
  export TITAN2_PURE_RELOAD=1 TITAN2_FAST_BOOT=1
  if [ -x /data/local/tmp/titan2-pad-agent.sh ]; then
    exec /system/bin/sh /data/local/tmp/titan2-pad-agent.sh
  fi
  exec /system/bin/sh "$0"
}

_heal_singleton_lock() {
  if _still_lock_owner; then
    return 0
  fi
  ap=`cat "$ST/titan2_agent.pid" 2>/dev/null | tr -d '\r\n '`
  if [ "$ap" = "$$" ]; then
    mkdir -p "$AGENT_LOCKDIR" 2>/dev/null || true
    echo $$ > "$AGENT_LOCKDIR/pid" 2>/dev/null || true
    log "pad-agent: healed lockdir pid=$$"
    return 0
  fi
  log "pad-agent: lost lock pid=$$ — exit"
  exit 0
}

_clear_sticky_keys_pause() {
  hid_session_on && return 0
  for _kp in titan2_host_layout_keys_pause titan2_usb_hid_keys_pause \
      titan2_specials_inject_pause; do
    for _d in $T2 $ST; do
      f="$_d/$_kp"
      [ -f "$f" ] || continue
      v=`_read_line_file "$f"`
      case "$v" in 1|true|on|ON)
        echo 0 > "$f" 2>/dev/null || true
        chmod 666 "$f" 2>/dev/null || true
        ;;
      esac
    done
  done
  settings put global titan2_host_layout_keys_pause 0 2>/dev/null || true
  settings put global titan2_usb_hid_keys_pause 0 2>/dev/null || true
  settings put global titan2_specials_inject_pause 0 2>/dev/null || true
  return 0
}

bump() {
  _t=`date +%s`
  echo "$_t" > "$ACTIVITY" 2>/dev/null; chmod 666 "$ACTIVITY" 2>/dev/null
  echo "$_t" > "$T2/titan2_key_activity" 2>/dev/null; chmod 666 "$T2/titan2_key_activity" 2>/dev/null
}

# ---- Typing plane (2.196/2.212): typing-watch owns park/cool SoT ----
# Agent: ensure watch live + heat-sleep short while typing-hot (no dual cool-down).

_typing_plane_hot() {
  if [ -f "$PAD_STATUS" ] && grep -q 'typing_lock=1' "$PAD_STATUS" 2>/dev/null; then
    return 0
  fi
  for _f in "$T2/titan2_pad_cursor_pause" "$ST/titan2_pad_cursor_pause"; do
    [ -f "$_f" ] || continue
    _v=`cat "$_f" 2>/dev/null | tr -d '\r\n \t'`
    case "$_v" in 1|true|on|yes) return 0 ;; esac
  done
  return 1
}

# typing-watch live? (2.167 any-parent; 2.212 densify). Sticky typing_lock needs live watch.
_typing_watch_live() {
  _wp=`cat "$ST/titan2_typing_watch.pid" 2>/dev/null | tr -d '\r\n '`
  if [ -n "$_wp" ] && [ -d "/proc/$_wp" ] && _cmdline_has "$_wp" "titan2-typing-watch"; then
    return 0
  fi
  _st=`cat "$ST/titan2_typing_watch_status" 2>/dev/null | tr -d '\r'`
  _wp=`echo "$_st" | sed -n 's/.*pid=\([0-9][0-9]*\).*/\1/p' | head -1`
  if [ -n "$_wp" ] && [ -d "/proc/$_wp" ]; then
    echo "$_wp" >"$ST/titan2_typing_watch.pid" 2>/dev/null || true
    return 0
  fi
  for _wp in `pgrep -f 'titan2-typing-watch' 2>/dev/null`; do
    case "$_wp" in ''|*[!0-9]*) continue ;; esac
    _is_pad_agent_pid "$_wp" 2>/dev/null && continue
    _cmdline_has "$_wp" "titan2-typing-watch" || continue
    [ "`cat /proc/$_wp/comm 2>/dev/null`" = "sh" ] || continue
    echo "$_wp" >"$ST/titan2_typing_watch.pid" 2>/dev/null || true
    return 0
  done
  return 1
}

CTRL_PATHS() {
  name="$1"
  # 2.78: OS plane + tmp ONLY. Never /data/user/0 or /data/data — those FUSE
  # paths hung boot (status stuck notif_engine) and made every mtime_max tick
  # multi-second. Controls/HID must mirror to T2+ST; settings global is fallback.
  echo "$T2/$name"
  echo "$ST/$name"
}


# Newest mtime wins (app/sdcard beat stale adb tmp).
# Clear tokens (0/none/-/clear) beat older non-empty values so named presets can
# drop a stale titan2_char_mod_scan without leaving Fn as specials forever.
# Note: "off" is NOT a clear token — titan2_pad_mode uses off|trackpad|mouse as
# real states (HID + Controls UI must stay in sync with applied touchpadd).
# Read one line from a real /data path — pure shell, no cat|head|tr forks.
_read_line_file() {
  f="$1"
  [ -f "$f" ] || { echo ""; return 1; }
  v=""
  IFS= read -r v < "$f" || true
  # strip CR if present (busybox/mksh portable)
  case "$v" in
    *$'\r') v=${v%$'\r'} ;;
  esac
  echo "$v"
  return 0
}

read_first() {
  name="$1"
  best_mt=0
  best_v=""
  # Paths listed T2 then ST. Prefer strictly newer mtime; on equal second
  # keep the first hit (T2) so a stale tmp mirror never beats OS plane.
  for f in `CTRL_PATHS "$name"`; do
    [ -f "$f" ] || continue
    v=`_read_line_file "$f"`
    mt=`stat -c %Y "$f" 2>/dev/null` || mt=0
    case "$mt" in ''|*[!0-9]*) mt=0;; esac
    case "$v" in
      ''|0|null|NULL|-|clear|CLEAR)
        # >= so later paths (app CE) beat same-second OS seed shells
        # Note: "none" is a valid plane value (B1 short = no action; 11.32).
        if [ "$mt" -ge "$best_mt" ] 2>/dev/null; then
          best_mt=$mt
          best_v=""
        fi
        continue
        ;;
      none|NONE)
        # Preserve literal none for side_short / BT WA (do not treat as clear)
        if [ "$mt" -ge "$best_mt" ] 2>/dev/null; then
          best_mt=$mt
          best_v=none
        fi
        continue
        ;;
    esac
    if [ "$mt" -ge "$best_mt" ] 2>/dev/null; then
      best_mt=$mt
      best_v=$v
    fi
  done
  # Controls 11.32+ mirrors KM/pad plane to Settings.Global — use when no file win
  if [ -z "$best_v" ] || [ "$best_mt" -eq 0 ] 2>/dev/null; then
    g=`settings get global "$name" 2>/dev/null`
    case "$g" in
      *$'
') g=${g%$'
'} ;;
    esac
    case "$g" in
      ''|null|NULL) ;;
      *)
        # If we only had empty clears, Global fills plane (unrooted publish)
        if [ -z "$best_v" ]; then
          best_v=$g
        fi
        ;;
    esac
  fi
  echo "$best_v"
}

mtime_max() {
  name="$1"; best=0
  for f in `CTRL_PATHS "$name"`; do
    [ -f "$f" ] || continue
    mt=`stat -c %Y "$f" 2>/dev/null` || mt=0
    case "$mt" in ''|*[!0-9]*) mt=0;; esac
    [ "$mt" -gt "$best" ] 2>/dev/null && best=$mt
  done
  echo $best
}

# off | trackpad | mouse (2.212 densify)
read_pad_mode() {
  case "`read_first titan2_pad_mode`" in
    off|OFF|0) echo off; return ;;
    trackpad|TRACKPAD|pad|PAD|native|NATIVE) echo trackpad; return ;;
    mouse|MOUSE|module|MODULE|on|ON|1|global|GLOBAL) echo mouse; return ;;
  esac
  case "`read_first titan2_touchpad_enabled`" in 1|true|on|ON) echo mouse; return ;; esac
  echo off
}
read_pad_top_row_cursor() {
  case "`read_first titan2_pad_top_row_cursor`" in 0|false|off|OFF|no|NO) echo 0;; *) echo 1;; esac
}
read_pad_follow_orient() {
  case "`read_first titan2_pad_follow_orient`" in 1|true|on|ON|yes|YES) echo 1;; *) echo 0;; esac
}
# Rotation 0..3 from plane only (never dumpsys — hung pad ON under heat).
read_display_rotation() {
  r=`read_first titan2_pad_rotation`
  case "$r" in 0|1|2|3) echo "$r" ;; *) echo 0 ;; esac
}
publish_pad_rotation() {
  [ "`read_pad_follow_orient`" = "1" ] || return 0
  r=`read_display_rotation`
  case "$r" in 0|1|2|3) ;; *) r=0 ;; esac
  write_if_changed "$T2/titan2_pad_rotation" "$r"
  write_if_changed "$ST/titan2_pad_rotation" "$r"
}

# LED plane: 0 valid — never read_first (0=clear → sticky glow residual).
read_led_plane() {
  name="$1"; best_mt=-1; best_v=""; found=0
  for f in `CTRL_PATHS "$name"`; do
    [ -f "$f" ] || continue
    v=`_read_line_file "$f" | tr -d '\r\n \t'`
    case "$v" in ''|null|NULL|-|clear|CLEAR|*[!0-9]*) continue ;; esac
    mt=`stat -c %Y "$f" 2>/dev/null` || mt=0
    case "$mt" in ''|*[!0-9]*) mt=0;; esac
    if [ "$found" = "0" ] || [ "$mt" -ge "$best_mt" ] 2>/dev/null; then
      best_mt=$mt; best_v=$v; found=1
    fi
  done
  [ "$found" = "1" ] && echo "$best_v" || echo ""
}

# Write only when content differs — never bump mtime for identical values
# (mtime thrash re-triggers pad apply and recenters the mouse every ~1–3s).
write_if_changed() {
  path="$1"; val="$2"
  [ -n "$path" ] || return 1
  cur=`cat "$path" 2>/dev/null | tr -d '\r\n '`
  [ "$cur" = "$val" ] && return 0
  echo "$val" > "$path" 2>/dev/null || return 1
  chmod 666 "$path" 2>/dev/null || true
  return 0
}

# Root-powered persistence for *all* controls. Ensures settings survive even if
# app-private files or OS plane are cleared on some boots (storage timing etc).
persist_ctrl() {
  name="$1"; val="$2"
  [ -n "$val" ] || return
  cur=`cat $T2/$name 2>/dev/null | tr -d '\r\n '`
  if [ "$cur" = "$val" ]; then
    last=`cat $T2/${name}_last 2>/dev/null | tr -d '\r\n '`
    [ "$last" = "$val" ] && return 0
  else
    echo "$val" > $T2/$name 2>/dev/null || true
    chmod 666 $T2/$name 2>/dev/null || true
  fi
  echo "$val" > $T2/${name}_last 2>/dev/null || true
  chmod 666 $T2/${name}_last 2>/dev/null || true
}

screen_is_on() {
  # prop: 1=OFF 2=ON 3=DOZE 4=DOZE_SUSPEND 6=ON_SUSPEND. Never dumpsys.
  s=`getprop debug.tracing.screen_state 2>/dev/null | tr -d '
 	'`
  case "$s" in 2|6) return 0 ;; 1|3|4) return 1 ;; esac
  for bl in /sys/class/leds/lcd-backlight/brightness \
            /sys/devices/platform/leds-mtk/leds/lcd-backlight/brightness \
            /sys/devices/platform/mtk-leds/leds/lcd-backlight/brightness \
            /sys/class/backlight/panel0-backlight/brightness; do
    [ -e "$bl" ] || continue
    bv=`cat "$bl" 2>/dev/null | tr -d '
 	'`
    case "$bv" in ''|*[!0-9]*) continue ;; 0) return 1 ;; *) return 0 ;; esac
  done
  return 0
}

# Rear mode face|apps|cube|off (2.212 densify). Inhibit: face/off=1; apps/cube digitizer on.
read_sub_mode() {
  m=`read_first titan2_sub_mode | tr 'A-Z' 'a-z' | tr -d '\r\n '`
  case "$m" in
    apps|app|launcher|touch|interactive) echo apps; return ;;
    cube|lattice|brain|neural) echo cube; return ;;
    face|clock|stock|custom|aod) echo face; return ;;
    off|0|none) echo off; return ;;
  esac
  case "`read_first titan2_subdisplay_on`" in 1|true|on|ON) echo face ;; *) echo off ;; esac
}
read_subtouch_inhibit() {
  case "`read_sub_mode`" in
    apps|cube)
      sa=`cat "$ST/titan2_subtouch_assoc_state" 2>/dev/null | tr -d '\r\n '`
      case "$sa" in
        "assoc:"*">local:"*|"assoc:"*">unique:"*) echo 0 ;;
        *) echo 1 ;;
      esac
      ;;
    *) echo 1 ;;
  esac
}

# Clear one-shot control on T2+ST + Settings.Global mirror.
clear_ctrl_name() {
  name="$1"
  for f in `CTRL_PATHS "$name"`; do
    [ -f "$f" ] || continue
    : > "$f" 2>/dev/null || true
  done
  settings put global "$name" "" 2>/dev/null || true
  settings delete global "$name" 2>/dev/null || true
}

# --- Firewall one-shots (2.214): Controls writes titan2_fw_action after Atlas bio ---
# Product path: agent root apply (Controls does not need Magisk Superuser).
# Payload: "enable <epoch>" | "deny-uid <uid> <epoch>" — trailing 10+ digit nonce stripped.
apply_fw_action() {
  act=`read_first titan2_fw_action`
  if [ -z "$act" ]; then
    g=`settings get global titan2_fw_action 2>/dev/null | tr -d '\r\n'`
    case "$g" in ""|null|NULL) ;; *) act=$g ;; esac
  fi
  if [ -z "$act" ] && [ -s "$ST/titan2_fw_action" ]; then
    act=`_read_line_file "$ST/titan2_fw_action"`
  fi
  [ -n "$act" ] || return 0
  # Clear ALL sources first so we never re-fire next tick.
  clear_ctrl_name titan2_fw_action
  settings put global titan2_fw_action "" 2>/dev/null || true
  rm -f "$ST/titan2_fw_action" /data/local/tmp/titan2_fw_action /data/misc/titan2/titan2_fw_action 2>/dev/null || true
  # Strip trailing epoch nonce (10+ digits) that Controls appends for mtime uniqueness.
  act=`echo "$act" | sed 's/[[:space:]][0-9]\{10,\}$//'`
  act=`echo "$act" | tr -d '\r' | sed 's/^[[:space:]]*//;s/[[:space:]]*$//'`
  [ -n "$act" ] || return 0
  act0=`echo "$act" | awk '{print $1}'`
  rest=`echo "$act" | cut -d' ' -f2- 2>/dev/null`
  case "$rest" in "$act"|"$act0") rest= ;; esac
  case "$act0" in
    enable|disable|apply|reset|status|list|observe) ;;
    deny-uid|allow-uid|deny-bin|allow-bin|deny-svc|allow-svc)
      # rest = first token only (uid|package|basename|svc name)
      rest=`echo "$rest" | awk '{print $1}'`
      [ -n "$rest" ] || { log "fw_action $act0 missing arg"; return 0; }
      ;;
    fw|firewall)
      act0=`echo "$act" | awk '{print $2}'`
      rest=`echo "$act" | cut -d' ' -f3- 2>/dev/null | awk '{print $1}'`
      ;;
    *)
      log "fw_action unknown: $act"
      return 0
      ;;
  esac
  CLI=""
  # Prefer system inject (product), then tip, then Magisk module bin
  for c in /system/bin/titan2-fw.sh /system/bin/titan2-fw \
      /data/local/tmp/titan2-fw.sh \
      /data/adb/modules/titan2_fw/system/bin/titan2-fw.sh \
      /data/adb/modules/titan2_fw/system/bin/titan2-fw; do
    [ -f "$c" ] && [ -r "$c" ] && CLI=$c && break
  done
  if [ -z "$CLI" ]; then
    log "fw_action: titan2-fw missing"
    echo "error: titan2-fw missing" >"$ST/titan2_fw.status" 2>/dev/null || true
    chmod 666 "$ST/titan2_fw.status" 2>/dev/null || true
    return 0
  fi
  log "fw_action=$act0 $rest via $CLI"
  case "$act0" in
    deny-uid|allow-uid|deny-bin|allow-bin|deny-svc|allow-svc)
      /system/bin/sh "$CLI" "$act0" "$rest" >>"$ST/titan2_fw.log" 2>&1 || true
      ;;
    *)
      /system/bin/sh "$CLI" "$act0" >>"$ST/titan2_fw.log" 2>&1 || true
      ;;
  esac
  # Machine-readable multi-line status for Controls (world-readable)
  /system/bin/sh "$CLI" status >"$ST/titan2_fw.status" 2>/dev/null || true
  chmod 666 "$ST/titan2_fw.status" "$ST/titan2_fw.log" 2>/dev/null || true
  # Mirror desire world mode (Controls paints without su)
  chmod 666 /data/misc/titan2/fw.enabled /data/misc/titan2/fw.deny \
    /data/misc/titan2/fw.deny.bins /data/misc/titan2/fw.deny.svcs 2>/dev/null || true
}

# --- Dev one-shots (2.173): bulk peeled to titan2-dev-action.sh ---
# Keep heal_b1 (ensure_b1_*) + reload_agent (exec) in-process.

# Debounce thrash: arm then off within same second (stale Global + file race).
_LAST_DEV_ACT=""
_LAST_DEV_TS=0

apply_dev_action() {
  act=`read_first titan2_dev_action`
  if [ -z "$act" ]; then
    g=`settings get global titan2_dev_action 2>/dev/null | tr -d '\r\n '`
    case "$g" in ""|null|NULL) ;; *) act=$g ;; esac
  fi
  if [ -z "$act" ] && [ -s "$ST/titan2_dev_action" ]; then
    act=`_read_line_file "$ST/titan2_dev_action"`
  fi
  [ -n "$act" ] || return 0
  # Clear ALL sources first so we never re-fire the opposite action next tick.
  clear_ctrl_name titan2_dev_action
  settings put global titan2_dev_action "" 2>/dev/null || true
  rm -f "$ST/titan2_dev_action" /data/local/tmp/titan2_dev_action /data/misc/titan2/titan2_dev_action 2>/dev/null || true
  log "dev_action=$act"
  act0=$(echo "$act" | awk '{print $1}')
  case "$act0" in
    dev-action|dev_action)
      act0=$(echo "$act" | awk '{print $2}')
      ;;
  esac
  [ -n "$act0" ] || return 0
  now_s=`date +%s`
  case "$_LAST_DEV_TS" in ''|*[!0-9]*) _LAST_DEV_TS=0 ;; esac
  if [ "$act0" = "$_LAST_DEV_ACT" ] && [ $((now_s - _LAST_DEV_TS)) -lt 4 ] 2>/dev/null; then
    log "dev_action debounce skip $act0"
    return 0
  fi
  # Never suppress disable — human OFF must always win over a recent arm.
  # (Old thrash-suppress blocked OFF right after ON and left TCP stuck.)
  _LAST_DEV_ACT=$act0
  _LAST_DEV_TS=$now_s
  case "$act0" in
    heal_b1|b1_heal)
      ensure_b1_mtk_kpd; ensure_b1_pmic_keys; ensure_b1_side_nodes
      log "heal_b1 done st=$(cat $ST/titan2_b1_kl_status 2>/dev/null | tr '\n' ' ')"
      return 0
      ;;
    reload_agent|agent_reload)
      boot_s=`cat "$ST/titan2_agent_boot_s" 2>/dev/null | tr -d '\r\n '`
      now_s=`date +%s`
      case "$boot_s" in ''|*[!0-9]*) boot_s=0 ;; esac
      age=`expr $now_s - $boot_s 2>/dev/null` || age=999
      if [ "$age" -lt 45 ] 2>/dev/null; then
        log "reload_agent ignored age=${age}s (cooldown)"
        return 0
      fi
      _pure_reexec_self "reload_agent"
      return 0
      ;;
  esac
  # Full line: "arm_wireless_adb_trusted 5555" / "pair_remote_adb_trusted 5037"
  # shell-split is intentional (port is digits only).
  # shellcheck disable=SC2086
  _peel_run titan2-dev-action.sh dev-action $act
  return $?
}

# --- IMS (2.166): peeled to titan2-ims-heal.sh (OPTIMIZE Phase 3) ---
# Helpers + apply_ims_action live in patches/bin/titan2-ims-heal.sh.
# Keep thin dispatch here so main loop never grows the IMS tower again.

# IMS (2.166/2.172 peels) + subdisplay (2.175) + DT2W (2.181) + LED apply (2.169)
_run_ims_heal() { _peel_run titan2-ims-heal.sh ims-heal "$@"; }
apply_ims_action() { _run_ims_heal action; }
ims_apply_sub_defaults() { _run_ims_heal sub_defaults; }
ims_bind_all_slots() { _run_ims_heal bind_all; }
LAST_SUBDISP=""; LAST_SUBDISP_MT=0; SUBDISP_REASSERT_FAILS=0
_run_subdisplay() { _peel_run titan2-subdisplay.sh subdisplay "$@"; }
_read_subdisplay_on() {
  _s=`_sysbin titan2-subdisplay.sh` || { echo 0; return 0; }
  /system/bin/sh "$_s" on
}
_read_subdisplay_bri() {
  _s=`_sysbin titan2-subdisplay.sh` || { echo 1.00; return 0; }
  /system/bin/sh "$_s" bri
}
apply_subdisplay() {
  _run_subdisplay apply || return 1
  _run_pad_idc digitizer_post 2>/dev/null || true
}
apply_dt2w() { _peel_run titan2-dt2w.sh dt2w apply; }
apply_ims_props() {
  _run_ims_heal props || {
    log "ims-heal props missing — IMS/BT plane offline"
    return 1
  }
  apply_dev_action
  apply_subdisplay
}
# HID session: only always-unlocked paths (T2/tmp/adb). Never FUSE CE.
hid_session_on() {
  for f in $T2/titan2_usb_hid_session /data/local/tmp/titan2_usb_hid_session \
      /data/adb/titan2/titan2_usb_hid_session; do
    [ -f "$f" ] || continue
    case "`_read_line_file "$f"`" in 1|true|on|ON) return 0;; esac
  done
  return 1
}
apply_led() { _peel_run titan2-keyled-write.sh keyled-write apply; }

# Cool/heat LED edge (2.197/2.207 densify): screen + brightness/timeout → apply_led.
_led_edge_tick() {
  # Cool: every call site tick. Heat: only every 4th even tick (caller gates %2).
  _led_tick=0
  [ "$HEAT_PARK" != "1" ] && _led_tick=1
  [ "$HEAT_PARK" = "1" ] && [ $((loop_n % 4)) -eq 0 ] && _led_tick=1
  _scrp=`getprop debug.tracing.screen_state 2>/dev/null | tr -d '\r\n \t'`
  case "$_scrp" in
    2|6) [ "$LAST_SCREEN" = "0" ] && { bump; _led_tick=1; }; LAST_SCREEN=1 ;;
    1|3|4) LAST_SCREEN=0; _led_tick=1 ;;
    *)
      if screen_is_on; then
        [ "$LAST_SCREEN" = "0" ] && { bump; _led_tick=1; }
        LAST_SCREEN=1
      else
        LAST_SCREEN=0; _led_tick=1
      fi
      ;;
  esac
  CUR_BRIGHT=`read_led_plane titan2_keyled_brightness`
  if [ "$CUR_BRIGHT" != "$LAST_BRIGHT" ]; then LAST_BRIGHT=$CUR_BRIGHT; bump; _led_tick=1; fi
  CUR_TO=`read_led_plane titan2_keyled_timeout`
  if [ "$CUR_TO" != "$LAST_TO" ]; then LAST_TO=$CUR_TO; bump; _led_tick=1; fi
  [ "$_led_tick" = "1" ] && apply_led
  return 0
}

_keyled_plane_persist() { _peel_run titan2-keyled-write.sh keyled-write plane "${1:-0}"; }


set_pad_inhibited() {
  # 2.186: peeled to titan2-pad-idc.sh inhibit (sysfs only + typing force kill).
  # Clear local TP cache when force-kill path may have stopped touchpadd.
  _run_pad_idc inhibit "${1:-1}" "${2:-}" || return $?
  case "${2:-}" in
    force|1|true|yes|typing)
      if [ "${1:-}" = "1" ]; then
        TP_PID_CACHE=""
      fi
      ;;
  esac
  return 0
}


# Cached pid checks — bare pidof every 200ms was a fork storm (loop freezes,
# Alt↔Fn misses). Re-probe only when cache empty or process gone.
TP_PID_CACHE=""
tp_pid() {
  if [ -n "$TP_PID_CACHE" ] && [ -d "/proc/$TP_PID_CACHE" ]; then
    echo "$TP_PID_CACHE"
    return 0
  fi
  TP_PID_CACHE=`pidof titan2-touchpadd 2>/dev/null`
  TP_PID_CACHE=`echo $TP_PID_CACHE | awk '{print $1}'`
  echo "$TP_PID_CACHE"
}
tp_up() {
  if [ -n "$TP_PID_CACHE" ] && [ -d "/proc/$TP_PID_CACHE" ]; then
    return 0
  fi
  tp_pid >/dev/null
  [ -n "$TP_PID_CACHE" ]
}

# --- Pad IDC (2.182 peel) / keylayout (2.178) / B1 (2.177) ---
_run_pad_idc() { _peel_run titan2-pad-idc.sh pad-idc "$@"; }
_reload_last_idc_kind() {
  [ -f "$ST/titan2_idc_kind" ] && LAST_IDC_KIND=`cat "$ST/titan2_idc_kind" 2>/dev/null | tr -d '\r\n '`
}
_run_keylayout() {
  _s=`_sysbin titan2-keylayout.sh` || {
    log "keylayout missing — install titan2-keylayout.sh"
    return 1
  }
  FORCE_TITANKEY_UEVENT="${FORCE_TITANKEY_UEVENT:-0}" /system/bin/sh "$_s" "$@"
  _rc=$?
  FORCE_TITANKEY_UEVENT=0
  return $_rc
}
# Specials readers (2.206/2.212 densify)
read_fn_mode() {
  case "`read_first titan2_fn_mode`" in stock|STOCK|normal|default_stock) echo stock ;; *) echo ctrl ;; esac
}
read_char_mod() {
  case "`read_first titan2_char_mod`" in
    alt|ALT|stock) echo alt ;; fn|FN|function) echo fn ;;
    custom|CUSTOM|scan|other) echo custom ;; *) echo sym ;;
  esac
}
read_char_mod_scan_raw() {
  v=`read_first titan2_char_mod_scan`
  case "$v" in ''|0|none|off|null) echo "" ;; *)
    echo "$v" | grep -Eq '^[0-9]+$' && echo "$v" || echo "" ;;
  esac
}
resolve_specials_scan() {
  case "`read_char_mod`" in
    fn) echo 251 ;; alt) echo 100 ;;
    custom) raw=`read_char_mod_scan_raw`; [ -n "$raw" ] && echo "$raw" || echo 253 ;;
    *) echo 253 ;;
  esac
}
_is_fn_sc() { [ "$1" = "183" ] || [ "$1" = "251" ]; }
_run_b1_kl() { _peel_run titan2-b1-kl.sh b1-kl "$@"; }
ensure_b1_mtk_kpd() { _run_b1_kl mtk; }
ensure_b1_pmic_keys() { _run_b1_kl pmic; }
ensure_b1_side_nodes() { _run_b1_kl sides; }

# host_layout mirror (stable mtime; ghost specials residual).
_mirror_host_layout_stable() {
  v="$1"
  for dest in "$ST/titan2_host_layout" "$T2/titan2_host_layout"; do
    cur=`_read_line_file "$dest" 2>/dev/null | tr -d '\r\n ' | tr 'A-Z' 'a-z'`
    [ "$cur" = "$v" ] && continue
    printf '%s\n' "$v" > "$dest" 2>/dev/null || true
    chmod 666 "$dest" 2>/dev/null || true
  done
}

# --- Plane heals (2.183) + cool-park (2.185/2.188) peels ---
_run_plane_heal() { _peel_run titan2-plane-heal.sh plane-heal "$@"; }
_heal_ghost_host_layout_phone() { _run_plane_heal ghost_host; }
_ensure_long_press_regl() { _run_plane_heal long_press; }
_ensure_latinime_product_ime() { :; }
_heal_sensor_qs_tiles() { _run_plane_heal sensor_qs; }
_allow_dim_belt() { _peel_run titan2-cool-park.sh cool-park allow_dim; }
_put_wallpaper_dim_settings_only() { _peel_run titan2-cool-park.sh cool-park dim_settings; }
_put_wallpaper_dim_mild() { _peel_run titan2-cool-park.sh cool-park dim_mild; }
_cool_idle_park_plane() { _peel_run titan2-cool-park.sh cool-park apply; }

# Heat gate (main-loop hot path)
HEAT_LOAD_GE=${HEAT_LOAD_GE:-8}
# CubalC free-flow: 2s heat park made pad QS lag. Cap deep-idle; human wake is 20ms.
HEAT_IDLE_US=${HEAT_IDLE_US:-50000}
HEAT_IDLE_HUMAN_US=${HEAT_IDLE_HUMAN_US:-15000}
_cube_heat_park() {
  set -- $(cat /proc/loadavg 2>/dev/null)
  _load1=${1%%.*}
  case "$_load1" in ''|*[!0-9]*) _load1=0 ;; esac
  [ "$_load1" -ge "${HEAT_LOAD_GE:-8}" ] 2>/dev/null
}
_read_pad_mode_files() {
  v=`_read_line_file "$ST/titan2_pad_mode" 2>/dev/null` || v=""
  [ -n "$v" ] || v=`_read_line_file "$T2/titan2_pad_mode" 2>/dev/null` || v=""
  case "$v" in
    mouse|MOUSE|1|module|MODULE|on|ON|global|GLOBAL) echo mouse; return ;;
    trackpad|TRACKPAD|pad|PAD|native|NATIVE) echo trackpad; return ;;
  esac
  echo off
}



read_host_layout() {
  _heal_ghost_host_layout_phone
  # 2.78: no FUSE/app-private CE path (hang residual). ST+T2 + global only.
  best_mt=0
  best_v=""
  for f in "$T2/titan2_host_layout" "$ST/titan2_host_layout"; do
    [ -f "$f" ] || continue
    v=`_read_line_file "$f" | tr -d '\r\n ' | tr 'A-Z' 'a-z'`
    mt=`stat -c %Y "$f" 2>/dev/null` || mt=0
    case "$mt" in ''|*[!0-9]*) mt=0;; esac
    case "$v" in
      specials|arrows|off)
        if [ "$mt" -ge "$best_mt" ] 2>/dev/null; then
          best_mt=$mt
          best_v=$v
        fi
        ;;
    esac
  done
  case "$best_v" in
    specials|arrows)
      _mirror_host_layout_stable "$best_v"
      echo "$best_v"
      ;;
    *)
      _mirror_host_layout_stable off
      echo off
      ;;
  esac
}

# Apply specials layout (2.180): peeled into titan2-keylayout.sh fn.
# Main loop still dirty-detects char_mod/fn_mode; this only binds KL.
# Reload LAST_* from peel state file for status/heal (process-local mirror).
apply_fn() {
  _run_keylayout fn || return $?
  if [ -f "$ST/titan2_fn_apply_last" ]; then
    # shellcheck disable=SC1090
    . "$ST/titan2_fn_apply_last" 2>/dev/null || true
  fi
  return 0
}


# --- Specials belt densify (2.194): one dirty/apply path for heat + cool main loop ---
# Stamp mtimes first; never block main on apply_fn (set_keylayout/TitanKey hang residual).
_specials_dirty_apply() {
  cm_mt=`mtime_max titan2_char_mod`
  sc_mt=`mtime_max titan2_char_mod_scan`
  fn_mt=`mtime_max titan2_fn_mode`
  hl_mt=`mtime_max titan2_host_layout`
  sm_mt=`mtime_max titan2_specials_method`
  fn_dirty=0
  if [ "$cm_mt" != "$LAST_CM_MT" ] || [ "$sc_mt" != "$LAST_SC_MT" ]       || [ "$fn_mt" != "$LAST_FN_MT" ] || [ "$hl_mt" != "$LAST_HL_MT" ]       || [ "$sm_mt" != "$LAST_SM_MT" ] || [ -z "$LAST_HOST_LAYOUT" ]; then
    fn_dirty=1
  fi
  [ "$fn_dirty" = "1" ] || return 1
  want_sp=`resolve_specials_scan`
  want_fn=`read_fn_mode`
  want_hl=`read_host_layout`
  case "$want_hl" in specials|arrows) ;; *) want_hl=off ;; esac
  _is_fn_sc "$want_sp" && want_fn=stock
  LAST_CM_MT=$cm_mt; LAST_SC_MT=$sc_mt; LAST_FN_MT=$fn_mt
  LAST_SM_MT=$sm_mt; LAST_HL_MT=$hl_mt
  if ! _pad_bg_busy; then
    (
      apply_fn
      log "ok i=${loop_n:-0} pad=$LAST_PAD fn=$LAST_FN char=$LAST_CHAR_MOD sp=$LAST_CHAR_SCAN hlay=$LAST_HOST_LAYOUT"
    ) &
  fi
  return 0
}

# Rare live_kl heal (2.194): body in titan2-keylayout.sh heal (cool path only).
# Returns 0 if heal kicked apply_fn (caller may continue micro-sleep).
_specials_live_kl_heal() {
  want_sp=${LAST_CHAR_SCAN:-}
  want_fn=${LAST_FN:-stock}
  [ -n "$want_sp" ] || want_sp=`resolve_specials_scan`
  _out=`_run_keylayout heal "$want_sp" "$want_fn" 2>/dev/null` || return 1
  # heal exits 0 when apply needed
  case "$_out" in *force_uevent=1*) FORCE_TITANKEY_UEVENT=1 ;; esac
  LAST_CM_MT=`mtime_max titan2_char_mod`
  LAST_SC_MT=`mtime_max titan2_char_mod_scan`
  LAST_FN_MT=`mtime_max titan2_fn_mode`
  LAST_HL_MT=`mtime_max titan2_host_layout`
  LAST_SM_MT=`mtime_max titan2_specials_method`
  ! _pad_bg_busy && ( apply_fn ) &
  return 0
}

# --- Keycode inject drain (2.179): peeled to titan2-keycode-inject.sh ---
# 2.223 Cube one-energy: pad=off + HID=0 + empty queue → park (no 20ms poll).
_cube_want_inject() {
  hid_session_on && return 0
  case "`_read_pad_mode_files`" in mouse|trackpad) return 0 ;; esac
  [ -s "$ST/titan2_keycode_inject" ] && return 0
  [ -s "$T2/titan2_keycode_inject" ] && return 0
  [ -s "$ST/titan2_mouse_btn_q" ] && return 0
  [ -s "$T2/titan2_mouse_btn_q" ] && return 0
  return 1
}

_park_keycode_drain() {
  dp=`cat "$ST/titan2_keycode_drain.pid" 2>/dev/null | tr -d '\r\n '`
  if [ -n "$dp" ] && kill -0 "$dp" 2>/dev/null; then
    kill "$dp" 2>/dev/null || true
    log "pad-agent: park keycode inject (pad off hid 0)"
  fi
  for p in `pgrep -f titan2-keycode-inject 2>/dev/null`; do
    case "$p" in ''|*[!0-9]*) continue ;; esac
    kill "$p" 2>/dev/null || true
  done
  return 0
}

_spawn_keycode_inject_drain() {
  _cube_want_inject || { _park_keycode_drain; return 0; }
  _ensure_peel_daemon titan2-keycode-inject.sh titan2-keycode-inject titan2_keycode_drain.pid titan2_keycode_inject.log run
}

# Keep single keycode inject drain alive (no multi-spawn thrash). 2.223 park idle.
_ensure_keycode_drain_alive() {
  if ! _cube_want_inject; then
    _park_keycode_drain
    return 0
  fi
  dp=`cat "$ST/titan2_keycode_drain.pid" 2>/dev/null | tr -d '\r\n '`
  if [ -z "$dp" ] || ! kill -0 "$dp" 2>/dev/null; then
    log "pad-agent: start keycode inject drain (single)"
    _spawn_keycode_inject_drain
  fi
  return 0
}

_ensure_keycode_drain_alive

# --- Pad apply stack (2.187): peeled to titan2-pad-apply.sh ---
# Covers: orient-rel, mouse/caret/trackpad/stop, apply_pad.

_run_pad_apply() {
  _s=`_sysbin titan2-pad-apply.sh` || {
    log "pad-apply missing — install titan2-pad-apply.sh"
    return 1
  }
  /system/bin/sh "$_s" "$@"
  _rc=$?
  # Reload LAST_* mirrors for main loop
  if [ -f "$ST/titan2_pad_apply_last" ]; then
    # shellcheck disable=SC1090
    . "$ST/titan2_pad_apply_last" 2>/dev/null || true
  fi
  if [ -f "$ST/titan2_idc_kind" ]; then
    LAST_IDC_KIND=`cat "$ST/titan2_idc_kind" 2>/dev/null | tr -d '\r\n '`
  fi
  TP_PID_CACHE=""
  return $_rc
}

orient_rel_up() { pidof titan2-orient-rel >/dev/null 2>&1; }
ensure_orient_rel() { _run_pad_apply orient ensure; }
apply_pad() { _run_pad_apply apply; }

# --- Boot + cool rare belts densify (2.205) ---
_stage_product_bridge_tmp() { _peel_run titan2-dev-action.sh dev-action stage_bridge 2>/dev/null || true; }
_boot_pad_safe() { _run_pad_apply boot_safe; }

# Quiet exclusive Sym bind when staged tip has in-bridge specials map.
_boot_bind_excl_sym() {
  [ -x /data/local/tmp/hid_bridge ] || return 0
  strings /data/local/tmp/hid_bridge 2>/dev/null | grep -q 'specials layer in-bridge' || return 0
  printf 1 >/data/local/tmp/titan2_hid_excl_sym 2>/dev/null || true
  chmod 666 /data/local/tmp/titan2_hid_excl_sym 2>/dev/null || true
  settings put global titan2_hid_excl_sym 1 2>/dev/null || true
  if ! strings /system/etc/titan2_usb_hid/hid_bridge 2>/dev/null | grep -q 'specials layer in-bridge'; then
    if [ -e /system/etc/titan2_usb_hid/hid_bridge ]; then
      mount --bind /data/local/tmp/hid_bridge /system/etc/titan2_usb_hid/hid_bridge 2>/dev/null \
        && log "boot_bind exclusive Sym staged→system (no swap thrash)" \
        || log "boot_bind skip (no root) — tmp map live for service"
    fi
  fi
  return 0
}

# Full boot after claim (skipped on FAST_BOOT pure-reload).
_agent_boot_full() {
  _stage_product_bridge_tmp
  _boot_bind_excl_sym
  _heal_session_off_remote_q
  _cool_idle_park_plane
  _heal_ghost_host_layout_phone
  _boot_pad_safe
  log "boot_pad_modes_only"
  apply_pad
  log "boot_pad_applied mode=`read_pad_mode`"
  LAST_SUBDISP=""
  SUBDISP_REASSERT_FAILS=0
  (
    apply_subdisplay
    log "boot_subdisplay_apply on=`_read_subdisplay_on` bri=`_read_subdisplay_bri`"
  ) &
  (
    apply_fn
    log "boot_specials_done fn=$LAST_FN char=$LAST_CHAR_MOD"
  ) &
  return 0
}

# One-shot IMS action plane (heat + cool). Props stay rate-limited elsewhere.
_ims_oneshot_tick() {
  _ia=`read_first titan2_ims_action 2>/dev/null`
  case "$_ia" in
    heal|rebind|create_apn|install|force_lte) apply_ims_action ;;
  esac
  return 0
}

# Auto multi_sim heal when SIM LOADED but multi_sim_voice still -1 (2.145/2.207).
_ims_multi_sim_tick() {
  _msv=`settings get global multi_sim_voice_call 2>/dev/null | tr -d '\r\n '`
  _sst=`getprop gsm.sim.state 2>/dev/null | tr -d '\r\n '`
  case "$_sst" in
    *LOADED*|*READY*)
      case "$_msv" in
        -1|null|"")
          log "ims auto multi_sim heal (was=$_msv sim=$_sst)"
          ims_apply_sub_defaults
          ims_bind_all_slots
          ;;
      esac
      ;;
  esac
  return 0
}

# Rate-limited props belt: keys_pause clear + IMS/BT props + subtouch + LED plane.
_props_belt_tick() {
  _clear_sticky_keys_pause
  apply_ims_props
  subtouch_now=$(read_subtouch_inhibit)
  persist_ctrl titan2_subtouch_inhibit "$subtouch_now"
  _keyled_plane_persist "$HEAT_PARK"
  return 0
}


# Cool-path rare belts (skip under heat where already gated).
_cool_rare_belts() {
  # Rear-pointer abandon + long_press/IME/dim/sensor (cool only; 2.209).
  if [ $((loop_n % 30)) -eq 5 ]; then
    for _d in "$T2" "$ST"; do
      printf 1 >"$_d/titan2_subtouch_inhibit" 2>/dev/null || true
      chmod 666 "$_d/titan2_subtouch_inhibit" 2>/dev/null || true
    done
    _settings_put_bg global titan2_subtouch_inhibit 1
    _surf=`read_first titan2_input_surface`
    case "`echo "$_surf" | tr 'A-Z' 'a-z' | tr -d '
 '`" in
      sub|rear|sub_touch|both|all|dual)
        case "`read_pad_mode`" in mouse|trackpad) _ns=hw ;; *) _ns=none ;; esac
        for _d in "$T2" "$ST"; do
          printf "$_ns" >"$_d/titan2_input_surface" 2>/dev/null || true
          chmod 666 "$_d/titan2_input_surface" 2>/dev/null || true
        done
        _settings_put_bg global titan2_input_surface "$_ns"
        set_pad_inhibited 1
        ;;
    esac
  fi
  [ $((loop_n % 25)) -eq 1 ] && ( _ensure_long_press_regl ) &
  [ $((loop_n % 120)) -eq 3 ] && _ensure_latinime_product_ime
  if [ $((loop_n % 30)) -eq 11 ]; then
    if _allow_dim_belt; then
      ( _put_wallpaper_dim_mild ) &
    else
      ( _put_wallpaper_dim_settings_only ) &
    fi
  fi
  [ $((loop_n % 90)) -eq 7 ] && { _heal_sensor_qs_tiles || true; }
  return 0
}



# --- main ---
mkdir -p /data/local/tmp "$T2" 2>/dev/null
chmod 777 "$T2" 2>/dev/null || true
# Singleton already claimed at top via lockdir — do NOT kill other pad-agents
# by name. Extra starters exit 0 when we hold a fresh lock.
MY_PID=$$
echo "$MY_PID" > /data/local/tmp/titan2_agent.pid 2>/dev/null || true
chmod 666 /data/local/tmp/titan2_agent.pid 2>/dev/null || true
rm -f /data/local/tmp/titan2_agent.lock 2>/dev/null || true
log "agent_claim pid=$MY_PID t2=$T2"
# 2.202: legacy migrate + claim hygiene + label + B1 + activity → ctrl-seed agent
_run_ctrl_seed_agent
echo "caret=plane trc=`read_pad_top_row_cursor 2>/dev/null || echo 1`" >"$ST/titan2_caret_status" 2>/dev/null || true

# Key activity + screen-off remaps: titan2-key-watch.sh (2.176).
# KEYBOARDS discovery + getevent loop live in that daemon.

# Session-off exclusive queue flush → key-fire (2.200)
_heal_session_off_remote_q() {
  case "`read_first titan2_usb_hid_session`" in 1|true|on|yes|ON) return 0 ;; esac
  case "`read_first titan2_usb_hid_grab`" in 1|true|on|yes|ON) return 0 ;; esac
  _peel_run titan2-key-fire.sh key-fire flush 2>/dev/null || true
}




# --- Side KEY_FIRE / key-watch (2.174/2.176 densify 2.197/2.211) ---
_ensure_side_key() {
  _ensure_peel_daemon titan2-side-key.sh titan2-side-key titan2_side_key.pid titan2_side_key.log run
}
# 2.215: Home (scan 580) dies when key-watch exits and leaves lock.d — clear
# stale singleton before spawn so ensure can revive without remount/su.
# 2.219: pidfile-only live check — never walk all /proc (hangs, dual-kill thrash).
_ensure_key_watch() {
  _kw_pid=`cat "$ST/titan2_key_watch.pid" 2>/dev/null | tr -d '\r\n '`
  _kw_live=0
  _kw_keep=
  case "$_kw_pid" in
    ''|*[!0-9]*) ;;
    *)
      if kill -0 "$_kw_pid" 2>/dev/null; then
        # Confirm cmdline without full /proc walk
        if grep -a -F -q "titan2-key-watch" "/proc/$_kw_pid/cmdline" 2>/dev/null; then
          _kw_live=1
          _kw_keep=$_kw_pid
        fi
      fi
      ;;
  esac
  if [ "$_kw_live" != "1" ]; then
    rmdir "$ST/titan2_key_watch.lock.d" 2>/dev/null || \
      rm -rf "$ST/titan2_key_watch.lock.d" 2>/dev/null || true
    rm -f "$ST/titan2_key_watch.lock" "$ST/titan2_key_watch.pid" 2>/dev/null || true
  fi
  # Prefer tip peel. Respawn when live status is not the same KW_VER as tip
  # (2.191 vs 2.193 was skipped because both matched 2.19x — Recents am heresy stuck).
  if [ -x /data/local/tmp/titan2-key-watch.sh ] \
    && grep -qE 'KW_VER=2\.19[0-9]|KW_VER=2\.2' /data/local/tmp/titan2-key-watch.sh 2>/dev/null; then
    _tip_ver=`grep -m1 '^KW_VER=' /data/local/tmp/titan2-key-watch.sh 2>/dev/null | head -1`
    _tip_ver=`echo "$_tip_ver" | tr -d '\r\n '`
    _run_ver=`cat "$ST/titan2_key_watch_status" 2>/dev/null | head -1`
    if [ "$_kw_live" = "1" ] && [ -n "$_tip_ver" ] \
      && ! echo "$_run_ver" | grep -qF "$_tip_ver" 2>/dev/null; then
      log "key-watch tip upgrade kill live=$_kw_keep tip=$_tip_ver run=$_run_ver"
      kill -9 "$_kw_keep" 2>/dev/null || true
      _kw_live=0
      rmdir "$ST/titan2_key_watch.lock.d" 2>/dev/null || \
        rm -rf "$ST/titan2_key_watch.lock.d" 2>/dev/null || true
      rm -f "$ST/titan2_key_watch.lock" "$ST/titan2_key_watch.pid" 2>/dev/null || true
    fi
    if [ "$_kw_live" != "1" ]; then
      /data/local/tmp/titan2-key-watch.sh run >>"$ST/titan2_key_watch.log" 2>&1 &
      sleep 0.2
    fi
    return 0
  fi
  _ensure_peel_daemon titan2-key-watch.sh titan2-key-watch titan2_key_watch.pid titan2_key_watch.log run
}
_ensure_watch_daemons() {
  _ensure_typing_watch 2>/dev/null || true
  _ensure_side_key 2>/dev/null || true
  _ensure_key_watch 2>/dev/null || true
}


# Soft ADB bootstrap (2.199): peeled to titan2-dev-action.sh adb_bootstrap
# 2.85: skip entirely on pure hot_reload FAST_BOOT (setprop USB thrash multi-sec).
if [ -z "${TITAN2_FAST_BOOT:-}" ]; then
  _peel_run titan2-dev-action.sh dev-action adb_bootstrap 2>/dev/null || true
fi

echo $$ > "$ST/titan2_agent.pid"
chmod 666 "$ST/titan2_agent.pid" 2>/dev/null

log "agent_start pid=$$"

# Dedicated notif LED engine (2.192/2.211): peel daemon ensure (match engine, not apply)
if ! _ensure_peel_daemon titan2-keyled-write.sh "keyled-write.sh engine" \
    titan2_notif_engine.pid titan2_notif_engine.log engine; then
  log "notif_engine offline — keyled-write missing"
fi

# 2.74: do NOT call apply_fn here — HARD uevent can hang; specials seed in loop (bg).
bump
# 1.44/1.45/2.200: boot inject + exclusive specials queue flush → key-fire
_peel_run titan2-key-fire.sh key-fire flush 2>/dev/null || true
log "agent_loop pid=$$"
# 2.85/2.205: pure hot_reload skips cool_idle/bridge/adb setprop thrash.
if [ -n "${TITAN2_FAST_BOOT:-}" ]; then
  unset TITAN2_FAST_BOOT 2>/dev/null || true
  log "fast_reload skip cool_idle/bridge pad-only"
  _boot_pad_safe() { return 0; }
  apply_pad
  log "fast_reload pad applied mode=`read_pad_mode`"
else
  _agent_boot_full
fi


# Pad mode apply (2.201): always inline. PAD_BG_PID only gates specials/orient bg.
# 2.86 dropped deferred pad apply (mode ON waited multi-sec behind specials).
PAD_BG_PID=""
_pad_bg_busy() {
  [ -n "$PAD_BG_PID" ] && kill -0 "$PAD_BG_PID" 2>/dev/null
}
_schedule_apply_pad() { apply_pad; }

# Cool residual mouse daemon health + orient-rel (2.206 densify).
_mouse_health_tick() {
  mode_now=`read_pad_mode`
  [ "$mode_now" = "mouse" ] || return 0
  TP_PID_CACHE=""
  if ! tp_up; then
    if [ -f "$PAD_STATUS" ] && grep -q 'typing_lock=1' "$PAD_STATUS" 2>/dev/null; then
      :
    else
      _schedule_apply_pad
    fi
  fi
  if [ "`read_pad_follow_orient`" = "1" ] && ! orient_rel_up       && [ "$ORIENT_REL_MISSING" != "1" ] && ! _pad_bg_busy; then
    ( ensure_orient_rel ) &
  fi
  return 0
}


# 2.86: mid-tick pad edge — call before any heavy work so mouse ON never waits
# for specials/subdisplay/ui_plane (lab: 6s freeze mid-tick at fixed i=N).
_pad_edge_quick() {
  _m=`read_pad_mode 2>/dev/null` || return 1
  [ -n "$_m" ] || return 1
  [ "$_m" != "$LAST_PAD" ] || return 1
  _schedule_apply_pad
  LAST_PAD_MT=`mtime_max titan2_pad_mode`
  _log_hb "ok i=${loop_n:-0} pad=$LAST_PAD edge=q"
  return 0
}

# 2.164/2.190: typing park SoT = titan2-typing-watch.sh only (no dual main-loop tick).
# Residual TTL/contact/park helpers lived only for _tick_typing_cursor_pause — removed.

_ensure_typing_watch() {
  # 2.167/2.203: any live typing-watch (not only $$); peel spawn via ensure.
  if _typing_watch_live; then
    return 0
  fi
  if _sysbin titan2-typing-watch.sh >/dev/null 2>&1; then
    # empty run-args: typing-watch has no subcommands (starts loop immediately)
    _ensure_peel_daemon titan2-typing-watch.sh titan2-typing-watch       titan2_typing_watch.pid titan2_typing_watch.log ""
    return $?
  fi
  # Fallback: TYPING_WATCH_ONLY via agent script (legacy tip without peel file)
  _script=$ST/titan2-pad-agent.sh
  [ -f "$_script" ] || _script=/system/bin/titan2-pad-agent.sh
  [ -f "$_script" ] || _script="$0"
  TYPING_WATCH_ONLY=1 /system/bin/sh "$_script" >>"$ST/titan2_typing_watch.log" 2>&1 &
  echo $! >"$ST/titan2_typing_watch.pid" 2>/dev/null
  chmod 666 "$ST/titan2_typing_watch.pid" 2>/dev/null || true
  log "typing_watch pid=$! script=$_script fallback"
}

# REG-K (2.200): pad-off inhibit/idc → titan2-pad-idc.sh off_assert
_assert_pad_off_inhibit() {
  _run_pad_idc off_assert 2>/dev/null || true
}



# Mouse down (not typing_lock) → pad_dirty (2.211 densify; shared heat/cool).
_pad_tp_down_dirty() {
  [ "$mode_now" = "mouse" ] || return 1
  TP_PID_CACHE=""
  tp_up && return 1
  if [ -f "$PAD_STATUS" ] && grep -q 'typing_lock=1' "$PAD_STATUS" 2>/dev/null; then
    return 1
  fi
  return 0
}

# --- Pad edge sample densify (2.203/2.211): heat thin + cool full ---
# Sets globals: pad_dirty, pad_mt, click_mt, follow_mt, surface_mt, flip_mt, mode_now
_pad_edge_sample() {
  pad_dirty=0
  pad_mt=`mtime_max titan2_pad_mode`
  [ "$pad_mt" != "$LAST_PAD_MT" ] && pad_dirty=1
  lock_mt=`mtime_max titan2_input_lock`
  [ "$lock_mt" != "$LAST_LOCK_MT" ] && pad_dirty=1
  LAST_LOCK_MT=$lock_mt
  if [ "$HEAT_PARK" = "1" ]; then
    mode_now=`_read_pad_mode_files`
    [ "$mode_now" != "$LAST_PAD" ] && pad_dirty=1
    # Secondary pad planes ~60s @2s under heat (click/follow/surface/flip).
    if [ $((loop_n % 30)) -eq 0 ]; then
      click_mt=`mtime_max titan2_pad_click`
      follow_mt=`mtime_max titan2_pad_follow_orient`
      surface_mt=`mtime_max titan2_input_surface`
      flip_mt=`mtime_max titan2_sub_touch_flip_x`
      [ "$click_mt" != "$LAST_CLICK_MT" ] && pad_dirty=1
      [ "$follow_mt" != "$LAST_FOLLOW_MT" ] && pad_dirty=1
      [ "$surface_mt" != "${LAST_SURFACE_MT:-0}" ] && pad_dirty=1
      [ "$flip_mt" != "${LAST_FLIP_MT:-0}" ] && pad_dirty=1
      _pad_tp_down_dirty && pad_dirty=1
    else
      click_mt=${LAST_CLICK_MT:-0}
      follow_mt=${LAST_FOLLOW_MT:-0}
      surface_mt=${LAST_SURFACE_MT:-0}
      flip_mt=${LAST_FLIP_MT:-0}
    fi
  else
    click_mt=`mtime_max titan2_pad_click`
    follow_mt=`mtime_max titan2_pad_follow_orient`
    surface_mt=`mtime_max titan2_input_surface`
    flip_mt=`mtime_max titan2_sub_touch_flip_x`
    [ "$click_mt" != "$LAST_CLICK_MT" ] && pad_dirty=1
    [ "$follow_mt" != "$LAST_FOLLOW_MT" ] && pad_dirty=1
    [ "$surface_mt" != "${LAST_SURFACE_MT:-0}" ] && pad_dirty=1
    [ "$flip_mt" != "${LAST_FLIP_MT:-0}" ] && pad_dirty=1
    mode_now=`read_pad_mode`
    [ "$mode_now" != "$LAST_PAD" ] && pad_dirty=1
    [ $((loop_n % 25)) -eq 0 ] && _pad_tp_down_dirty && pad_dirty=1
  fi
}

# Pad-edge micro-loop after dirty apply (2.208/2.211 densify): mid-tick flips.
_pad_edge_micro_loop() {
  _j=0
  while [ "$_j" -lt 12 ]; do
    _j=`expr $_j + 1 2>/dev/null` || _j=12
    _m2=`_read_pad_mode_files`
    if [ -n "$_m2" ] && [ "$_m2" != "$LAST_PAD" ]; then
      _schedule_apply_pad
      _log_hb "ok i=${loop_n:-0} pad=$LAST_PAD edge=μ"
    fi
    _usleep_us 12000
  done
  apply_led
}

# Heat thin-body rare belts then deep-idle continue (2.121/2.208 densify).
# Caller already set HEAT_PARK=1 and pad-edge sample ran.
_heat_thin_body() {
  # Remote ADB / Dev actions MUST run in heat park — human toggle cannot wait for cool.
  apply_dev_action
  if [ $((loop_n % 30)) -eq 0 ]; then
    _maybe_hot_reload_staged_agent
  fi
  _log_hb "ok i=$loop_n pad=$LAST_PAD heat=thin fn=$LAST_FN char=$LAST_CHAR_MOD sp=$LAST_CHAR_SCAN hlay=$LAST_HOST_LAYOUT"
  _heal_singleton_lock
  # 2.215: Home key-watch must heal in heat (was cool-only ensure → dead Home).
  if [ $((loop_n % 15)) -eq 1 ]; then
    _ensure_key_watch 2>/dev/null || true
  fi
  if [ $((loop_n % 30)) -eq 1 ]; then
    _ensure_keycode_drain_alive
  fi
  if [ $((loop_n % 15)) -eq 0 ]; then
    _specials_dirty_apply || true
    _subdisplay_edge_tick 0
  fi
  if [ $((loop_n % 90)) -eq 11 ]; then
    ( _put_wallpaper_dim_settings_only ) &
  fi
  if [ $((loop_n % 90)) -eq 0 ]; then
    _prune_orphan_agent_roots
    _kick_cube_load_tip_land
    _heat_getevent_heal_tick
  fi
  if [ $((loop_n % 15)) -eq 7 ]; then
    ge_q=`_getevent_count`
    if [ "$ge_q" -eq 0 ] 2>/dev/null; then
      _heat_reexec_if_aged "heat_input_dead_fast getevent=0 re-exec" 25 0 || true
    fi
  fi
  if [ $((loop_n % 100)) -eq 0 ]; then
    _heal_session_off_remote_q
    _clear_sticky_keys_pause
  fi
  if [ $((loop_n % 30)) -eq 0 ]; then
    log "cube-load-edge-park load park interval_us=${HEAT_IDLE_US:-2000000}"
  fi
  # LED + IMS oneshot every heat tick (must not stall VoLTE under load≥8).
  _ims_oneshot_tick
  apply_led
  _heat_idle_sleep
}


# --- Heat/getevent + subdisplay densify (2.204) ---
# Stamp heat heal time and pure-reexec. $1=reason $2=min_age_s $3=kill_orphans 0|1
_heat_reexec_if_aged() {
  _why="$1"; _min="${2:-30}"; _kill="${3:-0}"
  last_h=`cat "$ST/titan2_heat_heal_s" 2>/dev/null | tr -d '\r\n '`
  now_h=`date +%s`
  case "$last_h" in ''|*[!0-9]*) last_h=0 ;; esac
  age_h=`expr $now_h - $last_h 2>/dev/null` || age_h=999
  if [ "$age_h" -gt "$_min" ] 2>/dev/null; then
    echo "$now_h" > "$ST/titan2_heat_heal_s" 2>/dev/null || true
    chmod 666 "$ST/titan2_heat_heal_s" 2>/dev/null || true
    [ "$_kill" = "1" ] && _kill_orphan_input_watchers
    _pure_reexec_self "$_why"
  fi
  return 1
}

# Count getevent procs (0 if none).
_getevent_count() {
  ge_n=`pidof getevent 2>/dev/null | wc -w | tr -d ' \n'`
  case "$ge_n" in ''|*[!0-9]*) ge_n=0 ;; esac
  echo "$ge_n"
}

# Heat thin-body getevent heal (dead=0 → reexec; thrash>8 → kill+reexec).
# 2.223: pad off + HID 0 is Cube idle — do not restore getevent pile.
_heat_getevent_heal_tick() {
  if ! hid_session_on; then
    case "`_read_pad_mode_files`" in
      off) return 0 ;;
    esac
  fi
  ge_n=`_getevent_count`
  if [ "$ge_n" -eq 0 ] 2>/dev/null; then
    _heat_reexec_if_aged "heat_input_dead getevent=0 re-exec (restore key/side watchers)" 30 0 || true
  elif [ "$ge_n" -gt 8 ] 2>/dev/null; then
    if ! _heat_reexec_if_aged "heat_heal getevent=$ge_n re-exec" 120 1; then
      last_h=`cat "$ST/titan2_heat_heal_s" 2>/dev/null | tr -d '\r\n '`
      now_h=`date +%s`
      case "$last_h" in ''|*[!0-9]*) last_h=0 ;; esac
      age_h=`expr $now_h - $last_h 2>/dev/null` || age_h=999
      log "heat_warn getevent=$ge_n (heal cooldown ${age_h}s)"
    fi
  fi
  return 0
}

# Subdisplay apply-stamp edge (cheap). Optional mtime heal when $1=mtime.
_subdisplay_edge_tick() {
  _do_mt="${1:-0}"
  _app_stamp=`read_first titan2_subdisplay_apply`
  if [ -n "$_app_stamp" ] && [ "$_app_stamp" != "${LAST_SUBDISP_APPLY:-}" ]; then
    LAST_SUBDISP_APPLY="$_app_stamp"
    LAST_SUBDISP=""
    SUBDISP_REASSERT_FAILS=0
    ( apply_subdisplay ) &
  fi
  [ "$_do_mt" = "1" ] || return 0
  _sd_mt=0
  for _sdf in "$ST/titan2_subdisplay_on" "$T2/titan2_subdisplay_on" \
      "$ST/titan2_subdisplay_apply" "$T2/titan2_subdisplay_apply"; do
    [ -f "$_sdf" ] || continue
    _m=`stat -c %Y "$_sdf" 2>/dev/null` || _m=0
    case "$_m" in ''|*[!0-9]*) _m=0 ;; esac
    [ "$_m" -gt "$_sd_mt" ] 2>/dev/null && _sd_mt=$_m
  done
  if [ "$_sd_mt" -gt 0 ] 2>/dev/null && [ "$_sd_mt" != "$LAST_SUBDISP_MT" ]; then
    LAST_SUBDISP_MT=$_sd_mt
    LAST_SUBDISP=""
    SUBDISP_REASSERT_FAILS=0
    ( apply_subdisplay ) &
  fi
  # Optional DT2W plane edge
  _dt=`read_first titan2_dt2w`
  if [ -n "$_dt" ] && [ "$_dt" != "${LAST_DT2W_PLANE:-}" ]; then
    LAST_DT2W_PLANE="$_dt"
    ( apply_dt2w ) &
  fi
  return 0
}

# Heat idle sleep (typing hot / pointer / deep). Shared heat thin + cool heat park.
_heat_idle_sleep() {
  # CubalC free-flow: human UI wake / fresh pad_mode → ≤15ms, never 2s park.
  # Keep pad_wake inode 666 (ImpulseSnap app uid cannot create under tmp).
  if [ -s "$ST/titan2_pad_wake" ]; then
    : >"$ST/titan2_pad_wake" 2>/dev/null || true
    chmod 666 "$ST/titan2_pad_wake" 2>/dev/null || true
    _usleep_us "${HEAT_IDLE_HUMAN_US:-15000}"
    return 0
  fi
  _pmt=`stat -c %Y "$ST/titan2_pad_mode" 2>/dev/null` || _pmt=0
  _now=`date +%s 2>/dev/null` || _now=0
  case "$_pmt" in ''|*[!0-9]*) _pmt=0 ;; esac
  case "$_now" in ''|*[!0-9]*) _now=0 ;; esac
  if [ "$_now" -gt 0 ] && [ "$_pmt" -gt 0 ] 2>/dev/null \
      && [ $((_now - _pmt)) -le 2 ] 2>/dev/null; then
    _usleep_us "${HEAT_IDLE_HUMAN_US:-15000}"
    return 0
  fi
  _pm_heat=`cat "$T2/titan2_pad_mode" 2>/dev/null | tr -d '\r\n \t'`
  [ -n "$_pm_heat" ] || _pm_heat=`cat "$ST/titan2_pad_mode" 2>/dev/null | tr -d '\r\n \t'`
  if _typing_plane_hot; then
    _usleep_us 50000
  else
    case "$_pm_heat" in
      mouse|trackpad) _usleep_us 50000 ;;
      *)
        if command -v usleep >/dev/null 2>&1; then
          usleep "${HEAT_IDLE_US:-50000}"
        else
          sleep 0.05
        fi
        ;;
    esac
  fi
}


# Removed: titan2_icon_apply watcher. Magisk 2s loop + unique timestamps
# re-ran icons-preset until SystemUI froze. Apply is Controls one-shot only.
_icon_apply_tick() { return 0; }

# --- Cube dual-plane UI (2.186): peeled to titan2-ui-plane.sh ---
LAST_UI_PLANE=""

apply_ui_plane() {
  _peel_run titan2-ui-plane.sh ui-plane apply || return $?
  if [ -f "$ST/titan2_ui_plane_status" ]; then
    LAST_UI_PLANE=`cat "$ST/titan2_ui_plane_status" 2>/dev/null | tr -d '
 '`
  fi
  return 0
}



# 2.164: TYPING_WATCH_ONLY handled at file top (exec peeled script). No second loop.

loop_n=0
# Unlock delay owned by typing-watch sidecar (50ms), not the heat-stalled body.
# 2.174/2.176: side KEY_FIRE + key-watch daemons (not in-process getevent).
_ensure_watch_daemons
while true; do
  loop_n=`expr $loop_n + 1 2>/dev/null` || loop_n=1

  # Keep peeled watch alive (crash / pure-exec); park logic is not main-path SoT.
  # 2.215: every 10 loops (was 40) — Home key-watch must revive under heat.
  [ $((loop_n % 10)) -eq 1 ] && _ensure_watch_daemons

  # 2.122: heat gate FIRST so pad-edge can park under load≥8 (2.121 still paid
  # 5× mtime_max + read_pad_mode every heat tick before thin-body continue).
  HEAT_PARK=0
  if _cube_heat_park; then HEAT_PARK=1; fi

  # 2.83/2.203: PAD EDGE BEFORE heartbeat/subdisplay (shared sample helper).
  # Dev actions every loop top — before any continue (heat/pad/tight).
  apply_dev_action
  apply_fw_action
  _icon_apply_tick

  _pad_edge_sample
  _ce_now=`getprop sys.user.0.ce_available 2>/dev/null | tr -d '\r'`
  if [ -n "$_ce_now" ] && [ "$_ce_now" != "$LAST_CE" ]; then
    LAST_CE=$_ce_now
    pad_dirty=1
  fi
  if [ "$pad_dirty" = "1" ]; then
    _schedule_apply_pad
    LAST_PAD_MT=$pad_mt
    LAST_CLICK_MT=$click_mt
    LAST_FOLLOW_MT=$follow_mt
    LAST_SURFACE_MT=$surface_mt
    LAST_FLIP_MT=$flip_mt
    _log_hb "ok i=$loop_n pad=$LAST_PAD edge=1"
    _pad_edge_micro_loop
    continue
  fi

  # 2.121/2.208: heat thin-body densified (rare belts + LED/IMS + deep-idle).
  if [ "$HEAT_PARK" = "1" ]; then
    _heat_thin_body
    continue
  fi

  # ---- Cool path (load < HEAT_LOAD_GE) — heat thin already continued above ----
  # 2.209: no HEAT_PARK gates here (dead after thin continue).
  if [ $((loop_n % 3)) -eq 0 ]; then
    _maybe_hot_reload_staged_agent
  fi

  # Skip heavy body for 1s after pad mode write (mouse ON lag residual).
  _pmt=`stat -c %Y "$ST/titan2_pad_mode" 2>/dev/null` || _pmt=0
  _now=`date +%s 2>/dev/null` || _now=0
  case "$_pmt" in ''|*[!0-9]*) _pmt=0 ;; esac
  case "$_now" in ''|*[!0-9]*) _now=0 ;; esac
  if [ "$_now" -gt 0 ] && [ "$_pmt" -gt 0 ] 2>/dev/null \
      && [ $((_now - _pmt)) -le 1 ] 2>/dev/null; then
    _log_hb "ok i=$loop_n pad=$LAST_PAD tight=1"
    _usleep_us 15000
    continue
  fi

  _log_hb "ok i=$loop_n pad=$LAST_PAD fn=$LAST_FN char=$LAST_CHAR_MOD sp=$LAST_CHAR_SCAN hlay=$LAST_HOST_LAYOUT"
  _heal_singleton_lock
  [ $((loop_n % 15)) -eq 1 ] && _ensure_keycode_drain_alive
  _cool_rare_belts
  [ $((loop_n % 25)) -eq 0 ] && _heal_session_off_remote_q

  if _pad_edge_quick; then
    _usleep_us 5000
    continue
  fi

  # Specials dirty/apply; rare live_kl heal
  _typing_watch_live 2>/dev/null || _ensure_typing_watch 2>/dev/null || true
  if _specials_dirty_apply; then
    :
  elif [ $((loop_n % 50)) -eq 0 ] && _specials_live_kl_heal; then
    _usleep_us 5000
    continue
  fi

  [ $((loop_n % 15)) -eq 0 ] && \
    log "ok i=$loop_n pad=$LAST_PAD fn=$LAST_FN char=$LAST_CHAR_MOD sp=$LAST_CHAR_SCAN hlay=$LAST_HOST_LAYOUT"
  [ $((loop_n % 40)) -eq 0 ] && ! _cube_heat_park && apply_ui_plane
  [ $((loop_n % 2)) -eq 0 ] && _led_edge_tick
  if [ $((loop_n % 5)) -eq 0 ]; then
    _assert_pad_off_inhibit
    [ "`read_pad_follow_orient`" = "1" ] && publish_pad_rotation
  fi
  [ $((loop_n % 25)) -eq 0 ] && _mouse_health_tick
  # Dev actions (Remote ADB on/off) every tick — human UI must not wait 4 loops
  apply_dev_action
  apply_fw_action
  # Subdisplay stamp every tick cheap; mtime/DT2W heal ~10 ticks.
  _subdisplay_edge_tick 0
  [ $((loop_n % 10)) -eq 0 ] && _subdisplay_edge_tick 1
  # Dual-root prune + getevent thrash (~12s cool). 2.211: reuse heat heal helper.
  if [ $((loop_n % 10)) -eq 0 ]; then
    _prune_orphan_agent_roots
    _prune_orphan_agent_roots
    _kill_orphan_input_watchers
    _kick_cube_load_tip_land
    _heat_getevent_heal_tick
  fi
  [ $((loop_n % 5)) -eq 0 ] && _maybe_hot_reload_staged_agent
  _ims_oneshot_tick
  [ $((loop_n % 40)) -eq 0 ] && _ims_multi_sim_tick
  [ $((loop_n % 50)) -eq 0 ] && _props_belt_tick

  if _pad_edge_quick; then
    _usleep_us 5000
    continue
  fi

  _log_hb "ok i=$loop_n pad=$LAST_PAD fn=$LAST_FN char=$LAST_CHAR_MOD sp=$LAST_CHAR_SCAN hlay=$LAST_HOST_LAYOUT"
  _usleep_us 20000
done
