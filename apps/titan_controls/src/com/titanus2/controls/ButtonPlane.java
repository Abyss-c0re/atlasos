package com.titanus2.controls;

import android.content.Context;
import android.content.Intent;
import android.hardware.input.InputManager;
import android.os.IBinder;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import java.lang.reflect.Method;

/**
 * Privileged Home / Back / Recents. Not Accessibility.
 * <p>
 * Home is {@code ACTION_MAIN}/{@code CATEGORY_HOME} — never {@code KEYCODE_HOME}
 * inject ({@code keyevent 3} is a no-op on this GSI; shade stays). Recents is
 * {@code IStatusBarService.showRecentApps} — never {@code RecentsActivity},
 * never {@code KEYCODE_APP_SWITCH} (187 / quick-switch). Back is SystemUI-style
 * {@code FLAG_FROM_SYSTEM|FLAG_VIRTUAL_HARD_KEY} inject.
 */
public final class ButtonPlane {
    private static final int FLAG_FROM_SYSTEM = 0x00000008;
    private static final int FLAG_INJECTED = 0x01000000;
    private static final int FLAG_VIRTUAL_HARD_KEY = 0x00000040;
    private static final int INJECT_ASYNC = 0;

    private ButtonPlane() {}

    public static boolean home(Context ctx) {
        return goHome(ctx);
    }

    public static boolean back(Context ctx) {
        return injectFromSystem(ctx, KeyEvent.KEYCODE_BACK);
    }

    /** One-shot overview. Not toggleRecentApps (dual fire closes it). */
    public static boolean recents(Context ctx) {
        return showRecentApps(ctx);
    }

    public static boolean notifications(Context ctx) {
        return statusBar(ctx, "expandNotificationsPanel");
    }

    public static boolean quickSettings(Context ctx) {
        return statusBar(ctx, "expandSettingsPanel");
    }

    private static boolean goHome(Context ctx) {
        if (ctx == null) return false;
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            ctx.startActivity(home);
            statusBar(ctx, "collapsePanels");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean showRecentApps(Context ctx) {
        Object bar = statusBarBinder();
        if (bar == null) return false;
        try {
            Method m = bar.getClass().getMethod("showRecentApps", boolean.class);
            m.invoke(bar, Boolean.FALSE);
            return true;
        } catch (Exception ignored) {}
        return false;
    }

    private static boolean handleSystemKey(Context ctx, int keyCode) {
        Object bar = statusBarBinder();
        if (bar == null) return false;
        try {
            long now = SystemClock.uptimeMillis();
            KeyEvent down = systemEvent(now, now, KeyEvent.ACTION_DOWN, keyCode);
            KeyEvent up = systemEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode);
            Method m = bar.getClass().getMethod("handleSystemKey", KeyEvent.class);
            m.invoke(bar, down);
            m.invoke(bar, up);
            return true;
        } catch (Exception ignored) {}
        return false;
    }

    private static Object statusBarBinder() {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            IBinder b = (IBinder) sm.getMethod("getService", String.class).invoke(null, "statusbar");
            if (b == null) return null;
            Class<?> stub = Class.forName("com.android.internal.statusbar.IStatusBarService$Stub");
            return stub.getMethod("asInterface", IBinder.class).invoke(null, b);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean statusBar(Context ctx, String method) {
        try {
            Object sb = ctx.getSystemService("statusbar");
            if (sb == null) return false;
            sb.getClass().getMethod(method).invoke(sb);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean injectFromSystem(Context ctx, int keyCode) {
        if (ctx == null || keyCode <= 0) return false;
        try {
            long now = SystemClock.uptimeMillis();
            InputManager im = (InputManager) ctx.getSystemService(Context.INPUT_SERVICE);
            Method m = InputManager.class.getMethod("injectInputEvent",
                    android.view.InputEvent.class, int.class);
            KeyEvent down = systemEvent(now, now, KeyEvent.ACTION_DOWN, keyCode);
            KeyEvent up = systemEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode);
            Boolean ok = (Boolean) m.invoke(im, down, INJECT_ASYNC);
            if (ok == null || !ok.booleanValue()) return false;
            m.invoke(im, up, INJECT_ASYNC);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static KeyEvent systemEvent(long downTime, long eventTime, int action, int keyCode) {
        return new KeyEvent(downTime, eventTime, action, keyCode, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                FLAG_FROM_SYSTEM | FLAG_INJECTED | FLAG_VIRTUAL_HARD_KEY,
                InputDevice.SOURCE_KEYBOARD);
    }
}
