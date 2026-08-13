package com.android.fmradio;

/**
 * JNI surface expected by vendor {@code libaguifmjni.so}.
 * <p>
 * The blob registers natives against this exact class name
 * ({@code com/android/fmradio/FmNative}). Method signatures match the
 * MediaTek FM JNI used on Unihertz Titan 2 stock.
 * <p>
 * This is <b>not</b> the stock Agui app — only the stable native bridge
 * so our open Titan FM app can drive the same chip.
 */
public final class FmNative {
    static {
        System.loadLibrary("aguifmjni");
    }

    private FmNative() {}

    public static native boolean openDev();

    public static native boolean closeDev();

    /** Power FM up and tune to {@code frequencyMhz} (e.g. 97.5f). */
    public static native boolean powerUp(float frequencyMhz);

    /** {@code type} 0 = normal power down. */
    public static native boolean powerDown(int type);

    public static native boolean tune(float frequencyMhz);

    /** Seek from {@code frequencyMhz}; {@code upward} true = up. Returns new freq or 0. */
    public static native float seek(float frequencyMhz, boolean upward);

    public static native boolean stopScan();

    public static native short[] autoScan();

    /** 0 = unmute, non-zero often mute depending on HAL; stock uses boolean mute. */
    public static native int setMute(boolean mute);

    public static native int setRds(boolean enable);

    public static native int isRdsSupport();

    public static native short readRds();

    public static native byte[] getPs();

    public static native byte[] getLrText();

    public static native short readRdsBler();

    public static native int readRssi();

    public static native short readCapArray();

    public static native boolean setStereoMono(boolean mono);

    public static native boolean stereoMono();

    /**
     * Antenna select. Stock/RFM-style: {@code 0} default/headset wire,
     * {@code 1} internal (weaker). Titan 2 has a built-in path.
     */
    public static native int switchAntenna(int antenna);

    public static native short activeAf();

    public static native short[] emcmd(short[] args);

    public static native boolean emsetth(int index, int value);

    public static native int[] getHardwareVersion();
}
