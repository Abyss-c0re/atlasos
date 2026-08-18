# AtlasOS overlays — from live Titan (2026-08-18)

Captured from `TITAN20000021925` `/system/product/overlay/Titan*.apk`.
Built into MisterZtr GSI as `runtime_resource_overlay` + `PRODUCT_PACKAGES`.

| Module | Live package | Live state |
|--------|--------------|------------|
| TitanCubeIconMask | com.titanus2.overlay.cubemask | static, enabled |
| TitanSensorPrivacyOverlay | com.titanus2.overlay.sensorprivacy | static, enabled |
| TitanCubeIcon_settings | com.titanus2.overlay.cubeicon.settings | static, enabled |
| TitanCubeIcon_* | com.titanus2.overlay.cubeicon.* | mutable, present |

Do not hybrid-copy these APKs. Stage: `scripts/misterztr/stage_gsi_product.sh`.
