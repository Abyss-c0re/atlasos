# TitanNetFw

Persistent system owner of the Titan network stack. Not Debian, not Titan Controls.

- Engine: `/system/bin/titan2-fw` (INPUT + OUTPUT + FORWARD)
- Tether wrap: `/system/bin/titan2-tether.sh` (Wi‑Fi / USB / Ethernet)
- UI: Settings → Network → Router / Firewall
- Desire: `/data/misc/titan2/fw.*` `tether.prefix`
- Clients: `/proc/net/arp` + `fw.clients` (allow / isolate / lan-only / block)

Build: `./build.sh` then stage via `packages/gsi_product`.
