// [v2.4.86] MNN-LLM JNI bridge - loads libllm.so dynamically from a given directory
// Uses dlopen/dlsym with raw C++ mangled names to avoid needing MNN headers at compile time
//
// v2.4.86 fixes:
//   1. CRITICAL: Reordered symbol resolution to try const char* (PKc) versions FIRST
//      since our typedef uses const char*. Previously tried const std::string& versions
//      first, which if found would cause ABI mismatch → garbage stop_prompt → infinite
//      repetition until max_new_tokens.
//   2. CRITICAL: Call set_config AFTER load() to ensure use_template=true is active.
//      Previously set_config was called before load(), which MNN ignores (reads from file).
//   3. Simplified generation: use response(string) with proper JSON messages array
//      containing system prompt, let MNN's built-in template+special-token handling work.
//   4. Added BOS token suppression awareness when manually tokenizing (as fallback).
//   5. Removed keyword classification fallback in native (handled in Kotlin if needed).
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

#define TAG "MnnLlmJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// File-based logging for debugging MNN load failures
static int g_log_fd = -1;
static void mnn_logf(const char* fmt, ...);
static void mnn_log(const char* msg) {
    LOGI("%s", msg);
    if (g_log_fd < 0) {
        const char* paths[] = {
            "/storage/emulated/0/RadioApp/logs/subtitle/native.log",
            "/sdcard/RadioApp/logs/subtitle/native.log",
            "/storage/emulated/0/Android/data/com.radio.app/files/logs/subtitle/native.log",
            "/data/data/com.radio.app/files/logs/subtitle/native.log",
            nullptr
        };
        for (int i = 0; paths[i] != nullptr; i++) {
            char dir[256];
            strncpy(dir, paths[i], sizeof(dir) - 1);
            dir[sizeof(dir) - 1] = '\0';
            char* last_slash = strrchr(dir, '/');
            if (last_slash) {
                *last_slash = '\0';
                mkdir(dir, 0755);
                char* prev_slash = strrchr(dir, '/');
                if (prev_slash) {
                    *prev_slash = '\0';
                    mkdir(dir, 0755);
                }
            }
            g_log_fd = open(paths[i], O_WRONLY | O_CREAT | O_APPEND, 0644);
            if (g_log_fd >= 0) {
                mnn_logf("mnn_log: log file opened at %s (fd=%d)", paths[i], g_log_fd);
                break;
            }
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

// Function pointer types matching C++ ABI.
// IMPORTANT: Both response() overloads take stop_prompt as const char* (PKc in mangling).
// Using const std::string& would cause ABI mismatch because the caller passes a C string
// literal and the callee expects a std::string object reference.
typedef MNN::Transformer::Llm* (*CreateLLMFunc)(const std::string&);
typedef void (*DestroyFunc)(MNN::Transformer::Llm*);
typedef bool (*LoadFunc)(MNN::Transformer::Llm*);
typedef bool (*SetConfigFunc)(MNN::Transformer::Llm*, const std::string&);
typedef void (*ResponseStrFunc)(MNN::Transformer::Llm*, const std::string&, std::ostream*, const char*, int);
typedef void (*ResetFunc)(MNN::Transformer::Llm*);
typedef std::vector<int> (*TokenizeFunc)(MNN::Transformer::Llm*, const std::string&);
typedef void (*ResponseVecFunc)(MNN::Transformer::Llm*, const std::vector<int>&, std::ostream*, const char*, int);
typedef std::string (*ApplyChatTemplateFunc)(const MNN::Transformer::Llm*, const std::string&);

// Keep handles to all loaded libraries
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
static TokenizeFunc g_tokenize = nullptr;
static ResponseVecFunc g_response_vec = nullptr;
static ApplyChatTemplateFunc g_apply_chat_template = nullptr;

// Qwen2/Qwen2.5 ChatML special token IDs
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

// Try to resolve a symbol from multiple possible mangled names
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
    mnn_log("mnn_llm_jni COMPILE MARKER: v2.4.86 compiled at " __DATE__ " " __TIME__);

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

    // Resolve core symbols with hardcoded mangled names that match const char* stop_prompt.
    // The response() overloads use PKc (const char*) for stop_prompt, NOT const std::string&.
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

    // Resolve tokenizer_encode(string) -> vector<int>
    {
        const char* tokenize_names[] = {
            "_ZN3MNN11Transformer3Llm16tokenizer_encodeERKNSt6__ndk112basic_stringIcNS2_11char_traitsIcEENS2_9allocatorIcEEEE",
            "_ZN3MNN11Transformer3Llm15tokenizer_encodeERKNSt6__ndk112basic_stringIcNS2_11char_traitsIcEENS2_9allocatorIcEEEE",
            "_ZN3MNN11Transformer3Llm7tokenizeERKNSt6__ndk112basic_stringIcNS2_11char_traitsIcEENS2_9allocatorIcEEEE",
        };
        g_tokenize = try_resolve_symbols<TokenizeFunc>(g_libllm, tokenize_names, 3);
    }

    // v2.4.86: CRITICAL FIX - Try const char* (PKc) versions FIRST!
    // Our typedef uses const char* for stop_prompt. Previously we tried const std::string&
    // versions first, which if found would be cast to const char* function pointer → ABI
    // mismatch → garbage stop_prompt → model never stops → infinite repetition.
    {
        const char* response_vec_names[] = {
            // PKc (const char*) versions - TRIED FIRST because our typedef uses const char*
            "_ZN3MNN11Transformer3Llm8responseERKNSt6__ndk16vectorIiNS2_9allocatorIiEEEEPNS2_13basic_ostreamIcNS2_11char_traitsIcEEEEPKci",
            "_ZN3MNN11Transformer3Llm8responseERKSt6__ndk16vectorIiNS_9allocatorIiEEEEPNS_13basic_ostreamIcNS_11char_traitsIcEEEEPKci",
            "_ZN3MNN11Transformer3Llm8responseERKNSt6__ndk16vectorIiNS2_9allocatorIiEEEEPNS2_13basic_ostreamIcS5_EEEPKci",
            // const std::string& versions - fallback only (would require different typedef)
            "_ZN3MNN11Transformer3Llm8responseERKNSt6__ndk16vectorIiNS2_9allocatorIiEEEEPNS2_13basic_ostreamIcNS2_11char_traitsIcEEEERKNSt6__ndk112basic_stringIcNS2_11char_traitsIcEENS2_9allocatorIcEEEEi",
            "_ZN3MNN11Transformer3Llm8responseERKSt6__ndk16vectorIiNS_9allocatorIiEEEEPNS_13basic_ostreamIcNS_11char_traitsIcEEEERKNSt6__ndk112basic_stringIcNS2_11char_traitsIcEENS2_9allocatorIcEEEEi",
        };
        g_response_vec = try_resolve_symbols<ResponseVecFunc>(g_libllm, response_vec_names, 5);
    }

    // Resolve apply_chat_template(const string&) const -> string
    {
        const char* apply_chat_names[] = {
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
        mnn_log("nativeInit: WARNING - tokenizer_encode symbol NOT found!");
    }
    if (!g_response_vec) {
        mnn_log("nativeInit: WARNING - response(vector<int>) symbol NOT found!");
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

// v2.4.86: Set generation config AFTER load() so MNN actually uses it.
// Previously set_config was called before load(), but MNN reads config from the
// file passed to createLLM during load(), so pre-load set_config was ignored.
JNIEXPORT jboolean JNICALL
Java_com_radio_app_whisper_MnnLlmBridge_nativeSetConfigPostLoad(JNIEnv* env, jclass clazz, jlong ptr) {
    if (!g_set_config || ptr == 0) {
        mnn_logf("nativeSetConfigPostLoad: g_set_config=%p ptr=%lld", g_set_config, (long long)ptr);
        return JNI_FALSE;
    }
    auto* llm = reinterpret_cast<MNN::Transformer::Llm*>(ptr);
    // Low temperature for deterministic classification, short output, template enabled
    const char* configJson = "{\"temperature\":0.1,\"top_p\":0.8,\"max_new_tokens\":2000,\"repetition_penalty\":1.05,\"use_template\":true}";
    mnn_logf("nativeSetConfigPostLoad: setting config after load: %s", configJson);
    bool ok = g_set_config(llm, std::string(configJson));
    mnn_logf("nativeSetConfigPostLoad: set_config returned %d", ok);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// Helper: check if response looks like garbage/repetition
static bool isGarbageResponse(const std::string& s) {
    if (s.length() < 2) return true;
    if (s.length() < 10) return false;
    // Very few unique chars = repetitive garbage like "FFFFFFFF" or "慰慰慰"
    std::set<char> uniqueChars(s.begin(), s.begin() + std::min((size_t)50, s.length()));
    if (uniqueChars.size() <= 4) return true;
    // Repeating 2-char pattern like "interoperinteroper..."
    if (s.length() >= 20) {
        std::string p2 = s.substr(0, 2);
        int repetitions = 0;
        for (size_t i = 0; i + 2 <= s.length() && i < 100; i += 2) {
            if (s.substr(i, 2) == p2) repetitions++;
        }
        if (repetitions > 5) return true;
    }
    return false;
}

// Helper: trim whitespace and cut at stop token
static std::string cleanResponse(std::string s) {
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
}

static bool hasValidAnswer(const std::string& s) {
    return s.find("干货") != std::string::npos || s.find("水货") != std::string::npos;
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

    int max_new = (maxTokens > 0) ? (int)maxTokens : 50;

    // Reset KV cache and position_id before each generation
    if (g_reset) {
        g_reset(llm);
    }

    // ================================================================
    // v2.4.86 STRATEGY: Try methods in order of reliability:
    //
    // METHOD 1: response(string) with JSON messages array + use_template=true.
    //   This is the cleanest path: we pass a JSON array of messages (system + user),
    //   MNN's apply_chat_template formats it to ChatML, and the tokenizer handles
    //   special tokens internally. set_config was called AFTER load() so use_template
    //   is active. This should work on all MNN versions that have Jinja support.
    //
    // METHOD 2: response(string) with raw ChatML text + use_template=false.
    //   If METHOD 1 doesn't produce keywords, try constructing ChatML manually
    //   and passing it as raw text (relies on tokenizer recognizing special tokens).
    //
    // METHOD 3: Manual token ID construction (fallback if tokenizer_encode works).
    //   Manually build token IDs with hardcoded special token IDs, bypassing
    //   the tokenizer's special-token handling entirely.
    // ================================================================

    std::string result;

    // --- METHOD 1: JSON messages array with use_template=true ---
    {
        // Build JSON messages array: system prompt + user prompt
        // Escape quotes in rawPrompt for JSON safety
        std::string escapedPrompt;
        for (char c : rawPrompt) {
            if (c == '"' || c == '\\') {
                escapedPrompt += '\\';
                escapedPrompt += c;
            } else if (c == '\n') {
                escapedPrompt += "\\n";
            } else if (c == '\r') {
                escapedPrompt += "\\r";
            } else if (c == '\t') {
                escapedPrompt += "\\t";
            } else {
                escapedPrompt += c;
            }
        }

        std::string jsonMessages =
            "[{\"role\":\"system\",\"content\":\"你是一个广播内容分类助手。根据内容判断广播节目片段是干货还是水货，只回答'干货'或'水货'两个词之一，不要输出其他内容。\"},"
            "{\"role\":\"user\",\"content\":\"判断以下广播内容是干货还是水货：\\n" + escapedPrompt + "\"}]";

        mnn_logf("nativeGenerate: [METHOD1] JSON messages len=%zu, first200=%.200s",
                 jsonMessages.size(), jsonMessages.c_str());

        std::ostringstream oss;
        g_response(llm, jsonMessages, &oss, "<|im_end|>", max_new);
        result = cleanResponse(oss.str());

        mnn_logf("nativeGenerate: [METHOD1] result: len=%zu, garbage=%d, hasKeyword=%d, first200=%.200s",
                 result.size(), isGarbageResponse(result) ? 1 : 0, hasValidAnswer(result) ? 1 : 0, result.c_str());

        if (!result.empty() && !isGarbageResponse(result) && hasValidAnswer(result)) {
            mnn_log("nativeGenerate: [METHOD1] SUCCESS - valid answer with keywords");
            return env->NewStringUTF(result.c_str());
        }
        mnn_logf("nativeGenerate: [METHOD1] failed (empty=%d, garbage=%d, hasKeyword=%d), trying METHOD2",
                 result.empty() ? 1 : 0, isGarbageResponse(result) ? 1 : 0, hasValidAnswer(result) ? 1 : 0);
    }

    // Reset before trying next method
    if (g_reset) g_reset(llm);

    // --- METHOD 2: Raw ChatML text with response(string) ---
    // Build ChatML manually: system + user + assistant prompt
    {
        std::string chatmlInput =
            "<|im_start|>system\n你是一个广播内容分类助手。只回答干货或水货。<|im_end|>\n"
            "<|im_start|>user\n判断以下广播内容是干货还是水货，只回答干货或水货：\n" + rawPrompt + "<|im_end|>\n"
            "<|im_start|>assistant\n";

        mnn_logf("nativeGenerate: [METHOD2] raw ChatML len=%zu", chatmlInput.size());

        // Try with use_template=false by passing a config that disables it
        // Note: we can't easily change config mid-session, but if the tokenizer
        // recognizes special tokens in raw text, this will work.
        std::ostringstream oss;
        g_response(llm, chatmlInput, &oss, "<|im_end|>", max_new);
        result = cleanResponse(oss.str());

        mnn_logf("nativeGenerate: [METHOD2] result: len=%zu, garbage=%d, hasKeyword=%d, first200=%.200s",
                 result.size(), isGarbageResponse(result) ? 1 : 0, hasValidAnswer(result) ? 1 : 0, result.c_str());

        if (!result.empty() && !isGarbageResponse(result) && hasValidAnswer(result)) {
            mnn_log("nativeGenerate: [METHOD2] SUCCESS - valid answer with keywords");
            return env->NewStringUTF(result.c_str());
        }
        mnn_logf("nativeGenerate: [METHOD2] failed, trying METHOD3 (manual tokens)");
    }

    // Reset before trying next method
    if (g_reset) g_reset(llm);

    // --- METHOD 3: Manual token ID construction (most robust fallback) ---
    if (g_tokenize && g_response_vec) {
        mnn_log("nativeGenerate: [METHOD3] manual token ID construction");

        // Build the full ChatML text with system prompt
        std::string systemPrompt = "你是一个广播内容分类助手。只回答干货或水货，不要其他内容。";
        std::string userPrompt = "判断以下广播内容是干货还是水货，只回答干货或水货：\n" + rawPrompt;

        // Construct ChatML segments to tokenize separately
        // We build: <|im_start|>system\n{system}<|im_end|>\n<|im_start|>user\n{user}<|im_end|>\n<|im_start|>assistant\n
        struct Segment {
            const char* text;
            bool isSpecial;
            int specialToken;
        };

        // Build segments list
        std::vector<std::pair<std::string, bool>> segments;  // (text, isSpecialStart)
        // Helper to add a text segment followed by special token
        auto addTurn = [&](const std::string& role, const std::string& content) {
            segments.push_back({"", true});  // im_start
            segments.push_back({role + "\n" + content, false});  // role\ncontent
            segments.push_back({"", false});  // placeholder for im_end (handled below)
        };

        // Simpler approach: build the full ChatML string and parse it
        std::string fullChatML =
            "<|im_start|>system\n" + systemPrompt + "<|im_end|>\n"
            "<|im_start|>user\n" + userPrompt + "<|im_end|>\n"
            "<|im_start|>assistant\n";

        mnn_logf("nativeGenerate: [METHOD3] fullChatML len=%zu", fullChatML.size());

        const std::string MARKER_START = "<|im_start|>";
        const std::string MARKER_END = "<|im_end|>";

        std::vector<int> inputIds;
        size_t pos = 0;
        bool firstSegment = true;
        while (pos < fullChatML.size()) {
            size_t startIdx = fullChatML.find(MARKER_START, pos);
            size_t endIdx = fullChatML.find(MARKER_END, pos);

            size_t nextMarker = std::string::npos;
            bool isStart = false;
            if (startIdx != std::string::npos && (endIdx == std::string::npos || startIdx < endIdx)) {
                nextMarker = startIdx;
                isStart = true;
            } else if (endIdx != std::string::npos) {
                nextMarker = endIdx;
                isStart = false;
            }

            if (nextMarker == std::string::npos) {
                std::string rest = fullChatML.substr(pos);
                if (!rest.empty()) {
                    auto tokens = g_tokenize(llm, rest);
                    // v2.4.86: For the FIRST text segment after a marker, check if
                    // tokenizer added BOS (token 151643 for Qwen2). If so, remove it
                    // to avoid BOS appearing mid-sequence.
                    if (!firstSegment && !tokens.empty() && tokens[0] == 151643) {
                        tokens.erase(tokens.begin());
                        mnn_log("nativeGenerate: [METHOD3] removed leading BOS (151643) from segment");
                    }
                    mnn_logf("nativeGenerate: [METHOD3] encode segment len=%zu, tokens=%zu", rest.size(), tokens.size());
                    inputIds.insert(inputIds.end(), tokens.begin(), tokens.end());
                }
                break;
            }

            if (nextMarker > pos) {
                std::string segment = fullChatML.substr(pos, nextMarker - pos);
                if (!segment.empty()) {
                    auto tokens = g_tokenize(llm, segment);
                    // Remove leading BOS for non-first segments to avoid corruption
                    if (!firstSegment && !tokens.empty() && tokens[0] == 151643) {
                        tokens.erase(tokens.begin());
                    }
                    inputIds.insert(inputIds.end(), tokens.begin(), tokens.end());
                    firstSegment = false;
                }
            }

            // Insert special token ID
            inputIds.push_back(isStart ? CHATML_IM_START : CHATML_IM_END);
            firstSegment = false;

            pos = nextMarker + (isStart ? MARKER_START.size() : MARKER_END.size());
        }

        mnn_logf("nativeGenerate: [METHOD3] total input tokens=%zu, first15=%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d",
                 inputIds.size(),
                 inputIds.size() > 0 ? inputIds[0] : -1,
                 inputIds.size() > 1 ? inputIds[1] : -1,
                 inputIds.size() > 2 ? inputIds[2] : -1,
                 inputIds.size() > 3 ? inputIds[3] : -1,
                 inputIds.size() > 4 ? inputIds[4] : -1,
                 inputIds.size() > 5 ? inputIds[5] : -1,
                 inputIds.size() > 6 ? inputIds[6] : -1,
                 inputIds.size() > 7 ? inputIds[7] : -1,
                 inputIds.size() > 8 ? inputIds[8] : -1,
                 inputIds.size() > 9 ? inputIds[9] : -1,
                 inputIds.size() > 10 ? inputIds[10] : -1,
                 inputIds.size() > 11 ? inputIds[11] : -1,
                 inputIds.size() > 12 ? inputIds[12] : -1,
                 inputIds.size() > 13 ? inputIds[13] : -1,
                 inputIds.size() > 14 ? inputIds[14] : -1);

        if (!inputIds.empty()) {
            std::ostringstream oss;
            g_response_vec(llm, inputIds, &oss, "<|im_end|>", max_new);
            result = cleanResponse(oss.str());

            mnn_logf("nativeGenerate: [METHOD3] result: len=%zu, garbage=%d, hasKeyword=%d, first200=%.200s",
                     result.size(), isGarbageResponse(result) ? 1 : 0, hasValidAnswer(result) ? 1 : 0, result.c_str());

            if (!result.empty() && !isGarbageResponse(result)) {
                mnn_log("nativeGenerate: [METHOD3] result obtained");
                return env->NewStringUTF(result.c_str());
            }
        }
        mnn_log("nativeGenerate: [METHOD3] failed");
    }

    // --- Last resort: simple prompt with no system message ---
    if (g_reset) g_reset(llm);
    {
        mnn_log("nativeGenerate: [LAST RESORT] simple prompt without system prefix");
        std::string simplePrompt = "判断以下广播内容是干货还是水货，只回答干货或水货。\n" + rawPrompt;
        std::ostringstream oss;
        g_response(llm, simplePrompt, &oss, "<|im_end|>", max_new);
        result = cleanResponse(oss.str());
        mnn_logf("nativeGenerate: [LAST RESORT] result: len=%zu, garbage=%d, first200=%.200s",
                 result.size(), isGarbageResponse(result) ? 1 : 0, result.c_str());
    }

    mnn_logf("nativeGenerate: returning final result len=%zu", result.size());
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT void JNICALL
Java_com_radio_app_whisper_MnnLlmBridge_nativeReset(JNIEnv* env, jclass clazz, jlong ptr) {
    if (!g_reset || ptr == 0) {
        mnn_logf("nativeReset: g_reset=%p ptr=%lld", g_reset, (long long)ptr);
        return;
    }
    auto* llm = reinterpret_cast<MNN::Transformer::Llm*>(ptr);
    g_reset(llm);
    mnn_log("nativeReset: LLM session reset (KV cache cleared, position_id reset to 0)");
}

JNIEXPORT jstring JNICALL
Java_com_radio_app_whisper_MnnLlmBridge_nativeGetCompileMarker(JNIEnv* env, jclass clazz) {
    return env->NewStringUTF("MNN_JNI_v2.4.86");
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

// Diagnostic function to test apply_chat_template
JNIEXPORT jstring JNICALL
Java_com_radio_app_whisper_MnnLlmBridge_nativeTestApplyChatTemplate(JNIEnv* env, jclass clazz, jlong ptr, jstring userInput) {
    if (!g_apply_chat_template || ptr == 0) {
        return env->NewStringUTF("[ERROR: apply_chat_template not resolved]");
    }
    auto* llm = reinterpret_cast<MNN::Transformer::Llm*>(ptr);
    const char* input = env->GetStringUTFChars(userInput, nullptr);
    std::string inputStr(input);
    env->ReleaseStringUTFChars(userInput, input);

    mnn_logf("nativeTestApplyChatTemplate: input='%s'", inputStr.c_str());
    std::string result = g_apply_chat_template(llm, inputStr);
    mnn_logf("nativeTestApplyChatTemplate: result len=%zu, content='%.500s'",
             result.size(), result.c_str());

    return env->NewStringUTF(result.c_str());
}

// v2.4.86: Diagnostic - test JSON messages with apply_chat_template
JNIEXPORT jstring JNICALL
Java_com_radio_app_whisper_MnnLlmBridge_nativeTestJsonTemplate(JNIEnv* env, jclass clazz, jlong ptr) {
    if (!g_apply_chat_template || ptr == 0) {
        return env->NewStringUTF("[ERROR: apply_chat_template not resolved]");
    }
    auto* llm = reinterpret_cast<MNN::Transformer::Llm*>(ptr);
    // Test with JSON messages array (system + user)
    std::string jsonInput =
        "[{\"role\":\"system\",\"content\":\"你是一个分类助手。\"},"
        "{\"role\":\"user\",\"content\":\"你好\"}]";
    mnn_logf("nativeTestJsonTemplate: JSON input='%s'", jsonInput.c_str());
    std::string result = g_apply_chat_template(llm, jsonInput);
    mnn_logf("nativeTestJsonTemplate: result len=%zu, content='%.500s'",
             result.size(), result.c_str());
    return env->NewStringUTF(result.c_str());
}

} // extern "C"
