package com.android.fmradio;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.util.Log;


/**
 * Open FM engine for Titan 2.
 * <p>
 * Chip control via stock-compatible {@link FmNative} / {@code libaguifmjni}.
 * Audio is always software-rendered (FM_TUNER capture → {@link AudioTrack}).
 * Hardware {@code createAudioPatch} is intentionally omitted — it SIGSEGVs
 * ADSP on MisterZtr GSI + this vendor.
 * <p>
 * Sample rate is 48 kHz (stock Agui uses 44.1 kHz and fails HAL open here).
 */
public final class FmEngine {
    private static final String TAG = "TitanFm";

    /** MediaRecorder.AudioSource.RADIO_TUNER / FM_TUNER (hidden). */
    private static final int AUDIO_SOURCE_FM_TUNER = 1998;
    private static final int SAMPLE_RATE = 48000;
    private static final int CHANNEL_IN = AudioFormat.CHANNEL_IN_STEREO;
    private static final int CHANNEL_OUT = AudioFormat.CHANNEL_OUT_STEREO;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    private static final int BUF_SIZE = Math.max(
            AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING) * 4,
            16384);

    public static final float FREQ_MIN = 87.5f;
    public static final float FREQ_MAX = 108.0f;
    public static final float FREQ_STEP = 0.1f;

    public static final int ROUTE_SPEAKER = 0;
    /** System default output. Never pin USB accessory as PCM. */
    public static final int ROUTE_DEFAULT = 1;

    public interface Listener {
        void onState(String line);
        void onPower(boolean on);
        void onFrequency(float mhz);
        void onRds(String ps, String rt);
    }

    private final Context mApp;
    private final AudioManager mAm;
    private final PowerManager.WakeLock mWake;
    private final HandlerThread mWorker;
    private final Handler mH;
    private final Handler mMain;
    private Listener mUiListener;
    private Listener mServiceListener;

    private boolean mDeviceOpen;
    private boolean mPowered;
    private boolean mRendering;
    private float mFreq = 97.5f;
    /** 0 = headset/wire (FM RF antenna), 1 = internal (weaker). */
    private int mAntenna = 1;
    private boolean mAutoAntenna = true;
    private boolean mSpeaker = true;
    private int mRoute = ROUTE_SPEAKER;

    private AudioRecord mRecord;
    private AudioTrack mTrack;
    private Thread mRenderThread;
    private AudioFocusRequest mFocusReq;
    private boolean mFocusHeld;
    private AudioDeviceCallback mDeviceCb;

    private static volatile FmEngine sInstance;

    /** Process-wide engine so UI can die without killing FM. Owned by {@link FmService}. */
    public static FmEngine get(Context context) {
        FmEngine e = sInstance;
        if (e != null) return e;
        synchronized (FmEngine.class) {
            if (sInstance == null) {
                sInstance = new FmEngine(context.getApplicationContext());
            }
            return sInstance;
        }
    }

    public static FmEngine peek() {
        return sInstance;
    }

    private FmEngine(Context context) {
        mApp = context.getApplicationContext();
        mAm = (AudioManager) mApp.getSystemService(Context.AUDIO_SERVICE);
        PowerManager pm = (PowerManager) mApp.getSystemService(Context.POWER_SERVICE);
        mWake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "titanus2:fm");
        mWake.setReferenceCounted(false);
        mWorker = new HandlerThread("TitanFmWorker");
        mWorker.start();
        mH = new Handler(mWorker.getLooper());
        mMain = new Handler(mApp.getMainLooper());
        registerDeviceCallback();
        mH.post(this::refreshAntennaFromDevices);
    }

    public void setUiListener(Listener l) {
        mUiListener = l;
    }

    public void setServiceListener(Listener l) {
        mServiceListener = l;
    }

    /** @deprecated use setUiListener / setServiceListener */
    public void setListener(Listener l) {
        mUiListener = l;
    }

    private void emitState(String s) {
        Listener ui = mUiListener;
        Listener svc = mServiceListener;
        if (ui != null) mMain.post(() -> ui.onState(s));
        if (svc != null) mMain.post(() -> svc.onState(s));
    }

    private void emitPower(boolean on) {
        Listener ui = mUiListener;
        Listener svc = mServiceListener;
        if (ui != null) mMain.post(() -> ui.onPower(on));
        if (svc != null) mMain.post(() -> svc.onPower(on));
    }

    private void emitFreq(float f) {
        Listener ui = mUiListener;
        Listener svc = mServiceListener;
        if (ui != null) mMain.post(() -> ui.onFrequency(f));
        if (svc != null) mMain.post(() -> svc.onFrequency(f));
    }

    public float getFrequency() {
        return mFreq;
    }

    public boolean isPowered() {
        return mPowered;
    }

    public void setAntenna(int antenna) {
        mAutoAntenna = false; // user override
        applyAntenna(antenna == 0 ? 0 : 1);
    }

    /** When true, wire antenna if WIRED/USB headset appears (stock types 3/4/22). */
    public void setAutoAntenna(boolean auto) {
        mAutoAntenna = auto;
        if (auto) {
            mH.post(this::refreshAntennaFromDevices);
        }
    }

    private void applyAntenna(int antenna) {
        mAntenna = antenna == 0 ? 0 : 1;
        mH.post(() -> {
            if (mDeviceOpen) {
                try {
                    int r = FmNative.switchAntenna(mAntenna);
                    state("antenna=" + mAntenna + " rc=" + r);
                } catch (Throwable t) {
                    state("antenna fail: " + t.getMessage());
                }
            } else {
                state("antenna=" + mAntenna + " (pending open)");
            }
        });
    }

    private void registerDeviceCallback() {
        try {
            mDeviceCb = new AudioDeviceCallback() {
                @Override
                public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
                    mH.post(() -> { refreshAntennaFromDevices(); applyForceUse(); });
                }

                @Override
                public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
                    mH.post(() -> { refreshAntennaFromDevices(); applyForceUse(); });
                }
            };
            mAm.registerAudioDeviceCallback(mDeviceCb, mMain);
        } catch (Throwable t) {
            Log.w(TAG, "registerAudioDeviceCallback", t);
        }
    }

    /**
     * Stock Agui isAntennaAvailable(): types 3 (WIRED_HEADSET), 4 (WIRED_HEADPHONES),
     * 22 (USB_HEADSET). Digital TYPE_USB_DEVICE (11) is not treated as antenna.
     */
    private void refreshAntennaFromDevices() {
        if (!mAutoAntenna) return;
        boolean wire = false;
        try {
            for (AudioDeviceInfo d : mAm.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                int t = d.getType();
                if (t == AudioDeviceInfo.TYPE_WIRED_HEADSET
                        || t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                        || t == AudioDeviceInfo.TYPE_USB_HEADSET) {
                    wire = true;
                    break;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "getDevices", t);
        }
        int want = wire ? 0 : 1;
        if (want != mAntenna) {
            state(wire ? "wire headset → antenna 0" : "no wire → internal antenna 1");
            applyAntenna(want);
        }
    }

    public void setSpeaker(boolean speaker) {
        setRoute(speaker ? ROUTE_SPEAKER : ROUTE_DEFAULT);
    }

    public void setRoute(int route) {
        if (route != ROUTE_SPEAKER) {
            route = ROUTE_DEFAULT;
        }
        mRoute = route;
        mSpeaker = route == ROUTE_SPEAKER;
        mH.post(this::applyForceUse);
    }

    public int getRoute() {
        return mRoute;
    }

    public void setMediaVolume(int index) {
        try {
            int max = mAm.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            if (index < 0) index = 0;
            if (index > max) index = max;
            mAm.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0);
        } catch (Throwable t) {
            Log.w(TAG, "setMediaVolume", t);
        }
    }

    public int getMediaVolume() {
        try {
            return mAm.getStreamVolume(AudioManager.STREAM_MUSIC);
        } catch (Throwable t) {
            return 0;
        }
    }

    public int getMediaVolumeMax() {
        try {
            return mAm.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        } catch (Throwable t) {
            return 15;
        }
    }

    public void powerToggle() {
        mH.post(() -> {
            if (mPowered) {
                powerDownLocked(/*forceChip*/ true);
            } else {
                powerUpLocked(mFreq, /*forceReset*/ true);
            }
        });
    }

    public void powerOn() {
        // Always force chip/audio reset — "Stop then Power" and "already pwron"
        // left the HAL half-dead after the first FGS cycle.
        mH.post(() -> powerUpLocked(mFreq, /*forceReset*/ true));
    }

    public void powerOff() {
        mH.post(() -> powerDownLocked(/*forceChip*/ true));
    }

    /** Block until off (for service stop ordering). */
    public void powerOffSync(long timeoutMs) {
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        mH.post(() -> {
            try {
                powerDownLocked(true);
            } finally {
                done.countDown();
            }
        });
        try {
            done.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void tune(float mhz) {
        final float f = clampFreq(mhz);
        mH.post(() -> {
            mFreq = f;
            notifyFreq(f);
            if (!mPowered) {
                state(String.format("tuned %.1f (off)", f));
                return;
            }
            try {
                boolean ok = FmNative.tune(f);
                state(String.format("tune %.1f %s", f, ok ? "ok" : "fail"));
                if (ok) {
                    FmNative.setMute(false);
                }
            } catch (Throwable t) {
                state("tune err: " + t.getMessage());
            }
        });
    }

    public void seek(boolean up) {
        mH.post(() -> {
            if (!mPowered) {
                float n = clampFreq(mFreq + (up ? FREQ_STEP : -FREQ_STEP));
                mFreq = n;
                notifyFreq(n);
                return;
            }
            try {
                float r = FmNative.seek(mFreq, up);
                if (r >= FREQ_MIN && r <= FREQ_MAX) {
                    mFreq = r;
                    notifyFreq(r);
                    FmNative.setMute(false);
                    state(String.format("seek %.1f", r));
                } else {
                    state("seek empty");
                }
            } catch (Throwable t) {
                state("seek err: " + t.getMessage());
            }
        });
    }

    public void step(boolean up) {
        tune(mFreq + (up ? FREQ_STEP : -FREQ_STEP));
    }

    public void pollRdsOnce() {
        mH.post(() -> {
            if (!mPowered) return;
            try {
                FmNative.readRds();
                String ps = decodeRds(FmNative.getPs());
                String rt = decodeRds(FmNative.getLrText());
                int rssi = FmNative.readRssi();
                Listener ui = mUiListener;
                Listener svc = mServiceListener;
                if (ui != null) mMain.post(() -> ui.onRds(ps, rt));
                if (svc != null) mMain.post(() -> svc.onRds(ps, rt));
                if (ps != null && !ps.isEmpty()) {
                    state("RDS " + ps + " rssi=" + rssi);
                }
            } catch (Throwable ignored) {
            }
        });
    }

    public void release() {
        try {
            if (mDeviceCb != null) {
                mAm.unregisterAudioDeviceCallback(mDeviceCb);
            }
        } catch (Throwable ignored) {
        }
        mDeviceCb = null;
        mH.post(() -> {
            powerDownLocked(/*forceChip*/ true);
            if (mDeviceOpen) {
                try {
                    FmNative.closeDev();
                } catch (Throwable ignored) {
                }
                mDeviceOpen = false;
            }
        });
        mWorker.quitSafely();
    }

    private void powerUpLocked(float freq, boolean forceReset) {
        mFreq = clampFreq(freq);
        state("power up " + mFreq + (forceReset ? " (reset)" : "") + "…");
        try {
            // Always tear down previous session — restart after Stop was leaving
            // the MTK chip "already pwron" with a dead AudioRecord path.
            if (forceReset || mPowered || mRendering) {
                powerDownLocked(/*forceChip*/ true);
                try {
                    Thread.sleep(80);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            if (!mDeviceOpen) {
                boolean open = FmNative.openDev();
                mDeviceOpen = open;
                if (!open) {
                    // one retry after hard close
                    try {
                        FmNative.closeDev();
                    } catch (Throwable ignored) {
                    }
                    open = FmNative.openDev();
                    mDeviceOpen = open;
                }
                if (!open) {
                    state("openDev failed");
                    notifyPower(false);
                    return;
                }
                state("openDev ok");
            }

            try {
                int ar = FmNative.switchAntenna(mAntenna);
                state("antenna " + mAntenna + " rc=" + ar);
            } catch (Throwable t) {
                state("antenna: " + t.getMessage());
            }

            if (!requestFocus()) {
                state("audio focus denied");
                notifyPower(false);
                return;
            }

            applyForceUse();

            boolean pup = FmNative.powerUp(mFreq);
            if (!pup) {
                // Chip may be stuck on — force down and try once more
                state("powerUp fail, force chip cycle…");
                try {
                    FmNative.powerDown(0);
                    Thread.sleep(100);
                } catch (Throwable ignored) {
                }
                try {
                    FmNative.closeDev();
                } catch (Throwable ignored) {
                }
                mDeviceOpen = FmNative.openDev();
                if (!mDeviceOpen || !FmNative.powerUp(mFreq)) {
                    state("powerUp failed");
                    abandonFocus();
                    notifyPower(false);
                    return;
                }
            }

            mPowered = true;
            if (!mWake.isHeld()) {
                mWake.acquire();
            }

            // Software path only — never createAudioPatch.
            if (!startRenderLocked()) {
                state("render start failed (chip up) — retry");
                stopRenderLocked();
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (!startRenderLocked()) {
                    state("render dead");
                }
            }

            try {
                FmNative.setRds(true);
            } catch (Throwable ignored) {
            }
            FmNative.setMute(false);

            notifyPower(true);
            notifyFreq(mFreq);
            state(String.format("on %.1f Hz  out=%s", mFreq, routeLabel()));
        } catch (UnsatisfiedLinkError e) {
            state("native link: " + e.getMessage()
                    + " (need system priv-app + libaguifmjni)");
            mPowered = false;
            notifyPower(false);
        } catch (Throwable t) {
            Log.e(TAG, "powerUp", t);
            state("powerUp err: " + t.getMessage());
            mPowered = false;
            notifyPower(false);
        }
    }

    private void powerDownLocked(boolean forceChip) {
        state("power down…");
        // Stock Agui signals HAL before tearing the capture path.
        setAudioParameters("AudioFmPreStop=1");
        stopRenderLocked();
        try {
            // Always hit the chip when forceChip — process restart / half-dead
            // sessions leave "already pwron" and mute stuck.
            if (forceChip || mPowered || mDeviceOpen) {
                try {
                    FmNative.setMute(true);
                } catch (Throwable ignored) {
                }
                try {
                    FmNative.setRds(false);
                } catch (Throwable ignored) {
                }
                try {
                    FmNative.powerDown(0);
                } catch (Throwable t) {
                    state("powerDown ioctl: " + t.getMessage());
                }
            }
        } catch (Throwable t) {
            state("powerDown: " + t.getMessage());
        }
        mPowered = false;
        abandonFocus();
        clearForceUse();
        setAudioParameters("AudioFmPreStop=0");
        if (mWake.isHeld()) {
            mWake.release();
        }
        notifyPower(false);
        state("off");
    }

    private boolean startRenderLocked() {
        stopRenderLocked();
        try {
            mRecord = new AudioRecord(
                    AUDIO_SOURCE_FM_TUNER,
                    SAMPLE_RATE,
                    CHANNEL_IN,
                    ENCODING,
                    BUF_SIZE);
            if (mRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                state("AudioRecord not init (source 1998 / perms?)");
                releaseRecordTrack();
                return false;
            }

            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            AudioFormat fmt = new AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(ENCODING)
                    .setChannelMask(CHANNEL_OUT)
                    .build();
            mTrack = new AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(fmt)
                    .setBufferSizeInBytes(BUF_SIZE)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            if (mTrack.getState() != AudioTrack.STATE_INITIALIZED) {
                state("AudioTrack not init");
                releaseRecordTrack();
                return false;
            }

            mRendering = true;
            mRenderThread = new Thread(this::renderLoop, "TitanFmRender");
            mRenderThread.start();
            applyForceUse();
            state("render 48k stereo");
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "startRender", t);
            state("render err: " + t.getMessage());
            releaseRecordTrack();
            mRendering = false;
            return false;
        }
    }

    private void stopRenderLocked() {
        mRendering = false;
        Thread t = mRenderThread;
        mRenderThread = null;
        if (t != null) {
            t.interrupt();
            try {
                t.join(500);
            } catch (InterruptedException ignored) {
            }
        }
        releaseRecordTrack();
    }

    private void releaseRecordTrack() {
        try {
            if (mRecord != null) {
                if (mRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    mRecord.stop();
                }
                mRecord.release();
            }
        } catch (Throwable ignored) {
        }
        mRecord = null;
        try {
            if (mTrack != null) {
                if (mTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                    mTrack.stop();
                }
                mTrack.release();
            }
        } catch (Throwable ignored) {
        }
        mTrack = null;
    }

    private void renderLoop() {
        final byte[] buf = new byte[BUF_SIZE];
        int ignore = 0;
        try {
            if (mRecord != null
                    && mRecord.getState() == AudioRecord.STATE_INITIALIZED) {
                mRecord.startRecording();
            }
            if (mTrack != null
                    && mTrack.getState() == AudioTrack.STATE_INITIALIZED) {
                mTrack.play();
            }
            while (mRendering && !Thread.interrupted()) {
                AudioRecord rec = mRecord;
                AudioTrack tr = mTrack;
                if (rec == null || tr == null) break;
                int n = rec.read(buf, 0, buf.length);
                if (!mRendering) break;
                if (ignore < 3) {
                    ignore++;
                    continue;
                }
                if (n > 0) {
                    tr.write(buf, 0, n);
                } else if (n < 0) {
                    Log.w(TAG, "AudioRecord.read=" + n);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "renderLoop", t);
            state("render die: " + t.getMessage());
        } finally {
            try {
                if (mRecord != null
                        && mRecord.getRecordingState()
                        == AudioRecord.RECORDSTATE_RECORDING) {
                    mRecord.stop();
                }
            } catch (Throwable ignored) {
            }
            try {
                if (mTrack != null
                        && mTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                    mTrack.stop();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private boolean requestFocus() {
        if (mFocusHeld) return true;
        try {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            mFocusReq = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener(f -> {
                        if (f == AudioManager.AUDIOFOCUS_LOSS
                                || f == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                            mH.post(() -> powerDownLocked(/*forceChip*/ true));
                        }
                    })
                    .build();
            int r = mAm.requestAudioFocus(mFocusReq);
            mFocusHeld = r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            return mFocusHeld;
        } catch (Throwable t) {
            state("focus: " + t.getMessage());
            return false;
        }
    }

    private void abandonFocus() {
        if (!mFocusHeld) return;
        try {
            if (mFocusReq != null) {
                mAm.abandonAudioFocusRequest(mFocusReq);
            }
        } catch (Throwable ignored) {
        }
        mFocusHeld = false;
    }

    /**
     * Route media to speaker via AudioSystem.setForceUse only.
     * Never call {@link AudioManager#setSpeakerphoneOn} — that installs a
     * communication route client that can leave earpiece/speaker stuck and
     * silence other media apps after FM stops.
     */
    private String routeLabel() {
        return mRoute == ROUTE_SPEAKER ? "spk" : "default";
    }

    private void applyForceUse() {
        // FOR_MEDIA=1; NONE=0 SPK=1 HEADPHONES=2 BT_A2DP=4. Never WIRED_ACCESSORY (USB antenna).
        int force = 0;
        AudioDeviceInfo pick = null;
        if (mRoute == ROUTE_SPEAKER) {
            pick = findOutput(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER);
            force = 1;
        } else {
            pick = findOutput(
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_BLE_HEADSET,
                    AudioDeviceInfo.TYPE_BLE_SPEAKER,
                    AudioDeviceInfo.TYPE_HEARING_AID);
            if (pick != null) {
                force = 4;
            } else {
                pick = findOutput(
                        AudioDeviceInfo.TYPE_WIRED_HEADSET,
                        AudioDeviceInfo.TYPE_WIRED_HEADPHONES);
                if (pick != null) force = 2;
            }
        }
        try {
            Class<?> c = Class.forName("android.media.AudioSystem");
            c.getMethod("setForceUse", int.class, int.class)
                    .invoke(null, 1, force);
        } catch (Throwable t) {
            Log.w(TAG, "setForceUse", t);
        }
        try {
            if (mTrack != null) {
                mTrack.setPreferredDevice(pick);
            }
        } catch (Throwable t) {
            Log.w(TAG, "setPreferredDevice", t);
        }
        String dest = pick == null ? "none" : String.valueOf(pick.getProductName());
        state("route " + routeLabel() + " dest=" + dest
                + " vol=" + getMediaVolume() + "/" + getMediaVolumeMax());
    }

    private AudioDeviceInfo findOutput(int... types) {
        try {
            for (int want : types) {
                for (AudioDeviceInfo d : mAm.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                    if (d.getType() == want) return d;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "findOutput", t);
        }
        return null;
    }

    private void clearForceUse() {
        try {
            Class<?> c = Class.forName("android.media.AudioSystem");
            c.getMethod("setForceUse", int.class, int.class)
                    .invoke(null, 1, 0);
        } catch (Throwable t) {
            Log.w(TAG, "clearForceUse", t);
        }
        try {
            if (mTrack != null) mTrack.setPreferredDevice(null);
        } catch (Throwable ignored) {
        }
        try {
            mAm.setSpeakerphoneOn(false);
        } catch (Throwable ignored) {
        }
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                mAm.clearCommunicationDevice();
            }
        } catch (Throwable ignored) {
        }
    }

    private void preferRouteDevice() {
        applyForceUse();
    }

    private void setAudioParameters(String kv) {
        try {
            Class<?> c = Class.forName("android.media.AudioSystem");
            c.getMethod("setParameters", String.class).invoke(null, kv);
        } catch (Throwable t) {
            Log.w(TAG, "setParameters " + kv, t);
        }
    }

    private static float clampFreq(float f) {
        if (f < FREQ_MIN) return FREQ_MIN;
        if (f > FREQ_MAX) return FREQ_MAX;
        // quantize to 0.1 MHz
        return Math.round(f * 10f) / 10f;
    }

    private static String decodeRds(byte[] raw) {
        if (raw == null || raw.length == 0) return "";
        int end = raw.length;
        while (end > 0 && (raw[end - 1] == 0 || raw[end - 1] == ' ')) end--;
        if (end == 0) return "";
        try {
            return new String(raw, 0, end, "UTF-8").trim();
        } catch (Exception e) {
            return new String(raw, 0, end).trim();
        }
    }

    private void state(String s) {
        Log.i(TAG, s);
        emitState(s);
    }

    private void notifyPower(boolean on) {
        emitPower(on);
    }

    private void notifyFreq(float f) {
        emitFreq(f);
    }
}
