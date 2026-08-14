# Publish rules

This tree is meant to be posted as source. It must not contain Unihertz
firmware, Play Store blobs, or third-party binaries we cannot license.

```bash
./scripts/check_publish.sh
```

Must exit 0 before `git push` to a public remote.

## Allowed

- Our Java / C / shell (MIT), with credits in `CREDITS.md` / `NOTICE.md`
- Lineage / MisterZtr **patches** (small diffs), not the Android tree
- Vendored **source** of GPL projects **with their LICENSE file**
- Fetch scripts that download microG / OpenEUICC at build time

## Forbidden in git

- `*.apk`, `debug.keystore`, Debian rootfs tarballs
- Stock / vendor / `boot.img` / IMS native `.so`
- GNU bash ELF (GPLv3, no source here)
- `quest-usbip-host` ELF (source not in this repo)
- Upstream `titan2-touchpadd` ELF (rebuild from patches)
- Lab serials, private keys, Google API keys

## Flavors

| Flavor | Extra bits | In git? |
|--------|------------|---------|
| vanilla | none | — |
| microg | GmsCore / Companion / GsfProxy | fetch only |
| gapps | MisterZtr bgN4 lunch | not our blobs |
