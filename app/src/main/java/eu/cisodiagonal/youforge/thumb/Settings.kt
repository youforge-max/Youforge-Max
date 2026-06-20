package eu.cisodiagonal.youforge.thumb

import android.content.Context

/** Tiny SharedPreferences wrapper for the configurable model download URL. */
class Settings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("thumbforge", Context.MODE_PRIVATE)

    var modelUrl: String
        get() = prefs.getString(KEY_MODEL_URL, DEFAULT_MODEL_URL) ?: DEFAULT_MODEL_URL
        set(value) = prefs.edit().putString(KEY_MODEL_URL, value.trim()).apply()

    companion object {
        private const val KEY_MODEL_URL = "model_url"

        // Ungated, no-login MediaPipe .task: Qwen2.5-1.5B-Instruct (Apache-2.0, ~1.6 GB).
        val DEFAULT_MODEL_URL = SuggestedModels.all.first().url
    }
}

/** A downloadable on-device model. [url] is an ungated MediaPipe `.task` unless noted. */
data class SuggestedModel(
    val name: String,
    val size: String,
    val note: String,
    val url: String,
    val gated: Boolean = false
)

/**
 * Curated LiteRT-Community `.task` models for the Tab S7+ (SD865+/8GB): keep to
 * small instruct models — the renderer makes the thumbnail, the model only writes
 * the title JSON, so a 1–3B int4/q8 is plenty. URLs stay editable, so if a
 * filename ever changes the download just reports the failure (non-fatal).
 */
object SuggestedModels {
    private const val BASE = "https://huggingface.co/litert-community"

    val all: List<SuggestedModel> = listOf(
        SuggestedModel(
            "Qwen2.5-1.5B-Instruct", "~1.6 GB", "Default · fast · no login",
            "$BASE/Qwen2.5-1.5B-Instruct/resolve/main/" +
                "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task"
        ),
        SuggestedModel(
            "Gemma3-1B-IT", "~1.0 GB", "Smallest · newest · suggested upgrade",
            "$BASE/Gemma3-1B-IT/resolve/main/" +
                "Gemma3-1B-IT_multi-prefill-seq_q8_ekv1280.task"
        ),
        SuggestedModel(
            "Llama-3.2-1B-Instruct", "~1.2 GB", "Tiny · snappy",
            "$BASE/Llama-3.2-1B-Instruct/resolve/main/" +
                "Llama-3.2-1B-Instruct_multi-prefill-seq_q8_ekv1280.task"
        ),
        SuggestedModel(
            "Qwen2.5-3B-Instruct", "~3.2 GB", "Smarter titles · slower",
            "$BASE/Qwen2.5-3B-Instruct/resolve/main/" +
                "Qwen2.5-3B-Instruct_multi-prefill-seq_q8_ekv1280.task"
        ),
        SuggestedModel(
            "Llama-3.2-3B-Instruct", "~3.4 GB", "Best quality · heaviest",
            "$BASE/Llama-3.2-3B-Instruct/resolve/main/" +
                "Llama-3.2-3B-Instruct_multi-prefill-seq_q8_ekv1280.task"
        )
    )
}
