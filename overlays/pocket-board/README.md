# PocketBoard overlay

Titan hardware-keyboard XML maps currently live inside the vendored tree
`third_party/pocket-board/` (`*_titan.xml`). When that path becomes a
submodule, move only those maps here and let `sync-modules.sh` copy them in.
