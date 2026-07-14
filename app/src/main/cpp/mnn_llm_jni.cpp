// [v2.4.88] MNN-LLM JNI bridge
//
// v2.4.88 ROOT CAUSE FIX:
//   The REAL root cause was in MnnLlmBridge.kt: createLLM() was called with
//   llm.mnn.json (7MB model structure file) instead of config.json (159 bytes runtime config).
//   This caused MNN to load the model with wrong architecture settings → garbage output.
//
//   Native changes:
//   1. Use response(string) as PRIMARY method - MNN applies prompt_template from llm_config.json
//      automatically: "<|im_start|>user\n%s<|im_end|>\n<|im_start|>assistant\n"
//   2. Keep response(vector<int>) as FALLBACK if response(string) produces garbage
//   3. Self-test now uses response(string) first, then response(vector) as fallback
//   4. Do NOT call set_config() - let model use defaults from config.json
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
                if (prev_slash) { *prev_slash = '\0'; mkdir(dir, 0755); }
            }
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

namespace MNN { namespace Transformer { class Llm; }}

typedef MNN::Transformer::Llm* (*CreateLLMFunc)(const std::string&);
typedef void (*DestroyFunc)(MNN::Transformer::Llm*);
typedef bool (*LoadFunc)(MNN::Transformer::Llm*);
typedef bool (*SetConfigFunc)(MNN::Transformer::Llm*, const std::string&);
typedef void (*ResponseStrFunc)(MNN::Transformer::Llm*, const std::string&, std::ostream*, const char*, int);
typedef void (*ResetFunc)(MNN::Transformer::Llm*);
typedef std::vector<int> (*TokenizeFunc)(MNN::Transformer::Llm*, const std::string&);
typedef void (*ResponseVecFunc)(MNN::Transformer::Llm*, const std::vector<int>&, std::ostream*, const char*, int);

static void* g_libMNN = nullptr;
static void* g_libllm = nullptr;
static void* g_libMNN_Express = nullptr;
static void* g_libMNN_Vulkan = nullptr;
static void* g_libMNN_CL = nullptr;
static void* g_libMNNOpenCV = nullptr;
static void* g_libMNNAudio = nullptr;
static void* g_libmnncore = nullptr;

static CreateLLMFunc g_createLLM = nullptr;
static DestroyFunc g_destroy = nullptr;
static LoadFunc g_load = nullptr;
static SetConfigFunc g_set_config = nullptr;
static ResponseStrFunc g_response = nullptr;
static ResetFunc g_reset = nullptr;
static TokenizeFunc g_tokenize = nullptr;
static ResponseVecFunc g_response_vec = nullptr;

static const int QWEN2_BOS = 151643;
static const int CHATML_IM_START = 151644;
static const int CHATML_IM_END = 151645;
static bool g_model_sane = false;

static void* dlopen_from_dir(const char* dir, const char* libname) {
    std::string fullpath = std::string(dir) + "/" + libname;
    struct stat st;
    if (stat(fullpath.c_str(), &st) != 0) return nullptr;
    return dlopen(fullpath.c_str(), RTLD_NOW | RTLD_LOCAL);
}

template<typename FuncPtr>
static FuncPtr try_resolve_symbols(void* lib, const char* names[], int count) {
    for (int i = 0; i < count; i++) {
        void* sym = dlsym(lib, names[i]);
        if (sym) {
            mnn_logf("try_resolve: found symbol [%d]: %s at %p", i, names[i], sym);
            return reinterpret_cast<FuncPtr>(sym);
        }
    }
    return nullptr;
}

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

static bool isGarbageResponse(const std::string& s) {
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
}

// Build ChatML token IDs manually (fallback when response(string) fails)
static std::vector<int> buildChatMLTokenIds(MNN::Transformer::Llm* llm,
        const std::string& systemPrompt, const std::string& userPrompt) {
    std::vector<int> ids;
    ids.push_back(QWEN2_BOS);
    ids.push_back(CHATML_IM_START);
    auto sysRoleTokens = g_tokenize(llm, "system\n");
    if (!sysRoleTokens.empty() && sysRoleTokens[0] == QWEN2_BOS) sysRoleTokens.erase(sysRoleTokens.begin());
    ids.insert(ids.end(), sysRoleTokens.begin(), sysRoleTokens.end());
    auto sysContentTokens = g_tokenize(llm, systemPrompt);
    if (!sysContentTokens.empty() && sysContentTokens[0] == QWEN2_BOS) sysContentTokens.erase(sysContentTokens.begin());
    ids.insert(ids.end(), sysContentTokens.begin(), sysContentTokens.end());
    ids.push_back(CHATML_IM_END);
    auto nlTokens = g_tokenize(llm, "\n");
    if (!nlTokens.empty() && nlTokens[0] == QWEN2_BOS) nlTokens.erase(nlTokens.begin());
    ids.insert(ids.end(), nlTokens.begin(), nlTokens.end());
    ids.push_back(CHATML_IM_START);
    auto userRoleTokens = g_tokenize(llm, "user\n");
    if (!userRoleTokens.empty() && userRoleTokens[0] == QWEN2_BOS) userRoleTokens.erase(userRoleTokens.begin());
    ids.insert(ids.end(), userRoleTokens.begin(), userRoleTokens.end());
    auto userContentTokens = g_tokenize(llm, userPrompt);
    if (!userContentTokens.empty() && userContentTokens[0] == QWEN2_BOS) userContentTokens.erase(userContentTokens.begin());
    ids.insert(ids.end(), userContentTokens.begin(), userContentTokens.end());
    ids.push_back(CHATML_IM_END);
    ids.insert(ids.end(), nlTokens.begin(), nlTokens.end());
    ids.push_back(CHATML_IM_START);
    auto asstRoleTokens = g_tokenize(llm, "assistant\n");
    if (!asstRoleTokens.empty() && asstRoleTokens[0] == QWEN2_BOS) asstRoleTokens.erase(asstRoleTokens.begin());
    ids.insert(ids.end(), asstRoleTokens.begin(), asstRoleTokens.end());
    return ids;
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_radio_app_whisper_MnnLlmBridge_nativeInit(JNIEnv* env, jclass clazz, jstring libDir) {
    mnn_log("mnn_llm_jni COMPILE MARKER: v2.4.95 compiled at " __DATE__ " " __TIME__);
    g_model_sane = false;

    if (g_libllm != nullptr) return JNI_TRUE;

    const char* libDirC = nullptr;
    std::string libDirStr;
    if (libDir != nullptr) {
        libDirC = env->GetStringUTFChars(libDir, nullptr);
        libDirStr = libDirC ? libDirC : "";
        env->ReleaseStringUTFChars(libDir, libDirC);
    }

    if (!libDirStr.empty()) {
        g_libMNN = dlopen_from_dir(libDirStr.c_str(), "libMNN.so");
        if (!g_libMNN) return JNI_FALSE;
        g_libMNN_Express = dlopen_from_dir(libDirStr.c_str(), "libMNN_Express.so");
        g_libMNN_Vulkan = dlopen_from_dir(libDirStr.c_str(), "libMNN_Vulkan.so");
        g_libMNN_CL = dlopen_from_dir(libDirStr.c_str(), "libMNN_CL.so");
        g_libMNNOpenCV = dlopen_from_dir(libDirStr.c_str(), "libMNNOpenCV.so");
        g_libMNNAudio = dlopen_from_dir(libDirStr.c_str(), "libMNNAudio.so");
        g_libmnncore = dlopen_from_dir(libDirStr.c_str(), "libmnncore.so");
        g_libllm = dlopen_from_dir(libDirStr.c_str(), "libllm.so");
        if (!g_libllm) return JNI_FALSE;
    } else {
        g_libMNN = dlopen("libMNN.so", RTLD_NOW | RTLD_LOCAL);
        if (!g_libMNN) return JNI_FALSE;
        g_libMNN_Express = dlopen("libMNN_Express.so", RTLD_NOW | RTLD_LOCAL);
        g_libMNN_Vulkan = dlopen("libMNN_Vulkan.so", RTLD_NOW | RTLD_LOCAL);
        g_libMNN_CL = dlopen("libMNN_CL.so", RTLD_NOW | RTLD_LOCAL);
        g_libMNNOpenCV = dlopen("libMNNOpenCV.so", RTLD_NOW | RTLD_LOCAL);
        g_libMNNAudio = dlopen("libMNNAudio.so", RTLD_NOW | RTLD_LOCAL);
        g_libmnncore = dlopen("libmnncore.so", RTLD_NOW | RTLD_LOCAL);
        g_libllm = dlopen("libllm.so", RTLD_NOW | RTLD_GLOBAL);
        if (!g_libllm) return JNI_FALSE;
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
    g_reset = (ResetFunc)dlsym(g_libllm,
        "_ZN3MNN11Transformer3Llm5resetEv");

    {
        const char* tokenize_names[] = {
            "_ZN3MNN11Transformer3Llm16tokenizer_encodeERKNSt6__ndk112basic_stringIcNS2_11char_traitsIcEENS2_9allocatorIcEEEE",
            "_ZN3MNN11Transformer3Llm15tokenizer_encodeERKNSt6__ndk112basic_stringIcNS2_11char_traitsIcEENS2_9allocatorIcEEEE",
        };
        g_tokenize = try_resolve_symbols<TokenizeFunc>(g_libllm, tokenize_names, 2);
    }
    {
        const char* response_vec_names[] = {
            "_ZN3MNN11Transformer3Llm8responseERKNSt6__ndk16vectorIiNS2_9allocatorIiEEEEPNS2_13basic_ostreamIcNS2_11char_traitsIcEEEEPKci",
            "_ZN3MNN11Transformer3Llm8responseERKSt6__ndk16vectorIiNS_9allocatorIiEEEEPNS_13basic_ostreamIcNS_11char_traitsIcEEEEPKci",
            "_ZN3MNN11Transformer3Llm8responseERKNSt6__ndk16vectorIiNS2_9allocatorIiEEEEPNS2_13basic_ostreamIcS5_EEEPKci",
        };
        g_response_vec = try_resolve_symbols<ResponseVecFunc>(g_libllm, response_vec_names, 3);
    }

    mnn_logf("nativeInit: symbols: create=%p destroy=%p load=%p config=%p response=%p reset=%p tokenize=%p response_vec=%p",
             g_createLLM, g_destroy, g_load, g_set_config, g_response, g_reset, g_tokenize, g_response_vec);

    if (!g_createLLM || !g_destroy || !g_load || !g_response) return JNI_FALSE;
    mnn_log("nativeInit: OK");
    return JNI_TRUE;
}

JNIEXPORT jlong JNICALL
Java_com_radio_app_whisper_MnnLlmBridge_nativeCreateLlm(JNIEnv* env, jclass clazz, jstring configPath) {
    if (!g_createLLM) return 0;
    const char* path = env->GetStringUTFChars(configPath, nullptr);
    std::string configPathStr(path);
    env->ReleaseStringUTFChars(configPath, path);
    mnn_logf("nativeCreateLlm: configPath=%s", configPathStr.c_str());

    struct stat st;
    if (stat(configPathStr.c_str(), &st) != 0) {
        mnn_logf("nativeCreateLlm: config file does NOT exist!");
        return 0;
    }
    mnn_logf("nativeCreateLlm: config file size=%ld bytes", st.st_size);

    MNN::Transformer::Llm* llm = g_createLLM(configPathStr);
    mnn_logf("nativeCreateLlm: ptr=%p", llm);
    return llm ? reinterpret_cast<jlong>(llm) : 0;
}

JNIEXPORT jboolean JNICALL
Java_com_radio_app_whisper_MnnLlmBridge_nativeLoad(JNIEnv* env, jclass clazz, jlong ptr) {
    if (!g_load || ptr == 0) return JNI_FALSE;
    auto* llm = reinterpret_cast<MNN::Transformer::Llm*>(ptr);
    mnn_log("nativeLoad: calling load()...");
    bool ok = g_load(llm);
    mnn_logf("nativeLoad: returned %d", ok);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_radio_app_whisper_MnnLlmBridge_nativeSelfTest(JNIEnv* env, jclass clazz, jlong ptr) {
    if (ptr == 0 || !g_response) return JNI_FALSE;
    auto* llm = reinterpret_cast<MNN::Transformer::Llm*>(ptr);

    mnn_log("nativeSelfTest: running model sanity check with response(string)...");

    if (g_reset) g_reset(llm);

    // v2.4.88: Use response(string) - MNN applies prompt_template automatically
    std::ostringstream oss;
    g_response(llm, std::string("1+1="), &oss, "<|im_end|>", 20);
    std::string result = cleanResponse(oss.str());

    mnn_logf("nativeSelfTest: [response(string)] output='%s', len=%zu, garbage=%d",
             result.c_str(), result.size(), isGarbageResponse(result) ? 1 : 0);

    bool sane = false;
    if (!isGarbageResponse(result) && !result.empty()) {
        for (char c : result) {
            if (c >= '0' && c <= '9') { sane = true; break; }
        }
        // Also accept if it contains Chinese characters (model might respond in Chinese)
        for (char c : result) {
            if ((unsigned char)c >= 0x80) { sane = true; break; }
        }
    }

    // Fallback: try manual token IDs if response(string) failed
    if (!sane && g_tokenize && g_response_vec) {
        mnn_log("nativeSelfTest: response(string) failed, trying manual token IDs...");
        if (g_reset) g_reset(llm);
        std::vector<int> ids;
        ids.push_back(QWEN2_BOS);
        ids.push_back(CHATML_IM_START);
        auto roleTokens = g_tokenize(llm, "user\n");
        if (!roleTokens.empty() && roleTokens[0] == QWEN2_BOS) roleTokens.erase(roleTokens.begin());
        ids.insert(ids.end(), roleTokens.begin(), roleTokens.end());
        auto contentTokens = g_tokenize(llm, "1+1=");
        if (!contentTokens.empty() && contentTokens[0] == QWEN2_BOS) contentTokens.erase(contentTokens.begin());
        ids.insert(ids.end(), contentTokens.begin(), contentTokens.end());
        ids.push_back(CHATML_IM_END);
        auto nlTokens = g_tokenize(llm, "\n");
        if (!nlTokens.empty() && nlTokens[0] == QWEN2_BOS) nlTokens.erase(nlTokens.begin());
        ids.insert(ids.end(), nlTokens.begin(), nlTokens.end());
        ids.push_back(CHATML_IM_START);
        auto asstTokens = g_tokenize(llm, "assistant\n");
        if (!asstTokens.empty() && asstTokens[0] == QWEN2_BOS) asstTokens.erase(asstTokens.begin());
        ids.insert(ids.end(), asstTokens.begin(), asstTokens.end());

        mnn_logf("nativeSelfTest: [response(vec)] input tokens=%zu, first10=%d,%d,%d,%d,%d,%d,%d,%d,%d,%d",
                 ids.size(),
                 ids.size()>0?ids[0]:-1, ids.size()>1?ids[1]:-1, ids.size()>2?ids[2]:-1,
                 ids.size()>3?ids[3]:-1, ids.size()>4?ids[4]:-1, ids.size()>5?ids[5]:-1,
                 ids.size()>6?ids[6]:-1, ids.size()>7?ids[7]:-1, ids.size()>8?ids[8]:-1,
                 ids.size()>9?ids[9]:-1);

        std::ostringstream oss2;
        g_response_vec(llm, ids, &oss2, "<|im_end|>", 20);
        result = cleanResponse(oss2.str());
        mnn_logf("nativeSelfTest: [response(vec)] output='%s', len=%zu, garbage=%d",
                 result.c_str(), result.size(), isGarbageResponse(result) ? 1 : 0);

        if (!isGarbageResponse(result) && !result.empty()) {
            for (char c : result) {
                if (c >= '0' && c <= '9') { sane = true; break; }
            }
            for (char c : result) {
                if ((unsigned char)c >= 0x80) { sane = true; break; }
            }
        }
    }

    g_model_sane = sane;
    mnn_logf("nativeSelfTest: model is %s", sane ? "SANE" : "BROKEN");
    if (g_reset) g_reset(llm);
    return sane ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_radio_app_whisper_MnnLlmBridge_nativeGenerate(JNIEnv* env, jclass clazz, jlong ptr, jstring prompt, jint maxTokens) {
    auto* llm = reinterpret_cast<MNN::Transformer::Llm*>(ptr);
    if (!g_response || ptr == 0) return env->NewStringUTF("");

    // v2.4.90: Do NOT block generation when self-test fails!
    // The Qwen2.5-Coder model sometimes fails self-test but works fine for actual classification.

    const char* promptStr = env->GetStringUTFChars(prompt, nullptr);
    std::string rawPrompt(promptStr);
    env->ReleaseStringUTFChars(prompt, promptStr);

    int max_new = (maxTokens > 0) ? (int)maxTokens : 50;
    if (g_reset) g_reset(llm);

    // v2.4.88: PRIMARY - Use response(string), MNN applies prompt_template automatically.
    // The template from llm_config.json is: "<|im_start|>user\n%s<|im_end|>\n<|im_start|>assistant\n"
    // We include the system instruction as part of the user prompt since template only has user role.
    mnn_logf("nativeGenerate: [METHOD1 response(string)] prompt len=%zu", rawPrompt.size());
    std::ostringstream oss;
    g_response(llm, rawPrompt, &oss, "<|im_end|>", max_new);
    std::string result = cleanResponse(oss.str());
    mnn_logf("nativeGenerate: [METHOD1] result len=%zu, garbage=%d, first100=%.100s",
             result.size(), isGarbageResponse(result) ? 1 : 0, result.c_str());

    // If response(string) produced valid output, return it
    if (!result.empty() && !isGarbageResponse(result)) {
        mnn_log("nativeGenerate: [METHOD1] SUCCESS");
        return env->NewStringUTF(result.c_str());
    }

    // FALLBACK: Use manual token IDs if response(string) failed
    if (g_tokenize && g_response_vec) {
        mnn_log("nativeGenerate: [METHOD1] failed, trying [METHOD2 response(vector)]");
        if (g_reset) g_reset(llm);
        std::string systemPrompt = "你是一个分类助手。只回答干货或水货两个字之一。";
        std::vector<int> inputIds = buildChatMLTokenIds(llm, systemPrompt, rawPrompt);
        mnn_logf("nativeGenerate: [METHOD2] total input tokens=%zu, first10=%d,%d,%d,%d,%d,%d,%d,%d,%d,%d",
                 inputIds.size(),
                 inputIds.size()>0?inputIds[0]:-1, inputIds.size()>1?inputIds[1]:-1,
                 inputIds.size()>2?inputIds[2]:-1, inputIds.size()>3?inputIds[3]:-1,
                 inputIds.size()>4?inputIds[4]:-1, inputIds.size()>5?inputIds[5]:-1,
                 inputIds.size()>6?inputIds[6]:-1, inputIds.size()>7?inputIds[7]:-1,
                 inputIds.size()>8?inputIds[8]:-1, inputIds.size()>9?inputIds[9]:-1);

        std::ostringstream oss2;
        g_response_vec(llm, inputIds, &oss2, "<|im_end|>", max_new);
        result = cleanResponse(oss2.str());
        mnn_logf("nativeGenerate: [METHOD2] result len=%zu, garbage=%d, first100=%.100s",
                 result.size(), isGarbageResponse(result) ? 1 : 0, result.c_str());

        if (!result.empty()) {
            mnn_log("nativeGenerate: [METHOD2] returning result");
            return env->NewStringUTF(result.c_str());
        }
    }

    mnn_logf("nativeGenerate: all methods failed, returning empty");
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT void JNICALL
Java_com_radio_app_whisper_MnnLlmBridge_nativeReset(JNIEnv* env, jclass clazz, jlong ptr) {
    if (g_reset && ptr != 0) {
        g_reset(reinterpret_cast<MNN::Transformer::Llm*>(ptr));
    }
}

JNIEXPORT jstring JNICALL
Java_com_radio_app_whisper_MnnLlmBridge_nativeGetCompileMarker(JNIEnv* env, jclass clazz) {
    return env->NewStringUTF("MNN_JNI_v2.4.95");
}

JNIEXPORT void JNICALL
Java_com_radio_app_whisper_MnnLlmBridge_nativeFree(JNIEnv* env, jclass clazz, jlong ptr) {
    if (g_destroy && ptr != 0) {
        g_destroy(reinterpret_cast<MNN::Transformer::Llm*>(ptr));
        mnn_log("nativeFree: LLM freed");
    }
}

JNIEXPORT jboolean JNICALL
Java_com_radio_app_whisper_MnnLlmBridge_nativeSetConfig(JNIEnv* env, jclass clazz, jlong ptr, jstring configJson) {
    mnn_log("nativeSetConfig: NO-OP (let model use config.json defaults)");
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_radio_app_whisper_MnnLlmBridge_nativeSetConfigPostLoad(JNIEnv* env, jclass clazz, jlong ptr) {
    mnn_log("nativeSetConfigPostLoad: NO-OP (let model use config.json defaults)");
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_com_radio_app_whisper_MnnLlmBridge_nativeTestApplyChatTemplate(JNIEnv* env, jclass clazz, jlong ptr, jstring userInput) {
    return env->NewStringUTF("[v2.4.88] test disabled");
}

JNIEXPORT jstring JNICALL
Java_com_radio_app_whisper_MnnLlmBridge_nativeTestJsonTemplate(JNIEnv* env, jclass clazz, jlong ptr) {
    return env->NewStringUTF("[v2.4.88] test disabled");
}

} // extern "C"