#!/system/bin/sh
# Boot Nanobot product peer belt. LAW_PULL only.
#
# LAW: GGUF / llama-server are never ROM. User models live on userdata
# (Nanobot app download). This oneshot must not copy or start a GGUF.
# Product peer is titan2-nanobot.rc. We only poke Cube law if :8787 is up.

export PATH=/system/bin:/system/xbin:/vendor/bin:$PATH
LOG=/data/local/tmp/titan2_offline_nanobot.log
HOME_DIR=/data/local/tmp/nanobot_home

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

mkdir -p "$HOME_DIR" 2>/dev/null
chmod 777 "$HOME_DIR" 2>/dev/null

# Never start llama or seed a GGUF. Product peer owns :8787.
if peer_listening; then
  log "peer already up — leave product env (no offline env clobber)"
else
  log "peer not up — product titan2-nanobot.rc owns start; no GGUF fallback"
fi

# 1.18 residual: hybrid offline boot started peer with no LAW_PULL.
# 1.19 residual: 1.18 single health-check poke raced peer cold-start
# (health not listen) — dual virtual.tsv stayed seed when :8787 was late.
# Magisk service 1.4 SoT = listen wait + multi-pass belt.
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
  log "WARN :8787 not listening — no LAW poke"
fi
exit 0
