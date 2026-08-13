#!/system/bin/sh
# Boot offline Nanobot stack (Wi‑Fi not required):
#   llama-server (Gemma 3 270M) + nanobot peer on 127.0.0.1
# Runs as root from init. Idempotent.

export PATH=/system/bin:/system/xbin:/vendor/bin:$PATH
LOG=/data/local/tmp/titan2_offline_nanobot.log
HOME_DIR=/data/local/tmp/nanobot_home
MODEL_DIR=/data/local/tmp/nanobot_models
MODEL_NAME=gemma-3-270m-it-Q4_K_M.gguf
SYS_MODEL=/system/etc/titan2/models/$MODEL_NAME
ENG_DIR=/data/local/tmp/llama.cpp
SYS_ENG_BIN=/system/bin/llama-server
SYS_ENG_LIB=/system/lib64/llama-cpp

log() { echo "$(date +%Y%m%dT%H%M%S) $*" >>"$LOG" 2>/dev/null; chmod 666 "$LOG" 2>/dev/null; }

# Peer listen SoT (Magisk nanobot service 1.4 / ensure_nanobot_peer): port
# first, health second — health-only raced cold peer after 1.18 single poke.
peer_listening() {
  if ss -lntp 2>/dev/null | grep -q ':8787'; then return 0; fi
  if netstat -ltn 2>/dev/null | grep -q ':8787'; then return 0; fi
  if curl -sS --max-time 1 http://127.0.0.1:8787/peer/v1/health 2>/dev/null \
    | grep -qiE 'ok|nanobot'; then return 0; fi
  return 1
}

# 1.19 residual (after CubeContact 1.18): hybrid offline single-poke after
# llama wait only — peer late or health not yet ok left dual virtual.tsv seed
# zeros until Sensors/GL. Magisk late_start already multi-pass; match that SoT.
# 1.20 residual: 30s belt ended before promote tail (90/180) — empty process
# residual; Magisk nanobot 1.5 + CubeContact LawPromoteService SoT.
# 1.21 residual: 180s belt ended mid-tail (~325s wave) — Magisk 1.6 + sticky.
poke_cube_law_pull() {
  am broadcast -a com.titanus2.cubecontact.LAW_PULL -p com.titanus2.cubecontact \
    >/dev/null 2>&1 || true
  log "poked CubeContact LAW_PULL"
}

poke_law_belt() {
  # 1.21 SoT: 0 / 5 / 30 / 90 / 180 / 330s (covers full promote wave end).
  (
    poke_cube_law_pull
    sleep 5
    poke_cube_law_pull
    sleep 25
    poke_cube_law_pull
    sleep 60
    poke_cube_law_pull
    sleep 90
    poke_cube_law_pull
    sleep 150
    poke_cube_law_pull
  ) &
}

mkdir -p "$HOME_DIR" "$MODEL_DIR" "$ENG_DIR" 2>/dev/null
chmod 777 "$HOME_DIR" "$MODEL_DIR" 2>/dev/null

# Stage model from system image if needed
if [ ! -s "$MODEL_DIR/$MODEL_NAME" ] && [ -s "$SYS_MODEL" ]; then
  cp -f "$SYS_MODEL" "$MODEL_DIR/$MODEL_NAME" 2>/dev/null && log "staged model from system"
fi
MODEL="$MODEL_DIR/$MODEL_NAME"
[ -s "$MODEL" ] || { log "no model $MODEL — skip offline"; exit 0; }

# Engine: prefer tmp (lab opt), then system
BIN=""
LIB=""
if [ -x "$ENG_DIR/llama-server" ] && [ -f "$ENG_DIR/libllama.so" ]; then
  BIN=$ENG_DIR/llama-server
  LIB=$ENG_DIR
elif [ -x "$SYS_ENG_BIN" ]; then
  BIN=$SYS_ENG_BIN
  LIB=$SYS_ENG_LIB
fi
[ -n "$BIN" ] || { log "no llama-server — skip"; exit 0; }

NB=""
for c in /data/local/tmp/nanobot /system/bin/nanobot; do
  [ -x "$c" ] && NB=$c && break
done
[ -n "$NB" ] || { log "no nanobot binary — skip peer"; NB=""; }

# Already healthy? Never kill a live server (reboot race was exiting mid-load).
if curl -sS --max-time 1 http://127.0.0.1:8080/health 2>/dev/null | grep -qi ok; then
  log "llama already up"
else
  # Only kill if not listening — avoid thrash during model load (503 Loading)
  if ! curl -sS --max-time 1 http://127.0.0.1:8080/health 2>/dev/null | grep -qi loading; then
    for p in $(pidof llama-server 2>/dev/null); do kill -9 $p 2>/dev/null; done
    sleep 0.3
  fi
  if ! pidof llama-server >/dev/null 2>&1; then
    (
      cd "$LIB" 2>/dev/null || true
      export LD_LIBRARY_PATH="$LIB${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
      # nohup + setsid so init oneshot exit does not tear down the server
      # Lean Titan flags: 1 slot, 512 ctx, small batch → ~2s cold load for Gemma 270M
      nohup setsid "$BIN" -m "$MODEL" --host 127.0.0.1 --port 8080 \
        -c 1024 -ngl 0 -np 1 \
        --threads 4 --threads-batch 4 \
        --cpu-mask f0 --cpu-strict 1 --prio 1 \
        --flash-attn on -b 256 -ub 128 -ctk q8_0 -ctv q8_0 \
        >>/data/local/tmp/llama-server.out 2>&1 < /dev/null &
    )
    log "started llama-server model=$MODEL_NAME"
  else
    log "llama loading (pid alive) — wait"
  fi
fi

# 1.9 residual (after Magisk/product peer 1.8): this script always rewrote
# nanobot_home/env to local llama *before* peer_listening check. Product
# titan2-nanobot.rc seeds Remote/Grok on boot_completed; offline oneshot
# then clobbered env under a live product peer → Grok sign-in dead until
# force-kill (same class as Magisk --offline thrash). Only write offline
# env when *we* start/own the peer; if product peer already owns :8787,
# leave env alone (llama may still run for optional Local without backend steal).
if [ -n "$NB" ]; then
  if peer_listening; then
    log "peer already up — leave product env (no offline env clobber)"
  else
    # Offline env for peer we start (Tools OFF for Gemma 270M tool_calls thrash)
    printf "NANOBOT_BASE_URL=http://127.0.0.1:8080/v1\nNANOBOT_MODEL=%s\nNANOBOT_TOOLS=0\n" "$MODEL" \
      >"$HOME_DIR/env" 2>/dev/null
    chmod 666 "$HOME_DIR/env" 2>/dev/null
    for p in $(pidof nanobot 2>/dev/null); do kill -9 $p 2>/dev/null; done
    NANOBOT_HOME=$HOME_DIR HOME=$HOME_DIR \
      "$NB" --port 8787 --home "$HOME_DIR" >>"$HOME_DIR/nanobot.out" 2>&1 &
    log "started nanobot peer offline (env local only after start own)"
  fi
fi

# Wait briefly for llama health
i=0
while [ $i -lt 60 ]; do
  curl -sS --max-time 1 http://127.0.0.1:8080/health 2>/dev/null | grep -qi ok && break
  i=$((i+1)); sleep 1
done
log "done llama=$(curl -sS --max-time 1 http://127.0.0.1:8080/health 2>/dev/null | tr -d '\n') peer_port=8787"

# 1.18 residual: hybrid offline boot started peer with no LAW_PULL.
# 1.19 residual: 1.18 single health-check poke raced peer cold-start (llama
# wait only; health not listen) — dual virtual.tsv stayed seed when :8787
# was late. Magisk service 1.4 SoT = listen wait + multi-pass belt.
if [ -n "$NB" ]; then
  ok_peer=0
  j=0
  while [ $j -lt 20 ]; do
    if peer_listening; then ok_peer=1; break; fi
    j=$((j+1)); sleep 0.4
  done
  if [ "$ok_peer" = "1" ]; then
    log "LISTEN ok — poke LAW belt"
    poke_law_belt
  else
    log "WARN :8787 not listening after start — no LAW poke"
  fi
fi
exit 0
