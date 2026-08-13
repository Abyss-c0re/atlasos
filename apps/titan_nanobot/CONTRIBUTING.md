# Contributing

1. No vendor affiliation claims (see docs/LEGAL.md).
2. Privacy fail-closed: missing peer token / denylist floor stays enforced.
3. Secrets only via Keystore (`SecureStore`) or nanobot sealed session.
4. Run `./scripts/qa_smoke.sh` and `./scripts/security_checklist.sh` before PR.
5. Prefer small commits; do not commit keystores with production keys or tokens.
