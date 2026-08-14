# Source lockfile

`sources.yaml` names remotes. Trees stay on disk (`.links/`). Never upload
Lineage, MisterZtr checkouts, or Unihertz zips.

| Link | In this git? |
|------|----------------|
| This repo (patches, apps, product mk) | yes |
| Lineage / MisterZtr tree | **never** (`.links/lineage`) |
| Stock Unihertz zip | **never** |
| Built images | **never** (`out/`) |
| Upstream app checkouts | **never** (`.links/upstream/`) |

```bash
./scripts/sync-modules.sh --status
./scripts/bootstrap.sh
```
