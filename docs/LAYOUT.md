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
  third_party/titan2-touchpadd/   pad ELF + patches

titanus2/                         WORKSHOP (lab)
  product → ../atlasos            alias
  apps/<name>/src → atlasos       same inode
  patches/bin → atlasos
  packages/magisk_*/system/bin → atlasos/patches/bin
  gsi/ out/ firmware/ kitchen/    lab only — never in AtlasOS
  .atlasos_local/                 parked extras (APK, rootfs, .bak)
```

Edit **either** path. If you copy a file over a symlink, you fork the product.

```bash
./scripts/check_links.sh
./scripts/check_dupes.sh
./scripts/collapse_dupes.py   # repair
./scripts/link_workshop.sh    # repair workshop → AtlasOS
```
