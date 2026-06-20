package eu.cisodiagonal.youforge.thumb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * On-device LLM provider backed by llama.cpp (GGUF), via the native [LlamaBridge].
 * Counterpart to [OnDeviceLlm] (MediaPipe `.task`). P1: stateless — the native
 * call loads the model, generates, and frees each time. Keep-warm + a GBNF JSON
 * grammar to constrain the output land in P2.
 */
class LlamaCppEngine(private val modelFile: File) : AiProvider {

    override suspend fun suggest(description: String): OverlaySpec = withContext(Dispatchers.IO) {
        if (!LlamaBridge.ensureLoaded()) {
            throw IllegalStateException("GGUF backend not available on this device")
        }
        val raw = LlamaBridge.nativeGenerate(modelFile.absolutePath, Prompts.overlay(description), N_PREDICT)
        if (raw.isBlank()) throw IllegalStateException("Model returned no output")
        OverlaySpec.parse(raw)
            ?: throw IllegalStateException("Model returned no usable JSON")
    }

    companion object {
        // Enough headroom for the title JSON object; titles are short.
        private const val N_PREDICT = 160

        /** No cached native state yet (stateless P1); here to match the engine lifecycle. */
        fun close() { /* no-op until keep-warm (P2) */ }
    }
}
