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

// Function pointer types for dynamically loaded libwhisper.so
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
typedef const char* (*whisper_print_system_info_t)(void);

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
static whisper_print_system_info_t system_info_func = NULL;

static char whisper_so_path[512] = "libwhisper.so";

static int load_whisper_funcs() {
    if (init_func != NULL) return 1;

    LOGI("load_whisper_funcs: attempting dlopen(\"%s\")", whisper_so_path);
    whisper_lib = dlopen(whisper_so_path, RTLD_NOW);
    if (!whisper_lib) {
        const char* err = dlerror();
        LOGE("load_whisper_funcs: dlopen(\"%s\") failed: %s", whisper_so_path, err ? err : "unknown");
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

    init_func        = (whisper_init_from_file_with_params_t) dlsym(whisper_lib, "whisper_init_from_file_with_params");
    full_func        = (whisper_full_t)                       dlsym(whisper_lib, "whisper_full");
    nseg_func        = (whisper_full_n_segments_t)            dlsym(whisper_lib, "whisper_full_n_segments");
    text_func        = (whisper_full_get_segment_text_t)      dlsym(whisper_lib, "whisper_full_get_segment_text");
    t0_func          = (whisper_full_get_segment_t0_t)        dlsym(whisper_lib, "whisper_full_get_segment_t0");
    t1_func          = (whisper_full_get_segment_t1_t)        dlsym(whisper_lib, "whisper_full_get_segment_t1");
    free_func        = (whisper_free_t)                       dlsym(whisper_lib, "whisper_free");
    params_func      = (whisper_full_default_params_t)        dlsym(whisper_lib, "whisper_full_default_params");
    ctx_params_func  = (whisper_context_default_params_t)     dlsym(whisper_lib, "whisper_context_default_params");
    system_info_func = (whisper_print_system_info_t)          dlsym(whisper_lib, "whisper_print_system_info");

    LOGI("load_whisper_funcs: init=%p full=%p nseg=%p text=%p free=%p params=%p sysinfo=%p",
         init_func, full_func, nseg_func, text_func, free_func, params_func, system_info_func);

    if (!init_func || !full_func || !nseg_func || !text_func || !free_func || !params_func) {
        LOGE("load_whisper_funcs: one or more required symbols not found");
        return 0;
    }
    LOGI("load_whisper_funcs: all required symbols resolved");
    return 1;
}

JNIEXPORT void JNICALL
Java_com_radio_app_whisper_WhisperBridge_setLibraryPathNative(JNIEnv* env, jobject thiz, jstring path) {
    const char* cpath = (*env)->GetStringUTFChars(env, path, NULL);
    if (cpath) {
        strncpy(whisper_so_path, cpath, sizeof(whisper_so_path) - 1);
        whisper_so_path[sizeof(whisper_so_path) - 1] = '\0';
        LOGI("setLibraryPathNative: path=\"%s\"", whisper_so_path);
        (*env)->ReleaseStringUTFChars(env, path, cpath);
    }
}

JNIEXPORT jlong JNICALL
Java_com_radio_app_whisper_WhisperBridge_initFromFile(JNIEnv* env, jobject thiz, jstring model_path) {
    register_crash_handlers();

    if (!load_whisper_funcs()) {
        LOGE("initFromFile: load_whisper_funcs failed");
        return 0;
    }
    const char* path = (*env)->GetStringUTFChars(env, model_path, NULL);

    if (system_info_func) {
        const char* sysinfo = system_info_func();
        LOGI("initFromFile: system info: %s", sysinfo ? sysinfo : "(null)");
    }

    struct whisper_context_params cparams;
    memset(&cparams, 0, sizeof(cparams));
    if (ctx_params_func) {
        cparams = ctx_params_func();
    }
    cparams.use_gpu = false;
    cparams.flash_attn = false;
    LOGI("initFromFile: loading model from \"%s\" (use_gpu=false)", path);
    struct whisper_context* ctx = init_func(path, cparams);
    (*env)->ReleaseStringUTFChars(env, model_path, path);
    LOGI("initFromFile: ctx=%p", ctx);
    return (jlong)(intptr_t)ctx;
}

JNIEXPORT jint JNICALL
Java_com_radio_app_whisper_WhisperBridge_full(JNIEnv* env, jobject thiz, jlong ctx_ptr, jfloatArray samples, jint n_samples) {
    if (!ctx_ptr || !full_func || !params_func) {
        LOGE("full: invalid state ctx=%lld full=%p params=%p", (long long)ctx_ptr, full_func, params_func);
        return -1;
    }
    struct whisper_context* ctx = (struct whisper_context*)(intptr_t)ctx_ptr;

    jfloat* sample_data = (jfloat*)malloc((size_t)n_samples * sizeof(jfloat));
    if (!sample_data) {
        LOGE("full: malloc failed for %d samples", n_samples);
        return -3;
    }
    (*env)->GetFloatArrayRegion(env, samples, 0, n_samples, sample_data);
    LOGI("full: copied %d samples to C buffer", n_samples);

    if (!ctx) { free(sample_data); return -1; }

    // Replace NaN/Inf with silence
    int nan_count = 0;
    for (int i = 0; i < n_samples; i++) {
        if (!isfinite(sample_data[i])) { sample_data[i] = 0.0f; nan_count++; }
    }
    if (nan_count > 0) LOGI("full: replaced %d NaN/Inf samples", nan_count);

    // [v2.2.5] SAFE params strategy for ABI compatibility with older libwhisper.so:
    // 1) Zero-init our entire local struct to eliminate uninitialized stack garbage
    // 2) Call whisper_full_default_params() which fills in the library's defaults
    //    (library writes <= sizeof(our struct) bytes because library is older)
    // 3) Override ONLY early, stable fields that exist in all whisper.cpp versions
    // 4) Do NOT write to recently-added fields (tdrz_enable, grammar, abort_callback,
    //    suppress_regex, etc.) - if library is older, those offsets are beyond the
    //    library's struct and harmlessly ignored; but if field ordering shifted they
    //    could corrupt adjacent fields. We leave them at library-chosen defaults.
    struct whisper_full_params params;
    memset(&params, 0, sizeof(params));
    params = params_func(WHISPER_SAMPLING_GREEDY);

    // Core parameters (safe in all versions):
    params.strategy         = WHISPER_SAMPLING_GREEDY;
    params.n_threads        = 2;        // 2 threads is safe and faster than 1 on mobile
    params.n_max_text_ctx   = 448;      // Conservative default (not 0, not 1500)
    params.offset_ms        = 0;
    params.duration_ms      = 0;

    // Boolean flags (early fields, safe to set):
    params.translate        = false;
    params.no_context       = true;     // Each chunk independent
    params.no_timestamps    = false;    // Need timestamps for subtitles
    params.single_segment   = false;    // [v2.2.5] Let Whisper segment naturally (true caused issues)
    params.print_special    = false;
    params.print_progress   = false;
    params.print_realtime   = false;
    params.print_timestamps = false;

    // Token/timestamp parameters (exist in all modern versions):
    params.token_timestamps = false;
    params.thold_pt         = 0.01f;
    params.thold_ptsum      = 0.01f;
    params.max_len          = 0;
    params.split_on_word    = false;
    params.max_tokens       = 0;        // [v2.2.5] 0 = no limit (64 was too restrictive for Chinese)

    // Audio context: 0 means auto-calculate from input length
    params.audio_ctx        = 0;

    // Language: force Chinese. This field has existed since early whisper.cpp.
    // detect_language=false: critical when forcing a specific language. If the library
    // is older and doesn't have this field yet, writing false (0) to that offset
    // will set an adjacent bool to false, which is a safe default.
    params.language         = "zh";
    params.detect_language  = false;

    // Suppress blank / non-speech tokens (exist in most versions, safe to set)
    params.suppress_blank              = true;
    params.suppress_non_speech_tokens  = true;

    // Decoding temperature
    params.temperature      = 0.0f;
    params.temperature_inc  = 0.0f;
    params.max_initial_ts   = 1.0f;
    params.length_penalty   = -1.0f;
    params.entropy_thold    = 2.40f;
    params.logprob_thold    = -1.0f;
    params.no_speech_thold  = 0.6f;

    // Prompt and callbacks: leave as NULL/0 (already zeroed by memset and defaults)
    params.initial_prompt   = NULL;
    params.prompt_tokens    = NULL;
    params.prompt_n_tokens  = 0;

    // Greedy search defaults
    params.greedy.best_of   = 1;
    params.beam_search.beam_size = 1;
    params.beam_search.patience = -1.0f;

    // [v2.2.5] EXPLICITLY do NOT set: tdrz_enable, suppress_regex, debug_mode,
    // grammar_rules, abort_callback, or any callback function pointers.
    // These were added in later whisper.cpp versions and may not exist in the v2.0.34 engine.
    // detect_language IS set (false) because writing 0 to a bool offset is safe even if
    // the field doesn't exist in the older library (just sets adjacent bool to false).
    // Leaving other newer fields at the library's defaults (or zero from memset) is safest.

    LOGI("full: BEFORE whisper_full n_samples=%d n_threads=%d language=%s single_segment=%d max_tokens=%d suppress_blank=%d",
         n_samples, params.n_threads, params.language ? params.language : "(null)",
         params.single_segment, params.max_tokens, params.suppress_blank);
    LOGI("full: audio_ctx=%d n_max_text_ctx=%d temperature=%f",
         params.audio_ctx, params.n_max_text_ctx, params.temperature);
    LOGI("full: samples[0]=%.6f [1]=%.6f [2]=%.6f last=%.6f",
         n_samples > 0 ? sample_data[0] : 0.0f,
         n_samples > 1 ? sample_data[1] : 0.0f,
         n_samples > 2 ? sample_data[2] : 0.0f,
         n_samples > 0 ? sample_data[n_samples-1] : 0.0f);

    int result = full_func(ctx, params, sample_data, n_samples);

    LOGI("full: AFTER whisper_full result=%d", result);
    free(sample_data);
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
