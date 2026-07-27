#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <cstdlib>
#include <cstring>
#include <string>

#define LOG_TAG "chromaprint_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

typedef struct ChromaprintContext ChromaprintContext;

typedef ChromaprintContext* (*fp_chromaprint_new)(int algorithm);
typedef void (*fp_chromaprint_free)(ChromaprintContext* ctx);
typedef int (*fp_chromaprint_start)(ChromaprintContext* ctx, int sample_rate, int num_channels);
typedef int (*fp_chromaprint_feed)(ChromaprintContext* ctx, const int16_t* data, int size);
typedef int (*fp_chromaprint_finish)(ChromaprintContext* ctx);
typedef int (*fp_chromaprint_get_fingerprint)(ChromaprintContext* ctx, char** fingerprint);
typedef void (*fp_chromaprint_dealloc)(void* ptr);

static struct {
    void* handle;
    fp_chromaprint_new chromaprint_new;
    fp_chromaprint_free chromaprint_free;
    fp_chromaprint_start chromaprint_start;
    fp_chromaprint_feed chromaprint_feed;
    fp_chromaprint_finish chromaprint_finish;
    fp_chromaprint_get_fingerprint chromaprint_get_fingerprint;
    fp_chromaprint_dealloc chromaprint_dealloc;
    bool loaded;
} g_lib;

static bool load_chromaprint() {
    if (g_lib.loaded) return true;

    // 尝试通过 dlopen 加载已加载到进程中的 libchromaprint.so
    g_lib.handle = dlopen("libchromaprint.so", RTLD_NOW);
    if (!g_lib.handle) {
        LOGE("dlopen libchromaprint.so failed: %s", dlerror());
        return false;
    }

    g_lib.chromaprint_new = (fp_chromaprint_new)dlsym(g_lib.handle, "chromaprint_new");
    g_lib.chromaprint_free = (fp_chromaprint_free)dlsym(g_lib.handle, "chromaprint_free");
    g_lib.chromaprint_start = (fp_chromaprint_start)dlsym(g_lib.handle, "chromaprint_start");
    g_lib.chromaprint_feed = (fp_chromaprint_feed)dlsym(g_lib.handle, "chromaprint_feed");
    g_lib.chromaprint_finish = (fp_chromaprint_finish)dlsym(g_lib.handle, "chromaprint_finish");
    g_lib.chromaprint_get_fingerprint = (fp_chromaprint_get_fingerprint)dlsym(g_lib.handle, "chromaprint_get_fingerprint");
    g_lib.chromaprint_dealloc = (fp_chromaprint_dealloc)dlsym(g_lib.handle, "chromaprint_dealloc");

    if (!g_lib.chromaprint_new || !g_lib.chromaprint_free || !g_lib.chromaprint_start ||
        !g_lib.chromaprint_feed || !g_lib.chromaprint_finish || !g_lib.chromaprint_get_fingerprint) {
        LOGE("dlsym chromaprint functions failed");
        dlclose(g_lib.handle);
        g_lib.handle = nullptr;
        return false;
    }

    g_lib.loaded = true;
    LOGI("libchromaprint loaded and symbols resolved");
    return true;
}

static jstring extractFingerprint(JNIEnv* env, const int16_t* samples, int sample_count, int sample_rate, int channels) {
    if (!load_chromaprint()) {
        LOGE("load_chromaprint failed");
        return nullptr;
    }

    ChromaprintContext* ctx = g_lib.chromaprint_new(1); // CHROMAPRINT_ALGORITHM_DEFAULT
    if (!ctx) {
        LOGE("chromaprint_new failed");
        return nullptr;
    }

    jstring result = nullptr;
    if (g_lib.chromaprint_start(ctx, sample_rate, channels) == 0) {
        if (g_lib.chromaprint_feed(ctx, samples, sample_count) == 0) {
            if (g_lib.chromaprint_finish(ctx) == 0) {
                char* fp = nullptr;
                if (g_lib.chromaprint_get_fingerprint(ctx, &fp) == 0 && fp) {
                    result = env->NewStringUTF(fp);
                    if (g_lib.chromaprint_dealloc) {
                        g_lib.chromaprint_dealloc(fp);
                    } else {
                        free(fp);
                    }
                }
            } else {
                LOGE("chromaprint_finish failed");
            }
        } else {
            LOGE("chromaprint_feed failed");
        }
    } else {
        LOGE("chromaprint_start failed");
    }

    g_lib.chromaprint_free(ctx);
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_radio_app_utils_ChromaprintExtractor_nativeExtractFingerprint(
        JNIEnv* env,
        jclass /*clazz*/,
        jbyteArray pcmData,
        jint sampleRate,
        jint channels) {
    jsize len = env->GetArrayLength(pcmData);
    if (len <= 0) return nullptr;

    jbyte* bytes = env->GetByteArrayElements(pcmData, nullptr);
    int sample_count = len / 2;
    jstring result = extractFingerprint(env, reinterpret_cast<const int16_t*>(bytes), sample_count, sampleRate, channels);
    env->ReleaseByteArrayElements(pcmData, bytes, JNI_ABORT);
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_radio_app_utils_ChromaprintExtractor_nativeExtractFingerprintFromFile(
        JNIEnv* env,
        jclass /*clazz*/,
        jstring filePath,
        jint sampleRate,
        jint channels) {
    const char* path = env->GetStringUTFChars(filePath, nullptr);
    if (!path) return nullptr;

    FILE* f = fopen(path, "rb");
    if (!f) {
        env->ReleaseStringUTFChars(filePath, path);
        return nullptr;
    }

    fseek(f, 0, SEEK_END);
    long file_size = ftell(f);
    fseek(f, 0, SEEK_SET);

    jstring result = nullptr;
    if (file_size > 0) {
        int16_t* samples = (int16_t*)malloc(file_size);
        if (samples) {
            size_t read = fread(samples, 1, file_size, f);
            int sample_count = (int)(read / 2);
            result = extractFingerprint(env, samples, sample_count, sampleRate, channels);
            free(samples);
        }
    }

    fclose(f);
    env->ReleaseStringUTFChars(filePath, path);
    return result;
}
