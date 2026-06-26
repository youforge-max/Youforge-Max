package eu.youforgemax.youforge.thumb

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * On-device LLM provider backed by MediaPipe's GenAI LLM Inference API, running a
 * Gemma-2 2B (.task) model entirely on the tablet. Loaded lazily on first use and
 * kept warm. If no model is present this provider is not used — the UI falls back
 * to [TemplateProvider].
 */
class OnDeviceLlm(
    private val context: Context,
    private val modelFile: File
) : AiProvider {

    override suspend fun suggest(description: String): OverlaySpec = withContext(Dispatchers.IO) {
        val engine = ensureLoaded(context, modelFile)
        val raw = engine.generateResponse(Prompts.overlay(description))
        OverlaySpec.parse(raw)
            ?: throw IllegalStateException("Model returned no usable JSON")
    }

    companion object {
        @Volatile private var engine: LlmInference? = null
        @Volatile private var loadedPath: String? = null

        @Synchronized
        private fun ensureLoaded(context: Context, modelFile: File): LlmInference {
            val existing = engine
            if (existing != null && loadedPath == modelFile.absolutePath) return existing
            existing?.close()

            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                .setMaxTokens(512)
                .setMaxTopK(40)
                .build()
            val created = LlmInference.createFromOptions(context.applicationContext, options)
            engine = created
            loadedPath = modelFile.absolutePath
            return created
        }

        @Synchronized
        fun close() {
            engine?.close()
            engine = null
            loadedPath = null
        }
    }
}
