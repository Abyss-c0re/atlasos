# Titan Controls — changelog

**LAW:** every `build.sh` run stamps this file and embeds `assets/changelog.txt`.
Do not ship without a matching build entry.

---

## 15.96 (596) — 2026-08-16T16:30Z

- SIMs: SIM 1 / SIM 2 only. No carrier names. Settings 0094 keep-listed for next GSI.

---

## 15.95 (595) — 2026-08-16T15:22Z

- SIMs: UICC-off with empty ICC + slot -1 stays listed Off. Remember last-seen cards. Do not mirror Settings delete.

---

## 15.94 (594) — 2026-08-16T14:14Z

- Buttons: a11y_live is a lie after install/crash. Stamp truth. key-watch 2.194 yields TitanKey when a11y is actually live.

---

## 15.93 (593) — 2026-08-16T13:39Z

### rebuild 2026-08-16T13:39Z · 15.93 (593)

- compile fix


- Disable phone calls. Diagnostics: incoming OFF when disabled; FAIL if LTE/NR and MMTEL has no VOICE. Binder-thread is not incoming.

---

## 15.92 (592) — 2026-08-16T13:33Z

- SIMs screen: UICC-off cards stay listed Off. Settings hide is not delete.

---

## 15.91 (591) — 2026-08-16T13:25Z

- Diagnostics: incoming FAIL if MMTEL binder null or IMS capability on the other SIM slot.

---

## 15.90 (590) — 2026-08-16T13:18Z

### rebuild 2026-08-16T13:19Z · 15.90 (590)

- Rebuild (ALLOW_ROOT=0)


- Diagnostics: disable must not delete SIMs. IMS setup no longer re-enables UICC.

---

## 15.89 (589) — 2026-08-16T13:10Z

### rebuild 2026-08-16T13:10Z · 15.89 (589)

- Rebuild (ALLOW_ROOT=0)


- Diagnostics: incoming-call + nav health without a test call.

---

## 15.88 (588) — 2026-08-16T10:46Z

- About: product pad fork URL (submodule).

---

## 15.87 (587) — 2026-08-16T10:09Z

- About: credits match CREDITS.md.

---

## 15.86-about (586) — 2026-08-16T10:02Z

- About: AtlasOS build, repo link, Abyss-c0re / NexusCore / Hive / Grok agents, third-party projects.

---

## 15.85-changelog-notitle (585) — 2026-08-15T21:24Z

- Changelog: drop duplicate in-page title and LAW stamp block (action bar is enough).

---

## 15.84-changelog-md (584) — 2026-08-15T21:10Z

- Changelog screen renders stamped markdown (headings, lists, bold, code) instead of raw # ** -.

---

## 15.83-mouse-scroll (583) — 2026-08-15T20:58Z

- Mouse scroll is a first-class remap (Mouse… in picker). HID off / no touchpadd: inject real mouse wheel, not Page Up/Down. HID on: guest wheel without pad.

---

## 15.82-no-autodev (582) — 2026-08-14T23:35Z

### rebuild 2026-08-14T23:36Z · 15.82-no-autodev (582)

- Remove Auto Dev, Analyze shots, and BlackCube peer from Developer / QS.


- Remove Auto Dev, Analyze shots, and BlackCube peer from Developer / QS.

---

## 15.81-hub-clean (581) — 2026-08-14T16:27Z

### rebuild 2026-08-14T16:27Z · 15.81-hub-clean (581)

- Rebuild (ALLOW_ROOT=0)


- Drop unused network pages from the hub.

---

## 15.80-home-restore (580) — 2026-08-13T19:50Z

### rebuild 2026-08-13T21:44Z · 15.80-home-restore (580)

- Rebuild (ALLOW_ROOT=0)


### rebuild 2026-08-13T20:11Z · 15.80-home-restore (580)

- Rebuild (ALLOW_ROOT=0)


- Restore Controls Home/Recents on scan 580. Stop swallowing the key. Prefer GLOBAL_ACTION_HOME (inject HOME is a no-op on this GSI).

---

## 15.79-recents-release (579) — 2026-08-13T19:15Z

### rebuild 2026-08-13T19:21Z · 15.79-recents-release (579)

- Rebuild (ALLOW_ROOT=0)


### rebuild 2026-08-13T19:20Z · 15.79-recents-release (579)

- Rebuild (ALLOW_ROOT=0)


- Recents: swallow 580 in a11y (no hold-fire). KL maps 580 to F24 so PWM hold-preview cannot dismiss on release. key-watch is sole owner.

---

## 15.78-recents-sticky (578) — 2026-08-13T17:18Z

### rebuild 2026-08-13T18:39Z · 15.78-recents-sticky (578)

- Rebuild (ALLOW_ROOT=0)


### rebuild 2026-08-13T18:36Z · 15.78-recents-sticky (578)

- Rebuild (ALLOW_ROOT=0)


### rebuild 2026-08-13T18:35Z · 15.78-recents-sticky (578)

- Rebuild (ALLOW_ROOT=0)


### rebuild 2026-08-13T17:54Z · 15.78-recents-sticky (578)

- Rebuild (ALLOW_ROOT=0)


### rebuild 2026-08-13T17:28Z · 15.78-recents-sticky (578)

- Rebuild (ALLOW_ROOT=0)


### rebuild 2026-08-13T17:23Z · 15.78-recents-sticky (578)

- Rebuild (ALLOW_ROOT=0)


- Build stamp (ALLOW_ROOT=0)

---

## 15.77-recents-global-action (577) — 2026-08-13T16:20Z

### rebuild 2026-08-13T16:32Z · 15.77-recents-global-action (577)

- Rebuild (ALLOW_ROOT=0)


### rebuild 2026-08-13T16:32Z · 15.77-recents-global-action (577)

- Recents: GLOBAL_ACTION_RECENTS first; with key-watch 2.188 overview am (no 187).


### rebuild 2026-08-13T16:20Z · 15.77-recents-global-action (577)

- Recents: prefer a11y GLOBAL_ACTION_RECENTS over StatusBar toggleRecentApps no-op (long Home overview).


### rebuild 2026-08-13T16:20Z · 15.77-recents-global-action (577)

- Recents: prefer a11y GLOBAL_ACTION_RECENTS over StatusBar toggleRecentApps no-op.


- Recents: prefer a11y GLOBAL_ACTION_RECENTS over StatusBar toggleRecentApps no-op (long Home overview, never 187).

---

## 15.76-home-short-recents-long (576) — 2026-08-13T15:56Z

- Build stamp (ALLOW_ROOT=0)

---

## 15.75-typing-hotpath (575) — 2026-08-13T13:35Z

### rebuild 2026-08-13T13:35Z · 15.75-typing-hotpath (575)

- Rebuild (ALLOW_ROOT=0)


- Build stamp (ALLOW_ROOT=0)

---

## 15.74-multi-char-dual-residual (574) — 2026-08-13T06:53Z

### rebuild 2026-08-13T12:34Z · 15.74-multi-char-dual-residual (574)

- Rebuild (ALLOW_ROOT=0)


### rebuild 2026-08-13T12:07Z · 15.74-multi-char-dual-residual (574)

- Rebuild (ALLOW_ROOT=0)


### rebuild 2026-08-13T12:03Z · 15.74-multi-char-dual-residual (574)

- Rebuild (ALLOW_ROOT=0)


### rebuild 2026-08-13T11:27Z · 15.74-multi-char-dual-residual (574)

- Rebuild (ALLOW_ROOT=0)


### rebuild 2026-08-13T08:59Z · 15.74-multi-char-dual-residual (574)

- Rebuild (ALLOW_ROOT=0)


### rebuild 2026-08-13T06:58Z · 15.74-multi-char-dual-residual (574)

- Rebuild (ALLOW_ROOT=0)


- Build stamp (ALLOW_ROOT=0)

---

## 15.73-changelog-law (573)

### rebuild 2026-08-13T06:46Z · 15.73-changelog-law (573)

- Rebuild (ALLOW_ROOT=0)


### rebuild 2026-08-13T06:21Z · 15.73-changelog-law (573)

- Rebuild (ALLOW_ROOT=0)


### rebuild 2026-08-13T03:01Z · 15.73-changelog-law (573)

- Rebuild (ALLOW_ROOT=0)


### rebuild 2026-08-13T03:01Z · 15.73-changelog-law (573)

- Rebuild (ALLOW_ROOT=0)


### rebuild 2026-08-13T03:00Z · 15.73-changelog-law (573)

- Rebuild (ALLOW_ROOT=0)


### rebuild 2026-08-13T02:58Z · 15.73-changelog-law (573)

- Rebuild (ALLOW_ROOT=0)


### rebuild 2026-08-13T02:46Z · 15.73-changelog-law (573)

- Rebuild (ALLOW_ROOT=0)


### rebuild 2026-08-13T01:52Z · 15.73-changelog-law (573)

- Rebuild (ALLOW_ROOT=0)


### rebuild 2026-08-12T22:59Z · 15.73-changelog-law (573)

- Rebuild (ALLOW_ROOT=0)


### rebuild 2026-08-12T22:50Z · 15.73-changelog-law (573)

- Rebuild (ALLOW_ROOT=0)


### rebuild 2026-08-12T22:39Z · 15.73-changelog-law (573)

- Rebuild (ALLOW_ROOT=0)


### rebuild 2026-08-12T22:21Z · 15.73-changelog-law (573)

- Rebuild (ALLOW_ROOT=0)


### rebuild 2026-08-12T22:07Z · 15.73-changelog-law (573)

- Rebuild (ALLOW_ROOT=0)


### rebuild 2026-08-12T21:57Z · 15.73-changelog-law (573)

- Rebuild (ALLOW_ROOT=0)


### rebuild 2026-08-12T20:57Z · 15.73-changelog-law (573)

- Rebuild (ALLOW_ROOT=0)


### rebuild 2026-08-12T20:48Z · 15.73-changelog-law (573)

- Rebuild (ALLOW_ROOT=0)


### rebuild 2026-08-12T20:28Z · 15.73-changelog-law (573)

- Rebuild (ALLOW_ROOT=0)


### rebuild 2026-08-12T20:12Z · 15.73-changelog-law (573)

- Rebuild (ALLOW_ROOT=0)


### rebuild 2026-08-12T18:32Z · 15.73-changelog-law (573)

- Rebuild (ALLOW_ROOT=0)


### rebuild 2026-08-12T18:18Z · 15.73-changelog-law (573)

- Rebuild (ALLOW_ROOT=0)


### rebuild 2026-08-12T18:08Z · 15.73-changelog-law (573)

- Rebuild (ALLOW_ROOT=0)


### rebuild 2026-08-12T17:57Z · 15.73-changelog-law (573)

- Rebuild (ALLOW_ROOT=0)


### rebuild 2026-08-12T17:48Z · 15.73-changelog-law (573)

- Rebuild (ALLOW_ROOT=0)


### rebuild 2026-08-12T17:36Z · 15.73-changelog-law (573)

- Rebuild (ALLOW_ROOT=0)


### rebuild 2026-08-12T17:27Z · 15.73-changelog-law (573)

- Rebuild (ALLOW_ROOT=0)


### rebuild 2026-08-12T16:27Z · 15.73-changelog-law (573)

- Rebuild (ALLOW_ROOT=0)


### rebuild 2026-08-12T16:25Z · 15.73-changelog-law (573)

- Rebuild (ALLOW_ROOT=0)


### rebuild 2026-08-12T14:41Z · 15.73-changelog-law (573)

- Rebuild (ALLOW_ROOT=0)


### rebuild 2026-08-12T14:39Z · 15.73-changelog-law (573)

- Rebuild (ALLOW_ROOT=0)


### rebuild 2026-08-12T12:01Z · 15.73-changelog-law (573)

- Rebuild (ALLOW_ROOT=0)


### rebuild 2026-08-12T10:50Z · 15.73-changelog-law (573)

- Rebuild (ALLOW_ROOT=0)


### rebuild 2026-08-12T10:49Z · 15.73-changelog-law (573)

- Rebuild (ALLOW_ROOT=0)


### rebuild 2026-08-12T10:25Z · 15.73-changelog-law (573)

- Rebuild (ALLOW_ROOT=0)


### rebuild 2026-08-12T10:25Z · 15.73-changelog-law (573)

- Hub Changelog row + ChangelogActivity; build.sh stamps CHANGELOG every run (LAW)


- **LAW:** Changelog section in hub + every build updates CHANGELOG + embeds assets.
- ChangelogActivity: scrollable mono log, version line from BuildConfig.
- build.sh: stamp version/date, copy to assets/changelog.txt before aapt.

---

## 15.72-user-nav-sot (572)

- **Heresy fix:** TaskbarPin no longer writes `navigation_mode` / nav interaction mode.
- Boot/a11y pinOff no longer stomps user 3-button vs gesture choice.
- Host cube-ux / heal scripts: stop forcing gestural nav (product path).

---

## 15.71-privacy-failclosed-heresy (571)

- Sensor privacy fail-closed residuals (Secure vs SPM null query).
- ImpulseSnap / belt wake stamps; product privacy tiles registered, QS prefers stock.

---

## 15.70-vpn-hotspot (570)

- VPN / hotspot heal path; prior system priv-app baseline on hybrid.
