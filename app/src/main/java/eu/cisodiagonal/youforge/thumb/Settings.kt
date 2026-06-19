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
        const val DEFAULT_MODEL_URL =
            "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/" +
                "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task"
    }
}
