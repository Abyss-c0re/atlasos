# Legal — not affiliated with cloud vendors

**Nanobot Android control app** is an independent open project.

## Not affiliated

This software is **not** affiliated with, sponsored by, or endorsed by:

- xAI, Grok, or any Grok-branded product
- OpenAI, Anthropic, Meta, Google, or other LLM vendors
- Magisk / topjohnwu (module packaging is community-style only)

Any cloud provider is an **optional, user-configured auth/backend**. Device-code
or OAuth flows, when used, are interoperability with third-party services at the
user’s direction. Product UI must not claim official vendor status.

## Trademarks

Vendor names appearing in code or docs are used only to describe **protocol
compatibility** (e.g. “OpenAI-compatible API”, optional cloud provider id).
They do not imply endorsement.

## Privacy

Local-first by design. Optional on-device llama.cpp. Cloud credentials and peer
tokens stay under user control (Keystore / sealed peer home). See `docs/SECURITY.md`.

## Credits

- nanobot peer: Clanker/nanobot (separate license)
- Optional llama.cpp: upstream authors
- Optional ROM packaging: host tree may live under a device product; the app is reusable
