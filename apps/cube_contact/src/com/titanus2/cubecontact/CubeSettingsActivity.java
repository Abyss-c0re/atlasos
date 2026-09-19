package com.titanus2.cubecontact;

import android.app.Activity;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.KeyEvent;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Neural Cube settings — Lineage Settings rows, not a Cube theme.
 */
public class CubeSettingsActivity extends Activity {
    public static final String EXTRA_PLANE = "plane";

    private String plane = CubePlanePrefs.PLANE_FRONT;
    private TextView state;
    private LinearLayout tabs;
    private final List<LinearLayout> sourceRows = new ArrayList<>();
    private final List<MatrixSource> sourceOrder = new ArrayList<>();
    private Switch spin;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        try { StateMatrix.bindAppContext(this); } catch (Exception ignored) {}
        CubeSettingsUi.applyWindow(this);
        if (getIntent() != null && getIntent().hasExtra(EXTRA_PLANE)) {
            plane = CubePlanePrefs.normalizePlane(getIntent().getStringExtra(EXTRA_PLANE));
        }

        ScrollView sc = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        CubeSettingsUi.screen(sc, root);

        CubeSettingsUi.title(root, "Settings");
        tabs = CubeSettingsUi.tabBar(root, new String[]{"Main cube", "Sub display"}, i ->
            setPlane(i == 1 ? CubePlanePrefs.PLANE_REAR : CubePlanePrefs.PLANE_FRONT));
        state = CubeSettingsUi.stateLine(root);

        CubeSettingsUi.section(root, "Source");
        boolean nanobotApp = RomIntegration.nanobotInstalled(this);
        if (!nanobotApp && CubePlanePrefs.source(this, plane) == MatrixSource.PEER) {
            CubePlanePrefs.setSource(this, plane, MatrixSource.AUTO);
        }
        for (MatrixSource s : MatrixSource.values()) {
            if (s == MatrixSource.PEER && !nanobotApp) continue;
            final MatrixSource src = s;
            LinearLayout row = CubeSettingsUi.choiceRow(root, s.label, s.hint,
                () -> {
                    CubePlanePrefs.setSource(this, plane, src);
                    refresh();
                });
            sourceRows.add(row);
            sourceOrder.add(src);
        }

        spin = CubeSettingsUi.toggleRow(root, "Auto-spin",
            CubePlanePrefs.autoSpin(this, plane), on -> {
                CubePlanePrefs.setAutoSpin(this, plane, on);
                refresh();
            });

        CubeSettingsUi.section(root, "On this phone");
        CubeSettingsUi.navRow(root, "Wallpaper", "Live cube on the lock screen",
            this::openWallpaperPicker);
        CubeSettingsUi.navRow(root, "Screen saver", "Daydream",
            this::openDreamSettings);

        CubeSettingsUi.section(root, "More");
        CubeSettingsUi.navRow(root, "Sensors", "Which nodes feed the cube",
            () -> startActivity(new Intent(this, SensorsActivity.class)));
        CubeSettingsUi.navRow(root, "Access", "How this app may act",
            () -> startActivity(new Intent(this, PrivilegeActivity.class)));
        if (RomIntegration.nanobotInstalled(this)) {
            CubeSettingsUi.navRow(root, "Nanobot", "Open the Nanobot app",
                () -> launchPkg("com.titanus2.nanobot"));
        }
        if (RomIntegration.controlsInstalled(this)) {
            CubeSettingsUi.navRow(root, "Titan Controls", "Sub display and keys",
                () -> launchPkg("com.titanus2.controls"));
        }

        setContentView(sc);
        refresh();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.hasExtra(EXTRA_PLANE)) {
            plane = CubePlanePrefs.normalizePlane(intent.getStringExtra(EXTRA_PLANE));
            refresh();
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event == null || event.getAction() != KeyEvent.ACTION_DOWN
                || event.getRepeatCount() != 0) {
            return super.dispatchKeyEvent(event);
        }
        if (event.isAltPressed() || event.isCtrlPressed() || event.isMetaPressed()) {
            return super.dispatchKeyEvent(event);
        }
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_BACK:
                finish();
                return true;
            case KeyEvent.KEYCODE_1:
                setPlane(CubePlanePrefs.PLANE_FRONT);
                return true;
            case KeyEvent.KEYCODE_2:
                setPlane(CubePlanePrefs.PLANE_REAR);
                return true;
            default:
                return super.dispatchKeyEvent(event);
        }
    }

    private void setPlane(String p) {
        plane = CubePlanePrefs.normalizePlane(p);
        refresh();
    }

    private void refresh() {
        MatrixSource s = CubePlanePrefs.source(this, plane);
        boolean spinning = CubePlanePrefs.autoSpin(this, plane);
        boolean rear = CubePlanePrefs.PLANE_REAR.equals(plane);
        if (state != null) {
            state.setText((rear ? "Rear" : "Main") + " · " + s.label
                + " · spin " + (spinning ? "on" : "off"));
        }
        if (tabs != null) CubeSettingsUi.setTab(tabs, rear ? 1 : 0);
        for (int i = 0; i < sourceRows.size(); i++) {
            CubeSettingsUi.setChosen(sourceRows.get(i), sourceOrder.get(i) == s);
        }
        CubeSettingsUi.setSwitch(spin, spinning);
    }

    private void launchPkg(String pkg) {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i != null) startActivity(i);
        } catch (Exception ignored) {}
    }

    private void openWallpaperPicker() {
        try {
            Intent i = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
            i.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                new ComponentName(this, CubeWallpaperService.class));
            startActivity(i);
        } catch (Exception e) {
            try {
                startActivity(new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER));
            } catch (Exception e2) {
                Toast.makeText(this, "Open Settings → Wallpaper", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void openDreamSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_DREAM_SETTINGS));
        } catch (Exception e) {
            Toast.makeText(this, "Open Settings → Screen saver", Toast.LENGTH_SHORT).show();
        }
    }
}
