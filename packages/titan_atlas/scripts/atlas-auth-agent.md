# Atlas Authentication Agent

## Role

Shell identity is always **admin** (app UID). Privilege is never free root.
The **agent** is an Android service that authorizes elevate with biometrics.
**KernelSU is not required** for hybrid/Debian sudo — the image has real
setuid `/usr/bin/sudo` and an `admin` sudoers entry; agent already said yes.

| Layer | What |
|-------|------|
| **Agent host** | `AtlasSessionService` + biometrics (`AuthPromptActivity`) |
| **Protocol** | `$ATLAS_HOME/auth/{req,ok,fail,ticket,wake}.*` |
| **Client** | `atlas-auth request` · PATH `sudo`/`su` (agent clients) |
| **Debian elevate** | After grant → `/usr/bin/sudo -n` (setuid in image) |
| **ROM elevate** | Optional setuid-root agent client; KSU only last resort |

## Seamless hybrid

Android ↔ Debian share the same kernel. `bind_android` + `/atlas-bin` keep
tools **linked and re-synced** (`ATLAS_RELINK=1`). Call `am`/`apt` from either
shell; PATH is bidirectional.

## Modes

| Mode | Identity | Elevate |
|------|----------|---------|
| Android admin shell | app UID | agent → supervised / optional KSU |
| Debian hybrid | admin (same UID) | agent → `/usr/bin/sudo -n` |
| apt | admin | agent → hybrid run |

## Ticket

~90s after fingerprint so multi-step apt does not re-prompt.
