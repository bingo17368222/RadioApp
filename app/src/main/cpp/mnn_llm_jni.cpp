// [v2.4.27] MNN-LLM JNI bridge - loads libllm.so dynamically and calls Llm API
// Uses dlopen/dlsym with raw C++ mangled names to avoid needing MNN headers at compile time
#include <jni.h>
#include <dlfcn.h>
#include <string>
#include <sstream>
#include <android/log.h>

#define TAG "MnnLlmJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

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
typedef void (*GenerateFunc)(MNN::Transformer::Llm*, int);
typedef bool (*StopedFunc)(MNN::Transformer::Llm*);

static void* g_libllm = nullptr;
static CreateLLMFunc g_createLLM = nullptr;
static DestroyFunc g_destroy = nullptr;
static LoadFunc g_load = nullptr;
static SetConfigFunc g_set_config = nullptr;
static ResponseStrFunc g_response = nullptr;
static GenerateFunc g_generate = nullptr;
static StopedFunc g_stoped = nullptr;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_radio_app_whisper_MnnLlmBridge_nativeInit(JNIEnv* env, jclass clazz) {
    if (g_libllm != nullptr) return JNI_TRUE;

    g_libllm = dlopen("libllm.so", RTLD_NOW | RTLD_LOCAL);
    if (!g_libllm) {
        LOGE("Failed to load libllm.so: %s", dlerror());
        return JNI_FALSE;
    }

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
    g_generate = (GenerateFunc)dlsym(g_libllm,
        "_ZN3MNN11Transformer3Llm8generateEi");
    g_stoped = (StopedFunc)dlsym(g_libllm,
        "_ZN3MNN11Transformer3Llm6stopedEv");

    if (!g_createLLM || !g_destroy || !g_load || !g_response) {
        LOGE("Missing required symbols: create=%p destroy=%p load=%p response=%p",
             g_createLLM, g_destroy, g_load, g_response);
        return JNI_FALSE;
    }

    LOGI("MNN LLM initialized: create=%p destroy=%p load=%p response=%p generate=%p stoped=%p",
         g_createLLM, g_destroy, g_load, g_response, g_generate, g_stoped);
    return JNI_TRUE;
}

JNIEXPORT jlong JNICALL
Java_com_radio_app_whisper_MnnLlmBridge_nativeCreateLlm(JNIEnv* env, jclass clazz, jstring configPath) {
    if (!g_createLLM) return 0;
    const char* path = env->GetStringUTFChars(configPath, nullptr);
    std::string configPathStr(path);
    env->ReleaseStringUTFChars(configPath, path);

    MNN::Transformer::Llm* llm = g_createLLM(configPathStr);
    if (!llm) {
        LOGE("createLLM returned null for config: %s", configPathStr.c_str());
        return 0;
    }
    LOGI("LLM created successfully");
    return reinterpret_cast<jlong>(llm);
}

JNIEXPORT jboolean JNICALL
Java_com_radio_app_whisper_MnnLlmBridge_nativeLoad(JNIEnv* env, jclass clazz, jlong ptr) {
    if (!g_load || ptr == 0) return JNI_FALSE;
    auto* llm = reinterpret_cast<MNN::Transformer::Llm*>(ptr);
    bool ok = g_load(llm);
    LOGI("load() returned %d", ok);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_radio_app_whisper_MnnLlmBridge_nativeGenerate(JNIEnv* env, jclass clazz, jlong ptr, jstring prompt, jint maxTokens) {
    if (!g_response || ptr == 0) return env->NewStringUTF("");
    auto* llm = reinterpret_cast<MNN::Transformer::Llm*>(ptr);

    const char* promptStr = env->GetStringUTFChars(prompt, nullptr);
    std::string promptCpp(promptStr);
    env->ReleaseStringUTFChars(prompt, promptStr);

    std::ostringstream oss;
    // response() does prefill + generation in one call.
    // max_new_tokens controls how many tokens to generate (-1 = until stop token)
    int max_new = (maxTokens > 0) ? (int)maxTokens : -1;
    g_response(llm, promptCpp, &oss, nullptr, max_new);

    std::string result = oss.str();
    LOGI("generate() returned %zu chars", result.size());
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT void JNICALL
Java_com_radio_app_whisper_MnnLlmBridge_nativeFree(JNIEnv* env, jclass clazz, jlong ptr) {
    if (g_destroy && ptr != 0) {
        auto* llm = reinterpret_cast<MNN::Transformer::Llm*>(ptr);
        g_destroy(llm);
        LOGI("LLM freed");
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
