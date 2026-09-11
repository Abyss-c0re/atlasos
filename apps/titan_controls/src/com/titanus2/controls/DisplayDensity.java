package com.titanus2.controls;

import android.content.Context;
import android.os.IBinder;
import android.os.Process;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;

/**
 * Live display density. Same binder Settings uses:
 * {@code IWindowManager.setForcedDisplayDensityForUser(display, dpi, user)}.
 * {@code cmd window density} always targets USER_CURRENT (-2) and dies without
 * INTERACT_ACROSS_USERS_FULL — do not use it as the apply path.
 * Never density 0. Never transact 14 (ratio). Never write theme_*.
 */
public final class DisplayDensity {
    public static final String SECURE_FORCED = "display_density_forced";
    public static final int MIN = 120;
    public static final int MAX = 480;

    private DisplayDensity() {}

    public static int current(Context c) {
        if (c == null) return MIN;
        try {
            int d = c.getResources().getDisplayMetrics().densityDpi;
            if (d > 0) return d;
        } catch (Exception ignored) {}
        return MIN;
    }

    public static int physical(Context c) {
        Integer wm = invokeInt("getInitialDisplayDensity", displayId(c));
        if (wm != null && wm > 0) return wm;
        try {
            String p = propGet("ro.sf.lcd_density");
            if (p != null && p.length() > 0) {
                int n = Integer.parseInt(p.trim());
                if (n > 0) return n;
            }
        } catch (Exception ignored) {}
        if (c != null) {
            try {
                WindowManager w = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
                Display d = w != null ? w.getDefaultDisplay() : null;
                if (d != null) {
                    DisplayMetrics dm = new DisplayMetrics();
                    d.getRealMetrics(dm);
                    if (dm.densityDpi > 0) return dm.densityDpi;
                }
            } catch (Exception ignored) {}
        }
        return current(c);
    }

    public static int forced(Context c) {
        if (c == null) return 0;
        try {
            return parseDpi(Settings.Secure.getString(c.getContentResolver(), SECURE_FORCED));
        } catch (Exception e) {
            return 0;
        }
    }

    /** True only if the live override (or current metrics) is {@code dpi}. */
    public static boolean apply(Context c, int dpi) {
        if (c == null || dpi <= 0) return false;
        int disp = displayId(c);
        int user = myUserId();
        writeForced(c, dpi);
        propSet("persist.titanus2.cube_density", String.valueOf(dpi));
        boolean wm = setForcedWm(disp, dpi, user);
        if (!wm) {
            wm = setForcedWm(Display.DEFAULT_DISPLAY, dpi, user);
        }
        return isLive(c, dpi);
    }

    public static boolean reset(Context c) {
        if (c == null) return false;
        int disp = displayId(c);
        int user = myUserId();
        try {
            Settings.Secure.putString(c.getContentResolver(), SECURE_FORCED, "");
        } catch (Exception ignored) {}
        clearForcedWm(disp, user);
        clearForcedWm(Display.DEFAULT_DISPLAY, user);
        return liveOverride() == 0;
    }

    public static boolean isLive(Context c, int dpi) {
        if (dpi <= 0) return false;
        if (liveOverride() == dpi) return true;
        return c != null && current(c) == dpi;
    }

    public static int liveOverride() {
        String out = execOut("/system/bin/wm", "density");
        if (out == null || out.isEmpty()) {
            out = execOut("/system/bin/cmd", "window", "density");
        }
        if (out == null) return 0;
        for (String line : out.split("\n")) {
            if (line == null) continue;
            line = line.trim();
            if (!line.startsWith("Override")) continue;
            int sp = line.lastIndexOf(' ');
            if (sp < 0) continue;
            return parseDpi(line.substring(sp + 1));
        }
        return 0;
    }

    private static void writeForced(Context c, int dpi) {
        try {
            Settings.Secure.putString(c.getContentResolver(), SECURE_FORCED, String.valueOf(dpi));
        } catch (Exception ignored) {}
    }

    private static int parseDpi(String s) {
        if (s == null) return 0;
        s = s.trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) return 0;
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return 0;
        }
    }

    private static int displayId(Context c) {
        if (c == null) return Display.DEFAULT_DISPLAY;
        try {
            Display d = c.getDisplay();
            if (d != null) return d.getDisplayId();
        } catch (Exception ignored) {}
        try {
            WindowManager w = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
            Display d = w != null ? w.getDefaultDisplay() : null;
            if (d != null) return d.getDisplayId();
        } catch (Exception ignored) {}
        return Display.DEFAULT_DISPLAY;
    }

    /** Calling user (0), never USER_CURRENT (-2). */
    private static int myUserId() {
        return Process.myUid() / 100000;
    }

    static String lastWmError = "";
    /** IWindowManager.setForcedDisplayDensityForUser — proven on this WMS. Never 14 (ratio). */
    private static final int TX_SET_DENSITY = 12;
    private static final int TX_CLEAR_DENSITY = 13;

    private static boolean setForcedWm(int displayId, int dpi, int userId) {
        if (dpi <= 0) {
            lastWmError = "refuse density<=0";
            return false;
        }
        return transactDensity(TX_SET_DENSITY, displayId, dpi, userId);
    }

    private static boolean clearForcedWm(int displayId, int userId) {
        return transactDensity(TX_CLEAR_DENSITY, displayId, -1, userId);
    }

    private static boolean transactDensity(int code, int displayId, int dpi, int userId) {
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        try {
            IBinder b = windowBinder();
            if (b == null) {
                lastWmError = "no window binder";
                return false;
            }
            data.writeInterfaceToken("android.view.IWindowManager");
            data.writeInt(displayId);
            if (code == TX_SET_DENSITY) data.writeInt(dpi);
            data.writeInt(userId);
            b.transact(code, data, reply, 0);
            reply.readException();
            lastWmError = "";
            return true;
        } catch (Exception e) {
            lastWmError = e.getClass().getSimpleName() + ": " + e.getMessage();
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static IBinder windowBinder() {
        try {
            Object raw = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "window");
            if (raw instanceof IBinder) return (IBinder) raw;
        } catch (Exception ignored) {}
        try {
            Object svc = windowService();
            if (svc instanceof IBinder) return (IBinder) svc;
            if (svc instanceof android.os.IInterface) {
                return ((android.os.IInterface) svc).asBinder();
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static Integer invokeInt(String method, int displayId) {
        try {
            Object svc = windowService();
            if (svc == null) return null;
            Object v = svc.getClass().getMethod(method, int.class).invoke(svc, displayId);
            if (v instanceof Integer) return (Integer) v;
        } catch (Exception ignored) {}
        return null;
    }

    static {
        exemptHiddenApis();
    }

    private static void exemptHiddenApis() {
        try {
            Class<?> vm = Class.forName("dalvik.system.VMRuntime");
            Object rt = vm.getMethod("getRuntime").invoke(null);
            vm.getMethod("setHiddenApiExemptions", String[].class)
                .invoke(rt, (Object) new String[]{"L"});
        } catch (Throwable ignored) {}
    }

    private static Object windowService() {
        try {
            Class<?> stub = Class.forName("android.view.IWindowManager$Stub");
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Object binder = sm.getMethod("getService", String.class).invoke(null, "window");
            if (!(binder instanceof IBinder)) return null;
            return stub.getMethod("asInterface", IBinder.class).invoke(null, binder);
        } catch (Exception e) {
            try {
                return Class.forName("android.view.WindowManagerGlobal")
                    .getMethod("getWindowManagerService").invoke(null);
            } catch (Exception e2) {
                return null;
            }
        }
    }

    private static String propGet(String key) {
        try {
            return (String) Class.forName("android.os.SystemProperties")
                .getMethod("get", String.class, String.class).invoke(null, key, "");
        } catch (Exception e) {
            return "";
        }
    }

    private static void propSet(String key, String val) {
        try {
            Class.forName("android.os.SystemProperties")
                .getMethod("set", String.class, String.class).invoke(null, key, val);
        } catch (Exception ignored) {}
    }

    private static String execOut(String... cmd) {
        java.lang.Process p = null;
        try {
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[256];
            int n;
            java.io.InputStream in = p.getInputStream();
            while ((n = in.read(b)) > 0) buf.write(b, 0, n);
            if (!p.waitFor(4, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            return buf.toString("UTF-8");
        } catch (Exception e) {
            return null;
        } finally {
            if (p != null) p.destroy();
        }
    }
}
