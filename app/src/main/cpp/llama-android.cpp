// Minimal JNI wrapper over llama.cpp for YouForge's GGUF backend (P0 spike).
// Stateless: load model -> generate -> free on each call. Keep-warm + sampler
// tuning + GBNF JSON grammar come in P1/P2. Mirrors examples/simple of the
// pinned llama.cpp commit.
#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include "llama.h"

#define LOG_TAG "yf-llama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C"
JNIEXPORT jstring JNICALL
Java_eu_cisodiagonal_youforge_thumb_LlamaBridge_nativeGenerate(
        JNIEnv *env, jobject /*thiz*/, jstring jModelPath, jstring jPrompt, jint jnPredict) {

    const char *cModelPath = env->GetStringUTFChars(jModelPath, nullptr);
    const char *cPrompt = env->GetStringUTFChars(jPrompt, nullptr);
    std::string modelPath(cModelPath);
    std::string prompt(cPrompt);
    env->ReleaseStringUTFChars(jModelPath, cModelPath);
    env->ReleaseStringUTFChars(jPrompt, cPrompt);

    const int n_predict = jnPredict > 0 ? jnPredict : 64;
    std::string out;

    ggml_backend_load_all();

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0; // CPU only for the spike

    llama_model *model = llama_model_load_from_file(modelPath.c_str(), model_params);
    if (model == nullptr) {
        LOGE("failed to load model: %s", modelPath.c_str());
        return env->NewStringUTF("");
    }
    const llama_vocab *vocab = llama_model_get_vocab(model);

    // tokenize prompt
    const int n_prompt = -llama_tokenize(vocab, prompt.c_str(), prompt.size(), nullptr, 0, true, true);
    std::vector<llama_token> prompt_tokens(n_prompt);
    if (llama_tokenize(vocab, prompt.c_str(), prompt.size(), prompt_tokens.data(),
                       prompt_tokens.size(), true, true) < 0) {
        LOGE("tokenize failed");
        llama_model_free(model);
        return env->NewStringUTF("");
    }

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = n_prompt + n_predict;
    ctx_params.n_batch = n_prompt > 0 ? n_prompt : 1;
    llama_context *ctx = llama_init_from_model(model, ctx_params);
    if (ctx == nullptr) {
        LOGE("failed to create context");
        llama_model_free(model);
        return env->NewStringUTF("");
    }

    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());

    llama_batch batch = llama_batch_get_one(prompt_tokens.data(), prompt_tokens.size());
    int n_decode = 0;
    for (int n_pos = 0; n_pos + batch.n_tokens < n_prompt + n_predict;) {
        if (llama_decode(ctx, batch)) {
            LOGE("decode failed");
            break;
        }
        n_pos += batch.n_tokens;

        llama_token new_token_id = llama_sampler_sample(smpl, ctx, -1);
        if (llama_vocab_is_eog(vocab, new_token_id)) break;

        char buf[256];
        int n = llama_token_to_piece(vocab, new_token_id, buf, sizeof(buf), 0, true);
        if (n < 0) break;
        out.append(buf, n);

        batch = llama_batch_get_one(&new_token_id, 1);
        n_decode++;
    }

    LOGI("generated %d tokens, %zu chars", n_decode, out.size());

    llama_sampler_free(smpl);
    llama_free(ctx);
    llama_model_free(model);

    return env->NewStringUTF(out.c_str());
}
