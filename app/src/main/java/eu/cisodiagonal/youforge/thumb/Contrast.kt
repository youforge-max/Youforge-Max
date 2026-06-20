package eu.cisodiagonal.youforge.thumb

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Portable WCAG contrast math on packed ARGB ints. No Android imports, so the
 * future Linux/Mac/Windows builds reuse this verbatim. Used to warn when the
 * title colour is hard to read over the photo behind it.
 */
object Contrast {

    private fun linear(c: Double): Double =
        if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    /** WCAG relative luminance (0..1) of a packed ARGB colour. */
    fun relLuminance(argb: Int): Double {
        val r = ((argb shr 16) and 0xFF) / 255.0
        val g = ((argb shr 8) and 0xFF) / 255.0
        val b = (argb and 0xFF) / 255.0
        return 0.2126 * linear(r) + 0.7152 * linear(g) + 0.0722 * linear(b)
    }

    /** WCAG contrast ratio (1..21) between two packed ARGB colours. */
    fun ratio(a: Int, b: Int): Double {
        val la = relLuminance(a)
        val lb = relLuminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** Mean colour of a pixel array (alpha forced opaque). Grey when empty. */
    fun averageColor(pixels: IntArray): Int {
        if (pixels.isEmpty()) return 0xFF808080.toInt()
        var r = 0L; var g = 0L; var b = 0L
        for (p in pixels) {
            r += (p shr 16) and 0xFF
            g += (p shr 8) and 0xFF
            b += p and 0xFF
        }
        val n = pixels.size.toLong()
        return (0xFF shl 24) or
            ((r / n).toInt() shl 16) or
            ((g / n).toInt() shl 8) or
            (b / n).toInt()
    }

    data class Verdict(val ratio: Double, val ok: Boolean, val advice: String, val suggest: Int?)

    /**
     * Judge [titleColor] against the average background [bgColor] under the title.
     * Big bold thumbnail text reads from ~3.0; 4.5+ is the WCAG-AA text bar.
     * Returns a [suggest]ed title colour (white/black) when contrast is poor.
     */
    fun check(titleColor: Int, bgColor: Int): Verdict {
        val r = ratio(titleColor, bgColor)
        return when {
            r >= 4.5 -> Verdict(r, true, "Strong contrast", null)
            r >= 3.0 -> Verdict(r, true, "OK contrast", null)
            else -> {
                val white = 0xFFFFFFFF.toInt()
                val black = 0xFF000000.toInt()
                val pick = if (ratio(white, bgColor) >= ratio(black, bgColor)) white else black
                val word = if (pick == white) "white" else "black"
                Verdict(r, false, "Low contrast — try $word, or add an outline/glow", pick)
            }
        }
    }
}
