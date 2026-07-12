// [v2.4.72] MNN-LLM JNI bridge - loads libllm.so dynamically from a given directory
// Uses dlopen/dlsym with raw C++ mangled names to avoid needing MNN headers at compile time
// v2.4.72: CRITICAL FIX - bypass MNN template system entirely.
//   The old libllm.so likely lacks LLM_USE_JINJA (Jinja2 not compiled in) and
//   special_tokens_cache_ (tokenizer doesn't recognize <|im_start|> in raw text).
//   Fix: resolve tokenizer_encode() and response(vector<int>) symbols,
//   manually construct token IDs with hardcoded special token IDs (151644/151645),
//   and call response(vector<int>) directly. This completely bypasses the broken
//   template system and ensures correct ChatML token sequences reach the model.
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
#include <set>
#include <algorithm>
#include <vector>

// v2.4.53: Forward declaration
static bool isGarbageResponse(const std::string& s);

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
    char buf[2048];
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
typedef void (*ResetFunc)(MNN::Transformer::Llm*);

// v2.4.72: tokenizer_encode and response(vector<int>) function pointer types.
// On ARM64, std::vector<int> returned by value uses sret (hidden pointer in x8).
// The function pointer type must match the ABI: void(TokenizeOutput*, Llm*, const string&).
// But simpler: we use a wrapper that takes the same args and returns vector by value.
// The compiler will handle the ABI correctly if we match the declaration.
typedef std::vector<int> (*TokenizeFunc)(MNN::Transformer::Llm*, const std::string&);
typedef void (*ResponseVecFunc)(MNN::Transformer::Llm*, const std::vector<int>&, std::ostream*, const char*, int);
// v2.4.79: apply_chat_template(string) -> string
typedef std::string (*ApplyChatTemplateFunc)(const MNN::Transformer::Llm*, const std::string&);

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
static ResetFunc g_reset = nullptr;
static TokenizeFunc g_tokenize = nullptr;       // v2.4.72: tokenizer_encode
static ResponseVecFunc g_response_vec = nullptr; // v2.4.72: response(vector<int>)
static ApplyChatTemplateFunc g_apply_chat_template = nullptr; // v2.4.79: apply_chat_template(string)

// v2.4.72: Qwen2/Qwen1.5 ChatML special token IDs
// These are standard for all Qwen models using ChatML format
static const int CHATML_IM_START = 151644;  // <|im_start|>
static const int CHATML_IM_END = 151645;    // <|im_end|>

// Helper: dlopen from a specific directory with full path
static void* dlopen_from_dir(const char* dir, const char* libname) {
    std::string fullpath = std::string(dir) + "/" + libname;
    mnn_logf("dlopen_from_dir: %s", fullpath.c_str());
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

// v2.4.72: Try to resolve a symbol from multiple possible mangled names
template<typename FuncPtr>
static FuncPtr try_resolve_symbols(void* lib, const char* names[], int count) {
    for (int i = 0; i < count; i++) {
        void* sym = dlsym(lib, names[i]);
        if (sym) {
            mnn_logf("try_resolve: found symbol [%d]: %s at %p", i, names[i], sym);
            return reinterpret_cast<FuncPtr>(sym);
        }
    }
    mnn_logf("try_resolve: all %d symbol names failed", count);
    return nullptr;
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_radio_app_whisper_MnnLlmBridge_nativeInit(JNIEnv* env, jclass clazz, jstring libDir) {
    mnn_log("mnn_llm_jni COMPILE MARKER: v2.4.79 compiled at " __DATE__ " " __TIME__);

    if (g_libllm != nullptr) {
        mnn_log("nativeInit: already initialized");
        return JNI_TRUE;
    }

    mnn_log("nativeInit: starting...");

    const char* libDirC = nullptr;
    std::string libDirStr;
    if (libDir != nullptr) {
        libDirC = env->GetStringUTFChars(libDir, nullptr);
        libDirStr = libDirC ? libDirC : "";
        env->ReleaseStringUTFChars(libDir, libDirC);
    }
    mnn_logf("nativeInit: libDir=%s", libDirStr.c_str());

    if (!libDirStr.empty()) {
        mnn_log("nativeInit: loading from downloaded directory...");
        g_libMNN = dlopen_from_dir(libDirStr.c_str(), "libMNN.so");
        if (!g_libMNN) { mnn_log("nativeInit: FAILED - cannot load libMNN.so"); return JNI_FALSE; }
        g_libMNN_Express = dlopen_from_dir(libDirStr.c_str(), "libMNN_Express.so");
        if (!g_libMNN_Express) { mnn_log("nativeInit: WARNING - cannot load libMNN_Express.so"); }
        g_libMNN_Vulkan = dlopen_from_dir(libDirStr.c_str(), "libMNN_Vulkan.so");
        g_libMNN_CL = dlopen_from_dir(libDirStr.c_str(), "libMNN_CL.so");
        g_libMNNOpenCV = dlopen_from_dir(libDirStr.c_str(), "libMNNOpenCV.so");
        g_libMNNAudio = dlopen_from_dir(libDirStr.c_str(), "libMNNAudio.so");
        g_libmnncore = dlopen_from_dir(libDirStr.c_str(), "libmnncore.so");
        g_libllm = dlopen_from_dir(libDirStr.c_str(), "libllm.so");
        if (!g_libllm) { mnn_log("nativeInit: FAILED - cannot load libllm.so"); return JNI_FALSE; }
    } else {
        mnn_log("nativeInit: no libDir, loading bundled .so via system path...");
        g_libMNN = dlopen("libMNN.so", RTLD_NOW | RTLD_LOCAL);
        if (!g_libMNN) { mnn_logf("nativeInit: dlopen(\"libMNN.so\") FAILED: %s", dlerror() ?: "unknown"); return JNI_FALSE; }
        g_libMNN_Express = dlopen("libMNN_Express.so", RTLD_NOW | RTLD_LOCAL);
        g_libMNN_Vulkan = dlopen("libMNN_Vulkan.so", RTLD_NOW | RTLD_LOCAL);
        g_libMNN_CL = dlopen("libMNN_CL.so", RTLD_NOW | RTLD_LOCAL);
        g_libMNNOpenCV = dlopen("libMNNOpenCV.so", RTLD_NOW | RTLD_LOCAL);
        g_libMNNAudio = dlopen("libMNNAudio.so", RTLD_NOW | RTLD_LOCAL);
        g_libmnncore = dlopen("libmnncore.so", RTLD_NOW | RTLD_LOCAL);
        g_libllm = dlopen("libllm.so", RTLD_NOW | RTLD_GLOBAL);
        if (!g_libllm) { mnn_logf("nativeInit: dlopen(\"libllm.so\") FAILED: %s", dlerror() ?: "unknown"); return JNI_FALSE; }
    }

    mnn_logf("nativeInit: libllm.so loaded, handle=%p", g_libllm);

    // Resolve core symbols
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
    g_reset = (ResetFunc)dlsym(g_libllm,
        "_ZN3MNN11Transformer3Llm5resetEv");

    // v2.4.72: Resolve tokenizer_encode(string) -> vector<int>
    // v2.4.73: CRITICAL FIX - mangled name had wrong length (15 instead of 16).
    // "tokenizer_encode" = 16 characters, not 15!
    {
        const char* tokenize_names[] = {
            // v2.4.73 FIXED: 16tokenizer_encode (16 chars, was 15)
            "_ZN3MNN11Transformer3Llm16tokenizer_encodeERKNSt6__ndk112basic_stringIcNS2_11char_traitsIcEENS2_9allocatorIcEEEE",
            // v2.4.72 BUG: 15tokenizer_encode (wrong length, never matched)
            "_ZN3MNN11Transformer3Llm15tokenizer_encodeERKNSt6__ndk112basic_stringIcNS2_11char_traitsIcEENS2_9allocatorIcEEEE",
            // Alternative: might be named differently in older versions
            "_ZN3MNN11Transformer3Llm7tokenizeERKNSt6__ndk112basic_stringIcNS2_11char_traitsIcEENS2_9allocatorIcEEEE",
        };
        g_tokenize = try_resolve_symbols<TokenizeFunc>(g_libllm, tokenize_names, 3);
    }

    // v2.4.72: Resolve response(const vector<int>&, ostream*, const char*, int)
    {
        const char* response_vec_names[] = {
            // Standard mangling for Llm::response(const vector<int>&, ostream*, const char*, int)
            "_ZN3MNN11Transformer3Llm8responseERKNSt6__ndk16vectorIiNS2_9allocatorIiEEEEPNS2_13basic_ostreamIcNS2_11char_traitsIcEEEEPKci",
            // Alternative mangling (different substitution numbering)
            "_ZN3MNN11Transformer3Llm8responseERKSt6__ndk16vectorIiNS_9allocatorIiEEEEPNS_13basic_ostreamIcNS_11char_traitsIcEEEEPKci",
            // Another possible variant
            "_ZN3MNN11Transformer3Llm8responseERKNSt6__ndk16vectorIiNS2_9allocatorIiEEEEPNS2_13basic_ostreamIcS5_EEPKci",
        };
        g_response_vec = try_resolve_symbols<ResponseVecFunc>(g_libllm, response_vec_names, 3);
    }

    // v2.4.79: Resolve apply_chat_template(const string&) -> string
    {
        const char* apply_chat_names[] = {
            // Llm::apply_chat_template(const string&) const
            // _ZNK3MNN11Transformer3Llm19apply_chat_templateERKNSt6__ndk112basic_stringIcNS2_11char_traitsIcEENS2_9allocatorIcEEEE
            "_ZNK3MNN11Transformer3Llm19apply_chat_templateERKNSt6__ndk112basic_stringIcNS2_11char_traitsIcEENS2_9allocatorIcEEEE",
        };
        g_apply_chat_template = try_resolve_symbols<ApplyChatTemplateFunc>(g_libllm, apply_chat_names, 1);
    }

    mnn_logf("nativeInit: symbol resolution: create=%p destroy=%p load=%p config=%p response=%p reset=%p tokenize=%p response_vec=%p apply_chat=%p",
             g_createLLM, g_destroy, g_load, g_set_config, g_response, g_reset, g_tokenize, g_response_vec, g_apply_chat_template);

    if (!g_createLLM || !g_destroy || !g_load || !g_response) {
        mnn_log("nativeInit: FAILED - one or more core symbols not found");
        return JNI_FALSE;
    }
    if (!g_reset) {
        mnn_log("nativeInit: WARNING - reset() symbol NOT found!");
    }
    if (!g_tokenize) {
        mnn_log("nativeInit: WARNING - tokenizer_encode symbol NOT found! Will fall back to response(string)");
    }
    if (!g_response_vec) {
        mnn_log("nativeInit: WARNING - response(vector<int>) symbol NOT found! Will fall back to response(string)");
    }
    if (g_tokenize && g_response_vec) {
        mnn_log("nativeInit: [v2.4.74] tokenizer_encode + response(vector<int>) both resolved! Token ID bypass active.");
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

    int max_new = (maxTokens > 0) ? (int)maxTokens : -1;

    // Helper: check if response looks like garbage
    auto checkGarbage = [](const std::string& s) -> bool {
        if (s.length() < 2) return true;
        if (s.length() < 10) return false;
        std::set<char> uniqueChars(s.begin(), s.begin() + std::min((size_t)50, s.length()));
        if (uniqueChars.size() <= 4) return true;
        if (s.length() >= 20) {
            std::string p2 = s.substr(0, 2);
            int repetitions = 0;
            for (size_t i = 0; i + 2 <= s.length() && i < 100; i += 2) {
                if (s.substr(i, 2) == p2) repetitions++;
            }
            if (repetitions > 5) return true;
        }
        return false;
    };

    // Helper: trim and clean response
    auto cleanResponse = [](std::string s) -> std::string {
        size_t start = s.find_first_not_of(" \t\n\r");
        if (start == std::string::npos) return "";
        size_t end = s.find_last_not_of(" \t\n\r");
        s = s.substr(start, end - start + 1);
        size_t stopPos = s.find("<|im_end|>");
        if (stopPos != std::string::npos) s = s.substr(0, stopPos);
        start = s.find_first_not_of(" \t\n\r");
        if (start == std::string::npos) return "";
        end = s.find_last_not_of(" \t\n\r");
        return s.substr(start, end - start + 1);
    };

    auto hasValidAnswer = [](const std::string& s) -> bool {
        return s.find("干货") != std::string::npos || s.find("水货") != std::string::npos;
    };

    // v2.4.72: Call reset() before each response
    if (g_reset) {
        g_reset(llm);
        mnn_logf("nativeGenerate: [v2.4.77] reset() called");
    }

    // ================================================================
    // v2.4.77: Use response(string) with use_template=true + jinja.chat_template
    //
    // v2.4.70 tried jinja.chat_template but set it AFTER load() → never applied.
    // v2.4.76 tried use_template=true without jinja → old MNN ignores
    // prompt_template, falls back to plain text concatenation → "慰慰慰".
    //
    // v2.4.77: jinja.chat_template is now set BEFORE load() (in MnnLlmBridge.kt).
    // With use_template=true, response(string) calls apply_chat_template which:
    //   1. Builds ChatMessages from user_content
    //   2. Applies Jinja template: <|im_start|>user\n{content}<|im_end|>\n<|im_start|>assistant\n
    //   3. Calls tokenizer_encode on the result
    // The Jinja template contains <|im_start|>/<|im_end|> as literal text, but
    // apply_chat_template returns a string, and tokenizer_encode processes it.
    // Since the Jinja template is set, tokenizer_encode will see the full ChatML text.
    // If tokenizer_encode has special_tokens_cache_, <|im_start|> gets correct token ID.
    // If not, <|im_start|> gets BPE-encoded as regular text → wrong tokens.
    //
    // We pass only user content (no ChatML markers). MNN wraps it with Jinja template.
    // ================================================================

    std::string userContent =
        "判断以下广播内容是干货还是水货，只回答干货或水货。\n" + rawPrompt;

    mnn_logf("nativeGenerate: [v2.4.77] response(string) use_template=true+jinja, userContent len=%zu, first200=%.200s",
             userContent.size(), userContent.c_str());

    std::ostringstream oss;
    g_response(llm, userContent, &oss, "<|im_end|>", max_new);
    std::string result = cleanResponse(oss.str());

    mnn_logf("nativeGenerate: [v2.4.77] result: len=%zu, garbage=%d, hasKeyword=%d, first200=%.200s",
             result.size(), checkGarbage(result) ? 1 : 0, hasValidAnswer(result) ? 1 : 0, result.c_str());

    // If still garbage, try rawPrompt without system prefix
    if (checkGarbage(result) || result.empty()) {
        mnn_logf("nativeGenerate: [v2.4.77] garbage, trying rawPrompt only");
        if (g_reset) g_reset(llm);
        std::ostringstream oss2;
        g_response(llm, rawPrompt, &oss2, "<|im_end|>", max_new);
        std::string result2 = cleanResponse(oss2.str());
        mnn_logf("nativeGenerate: [v2.4.77] rawPrompt result: len=%zu, garbage=%d, hasKeyword=%d, first200=%.200s",
                 result2.size(), checkGarbage(result2) ? 1 : 0, hasValidAnswer(result2) ? 1 : 0, result2.c_str());
        if (!result2.empty() && !checkGarbage(result2)) {
            result = result2;
        }
    }

    mnn_logf("nativeGenerate: final result len=%zu, first200=%.200s", result.size(), result.c_str());
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT void JNICALL
Java_com_radio_app_whisper_MnnLlmBridge_nativeReset(JNIEnv* env, jclass clazz, jlong ptr) {
    if (!g_reset || ptr == 0) {
        mnn_logf("nativeReset: g_reset=%p ptr=%lld (reset not available or invalid ptr)", g_reset, (long long)ptr);
        return;
    }
    auto* llm = reinterpret_cast<MNN::Transformer::Llm*>(ptr);
    g_reset(llm);
    mnn_log("nativeReset: LLM session reset (KV cache cleared, position_id reset to 0)");
}

JNIEXPORT jstring JNICALL
Java_com_radio_app_whisper_MnnLlmBridge_nativeGetCompileMarker(JNIEnv* env, jclass clazz) {
    return env->NewStringUTF("MNN_JNI_v2.4.79");
}

static bool isGarbageResponse(const std::string& s) {
    if (s.size() < 10) return false;
    std::set<char> uniqueChars;
    int limit = std::min((int)s.size(), 50);
    for (int i = 0; i < limit; i++) {
        uniqueChars.insert(s[i]);
    }
    if (uniqueChars.size() <= 4) return true;
    if (s.size() >= 20) {
        std::string pattern = s.substr(0, 2);
        int count = 0;
        for (size_t i = 0; i < s.size() - 1; i += 2) {
            if (s.substr(i, 2) == pattern) count++;
        }
        if (count > 5) return true;
    }
    return false;
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

// v2.4.79: Diagnostic function to test apply_chat_template
JNIEXPORT jstring JNICALL
Java_com_radio_app_whisper_MnnLlmBridge_nativeTestApplyChatTemplate(JNIEnv* env, jclass clazz, jlong ptr, jstring userInput) {
    if (!g_apply_chat_template || ptr == 0) {
        mnn_log("nativeTestApplyChatTemplate: apply_chat_template not available or ptr=0");
        return env->NewStringUTF("[ERROR: apply_chat_template not resolved]");
    }
    auto* llm = reinterpret_cast<MNN::Transformer::Llm*>(ptr);
    const char* input = env->GetStringUTFChars(userInput, nullptr);
    std::string inputStr(input);
    env->ReleaseStringUTFChars(userInput, input);

    mnn_logf("nativeTestApplyChatTemplate: [v2.4.79] input='%s'", inputStr.c_str());

    std::string result = g_apply_chat_template(llm, inputStr);

    mnn_logf("nativeTestApplyChatTemplate: [v2.4.79] result len=%zu, content='%.500s'",
             result.size(), result.c_str());

    return env->NewStringUTF(result.c_str());
}

} // extern "C"
