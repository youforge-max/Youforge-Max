package eu.youforgemax.youforge.thumb

/**
 * A named title style (effect + colours + position). Built-in presets plus the
 * user's saved "brand kit" reuse this. Pure data — portable to desktop builds.
 */
data class StylePreset(
    val name: String,
    val effect: TextEffect,
    val titleColor: Int,
    val glowColor: Int,
    val position: Position
) {
    /** Apply this style to [s], keeping its text/transform/subtitle. */
    fun applyTo(s: OverlaySpec): OverlaySpec =
        s.copy(effect = effect, titleColor = titleColor, glowColor = glowColor, position = position)
}

object Presets {
    val builtin: List<StylePreset> = listOf(
        StylePreset("Wild camp", TextEffect.GLOW, 0xFFFFEC3D.toInt(), 0xFF1E7A2E.toInt(), Position.LOWER_LEFT),
        StylePreset("Night", TextEffect.NEON, 0xFFFFFFFF.toInt(), 0xFF00E5FF.toInt(), Position.LOWER_CENTER),
        StylePreset("Punch", TextEffect.POP, 0xFFFFFFFF.toInt(), 0xFFFF2D7A.toInt(), Position.CENTER),
        StylePreset("Warm", TextEffect.GRADIENT, 0xFFFF8A3D.toInt(), 0xFFFFA63D.toInt(), Position.LOWER_LEFT),
        StylePreset("Clean", TextEffect.OUTLINE, 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), Position.LOWER_LEFT)
    )
}
