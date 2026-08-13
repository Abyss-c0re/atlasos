# Chain (cubechain store)

AtlasOS does **not** contain LineageOS, MisterZtr trees, stock vendor, or
firmware. Those are **links**.

`sources.yaml` is the store: URL + ref + local path env. `./scripts/link.sh`
resolves them under `.links/` (gitignored) or the path you already have.

Same idea as a lockfile, not as a hivemind.

| Link | Uploaded? |
|------|-----------|
| This git (patches, apps, product mk) | yes |
| `MISTERZTR_TREE` | **never** |
| Stock Unihertz zip | **never** |
| Built `super.img` / GSI images | **never** (local `out/`) |
| Touchpadd / PocketBoard / OpenEUICC upstream trees | **never** (fetch) |
