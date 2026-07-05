#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <stdbool.h>
#include <stdint.h>
#include <math.h>
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

// [v2.0.53] Removed sigjmp_buf (doesn't work on Android ART)

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
    // [v2.0.92] Disable GPU to reduce native memory usage. GPU context allocation
    // can trigger SIGSEGV on devices with limited GPU memory. CPU-only mode is more stable.
    cparams.use_gpu = false;
    cparams.flash_attn = false;
    LOGI("initFromFile: calling whisper_init_from_file_with_params(\"%s\", use_gpu=false)", path);
    struct whisper_context* ctx = init_func(path, cparams);
    (*env)->ReleaseStringUTFChars(env, model_path, path);
    LOGI("initFromFile: result ctx=%p", ctx);
    return (jlong)(intptr_t)ctx;
}

// [v2.0.53] Signal handler only writes crash info to file before re-raising signal
// sigsetjmp/siglongjmp doesn't work on Android ART
static void whisper_crash_handler(int sig) {
    write_crash_to_file(sig);
    LOGE("whisper_crash_handler: native crash caught, signal=%d", sig);
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
    // [v2.0.61] Issue 2 Fix: Use GetFloatArrayRegion instead of GetFloatArrayElements
    // GetFloatArrayElements may return a direct pointer that GC can move, causing SIGSEGV
    // GetFloatArrayRegion copies data to a C buffer that is immune to GC movement
    jfloat* sample_data = (jfloat*)malloc(n_samples * sizeof(jfloat));
    if (!sample_data) {
        LOGE("full: failed to allocate sample_data buffer for %d samples", n_samples);
        return -3;
    }
    (*env)->GetFloatArrayRegion(env, samples, 0, n_samples, sample_data);
    LOGI("full: [v2.0.61] copied %d samples to C buffer (GetFloatArrayRegion)", n_samples);

    // [v2.0.57] Issue 2 Fix: Explicit NULL checks before processing
    if (!ctx) {
        LOGE("full: ctx is NULL, aborting");
        return -1;
    }
    if (!full_func) {
        LOGE("full: full_func is NULL (library not loaded), aborting");
        return -2;
    }

    // [v2.0.52] Issue 2 Fix: Remove sigsetjmp/siglongjmp (doesn't work on Android ART)
    // Android ART intercepts SIGSEGV before custom signal handlers, so siglongjmp never fires.
    // [v2.0.57] Issue 2 Fix: Reduce to 0.5 second (was 1s) - minimize memory to avoid OOM crash.
    // Also fix NaN check to check ALL samples (not just first 1000)
    // [v2.0.62] Issue 2 Fix: Process ALL samples passed from Kotlin (Kotlin handles chunking).
    // The previous 8000 (0.5s) cap was the root cause of "no output" - Whisper needs >=3s of audio.
    // Kotlin layer now passes 10-second chunks to balance memory vs recognition quality.
    int processSamples = n_samples;
    LOGI("full: n_samples=%d, processSamples=%d, ctx=%p", n_samples, processSamples, ctx);

    // [v2.0.66] Issue 1 Fix: Check for NaN/Infinity using isfinite() from <math.h>.
    // Previously used isnormal() which was an implicit declaration (undefined behavior → SIGSEGV).
    int nan_count = 0;
    for (int i = 0; i < processSamples; i++) {
        if (!isfinite(sample_data[i])) {
            sample_data[i] = 0.0f;  // Replace NaN/Inf with silence
            nan_count++;
        }
    }
    if (nan_count > 0) {
        LOGI("full: fixed %d NaN/Inf samples (out of %d)", nan_count, processSamples);
    }

    struct whisper_full_params params = params_func(WHISPER_SAMPLING_GREEDY);
    params.print_realtime  = false;
    params.print_progress  = false;
    params.print_timestamps = false;
    params.translate       = false;
    params.language        = (const char*)"zh";  // Force Chinese for Chinese radio
    // [v2.0.90] Memory optimization: single thread minimizes memory & avoids multi-thread bugs on Android
    params.n_threads       = 1;
    // [v2.1.8] Try: single_segment=true, audio_ctx=128, n_threads=2
    // single_segment simplifies processing by avoiding segment detection
    params.single_segment  = true;
    params.audio_ctx       = 128;   // explicit, ~1.28s of audio
    params.n_threads       = 2;     // try 2 threads (1 thread might have a bug)
    params.n_max_text_ctx  = 256;   // smaller than 512 to reduce memory
    params.max_tokens      = 32;    // smaller to reduce decoder work
    params.no_context      = true;
    params.temperature     = 0.0f;
    params.temperature_inc = 0.0f;
    // [v2.1.8] Suppress hallucination thresholds
    params.thold_pt        = 0.01f;
    params.thold_ptsum     = 0.01f;
    params.max_len         = 0;
    params.token_timestamps = false;

    // [v2.1.8] Removed alarm() - it sends SIGALRM which can kill the process
    // on some Android devices. The crash timeout is handled by the Kotlin layer.

    LOGI("full: BEFORE whisper_full, processSamples=%d, ctx=%p, sample_data=%p", processSamples, ctx, sample_data);
    LOGI("full: params: n_threads=%d, n_max_text_ctx=%d, audio_ctx=%d, max_tokens=%d, single_segment=%d",
         params.n_threads, params.n_max_text_ctx, params.audio_ctx, params.max_tokens, params.single_segment);
    LOGI("full: sample_data[0]=%f, [1]=%f, [2]=%f, last=%f",
         processSamples > 0 ? sample_data[0] : 0.0f,
         processSamples > 1 ? sample_data[1] : 0.0f,
         processSamples > 2 ? sample_data[2] : 0.0f,
         processSamples > 0 ? sample_data[processSamples-1] : 0.0f);

    int result = full_func(ctx, params, sample_data, processSamples);

    LOGI("full: AFTER whisper_full, result=%d", result);

    // [v2.0.61] Issue 2 Fix: Free the C-allocated buffer (not ReleaseFloatArrayElements)
    free(sample_data);
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
