package eu.cisodiagonal.youforge.thumb

/**
 * JNI bridge to the vendored llama.cpp (GGUF on-device inference). Keep-warm:
 * [nativeLoad] caches the model+context (idempotent per path), [nativeGenerate]
 * reuses them (KV memory cleared per call), [nativeFree] releases them. The
 * native lib is `libllama-android.so`, built from app/src/main/cpp via CMake/NDK.
 */
object LlamaBridge {
    @Volatile private var loaded = false

    /** Loads the native library; returns false if unavailable on this ABI. */
    @Synchronized
    fun ensureLoaded(): Boolean {
        if (loaded) return true
        return try {
            System.loadLibrary("llama-android")
            loaded = true
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** Load + cache the GGUF at [modelPath] (no-op if already warm for that path). */
    external fun nativeLoad(modelPath: String, nThreads: Int): Boolean

    /** Greedy-decode up to [nPredict] tokens from [prompt] using the loaded model. */
    external fun nativeGenerate(prompt: String, nPredict: Int): String

    /** Release the cached model + context. */
    external fun nativeFree()
}
