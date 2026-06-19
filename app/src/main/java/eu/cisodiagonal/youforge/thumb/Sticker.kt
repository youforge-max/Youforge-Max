package eu.cisodiagonal.youforge.thumb
import eu.cisodiagonal.youforge.R

/** A draggable overlay element: either a bundled vector or an emoji glyph. */
sealed interface StickerKind {
    data class Vector(val resId: Int) : StickerKind
    data class Emoji(val ch: String) : StickerKind
    /** Red YouTube-style "SUBSCRIBE" pill, drawn by the renderer (wide aspect). */
    data object Subscribe : StickerKind
}

/**
 * One placed sticker. Position is normalised to the canvas (0..1) so it maps
 * identically to the on-screen preview and the 1280x720 export. [scale] is the
 * sticker's width as a fraction of the canvas width.
 */
data class Sticker(
    val id: Long,
    val kind: StickerKind,
    val cx: Float = 0.5f,
    val cy: Float = 0.5f,
    val scale: Float = 0.18f
)

/** Palette shown in the UI. Vectors first, then a set of YouTube-y emoji. */
object Stickers {
    val vectors: List<Pair<String, Int>> = listOf(
        "Fish" to R.drawable.sticker_fish,
        "Fire" to R.drawable.sticker_fire,
        "Arrow" to R.drawable.sticker_arrow,
        "Circle" to R.drawable.sticker_circle
    )

    val emoji: List<String> = listOf(
        // reactions
        "😮", "😱", "😨", "🤯", "😭", "😂", "🤩", "😍", "🥶", "🤔", "😎", "👀", "😤",
        // hands / pointers
        "👉", "👆", "👇", "👍", "👎", "🙌", "👏", "🤙", "💪",
        // marks / emphasis
        "✅", "❌", "⭐", "💯", "❓", "❗", "🔥", "💥", "⚡", "✨", "🎯", "⚠️", "🚫", "🆕",
        // camping / outdoors / nature
        "🏕️", "⛺", "🌲", "🌳", "⛰️", "🏔️", "🌊", "💧", "🌧️", "🌙", "☀️", "❄️", "🌡️",
        "🎣", "🐟", "🦌", "🦊", "🐗", "🦉", "🪵", "🪓", "🔦", "🧭", "🥾", "🎒", "🔪",
        // clickbait misc
        "💀", "👑", "💰", "⏰", "🆘", "🔴"
    )

    /** Sensible starting width (fraction of canvas width) per sticker kind. */
    fun defaultScale(kind: StickerKind): Float = when (kind) {
        is StickerKind.Subscribe -> 0.34f   // wide pill
        is StickerKind.Vector -> 0.22f
        is StickerKind.Emoji -> 0.18f
    }

    private var counter = 0L
    fun nextId(): Long = ++counter
}
