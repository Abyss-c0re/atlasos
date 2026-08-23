package com.titanus2.controls.devtools;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.titanus2.controls.AgentBridge;
import com.titanus2.controls.DebugPrefs;
import com.titanus2.controls.ui.UiKit;

/**
 * Dev tools: Remote ADB, USB ADB, lab debug.
 * Remote ADB logic lives in {@link RemoteAdbUi} + {@code titan2-remote-adb.sh}.
 */
public class DevToolsActivity extends Activity {
    private final Handler h = new Handler(Looper.getMainLooper());
    private TextView status;
    private TextView debugState;
    private RemoteAdbUi remoteAdb;
    private NanobotPairUi nanobotPair;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (remoteAdb != null) remoteAdb.onResumeTick();
            if (nanobotPair != null) nanobotPair.onResumeTick();
            refreshState();
            h.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sc = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        UiKit.screen(root);
        sc.addView(root);

        UiKit.title(root, "Dev");

        remoteAdb = new RemoteAdbUi(this);
        remoteAdb.build(root);

        nanobotPair = new NanobotPairUi(this);
        nanobotPair.build(root);

        // ---- USB ADB ----
        UiKit.section(root, "USB ADB");
        UiKit.button(root, "Enable USB debugging", () -> {
            boolean ok = enableAdbFromApp();
            AgentBridge.put(this, AgentBridge.DEV_ACTION, "enable_adb");
            UiKit.toast(this, ok ? "USB ADB on — accept RSA" : "partial");
            refreshState();
            h.postDelayed(this::refreshState, 1500);
        });
        UiKit.button(root, "Developer options", () -> {
            enableDeveloperOptions();
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
            } catch (Exception e) {
                try {
                    startActivity(new Intent("com.android.settings.APPLICATION_DEVELOPMENT_SETTINGS"));
                } catch (Exception e2) {
                    UiKit.toast(this, "open Settings → Developer options");
                }
            }
        });
        UiKit.button(root, "USB settings", () -> {
            try {
                Intent i = new Intent();
                i.setClassName("com.android.settings",
                    "com.android.settings.Settings$UsbDetailsActivity");
                startActivity(i);
            } catch (Exception e) {
                UiKit.toast(this, "Developer → Default USB");
            }
        });

        // ---- Debug ----
        UiKit.section(root, "Debug");
        DebugPrefs dbg = new DebugPrefs(this);
        UiKit.toggle(root, "Key press popup", dbg.keyPressPopup(), on -> {
            dbg.setKeyPressPopup(on);
            refreshDebug();
        });
        UiKit.toggle(root, "Layout popup", dbg.layoutToasts(), on -> {
            dbg.setLayoutToasts(on);
            refreshDebug();
        });
        UiKit.toggle(root, "Unknown action popup", dbg.unknownActionToasts(), on -> {
            dbg.setUnknownActionToasts(on);
            refreshDebug();
        });
        debugState = UiKit.mono(root);

        UiKit.section(root, "State");
        status = UiKit.mono(root);
        TextView kbHint = UiKit.mono(root);
        kbHint.setText("W Remote ON · X OFF · C copy · Esc");

        setContentView(sc);
        refreshState();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event == null) return super.dispatchKeyEvent(event);
        if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() != 0) {
            return super.dispatchKeyEvent(event);
        }
        if (event.isAltPressed() || event.isCtrlPressed()
                || event.isMetaPressed() || event.isShiftPressed()) {
            return super.dispatchKeyEvent(event);
        }
        int kc = event.getKeyCode();
        DebugPrefs dbg = new DebugPrefs(this);
        switch (kc) {
            case KeyEvent.KEYCODE_ESCAPE:
                finish();
                return true;
            case KeyEvent.KEYCODE_E:
                boolean ok = enableAdbFromApp();
                AgentBridge.put(this, AgentBridge.DEV_ACTION, "enable_adb");
                UiKit.toast(this, ok ? "ADB on — accept RSA" : "partial");
                refreshState();
                return true;
            case KeyEvent.KEYCODE_W:
                if (remoteAdb != null) remoteAdb.keyOn();
                return true;
            case KeyEvent.KEYCODE_X:
                if (remoteAdb != null) remoteAdb.keyOff();
                return true;
            case KeyEvent.KEYCODE_C:
                if (remoteAdb != null) remoteAdb.keyCopy();
                return true;
            case KeyEvent.KEYCODE_K:
                dbg.setKeyPressPopup(!dbg.keyPressPopup());
                refreshDebug();
                return true;
            case KeyEvent.KEYCODE_L:
                dbg.setLayoutToasts(!dbg.layoutToasts());
                refreshDebug();
                return true;
            case KeyEvent.KEYCODE_U:
                dbg.setUnknownActionToasts(!dbg.unknownActionToasts());
                refreshDebug();
                return true;
            default:
                break;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (remoteAdb != null && remoteAdb.onActivityResult(requestCode, resultCode)) {
            return;
        }
    }

    @Override protected void onResume() {
        super.onResume();
        h.removeCallbacks(tick);
        h.post(tick);
    }

    @Override protected void onPause() {
        h.removeCallbacks(tick);
        super.onPause();
    }

    private boolean enableDeveloperOptions() {
        try {
            return Settings.Global.putInt(getContentResolver(),
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 1);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean enableAdbFromApp() {
        boolean ok = enableDeveloperOptions();
        try {
            ok = Settings.Global.putInt(getContentResolver(),
                Settings.Global.ADB_ENABLED, 1) && ok;
        } catch (Exception e) {
            ok = false;
        }
        return ok;
    }

    private void refreshState() {
        int dev = -1, adb = -1;
        try {
            dev = Settings.Global.getInt(getContentResolver(),
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0);
        } catch (Exception ignored) {}
        try {
            adb = Settings.Global.getInt(getContentResolver(),
                Settings.Global.ADB_ENABLED, 0);
        } catch (Exception ignored) {}
        String tcp = tryGetProp("service.adb.tcp.port");
        if (tcp == null || tcp.isEmpty()) tcp = "-";
        StringBuilder sb = new StringBuilder();
        sb.append("usb_debug=").append(adb == 1 ? "on" : "off")
            .append("  dev_opts=").append(dev == 1 ? "on" : "off")
            .append("\ntcp=").append(tcp).append('\n');
        String ag = AgentBridge.readStatus(AgentBridge.STATUS_AGENT);
        sb.append(ag != null ? ag.trim() : "pad-agent: no status");
        if (status != null) status.setText(sb.toString());
        refreshDebug();
    }

    private void refreshDebug() {
        if (debugState == null) return;
        DebugPrefs d = new DebugPrefs(this);
        int skp = -1;
        try {
            skp = Settings.System.getInt(getContentResolver(),
                DebugPrefs.SHOW_KEY_PRESSES, 0);
        } catch (Exception ignored) {}
        debugState.setText(
            "key_press_popup=" + (d.keyPressPopup() ? "on" : "off")
                + " (system show_key_presses=" + skp + ")"
                + "\nlayout=" + (d.layoutToasts() ? "on" : "off")
                + " unknown=" + (d.unknownActionToasts() ? "on" : "off"));
    }

    private static String tryGetProp(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Object v = sp.getMethod("get", String.class, String.class)
                .invoke(null, key, "");
            return v != null ? v.toString() : "";
        } catch (Exception e) {
            return "";
        }
    }
}
