package eu.cisodiagonal.youforge.thumb

import android.graphics.Color
import org.json.JSONObject

/** Where the text block sits on the 1280x720 canvas. */
enum class Position(val id: String) {
    UPPER_LEFT("upper-left"),
    UPPER_CENTER("upper-center"),
    UPPER_RIGHT("upper-right"),
    CENTER("center"),
    LOWER_LEFT("lower-left"),
    LOWER_CENTER("lower-center"),
    LOWER_RIGHT("lower-right");

    companion object {
        fun from(s: String?): Position =
            entries.firstOrNull { it.id.equals(s?.trim(), ignoreCase = true) } ?: LOWER_LEFT
    }
}

/**
 * The structured overlay the renderer draws onto the photo. Produced either by
 * the on-device LLM ([OnDeviceLlm]) or the offline [TemplateProvider].
 */
data class OverlaySpec(
    val title: String,
    val subtitle: String = "",
    val titleColor: Int = Color.parseColor("#FFEC3D"),
    val strokeColor: Int = Color.BLACK,
    val position: Position = Position.LOWER_LEFT,
    val mood: String = "",
    val accent: String = ""        // single emoji, optional
) {
    companion object {
        private fun color(hex: String?, fallback: Int): Int =
            try {
                if (hex.isNullOrBlank()) fallback else Color.parseColor(hex.trim())
            } catch (_: Exception) {
                fallback
            }

        /** Tolerant parse: pulls the first {...} block out of arbitrary LLM output. */
        fun parse(raw: String): OverlaySpec? {
            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            return try {
                val o = JSONObject(raw.substring(start, end + 1))
                val title = o.optString("title").trim()
                if (title.isEmpty()) return null
                OverlaySpec(
                    title = title.take(60),
                    subtitle = o.optString("subtitle").trim().take(80),
                    titleColor = color(o.optString("title_color"), Color.parseColor("#FFEC3D")),
                    strokeColor = color(o.optString("stroke_color"), Color.BLACK),
                    position = Position.from(o.optString("position")),
                    mood = o.optString("mood").trim().take(24),
                    accent = o.optString("accent").trim().take(4)
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
