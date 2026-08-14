# HybridOS

Unihertz does not provide kernel or device sources. AtlasOS is therefore a
**HybridOS**:

- **System** — source-built LineageOS GSI (MisterZtr recipe + our SERIES)
- **Vendor / boot / kernel** — stock train, region-matched, your zip
- **Product code** — this repo, compiled into the GSI when possible
- **Mouse driver** — `titan2-touchpadd` is staged into the Lineage tree
  and built into systemimage. Hybrid pack does not copy that ELF.

There is no honest “from-source Titan ROM” until OEM sources exist. Do not
claim otherwise.

Cross-region vendor (EEA ↔ non-EEA) is forbidden. Full native IMS inject is
banned (bootloop history). Kitchen Magisk `init_boot` on a hybrid flash is
banned (bootloop).
