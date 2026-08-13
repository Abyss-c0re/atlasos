# Atlas GSI product package

**In scope (Atlas ROM):**

| Piece | Role |
|-------|------|
| `TitanAtlas.apk` | Terminal KEY (Termux-class host, extra keys, hybrid enter) |
| `atlas-hybrid.sh` | Combined OS: ext4 loop + overlay + Debian binds |
| `atlas-net.sh` | Session entry (hybrid default when bootstrapped) |
| `atlas-hybrid-boot.sh` + `.rc` | Late boot **mount only** (never reformat) |
| Optional `debian-trixie-arm64-rootfs.tar.gz` | Essentials rootfs seed (nano, curl, ca-certs, …) |

**Not in Atlas ROM image:**

- Proprietary / third-party CLIs (e.g. user installers) — supported if the user installs them later (`curl` + CA + redirects work)
- **HW keyboard / keylayout / PocketBoard / TitanKey** — product keyboard track, out of Atlas scope

## Build rootfs essentials

```bash
./packages/titan_atlas/scripts/build_debian_rootfs.sh
# optional tip push + rebootstrap:
./packages/titan_atlas/scripts/build_debian_rootfs.sh --push
```

Seed packages: ca-certificates, curl, wget, openssl, nano, less, procps, iputils-ping, locales, xz-utils, tar, gzip.

## Stage into MisterZtr

```bash
# Build APK first
(cd apps/titan_atlas && ATLAS_SKIP_NATIVE=0 ./build.sh)
./scripts/misterztr/stage_gsi_product.sh
./scripts/misterztr/pipeline.sh --from=patch
```

## Device first use

```text
# Atlas app → hybrid shell (after bootstrap once)
hybrid bootstrap   # needs rootfs staged or URL
# or host: build_debian_rootfs.sh --push
apt install …      # real Debian
nano /etc/hosts
# optional user CLI (not preinstalled):
curl -fsSL https://…/install.sh | bash
```
