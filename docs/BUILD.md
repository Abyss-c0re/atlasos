# Build

```bash
git clone --recurse-submodules https://github.com/Abyss-c0re/AtlasOS
# existing clone:
git submodule update --init
./scripts/bootstrap.sh                 # pull Lineage + MisterZtr + our SERIES
./scripts/build.sh --flavor vanilla
./scripts/build.sh --flavor microg
./scripts/build.sh --flavor gapps
```

Optional `config/misterztr.local.env`:

```bash
MISTERZTR_TREE=$HOME/.cache/atlasos/lineage
STOCK_ZIP=/path/to/your-unihertz.zip   # hybrid only
```

This repo never flashes. Hybrid super needs **your** stock zip.
