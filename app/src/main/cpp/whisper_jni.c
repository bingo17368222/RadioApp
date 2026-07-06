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
#include <pthread.h>
#include <stdarg.h>
#include <stdio.h>

#define LOG_TAG "WhisperBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#include "whisper.h"

// [v2.3.0] File logging for native code - writes to same log dir as Kotlin
static int g_native_log_fd = -1;
static pthread_mutex_t g_log_mutex = PTHREAD_MUTEX_INITIALIZER;

static void native_log(const char* level, const char* fmt, ...) {
    pthread_mutex_lock(&g_log_mutex);
    if (g_native_log_fd < 0) {
        // Open log file on first use
        const char* log_path = "/data/data/com.radio.app/files/logs/subtitle/native.log";
        g_native_log_fd = open(log_path, O_WRONLY | O_CREAT | O_APPEND, 0644);
        // Also try external path as fallback
        if (g_native_log_fd < 0) {
            log_path = "/storage/emulated/0/Android/data/com.radio.app/files/logs/subtitle/native.log";
            g_native_log_fd = open(log_path, O_WRONLY | O_CREAT | O_APPEND, 0644);
        }
    }
    if (g_native_log_fd >= 0) {
        char buf[1024];
        va_list args;
        va_start(args, fmt);
        int len = vsnprintf(buf, sizeof(buf), fmt, args);
        va_end(args);
        if (len > 0) {
            // Write level and message
            write(g_native_log_fd, level, strlen(level));
            write(g_native_log_fd, buf, len < (int)sizeof(buf)-1 ? len : (int)sizeof(buf)-1);
            const char* nl = "\n";
            write(g_native_log_fd, nl, 1);
            // Don't fsync every time for performance; just flush
        }
    }
    pthread_mutex_unlock(&g_log_mutex);
}

#define NLOGI(...) do { LOGI(__VA_ARGS__); native_log("[NATIVE INFO] ", __VA_ARGS__); } while(0)
#define NLOGE(...) do { LOGE(__VA_ARGS__); native_log("[NATIVE ERROR] ", __VA_ARGS__); } while(0)

// [v2.2.5] Crash log file writer - async-signal-safe
static void write_crash_to_file(int sig) {
    int fd = open("/data/data/com.radio.app/files/logs/whisper/whisper_crash.log",
                  O_WRONLY | O_CREAT | O_APPEND, 0644);
    if (fd >= 0) {
        char buf[256];
        int len = snprintf(buf, sizeof(buf),
            "=== CRASH signal=%d (SIGSEGV=11, SIGABRT=6, SIGBUS=7, SIGILL=4) ===\n", sig);
        if (len > 0) write(fd, buf, len);
        close(fd);
    }
}

static void whisper_crash_handler(int sig) {
    write_crash_to_file(sig);
    LOGE("whisper_crash_handler: native crash caught, signal=%d", sig);
    signal(sig, SIG_DFL);
    raise(sig);
}

static void register_crash_handlers() {
    signal(SIGSEGV, whisper_crash_handler);
    signal(SIGABRT, whisper_crash_handler);
    signal(SIGBUS,  whisper_crash_handler);
    signal(SIGFPE,  whisper_crash_handler);
    signal(SIGILL,  whisper_crash_handler);
}

// [v2.3.0] whisper.cpp internal log callback - capture logs from library
static void whisper_log_cb(ggml_log_level level, const char* text, void* user_data) {
    (void)user_data;
    if (text && *text) {
        // Trim trailing newline for consistency
        char buf[512];
        strncpy(buf, text, sizeof(buf)-1);
        buf[sizeof(buf)-1] = '\0';
        int len = strlen(buf);
        while (len > 0 && (buf[len-1] == '\n' || buf[len-1] == '\r')) buf[--len] = '\0';
        if (len > 0) {
            if (level == GGML_LOG_LEVEL_ERROR) {
                LOGE("whisper.cpp: %s", buf);
                native_log("[WHISPER ERROR] ", "%s", buf);
            } else {
                LOGI("whisper.cpp: %s", buf);
                native_log("[WHISPER] ", "%s", buf);
            }
        }
    }
}

// Function pointer types for dynamically loaded libwhisper.so
// [v2.3.1] ABI-SAFE approach: Use the same by-value pattern as whisper_init_from_file_with_params
// (which works correctly). The key fix is the _reserved[2048] padding in whisper_full_params
// struct definition, which ensures our local struct is LARGER than the library's, preventing
// stack buffer overflow when the library writes/reads the struct by value.
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
typedef void (*whisper_free_params_t)(struct whisper_full_params* params);
typedef struct whisper_context_params (*whisper_context_default_params_t)(void);
typedef const char* (*whisper_print_system_info_t)(void);
typedef void (*whisper_log_set_t)(ggml_log_callback log_callback, void* user_data);

static void* whisper_lib = NULL;
static whisper_init_from_file_with_params_t init_func = NULL;
static whisper_full_t full_func = NULL;
static whisper_full_n_segments_t nseg_func = NULL;
static whisper_full_get_segment_text_t text_func = NULL;
static whisper_full_get_segment_t0_t t0_func = NULL;
static whisper_full_get_segment_t1_t t1_func = NULL;
static whisper_free_t free_func = NULL;
static whisper_full_default_params_t params_func = NULL;
static whisper_free_params_t free_params_func = NULL;
static whisper_context_default_params_t ctx_params_func = NULL;
static whisper_print_system_info_t system_info_func = NULL;
static whisper_log_set_t log_set_func = NULL;

static char whisper_so_path[512] = "libwhisper.so";

static int load_whisper_funcs() {
    if (init_func != NULL) return 1;

    NLOGI("load_whisper_funcs: attempting dlopen(\"%s\")", whisper_so_path);
    whisper_lib = dlopen(whisper_so_path, RTLD_NOW);
    if (!whisper_lib) {
        const char* err = dlerror();
        NLOGE("load_whisper_funcs: dlopen(\"%s\") failed: %s", whisper_so_path, err ? err : "unknown");
        if (strcmp(whisper_so_path, "libwhisper.so") != 0) {
            NLOGI("load_whisper_funcs: fallback dlopen(\"libwhisper.so\")");
            whisper_lib = dlopen("libwhisper.so", RTLD_NOW);
        }
        if (!whisper_lib) {
            err = dlerror();
            NLOGE("load_whisper_funcs: fallback dlopen also failed: %s", err ? err : "unknown");
            return 0;
        }
    }
    NLOGI("load_whisper_funcs: dlopen succeeded, resolving symbols...");

    init_func           = (whisper_init_from_file_with_params_t)  dlsym(whisper_lib, "whisper_init_from_file_with_params");
    full_func           = (whisper_full_t)                        dlsym(whisper_lib, "whisper_full");
    nseg_func           = (whisper_full_n_segments_t)             dlsym(whisper_lib, "whisper_full_n_segments");
    text_func           = (whisper_full_get_segment_text_t)       dlsym(whisper_lib, "whisper_full_get_segment_text");
    t0_func             = (whisper_full_get_segment_t0_t)         dlsym(whisper_lib, "whisper_full_get_segment_t0");
    t1_func             = (whisper_full_get_segment_t1_t)         dlsym(whisper_lib, "whisper_full_get_segment_t1");
    free_func           = (whisper_free_t)                        dlsym(whisper_lib, "whisper_free");
    params_func         = (whisper_full_default_params_t)         dlsym(whisper_lib, "whisper_full_default_params");
    free_params_func    = (whisper_free_params_t)                 dlsym(whisper_lib, "whisper_free_params");
    ctx_params_func     = (whisper_context_default_params_t)      dlsym(whisper_lib, "whisper_context_default_params");
    system_info_func    = (whisper_print_system_info_t)           dlsym(whisper_lib, "whisper_print_system_info");
    log_set_func        = (whisper_log_set_t)                     dlsym(whisper_lib, "whisper_log_set");

    NLOGI("load_whisper_funcs: init=%p full=%p nseg=%p text=%p free=%p params=%p free_params=%p sysinfo=%p log_set=%p",
         (void*)init_func, (void*)full_func, (void*)nseg_func, (void*)text_func, (void*)free_func,
         (void*)params_func, (void*)free_params_func, (void*)system_info_func, (void*)log_set_func);

    if (!init_func || !full_func || !nseg_func || !text_func || !free_func) {
        NLOGE("load_whisper_funcs: one or more required symbols not found");
        return 0;
    }
    // [v2.3.1] Use by-value whisper_full_default_params (same pattern as ctx_params which works).
    // The _reserved[2048] padding in our struct ensures we have enough stack space.
    if (!params_func) {
        NLOGE("load_whisper_funcs: whisper_full_default_params not found");
        return 0;
    }

    // [v2.3.0] Install whisper.cpp log callback to capture internal errors
    if (log_set_func) {
        log_set_func(whisper_log_cb, NULL);
        NLOGI("load_whisper_funcs: installed whisper.cpp log callback");
    }

    NLOGI("load_whisper_funcs: all required symbols resolved");
    return 1;
}

JNIEXPORT void JNICALL
Java_com_radio_app_whisper_WhisperBridge_setLibraryPathNative(JNIEnv* env, jobject thiz, jstring path) {
    const char* cpath = (*env)->GetStringUTFChars(env, path, NULL);
    if (cpath) {
        strncpy(whisper_so_path, cpath, sizeof(whisper_so_path) - 1);
        whisper_so_path[sizeof(whisper_so_path) - 1] = '\0';
        NLOGI("setLibraryPathNative: path=\"%s\"", whisper_so_path);
        (*env)->ReleaseStringUTFChars(env, path, cpath);
    }
}

JNIEXPORT jlong JNICALL
Java_com_radio_app_whisper_WhisperBridge_initFromFile(JNIEnv* env, jobject thiz, jstring model_path) {
    register_crash_handlers();

    if (!load_whisper_funcs()) {
        NLOGE("initFromFile: load_whisper_funcs failed");
        return 0;
    }
    const char* path = (*env)->GetStringUTFChars(env, model_path, NULL);

    if (system_info_func) {
        const char* sysinfo = system_info_func();
        NLOGI("initFromFile: system info: %s", sysinfo ? sysinfo : "(null)");
    }

    struct whisper_context_params cparams;
    memset(&cparams, 0, sizeof(cparams));
    if (ctx_params_func) {
        cparams = ctx_params_func();
    }
    cparams.use_gpu = false;
    cparams.flash_attn = false;
    NLOGI("initFromFile: loading model from \"%s\" (use_gpu=false)", path);
    struct whisper_context* ctx = init_func(path, cparams);
    (*env)->ReleaseStringUTFChars(env, model_path, path);
    NLOGI("initFromFile: ctx=%p", (void*)ctx);
    if (!ctx) {
        NLOGE("initFromFile: FAILED to create context (returned NULL) - model file may be corrupt or wrong format");
    }
    return (jlong)(intptr_t)ctx;
}

// [v2.3.1] Build params using the SAME by-value pattern as whisper_context_default_params
// (which works for init). The _reserved[2048] padding in our struct ensures the local
// buffer is large enough to receive the full struct from the library without stack overflow.
static void setup_params(struct whisper_full_params* params) {
    if (!params_func) {
        NLOGE("setup_params: params_func is NULL!");
        memset(params, 0, sizeof(struct whisper_full_params));
        return;
    }

    NLOGI("setup_params: getting defaults via by-value params_func");
    // Get defaults from library by value into our (padded) local struct.
    // The _reserved[2048] ensures the buffer is large enough for any whisper.cpp version.
    *params = params_func(WHISPER_SAMPLING_GREEDY);
    NLOGI("setup_params: got defaults by value, strategy=%d, sizeof(params)=%zu",
         (int)params->strategy, sizeof(struct whisper_full_params));

    // Override core early fields for Chinese streaming ASR.
    // [v2.3.2] Key parameter fixes for "0 segments" issue:
    // - single_segment=false: let Whisper manage segments naturally (true forces 1 segment
    //   which can cause 0 output when audio doesn't fit a single segment cleanly)
    // - no_context=false: allow Whisper to use internal context between calls (important
    //   for streaming; we reset context at episode boundaries by recreating ctx)
    // - audio_ctx=768: limit audio context tokens to prevent excessive memory allocation
    //   (0 = default which is 1500 tokens = 30s, way too much for 10s chunks)
    // - n_threads=2: safe for mobile
    params->strategy         = WHISPER_SAMPLING_GREEDY;
    params->n_threads        = 2;
    params->translate        = false;
    params->no_context       = false;       // [v2.3.2] Allow context for better recognition
    params->single_segment   = false;       // [v2.3.2] Don't force single segment
    params->print_special    = false;
    params->print_progress   = false;
    params->print_realtime   = false;
    params->print_timestamps = false;
    params->token_timestamps = false;
    params->debug_mode       = false;
    params->audio_ctx        = 768;         // [v2.3.2] Limit audio context (~15s) instead of 0=auto(30s)

    params->language         = "zh";
    params->detect_language  = false;

    params->suppress_blank             = true;
    params->suppress_non_speech_tokens = true;
    params->temperature                = 0.0f;
    params->greedy.best_of             = 1;
    params->beam_search.beam_size      = 1;

    // NULL out callback/pointer fields to prevent library from calling into stale memory
    params->initial_prompt   = NULL;
    params->prompt_tokens    = NULL;
    params->prompt_n_tokens  = 0;
    params->suppress_regex   = NULL;
    params->new_segment_callback           = NULL;
    params->new_segment_callback_user_data = NULL;
    params->progress_callback              = NULL;
    params->progress_callback_user_data    = NULL;
    params->encoder_begin_callback         = NULL;
    params->encoder_begin_callback_user_data = NULL;
    params->abort_callback                 = NULL;
    params->abort_callback_user_data       = NULL;
    params->logits_filter_callback         = NULL;
    params->logits_filter_callback_user_data = NULL;
    params->grammar_rules                  = NULL;
    params->n_grammar_rules                = 0;
    params->i_start_rule                   = 0;
    params->grammar_penalty                = 0.0f;

    NLOGI("setup_params: ready n_threads=%d lang=%s no_context=%d single_segment=%d",
         params->n_threads,
         params->language ? params->language : "(null)",
         (int)params->no_context,
         (int)params->single_segment);
}

JNIEXPORT jint JNICALL
Java_com_radio_app_whisper_WhisperBridge_full(JNIEnv* env, jobject thiz, jlong ctx_ptr, jfloatArray samples, jint n_samples) {
    if (!ctx_ptr || !full_func) {
        NLOGE("full: invalid state ctx=%lld full=%p", (long long)ctx_ptr, (void*)full_func);
        return -1;
    }
    struct whisper_context* ctx = (struct whisper_context*)(intptr_t)ctx_ptr;
    if (!ctx) {
        NLOGE("full: ctx is NULL after cast");
        return -1;
    }

    jfloat* sample_data = (jfloat*)malloc((size_t)n_samples * sizeof(jfloat));
    if (!sample_data) {
        NLOGE("full: malloc failed for %d samples", n_samples);
        return -3;
    }
    (*env)->GetFloatArrayRegion(env, samples, 0, n_samples, sample_data);

    // Check for NaN/Inf and compute audio stats
    int nan_count = 0;
    float abs_max = 0.0f;
    float abs_sum = 0.0f;
    int silence_count = 0;
    for (int i = 0; i < n_samples; i++) {
        float s = sample_data[i];
        if (!isfinite(s)) { sample_data[i] = 0.0f; nan_count++; continue; }
        float a = fabsf(s);
        if (a > abs_max) abs_max = a;
        abs_sum += a;
        if (a < 0.001f) silence_count++;
    }
    float avg_amp = abs_sum / (float)(n_samples > 0 ? n_samples : 1);
    int silence_pct = (int)((float)silence_count * 100.0f / (float)(n_samples > 0 ? n_samples : 1));
    NLOGI("full: n_samples=%d, abs_max=%.6f, avg_amp=%.6f, silence_pct=%d%%, nan_count=%d",
         n_samples, abs_max, avg_amp, silence_pct, nan_count);

    // Check for completely silent audio (avoid passing pure silence which may cause issues)
    if (abs_max < 0.0001f) {
        NLOGI("full: audio is nearly silent (abs_max=%.6f), returning 0 segments (not an error)", abs_max);
        free(sample_data);
        return 0;  // Return success with 0 segments - not an error
    }

    // [v2.3.1] Build params using by-value defaults (same pattern as init which works).
    // Local struct has _reserved[2048] padding to prevent stack overflow.
    struct whisper_full_params params;
    setup_params(&params);

    NLOGI("full: calling whisper_full, ctx=%p n_samples=%d n_threads=%d lang=%s",
         (void*)ctx, n_samples, params.n_threads, params.language ? params.language : "(null)");

    // Pass params by value - same pattern as init_func(path, cparams) which works.
    // The C compiler handles ARM64 AAPCS indirect passing automatically.
    int result = full_func(ctx, params, sample_data, n_samples);

    NLOGI("full: whisper_full returned %d", result);
    if (result == 0 && nseg_func) {
        int n = nseg_func(ctx);
        NLOGI("full: whisper_full_n_segments = %d", n);
    }
    if (result != 0) {
        NLOGE("full: whisper_full FAILED with code %d (n_samples=%d, abs_max=%.4f, lang=%s)",
             result, n_samples, abs_max, params.language ? params.language : "(null)");
    }

    free(sample_data);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_radio_app_whisper_WhisperBridge_fullNSegments(JNIEnv* env, jobject thiz, jlong ctx_ptr) {
    if (!ctx_ptr || !nseg_func) return 0;
    int n = nseg_func((struct whisper_context*)(intptr_t)ctx_ptr);
    NLOGI("fullNSegments: ctx=%p n_segments=%d", (void*)(intptr_t)ctx_ptr, n);
    return n;
}

JNIEXPORT jstring JNICALL
Java_com_radio_app_whisper_WhisperBridge_fullGetSegmentText(JNIEnv* env, jobject thiz, jlong ctx_ptr, jint segment_index) {
    if (!ctx_ptr || !text_func) return (*env)->NewStringUTF(env, "");
    const char* text = text_func((struct whisper_context*)(intptr_t)ctx_ptr, segment_index);
    const char* safe_text = text ? text : "";
    NLOGI("fullGetSegmentText: seg=%d text=\"%s\"", segment_index, safe_text);
    return (*env)->NewStringUTF(env, safe_text);
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
    NLOGI("free: freeing ctx=%p", (void*)(intptr_t)ctx_ptr);
    free_func((struct whisper_context*)(intptr_t)ctx_ptr);
}
