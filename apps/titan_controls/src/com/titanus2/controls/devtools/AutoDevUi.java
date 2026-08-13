package com.titanus2.controls.devtools;

import android.app.Activity;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.titanus2.controls.ui.UiKit;

/**
 * AUTO DEV MODE section for Developer hub.
 * ON/OFF + BlackCube pair require Atlas Authentication Agent biometrics.
 */
final class AutoDevUi {
    private static final String TAG = "AutoDevUi";
    static final int REQ_BIO_ON = 0xAD01;
    static final int REQ_BIO_OFF = 0xAD02;
    static final int REQ_BIO_PAIR = 0xAD03;

    private final Activity act;
    private final Handler h = new Handler(Looper.getMainLooper());
    private UiKit.Toggle master;
    private UiKit.Toggle analyze;
    private UiKit.Toggle peer;
    private TextView banner;
    private TextView detail;
    private EditText urlField;
    private boolean suppress;

    AutoDevUi(Activity act) {
        this.act = act;
    }

    void build(LinearLayout root) {
        // PRODUCT_UX: short labels, mono facts — no marketing notes.
        UiKit.section(root, "Auto Dev");
        banner = UiKit.mono(root);
        master = UiKit.toggle(root, "Auto Dev", AutoDevMode.isOn(act), want -> {
            if (suppress) return;
            snapMaster();
            if (want) {
                launchBio(REQ_BIO_ON, "Enable Auto Dev");
            } else {
                launchBio(REQ_BIO_OFF, "Disable Auto Dev");
            }
        });

        analyze = UiKit.toggle(root, "Analyze shots",
            AutoDevMode.analyzeShots(act), on -> {
                AutoDevMode.setAnalyzeShots(act, on);
                paint();
            });
        peer = UiKit.toggle(root, "BlackCube peer",
            AutoDevMode.blackCubePeer(act), on -> {
                AutoDevMode.setBlackCubePeer(act, on);
                paint();
            });

        urlField = new EditText(act);
        urlField.setHint("peer URL");
        urlField.setText(AutoDevMode.peerUrl(act));
        urlField.setSingleLine(true);
        urlField.setTextSize(14f);
        root.addView(urlField, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout row = UiKit.row(root);
        UiKit.flexButton(row, "Save URL", () -> {
            AutoDevMode.setPeerUrl(act, urlField.getText() != null
                ? urlField.getText().toString() : "");
            UiKit.toast(act, "saved");
            paint();
        });
        UiKit.flexButton(row, "Pair", () -> {
            if (urlField.getText() != null) {
                AutoDevMode.setPeerUrl(act, urlField.getText().toString());
            }
            launchBio(REQ_BIO_PAIR, "Pair BlackCube · "
                + AutoDevMode.peerUrl(act));
        });
        UiKit.button(root, "Unpair", () -> {
            AutoDevMode.clearPair(act);
            UiKit.toast(act, "unpaired");
            paint();
        });

        detail = UiKit.mono(root);
        paint();
    }

    void onResumeTick() {
        paint();
    }

    boolean onActivityResult(int requestCode, int resultCode) {
        if (requestCode != REQ_BIO_ON && requestCode != REQ_BIO_OFF
                && requestCode != REQ_BIO_PAIR) {
            return false;
        }
        if (resultCode != Activity.RESULT_OK) {
            UiKit.toast(act, "biometrics denied");
            paint();
            return true;
        }
        if (requestCode == REQ_BIO_ON) {
            AutoDevMode.enable(act);
            UiKit.toast(act, "Auto Dev on");
        } else if (requestCode == REQ_BIO_OFF) {
            AutoDevMode.disable(act);
            UiKit.toast(act, "Auto Dev off");
        } else {
            AutoDevMode.markPaired(act, "BlackCube nanobot");
            AutoDevMode.setBlackCubePeer(act, true);
            if (!AutoDevMode.isOn(act)) {
                AutoDevMode.enable(act);
            }
            UiKit.toast(act, "paired");
        }
        paint();
        return true;
    }

    private void paint() {
        boolean on = AutoDevMode.isOn(act);
        suppress = true;
        try {
            if (master != null) master.setChecked(on);
            if (analyze != null) analyze.setChecked(AutoDevMode.analyzeShots(act));
            if (peer != null) peer.setChecked(AutoDevMode.blackCubePeer(act));
        } finally {
            suppress = false;
        }
        if (banner != null) {
            banner.setText(on
                ? ("on"
                    + (AutoDevMode.analyzeShots(act) ? " · shots" : "")
                    + (AutoDevMode.blackCubePeer(act) ? " · peer" : "")
                    + (AutoDevMode.isPaired(act) ? " · paired" : ""))
                : "off · fail-closed");
        }
        if (detail != null) {
            detail.setText(AutoDevMode.statusLine(act));
        }
    }

    private void snapMaster() {
        h.post(() -> {
            suppress = true;
            try {
                if (master != null) master.setChecked(AutoDevMode.isOn(act));
            } finally {
                suppress = false;
            }
        });
    }

    private void launchBio(int req, String reason) {
        wakeAtlas();
        try {
            Intent i = new Intent();
            i.setClassName("com.titanus2.atlas", "com.titanus2.atlas.AuthPromptActivity");
            i.putExtra("auth_id", "autodev-" + req + "-" + System.currentTimeMillis());
            i.putExtra("auth_reason", reason);
            i.putExtra("auth_for_result", true);
            //noinspection deprecation
            act.startActivityForResult(i, req);
        } catch (Exception e) {
            Log.w(TAG, "atlas auth", e);
            UiKit.toast(act, "Atlas auth missing — install Atlas");
            paint();
        }
    }

    private void wakeAtlas() {
        try {
            Intent wake = new Intent();
            wake.setClassName("com.titanus2.atlas", "com.titanus2.atlas.AtlasSessionService");
            wake.setAction("com.titanus2.atlas.ENSURE_AUTH_AGENT");
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                act.startForegroundService(wake);
            } else {
                act.startService(wake);
            }
        } catch (Exception ignored) {
        }
    }
}
