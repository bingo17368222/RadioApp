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
typedef int (*fp_chromaprint_get_raw_fingerprint)(ChromaprintContext* ctx, uint32_t** fingerprint, int* size);
typedef int (*fp_chromaprint_decode_fingerprint)(const char* encoded_fp, int encoded_size, uint32_t** fingerprint, int* size, int* algorithm, int base64);
typedef void (*fp_chromaprint_dealloc)(void* ptr);

static struct {
    void* handle;
    fp_chromaprint_new chromaprint_new;
    fp_chromaprint_free chromaprint_free;
    fp_chromaprint_start chromaprint_start;
    fp_chromaprint_feed chromaprint_feed;
    fp_chromaprint_finish chromaprint_finish;
    fp_chromaprint_get_fingerprint chromaprint_get_fingerprint;
    fp_chromaprint_get_raw_fingerprint chromaprint_get_raw_fingerprint;
    fp_chromaprint_decode_fingerprint chromaprint_decode_fingerprint;
    fp_chromaprint_dealloc chromaprint_dealloc;
    bool loaded;
} g_lib;

// v3.0.6: 外部下载的 libchromaprint.so 绝对路径，用于绕过 Android linker 命名空间限制
static std::string g_chromaprint_lib_path;

static bool load_chromaprint() {
    if (g_lib.loaded) return true;

    // v3.0.6: 优先尝试短名称加载（APK 内置或系统已加载）
    g_lib.handle = dlopen("libchromaprint.so", RTLD_NOW);
    if (!g_lib.handle && !g_chromaprint_lib_path.empty()) {
        LOGI("dlopen short name failed, trying absolute path: %s", g_chromaprint_lib_path.c_str());
        g_lib.handle = dlopen(g_chromaprint_lib_path.c_str(), RTLD_NOW);
    }
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
    g_lib.chromaprint_get_raw_fingerprint = (fp_chromaprint_get_raw_fingerprint)dlsym(g_lib.handle, "chromaprint_get_raw_fingerprint");
    g_lib.chromaprint_decode_fingerprint = (fp_chromaprint_decode_fingerprint)dlsym(g_lib.handle, "chromaprint_decode_fingerprint");
    g_lib.chromaprint_dealloc = (fp_chromaprint_dealloc)dlsym(g_lib.handle, "chromaprint_dealloc");

    if (!g_lib.chromaprint_new || !g_lib.chromaprint_free || !g_lib.chromaprint_start ||
        !g_lib.chromaprint_feed || !g_lib.chromaprint_finish || !g_lib.chromaprint_get_fingerprint ||
        !g_lib.chromaprint_get_raw_fingerprint) {
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
    // v3.0.7: Chromaprint API 返回 1 表示成功，0 表示失败
    if (g_lib.chromaprint_start(ctx, sample_rate, channels) == 1) {
        if (g_lib.chromaprint_feed(ctx, samples, sample_count) == 1) {
            if (g_lib.chromaprint_finish(ctx) == 1) {
                // v3.0.8: 返回逗号分隔的 raw fingerprint 整数，便于本地比较
                uint32_t* raw_fp = nullptr;
                int raw_size = 0;
                if (g_lib.chromaprint_get_raw_fingerprint(ctx, &raw_fp, &raw_size) == 1 && raw_fp && raw_size > 0) {
                    std::string out;
                    out.reserve(raw_size * 12);
                    for (int i = 0; i < raw_size; i++) {
                        if (i > 0) out += ",";
                        out += std::to_string(raw_fp[i]);
                    }
                    result = env->NewStringUTF(out.c_str());
                    if (g_lib.chromaprint_dealloc) {
                        g_lib.chromaprint_dealloc(raw_fp);
                    } else {
                        free(raw_fp);
                    }
                } else {
                    LOGE("chromaprint_get_raw_fingerprint failed");
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

// v3.0.6: 设置外部 libchromaprint.so 的绝对路径，供 dlopen 回退使用
extern "C" JNIEXPORT void JNICALL
Java_com_radio_app_utils_ChromaprintExtractor_nativeSetLibraryPath(
        JNIEnv* env,
        jclass /*clazz*/,
        jstring libPath) {
    const char* path = env->GetStringUTFChars(libPath, nullptr);
    if (path) {
        g_chromaprint_lib_path = path;
        LOGI("set chromaprint library path: %s", g_chromaprint_lib_path.c_str());
        env->ReleaseStringUTFChars(libPath, path);
    }
}

// v3.0.8: 将 base64 编码的 fingerprint 解码为逗号分隔的 raw fingerprint 整数
extern "C" JNIEXPORT jstring JNICALL
Java_com_radio_app_utils_ChromaprintExtractor_nativeDecodeFingerprint(
        JNIEnv* env,
        jclass /*clazz*/,
        jstring encodedFp) {
    if (!load_chromaprint()) {
        LOGE("load_chromaprint failed");
        return nullptr;
    }
    if (!g_lib.chromaprint_decode_fingerprint) {
        LOGE("chromaprint_decode_fingerprint not available");
        return nullptr;
    }

    const char* input = env->GetStringUTFChars(encodedFp, nullptr);
    if (!input) return nullptr;

    uint32_t* raw = nullptr;
    int size = 0;
    int algorithm = 0;
    jstring result = nullptr;

    if (g_lib.chromaprint_decode_fingerprint(input, strlen(input), &raw, &size, &algorithm, 1) == 1 && raw && size > 0) {
        std::string out;
        out.reserve(size * 12);
        for (int i = 0; i < size; i++) {
            if (i > 0) out += ",";
            out += std::to_string(raw[i]);
        }
        result = env->NewStringUTF(out.c_str());
        if (g_lib.chromaprint_dealloc) {
            g_lib.chromaprint_dealloc(raw);
        } else {
            free(raw);
        }
    } else {
        LOGE("chromaprint_decode_fingerprint failed");
    }

    env->ReleaseStringUTFChars(encodedFp, input);
    return result;
}
