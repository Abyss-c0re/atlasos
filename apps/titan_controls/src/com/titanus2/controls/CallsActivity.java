package com.titanus2.controls;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.titanus2.controls.ui.UiKit;

/**
 * IMS plane in Controls. Settings → SIMs → Calls is the only voice pin.
 */
public class CallsActivity extends Activity {
    public static final String ACTION_OPEN_CALLS = "com.titanus2.controls.action.OPEN_CALLS";

    private final Handler h = new Handler(Looper.getMainLooper());
    private TextView status;
    private UiKit.Toggle tBinder;
    private UiKit.Toggle tMtk;
    private UiKit.Toggle tForce;
    private boolean paused;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiKit.applyOpaqueWindow(this);
        setTitle("Calls");

        ScrollView scroll = new ScrollView(this);
        UiKit.prepareScroll(scroll);
        LinearLayout root = new LinearLayout(this);
        UiKit.screen(root);
        scroll.addView(root, new ScrollView.LayoutParams(
            ScrollView.LayoutParams.MATCH_PARENT,
            ScrollView.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        UiKit.title(root, "Calls");
        UiKit.note(root, "Settings → SIMs → Calls is the voice pin. Controls owns IMS bind. TrebleApp is not IMS.");
        status = UiKit.mono(root);

        tBinder = UiKit.toggle(root, "Binder thread on incoming",
            ImsCalls.planeOn(this, AgentBridge.IMS_BINDER),
            on -> {
                ImsCalls.setPlane(this, AgentBridge.IMS_BINDER, on);
                h.postDelayed(this::refresh, 600);
            });
        tMtk = UiKit.toggle(root, "MTK IMS",
            ImsCalls.planeOn(this, AgentBridge.IMS_MTK),
            on -> {
                ImsCalls.setPlane(this, AgentBridge.IMS_MTK, on);
                h.postDelayed(this::refresh, 600);
            });
        tForce = UiKit.toggle(root, "Force VoLTE overlays",
            ImsCalls.planeOn(this, AgentBridge.IMS_FORCE_VOLTE),
            on -> {
                ImsCalls.setPlane(this, AgentBridge.IMS_FORCE_VOLTE, on);
                h.postDelayed(this::refresh, 600);
            });

        UiKit.section(root, "Bind IMS");
        LinearLayout bindRow = UiKit.row(root);
        UiKit.flexButton(bindRow, "SIM 1", () -> pickBind(ImsCalls.BIND_1));
        UiKit.flexButton(bindRow, "SIM 2", () -> pickBind(ImsCalls.BIND_2));
        UiKit.flexButton(bindRow, "Both", () -> pickBind(ImsCalls.BIND_BOTH));

        LinearLayout btns = UiKit.row(root);
        UiKit.flexButton(btns, "Rearm", this::rearm);
        UiKit.flexButton(btns, "Heal", this::heal);
        UiKit.flexButton(btns, "Refresh", this::refresh);
        LinearLayout more = UiKit.row(root);
        UiKit.flexButton(more, "VoLTE config", this::applyVolteCc);
        UiKit.flexButton(more, "Settings SIMs", this::openSimSettings);
        UiKit.flexButton(more, "Copy dump", this::copyDump);

        TextView hint = UiKit.mono(root);
        hint.setText("A rearm · H heal · R refresh · V VoLTE · S SIMs · C copy · Esc");
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        paused = false;
        refresh();
    }

    @Override
    protected void onPause() {
        paused = true;
        super.onPause();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event != null && event.getAction() == KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 0) {
            int kc = event.getKeyCode();
            if (kc == KeyEvent.KEYCODE_ESCAPE) {
                finish();
                return true;
            }
            if (kc == KeyEvent.KEYCODE_R) {
                refresh();
                return true;
            }
            if (kc == KeyEvent.KEYCODE_H) {
                heal();
                return true;
            }
            if (kc == KeyEvent.KEYCODE_A) {
                rearm();
                return true;
            }
            if (kc == KeyEvent.KEYCODE_V) {
                applyVolteCc();
                return true;
            }
            if (kc == KeyEvent.KEYCODE_S) {
                openSimSettings();
                return true;
            }
            if (kc == KeyEvent.KEYCODE_C) {
                copyDump();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void refresh() {
        if (isFinishing() || status == null) return;
        try {
            ImsCalls.Detect d = ImsCalls.detect(this);
            status.setText(d.dump());
            sync(tBinder, ImsCalls.planeOn(this, AgentBridge.IMS_BINDER));
            sync(tMtk, ImsCalls.planeOn(this, AgentBridge.IMS_MTK));
            sync(tForce, ImsCalls.planeOn(this, AgentBridge.IMS_FORCE_VOLTE));
        } catch (Exception e) {
            status.setText("detect error");
        }
    }

    private static void sync(UiKit.Toggle t, boolean on) {
        if (t != null && t.isChecked() != on) t.setChecked(on);
    }

    private void pickBind(String slots) {
        if (!ImsCalls.setBindSlots(this, slots)) {
            UiKit.toast(this, "Tray empty -- not bound");
            return;
        }
        UiKit.toast(this, "Bind " + ImsCalls.bindLabel(slots));
        h.postDelayed(this::refresh, 600);
        h.postDelayed(this::refresh, 2200);
    }

    private void heal() {
        ImsCalls.requestHeal(this);
        UiKit.toast(this, "Heal queued");
        h.postDelayed(this::refresh, 800);
        h.postDelayed(this::refresh, 2500);
        h.postDelayed(this::refresh, 6000);
    }

    private void rearm() {
        ImsCalls.requestRearm(this);
        UiKit.toast(this, "Rearm queued (enable only)");
        h.postDelayed(this::refresh, 800);
        h.postDelayed(this::refresh, 2500);
    }

    private void applyVolteCc() {
        ImsCalls.forceVolteCarrierConfig(this);
        ImsCalls.requestRearm(this);
        UiKit.toast(this, "VoLTE carrier override + rearm");
        h.postDelayed(this::refresh, 800);
        h.postDelayed(this::refresh, 2500);
    }

    private void openSimSettings() {
        try {
            android.content.Intent i = new android.content.Intent(
                android.provider.Settings.ACTION_NETWORK_OPERATOR_SETTINGS);
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            UiKit.toast(this, "Open Settings → SIMs → Calls");
        }
    }

    private void copyDump() {
        try {
            String dump = ImsCalls.detect(this).dump();
            android.content.ClipboardManager cm =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm == null) return;
            cm.setPrimaryClip(android.content.ClipData.newPlainText("ims", dump));
            UiKit.toast(this, "Dump copied");
        } catch (Exception e) {
            UiKit.toast(this, "Copy failed");
        }
    }
}
