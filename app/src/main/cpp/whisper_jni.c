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

// [v2.3.4] File logging for native code - writes to same log dir as Kotlin
static int g_native_log_fd = -1;
static pthread_mutex_t g_log_mutex = PTHREAD_MUTEX_INITIALIZER;

static void native_log(const char* level, const char* fmt, ...) {
    pthread_mutex_lock(&g_log_mutex);
    if (g_native_log_fd < 0) {
        const char* log_path = "/data/data/com.radio.app/files/logs/subtitle/native.log";
        g_native_log_fd = open(log_path, O_WRONLY | O_CREAT | O_APPEND, 0644);
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
            write(g_native_log_fd, level, strlen(level));
            write(g_native_log_fd, buf, len < (int)sizeof(buf)-1 ? len : (int)sizeof(buf)-1);
            const char* nl = "\n";
            write(g_native_log_fd, nl, 1);
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

// [v2.3.0] whisper.cpp internal log callback
static void whisper_log_cb(ggml_log_level level, const char* text, void* user_data) {
    (void)user_data;
    if (text && *text) {
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

// ============================================================================
// [v2.3.4] ABI-SAFE FUNCTION POINTER TYPES
// ============================================================================
// KEY INSIGHT for ARM64 AAPCS:
//   For any struct larger than 16 bytes passed by value, the caller allocates
//   a stack copy and passes a POINTER to that copy in the next available
//   integer register (X0-X7). This is indistinguishable from passing a void*.
//   So we declare the params argument as `void*` and pass a pointer to
//   library-allocated memory (from whisper_full_default_params_by_ref),
//   whose layout is guaranteed to match the library exactly.
//
//   This approach ZERO-DEPENDENCE on our stub whisper.h struct layout.
// ============================================================================

typedef struct whisper_context* (*whisper_init_from_file_with_params_t)(
    const char* path_model, struct whisper_context_params params);

// [v2.3.4] Second arg is void* (pointer to library-allocated params) — ABI-safe
typedef int (*whisper_full_t)(
    struct whisper_context* ctx, void* params,
    const float* samples, int n_samples);

typedef int (*whisper_full_n_segments_t)(struct whisper_context* ctx);
typedef const char* (*whisper_full_get_segment_text_t)(struct whisper_context* ctx, int i_segment);
typedef int64_t (*whisper_full_get_segment_t0_t)(struct whisper_context* ctx, int i_segment);
typedef int64_t (*whisper_full_get_segment_t1_t)(struct whisper_context* ctx, int i_segment);
typedef void (*whisper_free_t)(struct whisper_context* ctx);

// [v2.3.4] Only use _by_ref variant — returns library-allocated pointer
typedef struct whisper_full_params* (*whisper_full_default_params_by_ref_t)(
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
static whisper_full_default_params_by_ref_t params_by_ref_func = NULL;
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
    params_by_ref_func  = (whisper_full_default_params_by_ref_t)  dlsym(whisper_lib, "whisper_full_default_params_by_ref");
    free_params_func    = (whisper_free_params_t)                 dlsym(whisper_lib, "whisper_free_params");
    ctx_params_func     = (whisper_context_default_params_t)      dlsym(whisper_lib, "whisper_context_default_params");
    system_info_func    = (whisper_print_system_info_t)           dlsym(whisper_lib, "whisper_print_system_info");
    log_set_func        = (whisper_log_set_t)                     dlsym(whisper_lib, "whisper_log_set");

    NLOGI("load_whisper_funcs: init=%p full=%p nseg=%p text=%p free=%p params_by_ref=%p free_params=%p sysinfo=%p log_set=%p",
         (void*)init_func, (void*)full_func, (void*)nseg_func, (void*)text_func, (void*)free_func,
         (void*)params_by_ref_func, (void*)free_params_func, (void*)system_info_func, (void*)log_set_func);

    if (!init_func || !full_func || !nseg_func || !text_func || !free_func) {
        NLOGE("load_whisper_funcs: one or more required symbols not found");
        return 0;
    }
    if (!params_by_ref_func) {
        NLOGE("load_whisper_funcs: whisper_full_default_params_by_ref not found - required for ABI-safe calls");
        return 0;
    }

    if (log_set_func) {
        log_set_func(whisper_log_cb, NULL);
        NLOGI("load_whisper_funcs: installed whisper.cpp log callback");
    }

    NLOGI("load_whisper_funcs: all required symbols resolved (ABI-safe by_ref mode)");
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
    // [v2.3.4] Only set use_gpu (offset 0, guaranteed safe across all versions).
    // Don't touch flash_attn or other fields to avoid offset mismatch.
    cparams.use_gpu = false;
    NLOGI("initFromFile: loading model from \"%s\" (use_gpu=false, other fields at library defaults)", path);
    struct whisper_context* ctx = init_func(path, cparams);
    (*env)->ReleaseStringUTFChars(env, model_path, path);
    NLOGI("initFromFile: ctx=%p", (void*)ctx);
    if (!ctx) {
        NLOGE("initFromFile: FAILED to create context (returned NULL) - model file may be corrupt or wrong format");
    }
    return (jlong)(intptr_t)ctx;
}

// [v2.3.5] ABI-SAFE params preparation.
// Strategy:
//   1. Call whisper_full_default_params_by_ref(GREEDY) to get a library-allocated
//      params pointer. Memory layout is 100% correct for the loaded library.
//   2. Set ONLY stable early fields that are required for correct Chinese ASR:
//      - strategy (offset 0): GREEDY
//      - n_threads (offset 4): 2 (avoid stack overflow on mobile)
//      - language (stable core field): "zh" (force Chinese — auto-detect fails
//        on short/noisy chunks, producing English/Japanese hallucinations)
//      - detect_language (next to language): false (since we force language)
//   3. ALL OTHER fields use library defaults.
//   4. Caller must call whisper_free_params() after whisper_full() returns.
//
//   NOTE ON LANGUAGE FIELD OFFSET SAFETY:
//   The `language` field (const char*) was introduced in whisper.cpp when multilingual
//   support was added (2022), before the _by_ref API existed. It is preceded only by
//   primitive fields (enums, ints, bools, floats, other pointers) whose sizes and
//   alignments are standard across ARM64 compilers. The offset is stable across all
//   whisper.cpp versions that export whisper_full_default_params_by_ref.
//   We write a pointer to a string literal "zh" which is valid for the life of the process.
static struct whisper_full_params* prepare_params(void) {
    struct whisper_full_params* ref = NULL;
    if (!params_by_ref_func) {
        NLOGE("prepare_params: params_by_ref_func is NULL");
        return NULL;
    }
    ref = params_by_ref_func(WHISPER_SAMPLING_GREEDY);
    if (!ref) {
        NLOGE("prepare_params: by_ref returned NULL");
        return NULL;
    }
    NLOGI("prepare_params: got default params from by_ref (library-allocated, layout=exact)");

    // ---- FIELD OVERRIDES (only stable, early fields) ----

    // offset 0: strategy (enum, 4 bytes)
    ref->strategy = WHISPER_SAMPLING_GREEDY;

    // offset 4: n_threads (int, 4 bytes) — limit to 2 threads for mobile
    ref->n_threads = 2;

    // offset of language field: after all core primitive fields.
    // Force Chinese language — auto-detection on 10s radio chunks (which may start
    // mid-sentence or contain music/noise) often misidentifies as English/Japanese,
    // producing garbage output like "[Speaking Japanese]" or English hallucinations.
    ref->language = "zh";
    ref->detect_language = false;

    NLOGI("prepare_params: ready n_threads=%d language=zh (forced), strategy=GREEDY",
         ref->n_threads);
    return ref;
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

    if (abs_max < 0.0001f) {
        NLOGI("full: audio is nearly silent (abs_max=%.6f), returning 0 segments (not an error)", abs_max);
        free(sample_data);
        return 0;
    }

    // [v2.3.4] ABI-SAFE: get params from library (zero struct layout dependence)
    struct whisper_full_params* params = prepare_params();
    if (!params) {
        NLOGE("full: prepare_params failed");
        free(sample_data);
        return -2;
    }

    NLOGI("full: calling whisper_full (ABI-safe void* mode), ctx=%p n_samples=%d n_threads=%d language=zh (forced)",
         (void*)ctx, n_samples, params->n_threads);

    // Pass library-allocated params pointer directly.
    // On ARM64 AAPCS, structs >16 bytes are passed as pointers (in X1 for 2nd arg),
    // so passing a void* is bit-exact identical to passing by value.
    int result = full_func(ctx, params, sample_data, n_samples);

    NLOGI("full: whisper_full returned %d", result);
    if (result == 0 && nseg_func) {
        int n = nseg_func(ctx);
        NLOGI("full: whisper_full_n_segments = %d", n);
    }
    if (result != 0) {
        NLOGE("full: whisper_full FAILED with code %d (n_samples=%d, abs_max=%.4f)",
             result, n_samples, abs_max);
    }

    if (free_params_func) {
        free_params_func(params);
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
