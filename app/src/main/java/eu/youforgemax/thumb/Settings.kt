package eu.youforgemax.thumb

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

/** On-device model file format → which inference engine runs it. */
enum class ModelFormat(val ext: String) {
    /** MediaPipe GenAI LlmInference bundle. */
    TASK("task"),
    /** llama.cpp GGUF (via the native LlamaBridge). */
    GGUF("gguf")
}

/**
 * A downloadable on-device model. [url] is an ungated model file unless noted.
 * [format] picks the engine (MediaPipe `.task` vs llama.cpp `.gguf`).
 * [sha256] is the expected lower-case hex digest of the file (HF LFS oid); when set
 * the download is rejected on mismatch. Null (e.g. custom URLs) = skip verification.
 */
data class SuggestedModel(
    val slug: String,
    val name: String,
    val size: String,
    val note: String,
    val url: String,
    val gated: Boolean = false,
    val sha256: String? = null,
    val format: ModelFormat = ModelFormat.TASK,
    /** Approx download size in MB (for the "Download all" size estimate). */
    val approxMb: Int = 0,
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
                "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            sha256 = "8d867a7c93a6acf2892f08e0174e2f6f351ad256b7e3cfb6d6cd9c89794b42e0", approxMb = 1600
        ),
        SuggestedModel(
            "qwen2_5-0_5b", "Qwen2.5-0.5B-Instruct", "~0.55 GB", "Tiny · fastest · no login",
            "$BASE/Qwen2.5-0.5B-Instruct/resolve/main/" +
                "Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            sha256 = "e608953f169aeb1bd7b9155fec2559825e08453fc209b84eda3a781ed0452fd2", approxMb = 550
        ),
        SuggestedModel(
            "tinyllama-1_1b", "TinyLlama-1.1B-Chat", "~1.15 GB", "Small · snappy · no login",
            "$BASE/TinyLlama-1.1B-Chat-v1.0/resolve/main/" +
                "TinyLlama-1.1B-Chat-v1.0_multi-prefill-seq_q8_ekv1280.task",
            sha256 = "0f09dc7f792bb8d49b6629effaee3ed1a99e4506b082cd353471bdf391dee053", approxMb = 1150
        )
        // NB: DeepSeek-R1-Distill is intentionally omitted — it is a reasoning
        // model that emits <think>…</think> spans, which derails the short-title
        // JSON the renderer expects. Instruct models only here.
        ,
        // --- GGUF models (llama.cpp backend) — ungated Qwen official GGUF repos.
        // Smaller q4_k_m files than the .task q8 above; opens the wider GGUF
        // ecosystem (any ungated HF GGUF works via the custom-URL field too).
        SuggestedModel(
            "gguf-qwen2_5-0_5b", "Qwen2.5-0.5B-Instruct (GGUF)", "~0.47 GB",
            "GGUF · tiny · fastest · no login",
            "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/" +
                "qwen2.5-0.5b-instruct-q4_k_m.gguf",
            sha256 = "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db",
            approxMb = 491,
            format = ModelFormat.GGUF
        ),
        SuggestedModel(
            "gguf-qwen2_5-1_5b", "Qwen2.5-1.5B-Instruct (GGUF)", "~1.1 GB",
            "GGUF · balanced · no login",
            "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/" +
                "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            sha256 = "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e",
            approxMb = 1117,
            format = ModelFormat.GGUF
        ),
        // --- Larger, stronger instruct models for sharper titles. The 0.5–1.5B models
        // above can write junk; these 2–3B models follow the "short punchy hook" prompt
        // much better. All ungated (verified 302 → CDN, no login).
        SuggestedModel(
            "gguf-qwen2_5-3b", "Qwen2.5-3B-Instruct (GGUF)", "~2.1 GB",
            "GGUF · best quality · recommended · no login",
            "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/" +
                "qwen2.5-3b-instruct-q4_k_m.gguf",
            sha256 = "626b4a6678b86442240e33df819e00132d3ba7dddfe1cdc4fbb18e0a9615c62d",
            approxMb = 2105,
            format = ModelFormat.GGUF
        ),
        SuggestedModel(
            "gguf-llama3_2-3b", "Llama-3.2-3B-Instruct (GGUF)", "~2.0 GB",
            "GGUF · strong · no login",
            "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/" +
                "Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            sha256 = "6c1a2b41161032677be168d354123594c0e6e67d2b9227c84f296ad037c728ff",
            approxMb = 2019,
            format = ModelFormat.GGUF
        ),
        SuggestedModel(
            "gguf-gemma2-2b", "Gemma-2-2B-it (GGUF)", "~1.7 GB",
            "GGUF · Google · balanced · no login",
            "https://huggingface.co/bartowski/gemma-2-2b-it-GGUF/resolve/main/" +
                "gemma-2-2b-it-Q4_K_M.gguf",
            sha256 = "e0aee85060f168f0f2d8473d7ea41ce2f3230c1bc1374847505ea599288a7787",
            approxMb = 1709,
            format = ModelFormat.GGUF
        )
    )

    fun bySlug(slug: String): SuggestedModel? = all.firstOrNull { it.slug == slug }
}
