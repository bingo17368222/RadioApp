#include <jni.h>
#include <string.h>
#include <stdlib.h>

// Forward declarations from whisper.h
struct whisper_context;
struct whisper_full_params;

typedef struct whisper_context* (*whisper_init_from_file_with_params_t)(const char*, void*);
typedef int (*whisper_full_t)(struct whisper_context*, struct whisper_full_params, const float*, int);
typedef int (*whisper_full_n_segments_t)(struct whisper_context*);
typedef const char* (*whisper_full_get_segment_text_t)(struct whisper_context*, int);
typedef int64_t (*whisper_full_get_segment_t0_t)(struct whisper_context*, int);
typedef int64_t (*whisper_full_get_segment_t1_t)(struct whisper_context*, int);
typedef void (*whisper_free_t)(struct whisper_context*);
typedef struct whisper_full_params (*whisper_full_default_params_t)(int);

static void* whisper_lib = NULL;
static whisper_init_from_file_with_params_t init_func = NULL;
static whisper_full_t full_func = NULL;
static whisper_full_n_segments_t nseg_func = NULL;
static whisper_full_get_segment_text_t text_func = NULL;
static whisper_full_get_segment_t0_t t0_func = NULL;
static whisper_full_get_segment_t1_t t1_func = NULL;
static whisper_free_t free_func = NULL;
static whisper_full_default_params_t params_func = NULL;

// Load function pointers from libwhisper.so using dlopen
static int load_whisper_funcs() {
    if (init_func != NULL) return 1;
    void* dlopen(const char*, int);
    void* dlsym(void*, const char*);
    whisper_lib = dlopen("libwhisper.so", 2); // RTLD_NOW
    if (!whisper_lib) return 0;
    init_func = (whisper_init_from_file_with_params_t)dlsym(whisper_lib, "whisper_init_from_file_with_params");
    full_func = (whisper_full_t)dlsym(whisper_lib, "whisper_full");
    nseg_func = (whisper_full_n_segments_t)dlsym(whisper_lib, "whisper_full_n_segments");
    text_func = (whisper_full_get_segment_text_t)dlsym(whisper_lib, "whisper_full_get_segment_text");
    t0_func = (whisper_full_get_segment_t0_t)dlsym(whisper_lib, "whisper_full_get_segment_t0");
    t1_func = (whisper_full_get_segment_t1_t)dlsym(whisper_lib, "whisper_full_get_segment_t1");
    free_func = (whisper_free_t)dlsym(whisper_lib, "whisper_free");
    params_func = (whisper_full_default_params_t)dlsym(whisper_lib, "whisper_full_default_params");
    return (init_func && full_func && nseg_func && text_func && free_func) ? 1 : 0;
}

JNIEXPORT jlong JNICALL
Java_com_radio_app_whisper_WhisperBridge_initFromFile(JNIEnv* env, jobject thiz, jstring model_path) {
    if (!load_whisper_funcs()) return 0;
    const char* path = (*env)->GetStringUTFChars(env, model_path, NULL);
    // Use NULL for params (default)
    struct whisper_context* ctx = init_func(path, NULL);
    (*env)->ReleaseStringUTFChars(env, model_path, path);
    return (jlong)(long)ctx;
}

JNIEXPORT jint JNICALL
Java_com_radio_app_whisper_WhisperBridge_full(JNIEnv* env, jobject thiz, jlong ctx_ptr, jfloatArray samples, jint n_samples) {
    if (!ctx_ptr || !full_func || !params_func) return -1;
    struct whisper_context* ctx = (struct whisper_context*)(long)ctx_ptr;
    jfloat* sample_data = (*env)->GetFloatArrayElements(env, samples, NULL);
    // WHISPER_SAMPLING_GREEDY = 0
    struct whisper_full_params params = params_func(0);
    // Set basic params
    params.print_realtime = 0;
    params.print_progress = 0;
    params.print_timestamps = 0;
    params.translate = 0;
    int result = full_func(ctx, params, sample_data, n_samples);
    (*env)->ReleaseFloatArrayElements(env, samples, sample_data, JNI_ABORT);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_radio_app_whisper_WhisperBridge_fullNSegments(JNIEnv* env, jobject thiz, jlong ctx_ptr) {
    if (!ctx_ptr || !nseg_func) return 0;
    return nseg_func((struct whisper_context*)(long)ctx_ptr);
}

JNIEXPORT jstring JNICALL
Java_com_radio_app_whisper_WhisperBridge_fullGetSegmentText(JNIEnv* env, jobject thiz, jlong ctx_ptr, jint segment_index) {
    if (!ctx_ptr || !text_func) return (*env)->NewStringUTF(env, "");
    const char* text = text_func((struct whisper_context*)(long)ctx_ptr, segment_index);
    return (*env)->NewStringUTF(env, text ? text : "");
}

JNIEXPORT jlong JNICALL
Java_com_radio_app_whisper_WhisperBridge_fullGetSegmentT0(JNIEnv* env, jobject thiz, jlong ctx_ptr, jint segment_index) {
    if (!ctx_ptr || !t0_func) return 0;
    return (jlong)t0_func((struct whisper_context*)(long)ctx_ptr, segment_index);
}

JNIEXPORT jlong JNICALL
Java_com_radio_app_whisper_WhisperBridge_fullGetSegmentT1(JNIEnv* env, jobject thiz, jlong ctx_ptr, jint segment_index) {
    if (!ctx_ptr || !t1_func) return 0;
    return (jlong)t1_func((struct whisper_context*)(long)ctx_ptr, segment_index);
}

JNIEXPORT void JNICALL
Java_com_radio_app_whisper_WhisperBridge_free(JNIEnv* env, jobject thiz, jlong ctx_ptr) {
    if (!ctx_ptr || !free_func) return;
    free_func((struct whisper_context*)(long)ctx_ptr);
}
