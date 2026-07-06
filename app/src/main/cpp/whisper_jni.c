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
typedef struct whisper_full_params* (*whisper_full_default_params_by_ref_t)(
    enum whisper_sampling_strategy strategy);
typedef void (*whisper_free_params_t)(struct whisper_full_params* params);
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
static whisper_full_default_params_by_ref_t params_by_ref_func = NULL;
static whisper_free_params_t free_params_func = NULL;
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

    init_func           = (whisper_init_from_file_with_params_t)  dlsym(whisper_lib, "whisper_init_from_file_with_params");
    full_func           = (whisper_full_t)                        dlsym(whisper_lib, "whisper_full");
    nseg_func           = (whisper_full_n_segments_t)             dlsym(whisper_lib, "whisper_full_n_segments");
    text_func           = (whisper_full_get_segment_text_t)       dlsym(whisper_lib, "whisper_full_get_segment_text");
    t0_func             = (whisper_full_get_segment_t0_t)         dlsym(whisper_lib, "whisper_full_get_segment_t0");
    t1_func             = (whisper_full_get_segment_t1_t)         dlsym(whisper_lib, "whisper_full_get_segment_t1");
    free_func           = (whisper_free_t)                        dlsym(whisper_lib, "whisper_free");
    params_func         = (whisper_full_default_params_t)         dlsym(whisper_lib, "whisper_full_default_params");
    params_by_ref_func  = (whisper_full_default_params_by_ref_t)  dlsym(whisper_lib, "whisper_full_default_params_by_ref");
    free_params_func    = (whisper_free_params_t)                 dlsym(whisper_lib, "whisper_free_params");
    ctx_params_func     = (whisper_context_default_params_t)      dlsym(whisper_lib, "whisper_context_default_params");
    system_info_func    = (whisper_print_system_info_t)           dlsym(whisper_lib, "whisper_print_system_info");

    LOGI("load_whisper_funcs: init=%p full=%p nseg=%p text=%p free=%p params=%p params_by_ref=%p free_params=%p sysinfo=%p",
         init_func, full_func, nseg_func, text_func, free_func, params_func, params_by_ref_func, free_params_func, system_info_func);

    if (!init_func || !full_func || !nseg_func || !text_func || !free_func) {
        LOGE("load_whisper_funcs: one or more required symbols not found");
        return 0;
    }
    // Need either params_func or params_by_ref_func to get defaults
    if (!params_func && !params_by_ref_func) {
        LOGE("load_whisper_funcs: neither whisper_full_default_params nor whisper_full_default_params_by_ref found");
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
    // flash_attn may not exist in older libwhisper.so; setting false (0) at that offset is harmless
    // since bool is 1 byte and older versions treat that padding as reserved
    cparams.flash_attn = false;
    LOGI("initFromFile: loading model from \"%s\" (use_gpu=false)", path);
    struct whisper_context* ctx = init_func(path, cparams);
    (*env)->ReleaseStringUTFChars(env, model_path, path);
    LOGI("initFromFile: ctx=%p", ctx);
    return (jlong)(intptr_t)ctx;
}

JNIEXPORT jint JNICALL
Java_com_radio_app_whisper_WhisperBridge_full(JNIEnv* env, jobject thiz, jlong ctx_ptr, jfloatArray samples, jint n_samples) {
    if (!ctx_ptr || !full_func) {
        LOGE("full: invalid state ctx=%lld full=%p", (long long)ctx_ptr, full_func);
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

    // [v2.2.9] SAFE params strategy to avoid ABI mismatch crashes:
    // 1) Zero-init our local struct to eliminate uninitialized stack garbage
    // 2) Prefer whisper_full_default_params_by_ref() (returns pointer to library-allocated
    //    memory) to avoid struct-return ABI size mismatch. Copy defaults via memcpy.
    // 3) Fall back to whisper_full_default_params() (by value) if _by_ref unavailable.
    // 4) Override ONLY the earliest, most stable fields that have existed since
    //    whisper.cpp's first versions. Leave newer fields at library defaults.
    // 5) Do NOT write to recently-added fields (tdrz_enable, grammar, abort_callback,
    //    suppress_regex, etc.) - if library is older those offsets could be wrong.

    // Use a generously-sized local buffer to accommodate any library version's struct
    struct whisper_full_params params;
    memset(&params, 0, sizeof(params));

    int use_by_ref = (params_by_ref_func != NULL);
    if (use_by_ref) {
        LOGI("full: using whisper_full_default_params_by_ref() for ABI safety");
        struct whisper_full_params* ref_params = params_by_ref_func(WHISPER_SAMPLING_GREEDY);
        if (ref_params) {
            memcpy(&params, ref_params, sizeof(params));
            if (free_params_func) {
                free_params_func(ref_params);
            }
            LOGI("full: copied defaults from by_ref, strategy=%d", (int)params.strategy);
        } else {
            LOGE("full: by_ref returned NULL, falling back to by-value");
            use_by_ref = 0;
        }
    }

    if (!use_by_ref && params_func) {
        LOGI("full: using whisper_full_default_params() by value");
        params = params_func(WHISPER_SAMPLING_GREEDY);
        LOGI("full: got defaults by value, strategy=%d", (int)params.strategy);
    }

    // [v2.2.9] Set ONLY core, stable parameters that have existed in whisper_full_params
    // since the earliest whisper.cpp versions. These field offsets are stable.
    params.strategy         = WHISPER_SAMPLING_GREEDY;
    params.n_threads        = 2;        // Safe: early field, 2 threads for mobile
    params.translate        = false;
    params.no_context       = true;     // Each chunk independent (streaming)
    params.no_timestamps    = false;
    params.print_special    = false;
    params.print_progress   = false;
    params.print_realtime   = false;
    params.print_timestamps = false;

    // Language: force Chinese. "zh" and detect_language=false are early fields.
    params.language         = "zh";
    params.detect_language  = false;

    // Suppress non-speech (stable bool fields)
    params.suppress_blank              = true;
    params.suppress_non_speech_tokens  = true;

    // Temperature (stable float fields)
    params.temperature      = 0.0f;

    // Greedy search (stable nested struct, early in whisper.cpp history)
    params.greedy.best_of   = 1;
    params.beam_search.beam_size = 1;

    // [v2.2.9] EXPLICITLY DO NOT SET these fields that may not exist in older libwhisper.so:
    // - flash_attn is in context_params, not full_params
    // - tdrz_enable (added later)
    // - suppress_regex (added later)
    // - debug_mode (experimental, may shift)
    // - audio_ctx: leave at library default (0=auto). Setting 0 explicitly is safe.
    // - single_segment: leave at library default (false is typical)
    // - n_max_text_ctx, offset_ms, duration_ms: leave at library defaults
    // - token_timestamps, thold_pt, thold_ptsum, max_len, split_on_word, max_tokens:
    //   leave at library defaults to avoid offset issues
    // - All callback pointers: leave as NULL (library default from _by_ref or zero from memset)
    // - grammar_rules, n_grammar_rules, i_start_rule, grammar_penalty: leave at defaults

    // Ensure all callbacks and pointers are NULL (defense in depth)
    params.initial_prompt   = NULL;
    params.prompt_tokens    = NULL;
    params.prompt_n_tokens  = 0;
    params.new_segment_callback           = NULL;
    params.new_segment_callback_user_data = NULL;
    params.progress_callback              = NULL;
    params.progress_callback_user_data    = NULL;
    params.encoder_begin_callback         = NULL;
    params.encoder_begin_callback_user_data = NULL;
    params.abort_callback                 = NULL;
    params.abort_callback_user_data       = NULL;
    params.logits_filter_callback         = NULL;
    params.logits_filter_callback_user_data = NULL;
    params.grammar_rules                  = NULL;
    params.n_grammar_rules                = 0;
    params.i_start_rule                   = 0;
    params.grammar_penalty                = 0.0f;
    params.suppress_regex                 = NULL;

    LOGI("full: BEFORE whisper_full n_samples=%d n_threads=%d language=%s strategy=%d",
         n_samples, params.n_threads, params.language ? params.language : "(null)", (int)params.strategy);
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
