// [v2.4.28] MNN-LLM JNI bridge - loads libllm.so dynamically and calls Llm API
// Uses dlopen/dlsym with raw C++ mangled names to avoid needing MNN headers at compile time
#include <jni.h>
#include <dlfcn.h>
#include <string>
#include <sstream>
#include <fstream>
#include <android/log.h>
#include <sys/stat.h>
#include <unistd.h>
#include <fcntl.h>
#include <stdarg.h>
#include <string.h>

#define TAG "MnnLlmJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// [v2.4.28] File-based logging for debugging MNN load failures
static int g_log_fd = -1;
static void mnn_log(const char* msg) {
    LOGI("%s", msg);
    if (g_log_fd < 0) {
        const char* paths[] = {
            "/storage/emulated/0/Android/data/com.radio.app/files/logs/subtitle/native.log",
            "/data/data/com.radio.app/files/logs/subtitle/native.log",
            nullptr
        };
        for (int i = 0; paths[i] != nullptr; i++) {
            g_log_fd = open(paths[i], O_WRONLY | O_CREAT | O_APPEND, 0644);
            if (g_log_fd >= 0) break;
        }
    }
    if (g_log_fd >= 0) {
        write(g_log_fd, "[MNN_JNI] ", 10);
        write(g_log_fd, msg, strlen(msg));
        write(g_log_fd, "\n", 1);
    }
}

static void mnn_logf(const char* fmt, ...) {
    char buf[1024];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);
    mnn_log(buf);
}

// Forward declaration - treat Llm as opaque type
namespace MNN { namespace Transformer {
class Llm;
}}

// Function pointer types matching C++ ABI (this pointer is first param for member functions)
typedef MNN::Transformer::Llm* (*CreateLLMFunc)(const std::string&);
typedef void (*DestroyFunc)(MNN::Transformer::Llm*);
typedef bool (*LoadFunc)(MNN::Transformer::Llm*);
typedef bool (*SetConfigFunc)(MNN::Transformer::Llm*, const std::string&);
typedef void (*ResponseStrFunc)(MNN::Transformer::Llm*, const std::string&, std::ostream*, const char*, int);

static void* g_libllm = nullptr;
static CreateLLMFunc g_createLLM = nullptr;
static DestroyFunc g_destroy = nullptr;
static LoadFunc g_load = nullptr;
static SetConfigFunc g_set_config = nullptr;
static ResponseStrFunc g_response = nullptr;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_radio_app_whisper_MnnLlmBridge_nativeInit(JNIEnv* env, jclass clazz) {
    if (g_libllm != nullptr) {
        mnn_log("nativeInit: already initialized");
        return JNI_TRUE;
    }

    mnn_log("nativeInit: starting...");

    // Try to load libllm.so
    g_libllm = dlopen("libllm.so", RTLD_NOW | RTLD_LOCAL);
    if (!g_libllm) {
        const char* err = dlerror();
        mnn_logf("nativeInit: dlopen(\"libllm.so\") FAILED: %s", err ? err : "unknown");
        // Try alternative: maybe need to load dependencies first
        mnn_log("nativeInit: trying to load dependencies first...");
        void* libMNN = dlopen("libMNN.so", RTLD_NOW | RTLD_LOCAL);
        if (!libMNN) {
            mnn_logf("nativeInit: dlopen(\"libMNN.so\") FAILED: %s", dlerror() ?: "unknown");
        } else {
            mnn_log("nativeInit: libMNN.so loaded OK");
        }
        void* libExpr = dlopen("libMNN_Express.so", RTLD_NOW | RTLD_LOCAL);
        if (!libExpr) {
            mnn_logf("nativeInit: dlopen(\"libMNN_Express.so\") FAILED: %s", dlerror() ?: "unknown");
        } else {
            mnn_log("nativeInit: libMNN_Express.so loaded OK");
        }
        // Retry libllm.so
        g_libllm = dlopen("libllm.so", RTLD_NOW | RTLD_LOCAL);
        if (!g_libllm) {
            mnn_logf("nativeInit: retry dlopen(\"libllm.so\") FAILED: %s", dlerror() ?: "unknown");
            return JNI_FALSE;
        }
    }
    mnn_logf("nativeInit: dlopen(\"libllm.so\") OK, handle=%p", g_libllm);

    // Resolve symbols
    g_createLLM = (CreateLLMFunc)dlsym(g_libllm,
        "_ZN3MNN11Transformer3Llm9createLLMERKNSt6__ndk112basic_stringIcNS2_11char_traitsIcEENS2_9allocatorIcEEEE");
    g_destroy = (DestroyFunc)dlsym(g_libllm,
        "_ZN3MNN11Transformer3Llm7destroyEPS1_");
    g_load = (LoadFunc)dlsym(g_libllm,
        "_ZN3MNN11Transformer3Llm4loadEv");
    g_set_config = (SetConfigFunc)dlsym(g_libllm,
        "_ZN3MNN11Transformer3Llm10set_configERKNSt6__ndk112basic_stringIcNS2_11char_traitsIcEENS2_9allocatorIcEEEE");
    g_response = (ResponseStrFunc)dlsym(g_libllm,
        "_ZN3MNN11Transformer3Llm8responseERKNSt6__ndk112basic_stringIcNS2_11char_traitsIcEENS2_9allocatorIcEEEEPNS2_13basic_ostreamIcS5_EEPKci");

    mnn_logf("nativeInit: symbol resolution: create=%p destroy=%p load=%p config=%p response=%p",
             g_createLLM, g_destroy, g_load, g_set_config, g_response);

    if (!g_createLLM) {
        mnn_log("nativeInit: FAILED - createLLM symbol not found");
        return JNI_FALSE;
    }
    if (!g_destroy) {
        mnn_log("nativeInit: FAILED - destroy symbol not found");
        return JNI_FALSE;
    }
    if (!g_load) {
        mnn_log("nativeInit: FAILED - load symbol not found");
        return JNI_FALSE;
    }
    if (!g_response) {
        mnn_log("nativeInit: FAILED - response symbol not found");
        return JNI_FALSE;
    }

    mnn_log("nativeInit: all symbols resolved OK");
    return JNI_TRUE;
}

JNIEXPORT jlong JNICALL
Java_com_radio_app_whisper_MnnLlmBridge_nativeCreateLlm(JNIEnv* env, jclass clazz, jstring configPath) {
    if (!g_createLLM) {
        mnn_log("nativeCreateLlm: g_createLLM is null");
        return 0;
    }
    const char* path = env->GetStringUTFChars(configPath, nullptr);
    std::string configPathStr(path);
    env->ReleaseStringUTFChars(configPath, path);

    mnn_logf("nativeCreateLlm: configPath=%s", configPathStr.c_str());

    // Check if the config file exists
    struct stat st;
    if (stat(configPathStr.c_str(), &st) != 0) {
        mnn_logf("nativeCreateLlm: config file does NOT exist: %s", configPathStr.c_str());
        return 0;
    }
    mnn_logf("nativeCreateLlm: config file exists, size=%ld bytes", st.st_size);

    MNN::Transformer::Llm* llm = g_createLLM(configPathStr);
    if (!llm) {
        mnn_log("nativeCreateLlm: createLLM returned null!");
        return 0;
    }
    mnn_logf("nativeCreateLlm: LLM created OK, ptr=%p", llm);
    return reinterpret_cast<jlong>(llm);
}

JNIEXPORT jboolean JNICALL
Java_com_radio_app_whisper_MnnLlmBridge_nativeLoad(JNIEnv* env, jclass clazz, jlong ptr) {
    if (!g_load || ptr == 0) {
        mnn_logf("nativeLoad: g_load=%p ptr=%lld", g_load, (long long)ptr);
        return JNI_FALSE;
    }
    auto* llm = reinterpret_cast<MNN::Transformer::Llm*>(ptr);
    mnn_log("nativeLoad: calling load()...");
    bool ok = g_load(llm);
    mnn_logf("nativeLoad: load() returned %d", ok);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_radio_app_whisper_MnnLlmBridge_nativeGenerate(JNIEnv* env, jclass clazz, jlong ptr, jstring prompt, jint maxTokens) {
    if (!g_response || ptr == 0) {
        mnn_logf("nativeGenerate: g_response=%p ptr=%lld", g_response, (long long)ptr);
        return env->NewStringUTF("");
    }
    auto* llm = reinterpret_cast<MNN::Transformer::Llm*>(ptr);

    const char* promptStr = env->GetStringUTFChars(prompt, nullptr);
    std::string promptCpp(promptStr);
    env->ReleaseStringUTFChars(prompt, promptStr);

    mnn_logf("nativeGenerate: prompt length=%zu, maxTokens=%d", promptCpp.size(), maxTokens);

    std::ostringstream oss;
    // response() does prefill + generation in one call.
    // max_new_tokens controls how many tokens to generate (-1 = until stop token)
    int max_new = (maxTokens > 0) ? (int)maxTokens : -1;
    g_response(llm, promptCpp, &oss, nullptr, max_new);

    std::string result = oss.str();
    mnn_logf("nativeGenerate: response length=%zu", result.size());
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT void JNICALL
Java_com_radio_app_whisper_MnnLlmBridge_nativeFree(JNIEnv* env, jclass clazz, jlong ptr) {
    if (g_destroy && ptr != 0) {
        auto* llm = reinterpret_cast<MNN::Transformer::Llm*>(ptr);
        g_destroy(llm);
        mnn_log("nativeFree: LLM freed");
    }
}

JNIEXPORT jboolean JNICALL
Java_com_radio_app_whisper_MnnLlmBridge_nativeSetConfig(JNIEnv* env, jclass clazz, jlong ptr, jstring configJson) {
    if (!g_set_config || ptr == 0) return JNI_FALSE;
    auto* llm = reinterpret_cast<MNN::Transformer::Llm*>(ptr);
    const char* cfg = env->GetStringUTFChars(configJson, nullptr);
    std::string cfgStr(cfg);
    env->ReleaseStringUTFChars(configJson, cfg);
    bool ok = g_set_config(llm, cfgStr);
    return ok ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
