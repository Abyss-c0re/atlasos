# Tree layout — one file, many names

```
atlasos/                          PRODUCT SoT (this git)
  apps/<name>/src|res|manifest    Java apps
  patches/bin/                    ALL shippable titan2-*.sh + atlas-*.sh
  patches/init/                   init .rc
  patches/gsi_source/             Lineage SERIES
  patches/keylayout|idc           TitanKey + pad
  packages/titan_atlas/native/    Atlas C sources
  packages/gsi_product/           Soong wrappers (Android.bp / mk) — files are links
  third_party/titan2-touchpadd/   git submodule (Abyss-c0re/titan2-touchpadd)

titanus2/                         optional lab workshop
  product → ../atlasos
  apps/<name>/src → atlasos
  gsi/ out/ firmware/ kitchen/    lab only
```

Clone-to-build does **not** need titanus2. Flavors: `config/flavors.yaml`.
Reusable remotes: `config/modules.yaml` + `scripts/sync-modules.sh`.

Edit **either** path. If you copy a file over a symlink, you fork the product.

```bash
./scripts/check_links.sh
./scripts/check_dupes.sh
./scripts/collapse_dupes.py   # repair
./scripts/link_workshop.sh    # repair workshop → AtlasOS
```
