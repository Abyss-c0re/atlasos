# Titan Controls

Settings hub (no launcher icon) — **Settings → System → Titan Controls**.

**UI policy:** OS Material / DeviceDefault (match Settings).  
Cube / square / night cyan is **system-wide** only — see [`docs/project/PRODUCT_UX.md`](../../docs/project/PRODUCT_UX.md).  
App helpers: thin `…/ui/UiKit.java` (not a private brand). Template: `apps/ui_template/`.

**Keys** screen: shortcut list + press-to-bind (short/long/double), plus **Magic** hold-modifier (chords / layout / arrows / system, per-app).

| Package | Role |
|---------|------|
| `com.titanus2.controls` | Hub, pad, keys, network |
| `…notifled` | Notification LED |
| `…devtools` | ADB |
| `…ui` | Thin Material helpers + Look plane (accent for cube-ux) |

## Build

```bash
./build.sh   # → TitanControls-v2.apk
```

Hybrid: `WITH_CONTROLS=1` (default) installs as system priv-app.
