#!/system/bin/sh
# titan2-openwrt — official OpenWrt userspace on atlas_openwrt LP.
# Radio stays Android SoftAP (ap0). OpenWrt owns 192.168.6.0/24:
#   address, DHCP, DNS, firewall, NAT.
# WAN:
#   under  — clients through Android VPN (tun0). Default. Tailscale allows 192.168.6.0/24.
#   above  — clients bypass VPN (wlan0 / ccmni).
# Usage: titan2-openwrt.sh status|mount|start|stop|under|above
export PATH=/system/bin:/system/xbin:/vendor/bin:/data/local/tmp:$PATH
MNT=/data/local/atlas-openwrt
LPCTL=/system/bin/openwrt-lpctl
[ -x "$LPCTL" ] || LPCTL=/data/local/tmp/openwrt-lpctl
MODEF=/data/misc/titan2/openwrt.wan
LAN=192.168.6.1
NET=192.168.6.0/24
UH_PID=/data/local/tmp/titan2-openwrt-uhttpd.pid
UB_PID=/data/local/tmp/titan2-openwrt-ubusd.pid
RP_PID=/data/local/tmp/titan2-openwrt-rpcd.pid
DNS_PID=/data/local/tmp/titan2-openwrt-dnsmasq.pid

log() { echo "openwrt: $*"; }

wan_mode() {
  m=$(cat "$MODEF" 2>/dev/null | tr -d '\r\n')
  case "$m" in above|under) echo "$m" ;; *) echo under ;; esac
}

find_ap() {
  for n in ap0 ap_br_ap0 softap0; do
    [ -d "/sys/class/net/$n" ] || continue
    echo "$n"
    return 0
  done
  return 1
}

find_tun() {
  for n in tun0 tun1 wg0; do
    [ -d "/sys/class/net/$n" ] && echo "$n" && return 0
  done
  return 1
}

find_uplink() {
  # non-VPN default: STA then cell
  for n in wlan0 ccmni2 ccmni1 ccmni0; do
    [ -d "/sys/class/net/$n" ] || continue
    ip -o -4 addr show dev "$n" 2>/dev/null | grep -q inet && { echo "$n"; return 0; }
  done
  return 1
}

do_mount() {
  mkdir -p "$MNT" /data/misc/titan2
  if [ -x "$LPCTL" ]; then
    "$LPCTL" mount || true
  fi
  if ! grep -q " $MNT " /proc/mounts; then
    # live mapper (4k remake) or packed image
    mount -t ext4 -o rw,noatime /dev/block/mapper/atlas_openwrt_a "$MNT" 2>/dev/null \
      || mount -t ext4 -o ro,noload /dev/block/mapper/atlas_openwrt_a "$MNT" 2>/dev/null \
      || { log "mount fail"; return 2; }
  fi
  # bind so musl chroot can talk to the kernel
  for d in proc sys dev; do
    mkdir -p "$MNT/$d"
    grep -q " $MNT/$d " /proc/mounts || mount --bind "/$d" "$MNT/$d" 2>/dev/null || true
  done
  mkdir -p "$MNT/tmp" "$MNT/var/run" "$MNT/var/log"
  mount -t tmpfs -o size=32M tmpfs "$MNT/tmp" 2>/dev/null || true
  [ -d "$MNT/bin" ] || { log "empty root $MNT"; return 3; }
  log "mounted $MNT"
  return 0
}

kill_pidf() {
  f=$1
  [ -f "$f" ] || return 0
  p=$(cat "$f" 2>/dev/null | tr -d '\r\n ')
  [ -n "$p" ] && [ -d "/proc/$p" ] && kill "$p" 2>/dev/null || true
  rm -f "$f"
}

stop_daemons() {
  kill_pidf "$UH_PID"
  kill_pidf "$RP_PID"
  kill_pidf "$UB_PID"
  kill_pidf "$DNS_PID"
  killall uhttpd ubusd rpcd 2>/dev/null || true
  # do not kill Android dnsmasq
}

start_daemons() {
  [ -x "$MNT/sbin/ubusd" ] || { log "no ubusd"; return 1; }
  if ! [ -f "$UB_PID" ] || ! [ -d "/proc/$(cat $UB_PID 2>/dev/null)" ]; then
    chroot "$MNT" /sbin/ubusd >/dev/null 2>&1 &
    echo $! >"$UB_PID"
  fi
  sleep 0.2
  if [ -x "$MNT/sbin/rpcd" ]; then
    chroot "$MNT" /sbin/rpcd >/dev/null 2>&1 &
    echo $! >"$RP_PID"
  fi
  UH=$MNT/usr/sbin/uhttpd
  [ -x "$UH" ] || UH=$MNT/sbin/uhttpd
  if [ -x "$UH" ]; then
    # loopback for the wrapper; LAN :80 for clients
    chroot "$MNT" /usr/sbin/uhttpd -f \
      -p 127.0.0.1:8080 -p 192.168.6.1:80 \
      -h /www -x /cgi-bin -I index.html \
      >/data/local/tmp/titan2-openwrt-uhttpd.log 2>&1 &
    echo $! >"$UH_PID"
    log "uhttpd pid=$(cat $UH_PID)"
  else
    log "no uhttpd"
    return 1
  fi
  return 0
}

apply_lan() {
  ap=$(find_ap) || { log "no SoftAP yet — radio is Android, start hotspot"; return 0; }
  echo 1 >/proc/sys/net/ipv4/ip_forward
  # OpenWrt is the only host on 192.168.6.1 — drop Android prefix-network .0
  ip addr del 192.168.6.0/24 dev "$ap" 2>/dev/null || true
  ip addr del 192.168.6.0/32 dev "$ap" 2>/dev/null || true
  ip addr add "$LAN/24" broadcast 192.168.6.255 dev "$ap" 2>/dev/null || true
  ip link set "$ap" mtu 1280 2>/dev/null || true
  echo 0 >/proc/sys/net/ipv4/conf/"$ap"/rp_filter 2>/dev/null || true
  echo 1 >/proc/sys/net/ipv4/conf/"$ap"/forwarding 2>/dev/null || true
  tc qdisc del dev "$ap" clsact 2>/dev/null || true

  iptables -N titan2_ow_fwd 2>/dev/null || true
  iptables -C FORWARD -j titan2_ow_fwd 2>/dev/null || iptables -I FORWARD -j titan2_ow_fwd
  iptables -t nat -N titan2_ow_nat 2>/dev/null || true
  iptables -t nat -C POSTROUTING -j titan2_ow_nat 2>/dev/null || iptables -t nat -I POSTROUTING -j titan2_ow_nat
  iptables -F titan2_ow_fwd 2>/dev/null || true
  iptables -t nat -F titan2_ow_nat 2>/dev/null || true

  mode=$(wan_mode)
  tun=$(find_tun || true)
  up=$(find_uplink || true)
  ip rule del pref 70 2>/dev/null || true
  ip rule del pref 80 2>/dev/null || true
  if [ "$mode" = "above" ] && [ -n "$up" ]; then
    iptables -A titan2_ow_fwd -i "$ap" -o "$up" -j ACCEPT
    iptables -A titan2_ow_fwd -i "$up" -o "$ap" -m state --state RELATED,ESTABLISHED -j ACCEPT
    iptables -t nat -A titan2_ow_nat -s "$NET" -o "$up" -j MASQUERADE
    log "wan=above via $up"
  else
    if [ -n "$tun" ]; then
      echo 0 >/proc/sys/net/ipv4/conf/"$tun"/rp_filter 2>/dev/null || true
      echo 1 >/proc/sys/net/ipv4/conf/"$tun"/forwarding 2>/dev/null || true
      ip route replace "$NET" dev "$ap" table "$tun" 2>/dev/null || true
      ip route replace default dev "$tun" table "$tun" 2>/dev/null || true
      ip route replace "$NET" dev "$ap" table local_network 2>/dev/null || true
      ip rule add iif "$tun" to "$NET" lookup "$tun" pref 70 2>/dev/null || true
      ip rule add from "$NET" lookup "$tun" pref 80 2>/dev/null || true
      iptables -A titan2_ow_fwd -i "$ap" -o "$tun" -j ACCEPT
      iptables -A titan2_ow_fwd -i "$tun" -o "$ap" -m state --state RELATED,ESTABLISHED -j ACCEPT
      iptables -t nat -A titan2_ow_nat -s "$NET" -o "$tun" -j MASQUERADE
      log "wan=under via $tun (Tailscale $NET)"
    elif [ -n "$up" ]; then
      iptables -A titan2_ow_fwd -i "$ap" -o "$up" -j ACCEPT
      iptables -A titan2_ow_fwd -i "$up" -o "$ap" -m state --state RELATED,ESTABLISHED -j ACCEPT
      iptables -t nat -A titan2_ow_nat -s "$NET" -o "$up" -j MASQUERADE
      log "wan=under but no tun — fell back $up"
    else
      log "no WAN iface"
    fi
  fi

  # OpenWrt dnsmasq: DHCP + DNS for 192.168.6.0/24 (Android DHCP off if we win :67)
  kill_pidf "$DNS_PID"
  if [ -x "$MNT/usr/sbin/dnsmasq" ]; then
    iptables -I INPUT -i "$ap" -p udp --dport 67 -j DROP 2>/dev/null || true
    iptables -I OUTPUT -o "$ap" -p udp --sport 67 -j DROP 2>/dev/null || true
    chroot "$MNT" /usr/sbin/dnsmasq --conf-file=/dev/null \
      --pid-file=/tmp/dnsmasq.pid \
      --interface="$ap" --listen-address="$LAN" --except-interface=lo \
      --dhcp-range=192.168.6.100,192.168.6.200,255.255.255.0,1h \
      --dhcp-option=3,"$LAN" --dhcp-option=6,"$LAN" \
      --port=53 --server=1.1.1.1 --server=8.8.8.8 --no-resolv \
      --dhcp-authoritative --cache-size=200 \
      --log-facility=/tmp/dnsmasq.log >/dev/null 2>&1 &
    echo $! >"$DNS_PID"
  fi
}

do_start() {
  mkdir -p /data/misc/titan2
  [ -f "$MODEF" ] || printf 'under' >"$MODEF"
  do_mount || return $?
  start_daemons || true
  apply_lan
  do_status
}

do_stop() {
  stop_daemons
  iptables -F titan2_ow_fwd 2>/dev/null || true
  iptables -t nat -F titan2_ow_nat 2>/dev/null || true
  log "stopped daemons (LP stays mounted)"
}

do_status() {
  ap=$(find_ap || echo none)
  tun=$(find_tun || echo none)
  up=$(find_uplink || echo none)
  echo "lp=$(grep -q " $MNT " /proc/mounts && echo mounted || echo unmounted)"
  echo "mnt=$MNT"
  echo "has_root=$([ -d $MNT/bin ] && echo 1 || echo 0)"
  echo "has_luci=$([ -x $MNT/www/cgi-bin/luci ] && echo 1 || echo 0)"
  echo "wan=$(wan_mode)"
  echo "ap=$ap tun=$tun uplink=$up lan=$LAN net=$NET"
  echo "uhttpd=$([ -f $UH_PID ] && [ -d /proc/$(cat $UH_PID 2>/dev/null) ] && echo up || echo down)"
  [ -x "$LPCTL" ] && "$LPCTL" status 2>/dev/null || true
}

cmd=${1:-status}
case "$cmd" in
  mount) do_mount ;;
  start|apply) do_start ;;
  stop) do_stop ;;
  under) printf 'under' >"$MODEF"; apply_lan; do_status ;;
  above) printf 'above' >"$MODEF"; apply_lan; do_status ;;
  status|*) do_status ;;
esac
