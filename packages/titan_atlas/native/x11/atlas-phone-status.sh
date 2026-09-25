#!/bin/sh
# Phone status for the KDE panel.
# Battery comes from the kernel gauge. LTE/NR and the default network
# come from Android telephony and ConnectivityService.
# One pipe-separated line:
#   RAT|bars|rsrp|operator|network|percent|charge|detail
OUT="${ATLAS_PHONE_STATUS:-/home/atlas/.cache/atlas-phone.txt}"
mkdir -p "$(dirname "$OUT")" 2>/dev/null || true

grab_last() {
    # Multi-SIM getprop prints an empty field, then the live name.
    printf '%s\n' "$1" | tr ',' '\n' | sed '/^$/d' | tail -n 1
}

sample() {
    cap=$(cat /sys/class/power_supply/battery/capacity 2>/dev/null)
    st=$(cat /sys/class/power_supply/battery/status 2>/dev/null)
    volt=$(cat /sys/class/power_supply/battery/voltage_now 2>/dev/null)
    temp=$(cat /sys/class/power_supply/battery/temp 2>/dev/null)
    if [ -z "$cap" ]; then
        bat=$(android dumpsys battery 2>/dev/null)
        cap=$(printf '%s\n' "$bat" | awk '/^  level:/{print $2; exit}')
        bst=$(printf '%s\n' "$bat" | awk '/^  status:/{print $2; exit}')
        case "$bst" in
            2|5) st=Charging ;;
        esac
    fi
    [ -n "$cap" ] || cap='?'
    chg=
    case "$st" in
        Charging|Full) chg='+' ;;
    esac

    radio=$(android dumpsys telephony.registry 2>/dev/null | awk '
        function grab(s, key,   i, n, c) {
            i = index(s, key)
            if (i == 0) return ""
            s = substr(s, i + length(key))
            sub(/^ */, "", s)
            n = ""
            if (substr(s, 1, 1) == "-") { n = "-"; s = substr(s, 2) }
            while (substr(s, 1, 1) ~ /[0-9]/) {
                n = n substr(s, 1, 1)
                s = substr(s, 2)
            }
            return n
        }
        function okdbm(v) {
            v += 0
            return (v <= -44 && v >= -150)
        }
        function keep(rat, bars, rsrp, rssi) {
            if (!okdbm(rsrp)) return
            bars += 0
            if (bars < 0) bars = 0
            if (bars > 4) bars = 4
            if (bestb < 0 || bars > bestb || (bars == bestb && rsrp+0 > bestrsrp+0)) {
                bestb = bars
                bestrat = rat
                bestrsrp = rsrp
                bestrssi = rssi
            }
        }
        BEGIN { bestb = -1 }
        /mSignalStrength=SignalStrength:/ {
            i = index($0, "mNr=CellSignalStrengthNr:")
            if (i > 0) {
                chunk = substr($0, i, 280)
                keep("5G", grab(chunk, "level = "), grab(chunk, "ssRsrp = "), grab(chunk, "ssRsrp = "))
            }
            i = index($0, "mLte=CellSignalStrengthLte:")
            if (i > 0) {
                chunk = substr($0, i, 220)
                keep("LTE", grab(chunk, "level="), grab(chunk, "rsrp="), grab(chunk, "rssi="))
            }
            i = index($0, "mWcdma=CellSignalStrengthWcdma:")
            if (i > 0 && bestb < 0) {
                chunk = substr($0, i, 160)
                keep("3G", grab(chunk, "level="), grab(chunk, "rscp="), grab(chunk, "ss="))
            }
        }
        END {
            if (bestb < 0) print "none 0 0 0"
            else print bestrat, bestb, bestrsrp, bestrssi
        }
    ')
    rat=$(printf '%s\n' "$radio" | awk '{print $1}')
    bars=$(printf '%s\n' "$radio" | awk '{print $2}')
    rsrp=$(printf '%s\n' "$radio" | awk '{print $3}')
    rssi=$(printf '%s\n' "$radio" | awk '{print $4}')
    [ -n "$rat" ] || rat=none
    [ -n "$bars" ] || bars=0
    case "$rat" in
        none) ratshow='no cell'; rsrpshow='' ;;
        *) ratshow=$rat; rsrpshow=$rsrp ;;
    esac

    op=$(grab_last "$(android getprop gsm.operator.alpha 2>/dev/null)")
    [ -n "$op" ] || op=$(grab_last "$(android getprop gsm.sim.operator.alpha 2>/dev/null)")
    op=$(printf '%s' "$op" | tr '|' '/')

    net=$(android dumpsys connectivity 2>/dev/null | awk '
        function grab(s, key,   i, n) {
            i = index(s, key)
            if (i == 0) return ""
            s = substr(s, i + length(key))
            n = ""
            if (substr(s, 1, 1) == "-") { n = "-"; s = substr(s, 2) }
            while (substr(s, 1, 1) ~ /[0-9]/) {
                n = n substr(s, 1, 1)
                s = substr(s, 2)
            }
            return n
        }
        /Active default network/ { a = 1; next }
        a && /NetworkAgentInfo\{/ {
            name = "network"
            if ($0 ~ /Transports: WIFI/) name = "Wi-Fi"
            else if ($0 ~ /Transports: CELLULAR/) name = "Mobile"
            else if ($0 ~ /Transports: VPN/) name = "VPN"
            else if ($0 ~ /Transports: ETHERNET/) name = "Ethernet"
            ssid = ""
            if (match($0, /SSID="[^"]+"/))
                ssid = substr($0, RSTART + 6, RLENGTH - 7)
            rssi = grab($0, "RSSI: ")
            gsub(/\|/, "/", ssid)
            if (name == "Wi-Fi" && ssid != "") {
                printf "Wi-Fi %s", ssid
                if (rssi != "") printf " %s dBm", rssi
            } else if (name == "Mobile") {
                printf "Mobile data"
            } else
                printf "%s", name
            printf "\n"
            exit
        }
    ')
    [ -n "$net" ] || net='no network'
    net=$(printf '%s' "$net" | tr '|' '/')

    detail=''
    if [ -n "$rsrpshow" ]; then
        detail="RSRP ${rsrp} dBm"
        [ -n "$rssi" ] && [ "$rssi" != "$rsrp" ] && detail="${detail}, RSSI ${rssi} dBm"
    fi
    if [ -n "$volt" ]; then
        vv=$(awk -v v="$volt" 'BEGIN { if (v+0 > 0) printf "%.2f V", v/1000000 }')
        [ -n "$vv" ] && detail="${detail:+$detail, }$vv"
    fi
    if [ -n "$temp" ]; then
        tt=$(awk -v t="$temp" 'BEGIN { if (t+0 > 0) printf "%.1f C", t/10 }')
        [ -n "$tt" ] && detail="${detail:+$detail, }$tt"
    fi

    line="${ratshow}|${bars}|${rsrpshow}|${op}|${net}|${cap}|${chg}|${detail}"
    printf '%s\n' "$line" > "$OUT"
    printf '%s\n' "$line"
}

if [ "${1:-}" = --once ]; then
    sample
    exit 0
fi

while true; do
    sample >/dev/null
    sleep 4
done
