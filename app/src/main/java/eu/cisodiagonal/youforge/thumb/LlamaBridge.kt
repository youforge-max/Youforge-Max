package eu.cisodiagonal.youforge.thumb

/**
 * JNI bridge to the vendored llama.cpp (GGUF on-device inference). P0 spike:
 * a single stateless [nativeGenerate]. Keep-warm, sampler tuning and a GBNF
 * JSON grammar follow in P1/P2. The native lib is `liblama-android.so`,
 * built from app/src/main/cpp via CMake/NDK.
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

    /** Greedy-decode [nPredict] tokens from [prompt] using the GGUF at [modelPath]. */
    external fun nativeGenerate(modelPath: String, prompt: String, nPredict: Int): String
}
