/**
 * Register a fabricated dimension overlay. {@code cmd overlay fabricate}
 * only accepts integer types 0x10–0x1f, so a 0dp inset has to be committed
 * through {@code FabricatedOverlay} itself.
 *
 * <p>Build (host):
 * <pre>
 * javac --release 11 -d /tmp/imeinset patches/host/TitanImeNavInset.java
 * d8 --min-api 26 --output /tmp/titan2-ime-nav-inset.jar /tmp/imeinset/TitanImeNavInset.class
 * </pre>
 * Device, as root:
 * <pre>
 * CLASSPATH=/data/local/tmp/titan2-ime-nav-inset.jar \
 *   app_process /system/bin TitanImeNavInset
 * </pre>
 * Args: overlayName resourceName valueDp unit
 * unit is TypedValue.COMPLEX_UNIT_* (1 = dp). Default overlays
 * android:dimen/taskbar_frame_height as 0dp under com.android.shell:TitanImeNavInset.
 */
public final class TitanImeNavInset {
    public static void main(String[] args) {
        String name = args.length > 0 ? args[0] : "TitanImeNavInset";
        String resource = args.length > 1 ? args[1] : "android:dimen/taskbar_frame_height";
        float value = 0f;
        int unit = 1;
        if (args.length > 2) {
            try {
                value = Float.parseFloat(args[2]);
            } catch (NumberFormatException ex) {
                fail("bad value");
                return;
            }
        }
        if (args.length > 3) {
            try {
                unit = Integer.parseInt(args[3]);
            } catch (NumberFormatException ex) {
                fail("bad unit");
                return;
            }
        }
        try {
            Class<?> foCls = Class.forName("android.content.om.FabricatedOverlay");
            Object overlay = foCls.getConstructor(String.class, String.class)
                    .newInstance(name, "android");
            foCls.getMethod("setOwningPackage", String.class)
                    .invoke(overlay, "com.android.shell");
            if (!setDimen(foCls, overlay, resource, value, unit)) {
                pokeDimen(overlay, resource, value, unit);
            }

            Class<?> builderCls = Class.forName(
                    "android.content.om.OverlayManagerTransaction$Builder");
            Object builder = builderCls.getConstructor().newInstance();
            builderCls.getMethod("registerFabricatedOverlay", foCls).invoke(builder, overlay);

            Object ident = foCls.getMethod("getIdentifier").invoke(overlay);
            Class<?> idCls = Class.forName("android.content.om.OverlayIdentifier");
            boolean armed = false;
            for (java.lang.reflect.Method method : builderCls.getMethods()) {
                if (!"setEnabled".equals(method.getName())) continue;
                Class<?>[] params = method.getParameterTypes();
                if (params.length == 3 && params[0] == idCls && params[1] == boolean.class
                        && params[2] == int.class) {
                    method.invoke(builder, ident, Boolean.TRUE, Integer.valueOf(0));
                    armed = true;
                    break;
                }
            }
            if (!armed) {
                fail("no setEnabled");
                return;
            }

            Class<?> txCls = Class.forName("android.content.om.OverlayManagerTransaction");
            Object tx = builderCls.getMethod("build").invoke(builder);

            Class<?> smCls = Class.forName("android.os.ServiceManager");
            Object binder = smCls.getMethod("getService", String.class).invoke(null, "overlay");
            if (binder == null) {
                fail("overlay service missing");
                return;
            }
            Class<?> stubCls = Class.forName("android.content.om.IOverlayManager$Stub");
            Object service = stubCls.getMethod("asInterface", Class.forName("android.os.IBinder"))
                    .invoke(null, binder);
            Class<?> iface = Class.forName("android.content.om.IOverlayManager");
            iface.getMethod("commit", txCls).invoke(service, tx);
            System.out.println("inset ok " + name + " " + resource);
        } catch (Throwable ex) {
            Throwable cause = ex;
            while (cause.getCause() != null && cause.getCause() != cause) {
                cause = cause.getCause();
            }
            fail(cause.getClass().getSimpleName() + ": " + cause.getMessage());
        }
    }

    /** True when the flagged float setter exists and accepts the value. */
    private static boolean setDimen(Class<?> foCls, Object overlay, String resource,
            float value, int unit) {
        for (java.lang.reflect.Method method : foCls.getMethods()) {
            if (!"setResourceValue".equals(method.getName())) continue;
            Class<?>[] params = method.getParameterTypes();
            if (params.length == 4 && params[0] == String.class && params[1] == float.class
                    && params[2] == int.class && params[3] == String.class) {
                try {
                    method.invoke(overlay, resource, Float.valueOf(value),
                            Integer.valueOf(unit), null);
                    return true;
                } catch (Throwable ex) {
                    return false;
                }
            }
        }
        return false;
    }

    /**
     * Older images hide the float setter. The parcel entry still carries
     * TYPE_DIMENSION (0x05). 0dp is magnitude 0 with the unit in the low bits.
     */
    @SuppressWarnings("unchecked")
    private static void pokeDimen(Object overlay, String resource, float value, int unit)
            throws Exception {
        if (value != 0f) {
            throw new IllegalStateException("poke only encodes 0");
        }
        int data = unit & 0xf;
        try {
            Class<?> tv = Class.forName("android.util.TypedValue");
            data = ((Integer) tv.getMethod("createComplexDimension", float.class, int.class)
                    .invoke(null, Float.valueOf(0f), Integer.valueOf(unit))).intValue();
        } catch (Throwable ignored) {
            // 0dp complex word is just the unit.
        }
        Object internal = overlay.getClass().getMethod("getInternal").invoke(overlay);
        java.lang.reflect.Field entriesField = internal.getClass().getField("entries");
        java.util.List<Object> entries = (java.util.List<Object>) entriesField.get(internal);
        Class<?> entryCls = Class.forName("android.os.FabricatedOverlayInternalEntry");
        Object entry = entryCls.newInstance();
        entryCls.getField("resourceName").set(entry, resource);
        entryCls.getField("dataType").setInt(entry, 0x05);
        entryCls.getField("data").setInt(entry, data);
        entries.add(entry);
    }

    private static void fail(String message) {
        System.out.println("inset fail " + message);
        System.exit(1);
    }
}
