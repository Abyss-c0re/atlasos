#!/system/bin/sh
# titan2-vpn-hotspot — optional VPN-over-hotspot heal (Tailscale / any TUN).
#
# Symptom (lab 2026-08-11): SoftAP upstream = tun0, MASQUERADE OK, but clients
# have no internet because nothing listens on gateway :53 (Android DNS proxy
# fails when upstream is TUN; BPF tc attach on tun0 also fails).
#
# apply:
#   - tethering_allow_vpn_upstreams=1
#   - tether_offload_disabled=1 (HAL offload cannot use TUN well)
#   - SoftAP MTU clamp to VPN MTU
#   - dnsmasq DNS-only on SoftAP gateway → MagicDNS + public resolvers
#   - SNAT SoftAP subnet out tun* (belt; tetherctrl may already MASQ)
#   - TCPMSS clamp for 1280 MTU tunnels
# stop: kill our dnsmasq + flush our iptables chains
#
# Usage: titan2-vpn-hotspot.sh apply|stop|status|watch
# Optional: TITAN2_VHN_DESIRE=1 written by Controls when toggle ON.
export PATH=/system/bin:/system/xbin:/vendor/bin:$PATH

ST=/data/local/tmp/titan2-vpn-hotspot
MISC=/data/misc/titan2
PIDF=$ST/dnsmasq.pid
LOG=$ST/dnsmasq.log
OUT=$ST/last.log
DESIRE=$MISC/vpn_hotspot_heal
STATUS=$ST/status

log() {
  mkdir -p "$ST" 2>/dev/null || true
  echo "vhn: $*" >>"$OUT" 2>/dev/null || true
  chmod 666 "$OUT" 2>/dev/null || true
}

find_softap() {
  # Prefer bridged SoftAP used by modern tethering
  for n in ap_br_ap0 softap0 ap0 wlan2 wlan1; do
    if [ -d "/sys/class/net/$n" ]; then
      ip -o -4 addr show dev "$n" 2>/dev/null | grep -q 'inet ' && {
        echo "$n"
        return 0
      }
    fi
  done
  # Fallback: any iface matching tether regex with 10/192.168 private
  ip -o -4 addr show 2>/dev/null | while read -r _ ifc rest; do
    case "$ifc" in
      ap_br_ap*|softap*|ap[0-9]*|wlan[1-9]*)
        echo "$ifc" | tr -d ':'
        return 0
        ;;
    esac
  done
  return 1
}

softap_ip() {
  dev=$1
  ip -o -4 addr show dev "$dev" 2>/dev/null \
    | awk '{for(i=1;i<=NF;i++) if($i=="inet"){split($(i+1),a,"/"); print a[1]; exit}}'
}

softap_cidr() {
  dev=$1
  ip -o -4 addr show dev "$dev" 2>/dev/null \
    | awk '{for(i=1;i<=NF;i++) if($i=="inet"){print $(i+1); exit}}'
}

cidr_to_net() {
  # rough: 10.238.58.119/24 → 10.238.58.0/24
  cidr=$1
  ip=${cidr%/*}
  pref=${cidr#*/}
  case "$pref" in
    24)
      echo "$ip" | awk -F. '{print $1"."$2"."$3".0/24"}'
      ;;
    16)
      echo "$ip" | awk -F. '{print $1"."$2".0.0/16"}'
      ;;
    *)
      echo "$cidr"
      ;;
  esac
}

find_tun() {
  for n in tun0 tun1 wg0 tailscale0; do
    [ -d "/sys/class/net/$n" ] || continue
    ip -o link show "$n" 2>/dev/null | grep -q UP && {
      echo "$n"
      return 0
    }
  done
  ip -o link show 2>/dev/null | awk -F': ' '$2 ~ /^(tun|wg)[0-9]+/ {print $2; exit}'
}

tun_mtu() {
  dev=$1
  [ -n "$dev" ] || { echo 1280; return; }
  m=$(cat "/sys/class/net/$dev/mtu" 2>/dev/null)
  case "$m" in
    ''|*[!0-9]*) echo 1280 ;;
    *) echo "$m" ;;
  esac
}

ensure_settings() {
  settings put global tethering_allow_vpn_upstreams 1 2>/dev/null || true
  settings put secure tethering_allow_vpn_upstreams 1 2>/dev/null || true
  # Hardware offload cannot steer SoftAP → TUN; leave kernel/iptables path.
  settings put global tether_offload_disabled 1 2>/dev/null || true
}

ensure_chains() {
  iptables -t nat -N titan2_vhn_POSTROUTING 2>/dev/null || true
  iptables -t nat -C POSTROUTING -j titan2_vhn_POSTROUTING 2>/dev/null \
    || iptables -t nat -I POSTROUTING -j titan2_vhn_POSTROUTING 2>/dev/null || true
  iptables -t nat -N titan2_vhn_PREROUTING 2>/dev/null || true
  iptables -t nat -C PREROUTING -j titan2_vhn_PREROUTING 2>/dev/null \
    || iptables -t nat -I PREROUTING -j titan2_vhn_PREROUTING 2>/dev/null || true
  iptables -t mangle -N titan2_vhn_FORWARD 2>/dev/null || true
  iptables -t mangle -C FORWARD -j titan2_vhn_FORWARD 2>/dev/null \
    || iptables -t mangle -I FORWARD -j titan2_vhn_FORWARD 2>/dev/null || true
  # Must sit in front of tetherctrl_FORWARD's terminal DROP.
  iptables -N titan2_vhn_fwd 2>/dev/null || true
  iptables -C FORWARD -j titan2_vhn_fwd 2>/dev/null \
    || iptables -I FORWARD -j titan2_vhn_fwd 2>/dev/null || true
  iptables -N titan2_vhn_INPUT 2>/dev/null || true
  iptables -C INPUT -j titan2_vhn_INPUT 2>/dev/null \
    || iptables -I INPUT -j titan2_vhn_INPUT 2>/dev/null || true
}

flush_chains() {
  iptables -t nat -F titan2_vhn_POSTROUTING 2>/dev/null || true
  iptables -t nat -F titan2_vhn_PREROUTING 2>/dev/null || true
  iptables -t mangle -F titan2_vhn_FORWARD 2>/dev/null || true
  iptables -F titan2_vhn_fwd 2>/dev/null || true
  iptables -F titan2_vhn_INPUT 2>/dev/null || true
}

stop_dns() {
  if [ -f "$PIDF" ]; then
    old=$(cat "$PIDF" 2>/dev/null | tr -d '\r\n ')
    case "$old" in
      ''|*[!0-9]*) ;;
      *)
        if [ -d "/proc/$old" ]; then
          kill "$old" 2>/dev/null || true
          sleep 0.2
          kill -9 "$old" 2>/dev/null || true
        fi
        ;;
    esac
    rm -f "$PIDF" 2>/dev/null || true
  fi
  # belt: any dnsmasq bound only to softap we started (pidfile missing)
  true
}

start_dns() {
  gw=$1
  ifc=$2
  command -v dnsmasq >/dev/null 2>&1 || {
    log "no dnsmasq binary"
    return 1
  }
  stop_dns
  mkdir -p "$ST" 2>/dev/null || true
  : >"$LOG" 2>/dev/null || true
  chmod 666 "$LOG" 2>/dev/null || true
  # DNS only — Android still owns DHCP/leases on SoftAP.
  # Do NOT use --bind-interfaces: that sources upstream queries from $gw,
  # and Android has no default in main for the SoftAP address (VPN is
  # policy-routed iif lo). Upstream then hits unreachable → silent DNS fail.
  dnsmasq \
    --pid-file="$PIDF" \
    --conf-file=/dev/null \
    --interface="$ifc" \
    --listen-address="$gw" \
    --except-interface=lo \
    --no-dhcp-interface=* \
    --port=53 \
    --server=100.100.100.100 \
    --server=1.1.1.1 \
    --server=8.8.8.8 \
    --no-resolv \
    --cache-size=1000 \
    --user=root \
    --group=root \
    --log-facility="$LOG" \
    --log-queries \
    2>>"$OUT" || {
      log "dnsmasq start failed"
      return 1
    }
  sleep 0.3
  if [ -f "$PIDF" ] && [ -d "/proc/$(cat "$PIDF" 2>/dev/null | tr -d '\r\n ')" ]; then
    log "dnsmasq ok pid=$(cat "$PIDF") listen=$gw@$ifc"
    return 0
  fi
  log "dnsmasq pid missing after start"
  return 1
}

apply_once() {
  ensure_settings
  ensure_chains
  echo 1 >/proc/sys/net/ipv4/ip_forward 2>/dev/null || true

  ifc=$(find_softap)
  tun=$(find_tun)
  mtu=$(tun_mtu "$tun")

  if [ -z "$ifc" ]; then
    log "no softap iface yet — settings only"
    printf 'state=armed softap=none tun=%s\n' "${tun:-none}" >"$STATUS"
    chmod 666 "$STATUS" 2>/dev/null || true
    return 0
  fi

  gw=$(softap_ip "$ifc")
  cidr=$(softap_cidr "$ifc")
  net=$(cidr_to_net "$cidr")
  if [ -z "$gw" ]; then
    log "softap $ifc has no ipv4 yet"
    printf 'state=armed softap=%s ip=none tun=%s\n' "$ifc" "${tun:-none}" >"$STATUS"
    chmod 666 "$STATUS" 2>/dev/null || true
    return 0
  fi

  # Android IpServer often assigns the prefix network address (.0). Clients
  # treat that as broadcast and never install a unicast gateway.
  case "$gw" in
    *.0)
      host=${gw%.*}.1
      ip addr del "$cidr" dev "$ifc" 2>/dev/null || true
      ip addr add "$host/24" broadcast "${gw%.*}.255" dev "$ifc" 2>/dev/null || true
      gw=$host
      cidr=$host/24
      log "moved softap $ifc off .0 → $gw"
      ;;
  esac

  # MTU: SoftAP 1500 + TUN 1280 → blackhole / unreplied SYN without clamp.
  ip link set "$ifc" mtu "$mtu" 2>/dev/null || true
  for leg in ap0 ap1 softap0; do
    [ -d "/sys/class/net/$leg" ] && ip link set "$leg" mtu "$mtu" 2>/dev/null || true
  done

  echo 0 >/proc/sys/net/ipv4/conf/all/rp_filter 2>/dev/null || true
  echo 0 >/proc/sys/net/ipv4/conf/default/rp_filter 2>/dev/null || true
  echo 0 >/proc/sys/net/ipv4/conf/"$ifc"/rp_filter 2>/dev/null || true
  echo 1 >/proc/sys/net/ipv4/conf/all/forwarding 2>/dev/null || true
  echo 1 >/proc/sys/net/ipv4/conf/"$ifc"/forwarding 2>/dev/null || true
  [ -n "$tun" ] && echo 0 >/proc/sys/net/ipv4/conf/"$tun"/rp_filter 2>/dev/null || true
  [ -n "$tun" ] && echo 1 >/proc/sys/net/ipv4/conf/"$tun"/forwarding 2>/dev/null || true
  # Android tether BPF (clsact) eats SoftAP frames before FORWARD. Strip it.
  tc qdisc del dev "$ifc" clsact 2>/dev/null || true
  for leg in ap0 ap1 softap0 wlan1 wlan2; do
    [ -d "/sys/class/net/$leg" ] && tc qdisc del dev "$leg" clsact 2>/dev/null || true
  done
  # SoftAP-sourced packets must use TUN. pref 80 beats Android fwmark 0 → ap_br table.
  # Replies arrive iif tun0 with src=internet; Android rule 12000 (iif tun0 →
  # local_network) has no LAN route, so they die as unreachable unless we
  # install a higher-pref return rule + LAN in both tables.
  if [ -n "$tun" ] && [ -n "$net" ]; then
    ip route replace "$net" dev "$ifc" table "$tun" 2>/dev/null || true
    ip route replace default dev "$tun" table "$tun" 2>/dev/null || true
    ip route replace "$net" dev "$ifc" table local_network 2>/dev/null || true
    ip rule del pref 70 2>/dev/null || true
    ip rule add iif "$tun" to "$net" lookup "$tun" pref 70 2>/dev/null || true
    ip rule del pref 80 2>/dev/null || true
    ip rule add from "$net" lookup "$tun" pref 80 2>/dev/null || true
    ip rule del from "$net" lookup "$tun" pref 20900 2>/dev/null || true
    ip rule add from "$net" lookup "$tun" pref 20900 2>/dev/null || true
  fi

  flush_chains
  if [ -n "$tun" ] && [ -n "$net" ]; then
    iptables -t nat -A titan2_vhn_POSTROUTING -s "$net" -o "$tun" -j MASQUERADE 2>/dev/null || true
    iptables -A titan2_vhn_fwd -i "$ifc" -o "$tun" -j ACCEPT 2>/dev/null || true
    iptables -A titan2_vhn_fwd -i "$tun" -o "$ifc" -m state --state RELATED,ESTABLISHED -j ACCEPT 2>/dev/null || true
  fi
  iptables -t mangle -A titan2_vhn_FORWARD -p tcp --tcp-flags SYN,RST SYN \
    -j TCPMSS --clamp-mss-to-pmtu 2>/dev/null || true
  iptables -A titan2_vhn_INPUT -i "$ifc" -p icmp -j ACCEPT 2>/dev/null || true
  iptables -A titan2_vhn_INPUT -i "$ifc" -p udp --dport 53 -j ACCEPT 2>/dev/null || true
  iptables -A titan2_vhn_INPUT -i "$ifc" -p tcp --dport 53 -j ACCEPT 2>/dev/null || true

  # Transparent DNS: rewrite SoftAP :53 to MagicDNS (then public) via TUN.
  # Local dnsmasq is a bonus; Android policy routing often makes it mute.
  stop_dns
  iptables -t nat -A titan2_vhn_PREROUTING -i "$ifc" -p udp --dport 53 \
    -j DNAT --to-destination 1.1.1.1:53 2>/dev/null || true
  iptables -t nat -A titan2_vhn_PREROUTING -i "$ifc" -p tcp --dport 53 \
    -j DNAT --to-destination 1.1.1.1:53 2>/dev/null || true
  dns=dnat
  if start_dns "$gw" "$ifc"; then
    dns=up
  fi
  printf 'state=active softap=%s gw=%s net=%s tun=%s mtu=%s dns=%s\n' \
    "$ifc" "$gw" "$net" "${tun:-none}" "$mtu" "$dns" >"$STATUS"
  chmod 666 "$STATUS" 2>/dev/null || true
  log "apply softap=$ifc gw=$gw tun=${tun:-none} mtu=$mtu"
  return 0
}

do_stop() {
  stop_dns
  flush_chains
  ip rule del pref 20900 2>/dev/null || true
  # Leave chains hooked empty so re-apply is cheap; optional full detach:
  # iptables -t nat -D POSTROUTING -j titan2_vhn_POSTROUTING
  printf 'state=stopped\n' >"$STATUS" 2>/dev/null || true
  chmod 666 "$STATUS" 2>/dev/null || true
  log "stop"
}

do_status() {
  desire=0
  [ -f "$DESIRE" ] && desire=$(cat "$DESIRE" 2>/dev/null | tr -d '\r\n ' | head -c 1)
  case "$desire" in 1|y|Y|on|ON) desire=1 ;; *) desire=0 ;; esac
  dns=down
  if [ -f "$PIDF" ]; then
    p=$(cat "$PIDF" 2>/dev/null | tr -d '\r\n ')
    [ -n "$p" ] && [ -d "/proc/$p" ] && dns=up
  fi
  ifc=$(find_softap 2>/dev/null || true)
  tun=$(find_tun 2>/dev/null || true)
  st=unknown
  [ -f "$STATUS" ] && st=$(cat "$STATUS" 2>/dev/null | head -1)
  echo "desire=$desire dns=$dns softap=${ifc:-none} tun=${tun:-none}"
  echo "$st"
  settings get global tethering_allow_vpn_upstreams 2>/dev/null | awk '{print "allow_vpn_upstreams="$0}'
  settings get global tether_offload_disabled 2>/dev/null | awk '{print "tether_offload_disabled="$0}'
}

# watch: re-apply while desire=1 (pad-agent / boot companion)
do_watch() {
  log "watch start"
  while true; do
    d=0
    [ -f "$DESIRE" ] && d=$(cat "$DESIRE" 2>/dev/null | tr -d '\r\n ' | head -c 1)
    case "$d" in
      1|y|Y|on|ON)
        apply_once
        ;;
      *)
        # desire off — do not thrash stop every loop; only once if dns up
        if [ -f "$PIDF" ]; then
          do_stop
        fi
        ;;
    esac
    sleep 12
  done
}

cmd=${1:-status}
case "$cmd" in
  apply|start|on)
    mkdir -p "$MISC" "$ST" 2>/dev/null || true
    printf '1' >"$DESIRE" 2>/dev/null || true
    chmod 666 "$DESIRE" 2>/dev/null || true
    apply_once
    ;;
  stop|off)
    mkdir -p "$MISC" "$ST" 2>/dev/null || true
    printf '0' >"$DESIRE" 2>/dev/null || true
    chmod 666 "$DESIRE" 2>/dev/null || true
    do_stop
    ;;
  status)
    do_status
    ;;
  watch)
    do_watch
    ;;
  apply-only)
    # honor desire; used by boot/watch
    d=0
    [ -f "$DESIRE" ] && d=$(cat "$DESIRE" 2>/dev/null | tr -d '\r\n ' | head -c 1)
    case "$d" in 1|y|Y|on|ON) apply_once ;; *) echo "desire off"; exit 0 ;; esac
    ;;
  *)
    echo "usage: $0 apply|stop|status|watch|apply-only" >&2
    exit 2
    ;;
esac
