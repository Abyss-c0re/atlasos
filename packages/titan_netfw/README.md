# TitanNetFw

Persistent owner of the Titan network stack. Not Debian, not Titan Controls.
Own UID only — never `android.uid.system`. Kitchen inject of a system-uid APK
onto a MisterZtr-signed GSI kills PMS (signature mismatch bootloop). Engine
is `/system/bin/titan2-fw`; this APK only execs it.

- Engine: `/system/bin/titan2-fw` (INPUT + OUTPUT + FORWARD)
- Tether wrap: `/system/bin/titan2-tether.sh` (Wi‑Fi / USB / Ethernet)
- UI: Settings → Network → Router / Firewall
- Desire: `/data/misc/titan2/fw.*` `tether.prefix`
- Clients: `/proc/net/arp` + `fw.clients` (allow / isolate / lan-only / block)

Build: `./build.sh` then stage via `packages/gsi_product`.
