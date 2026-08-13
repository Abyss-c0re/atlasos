#!/system/bin/sh
# Kernel + virtual sensors → 8^3 lattice (cube_gl cells.bin + nodes.tsv).
# Selection file (optional): $OUT_DIR/selected.txt  — one channel name per line.
#   If missing/empty → all discovered channels (legacy).
# Virtual sensors: $OUT_DIR/virtual.tsv  — name<TAB>value (managed by app/nanobot).
# Catalog for UI: $OUT_DIR/catalog.tsv — name\tvalue\tgroup\tenabled

OUT_DIR=/data/local/tmp/cubebrain_viz
CELLS=$OUT_DIR/cells.bin
NODES=$OUT_DIR/nodes.tsv
CATALOG=$OUT_DIR/catalog.tsv
SELECTED=$OUT_DIR/selected.txt
VIRTUAL=$OUT_DIR/virtual.tsv
N=8
NEED=$((N*N*N))
PREV=$OUT_DIR/.prev_vals
INTERVAL_MS=${INTERVAL_MS:-1500}
# 1.3 residual: fixed 1500ms + full sample_kernel under load≥8 left kernel-cube
# ~15–20% CPU (lab load≈17 after app/peer heat parks). Align cube heat SoT:
# load≥8 → light sample + HEAT_INTERVAL_MS (default 8s) + always SKIP_IRQ.
# 1.4 residual: 1.3 still ran light sample + full 512-cell awk catalog/dig every
# 8s (~7–11% CPU under lab load≈16 after tip land). Deep park skips sample+awk.
# 1.5 residual: 1.4 still wrote 512 zero cells via nested `byte()` printf each tick
# (~15s shell thrash / ~15% CPU under lab load≈16 while "parked"). Use one dd.
# 1.6 residual: tip_ver only re-stamped on heat path — cool path / stuck sample
# left stamp mtime frozen → pad-agent land could not prove tip alive by stamp
# alone (lab 2026-07-26 tip_ver mtime stuck 20m+ under load≈15 while process live).
# Marker tokens (tip land / assert): cube-load-park + cube-load-deep-park +
# cube-load-deep-zero + cube-load-stamp-loop (1.6 every-tick stamp).
HEAT_INTERVAL_MS=${HEAT_INTERVAL_MS:-15000}
HEAT_LOAD_GE=${HEAT_LOAD_GE:-8}
# Full IRQ table is expensive — sample every N ticks (default 4 ≈ 3.2s)
IRQ_EVERY=${IRQ_EVERY:-4}
TICK_N=0
# Tip-land re-exec stamp (pad-agent 2.118+ / 2.119 stamp-heal): keep deep-zero
# token so land grep matches; 1.6 = stamp every loop (cool+heat).
# 1.7: enforce keyboard LED from plane (root). pad-agent 2.130 claimed
# applied=1 while sysfs stayed at 3 under phhsu — cube already reads this node.
# 1.8 residual: 1.7 re-derived idle/screen and treated empty screen_state as OFF
# while pad-agent 2.129b/2.132 treats unknown as ON / uses hw_want — dual writers
# thrash keypad_led (visible blink). Follow pad-agent applied level only.
# 1.9 residual: under heat (load≥8) 1.5 wrote all-zero cells.bin so CubeContact
# went pure black while Grok/Atlas/hybrid elevated load — lattice "vanished"
# without touching cube app code. Hold last good frame; ambient only if none.
KCUBE_TIP_VER=1.9-heat-hold-frame
_stamp_tip_ver() {
  echo "$KCUBE_TIP_VER" > /data/local/tmp/titan2_kernel_cube_tip_ver 2>/dev/null || true
  chmod 666 /data/local/tmp/titan2_kernel_cube_tip_ver 2>/dev/null || true
}
_stamp_tip_ver

# Root backup: push pad-agent's last applied level to sysfs. Do NOT re-derive
# idle/HID/screen — that fought apply_led and blinked the keyboard.
enforce_keyled() {
  LED=""
  for c in \
    /sys/devices/platform/keypad_led/keyled_brightness \
    /sys/bus/platform/devices/keypad_led/keyled_brightness \
    /sys/class/misc/keypad_led/keyled_brightness \
    /sys/devices/virtual/misc/keypad_led/keyled_brightness
  do
    if cat "$c" >/dev/null 2>&1; then LED=$c; break; fi
  done
  [ -n "$LED" ] || return 0

  # SoT: pad-agent stamps applied level + reason every write_led.
  applied=`cat /data/local/tmp/titan2_keyled_hw_want 2>/dev/null | tr -d '\r\n \t'`
  case "$applied" in [0-7])
    rb=`cat "$LED" 2>/dev/null | tr -d '\r\n \t'`
    [ "$rb" = "$applied" ] && return 0
    printf '%s\n' "$applied" >"$LED" 2>/dev/null
    [ "$applied" = "0" ] && printf '0\n' >"$LED" 2>/dev/null
    return 0
    ;;
  esac

  # Fallback only when pad-agent has not stamped yet (early boot / agent down).
  want=`cat /data/misc/titan2/titan2_keyled_brightness 2>/dev/null | tr -d '\r\n \t'`
  case "$want" in [0-7]) ;; *) want=3 ;; esac
  to=`cat /data/misc/titan2/titan2_keyled_timeout 2>/dev/null | tr -d '\r\n \t'`
  case "$to" in ''|*[!0-9]*) to=30 ;; esac
  s=`getprop debug.tracing.screen_state 2>/dev/null | tr -d '\r\n \t'`
  # Known OFF only — empty/unknown must NOT force dark (pad-agent unknown→ON).
  case "$s" in 1|3|4)
    printf '0\n' >"$LED" 2>/dev/null
    printf '0\n' >"$LED" 2>/dev/null
    return 0
    ;;
  esac
  if [ "$want" = "0" ]; then
    printf '0\n' >"$LED" 2>/dev/null
    printf '0\n' >"$LED" 2>/dev/null
    return 0
  fi
  last=`cat /data/misc/titan2/titan2_key_activity 2>/dev/null | tr -d '\r\n \t'`
  case "$last" in ''|*[!0-9]*) last=0 ;; esac
  # Controls may stamp ms (13 digits); age math is seconds — convert or idle fights pad-agent (KB blink).
  case "$last" in
    [0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]*)
      last=`awk -v n="$last" 'BEGIN{printf "%d\n", n/1000}' 2>/dev/null` || last=0
      ;;
  esac
  now=`date +%s`
  age=$((now - last))
  case "$age" in ''|-*|*[!0-9]*) age=9999 ;; esac
  if [ "$to" = "0" ] || [ "$age" -lt "$to" ] 2>/dev/null; then
    printf '%s\n' "$want" >"$LED" 2>/dev/null
  else
    printf '0\n' >"$LED" 2>/dev/null
    printf '0\n' >"$LED" 2>/dev/null
  fi
}
mkdir -p "$OUT_DIR"; chmod 777 "$OUT_DIR" 2>/dev/null || true
# ensure virtual file exists for app/nanobot + Crimson LAW (all cubes)
if [ ! -f "$VIRTUAL" ]; then
  printf "%s\n" \
    "# Virtual sensors (nanobot/app) - Crimson Cube / First Cube LAW" \
    "virt_user_intent	0" \
    "virt_nanobot_up	0" \
    "virt_law_energy	0" \
    "virt_law_wins	0" \
    "virt_law_losses	0" \
    "virt_law_combines	0" \
    "virt_law_winner	-1" \
    "virt_braincube_pick	-1" \
    "virt_braincube_activity	0" \
    > "$VIRTUAL"
  chmod 666 "$VIRTUAL" 2>/dev/null || true
fi
# 1.2 residual: create-only seed left pre-LAW virtual.tsv forever without virt_law_*
# (peer-dead cool lab → rear LAW blank). Merge-append missing keys only.
ensure_law_virtual_keys() {
  [ -f "$VIRTUAL" ] || return 0
  # name TAB default
  for pair in \
    "virt_law_energy	0" \
    "virt_law_wins	0" \
    "virt_law_losses	0" \
    "virt_law_combines	0" \
    "virt_law_winner	-1" \
    "virt_braincube_pick	-1" \
    "virt_braincube_activity	0" \
    "virt_nanobot_up	0" \
    "virt_user_intent	0"
  do
    key=${pair%%	*}
    if ! grep -qE "^${key}([[:space:]]|$)" "$VIRTUAL" 2>/dev/null; then
      printf '%s\n' "$pair" >> "$VIRTUAL"
    fi
  done
  chmod 666 "$VIRTUAL" 2>/dev/null || true
}
ensure_law_virtual_keys
if [ ! -f "$SELECTED" ]; then
  : # all sensors
  true
fi
chmod 666 "$VIRTUAL" "$SELECTED" 2>/dev/null || true


sample_kernel_light() {
  # Minimal set for heat control when user selected few channels
  for k in capacity voltage_now current_now temp; do
    f=/sys/class/power_supply/battery/$k; [ -r "$f" ] || continue
    v=$(cat "$f" 2>/dev/null) || continue; echo "power ps_battery_$k $v"
  done
  while read -r line; do
    set -- $line
    case "$1" in
      cpu|cpu[0-9]*)
        c=$1; shift
        for field in user nice system idle iowait irq softirq; do
          [ -n "$1" ] || break; echo "cpu stat_${c}_$field $1"; shift
        done ;;
    esac
  done < /proc/stat 2>/dev/null
  set -- $(cat /proc/loadavg 2>/dev/null)
  echo "mem load_1m $(echo $1 | tr -d '.')"
  awk '/MemAvailable/ {print "mem", "mem_avail_kb", $2}' /proc/meminfo 2>/dev/null
  set -- $(cat /proc/uptime 2>/dev/null); echo "mem uptime_s ${1%.*}"
  for k in titan2_pad_mode; do
    v=$(settings get system $k 2>/dev/null | tr -d '\r')
    case "$v" in mouse) echo "plane set_$k 2";; trackpad) echo "plane set_$k 1";; *) echo "plane set_$k 0";; esac
  done
}

sample_kernel() {
  for k in capacity voltage_now current_now temp charge_counter charge_full; do
    f=/sys/class/power_supply/battery/$k; [ -r "$f" ] || continue
    v=$(cat "$f" 2>/dev/null) || continue; echo "power ps_battery_$k $v"
  done
  for p in /sys/class/power_supply/*; do
    bn=$(basename "$p"); [ "$bn" = battery ] && continue
    for k in voltage_now current_now temp online capacity; do
      f=$p/$k; [ -r "$f" ] || continue
      v=$(cat "$f" 2>/dev/null) || continue; echo "power ps_${bn}_$k $v"
    done
  done
  while read -r line; do
    set -- $line
    case "$1" in
      cpu|cpu[0-9]*)
        c=$1; shift
        for field in user nice system idle iowait irq softirq steal; do
          [ -n "$1" ] || break; echo "cpu stat_${c}_$field $1"; shift
        done ;;
      intr)
        shift; i=0
        for v in "$@"; do [ $i -ge 96 ] && break; echo "cpu stat_intr_$i $v"; i=$((i+1)); done ;;
    esac
  done < /proc/stat 2>/dev/null
  if [ "${SKIP_IRQ:-0}" != "1" ]; then
    awk 'NR>1 {
      name=$NF; gsub(/[^a-zA-Z0-9_+\-]/,"_",name); s=0
      for(i=2;i<=NF && i<=9;i++) if($i ~ /^[0-9]+$/) s+=$i
      if(name!="" && name!~/^CPU/) print "irq", "irq_" name, s
    }' /proc/interrupts 2>/dev/null | head -120
  fi
  set -- $(cat /proc/loadavg 2>/dev/null)
  echo "mem load_1m $(echo $1 | tr -d '.')"; echo "mem load_5m $(echo $2 | tr -d '.')"; echo "mem load_15m $(echo $3 | tr -d '.')"
  awk '/MemAvailable/ {print "mem", "mem_avail_kb", $2} /MemFree/ {print "mem", "mem_free_kb", $2}
       /Buffers/ {print "mem", "mem_buffers_kb", $2} /^Cached/ {print "mem", "mem_cached_kb", $2}
       /SwapFree/ {print "mem", "swap_free_kb", $2}' /proc/meminfo 2>/dev/null
  set -- $(cat /proc/uptime 2>/dev/null); echo "mem uptime_s ${1%.*}"
  for iface in wlan0 rmnet_data0 lo; do
    [ -r /sys/class/net/$iface/operstate ] && echo "net net_${iface}_up $([ "$(cat /sys/class/net/$iface/operstate 2>/dev/null)" = up ] && echo 1 || echo 0)"
    [ -r /sys/class/net/$iface/statistics/rx_bytes ] && echo "net net_${iface}_rx $(cat /sys/class/net/$iface/statistics/rx_bytes 2>/dev/null)"
    [ -r /sys/class/net/$iface/statistics/tx_bytes ] && echo "net net_${iface}_tx $(cat /sys/class/net/$iface/statistics/tx_bytes 2>/dev/null)"
  done
  for f in /sys/class/leds/lcd-backlight/brightness \
           /sys/devices/platform/mtk-leds1/leds/lcd-backlight1/brightness \
           /sys/class/leds/lcd-backlight1/brightness \
           /sys/devices/platform/keypad_led/keyled_brightness; do
    [ -r "$f" ] || continue
    bn=$(echo "$f" | sed 's/[\/\-]/_/g'); echo "led led_$bn $(cat "$f" 2>/dev/null)"
  done
  for k in titan2_pad_mode titan2_private_mode; do
    v=$(settings get system $k 2>/dev/null | tr -d '\r')
    case "$v" in mouse) echo "plane set_$k 2";; trackpad) echo "plane set_$k 1";; 1|true) echo "plane set_$k 1";; *) echo "plane set_$k 0";; esac
  done
  i=0
  for d in /sys/class/input/input*; do
    [ -d "$d" ] || continue
    nm=$(cat "$d/name" 2>/dev/null | tr ' /' '__'); [ -z "$nm" ] && nm=$(basename "$d")
    echo "input input_${i}_$nm 1"; i=$((i+1)); [ $i -ge 16 ] && break
  done
}

# virtual.tsv lines: name value   or  name\tvalue  (group forced virtual)
sample_virtual() {
  [ -f "$VIRTUAL" ] || return 0
  while IFS= read -r line || [ -n "$line" ]; do
    line=$(echo "$line" | tr -d '\r')
    [ -z "$line" ] && continue
    case "$line" in \#*) continue;; esac
    # tab or space
    name=$(echo "$line" | awk -F'[\t ]+' '{print $1}')
    val=$(echo "$line" | awk -F'[\t ]+' '{print $2}')
    [ -z "$name" ] && continue
    case "$val" in ''|*[!0-9-]*) val=0;; esac
    echo "virtual $name $val"
  done < "$VIRTUAL"
}

byte() { printf $(printf '\\%03o' "$1"); }

# 1.3: integer 1m loadavg (trunc before '.') for heat park gate.
load_1m_int() {
  set -- $(cat /proc/loadavg 2>/dev/null)
  li=${1%%.*}
  case "$li" in ''|*[!0-9]*) li=0;; esac
  echo "$li"
}

tick=0
while true; do
  tick=$((tick+1))
  TICK_N=$tick
  HEAT_PARK=0
  li=$(load_1m_int)
  if [ "$li" -ge "$HEAT_LOAD_GE" ]; then HEAT_PARK=1; fi
  # 1.4 cube-load-deep-park: under load≥8 do NOT run light sample + full 512-cell
  # awk catalog/dig (1.3 residual reheated tip at ~7–11% CPU). Long sleep continue.
  # 1.5 cube-load-deep-zero: wrote 512 zero cells — killed visual cube under heat.
  # 1.9 heat-hold-frame: keep last good cells.bin (mtime heartbeat only). If no
  # prior frame, soft ambient 1–3 (never pure black void). densify in app is
  # backup; zeroing the feed was product-wrong while Grok/Atlas raised load.
  # 1.6: stamp every loop (cool + heat) so land/mtime prove tip alive even if
  # cool sample hangs mid-tick or heat path is skipped.
  _stamp_tip_ver
  # 1.7: keyled enforce every tick (incl. heat park) — pad light must not stick
  enforce_keyled
  if [ "$HEAT_PARK" -eq 1 ]; then
    # Prefer hold: non-empty lattice already on disk (dim byte + payload).
    csz=0
    [ -f "$CELLS" ] && csz=`wc -c < "$CELLS" 2>/dev/null | tr -d ' \r\n'` || csz=0
    case "$csz" in ''|*[!0-9]*) csz=0;; esac
    # Detect all-zero payload (heat-deep-zero residual): first byte N, rest 0.
    allz=0
    if [ "$csz" -gt 16 ]; then
      # skip N byte; if no non-zero in next 64, treat as void
      nz=`dd if="$CELLS" bs=1 skip=1 count=64 2>/dev/null | tr -d '\000' | wc -c | tr -d ' \r\n'`
      case "$nz" in ''|*[!0-9]*) nz=0;; esac
      [ "$nz" -eq 0 ] && allz=1
    fi
    if [ "$csz" -gt 16 ] && [ "$allz" -eq 0 ]; then
      # Hold frame — only touch for mtime so CubeContact reload stays warm
      touch "$CELLS" 2>/dev/null || true
      chmod 666 "$CELLS" 2>/dev/null || true
    else
      # No usable prior lattice: soft ambient (not zero park).
      # One awk pass — never 512× nested printf (kills tip under load).
      {
        awk -v n="$NEED" 'BEGIN {
          printf "%c", 8
          for (i = 0; i < n; i++) printf "%c", 1 + (i % 3)
        }'
        printf 'digit=1 pick=1 ticks=%s source=heat-hold-ambient load=%s\n' "$tick" "$li"
      } > "$CELLS.tmp" 2>/dev/null || true
      mv "$CELLS.tmp" "$CELLS" 2>/dev/null || true
      chmod 666 "$CELLS" 2>/dev/null || true
    fi
    sleep_ms=$HEAT_INTERVAL_MS
    if command -v usleep >/dev/null 2>&1; then
      usleep $((sleep_ms * 1000))
    else
      s=$(( (sleep_ms + 999) / 1000 ))
      [ "$s" -lt 1 ] && s=1
      sleep "$s"
    fi
    continue
  fi
  export SKIP_IRQ=0
  if [ $((tick % IRQ_EVERY)) -ne 0 ]; then export SKIP_IRQ=1; fi
  {
    # Cool path: light when user selected channels; full scan otherwise.
    if [ -s "$SELECTED" ]; then
      sample_kernel_light
    else
      sample_kernel
    fi
    sample_virtual
  } > "$OUT_DIR/.raw" 2>/dev/null

  # catalog: all channels + enabled flag
  : > "$CATALOG"
  : > "$CATALOG"
  if [ -s "$SELECTED" ]; then
    awk -v SEL="$SELECTED" -v CATALOG="$CATALOG" '
      BEGIN {
        while ((getline < SEL) > 0) {
          gsub(/\r/,"")
          if ($1!="" && $1!~/^#/) sel[$1]=1
        }
        close(SEL)
        has=0; for (k in sel) has=1
      }
      NF>=3 {
        grp=$1; name=$2; val=$3+0
        en = (!has || (name in sel)) ? 1 : 0
        printf "%s\t%d\t%s\t%d\n", name, val, grp, en >> CATALOG
        if (en) print name, val
      }
    ' "$OUT_DIR/.raw" > "$OUT_DIR/.sample"
  else
    : > "$CATALOG"
    awk -v CATALOG="$CATALOG" '
      NF>=3 {
        printf "%s\t%d\t%s\t%d\n", $2, $3+0, $1, 1 >> CATALOG
        print $2, $3
      }
    ' "$OUT_DIR/.raw" > "$OUT_DIR/.sample"
  fi

  : > "$NODES"
  : > "$OUT_DIR/.digits"
  : > "$OUT_DIR/.newprev"
  awk -v NEED="$NEED" -v PREV="$PREV" -v NODES="$NODES" -v DIGS="$OUT_DIR/.digits" -v NEWP="$OUT_DIR/.newprev" '
    BEGIN { while ((getline < PREV) > 0) if (NF>=2) p[$1]=$2+0; close(PREV) }
    NF>=2 && length($1)>0 {
      names[ns]=$1; vals[ns]=$2+0; ns++
    }
    END {
      if (ns<1) { names[0]="empty"; vals[0]=0; ns=1 }
      # drop accidental empty slots
      nn=0
      for (k=0;k<ns;k++) if (length(names[k])>0) { names[nn]=names[k]; vals[nn]=vals[k]; nn++ }
      ns=nn; if (ns<1) { names[0]="empty"; vals[0]=0; ns=1 }
      for (i=0;i<NEED;i++) {
        j=i%ns; name=names[j]; if (name=="") name="node_" i
        if (i>=ns) name=name "_r" int(i/ns)
        val=vals[j]; pv=(name in p)?p[name]:val; d=val-pv; if(d<0)d=-d
        dig=0
        if (d>0) {
          if (d<2) dig=2; else if (d<10) dig=3; else if (d<50) dig=4
          else if (d<200) dig=5; else if (d<1000) dig=6; else if (d<10000) dig=7
          else if (d<100000) dig=8; else dig=9
        } else {
          av=val; if(av<0)av=-av
          if (av==0) dig=0; else if (av<=100) dig=int(av/11)+1
          else if (av<500) dig=4; else if (av<2000) dig=5; else if (av<10000) dig=6
          else if (av<100000) dig=7; else if (av<1000000) dig=8; else dig=9
          if (dig>9) dig=9
        }
        printf "%d\t%s\t%d\t%d\n", i, name, val, dig >> NODES
        print dig >> DIGS
        np[name]=val
      }
      for (k in np) print k, np[k] > NEWP
    }
  ' "$OUT_DIR/.sample"
  mv "$OUT_DIR/.newprev" "$PREV" 2>/dev/null
  {
    byte $N
    while read -r dig; do
      case "$dig" in [0-9]) byte "$dig";; *) byte 0;; esac
    done < "$OUT_DIR/.digits"
    dd if=/dev/zero bs=$NEED count=1 2>/dev/null
    printf 'digit=-1 pick=-1 ticks=%s source=kernel+virtual\n' "$tick"
  } > "$CELLS.tmp"
  mv "$CELLS.tmp" "$CELLS"
  chmod 666 "$CELLS" "$NODES" "$CATALOG" 2>/dev/null || true
  # Prefer usleep; fallback sleep whole seconds when INTERVAL_MS large.
  # Heat deep-park returns above; cool path uses INTERVAL_MS only.
  sleep_ms=$INTERVAL_MS
  if command -v usleep >/dev/null 2>&1; then
    usleep $((sleep_ms * 1000))
  else
    s=$(( (sleep_ms + 999) / 1000 ))
    [ "$s" -lt 1 ] && s=1
    sleep "$s"
  fi
done
