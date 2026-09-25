package com.titanus2.atlas;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Process;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import android.util.Log;

import java.io.File;
import java.io.FileDescriptor;

/**
 * Desk speaker and mic. PipeWire writes and reads two FIFOs.
 * This process opens the phone's real devices. No socket and no port.
 * A root AAudio helper is not used: AudioFlinger gives it no session.
 */
public final class DeskAudio {
    private static final String TAG = "AtlasAudio";
    static final String DIR = "/data/local/tmp/atlas-virgl";
    static final String PLAY = DIR + "/audio-play";
    static final String CAP = DIR + "/audio-cap";
    static final int RATE = 48000;
    private static final int FRAME = 4;

    private static final java.util.concurrent.atomic.AtomicBoolean started =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private static volatile boolean run;
    private static volatile FileDescriptor playFd;
    private static volatile FileDescriptor capFd;
    private static volatile AudioManager audioManager;

    private DeskAudio() {}

    /** Idempotent. Safe from the session service and from the desk activity. */
    public static void start(Context ctx) {
        if (ctx == null) return;
        if (!started.compareAndSet(false, true)) return;
        run = true;
        Context app = ctx.getApplicationContext();
        audioManager = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        Thread t = new Thread(() -> own(app), "atlas-desk-audio");
        t.setDaemon(true);
        t.start();
    }

    public static void stop() {
        run = false;
        started.set(false);
        closeQuiet(playFd);
        closeQuiet(capFd);
        playFd = null;
        capFd = null;
    }

    private static void own(Context app) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        ensureFifos();
        playFd = openWhenReady(PLAY, OsConstants.O_RDONLY);
        capFd = openWhenReady(CAP, OsConstants.O_WRONLY);
        if (!run) return;
        /* Hold both ends before the root helper exits, or PipeWire sees EPIPE. */
        DeskSession.releaseRootBridge();
        Thread play = new Thread(() -> playLoop(), "atlas-audio-play");
        Thread cap = new Thread(() -> capLoop(app), "atlas-audio-cap");
        play.setDaemon(true);
        cap.setDaemon(true);
        play.start();
        cap.start();
        Log.i(TAG, "proxy up play=" + PLAY + " cap=" + CAP);
    }

    private static void ensureFifos() {
        if (isFifo(new File(PLAY)) && isFifo(new File(CAP))) return;
        DeskSession.prepareAudioFifos();
    }

    private static boolean isFifo(File f) {
        try {
            return f.exists() && OsConstants.S_ISFIFO(Os.stat(f.getPath()).st_mode);
        } catch (ErrnoException e) {
            return false;
        }
    }

    private static FileDescriptor openWhenReady(String path, int rw) {
        int flags = rw | OsConstants.O_NONBLOCK;
        while (run) {
            try {
                FileDescriptor fd = Os.open(path, flags, 0666);
                Log.i(TAG, "open " + path);
                return fd;
            } catch (ErrnoException e) {
                if (e.errno != OsConstants.ENXIO && e.errno != OsConstants.ENOENT) {
                    Log.w(TAG, "open " + path + " errno=" + e.errno);
                }
                sleep(400);
            }
        }
        return null;
    }

    private static void playLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        AudioTrack track = null;
        byte[] raw = new byte[4096];
        byte[] hold = new byte[16384];
        int have = 0;
        long played = 0;
        long nextLog = android.os.SystemClock.uptimeMillis() + 2000;
        int idle = 0;
        while (run) {
            FileDescriptor fd = playFd;
            if (fd == null) {
                sleep(200);
                continue;
            }
            short ev = poll(fd, (short) OsConstants.POLLIN, 200);
            if ((ev & (OsConstants.POLLHUP | OsConstants.POLLERR | OsConstants.POLLNVAL)) != 0
                    && (ev & OsConstants.POLLIN) == 0) {
                idle = 0;
                have = 0;
                track = dropTrack(track);
                reopenPlay();
                continue;
            }
            if ((ev & OsConstants.POLLIN) == 0) {
                /* PipeWire can gap. Pausing here fills the track and then stalls. */
                if (++idle >= 25) track = pauseTrack(track);
                continue;
            }
            idle = 0;
            int n;
            try {
                n = Os.read(fd, raw, 0, raw.length);
            } catch (ErrnoException e) {
                if (e.errno == OsConstants.EAGAIN || e.errno == OsConstants.EINTR) continue;
                Log.w(TAG, "play read errno=" + e.errno);
                sleep(200);
                continue;
            } catch (java.io.InterruptedIOException e) {
                continue;
            }
            if (n < 0) continue;
            if (n == 0) {
                have = 0;
                track = dropTrack(track);
                reopenPlay();
                continue;
            }
            if (have + n > hold.length) {
                have = 0;
            }
            System.arraycopy(raw, 0, hold, have, n);
            have += n;
            int frames = have - (have % FRAME);
            if (frames <= 0) continue;
            track = ensureTrack(track);
            if (track == null) {
                have = 0;
                sleep(300);
                continue;
            }
            int off = 0;
            int spins = 0;
            while (off < frames && run && spins < 8) {
                if (track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                    try {
                        track.play();
                    } catch (IllegalStateException e) {
                        track = dropTrack(track);
                        break;
                    }
                }
                int w = track.write(hold, off, frames - off, AudioTrack.WRITE_NON_BLOCKING);
                if (w < 0) {
                    Log.w(TAG, "play write " + w);
                    track = dropTrack(track);
                    break;
                }
                if (w == 0) {
                    spins++;
                    sleep(10);
                    continue;
                }
                off += w;
                played += w;
                spins = 0;
            }
            int left = have - frames;
            if (left > 0) System.arraycopy(hold, frames, hold, 0, left);
            have = left;
            long now = android.os.SystemClock.uptimeMillis();
            if (now >= nextLog) {
                Log.i(TAG, "play bytes=" + played);
                nextLog = now + 2000;
            }
        }
        dropTrack(track);
    }

    private static AudioTrack ensureTrack(AudioTrack track) {
        if (track != null && track.getState() == AudioTrack.STATE_INITIALIZED
                && track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
            return track;
        }
        if (track != null && track.getState() == AudioTrack.STATE_INITIALIZED) {
            try {
                track.play();
                track.setVolume(1f);
                return track;
            } catch (IllegalStateException e) {
                dropTrack(track);
            }
        }
        int min = AudioTrack.getMinBufferSize(
                RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        if (min < FRAME) min = 8192;
        int buf = Math.max(min, RATE * FRAME / 5);
        try {
            AudioAttributes attr = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            requestFocus(attr);
            int minBuf = AudioTrack.getMinBufferSize(
                    RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            int useBuf = minBuf > 0 ? Math.max(minBuf, minBuf * 2) : buf;
            AudioTrack t = new AudioTrack.Builder()
                    .setAudioAttributes(attr)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build())
                    .setBufferSizeInBytes(useBuf)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build();
            preferOut(t);
            t.play();
            t.setVolume(1f);
            Log.i(TAG, "AudioTrack speaker buf=" + useBuf);
            return t;
        } catch (RuntimeException e) {
            Log.w(TAG, "AudioTrack " + e.getMessage());
            return null;
        }
    }

    private static AudioTrack pauseTrack(AudioTrack track) {
        if (track == null) return null;
        try {
            if (track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) track.pause();
        } catch (IllegalStateException ignored) {
        }
        return track;
    }

    private static AudioTrack dropTrack(AudioTrack track) {
        if (track == null) return null;
        try {
            track.pause();
            track.flush();
            track.release();
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    private static void capLoop(Context app) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        AudioRecord rec = null;
        byte[] buf = new byte[4096];
        long captured = 0;
        int peak = 0;
        long nextLog = android.os.SystemClock.uptimeMillis() + 2000;
        int blocked = 0;
        while (run) {
            if (app.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                rec = dropRec(rec);
                Log.w(TAG, "RECORD_AUDIO not granted");
                sleep(1000);
                continue;
            }
            FileDescriptor fd = capFd;
            if (fd == null) {
                sleep(200);
                continue;
            }
            short ev = poll(fd, (short) OsConstants.POLLOUT, 200);
            if ((ev & (OsConstants.POLLHUP | OsConstants.POLLERR | OsConstants.POLLNVAL)) != 0
                    && (ev & OsConstants.POLLOUT) == 0) {
                rec = dropRec(rec);
                reopenCap();
                continue;
            }
            if ((ev & OsConstants.POLLOUT) == 0) {
                if (++blocked >= 3) rec = dropRec(rec);
                continue;
            }
            blocked = 0;
            rec = ensureRec(rec);
            if (rec == null) {
                sleep(500);
                continue;
            }
            int n = rec.read(buf, 0, buf.length);
            if (n < 0) {
                Log.w(TAG, "cap read " + n);
                rec = dropRec(rec);
                sleep(300);
                continue;
            }
            if (n <= 0) continue;
            byte[] pcm = buf;
            if (rec.getChannelCount() == 1) {
                pcm = upmixMono(buf, n);
                n = pcm.length;
            }
            int frames = n - (n % FRAME);
            int off = 0;
            boolean again = false;
            while (off < frames) {
                try {
                    int w = Os.write(fd, pcm, off, frames - off);
                    if (w <= 0) break;
                    off += w;
                    captured += w;
                } catch (ErrnoException e) {
                    if (e.errno == OsConstants.EAGAIN || e.errno == OsConstants.EINTR) {
                        again = true;
                        break;
                    }
                    Log.w(TAG, "cap write errno=" + e.errno);
                    again = true;
                    break;
                } catch (java.io.InterruptedIOException e) {
                    again = true;
                    break;
                }
            }
            for (int i = 0; i + 1 < frames; i += 2) {
                int s = (pcm[i] & 0xff) | (pcm[i + 1] << 8);
                int a = s < 0 ? -s : s;
                if (a > peak) peak = a;
            }
            if (again && ++blocked >= 3) rec = dropRec(rec);
            long now = android.os.SystemClock.uptimeMillis();
            if (now >= nextLog) {
                Log.i(TAG, "cap bytes=" + captured + " peak=" + peak);
                peak = 0;
                nextLog = now + 2000;
            }
        }
        dropRec(rec);
    }

    private static AudioRecord ensureRec(AudioRecord rec) {
        if (rec != null && rec.getState() == AudioRecord.STATE_INITIALIZED
                && rec.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
            return rec;
        }
        if (rec != null && rec.getState() == AudioRecord.STATE_INITIALIZED) {
            try {
                rec.startRecording();
                return rec;
            } catch (IllegalStateException e) {
                dropRec(rec);
            }
        }
        AudioRecord made = buildRec(AudioFormat.CHANNEL_IN_STEREO);
        if (made == null) made = buildRec(AudioFormat.CHANNEL_IN_MONO);
        if (made == null) return null;
        try {
            made.startRecording();
            Log.i(TAG, "AudioRecord mic ch=" + made.getChannelCount());
            return made;
        } catch (RuntimeException e) {
            Log.w(TAG, "AudioRecord start " + e.getMessage());
            dropRec(made);
            return null;
        }
    }

    private static AudioRecord buildRec(int channel) {
        int min = AudioRecord.getMinBufferSize(RATE, channel, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) return null;
        try {
            AudioRecord rec = new AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(RATE)
                            .setChannelMask(channel)
                            .build())
                    .setBufferSizeInBytes(Math.max(min, RATE * 2))
                    .build();
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                rec.release();
                return null;
            }
            preferIn(rec);
            return rec;
        } catch (RuntimeException e) {
            Log.w(TAG, "AudioRecord " + e.getMessage());
            return null;
        }
    }

    /** Mono capture still feeds the stereo FIFO. */
    private static byte[] upmixMono(byte[] in, int n) {
        int samples = n / 2;
        byte[] out = new byte[samples * FRAME];
        for (int i = 0; i < samples; i++) {
            out[i * 4] = in[i * 2];
            out[i * 4 + 1] = in[i * 2 + 1];
            out[i * 4 + 2] = in[i * 2];
            out[i * 4 + 3] = in[i * 2 + 1];
        }
        return out;
    }

    private static AudioRecord dropRec(AudioRecord rec) {
        if (rec == null) return null;
        try {
            rec.stop();
        } catch (RuntimeException ignored) {
        }
        try {
            rec.release();
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    private static void requestFocus(AudioAttributes attr) {
        AudioManager am = audioManager;
        if (am == null || attr == null) return;
        try {
            AudioFocusRequest req = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attr)
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener(focus -> { })
                    .build();
            int rc = am.requestAudioFocus(req);
            Log.i(TAG, "focus " + rc);
        } catch (RuntimeException e) {
            Log.w(TAG, "focus " + e.getMessage());
        }
    }

    private static void preferOut(AudioTrack track) {
        if (track == null) return;
        AudioDeviceInfo d = device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, false);
        if (d != null) track.setPreferredDevice(d);
    }

    private static void preferIn(AudioRecord rec) {
        if (rec == null) return;
        AudioDeviceInfo d = device(AudioDeviceInfo.TYPE_BUILTIN_MIC, true);
        if (d != null) rec.setPreferredDevice(d);
    }

    private static AudioDeviceInfo device(int type, boolean input) {
        AudioManager am = audioManager;
        if (am == null) return null;
        int dir = input ? AudioManager.GET_DEVICES_INPUTS : AudioManager.GET_DEVICES_OUTPUTS;
        AudioDeviceInfo[] all = am.getDevices(dir);
        if (all == null) return null;
        for (AudioDeviceInfo d : all) {
            if (d.getType() == type) return d;
        }
        return null;
    }

    private static void reopenPlay() {
        FileDescriptor old = playFd;
        playFd = null;
        closeQuiet(old);
        playFd = openWhenReady(PLAY, OsConstants.O_RDONLY);
    }

    private static void reopenCap() {
        FileDescriptor old = capFd;
        capFd = null;
        closeQuiet(old);
        capFd = openWhenReady(CAP, OsConstants.O_WRONLY);
    }

    private static short poll(FileDescriptor fd, short events, int timeoutMs) {
        StructPollfd p = new StructPollfd();
        p.fd = fd;
        p.events = events;
        try {
            Os.poll(new StructPollfd[]{p}, timeoutMs);
        } catch (ErrnoException e) {
            if (e.errno != OsConstants.EINTR) return (short) OsConstants.POLLERR;
        }
        return p.revents;
    }

    private static void closeQuiet(FileDescriptor fd) {
        if (fd == null) return;
        try {
            Os.close(fd);
        } catch (ErrnoException ignored) {
        }
    }

    private static void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
