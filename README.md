# AtlasOS

**CyberDeck OS for the Unihertz Titan 2.**

HybridOS: you compile a LineageOS GSI (MisterZtr recipe + our patches). The
kernel and vendor stay stock, because Unihertz publishes no device sources.

Clone this repo. It pulls Lineage, applies MisterZtr patches, then ours.
Flavors: **vanilla** / **microg** / **gapps**.

| Use | Meaning |
|-----|---------|
| Smart-home input | Hardware keyboard, trackpad, USB/BT HID |
| Development device | Atlas terminal, Debian plane, ADB |
| Grok + local models | Atlas / Nanobot → Grok or a LAN / on-device model |

Cube is **system chrome only** (icon mask + night/cyan). This is not a game
or hive product. Working-product gates: [`docs/PRODUCT_LOCK.md`](docs/PRODUCT_LOCK.md).
Not Cube-certified until those gates pass on a wiped image.

## Build (standalone)

```bash
git clone https://github.com/Abyss-c0re/AtlasOS.git AtlasOS && cd AtlasOS
./scripts/bootstrap.sh                 # repo init/sync Lineage + MisterZtr + our SERIES
./scripts/build.sh --flavor vanilla    # GSI; mouse driver compiled in (not inject)
./scripts/build.sh --flavor microg     # + fetched microG (not in git)
./scripts/build.sh --flavor gapps      # MisterZtr bgN4 lunch, if the tree has it
```

What you must fetch yourself: [`DEPENDENCIES.md`](DEPENDENCIES.md).

The Android tree is **local** (`.links/lineage`, gitignored). Tens of GB.

Hybrid super (stock vendor + this GSI) needs **your** region-matching Unihertz
zip (`STOCK_ZIP=`). That firmware is not in this git.

## Layout

| Path | Role |
|------|------|
| `apps/` | Product apps (Titan-only stay here) |
| `patches/gsi_source/` | Our Lineage SERIES |
| `patches/bin/` | One copy of every shippable script |
| `config/flavors.yaml` | vanilla / microg / gapps |
| `config/modules.yaml` | Reusable remotes + unpublished in-tree |
| `scripts/sync-modules.sh` | Pull remotes into `.links/upstream/` |

Reusable apps that are **not published yet** stay in this repo. When a remote
exists, add it to `.gitmodules` (see `.gitmodules.example`) and
`./scripts/sync-modules.sh`.

## Commands

```bash
./scripts/sync-modules.sh --status
./scripts/check_dupes.sh
./scripts/check_clean.sh
```

## License

Original work: MIT (`LICENSE`). Third-party map: [`NOTICE.md`](NOTICE.md).  
What must not be in git: [`docs/LEGAL.md`](docs/LEGAL.md).

```bash
./scripts/check_publish.sh    # must pass before a public push
```

Termux emulator sources are **GPLv3** (not MIT). PocketBoard is **not** an AtlasOS
ROM package (optional sideload). microG / OpenEUICC APKs are fetched at build
time, not committed. Unihertz firmware is never in this repo.
