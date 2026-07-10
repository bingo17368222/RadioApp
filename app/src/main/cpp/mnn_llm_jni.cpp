// [v2.4.31] MNN-LLM JNI bridge - loads libllm.so dynamically from a given directory
// Uses dlopen/dlsym with raw C++ mangled names to avoid needing MNN headers at compile time
// v2.4.31: MNN .so files are NO LONGER in the APK - they are downloaded with the model
//          nativeInit accepts a directory path and uses dlopen with full paths
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

// File-based logging for debugging MNN load failures
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

// Function pointer types matching C++ ABI
typedef MNN::Transformer::Llm* (*CreateLLMFunc)(const std::string&);
typedef void (*DestroyFunc)(MNN::Transformer::Llm*);
typedef bool (*LoadFunc)(MNN::Transformer::Llm*);
typedef bool (*SetConfigFunc)(MNN::Transformer::Llm*, const std::string&);
typedef void (*ResponseStrFunc)(MNN::Transformer::Llm*, const std::string&, std::ostream*, const char*, int);

// Keep handles to all loaded libraries so they stay resident
static void* g_libMNN = nullptr;
static void* g_libMNN_Express = nullptr;
static void* g_libMNN_Vulkan = nullptr;
static void* g_libMNN_CL = nullptr;
static void* g_libMNNOpenCV = nullptr;
static void* g_libMNNAudio = nullptr;
static void* g_libmnncore = nullptr;
static void* g_libllm = nullptr;

static CreateLLMFunc g_createLLM = nullptr;
static DestroyFunc g_destroy = nullptr;
static LoadFunc g_load = nullptr;
static SetConfigFunc g_set_config = nullptr;
static ResponseStrFunc g_response = nullptr;

// Helper: dlopen from a specific directory with full path
static void* dlopen_from_dir(const char* dir, const char* libname) {
    std::string fullpath = std::string(dir) + "/" + libname;
    mnn_logf("dlopen_from_dir: %s", fullpath.c_str());
    // Check file exists
    struct stat st;
    if (stat(fullpath.c_str(), &st) != 0) {
        mnn_logf("dlopen_from_dir: file NOT FOUND: %s", fullpath.c_str());
        return nullptr;
    }
    mnn_logf("dlopen_from_dir: file exists, size=%ld bytes", st.st_size);
    void* handle = dlopen(fullpath.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!handle) {
        mnn_logf("dlopen_from_dir: FAILED: %s", dlerror() ?: "unknown");
    } else {
        mnn_logf("dlopen_from_dir: OK, handle=%p", handle);
    }
    return handle;
}

extern "C" {

// v2.4.31: nativeInit now accepts a libDir parameter (directory where MNN .so files are downloaded)
// Falls back to system loadLibrary if libDir is empty (for backward compatibility)
JNIEXPORT jboolean JNICALL
Java_com_radio_app_whisper_MnnLlmBridge_nativeInit(JNIEnv* env, jclass clazz, jstring libDir) {
    if (g_libllm != nullptr) {
        mnn_log("nativeInit: already initialized");
        return JNI_TRUE;
    }

    mnn_log("nativeInit: starting...");

    // Get libDir parameter
    const char* libDirC = nullptr;
    std::string libDirStr;
    if (libDir != nullptr) {
        libDirC = env->GetStringUTFChars(libDir, nullptr);
        libDirStr = libDirC ? libDirC : "";
        env->ReleaseStringUTFChars(libDir, libDirC);
    }
    mnn_logf("nativeInit: libDir=%s", libDirStr.c_str());

    if (!libDirStr.empty()) {
        // v2.4.31: Load from downloaded directory
        mnn_log("nativeInit: loading from downloaded directory...");

        // Load in dependency order
        g_libMNN = dlopen_from_dir(libDirStr.c_str(), "libMNN.so");
        if (!g_libMNN) {
            mnn_log("nativeInit: FAILED - cannot load libMNN.so");
            return JNI_FALSE;
        }

        g_libMNN_Express = dlopen_from_dir(libDirStr.c_str(), "libMNN_Express.so");
        if (!g_libMNN_Express) {
            mnn_log("nativeInit: WARNING - cannot load libMNN_Express.so (may not be needed)");
        }

        g_libMNN_Vulkan = dlopen_from_dir(libDirStr.c_str(), "libMNN_Vulkan.so");
        if (!g_libMNN_Vulkan) {
            mnn_log("nativeInit: WARNING - cannot load libMNN_Vulkan.so (may not be needed)");
        }

        g_libMNN_CL = dlopen_from_dir(libDirStr.c_str(), "libMNN_CL.so");
        if (!g_libMNN_CL) {
            mnn_log("nativeInit: WARNING - cannot load libMNN_CL.so (may not be needed)");
        }

        g_libMNNOpenCV = dlopen_from_dir(libDirStr.c_str(), "libMNNOpenCV.so");
        if (!g_libMNNOpenCV) {
            mnn_log("nativeInit: WARNING - cannot load libMNNOpenCV.so (may not be needed)");
        }

        g_libMNNAudio = dlopen_from_dir(libDirStr.c_str(), "libMNNAudio.so");
        if (!g_libMNNAudio) {
            mnn_log("nativeInit: WARNING - cannot load libMNNAudio.so (may not be needed)");
        }

        g_libmnncore = dlopen_from_dir(libDirStr.c_str(), "libmnncore.so");
        if (!g_libmnncore) {
            mnn_log("nativeInit: WARNING - cannot load libmnncore.so (may not be needed)");
        }

        // Now load libllm.so (depends on all above)
        g_libllm = dlopen_from_dir(libDirStr.c_str(), "libllm.so");
        if (!g_libllm) {
            mnn_log("nativeInit: FAILED - cannot load libllm.so");
            return JNI_FALSE;
        }
    } else {
        // Fallback: try system loadLibrary path (for backward compatibility)
        mnn_log("nativeInit: no libDir, trying system path...");
        g_libMNN = dlopen("libMNN.so", RTLD_NOW | RTLD_LOCAL);
        g_libMNN_Express = dlopen("libMNN_Express.so", RTLD_NOW | RTLD_LOCAL);
        g_libllm = dlopen("libllm.so", RTLD_NOW | RTLD_LOCAL);
        if (!g_libllm) {
            mnn_logf("nativeInit: dlopen(\"libllm.so\") FAILED: %s", dlerror() ?: "unknown");
            return JNI_FALSE;
        }
    }

    mnn_logf("nativeInit: libllm.so loaded, handle=%p", g_libllm);

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

    if (!g_createLLM || !g_destroy || !g_load || !g_response) {
        mnn_log("nativeInit: FAILED - one or more symbols not found");
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
    std::string rawPrompt(promptStr);
    env->ReleaseStringUTFChars(prompt, promptStr);

    // v2.4.42: Wrap prompt in Qwen1.5-Chat chat template.
    // Without the chat template, the model falls into a degenerate repetition
    // loop outputting garbage tokens like "集结集结集结漏漏...".
    // Qwen1.5-Chat REQUIRES the <|im_start|>/<|im_end|> format.
    std::string promptCpp = "<|im_start|>system\nYou are a helpful assistant.<|im_end|>\n<|im_start|>user\n"
                           + rawPrompt + "<|im_end|>\n<|im_start|>assistant\n";

    mnn_logf("nativeGenerate: prompt length=%zu (wrapped), maxTokens=%d", promptCpp.size(), maxTokens);

    std::ostringstream oss;
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
