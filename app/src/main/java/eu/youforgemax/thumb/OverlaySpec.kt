package eu.youforgemax.thumb

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

    /** Approx normalised centre of the title block for this anchor — used to seed
     *  free-drag so the title doesn't jump on first touch. */
    fun approxCenter(): Pair<Float, Float> {
        val x = when (this) {
            UPPER_LEFT, LOWER_LEFT -> 0.30f
            UPPER_RIGHT, LOWER_RIGHT -> 0.70f
            else -> 0.50f
        }
        val y = when (this) {
            UPPER_LEFT, UPPER_CENTER, UPPER_RIGHT -> 0.22f
            CENTER -> 0.50f
            else -> 0.78f
        }
        return x to y
    }

    companion object {
        fun from(s: String?): Position =
            entries.firstOrNull { it.id.equals(s?.trim(), ignoreCase = true) } ?: LOWER_LEFT
    }
}

/** Title rendering style. The renderer draws a different layer stack per effect. */
enum class TextEffect(val id: String, val label: String) {
    SHADOW("shadow", "Shadow"),
    OUTLINE("outline", "Bold outline"),
    GLOW("glow", "Glow"),
    NEON("neon", "Neon"),
    GRADIENT("gradient", "Gradient"),
    POP("pop", "Pop (white + stroke)"),
    PLAIN("plain", "Plain");

    companion object {
        fun from(s: String?): TextEffect =
            entries.firstOrNull { it.id.equals(s?.trim(), ignoreCase = true) } ?: GLOW
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
    val accent: String = "",       // single emoji, optional
    val effect: TextEffect = TextEffect.GLOW,
    val glowColor: Int = Color.parseColor("#FF2D7A"),   // halo/accent for glow & neon
    // --- Free transform (manual, not produced by the LLM) ---
    /** Rotation in degrees, clockwise, about the title block centre. */
    val rotation: Float = 0f,
    /** Manual centre (0..1) of the title block. When non-null, overrides [position]. */
    val freeX: Float? = null,
    val freeY: Float? = null,
    /** Manual size multiplier applied on top of the auto-fit size. */
    val titleScale: Float = 1f
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
                    accent = o.optString("accent").trim().take(4),
                    effect = TextEffect.from(o.optString("effect")),
                    glowColor = color(o.optString("glow_color"), Color.parseColor("#FF2D7A"))
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
