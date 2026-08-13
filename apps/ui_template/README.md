# UI template — OS Material / DeviceDefault (apps)

**Cube design is system-wide** (cube-ux + square RROs + SystemUI).  
**This folder is for product apps only** — Titan Controls / USB HID style.

Reference: `apps/titan_controls` (especially `…/ui/UiKit.java` after Material purge).

## Rules

1. **Match the OS** — `Theme.DeviceDefault.DayNight`, framework `Switch` / `Button`, theme text colors.  
2. **No private Cube chrome** — no black field + cyan square tiles in app code.  
3. **Internal tool** — no taglines, slogans, marketing copy, credits on device.  
4. **Labels** = control name or action; state lines = short mono facts.  
5. **Copy** `UiKit.java` only as a *thin* helper (section headers, themed rows). Do not restore Cube palette constants as product chrome.

## Density

| Element | Guidance |
| --- | --- |
| Screen pad | ~16 dp; inherit activity theme background |
| Section | Caps / secondary text (Settings-like) |
| On/off | `Switch` via `UiKit.toggle` |
| Modes | borderless `Button` segments + `setSelected` |
| Destinations | `UiKit.navRow` with selectableItemBackground |

## How to use

1. Copy `apps/titan_controls/src/com/titanus2/controls/ui/UiKit.java` and fix package.  
2. Build screens programmatically (or XML) with **framework widgets**.  
3. Put visual Cube work into **OS** (RROs / SystemUI / cube-ux), not here.
