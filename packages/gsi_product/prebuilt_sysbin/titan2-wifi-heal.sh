#!/system/bin/sh
# titan2-wifi-heal — keep Titan STA on WPA2-only home APs.
# Heresy 2026-08-27/28: GSI config_wifiSaeUpgradeEnabled=true restamps
# wpa3-sae^ + ieee80211w=3 on every wifi toggle / reboot.
# Overlay + fabricate + live wpa_cli strip. Never prints PSKs.
#
# Usage: titan2-wifi-heal.sh [boot|apply|status|sanitize|watch]
export PATH=/system/bin:/system/xbin:/vendor/bin:$PATH
ST=/data/local/tmp
T2=/data/misc/titan2
LOG=$ST/titan2-wifi-heal.log
VER=1.2-watch-sae-off
WPA="wpa_cli -i wlan0 -p /data/vendor/wifi/wpa/sockets"
STORE=/data/misc/apexdata/com.android.wifi/WifiConfigStore.xml

log() {
  mkdir -p "$ST" 2>/dev/null || true
  echo "wifi-heal: $*" | tee -a "$LOG" 2>/dev/null
}

fabricate_overlays() {
  cmd overlay fabricate --target-name WifiCustomization \
    --target com.android.wifi.resources --name TitanSaeOff \
    com.android.wifi.resources:bool/config_wifiSaeUpgradeEnabled 0x12 0 \
    >/dev/null 2>&1 || true
  cmd overlay fabricate --target-name WifiCustomization \
    --target com.android.wifi.resources --name TitanSaeOffloadOff \
    com.android.wifi.resources:bool/config_wifiSaeUpgradeOffloadEnabled 0x12 0 \
    >/dev/null 2>&1 || true
  cmd overlay enable --user 0 com.android.shell:TitanSaeOff >/dev/null 2>&1 || true
  cmd overlay enable --user 0 com.android.shell:TitanSaeOffloadOff >/dev/null 2>&1 || true
  cmd wifi force-overlay-config-value bool config_wifiSaeUpgradeEnabled enabled false >/dev/null 2>&1 || true
  cmd wifi force-overlay-config-value bool config_wifiSaeUpgradeOffloadEnabled enabled false >/dev/null 2>&1 || true
}

sae_overlay_on() {
  v=$(cmd overlay lookup com.android.wifi.resources \
    com.android.wifi.resources:bool/config_wifiSaeUpgradeEnabled 2>/dev/null | tr -d '\r')
  [ "$v" = true ]
}

has_ipv4() {
  ip -o -4 addr show wlan0 2>/dev/null | grep -q inet
}

wifi_on() {
  [ "$(cmd wifi status 2>/dev/null | head -1)" = "Wifi is enabled" ]
}

sanitize_supplicant() {
  $WPA ping >/dev/null 2>&1 || return 1
  nets=$($WPA list_networks 2>/dev/null | awk 'NR>1{print $1}')
  [ -n "$nets" ] || return 1
  dirty=0
  for n in $nets; do
    km=$($WPA get_network "$n" key_mgmt 2>/dev/null | tr -d '\r')
    pmf=$($WPA get_network "$n" ieee80211w 2>/dev/null | tr -d '\r')
    case "$km" in
      *SAE*|*SHA256*|*FT-PSK*) dirty=1 ;;
    esac
    case "$pmf" in
      2|3) dirty=1 ;;
    esac
    $WPA set_network "$n" key_mgmt WPA-PSK >/dev/null
    $WPA set_network "$n" proto RSN >/dev/null
    $WPA set_network "$n" pairwise CCMP >/dev/null
    $WPA set_network "$n" group CCMP >/dev/null
    $WPA set_network "$n" ieee80211w 0 >/dev/null
    $WPA enable_network "$n" >/dev/null
  done
  if [ "$dirty" = 1 ]; then
    log "stripped SAE/PMF from supplicant"
    $WPA reassociate >/dev/null
  fi
  return 0
}

# Drop SecurityType 4 (SAE auto-upgrade) from the XML store. Wifi off first.
strip_store_sae() {
  [ -f "$STORE" ] || return 0
  grep -q 'SecurityType" value="4"' "$STORE" 2>/dev/null || return 0
  wifi_on && cmd wifi set-wifi-enabled disabled >/dev/null 2>&1
  sleep 2
  tmp=$ST/WifiConfigStore.heal.xml
  # Remove each SAE SecurityParams block (type 4).
  awk '
    BEGIN { skip=0 }
    /<SecurityParams>/ { buf=$0 ORS; inblk=1; next }
    inblk {
      buf=buf $0 ORS
      if (/<\/SecurityParams>/) {
        if (buf !~ /SecurityType" value="4"/) printf "%s", buf
        buf=""; inblk=0
      }
      next
    }
    { print }
  ' "$STORE" >"$tmp" 2>/dev/null || return 0
  if [ -s "$tmp" ] && grep -q 'WifiConfigStoreData' "$tmp"; then
    cat "$tmp" >"$STORE"
    chown system:system "$STORE" 2>/dev/null || true
    chmod 600 "$STORE" 2>/dev/null || true
    log "stripped SAE SecurityType 4 from WifiConfigStore"
  fi
  rm -f "$tmp"
  cmd wifi set-wifi-enabled enabled >/dev/null 2>&1 || true
}

prefer_24() {
  # If 5 GHz Dark Network is the only saved SSID and 2.4 is visible, add it
  # using the already-saved PSK (never echo it).
  cmd wifi list-networks 2>/dev/null | grep -q 'Dark Network 2.4GHz' && return 0
  cmd wifi list-scan-results 2>/dev/null | grep -q 'Dark Network 2.4GHz' || return 0
  [ -f "$STORE" ] || return 0
  psk=$(sed -n 's/.*name="PreSharedKey">&quot;\([^<]*\)&quot;.*/\1/p' "$STORE" | head -1)
  [ -n "$psk" ] || return 0
  cmd wifi connect-network "Dark Network 2.4GHz" wpa2 "$psk" -r none >/dev/null 2>&1 || true
  unset psk
  log "ensured Dark Network 2.4GHz WPA2 profile"
}

do_status() {
  echo "ver=$VER"
  echo "wifi=$(cmd wifi status 2>/dev/null | head -1 | tr -d '\r')"
  echo "sae_overlay=$(cmd overlay lookup com.android.wifi.resources com.android.wifi.resources:bool/config_wifiSaeUpgradeEnabled 2>/dev/null | tr -d '\r')"
  echo "ipv4=$(has_ipv4 && echo 1 || echo 0)"
  iw wlan0 link 2>/dev/null | head -5
  cmd wifi list-networks 2>/dev/null | head -12
}

do_apply() {
  fabricate_overlays
  log "overlays armed ($VER)"
  cmd wifi set-verbose-logging disabled >/dev/null 2>&1 || true
  i=0
  while [ $i -lt 8 ]; do
    sanitize_supplicant && break
    sleep 2
    i=$((i + 1))
  done
  prefer_24
  do_status | tee -a "$LOG" >/dev/null
}

do_boot() {
  i=0
  while [ $i -lt 40 ]; do
    cmd overlay list >/dev/null 2>&1 && cmd wifi status >/dev/null 2>&1 && break
    sleep 2
    i=$((i + 1))
  done
  if grep -q 'SecurityType" value="4"' "$STORE" 2>/dev/null; then
    strip_store_sae
    sleep 4
  fi
  do_apply
}

do_watch() {
  do_boot
  fail=0
  while true; do
    sae_overlay_on && fabricate_overlays
    if wifi_on && ! has_ipv4; then
      sanitize_supplicant || true
      prefer_24
      fail=$((fail + 1))
      # Re-enable a temp-disabled WPA2 net after a few misses.
      if [ $fail -eq 3 ]; then
        $WPA enable_network 0 >/dev/null 2>&1 || true
        $WPA enable_network 1 >/dev/null 2>&1 || true
        $WPA reassociate >/dev/null 2>&1 || true
      fi
      sleep 2
    else
      fail=0
      sleep 8
    fi
  done
}

cmd=${1:-apply}
case "$cmd" in
  boot) do_boot ;;
  watch) do_watch ;;
  status) do_status ;;
  sanitize) sanitize_supplicant; $WPA status; iw wlan0 link ;;
  strip-store) strip_store_sae ;;
  apply|*) do_apply ;;
esac
