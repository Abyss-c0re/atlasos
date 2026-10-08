# AtlasOS product lock — working product, not a lab prototype

**Status: LOCK, not certified.**  
Cube-certified is a **gate list**. This file does not claim the live Titan passed it.

Cube law: lattice wins. Empty cells stay empty. No invented PASS.

## What AtlasOS is

Titan 2 face of Cube OS. Three userspaces on one stock kernel:

| Plane | Where | Wipe |
|-------|--------|------|
| Android (GSI + stock vendor) | super `system` / `vendor` | survives |
| Debian | super `atlas_linux_a` | survives (home on `/data` does not) |
| OpenWrt 24.10.4 | super `atlas_openwrt_a` | survives (UCI + WAN mode + password on LP) |

No Magisk/KSU modules as the product path. No `/data/local/tmp` as the ship path.

## Single owners

| Intent | Owner | Not |
|--------|--------|-----|
| Pad / keys / HID | pad-agent + touchpadd + hid_bridge (see `NO_CIRCLES_INPUT`) | dual trees, cool-plug kill of healthy n=1 |
| LAN / hotspot / LuCI | OpenWrt LP + `titan2-openwrt.sh` | Java firewall museum, `titan2-fw` Settings tile |
| Android Settings network tile | **Router** only (wrapper + clients + LuCI) | Firewall button |
| Titan Controls | pad, keys, sub display, tweaks | Router / Firewall |
| Cube chrome | system-wide cube-ux + icon mask | private Cube UI in Controls/HID |
| Rear lattice | Cube Contact ← on-device nanobot `:8787` | hardcoded lab IP |
| Flash | human + HOLD_FLASH | remote exclusive HID flash |

## Pad and HID

The pain map lives in the sibling tree: `titanus2` `docs/project/rd/LOGIC_FLOW_MAP.md` (S-PAD-01..04b) and `PAD_CONFLICT_MAP.md` (C2, C6, C7, C14). This image packs `patches/bin/titan2-pad-agent.sh`, `titan2-pad-apply.sh`, and `titan2-typing-watch.sh`. A `/data/local/tmp` copy is a boot reload only. The GSI preflight rejects a cook that drops the hot mode edge.

| Mode | Who owns `titan2-touchpadd` | HID |
|------|-----------------------------|-----|
| off | pad-agent kills it | may keep one temporary daemon only while the USB session mouse plane is on (S-PAD-01) |
| trackpad | pad-agent kills it | must not start it (S-PAD-03) |
| mouse | pad-agent starts it | does not start a second one while the agent is live (S-PAD-02) |
| exclusive grab, same mode | nobody restarts it | a same-mode write does not bump epoch or start the daemon (S-PAD-04b) |

Load at or above 8 does not skip a mode change. Trackpad is native ABS. Mouse is the relative daemon. Those two do not run together.

The hardware keyboard map is `titan2-keys` (`packages/titan_usb_hid_system/titan_keys.c`). `hid_bridge` links that file, so USB `hidg` and Bluetooth `@titan2_bt_kbd` send one 8-byte report. Android, the HID pad, and Moonlight query `@titan2_keys` and use the same table when the socket is down. A specials glyph already includes its own Shift. Held Shift does not change it. `titan2-keys` does not open the trackpad or TitanKey. The trackpad owner stays the table above.

Controls starts `titan2-touchpadd` only for mouse, or for rear sub-hid. Trackpad does not. Off does not kill a daemon that a live USB HID mouse session still owns. A same-mode exclusive grab does not start or restart it. HID `prepareDriverPad` does not start a second daemon while the pad-agent lock is live.

## Cube-certified gates (all required)

A device is **Cube-certified AtlasOS** only when every line is proven on that image, after a **userdata wipe**, with no modules:

1. **Wipe-survive.** After factory reset + setup: Debian enter works from LP (empty home). OpenWrt LuCI answers on `127.0.0.1:8080`. Init used `titan2-openwrt-boot` / `atlas-hybrid-boot` — **no** `u:r:su:s0`.
2. **Router.** Settings shows Router. No Firewall tile. Wrapper shows gw/wan/clients. More settings opens LuCI.
3. **Hotspot.** Client DHCP gateway is the router host (`.1`, never `.0`). Client `generate_204` validates. Subnet set in LuCI is the one Android advertises.
4. **WAN.** SoftAP clients get internet via OpenWrt NAT. Above VPN and under TUN both work when that iface has IPv4. No tun → fall back to STA/cell.
5. **Input.** One owner per class (`NO_CIRCLES_INPUT` + `LOGIC_FLOW_MAP` 2026-08-14). Scan 580 = a11y `GLOBAL_ACTION_*` only while screen on. No Magisk `APP_SWITCH` on product. No pad-agent + Magisk twin both live. No invented HUMAN_FEEL. B1/B2 remain human gates.
6. **Cube.** Rear State Matrix is live from on-device `:8787` or fails closed (blank). No synthetic fill. IN/OUT ports present.
7. **Image = tip.** `/system/bin/titan2-openwrt.sh`, Controls, NetFw, Settings 0091, both LPs are the packed versions — not tmp prototypes.

Until then the bench unit is a **working-product candidate**.

## Explicitly not product

- Magisk/KSU modules for OpenWrt, pad, or fw
- Path B GSIs
- Full IMS native inject
- Zero-radius Settings RROs
- Firewall as a Settings or Controls page
- Claiming certified from a KEEP_DATA dirty flash only
