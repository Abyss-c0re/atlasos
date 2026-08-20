/* JNI thin glue for termgrid.c — Android only */
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

void term_init(int cols, int rows);
void term_resize(int cols, int rows);
void term_feed(const uint8_t *data, int len);
void term_reset(void);
int term_cols(void);
int term_rows(void);
int term_dirty(void);
void term_clear_dirty(void);
int term_cx(void);
int term_cy(void);
int term_copy_row(int row, uint32_t *ch, uint32_t *fg, uint32_t *bg, int maxc);
int term_frame_ints(void);
int term_fill_frame(int32_t *out, int max);

JNIEXPORT void JNICALL
Java_com_titanus2_atlas_TermGrid_nativeInit(JNIEnv *env, jclass cls, jint cols, jint rows) {
    (void)env; (void)cls;
    term_init(cols, rows);
}

JNIEXPORT void JNICALL
Java_com_titanus2_atlas_TermGrid_nativeFeed(JNIEnv *env, jclass cls, jbyteArray arr) {
    (void)cls;
    if (!arr) return;
    jsize n = (*env)->GetArrayLength(env, arr);
    jbyte *p = (*env)->GetByteArrayElements(env, arr, NULL);
    if (p) {
        term_feed((const uint8_t *)p, (int)n);
        (*env)->ReleaseByteArrayElements(env, arr, p, JNI_ABORT);
    }
}

JNIEXPORT void JNICALL
Java_com_titanus2_atlas_TermGrid_nativeReset(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    term_reset();
}

JNIEXPORT jint JNICALL
Java_com_titanus2_atlas_TermGrid_nativeCols(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return term_cols();
}

JNIEXPORT jint JNICALL
Java_com_titanus2_atlas_TermGrid_nativeRows(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return term_rows();
}

JNIEXPORT jboolean JNICALL
Java_com_titanus2_atlas_TermGrid_nativeDirty(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return term_dirty() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_titanus2_atlas_TermGrid_nativeClearDirty(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    term_clear_dirty();
}

JNIEXPORT jint JNICALL
Java_com_titanus2_atlas_TermGrid_nativeCx(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return term_cx();
}

JNIEXPORT jint JNICALL
Java_com_titanus2_atlas_TermGrid_nativeCy(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return term_cy();
}

/* Returns int[] layout: [n, ch0,fg0,bg0, ch1,fg1,bg1, ...] — stack, no heap. */
JNIEXPORT jintArray JNICALL
Java_com_titanus2_atlas_TermGrid_nativeRow(JNIEnv *env, jclass cls, jint row) {
    (void)cls;
    int cols = term_cols();
    if (cols <= 0 || cols > 256) return NULL;
    uint32_t ch[256], fg[256], bg[256];
    int n = term_copy_row(row, ch, fg, bg, cols);
    jintArray out = (*env)->NewIntArray(env, 1 + n * 3);
    if (!out) return NULL;
    jint buf[1 + 256 * 3];
    buf[0] = n;
    for (int i = 0; i < n; i++) {
        buf[1 + i * 3] = (jint)ch[i];
        buf[2 + i * 3] = (jint)fg[i];
        buf[3 + i * 3] = (jint)bg[i];
    }
    (*env)->SetIntArrayRegion(env, out, 0, 1 + n * 3, buf);
    return out;
}

JNIEXPORT jint JNICALL
Java_com_titanus2_atlas_TermGrid_nativeFrameInts(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return term_frame_ints();
}

/* Fills a reusable Java int[]: [cols, rows, cx, cy, (ch,fg,bg)*] */
JNIEXPORT jint JNICALL
Java_com_titanus2_atlas_TermGrid_nativeFill(JNIEnv *env, jclass cls, jintArray arr) {
    (void)cls;
    if (!arr) return 0;
    jsize n = (*env)->GetArrayLength(env, arr);
    jint *p = (*env)->GetIntArrayElements(env, arr, NULL);
    if (!p) return 0;
    int w = term_fill_frame((int32_t *)p, (int)n);
    (*env)->ReleaseIntArrayElements(env, arr, p, 0);
    return w;
}
