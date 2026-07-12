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
    mnn_log("mnn_llm_jni COMPILE MARKER: v2.4.72 compiled at " __DATE__ " " __TIME__);

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
    // Try multiple possible mangled names for different MNN versions
    {
        const char* tokenize_names[] = {
            // Standard mangling for Llm::tokenizer_encode(const string&)
            "_ZN3MNN11Transformer3Llm15tokenizer_encodeERKNSt6__ndk112basic_stringIcNS2_11char_traitsIcEENS2_9allocatorIcEEEE",
            // Alternative: might be named differently in older versions
            "_ZN3MNN11Transformer3Llm7tokenizeERKNSt6__ndk112basic_stringIcNS2_11char_traitsIcEENS2_9allocatorIcEEEE",
        };
        g_tokenize = try_resolve_symbols<TokenizeFunc>(g_libllm, tokenize_names, 2);
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

    mnn_logf("nativeInit: symbol resolution: create=%p destroy=%p load=%p config=%p response=%p reset=%p tokenize=%p response_vec=%p",
             g_createLLM, g_destroy, g_load, g_set_config, g_response, g_reset, g_tokenize, g_response_vec);

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
        mnn_log("nativeInit: [v2.4.72] tokenizer_encode + response(vector<int>) both resolved! Will use token ID bypass.");
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
        mnn_logf("nativeGenerate: [v2.4.72] reset() called");
    }

    // ================================================================
    // v2.4.72: PRIMARY PATH - Token ID bypass
    // Bypass MNN's broken template system by:
    // 1. Using tokenizer_encode to encode text parts (without special tokens)
    // 2. Manually inserting ChatML special token IDs (151644, 151645)
    // 3. Calling response(vector<int>) directly with the complete token ID list
    //
    // This works even if:
    // - LLM_USE_JINJA is not compiled in (no Jinja2 template support)
    // - special_tokens_cache_ is missing (tokenizer doesn't recognize <|im_start|> in text)
    // - use_template config key is not recognized by old MNN
    // ================================================================
    std::string result;

    if (g_tokenize && g_response_vec) {
        mnn_logf("nativeGenerate: [v2.4.72] Using token ID bypass (tokenizer_encode + response_vec)");

        // Step 1: Diagnostic - check if tokenizer recognizes <|im_start|>
        {
            std::vector<int> testIds = g_tokenize(llm, "<|im_start|>");
            std::string idsStr;
            for (size_t i = 0; i < testIds.size() && i < 10; i++) {
                idsStr += std::to_string(testIds[i]) + " ";
            }
            bool recognizesSpecial = (testIds.size() == 1 && testIds[0] == CHATML_IM_START);
            // Note: tokenizer_encode adds prefix_tokens_, so the count might be >1.
            // But if the LAST token is 151644, it means <|im_start|> was recognized.
            bool lastIsSpecial = (!testIds.empty() && testIds.back() == CHATML_IM_START);
            mnn_logf("nativeGenerate: [v2.4.72] DIAGNOSTIC: encode(\"<|im_start|>\") = [%s] count=%zu, recognizesSpecial=%d, lastIsSpecial=%d",
                     idsStr.c_str(), testIds.size(), recognizesSpecial ? 1 : 0, lastIsSpecial ? 1 : 0);
        }

        // Step 2: Encode the full ChatML text using tokenizer_encode
        // If the tokenizer recognizes special tokens, we can encode the full ChatML text directly.
        // If not, we need to encode text parts separately and insert special token IDs manually.
        std::string chatmlPrompt =
            "<|im_start|>system\n你是一个广播内容分类器。判断广播内容是干货还是水货，只回答干货或水货。<|im_end|>\n"
            "<|im_start|>user\n" + rawPrompt + "<|im_end|>\n"
            "<|im_start|>assistant\n";

        // Try encoding the full ChatML text first
        std::vector<int> allIds = g_tokenize(llm, chatmlPrompt);

        // Check if the encoded IDs contain CHATML_IM_START (151644)
        bool hasSpecialTokens = false;
        for (int id : allIds) {
            if (id == CHATML_IM_START || id == CHATML_IM_END) {
                hasSpecialTokens = true;
                break;
            }
        }

        // Log first 40 token IDs for debugging
        {
            std::string idsStr;
            for (size_t i = 0; i < allIds.size() && i < 40; i++) {
                idsStr += std::to_string(allIds[i]) + " ";
            }
            mnn_logf("nativeGenerate: [v2.4.72] Full ChatML encode: count=%zu, hasSpecialTokens=%d, first40=[%s]",
                     allIds.size(), hasSpecialTokens ? 1 : 0, idsStr.c_str());
        }

        if (hasSpecialTokens) {
            // Tokenizer recognized special tokens! Use the encoded IDs directly.
            mnn_logf("nativeGenerate: [v2.4.72] Tokenizer recognized special tokens, using encoded IDs directly");
        } else {
            // Tokenizer did NOT recognize special tokens.
            // Manually construct token IDs by encoding text parts separately.
            mnn_logf("nativeGenerate: [v2.4.72] Tokenizer did NOT recognize special tokens, constructing manually");

            // Encode a dummy string to find out how many prefix_tokens_ are added
            std::vector<int> dummyIds = g_tokenize(llm, "");
            int prefixCount = (int)dummyIds.size();
            mnn_logf("nativeGenerate: [v2.4.72] prefix_tokens count=%d", prefixCount);

            // Helper: encode text and strip prefix tokens
            auto encodeNoPrefix = [&](const std::string& text) -> std::vector<int> {
                std::vector<int> ids = g_tokenize(llm, text);
                if ((int)ids.size() > prefixCount) {
                    ids.erase(ids.begin(), ids.begin() + prefixCount);
                }
                return ids;
            };

            // Build ChatML token sequence manually:
            // <|im_start|>system\n{system}<|im_end|>\n
            // <|im_start|>user\n{user}<|im_end|>\n
            // <|im_start|>assistant\n
            allIds.clear();
            // Start with prefix tokens (BOS etc.)
            for (int i = 0; i < prefixCount; i++) {
                allIds.push_back(dummyIds[i]);
            }

            // System message
            allIds.push_back(CHATML_IM_START);
            auto sysIds = encodeNoPrefix("system\n你是一个广播内容分类器。判断广播内容是干货还是水货，只回答干货或水货。");
            allIds.insert(allIds.end(), sysIds.begin(), sysIds.end());
            allIds.push_back(CHATML_IM_END);
            auto nlIds = encodeNoPrefix("\n");
            allIds.insert(allIds.end(), nlIds.begin(), nlIds.end());

            // User message
            allIds.push_back(CHATML_IM_START);
            auto userIds = encodeNoPrefix("user\n" + rawPrompt);
            allIds.insert(allIds.end(), userIds.begin(), userIds.end());
            allIds.push_back(CHATML_IM_END);
            allIds.insert(allIds.end(), nlIds.begin(), nlIds.end());

            // Assistant prompt
            allIds.push_back(CHATML_IM_START);
            auto asstIds = encodeNoPrefix("assistant\n");
            allIds.insert(allIds.end(), asstIds.begin(), asstIds.end());

            // Log the manually constructed token IDs
            {
                std::string idsStr;
                for (size_t i = 0; i < allIds.size() && i < 50; i++) {
                    idsStr += std::to_string(allIds[i]) + " ";
                }
                mnn_logf("nativeGenerate: [v2.4.72] Manual token IDs: count=%zu, first50=[%s]",
                         allIds.size(), idsStr.c_str());
            }
        }

        // Step 3: Call response(vector<int>) with the token IDs
        std::ostringstream oss;
        g_response_vec(llm, allIds, &oss, "<|im_end|>", max_new);
        result = cleanResponse(oss.str());

        mnn_logf("nativeGenerate: [v2.4.72] response_vec result: len=%zu, garbage=%d, hasKeyword=%d, first200=%.200s",
                 result.size(), checkGarbage(result) ? 1 : 0, hasValidAnswer(result) ? 1 : 0, result.c_str());

        // If token ID bypass produced garbage, fall back to response(string) as last resort
        if (checkGarbage(result) || result.empty()) {
            mnn_logf("nativeGenerate: [v2.4.72] token ID bypass produced garbage, trying response(string) fallback");

            if (g_reset) g_reset(llm);
            std::ostringstream oss2;
            g_response(llm, chatmlPrompt, &oss2, "<|im_end|>", max_new);
            std::string result2 = cleanResponse(oss2.str());
            mnn_logf("nativeGenerate: [v2.4.72] response(string) fallback: len=%zu, garbage=%d, first200=%.200s",
                     result2.size(), checkGarbage(result2) ? 1 : 0, result2.c_str());
            if (!result2.empty() && !checkGarbage(result2)) {
                result = result2;
            }
        }
    } else {
        // ================================================================
        // FALLBACK PATH - response(string) with manual ChatML
        // Used when tokenizer_encode or response(vector<int>) symbols are not available
        // ================================================================
        mnn_logf("nativeGenerate: [v2.4.72] Fallback: response(string) with ChatML (tokenize=%p, response_vec=%p)",
                 g_tokenize, g_response_vec);

        std::string chatmlPrompt =
            "<|im_start|>system\n你是一个广播内容分类器。判断广播内容是干货还是水货，只回答干货或水货。<|im_end|>\n"
            "<|im_start|>user\n" + rawPrompt + "<|im_end|>\n"
            "<|im_start|>assistant\n";

        mnn_logf("nativeGenerate: [v2.4.72] response(string), chatml len=%zu, first200=%.200s",
                 chatmlPrompt.size(), chatmlPrompt.c_str());

        std::ostringstream oss;
        g_response(llm, chatmlPrompt, &oss, "<|im_end|>", max_new);
        result = cleanResponse(oss.str());

        mnn_logf("nativeGenerate: [v2.4.72] response(string) result: len=%zu, garbage=%d, hasKeyword=%d, first200=%.200s",
                 result.size(), checkGarbage(result) ? 1 : 0, hasValidAnswer(result) ? 1 : 0, result.c_str());
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
    return env->NewStringUTF("MNN_JNI_v2.4.72");
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

} // extern "C"
