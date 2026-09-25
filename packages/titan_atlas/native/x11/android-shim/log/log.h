#pragma once
#include <android/log.h>
#ifndef ALOGE
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, "virgl", __VA_ARGS__)
#define ALOGW(...) __android_log_print(ANDROID_LOG_WARN, "virgl", __VA_ARGS__)
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, "virgl", __VA_ARGS__)
#define ALOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "virgl", __VA_ARGS__)
#define ALOGV(...) __android_log_print(ANDROID_LOG_VERBOSE, "virgl", __VA_ARGS__)
#endif
#ifndef LOG_PRI
#define LOG_PRI(priority, tag, ...) __android_log_print(priority, tag, __VA_ARGS__)
#endif
