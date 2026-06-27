#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <stdbool.h>
#include <stdint.h>
#include <dlfcn.h>
#include <android/log.h>
#include <signal.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/stat.h>

#define LOG_TAG "WhisperBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#include "whisper.h"

// [v2.0.43] Issue 3&7: Write crash info to log file before re-raising signal
// Uses async-signal-safe functions only (open, write, close)
static void write_crash_to_file(int sig) {
    int fd = open("/data/data/com.radio.app/files/logs/whisper/whisper_crash.log",
                  O_WRONLY | O_CREAT | O_APPEND, 0644);
    if (fd >= 0) {
        char buf[256];
        int len = snprintf(buf, sizeof(buf),
            "=== CRASH signal=%d (SIGSEGV=11, SIGABRT=6) ===\n", sig);
        if (len > 0) write(fd, buf, len);
        close(fd);
    }
}

// Function pointer types matching whisper.h API signatures
typedef struct whisper_context* (*whisper_init_from_file_with_params_t)(
    const char* path_model, struct whisper_context_params params);
typedef int (*whisper_full_t)(
    struct whisper_context* ctx, struct whisper_full_params params,
    const float* samples, int n_samples);
typedef int (*whisper_full_n_segments_t)(struct whisper_context* ctx);
typedef const char* (*whisper_full_get_segment_text_t)(struct whisper_context* ctx, int i_segment);
typedef int64_t (*whisper_full_get_segment_t0_t)(struct whisper_context* ctx, int i_segment);
typedef int64_t (*whisper_full_get_segment_t1_t)(struct whisper_context* ctx, int i_segment);
typedef void (*whisper_free_t)(struct whisper_context* ctx);
typedef struct whisper_full_params (*whisper_full_default_params_t)(
    enum whisper_sampling_strategy strategy);
typedef struct whisper_context_params (*whisper_context_default_params_t)(void);

static void* whisper_lib = NULL;
static whisper_init_from_file_with_params_t init_func = NULL;
static whisper_full_t full_func = NULL;
static whisper_full_n_segments_t nseg_func = NULL;
static whisper_full_get_segment_text_t text_func = NULL;
static whisper_full_get_segment_t0_t t0_func = NULL;
static whisper_full_get_segment_t1_t t1_func = NULL;
static whisper_free_t free_func = NULL;
static whisper_full_default_params_t params_func = NULL;
static whisper_context_default_params_t ctx_params_func = NULL;

// Path to libwhisper.so, set from Java via setLibraryPathNative
static char whisper_so_path[512] = "libwhisper.so";

// Load function pointers from libwhisper.so using dlopen
static int load_whisper_funcs() {
    if (init_func != NULL) return 1;

    LOGI("load_whisper_funcs: attempting dlopen(\"%s\")", whisper_so_path);
    whisper_lib = dlopen(whisper_so_path, RTLD_NOW);
    if (!whisper_lib) {
        const char* err = dlerror();
        LOGE("load_whisper_funcs: dlopen(\"%s\") failed: %s", whisper_so_path, err ? err : "unknown");

        // Fallback: try just the library name (works if already loaded via System.load)
        if (strcmp(whisper_so_path, "libwhisper.so") != 0) {
            LOGI("load_whisper_funcs: fallback dlopen(\"libwhisper.so\")");
            whisper_lib = dlopen("libwhisper.so", RTLD_NOW);
        }
        if (!whisper_lib) {
            err = dlerror();
            LOGE("load_whisper_funcs: fallback dlopen also failed: %s", err ? err : "unknown");
            return 0;
        }
    }
    LOGI("load_whisper_funcs: dlopen succeeded, resolving symbols...");

    init_func       = (whisper_init_from_file_with_params_t) dlsym(whisper_lib, "whisper_init_from_file_with_params");
    full_func       = (whisper_full_t)                       dlsym(whisper_lib, "whisper_full");
    nseg_func       = (whisper_full_n_segments_t)             dlsym(whisper_lib, "whisper_full_n_segments");
    text_func       = (whisper_full_get_segment_text_t)       dlsym(whisper_lib, "whisper_full_get_segment_text");
    t0_func         = (whisper_full_get_segment_t0_t)         dlsym(whisper_lib, "whisper_full_get_segment_t0");
    t1_func         = (whisper_full_get_segment_t1_t)         dlsym(whisper_lib, "whisper_full_get_segment_t1");
    free_func       = (whisper_free_t)                        dlsym(whisper_lib, "whisper_free");
    params_func     = (whisper_full_default_params_t)         dlsym(whisper_lib, "whisper_full_default_params");
    ctx_params_func = (whisper_context_default_params_t)      dlsym(whisper_lib, "whisper_context_default_params");

    LOGI("load_whisper_funcs: init_func=%p, full_func=%p, nseg_func=%p, text_func=%p, free_func=%p, params_func=%p",
         init_func, full_func, nseg_func, text_func, free_func, params_func);

    if (!init_func || !full_func || !nseg_func || !text_func || !free_func || !params_func) {
        LOGE("load_whisper_funcs: one or more required symbols not found");
        return 0;
    }
    LOGI("load_whisper_funcs: all symbols resolved successfully");
    return 1;
}

JNIEXPORT void JNICALL
Java_com_radio_app_whisper_WhisperBridge_setLibraryPathNative(JNIEnv* env, jobject thiz, jstring path) {
    const char* cpath = (*env)->GetStringUTFChars(env, path, NULL);
    if (cpath) {
        strncpy(whisper_so_path, cpath, sizeof(whisper_so_path) - 1);
        whisper_so_path[sizeof(whisper_so_path) - 1] = '\0';
        LOGI("setLibraryPathNative: path set to \"%s\"", whisper_so_path);
        (*env)->ReleaseStringUTFChars(env, path, cpath);
    }
}

JNIEXPORT jlong JNICALL
Java_com_radio_app_whisper_WhisperBridge_initFromFile(JNIEnv* env, jobject thiz, jstring model_path) {
    if (!load_whisper_funcs()) {
        LOGE("initFromFile: load_whisper_funcs failed");
        return 0;
    }
    const char* path = (*env)->GetStringUTFChars(env, model_path, NULL);
    struct whisper_context_params cparams;
    memset(&cparams, 0, sizeof(cparams));
    if (ctx_params_func) {
        cparams = ctx_params_func();
    }
    LOGI("initFromFile: calling whisper_init_from_file_with_params(\"%s\")", path);
    struct whisper_context* ctx = init_func(path, cparams);
    (*env)->ReleaseStringUTFChars(env, model_path, path);
    LOGI("initFromFile: result ctx=%p", ctx);
    return (jlong)(intptr_t)ctx;
}

// [v2.0.43] Issue 3&7: Signal handler writes crash info to log file BEFORE re-raising
static void whisper_crash_handler(int sig) {
    LOGE("whisper_crash_handler: native crash caught, signal=%d", sig);
    write_crash_to_file(sig);
    // Restore default handler and re-raise so a tombstone is generated
    signal(sig, SIG_DFL);
    raise(sig);
}

JNIEXPORT jint JNICALL
Java_com_radio_app_whisper_WhisperBridge_full(JNIEnv* env, jobject thiz, jlong ctx_ptr, jfloatArray samples, jint n_samples) {
    if (!ctx_ptr || !full_func || !params_func) {
        LOGE("full: invalid state - ctx_ptr=%lld, full_func=%p, params_func=%p", (long long)ctx_ptr, full_func, params_func);
        return -1;
    }
    struct whisper_context* ctx = (struct whisper_context*)(intptr_t)ctx_ptr;
    jfloat* sample_data = (*env)->GetFloatArrayElements(env, samples, NULL);

    // [v2.0.47] Issue 3 Fix: Reduce to 10 seconds - 30 seconds still crashed
    // 10 seconds = 160000 samples at 16kHz
    int maxSamples = 10 * 16000;
    int processSamples = n_samples < maxSamples ? n_samples : maxSamples;
    LOGI("full: n_samples=%d, processSamples=%d (capped at 10s), ctx=%p", n_samples, processSamples, ctx);

    // [v2.0.45] Issue 3 Fix: Check for NaN/Infinity in samples that could cause SIGSEGV
    int hasBadSamples = 0;
    for (int i = 0; i < processSamples && i < 1000; i++) {
        if (sample_data[i] != sample_data[i] || sample_data[i] > 1.0f || sample_data[i] < -1.0f) {
            hasBadSamples = 1;
            LOGE("full: bad sample at index %d: %f", i, sample_data[i]);
            break;
        }
    }
    if (hasBadSamples) {
        LOGE("full: found NaN/Infinity/out-of-range samples, clamping to [-1, 1]");
        for (int i = 0; i < processSamples; i++) {
            if (sample_data[i] != sample_data[i]) sample_data[i] = 0.0f;  // NaN -> 0
            else if (sample_data[i] > 1.0f) sample_data[i] = 1.0f;
            else if (sample_data[i] < -1.0f) sample_data[i] = -1.0f;
        }
    }

    struct whisper_full_params params = params_func(WHISPER_SAMPLING_GREEDY);
    params.print_realtime  = false;
    params.print_progress  = false;
    params.print_timestamps = false;
    params.translate       = false;
    params.n_threads       = 2;  // Limit threads to prevent resource contention

    // Install simple signal handler to log crashes
    signal(SIGSEGV, whisper_crash_handler);
    signal(SIGABRT, whisper_crash_handler);

    LOGI("full: calling whisper_full(ctx=%p, processSamples=%d)", ctx, processSamples);
    int result = -999;

    // Set alarm timeout (120 seconds)
    alarm(120);

    result = full_func(ctx, params, sample_data, processSamples);

    // Cancel alarm
    alarm(0);

    (*env)->ReleaseFloatArrayElements(env, samples, sample_data, JNI_ABORT);
    LOGI("full: whisper_full returned %d", result);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_radio_app_whisper_WhisperBridge_fullNSegments(JNIEnv* env, jobject thiz, jlong ctx_ptr) {
    if (!ctx_ptr || !nseg_func) return 0;
    return nseg_func((struct whisper_context*)(intptr_t)ctx_ptr);
}

JNIEXPORT jstring JNICALL
Java_com_radio_app_whisper_WhisperBridge_fullGetSegmentText(JNIEnv* env, jobject thiz, jlong ctx_ptr, jint segment_index) {
    if (!ctx_ptr || !text_func) return (*env)->NewStringUTF(env, "");
    const char* text = text_func((struct whisper_context*)(intptr_t)ctx_ptr, segment_index);
    return (*env)->NewStringUTF(env, text ? text : "");
}

JNIEXPORT jlong JNICALL
Java_com_radio_app_whisper_WhisperBridge_fullGetSegmentT0(JNIEnv* env, jobject thiz, jlong ctx_ptr, jint segment_index) {
    if (!ctx_ptr || !t0_func) return 0;
    return (jlong)t0_func((struct whisper_context*)(intptr_t)ctx_ptr, segment_index);
}

JNIEXPORT jlong JNICALL
Java_com_radio_app_whisper_WhisperBridge_fullGetSegmentT1(JNIEnv* env, jobject thiz, jlong ctx_ptr, jint segment_index) {
    if (!ctx_ptr || !t1_func) return 0;
    return (jlong)t1_func((struct whisper_context*)(intptr_t)ctx_ptr, segment_index);
}

JNIEXPORT void JNICALL
Java_com_radio_app_whisper_WhisperBridge_free(JNIEnv* env, jobject thiz, jlong ctx_ptr) {
    if (!ctx_ptr || !free_func) return;
    free_func((struct whisper_context*)(intptr_t)ctx_ptr);
}
