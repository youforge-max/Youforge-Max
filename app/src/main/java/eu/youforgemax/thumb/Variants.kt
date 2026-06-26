package eu.youforgemax.thumb

/**
 * Deterministic A/B style variants of a base spec — different effect / colour /
 * position so the creator can export a set and test which gets more clicks
 * (YouTube's 2026 ranking weights A/B test data). Pure data transforms, no
 * Android — portable to the desktop builds.
 */
object Variants {

    /** Up to [n] distinct variants, the base always first. */
    fun make(base: OverlaySpec, n: Int = 3): List<OverlaySpec> {
        val combos = listOf(
            base,
            base.copy(
                effect = TextEffect.POP,
                titleColor = 0xFFFFFFFF.toInt()
            ),
            base.copy(
                effect = TextEffect.NEON,
                titleColor = 0xFFFFFFFF.toInt(),
                glowColor = 0xFF00E5FF.toInt(),
                position = flipVertical(base.position)
            ),
            base.copy(
                effect = TextEffect.GRADIENT,
                titleColor = 0xFFFFEC3D.toInt()
            )
        )
        return combos.distinct().take(n.coerceIn(1, combos.size))
    }

    private fun flipVertical(p: Position): Position = when (p) {
        Position.LOWER_LEFT -> Position.UPPER_LEFT
        Position.LOWER_RIGHT -> Position.UPPER_RIGHT
        Position.LOWER_CENTER -> Position.UPPER_CENTER
        Position.UPPER_LEFT -> Position.LOWER_LEFT
        Position.UPPER_RIGHT -> Position.LOWER_RIGHT
        Position.UPPER_CENTER -> Position.LOWER_CENTER
        Position.CENTER -> Position.LOWER_CENTER
    }
}
