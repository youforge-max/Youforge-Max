package eu.youforgemax.youforge.thumb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * On-device LLM provider backed by llama.cpp (GGUF), via the native [LlamaBridge].
 * Counterpart to [OnDeviceLlm] (MediaPipe `.task`). Keep-warm: [nativeLoad] caches
 * the model+context (idempotent per path), so repeat suggests skip the reload;
 * [close] (called by ModelManager on model switch) frees the native handle.
 * A GBNF grammar to constrain the JSON lands in P2b.
 */
class LlamaCppEngine(private val modelFile: File) : AiProvider {

    override suspend fun suggest(description: String): OverlaySpec = withContext(Dispatchers.IO) {
        if (!LlamaBridge.ensureLoaded()) {
            throw IllegalStateException("GGUF backend not available on this device")
        }
        if (!LlamaBridge.nativeLoad(modelFile.absolutePath, threadCount())) {
            throw IllegalStateException("Could not load GGUF model")
        }
        val raw = LlamaBridge.nativeGenerate(Prompts.overlay(description), N_PREDICT)
        if (raw.isBlank()) throw IllegalStateException("Model returned no output")
        OverlaySpec.parse(raw)
            ?: throw IllegalStateException("Model returned no usable JSON")
    }

    companion object {
        // Enough headroom for the title JSON object; titles are short.
        private const val N_PREDICT = 160

        /** A sensible CPU thread count for inference on a mid-range tablet. */
        private fun threadCount(): Int =
            Runtime.getRuntime().availableProcessors().coerceIn(2, 6)

        /** Free the cached native model+context (on model switch / teardown). */
        fun close() {
            if (LlamaBridge.ensureLoaded()) LlamaBridge.nativeFree()
        }
    }
}
