public final class AccConnect {
    public static void main(String[] args) throws Exception {
        int type = args.length > 0 ? Integer.decode(args[0]) : 0x8;
        int state = args.length > 1 ? Integer.parseInt(args[1]) : 1;
        Class<?> c = Class.forName("android.media.AudioSystem");
        try {
            Class<?> aa = Class.forName("android.media.AudioDeviceAttributes");
            Object inst = aa.getConstructor(int.class, String.class).newInstance(type, "");
            Object rc = c.getMethod("setDeviceConnectionState", aa, int.class, int.class, boolean.class)
                    .invoke(null, inst, state, 0, Boolean.FALSE);
            System.out.println("rc=" + rc + " type=0x" + Integer.toHexString(type) + " state=" + state);
            return;
        } catch (Throwable t) {
            System.out.println("aa: " + t);
        }
        Object rc = c.getMethod("setDeviceConnectionState", int.class, int.class, String.class, String.class)
                .invoke(null, type, state, "", "");
        System.out.println("rc4=" + rc);
    }
}
