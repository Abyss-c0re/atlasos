/* Android-side desk audio. Debian must not open ALSA hw:0,0 — AudioFlinger
 * owns the speaker. Clients speak s16le PCM on a unix socket; this process
 * is the only one that opens a stream, via AAudio (same AudioFlinger path as
 * titan_fm's AudioTrack / AudioRecord). Idle: no stream, no ALSA device.
 *
 * hello (little-endian): magic AUDB, ver 1, dir 0=play 1=cap, rate, ch, fmt.
 * fmt 1 is s16le. rate must be 48000 (44.1 fails this HAL). ch is 1 or 2.
 * reply: int32 status (0 or an AAudio code), u32 rate, ch, frames-per-burst.
 * Then raw interleaved s16le until the client closes. The stream closes with it.
 */
#include <aaudio/AAudio.h>
#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <unistd.h>

#define MAGIC 0x41554442u
#define VER 1u
#define FMT_S16 1u
#define RATE 48000u
#define SOCK_DEFAULT "/data/local/tmp/atlas-virgl/audio.sock"
#define TAG "atlas-audio"

struct hello {
    uint32_t magic, ver, dir, rate, channels, format;
} __attribute__((packed));

struct reply {
    int32_t status;
    uint32_t rate, channels, burst;
} __attribute__((packed));

static volatile sig_atomic_t stop_flag;
static int listen_fd = -1;

static void on_sig(int sig) {
    (void)sig;
    stop_flag = 1;
    if (listen_fd >= 0) close(listen_fd);
    listen_fd = -1;
}

static void loge(const char *msg) {
    fprintf(stderr, "atlas-audio: %s\n", msg);
    fflush(stderr);
    __android_log_print(ANDROID_LOG_INFO, TAG, "%s", msg);
}

static int send_all(int fd, const void *buf, size_t n) {
    const char *p = buf;
    while (n) {
        ssize_t w = write(fd, p, n);
        if (w < 0 && errno == EINTR) continue;
        if (w <= 0) return -1;
        p += w;
        n -= (size_t)w;
    }
    return 0;
}

static int read_full(int fd, void *buf, size_t n) {
    char *p = buf;
    while (n) {
        ssize_t r = read(fd, p, n);
        if (r < 0 && errno == EINTR) continue;
        if (r <= 0) return -1;
        p += r;
        n -= (size_t)r;
    }
    return 0;
}

static void close_stream(AAudioStream *stream) {
    if (!stream) return;
    AAudioStream_requestStop(stream);
    AAudioStream_close(stream);
}

static int open_stream(const struct hello *h, AAudioStream **out, struct reply *rep) {
    AAudioStreamBuilder *b = NULL;
    AAudioStream *stream = NULL;
    aaudio_result_t rc;

    *out = NULL;
    memset(rep, 0, sizeof(*rep));
    rc = AAudio_createStreamBuilder(&b);
    if (rc != AAUDIO_OK) {
        rep->status = rc;
        return -1;
    }
    AAudioStreamBuilder_setDirection(
        b, h->dir ? AAUDIO_DIRECTION_INPUT : AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setSharingMode(b, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_NONE);
    AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setSampleRate(b, (int32_t)h->rate);
    AAudioStreamBuilder_setChannelCount(b, (int32_t)h->channels);
    if (h->dir == 0) {
        AAudioStreamBuilder_setUsage(b, AAUDIO_USAGE_MEDIA);
        AAudioStreamBuilder_setContentType(b, AAUDIO_CONTENT_TYPE_MUSIC);
    } else {
        AAudioStreamBuilder_setInputPreset(b, AAUDIO_INPUT_PRESET_GENERIC);
    }
    rc = AAudioStreamBuilder_openStream(b, &stream);
    AAudioStreamBuilder_delete(b);
    if (rc != AAUDIO_OK || !stream) {
        rep->status = rc != AAUDIO_OK ? rc : AAUDIO_ERROR_NULL;
        return -1;
    }
    rc = AAudioStream_requestStart(stream);
    if (rc != AAUDIO_OK) {
        AAudioStream_close(stream);
        rep->status = rc;
        return -1;
    }
    rep->status = AAUDIO_OK;
    rep->rate = (uint32_t)AAudioStream_getSampleRate(stream);
    rep->channels = (uint32_t)AAudioStream_getChannelCount(stream);
    rep->burst = (uint32_t)AAudioStream_getFramesPerBurst(stream);
    *out = stream;
    return 0;
}

static int play_loop(int fd, AAudioStream *stream, int frame_bytes) {
    uint8_t hold[8192 + 16];
    size_t have = 0;
    char line[80];

    while (!stop_flag) {
        ssize_t n;
        int32_t frames;
        aaudio_result_t w;
        size_t used;

        if (have < (size_t)frame_bytes) {
            n = read(fd, hold + have, (sizeof(hold) - 16) - have);
            if (n == 0) return 0;
            if (n < 0) {
                if (errno == EINTR) continue;
                return -1;
            }
            have += (size_t)n;
        }
        frames = (int32_t)(have / (size_t)frame_bytes);
        if (frames <= 0) continue;
        w = AAudioStream_write(stream, hold, frames, 500000000LL);
        if (w == AAUDIO_ERROR_TIMEOUT) continue;
        if (w < 0) {
            snprintf(line, sizeof(line), "write %d", (int)w);
            loge(line);
            return -1;
        }
        if (w == 0) continue;
        used = (size_t)w * (size_t)frame_bytes;
        if (used > have) used = have;
        memmove(hold, hold + used, have - used);
        have -= used;
    }
    return 0;
}

static int cap_loop(int fd, AAudioStream *stream, int frame_bytes) {
    int16_t buf[2048];
    int max_frames = (int)(sizeof(buf) / (size_t)frame_bytes);
    char line[80];

    if (max_frames < 1) return -1;
    while (!stop_flag) {
        struct pollfd pfd;
        aaudio_result_t n;
        pfd.fd = fd;
        pfd.events = POLLIN;
        pfd.revents = 0;
        if (poll(&pfd, 1, 0) > 0 &&
            (pfd.revents & (POLLHUP | POLLERR | POLLNVAL)))
            return 0;
        n = AAudioStream_read(stream, buf, max_frames, 500000000LL);
        if (n == AAUDIO_ERROR_TIMEOUT) continue;
        if (n < 0) {
            snprintf(line, sizeof(line), "read %d", (int)n);
            loge(line);
            return -1;
        }
        if (n == 0) continue;
        if (send_all(fd, buf, (size_t)n * (size_t)frame_bytes) != 0) return -1;
    }
    return 0;
}

static void handle_client(int fd) {
    struct hello h;
    struct reply rep;
    AAudioStream *stream = NULL;
    char line[96];
    int frame_bytes;

    if (read_full(fd, &h, sizeof(h)) != 0) return;
    memset(&rep, 0, sizeof(rep));
    if (h.magic != MAGIC || h.ver != VER || h.format != FMT_S16 ||
        h.rate != RATE || (h.channels != 1 && h.channels != 2) || h.dir > 1) {
        rep.status = AAUDIO_ERROR_ILLEGAL_ARGUMENT;
        send_all(fd, &rep, sizeof(rep));
        return;
    }
    if (open_stream(&h, &stream, &rep) != 0) {
        snprintf(line, sizeof(line), "open dir=%u rc=%d", h.dir, (int)rep.status);
        loge(line);
        send_all(fd, &rep, sizeof(rep));
        return;
    }
    snprintf(line, sizeof(line), "stream dir=%u %u Hz ch=%u burst=%u",
             h.dir, rep.rate, rep.channels, rep.burst);
    loge(line);
    if (send_all(fd, &rep, sizeof(rep)) != 0) {
        close_stream(stream);
        return;
    }
    frame_bytes = (int)h.channels * (int)sizeof(int16_t);
    if (h.dir == 0) play_loop(fd, stream, frame_bytes);
    else cap_loop(fd, stream, frame_bytes);
    close_stream(stream);
    loge("stream closed");
}

/* Two FIFOs, no socket and no port. play: desk writes, we play.
 * cap: we record, desk reads. 48 kHz stereo s16le. */
static int open_fifo(const char *path, int flags) {
    if (mkfifo(path, 0666) != 0 && errno != EEXIST) {
        perror(path);
        return -1;
    }
    chmod(path, 0666);
    return open(path, flags);
}

static void *play_fifo_thread(void *arg) {
    const char *path = arg;
    int fd = open_fifo(path, O_RDONLY);
    struct hello h;
    struct reply rep;
    AAudioStream *stream = NULL;
    char line[96];

    if (fd < 0) return NULL;
    memset(&h, 0, sizeof(h));
    h.magic = MAGIC;
    h.ver = VER;
    h.dir = 0;
    h.rate = RATE;
    h.channels = 2;
    h.format = FMT_S16;
    if (open_stream(&h, &stream, &rep) != 0) {
        snprintf(line, sizeof(line), "play open rc=%d", (int)rep.status);
        loge(line);
        close(fd);
        return NULL;
    }
    snprintf(line, sizeof(line), "play fifo %s burst=%u", path, rep.burst);
    loge(line);
    play_loop(fd, stream, 4);
    close_stream(stream);
    close(fd);
    loge("play closed");
    return NULL;
}

static void *cap_fifo_thread(void *arg) {
    const char *path = arg;
    int fd;
    struct hello h;
    struct reply rep;
    AAudioStream *stream = NULL;
    char line[96];

    /* Block until PipeWire opens the read side, then record. */
    fd = open_fifo(path, O_WRONLY);
    if (fd < 0) return NULL;
    memset(&h, 0, sizeof(h));
    h.magic = MAGIC;
    h.ver = VER;
    h.dir = 1;
    h.rate = RATE;
    h.channels = 2;
    h.format = FMT_S16;
    if (open_stream(&h, &stream, &rep) != 0) {
        snprintf(line, sizeof(line), "cap open rc=%d", (int)rep.status);
        loge(line);
        close(fd);
        return NULL;
    }
    snprintf(line, sizeof(line), "cap fifo %s burst=%u", path, rep.burst);
    loge(line);
    cap_loop(fd, stream, 4);
    close_stream(stream);
    close(fd);
    loge("cap closed");
    return NULL;
}

static int run_fifos(const char *play, const char *cap) {
    pthread_t tp, tc;

    signal(SIGTERM, on_sig);
    signal(SIGINT, on_sig);
    signal(SIGPIPE, SIG_IGN);
    fprintf(stderr, "atlas-audio-bridge fifos %s %s\n", play, cap);
    __android_log_print(ANDROID_LOG_INFO, TAG, "fifos %s %s", play, cap);
    if (pthread_create(&tp, NULL, play_fifo_thread, (void *)play) != 0) return 1;
    if (pthread_create(&tc, NULL, cap_fifo_thread, (void *)cap) != 0) return 1;
    while (!stop_flag) sleep(1);
    pthread_kill(tp, SIGTERM);
    pthread_kill(tc, SIGTERM);
    pthread_join(tp, NULL);
    pthread_join(tc, NULL);
    return 0;
}

int main(int argc, char **argv) {
    const char *path = SOCK_DEFAULT;
    struct sockaddr_un addr;

    if (argc == 4 && strcmp(argv[1], "--fifos") == 0)
        return run_fifos(argv[2], argv[3]);
    if (argc > 2) {
        fprintf(stderr, "usage: atlas-audio-bridge [sock] | --fifos PLAY CAP\n");
        return 2;
    }
    if (argc == 2) path = argv[1];
    signal(SIGTERM, on_sig);
    signal(SIGINT, on_sig);
    signal(SIGPIPE, SIG_IGN);
    listen_fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (listen_fd < 0) {
        perror("socket");
        return 1;
    }
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    if (strlen(path) >= sizeof(addr.sun_path)) {
        fprintf(stderr, "sock path too long\n");
        return 1;
    }
    memcpy(addr.sun_path, path, strlen(path) + 1);
    unlink(path);
    if (bind(listen_fd, (struct sockaddr *)&addr, sizeof(addr)) != 0) {
        perror("bind");
        return 1;
    }
    chmod(path, 0666);
    if (listen(listen_fd, 1) != 0) {
        perror("listen");
        return 1;
    }
    fprintf(stderr, "atlas-audio-bridge %s\n", path);
    __android_log_print(ANDROID_LOG_INFO, TAG, "listen %s", path);
    while (!stop_flag && listen_fd >= 0) {
        int c = accept(listen_fd, NULL, NULL);
        if (c < 0) {
            if (errno == EINTR) continue;
            if (stop_flag) break;
            perror("accept");
            break;
        }
        handle_client(c);
        close(c);
    }
    if (listen_fd >= 0) close(listen_fd);
    unlink(path);
    return 0;
}
