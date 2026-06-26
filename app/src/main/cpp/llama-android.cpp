// JNI wrapper over llama.cpp for YouForge's GGUF backend.
// P2: keep-warm — the model + context + sampler are loaded once and reused
// across calls (KV memory cleared per generation), instead of reloading the
// whole model every time. A GBNF grammar to constrain the JSON comes next.
#include <jni.h>
#include <android/log.h>
#include <mutex>
#include <string>
#include <vector>
#include "llama.h"

#define LOG_TAG "yf-llama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
    std::mutex g_mutex;
    llama_model   *g_model = nullptr;
    llama_context *g_ctx   = nullptr;
    const llama_vocab *g_vocab = nullptr;
    llama_sampler *g_smpl  = nullptr;
    std::string    g_path;

    constexpr int N_CTX = 2048;

    // GBNF grammar that forces the exact OverlaySpec JSON the renderer parses:
    // all 9 keys in order, title constrained to UPPERCASE (small models otherwise
    // ignore the "uppercase ≤4-word" instruction), position/effect locked to the
    // enums OverlaySpec accepts, colours to #RRGGBB. Guarantees parseable output.
    const char *GRAMMAR = R"GBNF(
root ::= "{" ws "\"title\":" ws "\"" [A-Z0-9 !?.,'&]{1,40} "\"" "," ws "\"subtitle\":" ws str "," ws "\"title_color\":" ws hex "," ws "\"stroke_color\":" ws hex "," ws "\"position\":" ws pos "," ws "\"mood\":" ws str "," ws "\"accent\":" ws str "," ws "\"effect\":" ws eff "," ws "\"glow_color\":" ws hex ws "}"
str ::= "\"" [^"\\]{0,60} "\""
hex ::= "\"#" [0-9a-fA-F]{6} "\""
pos ::= "\"upper-left\"" | "\"upper-center\"" | "\"upper-right\"" | "\"center\"" | "\"lower-left\"" | "\"lower-center\"" | "\"lower-right\""
eff ::= "\"shadow\"" | "\"outline\"" | "\"glow\"" | "\"neon\"" | "\"gradient\"" | "\"pop\"" | "\"plain\""
ws ::= [ \t\n]*
)GBNF";

    void freeAll() {
        if (g_smpl)  { llama_sampler_free(g_smpl); g_smpl = nullptr; }
        if (g_ctx)   { llama_free(g_ctx);          g_ctx = nullptr; }
        if (g_model) { llama_model_free(g_model);  g_model = nullptr; }
        g_vocab = nullptr;
        g_path.clear();
    }
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_eu_youforgemax_youforge_thumb_LlamaBridge_nativeLoad(
        JNIEnv *env, jobject, jstring jPath, jint jThreads) {
    std::lock_guard<std::mutex> lock(g_mutex);
    const char *cPath = env->GetStringUTFChars(jPath, nullptr);
    std::string path(cPath);
    env->ReleaseStringUTFChars(jPath, cPath);

    if (g_model != nullptr && g_path == path) return JNI_TRUE; // already warm
    freeAll();

    ggml_backend_load_all();

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0; // CPU
    g_model = llama_model_load_from_file(path.c_str(), mp);
    if (!g_model) { LOGE("load failed: %s", path.c_str()); return JNI_FALSE; }
    g_vocab = llama_model_get_vocab(g_model);

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx   = N_CTX;
    cp.n_batch = N_CTX;
    if (jThreads > 0) { cp.n_threads = jThreads; cp.n_threads_batch = jThreads; }
    g_ctx = llama_init_from_model(g_model, cp);
    if (!g_ctx) { LOGE("ctx failed"); freeAll(); return JNI_FALSE; }

    g_smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    // Grammar first (constrains the logits to the JSON schema), then greedy.
    llama_sampler *gr = llama_sampler_init_grammar(g_vocab, GRAMMAR, "root");
    if (gr) llama_sampler_chain_add(g_smpl, gr);
    else LOGE("grammar failed to parse — falling back to unconstrained decode");
    llama_sampler_chain_add(g_smpl, llama_sampler_init_greedy());

    g_path = path;
    LOGI("model loaded (warm): %s", path.c_str());
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_eu_youforgemax_youforge_thumb_LlamaBridge_nativeGenerate(
        JNIEnv *env, jobject, jstring jPrompt, jint jnPredict) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_ctx || !g_model) return env->NewStringUTF("");

    const char *cPrompt = env->GetStringUTFChars(jPrompt, nullptr);
    std::string prompt(cPrompt);
    env->ReleaseStringUTFChars(jPrompt, cPrompt);
    const int n_predict = jnPredict > 0 ? jnPredict : 128;
    std::string out;

    // Reset the KV memory so each request starts from a clean context.
    llama_memory_clear(llama_get_memory(g_ctx), true);
    llama_sampler_reset(g_smpl);

    const int n_prompt = -llama_tokenize(g_vocab, prompt.c_str(), prompt.size(), nullptr, 0, true, true);
    std::vector<llama_token> tokens(n_prompt);
    if (llama_tokenize(g_vocab, prompt.c_str(), prompt.size(), tokens.data(), tokens.size(), true, true) < 0) {
        LOGE("tokenize failed");
        return env->NewStringUTF("");
    }
    if (n_prompt + n_predict > N_CTX) {
        LOGE("prompt too long: %d + %d > %d", n_prompt, n_predict, N_CTX);
    }

    llama_batch batch = llama_batch_get_one(tokens.data(), tokens.size());
    int n_decode = 0;
    for (int n_pos = 0; n_pos + batch.n_tokens < n_prompt + n_predict;) {
        if (llama_decode(g_ctx, batch)) { LOGE("decode failed"); break; }
        n_pos += batch.n_tokens;

        llama_token id = llama_sampler_sample(g_smpl, g_ctx, -1);
        if (llama_vocab_is_eog(g_vocab, id)) break;

        char buf[256];
        int n = llama_token_to_piece(g_vocab, id, buf, sizeof(buf), 0, true);
        if (n < 0) break;
        out.append(buf, n);

        batch = llama_batch_get_one(&id, 1);
        n_decode++;
    }
    LOGI("generated %d tokens, %zu chars", n_decode, out.size());
    return env->NewStringUTF(out.c_str());
}

extern "C"
JNIEXPORT void JNICALL
Java_eu_youforgemax_youforge_thumb_LlamaBridge_nativeFree(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    freeAll();
}
