#!/system/bin/sh
# titan2-tether — own every tethering path (wifi / usb / ethernet).
# Calls titan2-fw for client policy. Does not install Debian NAT.
# Usage: titan2-tether.sh apply|stop|status|watch|prefix <cidr>
export PATH=/system/bin:/system/xbin:/vendor/bin:$PATH

T2=/data/misc/titan2
ST=/data/local/tmp/titan2-tether
DESIRE=$T2/vpn_hotspot_heal
PREFIX=$T2/tether.prefix
OUT=$ST/last.log
FW=/system/bin/titan2-fw
[ -x "$FW" ] || FW=/system/bin/titan2-fw.sh
[ -x "$FW" ] || FW=/data/local/tmp/titan2-fw.sh

log() { mkdir -p "$ST" 2>/dev/null; echo "tether: $*" >>"$OUT"; }

list_downstreams() {
  for n in ap_br_ap0 softap0 ap0 rndis0 usb0 usb1 eth0 eth1 wlan1 wlan2; do
    [ -d "/sys/class/net/$n" ] || continue
    ip -o -4 addr show dev "$n" 2>/dev/null | grep -q 'inet ' && echo "$n"
  done
}

find_tun() {
  for n in tun1 tun0 wg0 tailscale0; do
    [ -d "/sys/class/net/$n" ] || continue
    ip -o link show "$n" 2>/dev/null | grep -q UP && { echo "$n"; return 0; }
  done
  return 1
}

apply_one() {
  ifc=$1
  tun=$2
  gw=$(ip -o -4 addr show dev "$ifc" 2>/dev/null \
    | awk '{for(i=1;i<=NF;i++) if($i=="inet"){split($(i+1),a,"/"); print a[1]; exit}}')
  cidr=$(ip -o -4 addr show dev "$ifc" 2>/dev/null \
    | awk '{for(i=1;i<=NF;i++) if($i=="inet"){print $(i+1); exit}}')
  [ -n "$gw" ] || return 0
  net=$(echo "$cidr" | awk -F'[./]' '{print $1"."$2"."$3".0/"$5}')
  mtu=1280
  [ -n "$tun" ] && mtu=$(cat /sys/class/net/$tun/mtu 2>/dev/null)
  case "$mtu" in ''|*[!0-9]*) mtu=1280 ;; esac
  ip link set "$ifc" mtu "$mtu" 2>/dev/null || true
  echo 1 >/proc/sys/net/ipv4/ip_forward 2>/dev/null || true
  echo 0 >/proc/sys/net/ipv4/conf/"$ifc"/rp_filter 2>/dev/null || true
  [ -n "$tun" ] && echo 0 >/proc/sys/net/ipv4/conf/"$tun"/rp_filter 2>/dev/null || true
  if [ -n "$tun" ]; then
    iptables -C FORWARD -i "$ifc" -o "$tun" -j titan2_fw_fwd 2>/dev/null \
      || iptables -I FORWARD -i "$ifc" -o "$tun" -j ACCEPT 2>/dev/null || true
    iptables -t nat -C POSTROUTING -s "$net" -o "$tun" -j MASQUERADE 2>/dev/null \
      || iptables -t nat -I POSTROUTING -s "$net" -o "$tun" -j MASQUERADE 2>/dev/null || true
    iptables -t nat -C PREROUTING -i "$ifc" -p udp --dport 53 -j DNAT --to-destination 1.1.1.1:53 2>/dev/null \
      || iptables -t nat -I PREROUTING -i "$ifc" -p udp --dport 53 -j DNAT --to-destination 1.1.1.1:53 2>/dev/null || true
    ip rule del from "$net" lookup "$tun" pref 20900 2>/dev/null || true
    ip rule add from "$net" lookup "$tun" pref 20900 2>/dev/null || true
  fi
  log "apply ifc=$ifc gw=$gw net=$net tun=${tun:-none} mtu=$mtu"
  if [ -x "$FW" ]; then
    sh "$FW" prefix "$cidr" >/dev/null 2>&1 || true
    [ "$(cat $T2/fw.enabled 2>/dev/null)" = "on" ] && sh "$FW" apply >/dev/null 2>&1 || true
  fi
}

cmd_apply() {
  mkdir -p "$T2" "$ST" 2>/dev/null || true
  printf 1 >"$DESIRE" 2>/dev/null || true
  settings put global tethering_allow_vpn_upstreams 1 2>/dev/null || true
  settings put global tether_offload_disabled 1 2>/dev/null || true
  tun=$(find_tun || true)
  n=0
  for ifc in $(list_downstreams); do
    apply_one "$ifc" "$tun"
    n=$((n + 1))
  done
  echo "tether apply ifaces=$n tun=${tun:-none}"
}

cmd_stop() {
  printf 0 >"$DESIRE" 2>/dev/null || true
  echo "tether stop (desire=0; live NAT left until next apply)"
}

cmd_status() {
  echo "desire=$(cat $DESIRE 2>/dev/null | tr -d '\r\n')"
  echo "prefix=$(cat $PREFIX 2>/dev/null | tr -d '\r\n')"
  echo "downstreams=$(list_downstreams | tr '\n' ' ')"
  echo "tun=$(find_tun || echo none)"
  [ -x "$FW" ] && sh "$FW" client-list 2>/dev/null || true
}

cmd_watch() {
  while true; do
    d=$(cat "$DESIRE" 2>/dev/null | tr -d '\r\n ')
    case "$d" in 1|on|ON) cmd_apply ;; esac
    sleep 12
  done
}

cmd=${1:-status}
shift 2>/dev/null || true
case "$cmd" in
  apply|start|on) cmd_apply ;;
  stop|off) cmd_stop ;;
  status) cmd_status ;;
  watch) cmd_watch ;;
  prefix) [ -x "$FW" ] && exec sh "$FW" prefix "$@" ;;
  *) echo "usage: $0 apply|stop|status|watch|prefix" >&2; exit 2 ;;
esac
