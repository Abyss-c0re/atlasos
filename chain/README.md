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

## Workshop sync (do not copy)

Shared product paths are **one inode**: the workshop tree (`titanus2`)
symlinks into this repo. List: [`sync_paths.txt`](sync_paths.txt).

```bash
./scripts/link_workshop.sh    # create / repair
./scripts/check_links.sh      # must pass
```

Edit Atlas (or Controls / HID / patches) in *either* tree — it is the same
file. Workshop-only blobs (debian rootfs, APKs, `jniLibs`, `out/`) stay in
`titanus2` or `.atlasos_local/`. Never rsync those into AtlasOS.
| Touchpadd / PocketBoard / OpenEUICC upstream trees | **never** (fetch) |
