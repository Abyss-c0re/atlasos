#!/system/bin/sh
# titan2-b1-kl — OPTIMIZE Phase 3 peel: B1 side-key keylayout heal
# SoT: docs/project/OPTIMIZE_SOURCE_PRODUCT.md · B1 / LOGIC_FLOW_MAP
# Invoked by pad-agent: mtk | pmic | sides | all | version
# Unmaps 249/250 on mtk-kpd, mtk-pmic-keys, gpio_key-func, ff_key.
export PATH=/system/bin:/system/xbin:/vendor/bin:$PATH
T2=/data/misc/titan2
ST=/data/local/tmp
B1_VER=2.177-b1-kl-peel

log() {
  mkdir -p "$ST" 2>/dev/null || true
  { echo "b1-kl: $*" >>"$ST/titan2_pad_agent.log"; } 2>/dev/null || true
}


ensure_b1_mtk_kpd() {
  KL_LIVE=/system/usr/keylayout/mtk-kpd.kl
  KL_PHH_SRC=/system/phh/unihertz-mtk-kpd.kl
  KL_PHH_MNT=/mnt/phh/keylayout/mtk-kpd.kl
  DATA_KL=/data/local/tmp/titan2_kl
  mkdir -p "$DATA_KL" 2>/dev/null || true
  src=""
  for c in \
    /system/etc/titan2_keylayout/mtk-kpd.kl \
    /data/adb/modules/titan2_keychars/system/usr/keylayout/mtk-kpd.kl \
    /data/local/tmp/mtk-kpd.kl
  do
    [ -f "$c" ] || continue
    if ! grep -E '^[[:space:]]*key[[:space:]]+(249|250)[[:space:]]' "$c" >/dev/null 2>&1; then
      src=$c
      break
    fi
  done
  if [ -z "$src" ]; then
    src=$DATA_KL/mtk-kpd.product.kl
    {
      echo "# Titan 2 B1 product mtk-kpd (b1-kl heal)"
      echo "key 115   VOLUME_UP"
      echo "key 114   VOLUME_DOWN"
      echo "key 116   POWER   WAKE"
      echo "# key 249/250 intentionally omitted (sides — no CAMERA)"
    } > "$src" 2>/dev/null || true
    chmod 0644 "$src" 2>/dev/null || true
  fi
  [ -s "$src" ] || return 0
  # Always keep phh source product-shaped so next early-boot copy is clean
  if [ -f "$KL_PHH_SRC" ] && grep -E '^[[:space:]]*key[[:space:]]+(249|250)[[:space:]]' "$KL_PHH_SRC" >/dev/null 2>&1; then
    cat "$src" > "$KL_PHH_SRC" 2>/dev/null || cp -f "$src" "$KL_PHH_SRC" 2>/dev/null || true
    chmod 0644 "$KL_PHH_SRC" 2>/dev/null || true
  fi
  _b1_needs=
  for t in "$KL_LIVE" "$KL_PHH_MNT"; do
    [ -f "$t" ] || continue
    if grep -E '^[[:space:]]*key[[:space:]]+(249|250)[[:space:]]' "$t" >/dev/null 2>&1; then
      _b1_needs=1
      break
    fi
  done
  [ -n "$_b1_needs" ] || {
    echo "b1_mtk_kpd=ok" > "$ST/titan2_b1_kl_status" 2>/dev/null || true
    chmod 666 "$ST/titan2_b1_kl_status" 2>/dev/null || true
    return 0
  }
  dest=$DATA_KL/mtk-kpd.kl
  if [ -f "$dest" ]; then
    cat "$src" > "$dest" 2>/dev/null || cp -f "$src" "$dest" 2>/dev/null || true
  else
    cp -f "$src" "$dest" 2>/dev/null || cat "$src" > "$dest" 2>/dev/null || true
  fi
  chmod 0644 "$dest" 2>/dev/null || true
  fixed=0
  for t in "$KL_LIVE" "$KL_PHH_MNT"; do
    [ -f "$t" ] || continue
    umount "$t" 2>/dev/null || true
    if mount --bind "$dest" "$t" 2>/dev/null; then
      fixed=1
      continue
    fi
    if cat "$src" > "$t" 2>/dev/null; then
      chmod 0644 "$t" 2>/dev/null || true
      fixed=1
    fi
  done
  # Bounce EventHub for mtk-kpd node if present (usually absent on Titan 2 gpio/ff path)
  for d in /sys/class/input/input*; do
    [ -e "$d/name" ] || continue
    n=`cat "$d/name" 2>/dev/null` || continue
    case "$n" in
      mtk-kpd|gpio_key-func|ff_key)
        echo change > "$d/uevent" 2>/dev/null || true
        ;;
    esac
  done
  echo "b1_mtk_kpd=healed fixed=$fixed src=$src" > "$ST/titan2_b1_kl_status" 2>/dev/null || true
  chmod 666 "$ST/titan2_b1_kl_status" 2>/dev/null || true
}

# B1 belt: mtk-pmic-keys also advertises 249/250. Without a named kl,
# EventHub can fall back to Generic (or future vendor maps). Install POWER-only
# product layout so sides never become system keys via pmic dual-emit.
ensure_b1_pmic_keys() {
  KL_LIVE=/system/usr/keylayout/mtk-pmic-keys.kl
  DATA_KL=/data/local/tmp/titan2_kl
  mkdir -p "$DATA_KL" 2>/dev/null || true
  src=""
  for c in \
    /system/etc/titan2_keylayout/mtk-pmic-keys.kl \
    /data/adb/modules/titan2_keychars/system/usr/keylayout/mtk-pmic-keys.kl \
    /data/local/tmp/mtk-pmic-keys.kl
  do
    [ -f "$c" ] || continue
    if ! grep -E '^[[:space:]]*key[[:space:]]+(249|250)[[:space:]]' "$c" >/dev/null 2>&1; then
      src=$c
      break
    fi
  done
  if [ -z "$src" ]; then
    src=$DATA_KL/mtk-pmic-keys.product.kl
    {
      echo "# Titan 2 B1 product mtk-pmic-keys (b1-kl heal)"
      echo "key 116   POWER   WAKE"
      echo "# key 249/250 intentionally omitted (sides — no CAMERA/HOME)"
    } > "$src" 2>/dev/null || true
    chmod 0644 "$src" 2>/dev/null || true
  fi
  [ -s "$src" ] || return 0
  needs=
  if [ ! -f "$KL_LIVE" ]; then
    needs=1
  elif grep -E '^[[:space:]]*key[[:space:]]+(249|250)[[:space:]]' "$KL_LIVE" >/dev/null 2>&1; then
    needs=1
  fi
  [ -n "$needs" ] || {
    echo "b1_pmic=ok" >> "$ST/titan2_b1_kl_status" 2>/dev/null || true
    return 0
  }
  dest=$DATA_KL/mtk-pmic-keys.kl
  cp -f "$src" "$dest" 2>/dev/null || cat "$src" > "$dest" 2>/dev/null || true
  chmod 0644 "$dest" 2>/dev/null || true
  fixed=0
  if [ -f "$KL_LIVE" ]; then
    umount "$KL_LIVE" 2>/dev/null || true
    if mount --bind "$dest" "$KL_LIVE" 2>/dev/null; then
      fixed=1
    elif cat "$src" > "$KL_LIVE" 2>/dev/null; then
      chmod 0644 "$KL_LIVE" 2>/dev/null || true
      fixed=1
    fi
  else
    # Missing file: hybrid often mounts /system/usr/keylayout as tmpfs (rw) with
    # per-file bind-mounts for product kl. Creating the *named* file is enough
    # for EventHub to leave Generic.kl (B1 belt).
    if [ -d /data/adb/modules ]; then
      mkdir -p /data/adb/modules/titan2_keychars/system/usr/keylayout 2>/dev/null || true
      cp -f "$src" /data/adb/modules/titan2_keychars/system/usr/keylayout/mtk-pmic-keys.kl 2>/dev/null || true
    fi
    mount -o remount,rw /system 2>/dev/null || mount -o remount,rw / 2>/dev/null || true
    if cp -f "$src" "$KL_LIVE" 2>/dev/null || cat "$src" > "$KL_LIVE" 2>/dev/null; then
      chmod 0644 "$KL_LIVE" 2>/dev/null || true
      fixed=1
    else
      # Writable dest then bind into place (works when parent is tmpfs)
      if cp -f "$src" "$dest" 2>/dev/null; then
        if mount --bind "$dest" "$KL_LIVE" 2>/dev/null; then
          fixed=1
        fi
      fi
      if [ "$fixed" != "1" ] && [ -d /data/system/devices/keylayout ]; then
        cp -f "$src" /data/system/devices/keylayout/mtk-pmic-keys.kl 2>/dev/null && fixed=1 || true
      fi
    fi
  fi
  for d in /sys/class/input/input*; do
    [ -e "$d/name" ] || continue
    n=`cat "$d/name" 2>/dev/null` || continue
    case "$n" in
      mtk-pmic-keys)
        echo change > "$d/uevent" 2>/dev/null || true
        ;;
    esac
  done
  echo "b1_pmic=healed fixed=$fixed" >> "$ST/titan2_b1_kl_status" 2>/dev/null || true
  chmod 666 "$ST/titan2_b1_kl_status" 2>/dev/null || true
}

# B1 real side nodes: gpio_key-func (bottom 250) + ff_key (top 249).
# mtk-kpd/pmic heal alone left Home residual when stock gpio/ff mapped 249/250
# (Titan 2 sides are not on mtk-kpd — see LOGIC_FLOW_MAP event3/event8).
# Prefer product Magisk/tmp bodies; install named kl when missing or mapped.
ensure_b1_side_nodes() {
  DATA_KL=/data/local/tmp/titan2_kl
  DATA_SYS_KL=/data/system/devices/keylayout
  mkdir -p "$DATA_KL" 2>/dev/null || true
  mkdir -p "$DATA_SYS_KL" 2>/dev/null || true
  for name in gpio_key-func ff_key; do
    KL_LIVE=/system/usr/keylayout/${name}.kl
    src=""
    for c in \
      /system/etc/titan2_keylayout/${name}.kl \
      /data/adb/modules/titan2_keychars/system/usr/keylayout/${name}.kl \
      /data/local/tmp/${name}.kl \
      /data/local/tmp/titan2_kl/${name}.kl
    do
      [ -f "$c" ] || continue
      if ! grep -E '^[[:space:]]*key[[:space:]]+(249|250)[[:space:]]' "$c" >/dev/null 2>&1; then
        src=$c
        break
      fi
    done
    if [ -z "$src" ]; then
      src=$DATA_KL/${name}.product.kl
      if [ "$name" = "ff_key" ]; then
        {
          echo "# Titan 2 B1 product ff_key (b1-kl heal)"
          echo "key 28    ENTER"
          echo "key 103   DPAD_UP"
          echo "key 105   DPAD_LEFT"
          echo "key 106   DPAD_RIGHT"
          echo "key 108   DPAD_DOWN"
          echo "key 116   POWER   WAKE"
          echo "key 158   BACK"
          echo "# key 249 intentionally omitted (side · top)"
        } > "$src" 2>/dev/null || true
      else
        {
          echo "# Titan 2 B1 product gpio_key-func (b1-kl heal)"
          echo "# Intentionally no key maps — side · bottom scan 250 pad-agent only"
          echo "# key 250 intentionally omitted"
        } > "$src" 2>/dev/null || true
      fi
      chmod 0644 "$src" 2>/dev/null || true
    fi
    [ -s "$src" ] || continue
    needs=
    if [ ! -f "$KL_LIVE" ]; then
      needs=1
    elif grep -E '^[[:space:]]*key[[:space:]]+(249|250)[[:space:]]' "$KL_LIVE" >/dev/null 2>&1; then
      needs=1
    fi
    # Always keep data-system + tmp product-shaped (EventHub override / install stage)
    cp -f "$src" "$DATA_KL/${name}.kl" 2>/dev/null || cat "$src" > "$DATA_KL/${name}.kl" 2>/dev/null || true
    chmod 0644 "$DATA_KL/${name}.kl" 2>/dev/null || true
    cp -f "$src" "$DATA_SYS_KL/${name}.kl" 2>/dev/null || true
    chmod 0644 "$DATA_SYS_KL/${name}.kl" 2>/dev/null || true
    if [ -z "$needs" ]; then
      echo "b1_${name}=ok" >> "$ST/titan2_b1_kl_status" 2>/dev/null || true
      continue
    fi
    dest=$DATA_KL/${name}.kl
    fixed=0
    if [ -f "$KL_LIVE" ]; then
      umount "$KL_LIVE" 2>/dev/null || true
      if mount --bind "$dest" "$KL_LIVE" 2>/dev/null; then
        fixed=1
      elif cat "$src" > "$KL_LIVE" 2>/dev/null; then
        chmod 0644 "$KL_LIVE" 2>/dev/null || true
        fixed=1
      fi
    else
      if [ -d /data/adb/modules ]; then
        mkdir -p /data/adb/modules/titan2_keychars/system/usr/keylayout 2>/dev/null || true
        cp -f "$src" /data/adb/modules/titan2_keychars/system/usr/keylayout/${name}.kl 2>/dev/null || true
      fi
      mount -o remount,rw /system 2>/dev/null || mount -o remount,rw / 2>/dev/null || true
      if cp -f "$src" "$KL_LIVE" 2>/dev/null || cat "$src" > "$KL_LIVE" 2>/dev/null; then
        chmod 0644 "$KL_LIVE" 2>/dev/null || true
        fixed=1
      elif mount --bind "$dest" "$KL_LIVE" 2>/dev/null; then
        fixed=1
      fi
    fi
    for d in /sys/class/input/input*; do
      [ -e "$d/name" ] || continue
      n=`cat "$d/name" 2>/dev/null` || continue
      [ "$n" = "$name" ] || continue
      echo change > "$d/uevent" 2>/dev/null || true
    done
    echo "b1_${name}=healed fixed=$fixed" >> "$ST/titan2_b1_kl_status" 2>/dev/null || true
  done
  chmod 666 "$ST/titan2_b1_kl_status" 2>/dev/null || true
}

cmd="${1-all}"
case "$cmd" in
  mtk|mtk_kpd)
    ensure_b1_mtk_kpd
    ;;
  pmic|pmic_keys)
    ensure_b1_pmic_keys
    ;;
  sides|side_nodes)
    ensure_b1_side_nodes
    ;;
  all|heal)
    ensure_b1_mtk_kpd
    ensure_b1_pmic_keys
    ensure_b1_side_nodes
    log "all done"
    ;;
  version|-v|--version)
    echo "$B1_VER"
    ;;
  *)
    log "unknown cmd=$cmd"
    exit 1
    ;;
esac
exit 0
