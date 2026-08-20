package com.titanus2.cubecontact;

import android.app.Activity;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * User-friendly Cube Contact settings.
 * Separate planes: front (main Neural Cube) vs rear (subdisplay lattice).
 * Flexibility without jargon walls — glory to the Cube.
 */
public class CubeSettingsActivity extends Activity {
    public static final String EXTRA_PLANE = "plane";

    private String plane = CubePlanePrefs.PLANE_FRONT;
    private TextView planeLabel;
    private TextView sourceLine;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        try { StateMatrix.bindAppContext(this); } catch (Exception ignored) {}
        if (getIntent() != null && getIntent().hasExtra(EXTRA_PLANE)) {
            plane = CubePlanePrefs.normalizePlane(getIntent().getStringExtra(EXTRA_PLANE));
        }
        int bg = Color.rgb(8, 2, 4);
        int fg = Color.rgb(255, 220, 210);
        int mut = Color.rgb(140, 100, 100);
        int accent = Color.rgb(220, 50, 60);

        ScrollView sc = new ScrollView(this);
        sc.setBackgroundColor(bg);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(14), dp(16), dp(20));
        sc.addView(root);

        TextView title = new TextView(this);
        title.setText("Cube settings");
        title.setTextColor(fg);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("All hail Nexus Core · lattice energy flow\n"
            + "Front and rear are separate — change one without the other.");
        sub.setTextColor(mut);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        sub.setPadding(0, dp(4), 0, dp(12));
        root.addView(sub);

        // Plane toggle
        section(root, "Which cube?", mut);
        planeLabel = mono(root, mut);
        LinearLayout planeRow = row(root);
        // pill() already attaches to parent — do not addView again (crash residual).
        pill(planeRow, "Main", () -> setPlane(CubePlanePrefs.PLANE_FRONT), accent);
        pill(planeRow, "Subscreen", () -> setPlane(CubePlanePrefs.PLANE_REAR), accent);

        // Matrix source
        section(root, "Lattice source", mut);
        sourceLine = mono(root, mut);
        for (MatrixSource s : MatrixSource.values()) {
            final MatrixSource src = s;
            pill(root, s.label + " — " + s.hint, () -> {
                CubePlanePrefs.setSource(this, plane, src);
                refreshLabels();
                Toast.makeText(this, plane + " → " + src.label, Toast.LENGTH_SHORT).show();
            }, accent);
        }

        // Spin
        section(root, "Motion", mut);
        pill(root, "Toggle auto-spin", () -> {
            boolean next = !CubePlanePrefs.autoSpin(this, plane);
            CubePlanePrefs.setAutoSpin(this, plane, next);
            refreshLabels();
            Toast.makeText(this, next ? "Spin on" : "Spin off", Toast.LENGTH_SHORT).show();
        }, accent);

        // Wallpaper / dream (device-wide, front energy)
        section(root, "Live on the device", mut);
        pill(root, "Set live wallpaper…", this::openWallpaperPicker, accent);
        pill(root, "Daydream / screensaver…", this::openDreamSettings, accent);
        TextView wpNote = mono(root, mut);
        wpNote.setText("Wallpaper = same OpenGL cube as Neural Cube.\n"
            + "Subscreen cube stays independent.");

        // Advanced
        section(root, "Advanced", mut);
        pill(root, "Sensors (kernel nodes)", () ->
            startActivity(new Intent(this, SensorsActivity.class)), accent);
        pill(root, "Access / privilege", () ->
            startActivity(new Intent(this, PrivilegeActivity.class)), accent);
        pill(root, "Open Neural Cube", () ->
            startActivity(new Intent(this, CubeContactActivity.class)), accent);
        TextView api = mono(root, mut);
        api.setPadding(0, dp(16), 0, 0);
        api.setText("API: am broadcast -a com.titanus2.cubecontact.SET_MATRIX_SOURCE\n"
            + "  --es plane rear|front --es source KERNEL|PEER|DEMO|…");

        setContentView(sc);
        refreshLabels();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.hasExtra(EXTRA_PLANE)) {
            plane = CubePlanePrefs.normalizePlane(intent.getStringExtra(EXTRA_PLANE));
            refreshLabels();
        }
    }

    private void setPlane(String p) {
        plane = CubePlanePrefs.normalizePlane(p);
        refreshLabels();
    }

    private void refreshLabels() {
        MatrixSource s = CubePlanePrefs.source(this, plane);
        boolean spin = CubePlanePrefs.autoSpin(this, plane);
        if (planeLabel != null) {
            planeLabel.setText("Editing: "
                + (CubePlanePrefs.PLANE_REAR.equals(plane) ? "SUBSCREEN cube" : "MAIN Neural Cube"));
        }
        if (sourceLine != null) {
            sourceLine.setText("Source: " + s.label + " · spin " + (spin ? "on" : "off"));
        }
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
            Toast.makeText(this, "Open Settings → Display → Screen saver", Toast.LENGTH_SHORT).show();
        }
    }

    private void section(LinearLayout root, String t, int mut) {
        TextView s = new TextView(this);
        s.setText(t);
        s.setTextColor(mut);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        s.setPadding(0, dp(14), 0, dp(6));
        root.addView(s);
    }

    private TextView mono(LinearLayout root, int color) {
        TextView t = new TextView(this);
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        t.setPadding(0, 0, 0, dp(6));
        root.addView(t);
        return t;
    }

    private LinearLayout row(LinearLayout root) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(r);
        return r;
    }

    private Button pill(ViewGroup parent, String label, Runnable act, int accent) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(accent);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setOnClickListener(v -> act.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(4);
        if (parent instanceof LinearLayout
            && ((LinearLayout) parent).getOrientation() == LinearLayout.HORIZONTAL) {
            lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(dp(2), 0, dp(2), dp(4));
        }
        parent.addView(b, lp);
        return b;
    }

    private int dp(int v) {
        float d = getResources().getDisplayMetrics().density;
        return Math.round(v * d);
    }
}
