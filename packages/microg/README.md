# microG (flavor)

Optional Play-compat for `--flavor microg`. Same restricted model as
LineageOS for microG: priv-app + `FAKE_PACKAGE_SIGNATURE` whitelist.
MisterZtr VANILLA already has `isMicrogSigned` helpers.

APKs are **not** in git.

```bash
./packages/microg/fetch.sh
./scripts/build.sh --flavor microg
```

Upstream: [microg/GmsCore](https://github.com/microg/GmsCore) ·
[LineageOS for microG](https://lineage.microg.org/).
