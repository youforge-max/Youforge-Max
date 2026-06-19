package eu.cisodiagonal.youforge.thumb

import android.graphics.Color

/**
 * Offline fallback. No LLM, no network — pure keyword heuristics. Always available
 * so the app is useful before the model is downloaded (and when the LLM fails).
 */
object TemplateProvider : AiProvider {

    private val STOP = setOf(
        "the", "a", "an", "and", "or", "of", "to", "in", "on", "with", "for", "my", "me",
        "i", "is", "are", "at", "it", "this", "that", "title", "video", "thumbnail", "about"
    )

    private data class Mood(
        val keys: List<String>, val title: Int, val stroke: Int, val mood: String,
        val effect: TextEffect, val glow: Int
    )

    private val MOODS = listOf(
        Mood(listOf("moody", "dark", "night", "storm", "cold", "alone", "scary", "danger"),
            Color.parseColor("#FFEC3D"), Color.BLACK, "moody",
            TextEffect.GLOW, Color.parseColor("#FF2D7A")),
        Mood(listOf("bright", "sunny", "summer", "happy", "fun", "beach", "warm"),
            Color.WHITE, Color.parseColor("#0A6CC2"), "bright",
            TextEffect.OUTLINE, Color.parseColor("#FFD83D")),
        Mood(listOf("nature", "forest", "river", "wild", "camp", "mountain", "green"),
            Color.parseColor("#B6FF3D"), Color.parseColor("#0E2B12"), "nature",
            TextEffect.GRADIENT, Color.parseColor("#3DFFB0")),
        Mood(listOf("epic", "extreme", "insane", "crazy", "record", "fastest", "biggest"),
            Color.parseColor("#FF3D3D"), Color.WHITE, "epic",
            TextEffect.POP, Color.parseColor("#FF2D2D")),
        Mood(listOf("gaming", "neon", "cyber", "tech", "stream", "live", "rgb"),
            Color.parseColor("#00E5FF"), Color.BLACK, "neon",
            TextEffect.NEON, Color.parseColor("#00E5FF"))
    )

    override suspend fun suggest(description: String): OverlaySpec {
        val lower = description.lowercase()
        val mood = MOODS.firstOrNull { m -> m.keys.any { lower.contains(it) } }

        // Quoted phrase wins as the title, else strongest keywords.
        val quoted = Regex("[\"“”']([^\"“”']{2,40})[\"“”']").find(description)?.groupValues?.get(1)
        val title = (quoted ?: keywordTitle(description)).uppercase()

        return OverlaySpec(
            title = title.ifBlank { "WATCH THIS" },
            subtitle = "",
            titleColor = mood?.title ?: Color.parseColor("#FFEC3D"),
            strokeColor = mood?.stroke ?: Color.BLACK,
            position = Position.LOWER_LEFT,
            mood = mood?.mood ?: "",
            accent = accentFor(lower),
            effect = mood?.effect ?: TextEffect.GLOW,
            glowColor = mood?.glow ?: Color.parseColor("#FF2D7A")
        )
    }

    private fun keywordTitle(description: String): String {
        val words = Regex("[A-Za-z0-9']+").findAll(description)
            .map { it.value }
            .filter { it.length > 2 && it.lowercase() !in STOP }
            .toList()
        return words.take(4).joinToString(" ")
    }

    private fun accentFor(lower: String): String = when {
        lower.contains("fire") || lower.contains("hot") || lower.contains("epic") -> "🔥"
        lower.contains("camp") || lower.contains("tent") || lower.contains("forest") -> "🏕"
        lower.contains("water") || lower.contains("river") || lower.contains("rain") -> "💧"
        lower.contains("warn") || lower.contains("danger") -> "⚠"
        else -> ""
    }
}
