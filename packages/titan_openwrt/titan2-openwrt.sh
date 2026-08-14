#!/system/bin/sh
# titan2-openwrt — official OpenWrt userspace on atlas_openwrt LP.
# Android is WAN. OpenWrt filters, then loops traffic back to LAN clients.
#   WAN under — Android VPN tun0 (default). Tailscale allows 192.168.6.0/24.
#   WAN above — Android STA/cell, bypass VPN.
#   LAN       — one subnet for all modes: 192.168.6.1/24
#               Wi-Fi SoftAP + USB rndis/ncm + Ethernet + bt-pan on br-owrt.
# Usage: titan2-openwrt.sh status|mount|start|stop|under|above
export PATH=/system/bin:/system/xbin:/vendor/bin:$PATH
MNT=/data/local/atlas-openwrt
LPCTL=/system/bin/openwrt-lpctl
# WAN mode + UCI live on the LP (survive wipe). /data is mountpoints + logs only.
MODEF=$MNT/etc/atlas-openwrt.wan
LAN=192.168.6.1
NET=192.168.6.0/24
BR=br-owrt
NS_UID=1073
LOGDIR=/data/local/tmp
UH_PID=$LOGDIR/titan2-openwrt-uhttpd.pid
UB_PID=$LOGDIR/titan2-openwrt-ubusd.pid
RP_PID=$LOGDIR/titan2-openwrt-rpcd.pid
DNS_PID=$LOGDIR/titan2-openwrt-dnsmasq.pid
NF_PID=$LOGDIR/titan2-openwrt-netifd.pid
AP_PID=$LOGDIR/titan2-openwrt-applyd.pid
FILES=/system/etc/titan-openwrt
[ -d "$FILES/etc/config" ] || FILES=/system/etc/atlas/openwrt

log() { echo "openwrt: $*"; }

wan_mode() {
  m=$(cat "$MODEF" 2>/dev/null | tr -d '\r\n')
  case "$m" in above|under) echo "$m" ;; *) echo under ;; esac
}

# Every Android tether downstream. Same 192.168.6.0/24 unless overridden.
find_downstreams() {
  ip -o link show 2>/dev/null | awk -F': ' '{print $2}' | tr -d '@' | while read -r n; do
    n=${n%%@*}
    case "$n" in
      ap0|ap_br_ap0|softap0|rndis*|ncm*|usb[0-9]*|eth*|en*|bt-pan|bt-pan*)
        [ -d "/sys/class/net/$n" ] && echo "$n"
        ;;
    esac
  done
}

find_ap() {
  # first downstream (status); LAN iface is $BR when present
  if [ -d "/sys/class/net/$BR" ]; then
    echo "$BR"
    return 0
  fi
  find_downstreams | head -1
}

ensure_lan_bridge() {
  # Prefer Android SoftAP bridge (ap_br_ap0). Do not build a second wifi bridge.
  if [ -d /sys/class/net/ap_br_ap0 ]; then
    BR=ap_br_ap0
    # drop our extra bridge if it exists
    if [ -d /sys/class/net/br-owrt ]; then
      ip link set br-owrt down 2>/dev/null || true
      ip link del br-owrt 2>/dev/null || true
    fi
  elif [ ! -d "/sys/class/net/$BR" ]; then
    ip link add "$BR" type bridge 2>/dev/null || {
      log "no bridge — LAN on first downstream"
      return 1
    }
  fi
  ip link set "$BR" up 2>/dev/null || true
  for n in $(find_downstreams); do
    [ "$n" = "$BR" ] && continue
    case "$n" in
      ap0|ap1|softap0) continue ;; # already in ap_br_ap0
    esac
    ip addr flush dev "$n" 2>/dev/null || true
    ip link set "$n" master "$BR" 2>/dev/null || continue
    ip link set "$n" up 2>/dev/null || true
    log "lan member $n -> $BR"
  done
  return 0
}

find_tun() {
  # iface present is not WAN. Tailscale often leaves a nameless tun0.
  for n in tun0 tun1 wg0; do
    [ -d "/sys/class/net/$n" ] || continue
    ip -o -4 addr show dev "$n" 2>/dev/null | grep -q inet || continue
    echo "$n"
    return 0
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
  mkdir -p "$MNT/tmp" "$MNT/var/run/ubus" "$MNT/var/log" "$MNT/var/lock"
  mount -t tmpfs -o size=32M tmpfs "$MNT/tmp" 2>/dev/null || true
  mkdir -p "$MNT/tmp/run" "$MNT/tmp/lock"
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
  kill_pidf "$NF_PID"
  kill_pidf "$AP_PID"
  kill_pidf "$UB_PID"
  kill_pidf "$DNS_PID"
  killall uhttpd ubusd rpcd netifd 2>/dev/null || true
  # do not kill Android dnsmasq
}

# LuCI writes UCI. This copies the control plane onto the LP and
# points init.d at a hook the host applyd executes as Android iptables.
seed_plane() {
  mkdir -p "$MNT/etc/config" "$MNT/usr/libexec/rpcd" "$MNT/tmp"
  if [ ! -f "$MNT/etc/config/network" ]; then
    if [ -f "$FILES/etc/config/network" ]; then
      cp "$FILES/etc/config/network" "$MNT/etc/config/network"
    else
      cat >"$MNT/etc/config/network" <<'EOF'
config interface 'loopback'
	option device 'lo'
	option proto 'static'
	option ipaddr '127.0.0.1'
	option netmask '255.0.0.0'

config interface 'lan'
	option device 'ap_br_ap0'
	option proto 'static'
	option ipaddr '192.168.6.1'
	option netmask '255.255.255.0'

config interface 'wan'
	option device 'tun0'
	option proto 'none'
	option metric '10'

config interface 'wan6'
	option device 'tun0'
	option proto 'none'
EOF
    fi
  fi
  if [ ! -f "$MNT/etc/config/system" ]; then
    if [ -f "$FILES/etc/config/system" ]; then
      cp "$FILES/etc/config/system" "$MNT/etc/config/system"
    else
      cat >"$MNT/etc/config/system" <<'EOF'
config system
	option hostname 'BlackCube'
	option timezone 'UTC'
EOF
    fi
  fi
  if [ -f "$FILES/etc/board.json" ]; then
    cp "$FILES/etc/board.json" "$MNT/etc/board.json"
  elif [ ! -f "$MNT/etc/board.json" ]; then
    printf '%s\n' '{"model":{"id":"unihertz,titan2","name":"Unihertz Titan 2"}}' >"$MNT/etc/board.json"
  fi
  if [ -f "$FILES/usr/libexec/rpcd/system" ]; then
    cp "$FILES/usr/libexec/rpcd/system" "$MNT/usr/libexec/rpcd/system"
    chmod 755 "$MNT/usr/libexec/rpcd/system"
  fi
  # Replace procd-backed init with a hook LuCI Save & Apply can run in-chroot.
  for s in network firewall dnsmasq; do
    if [ -f "$MNT/etc/init.d/$s" ] && [ ! -f "$MNT/etc/init.d/$s.procd" ]; then
      mv "$MNT/etc/init.d/$s" "$MNT/etc/init.d/$s.procd"
    fi
    cat >"$MNT/etc/init.d/$s" <<'EOF'
#!/bin/sh
case "$1" in
  start|restart|reload|boot)
    if [ -x /sbin/netifd ]; then
      if ! pidof netifd >/dev/null 2>&1; then
        /sbin/netifd >/tmp/netifd.log 2>&1 &
        sleep 0.3
      else
        ubus call network reload >/dev/null 2>&1 || true
      fi
    fi
    touch /tmp/need-apply
    ;;
esac
exit 0
EOF
    chmod 755 "$MNT/etc/init.d/$s"
  done
  echo BlackCube >"$MNT/etc/hostname"
  echo BlackCube >/proc/sys/kernel/hostname 2>/dev/null || true
  # Empty root only when the LP has never been inited and root is locked.
  # A real password on the LP must survive wipe and reboot.
  if [ ! -f "$MNT/etc/atlas-openwrt-inited" ]; then
    rootpw=$(awk -F: '/^root:/{print $2}' "$MNT/etc/shadow" 2>/dev/null)
    case "$rootpw" in
      ''|'!'|'*'|'x') chroot "$MNT" /bin/busybox passwd -d root >/dev/null 2>&1 || true ;;
    esac
    touch "$MNT/etc/atlas-openwrt-inited"
  fi
}

uci_get() {
  chroot "$MNT" /sbin/uci -q get "$1" 2>/dev/null
}

# One SoT: LuCI network.lan → LAN/NET/BCAST → Android tether pin.
# .0 is the network id, never a host. Default only if UCI is empty.
load_lan() {
  LAN=192.168.6.1
  PFX=24
  ipa=$(uci_get network.lan.ipaddr 2>/dev/null || true)
  nm=$(uci_get network.lan.netmask 2>/dev/null || true)
  pin=$(getprop persist.sys.titan2.tether_ipv4 2>/dev/null || true)
  if [ -n "$ipa" ]; then
    LAN=$ipa
  elif echo "$pin" | grep -q /; then
    LAN=${pin%%/*}
    t=${pin##*/}
    [ -n "$t" ] && PFX=$t
  fi
  case "$nm" in
    255.255.255.0|"") PFX=24 ;;
    255.255.0.0) PFX=16 ;;
    255.0.0.0) PFX=8 ;;
  esac
  head=${LAN%.*}
  last=${LAN##*.}
  if [ "$last" = "0" ]; then
    LAN=${head}.1
    log "lan host was .0 — using $LAN"
  fi
  NET=${head}.0/${PFX}
  BCAST=${head}.255
  setprop persist.sys.titan2.tether_ipv4 "$LAN/$PFX" 2>/dev/null || true
}

# Strip the network-id address Android may still plant.
ban_netaddr() {
  load_lan
  netip=${NET%%/*}
  for n in $(ip -o link show 2>/dev/null | awk -F': ' '{print $2}' | tr -d '@'); do
    n=${n%%@*}
    ip addr del "$netip/$PFX" dev "$n" 2>/dev/null || true
    ip addr del "$netip/32" dev "$n" 2>/dev/null || true
  done
}

ensure_router_ip() {
  load_lan
  ban_netaddr
  ap=$(find_ap || true)
  [ -n "$ap" ] && [ -d "/sys/class/net/$ap" ] || return 0
  ip addr add "$LAN/$PFX" broadcast "$BCAST" dev "$ap" 2>/dev/null || true
  ban_netaddr
}

start_applyd() {
  if [ -f "$AP_PID" ] && [ -d "/proc/$(cat "$AP_PID" 2>/dev/null)" ]; then
    return 0
  fi
  (
    last=""
    last_ds=""
    while [ -d "$MNT/etc/config" ]; do
      load_lan
      ensure_router_ip
      ds=$(find_downstreams | tr '\n' ',')
      now=$(cat "$MNT/etc/config/network" "$MNT/etc/config/firewall" "$MNT/etc/config/dhcp" "$MNT/tmp/need-apply" 2>/dev/null | md5sum)
      sync_leases
      if [ "$ds" != "$last_ds" ] || [ "$now" != "$last" ]; then
        last=$now
        last_ds=$ds
        apply_lan
      fi
      sleep 2
    done
  ) >"$LOGDIR/titan2-openwrt-applyd.log" 2>&1 &
  echo $! >"$AP_PID"
  disown 2>/dev/null || true
}

start_daemons() {
  [ -x "$MNT/sbin/ubusd" ] || { log "no ubusd"; return 1; }
  # /var -> /tmp. Unix socket must live on that tmpfs, not the ext4 LP.
  mkdir -p "$MNT/tmp/run/ubus" "$MNT/tmp/lock" "$MNT/tmp/log"
  SOCK="$MNT/var/run/ubus/ubus.sock"
  if [ ! -S "$SOCK" ]; then
    killall ubusd 2>/dev/null || true
    chroot "$MNT" /sbin/ubusd -s /var/run/ubus/ubus.sock \
      >$LOGDIR/titan2-openwrt-ubusd.log 2>&1 &
    echo $! >"$UB_PID"
    sleep 0.4
  fi
  if [ ! -S "$SOCK" ]; then
    log "ubus socket missing after ubusd"
    return 1
  fi
  # LuCI 500 = rpcd off the ubus socket (only netifd objects left).
  if ! chroot "$MNT" /bin/ubus -s /var/run/ubus/ubus.sock list 2>/dev/null | grep -q '^session$'; then
    killall rpcd 2>/dev/null || true
    chroot "$MNT" /sbin/rpcd -s /var/run/ubus/ubus.sock \
      >$LOGDIR/titan2-openwrt-rpcd.log 2>&1 &
    echo $! >"$RP_PID"
    sleep 0.4
  fi
  if [ -x "$MNT/sbin/netifd" ] && ! pgrep -x netifd >/dev/null 2>&1; then
    chroot "$MNT" /sbin/netifd >$LOGDIR/titan2-openwrt-netifd.log 2>&1 &
    echo $! >"$NF_PID"
    sleep 0.4
  fi
  UHBIN=/usr/sbin/uhttpd
  [ -x "$MNT$UHBIN" ] || UHBIN=/sbin/uhttpd
  if [ ! -x "$MNT$UHBIN" ]; then
    log "no uhttpd"
    return 1
  fi
  if ! pgrep -x uhttpd >/dev/null 2>&1; then
    # Loopback first. Binding LAN:80 before 192.168.6.1 exists kills -f
    # (bind Address not available → whole uhttpd exits, :8080 dies too).
    UH_PORTS="-p 127.0.0.1:8080"
    if ip -4 addr show 2>/dev/null | grep -q "inet ${LAN}/"; then
      UH_PORTS="$UH_PORTS -p ${LAN}:80"
    fi
    # setsid: init oneshot exit must not SIGHUP the daemon.
    setsid chroot "$MNT" "$UHBIN" -f \
      $UH_PORTS \
      -h /www -x /cgi-bin -I index.html -t 20 \
      >"$LOGDIR/titan2-openwrt-uhttpd.log" 2>&1 &
    echo $! >"$UH_PID"
    disown 2>/dev/null || true
  else
    pgrep -x uhttpd | head -1 >"$UH_PID"
  fi
  log "uhttpd pid=$(cat $UH_PID 2>/dev/null) ubus=$SOCK"
  return 0
}

apply_lan() {
  load_lan
  ban_netaddr
  ds=$(find_downstreams | tr '\n' ' ')
  if [ -z "$ds" ]; then
    log "no tether downstream yet (start Wi-Fi/USB/Ethernet tethering)"
    return 0
  fi
  echo 1 >/proc/sys/net/ipv4/ip_forward
  if ensure_lan_bridge; then
    ap=$BR
  else
    ap=$(find_downstreams | head -1)
  fi
  netip=${NET%%/*}
  for n in $ds $ap; do
    ip addr del "$netip/$PFX" dev "$n" 2>/dev/null || true
    ip addr del "$netip/32" dev "$n" 2>/dev/null || true
    [ "$n" = "$ap" ] || ip addr flush dev "$n" 2>/dev/null || true
  done
  ip addr add "$LAN/$PFX" broadcast "$BCAST" dev "$ap" 2>/dev/null || true
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
  # LuCI network.wan.device is the WAN the human picked.
  uci_wan=$(uci_get network.wan.device || true)
  if [ -n "$uci_wan" ] && [ -d "/sys/class/net/$uci_wan" ]; then
    case "$uci_wan" in
      tun0|tun1|wg0)
        if ip -o -4 addr show dev "$uci_wan" 2>/dev/null | grep -q inet; then
          tun=$uci_wan
          mode=under
        else
          log "uci wan=$uci_wan has no IPv4 — ignore"
          tun=""
        fi
        ;;
      *) up=$uci_wan; [ "$mode" = "under" ] && [ -z "$tun" ] && mode=above ;;
    esac
  fi
  ip rule del pref 70 2>/dev/null || true
  ip rule del pref 75 2>/dev/null || true
  ip rule del pref 80 2>/dev/null || true
  if [ -z "$tun" ] && [ -n "$up" ]; then
    mode=above
  fi
  if [ "$mode" = "above" ] && [ -n "$up" ]; then
    echo 0 >/proc/sys/net/ipv4/conf/"$up"/rp_filter 2>/dev/null || true
    echo 1 >/proc/sys/net/ipv4/conf/"$up"/forwarding 2>/dev/null || true
    ip route replace "$NET" dev "$ap" table "$up" 2>/dev/null || true
    ip rule add from "$NET" lookup "$up" pref 75 2>/dev/null || true
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

  # Android IpServer advertises the iface address as router. It uses .0.
  # Mute only network_stack DHCP replies; we answer with gw=.1.
  iptables -C OUTPUT -p udp --sport 67 -m owner --uid-owner "$NS_UID" -j DROP 2>/dev/null \
    || iptables -I OUTPUT -p udp --sport 67 -m owner --uid-owner "$NS_UID" -j DROP 2>/dev/null || true
  # no blanket INPUT DROP — that killed our own DHCP too
  if [ -x /data/local/tmp/titan_dhcp_gw ]; then
    killall titan_dhcp_gw 2>/dev/null || true
    /data/local/tmp/titan_dhcp_gw "$ap" >/data/local/tmp/titan_dhcp_gw.log 2>&1 &
  fi
  # LAN DNS: DHCP option 6 is $LAN. Android does not serve :53.
  iptables -t nat -C PREROUTING -i "$ap" -p udp --dport 53 -j DNAT --to-destination 8.8.8.8:53 2>/dev/null \
    || iptables -t nat -I PREROUTING -i "$ap" -p udp --dport 53 -j DNAT --to-destination 8.8.8.8:53
  iptables -t nat -C PREROUTING -i "$ap" -p tcp --dport 53 -j DNAT --to-destination 8.8.8.8:53 2>/dev/null \
    || iptables -t nat -I PREROUTING -i "$ap" -p tcp --dport 53 -j DNAT --to-destination 8.8.8.8:53
  kill_pidf "$DNS_PID"
  mkdir -p "$MNT/tmp" "$MNT/var/lib/misc"
  if [ -x "$MNT/usr/sbin/dnsmasq" ]; then
    # DNS only. Android already owns :67 — do not fight it.
    chroot "$MNT" /usr/sbin/dnsmasq --conf-file=/dev/null --interface="$ap" \
      --listen-address="$LAN" --except-interface=lo \
      --port=53 -S 8.8.8.8 -S 1.1.1.1 --no-resolv \
      --dhcp-leasefile=/tmp/dhcp.leases --pid-file=/tmp/dnsmasq.pid \
      >"$LOGDIR/titan2-openwrt-dnsmasq.log" 2>&1 &
    echo $! >"$DNS_PID"
  fi
}

do_start() {
  mkdir -p "$LOGDIR" /data/local/atlas-openwrt /data/misc/titan2
  do_mount || return $?
  if [ ! -f "$MODEF" ] && [ -f /data/misc/titan2/openwrt.wan ]; then
    cp /data/misc/titan2/openwrt.wan "$MODEF" 2>/dev/null || true
  fi
  [ -f "$MODEF" ] || printf 'under' >"$MODEF"
  load_lan
  seed_plane
  start_daemons || true
  apply_lan
  start_applyd
  do_status
}

do_stop() {
  stop_daemons
  iptables -F titan2_ow_fwd 2>/dev/null || true
  iptables -t nat -F titan2_ow_nat 2>/dev/null || true
  log "stopped daemons (LP stays mounted)"
}

# OpenWrt-native client list: dnsmasq lease file so LuCI Status shows STAs.
sync_leases() {
  load_lan
  mkdir -p "$MNT/tmp"
  f=$MNT/tmp/dhcp.leases
  exp=$(($(date +%s 2>/dev/null || echo 0) + 3600))
  head=${LAN%.*}
  : >"$f"
  ip neigh show 2>/dev/null | while read -r ip dummy macrest; do
    echo "$ip" | grep -q "^${head}\\." || continue
    echo " $dummy $macrest" | grep -q lladdr || continue
    mac=$(echo "$dummy $macrest" | awk '{for(i=1;i<=NF;i++) if($i=="lladdr"){print $(i+1); exit}}')
    [ -n "$mac" ] || continue
    printf '%s %s %s * *\n' "$exp" "$mac" "$ip" >>"$f"
  done
}

do_clients() {
  load_lan
  sync_leases
  ip neigh show 2>/dev/null | while read -r line; do
    echo "$line" | grep -q lladdr || continue
    echo "client $line"
  done
  iptables -S titan2_ow_fwd 2>/dev/null | while read -r line; do
    echo "$line" | grep -q -- '--mac-source' || continue
    echo "$line" | grep -q -- '-j DROP' || continue
    mac=$(echo "$line" | awk '{for(i=1;i<=NF;i++) if($i=="--mac-source"){print $(i+1); exit}}')
    [ -n "$mac" ] && echo "blocked $mac"
  done
  echo "leases=$(wc -l <"$MNT/tmp/dhcp.leases" 2>/dev/null || echo 0)"
}

do_block() {
  mac=$(echo "$1" | tr 'A-F' 'a-f')
  [ -n "$mac" ] || { echo "error: mac"; return 1; }
  iptables -C titan2_ow_fwd -m mac --mac-source "$mac" -j DROP 2>/dev/null \
    || iptables -I titan2_ow_fwd -m mac --mac-source "$mac" -j DROP
  echo "blocked=$mac"
}

do_allow() {
  mac=$(echo "$1" | tr 'A-F' 'a-f')
  [ -n "$mac" ] || { echo "error: mac"; return 1; }
  iptables -D titan2_ow_fwd -m mac --mac-source "$mac" -j DROP 2>/dev/null || true
  echo "allowed=$mac"
}

do_status() {
  load_lan
  ap=$(find_ap || echo none)
  tun=$(find_tun || echo none)
  up=$(find_uplink || echo none)
  echo "lp=$(grep -q " $MNT " /proc/mounts && echo mounted || echo unmounted)"
  echo "mnt=$MNT"
  echo "has_root=$([ -d $MNT/bin ] && echo 1 || echo 0)"
  echo "has_luci=$([ -x $MNT/www/cgi-bin/luci ] && echo 1 || echo 0)"
  echo "wan=$(wan_mode)"
  echo "lan=$LAN net=$NET br=$BR"
  echo "downstreams=$(find_downstreams | tr '\n' ',' | sed 's/,$//')"
  echo "ap=$ap tun=$tun uplink=$up"
  echo "uhttpd=$(pgrep -x uhttpd >/dev/null && echo up || echo down)"
  echo "clients=$(ip neigh show 2>/dev/null | grep -c lladdr || echo 0)"
  [ -x "$LPCTL" ] && "$LPCTL" status 2>/dev/null || true
}

cmd=${1:-status}
case "$cmd" in
  mount) do_mount ;;
  start|apply) do_start ;;
  stop) do_stop ;;
  ban) load_lan; ban_netaddr; ip addr add "$LAN/$PFX" broadcast "$BCAST" dev "${BR:-ap_br_ap0}" 2>/dev/null || true; do_status ;;
  under) do_mount; printf 'under' >"$MODEF"; apply_lan; do_status ;;
  above) do_mount; printf 'above' >"$MODEF"; apply_lan; do_status ;;
  clients) do_clients ;;
  block) do_block "$2" ;;
  allow) do_allow "$2" ;;
  status|*) do_status ;;
esac
