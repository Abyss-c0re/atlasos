# Build

```bash
cp config/misterztr.env.example config/misterztr.local.env
# set MISTERZTR_TREE and optional TITANUS2_WORKSHOP / STOCK_ZIP

./scripts/link.sh
./scripts/check_clean.sh
./scripts/build.sh            # GSI + hybrid pack when workshop + stock exist
./scripts/build.sh --gsi-only # SERIES + stage + systemimage only
```

`link.sh` does not download the Android tree. Point `MISTERZTR_TREE` at an
existing MisterZtr checkout (workshop artifacts) or sync it yourself with
the MisterZtr README.

First cook that must **match the live bench** uses the linked workshop
kitchen (`lab_rootless` + KEEP_DATA policy owned there). AtlasOS supplies
the product sources and patches.

This repo never flashes.
