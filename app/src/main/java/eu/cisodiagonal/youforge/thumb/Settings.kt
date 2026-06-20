package eu.cisodiagonal.youforge.thumb

import android.content.Context

/** Tiny SharedPreferences wrapper for the configurable model download URL. */
class Settings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("thumbforge", Context.MODE_PRIVATE)

    var modelUrl: String
        get() = prefs.getString(KEY_MODEL_URL, DEFAULT_MODEL_URL) ?: DEFAULT_MODEL_URL
        set(value) = prefs.edit().putString(KEY_MODEL_URL, value.trim()).apply()

    /** The user's saved "brand kit" title style, or null if none saved. */
    fun brandKit(): StylePreset? {
        if (!prefs.getBoolean(KEY_BRAND_SET, false)) return null
        return StylePreset(
            name = "My style",
            effect = TextEffect.from(prefs.getString(KEY_BRAND_EFFECT, null)),
            titleColor = prefs.getInt(KEY_BRAND_TCOLOR, 0xFFFFEC3D.toInt()),
            glowColor = prefs.getInt(KEY_BRAND_GCOLOR, 0xFF2D7A.toInt()),
            position = Position.from(prefs.getString(KEY_BRAND_POS, null))
        )
    }

    fun saveBrandKit(s: OverlaySpec) = prefs.edit()
        .putBoolean(KEY_BRAND_SET, true)
        .putString(KEY_BRAND_EFFECT, s.effect.id)
        .putInt(KEY_BRAND_TCOLOR, s.titleColor)
        .putInt(KEY_BRAND_GCOLOR, s.glowColor)
        .putString(KEY_BRAND_POS, s.position.id)
        .apply()

    fun clearBrandKit() = prefs.edit().putBoolean(KEY_BRAND_SET, false).apply()

    companion object {
        private const val KEY_MODEL_URL = "model_url"
        private const val KEY_BRAND_SET = "brand_set"
        private const val KEY_BRAND_EFFECT = "brand_effect"
        private const val KEY_BRAND_TCOLOR = "brand_tcolor"
        private const val KEY_BRAND_GCOLOR = "brand_gcolor"
        private const val KEY_BRAND_POS = "brand_pos"

        // Ungated, no-login MediaPipe .task: Qwen2.5-1.5B-Instruct (Apache-2.0, ~1.6 GB).
        val DEFAULT_MODEL_URL = SuggestedModels.all.first().url
    }
}

/** A downloadable on-device model. [url] is an ungated MediaPipe `.task` unless noted. */
data class SuggestedModel(
    val slug: String,
    val name: String,
    val size: String,
    val note: String,
    val url: String,
    val gated: Boolean = false
)

/**
 * Curated LiteRT-Community `.task` models for mid-range Android devices: keep to
 * small instruct models — the renderer makes the thumbnail, the model only writes
 * the title JSON, so a 0.5–1.5B q8 is plenty.
 *
 * Every entry below is an **ungated** repo whose `resolve/` download works with no
 * Hugging Face login (verified 302→CDN). Gemma3 / Llama-3.2 / Qwen-3B were dropped
 * from the list because their downloads are gated (HF returns 401 without an
 * accepted-license token) — they can still be added via the custom-URL field or
 * the "Pick local .task file" import after downloading them on a logged-in machine.
 * URLs stay editable, so if a filename ever changes the download just reports the
 * failure (non-fatal).
 */
object SuggestedModels {
    private const val BASE = "https://huggingface.co/litert-community"

    val all: List<SuggestedModel> = listOf(
        SuggestedModel(
            "qwen2_5-1_5b", "Qwen2.5-1.5B-Instruct", "~1.6 GB", "Default · balanced · no login",
            "$BASE/Qwen2.5-1.5B-Instruct/resolve/main/" +
                "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task"
        ),
        SuggestedModel(
            "qwen2_5-0_5b", "Qwen2.5-0.5B-Instruct", "~0.55 GB", "Tiny · fastest · no login",
            "$BASE/Qwen2.5-0.5B-Instruct/resolve/main/" +
                "Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task"
        ),
        SuggestedModel(
            "tinyllama-1_1b", "TinyLlama-1.1B-Chat", "~1.15 GB", "Small · snappy · no login",
            "$BASE/TinyLlama-1.1B-Chat-v1.0/resolve/main/" +
                "TinyLlama-1.1B-Chat-v1.0_multi-prefill-seq_q8_ekv1280.task"
        )
        // NB: DeepSeek-R1-Distill is intentionally omitted — it is a reasoning
        // model that emits <think>…</think> spans, which derails the short-title
        // JSON the renderer expects. Instruct models only here.
    )

    fun bySlug(slug: String): SuggestedModel? = all.firstOrNull { it.slug == slug }
}
