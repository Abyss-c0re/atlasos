package com.titanus2.controls.subdisplay;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.view.KeyEvent;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.titanus2.controls.ui.UiKit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Pick up to 6 apps for the rear Apps launcher. Stays in Titan Controls.
 */
public class SubDisplayAppsActivity extends Activity {
    private static final String[] BUILTIN = {
        ":clock", ":calc", ":camera", ":files", ":torch"
    };

    private TextView state;
    private final List<String> selected = new ArrayList<>();
    private final List<LinearLayout> rows = new ArrayList<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        selected.addAll(SubDisplayPrefs.launcherIds(this));

        ScrollView sc = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        UiKit.screen(root);
        UiKit.prepareScroll(sc);
        sc.addView(root);

        UiKit.title(root, "Rear apps");
        state = UiKit.stateLine(root);

        UiKit.section(root, "Built-in");
        for (String id : BUILTIN) {
            addRow(root, SubDisplayPrefs.launcherLabel(this, id), id);
        }

        UiKit.section(root, "Installed");
        PackageManager pm = getPackageManager();
        android.content.Intent main = new android.content.Intent(android.content.Intent.ACTION_MAIN);
        main.addCategory(android.content.Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(main, 0);
        Collections.sort(apps, (x, y) -> x.loadLabel(pm).toString()
            .compareToIgnoreCase(y.loadLabel(pm).toString()));
        String self = getPackageName();
        for (ResolveInfo ri : apps) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg == null || pkg.equals(self)) continue;
            addRow(root, ri.loadLabel(pm).toString(), pkg);
        }

        TextView kb = UiKit.mono(root);
        kb.setText("Tap to add or remove · 6 max · Esc");
        setContentView(sc);
        refresh();
    }

    private void addRow(LinearLayout root, String label, String id) {
        final String key = id;
        LinearLayout row = UiKit.navRow(root, label, "", () -> toggle(key));
        row.setTag(key);
        rows.add(row);
    }

    private void toggle(String id) {
        if (selected.contains(id)) {
            selected.remove(id);
        } else if (selected.size() >= SubDisplayPrefs.LAUNCHER_MAX) {
            UiKit.toast(this, "6 apps max");
            return;
        } else {
            selected.add(id);
        }
        SubDisplayPrefs.setLauncherIds(this, selected);
        refresh();
    }

    private void refresh() {
        if (state != null) {
            state.setText(selected.size() + " of " + SubDisplayPrefs.LAUNCHER_MAX
                + " · " + SubDisplayPrefs.launcherSummary(this));
        }
        for (LinearLayout row : rows) {
            Object tag = row.getTag();
            boolean on = tag instanceof String && selected.contains(tag);
            UiKit.setNavSummary(row, on ? "On rear" : "");
            row.setSelected(on);
            row.setActivated(on);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event != null && event.getAction() == KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 0
                && (event.getKeyCode() == KeyEvent.KEYCODE_ESCAPE
                    || event.getKeyCode() == KeyEvent.KEYCODE_BACK)) {
            finish();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
}
