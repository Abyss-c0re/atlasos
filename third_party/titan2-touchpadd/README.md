# titan2-touchpadd (third_party)

Upstream: [PeterGSI/titan2-touchpadd](https://gitea.angry.im/PeterGSI/titan2-touchpadd)  
Prebuilt: [android_vendor_prebuilts_titan2-touchpadd](https://gitea.angry.im/PeterGSI/android_vendor_prebuilts_titan2-touchpadd)

Converts the Titan 2 keyboard capacitive surface into a uinput mouse (gestures). **Credit and license: upstream.**

## In this tree

| Path | Role |
| --- | --- |
| `bin/titan2-touchpadd` | aarch64 musl static binary (our patched build) |
| `idc/` | default ignore profiles for module mode |
| `init/titan2-touchpadd.rc` | optional disabled init unit (`KEYBOARD_FEATURES false`) |
| `patches/` | full source rebuild inputs (main/evloop/gesture/…) |

## titanus2 patches (vs upstream)

1. **`KEYBOARD_FEATURES≠true` never opens TitanKey** — pad-only path; avoids dual-open with `hid_bridge` exclusive grab (S-HID-08 / B4). Name via **sysfs** so TitanKey is not even briefly opened.
2. **`TAP_TO_CLICK=0|false|off`** — pointer + scroll only (no click/drag).
3. **`TOP_ROW_CURSOR` (default on)** — independent top-row module:
   - Cap strip over Shift/Sym/Back/Recents/Fn/Alt → **text caret** (`KEY_LEFT`/`KEY_RIGHT` via `titan2-text-nav`)
   - When on, **main pad surface is the lower region only** (`PAD_SURFACE=lower`)
   - When off (`TOP_ROW_CURSOR=0`), full surface expands for trackpad/mouse
4. **`TOP_ROW_ONLY=1`** — standalone top-row caret only (no lower pad pointer).
5. Init rc default **`KEYBOARD_FEATURES false`** (upstream sample used `true`).
6. **In-process park** (`pause.rs`) — plane pause rising-edge + key-activity cool; suppress REL/BTN without kill. Status: `mode=… park=0|1 pid=…`. Contract: `docs/project/PAD_TOUCHPADD_CONTRACT.md`.
7. **Configurable pad gestures** (`evloop.rs` + plane) — `titan2_pad_tap_click` / `long_click` / `scroll` (1|0) and `titan2_pad_dbltap` = `classic` (default drag-hold while finger down) | `latch` (toggle hold) | `off`. Hot-read; no restart.

## Start-path contract (pad-agent + HID service)

- Always spawn with `KEYBOARD_FEATURES=false TOP_ROW_CURSOR=1`.
- If a live `titan2-touchpadd` still holds TitanKey FD → **kill+restart** pad-only (budget ≤2).
- Under exclusive `keys=1`, only `hid_bridge` may own TitanKey (`event7`).

## Product path (2026-08-06+)

**Pad runtime SoT is this binary**, not `titan2-pad-agent.sh`.  
Typing park / mouse arming belong **in-process** (see OPTIMIZE Phase 1).  
Ship target: **GSI prebuilt** (`packages/gsi_product/prebuilt_touchpadd/`, gsi_source),
not permanent hybrid-only inject.

Program: `docs/project/OPTIMIZE_SOURCE_PRODUCT.md`.

## Rebuild

```bash
git clone --depth 1 https://gitea.angry.im/PeterGSI/titan2-touchpadd.git /tmp/t2tp
cp patches/*.rs /tmp/t2tp/src/
export CARGO_TARGET_AARCH64_UNKNOWN_LINUX_MUSL_LINKER="$(rustc --print sysroot)/lib/rustlib/x86_64-unknown-linux-gnu/bin/rust-lld"
( cd /tmp/t2tp && cargo build --target aarch64-unknown-linux-musl --release )
cp /tmp/t2tp/target/aarch64-unknown-linux-musl/release/titan2-touchpadd bin/
```

Lab tip: root/Magisk push for iteration. Product: GSI pipeline after prove.

`fetch.sh` pulls **upstream prebuilt** (no TitanKey/top-row split) — prefer rebuilding from `patches/`.
