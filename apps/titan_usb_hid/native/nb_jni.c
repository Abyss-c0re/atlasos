/*
 * JNI lock around one process-wide pointer and keyboard.
 * Java BLE I/O stays in NeckbandLink. This file only frames bytes.
 */
#include "nb_remote.h"

#include <jni.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>

static NbPointer g_ptr;
static NbKbd g_kbd;
static int g_ready;
static int g_pkg = 1;
static pthread_mutex_t g_mu = PTHREAD_MUTEX_INITIALIZER;

static void ensure_init(void) {
    if (g_ready) return;
    nb_pointer_init(&g_ptr);
    memset(&g_kbd, 0, sizeof(g_kbd));
    g_ready = 1;
}

static jbyteArray frames_or_null(JNIEnv *env, uint8_t *buf, int frames) {
    jbyteArray arr;
    if (frames <= 0) return NULL;
    arr = (*env)->NewByteArray(env, frames * NB_FRAME);
    if (arr == NULL) return NULL;
    (*env)->SetByteArrayRegion(env, arr, 0, frames * NB_FRAME, (const jbyte *)buf);
    return arr;
}

JNIEXPORT void JNICALL
Java_com_titanus2_usbhid_NeckbandNative_reset(JNIEnv *env, jclass cls) {
    (void)env;
    (void)cls;
    pthread_mutex_lock(&g_mu);
    nb_pointer_init(&g_ptr);
    memset(&g_kbd, 0, sizeof(g_kbd));
    g_ready = 1;
    pthread_mutex_unlock(&g_mu);
}

JNIEXPORT void JNICALL
Java_com_titanus2_usbhid_NeckbandNative_setGain(JNIEnv *env, jclass cls, jint pct) {
    (void)env;
    (void)cls;
    pthread_mutex_lock(&g_mu);
    ensure_init();
    nb_pointer_set_gain(&g_ptr, (int)pct);
    pthread_mutex_unlock(&g_mu);
}

JNIEXPORT jbyteArray JNICALL
Java_com_titanus2_usbhid_NeckbandNative_auth(JNIEnv *env, jclass cls) {
    uint8_t frame[NB_FRAME];
    int n;
    jbyteArray arr;
    (void)cls;
    n = nb_frame_auth(frame);
    arr = (*env)->NewByteArray(env, n);
    if (arr == NULL) return NULL;
    (*env)->SetByteArrayRegion(env, arr, 0, n, (const jbyte *)frame);
    return arr;
}

JNIEXPORT jbyteArray JNICALL
Java_com_titanus2_usbhid_NeckbandNative_feed(JNIEnv *env, jclass cls,
        jint buttons, jint dx, jint dy, jint wheel) {
    uint8_t buf[8 * NB_FRAME];
    int frames = 0;
    int n;
    (void)cls;
    pthread_mutex_lock(&g_mu);
    ensure_init();
    if (dx != 0 || dy != 0) nb_pointer_move(&g_ptr, (int)dx, (int)dy);
    n = nb_pointer_buttons(&g_ptr, (int)buttons, buf, (int)sizeof(buf));
    frames = n;
    n = nb_pointer_wheel(&g_ptr, (int)wheel, buf + frames * NB_FRAME,
        (int)sizeof(buf) - frames * NB_FRAME);
    frames += n;
    pthread_mutex_unlock(&g_mu);
    return frames_or_null(env, buf, frames);
}

JNIEXPORT jbyteArray JNICALL
Java_com_titanus2_usbhid_NeckbandNative_takeHover(JNIEnv *env, jclass cls) {
    uint8_t frame[NB_FRAME];
    int n;
    (void)cls;
    pthread_mutex_lock(&g_mu);
    ensure_init();
    n = nb_pointer_take(&g_ptr, frame);
    pthread_mutex_unlock(&g_mu);
    return frames_or_null(env, frame, n);
}

JNIEXPORT jboolean JNICALL
Java_com_titanus2_usbhid_NeckbandNative_hoverPending(JNIEnv *env, jclass cls) {
    int p;
    (void)env;
    (void)cls;
    pthread_mutex_lock(&g_mu);
    ensure_init();
    p = nb_pointer_pending(&g_ptr);
    pthread_mutex_unlock(&g_mu);
    return p ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jbyteArray JNICALL
Java_com_titanus2_usbhid_NeckbandNative_scrollEnd(JNIEnv *env, jclass cls) {
    uint8_t frame[NB_FRAME];
    int n;
    (void)cls;
    pthread_mutex_lock(&g_mu);
    ensure_init();
    n = nb_pointer_scroll_end(&g_ptr, frame);
    pthread_mutex_unlock(&g_mu);
    return frames_or_null(env, frame, n);
}

JNIEXPORT jbyteArray JNICALL
Java_com_titanus2_usbhid_NeckbandNative_cancel(JNIEnv *env, jclass cls) {
    uint8_t frame[NB_FRAME];
    int n;
    (void)cls;
    pthread_mutex_lock(&g_mu);
    ensure_init();
    n = nb_pointer_cancel(&g_ptr, frame);
    pthread_mutex_unlock(&g_mu);
    return frames_or_null(env, frame, n);
}

JNIEXPORT jbyteArray JNICALL
Java_com_titanus2_usbhid_NeckbandNative_keyEdge(JNIEnv *env, jclass cls,
        jint mod, jint usage, jboolean press) {
    /* A full boot-report edge is at most 6+8+6 frames. 8 would drop key-ups. */
    uint8_t buf[24 * NB_FRAME];
    int n;
    (void)cls;
    pthread_mutex_lock(&g_mu);
    ensure_init();
    n = nb_kbd_edge(&g_kbd, (int)mod, (int)usage, press == JNI_TRUE, buf, (int)sizeof(buf));
    pthread_mutex_unlock(&g_mu);
    return frames_or_null(env, buf, n);
}

JNIEXPORT jbyteArray JNICALL
Java_com_titanus2_usbhid_NeckbandNative_kbdReport(JNIEnv *env, jclass cls, jbyteArray report) {
    uint8_t raw[8];
    uint8_t buf[24 * NB_FRAME];
    int n = 0;
    jsize len;
    (void)cls;
    if (report == NULL) return NULL;
    len = (*env)->GetArrayLength(env, report);
    if (len < 8) return NULL;
    (*env)->GetByteArrayRegion(env, report, 0, 8, (jbyte *)raw);
    pthread_mutex_lock(&g_mu);
    ensure_init();
    n = nb_kbd_diff(&g_kbd, raw, buf, (int)sizeof(buf));
    pthread_mutex_unlock(&g_mu);
    return frames_or_null(env, buf, n);
}

JNIEXPORT jbyteArray JNICALL
Java_com_titanus2_usbhid_NeckbandNative_releaseKeys(JNIEnv *env, jclass cls) {
    uint8_t buf[24 * NB_FRAME];
    int n;
    (void)cls;
    pthread_mutex_lock(&g_mu);
    ensure_init();
    n = nb_kbd_release(&g_kbd, buf, (int)sizeof(buf));
    pthread_mutex_unlock(&g_mu);
    return frames_or_null(env, buf, n);
}

JNIEXPORT jbyteArray JNICALL
Java_com_titanus2_usbhid_NeckbandNative_text(JNIEnv *env, jclass cls, jbyteArray utf8) {
    jsize len;
    jbyte *bytes;
    uint8_t *out;
    int cap;
    int n;
    int pkg;
    jbyteArray arr = NULL;
    (void)cls;
    if (utf8 == NULL) return NULL;
    len = (*env)->GetArrayLength(env, utf8);
    if (len < 0) return NULL;
    if (len > 15 * 40) len = 15 * 40;
    bytes = (*env)->GetByteArrayElements(env, utf8, NULL);
    if (bytes == NULL && len > 0) return NULL;
    cap = ((len / 15) + 2) * NB_FRAME;
    out = (uint8_t *)malloc((size_t)cap);
    if (out == NULL) {
        if (bytes != NULL) (*env)->ReleaseByteArrayElements(env, utf8, bytes, JNI_ABORT);
        return NULL;
    }
    pthread_mutex_lock(&g_mu);
    pkg = (g_pkg++ ) & 0xff;
    n = nb_text_encode(out, cap, (const uint8_t *)bytes, (int)len, pkg);
    pthread_mutex_unlock(&g_mu);
    if (bytes != NULL) (*env)->ReleaseByteArrayElements(env, utf8, bytes, JNI_ABORT);
    if (n > 0) {
        arr = (*env)->NewByteArray(env, n * NB_FRAME);
        if (arr != NULL) {
            (*env)->SetByteArrayRegion(env, arr, 0, n * NB_FRAME, (const jbyte *)out);
        }
    }
    free(out);
    return arr;
}
