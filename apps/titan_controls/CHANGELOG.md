# Titan Controls — changelog

**LAW:** every `build.sh` run stamps this file and embeds `assets/changelog.txt`.
Do not ship without a matching build entry.

---

## 16.59-ims-calls (659) — 2026-08-22T17:56Z

### rebuild 2026-08-22T17:57Z · 16.59-ims-calls (659)

- Rebuild (ALLOW_ROOT=0)


- IMS tweaks + Settings Calls heal
- Tweaks: IMS plane (binder / MTK / VoLTE) + Heal calls
- Detect/heal follow Settings → Calls; never overwrite the pin

---

## 16.55-button-plane (655) — 2026-08-21T20:05Z

- Build stamp (ALLOW_ROOT=0)

---

## 16.54-nav-buttons (654) — 2026-08-21T19:45Z

- Build stamp (ALLOW_ROOT=0)

---

## 16.49-chrome (649) — 2026-08-21T13:03Z

- Theme: Navbar tint and QS background are their own Apply rows. shade_panel_fallback was Monet crimson.

---

## 16.48-sysui-accent (648) — 2026-08-21T12:58Z

- Apply accent tints navbar/QS via system_accent1_*_dark and primary. Disables Monet systemui:accent grey. No JSON rewrite.

---

## 16.47-launcher-tint (647) — 2026-08-21T12:54Z

- Accent Apply is OS highlight only. App icons fabricate launcher themed_icon_* (Monet crimson was winning).

---

## 16.46-icon-split (646) — 2026-08-21T12:42Z

- Theme: split Settings vs App icons. App icons apply background, color, shape. Removed dead Accent Apply.

---

## 16.45-fast-theme (645) — 2026-08-21T07:36Z

- Theme apply: four accent overlays, no Monet JSON rewrite, no SystemUI crash.

---

## 16.44-icon-apply (644) — 2026-08-21T07:25Z

- Apply icons: persist pick, wake root belt, succeed only when accent matches.

---

## 16.43-icon-wake (643) — 2026-08-21T07:23Z

- Apply icons wakes root belt; success only if accent matches the picked color.

---

## 16.42-theme-apply (642) — 2026-08-21T07:18Z

### rebuild 2026-08-21T07:23Z · 16.42-theme-apply (642)

- Apply icons stamps root belt app_icons; waits until accent matches the picked color.


- Theme: pick then Apply. Apply icons uses the picked glyph on themed app icons. No cubeicon. No auto-apply.

---

## 16.41-icon-color (641) — 2026-08-21T07:08Z

- Apply icons is color-only (icons-preset). Reverted cursed cubeicon apps-on.

---

## 16.40-app-icons (640) — 2026-08-21T07:04Z

- Apply icons enables and tones cubeicon app overlays (unique cplate/cglyph per app).

---

## 16.39-dpi-min120 (639) — 2026-08-21T06:56Z

- Remove dpi intent extra that re-applied 260 on recreate. Slider min 120.

---

## 16.38-dpi-binder (638) — 2026-08-21T06:43Z

- Apply density via IWindowManager transact 12 user 0. Never ratio/0. Bootloop was dens 0 persist.

---

## 16.37-dpi-wm (637) — 2026-08-21T06:29Z

### rebuild 2026-08-21T06:30Z · 16.37-dpi-wm (637)

- Display size Apply uses IWindowManager user 0 like Settings; do not lie on Settings write.


### rebuild 2026-08-21T06:30Z · 16.37-dpi-wm (637)

- Display size Apply uses IWindowManager user 0 like Settings; do not lie on Settings write.


- Display size Apply uses IWindowManager user 0 like Settings; do not lie on Settings write.

---

## 16.36-dpi-apply (636) — 2026-08-21T06:14Z

- Tweaks Display size: Apply writes wm density live (slider was paint-only heresy).

---

## 16.35-dpi (635) — 2026-08-21T05:54Z

- Tweaks Display size: live dpi on top, slider, Reset. No two-pane clamp.

---

## 16.27-settings-mono (627) — 2026-08-20T15:49Z

- Settings mono fabricate in Controls (no Magisk, no tmp). Purged cube_crimson.

---

## 16.26-no-icon-loop (626) — 2026-08-20T15:35Z

- No icon-apply loop; a11y stamps titan2_input_lock on keyguard so /system pad-apply parks touchpadd after reboot

---

## 16.25-color-apply (625) — 2026-08-20T15:16Z

- Apply color: Magisk watcher fabricates seed; pad-agent 2.226 mksh-safe

---

## 16.24-color-apply (624) — 2026-08-20T15:09Z

- Apply color writes theme JSON and fabricates seed shades; pad-agent runs icons-preset

---

## 16.23-color-pick (623) — 2026-08-20T14:41Z

- Theme: HSV/RGB/hex custom color applies accent + app icons

---

## 16.18-theme-icons (618) — 2026-08-20T02:20Z

- Theme hub: Settings icon overlay API (plate/glyph)

---

## 16.17-adb-clients (617) — 2026-08-19T01:14Z

- Remote ADB client list; first new host needs Atlas auth

---

## 16.15 (615) — 2026-08-17T15:36Z

- Hub paints before seed/heal. Grey Settings chrome, no teal chips. Keys lists after first frame.

---

## 16.14 (614) — 2026-08-17T10:01Z

- Follow-orient: accept Surface rotation 0-3. Stop pad-apply writing 0 over landscape.

---

## 16.13 (613) — 2026-08-17T09:47Z

- Chords: 2 or 3 keys. Hold together then release to capture. Longest match wins; a 2-key chord waits briefly if a 3-key chord extends it.

---

## 16.12 (612) — 2026-08-17T09:39Z

- Alt+letter chords: capture and fire even when a11y has scan 0 or Alt is only a modifier bit.

---

## 16.11 (611) — 2026-08-17T09:16Z

- Act as key remaps a physical key (no short/long split). Mouse left/right/middle follow hold so you can drag-select. Double tap stays a separate type.

---

## 16.10 (610) — 2026-08-17T08:58Z

### rebuild 2026-08-17T08:59Z · 16.10 (610)

- KeyCapture cannot swallow nav unless Keys is open. Disarm + wake keypad LED on a11y bind.


- KeyCapture cannot swallow nav unless Keys is open. Disarm + wake keypad LED on a11y bind.

---

## 16.09 (609) — 2026-08-17T08:56Z

- Chords record two keys held together. Scan 0 ignored. Singles still fire when pressed alone.

---

## 16.08 (608) — 2026-08-17T08:50Z

- Chord capture uses the same side-rail identity as remaps. Ignore mtk-kpd CAMERA scan 0; wait for gpio/ff_key.

---

## 16.07 (607) — 2026-08-17T08:43Z

- Nav SoT is KeyMapPrefs slots only. key-watch KEY_FIREs the published action; no hardcoded home/recents fallback.

---

## 16.06 (606) — 2026-08-17T08:39Z

- Nav hard-guard: pair chords only track saved-pair members. key-watch yields when Controls a11y is listed (install must not starve TitanKey).

---

## 16.05 (605) — 2026-08-17T05:48Z

- Identify side/Alt when EventHub scan is 0 so chords save. Block single shortcuts only after a pair is detected.

---

## 16.04 (604) — 2026-08-17T05:43Z

- Generic key+key chords (neither single shortcut fires). Keys → Chords. Restore keypad LED on activity.

---

## 16.03 (603) — 2026-08-17T05:34Z

- Recents follows Controls map (long=overview GLOBAL_ACTION_RECENTS). Drop residual Home on release (closes overview). Never toggleRecentApps.

---

## 16.02 (602) — 2026-08-17T05:29Z

- do not walk a11y tree on mouse click (wedged Back/Recents when IME on rear Cube).

---

## 16.01 (601) — 2026-08-17T05:25Z

- mouse:left/right click immediately at cursor (do not wait for pad-agent 2s heat sleep).

---

## 16.00 (600) — 2026-08-17T05:19Z

### rebuild 2026-08-17T05:19Z · 16.00 (600)

- mouse:left/right click at the pad pointer (evdev BTN on titan2-virtual-mouse).


- mouse:left/right click at the pad pointer (evdev BTN on titan2-virtual-mouse). Stop clicking the focused node.

---

## 15.99 (599) — 2026-08-17T05:02Z

- mouse:left/right use a11y click (injected SOURCE_MOUSE was dropped). Pair with touchpadd REL_WHEEL for Mouse Mode on Android.

---

## 15.98 (598) — 2026-08-17T04:57Z

- Side keys mouse:scroll_* scroll the Android window via a11y (ACTION_SCROLL + swipe). Injected SOURCE_MOUSE wheel was accepted and dropped with no mouse device.

---

## 15.97 (597) — 2026-08-17T00:20Z

- Diagnostics: detect simswitch NVRAM split, MMTEL empty-caps theater, dual IMS APN flap, USP OP08 vs live SIM, silent SKIP_RINGING, hold missing. No test call.

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
