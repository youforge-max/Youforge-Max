package eu.youforgemax.thumb

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.ContextCompat
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Pure, offline compositor: takes a source photo + an [OverlaySpec] and renders a
 * 1280x720 YouTube thumbnail. No network, no LLM — fully deterministic.
 */
object ThumbnailRenderer {

    const val W = 1280
    const val H = 720
    private const val PAD = 56f          // safe-zone padding
    private const val MAX_LINES = 3

    /**
     * Full compositor. [spec] may be null (photo + stickers only, before the AI/template
     * has produced a title). [context] is only needed to rasterise vector stickers.
     */
    fun render(
        context: Context,
        source: Bitmap,
        spec: OverlaySpec?,
        stickers: List<Sticker> = emptyList()
    ): Bitmap {
        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)

        drawCenterCropped(canvas, source)
        if (spec != null) {
            // Free-positioned text carries its own legibility via the effect stack;
            // the position scrim only applies to anchored text.
            if (spec.freeX == null || spec.freeY == null) drawScrim(canvas, spec.position)
            drawText(canvas, spec)
        }
        drawStickers(canvas, context, stickers)
        return out
    }

    /**
     * Axis-aligned bounding box of the title block, normalised to 0..1, for hit
     * testing in the editor. Rotation is ignored (generous box is fine for taps).
     * Returns null when there is no title.
     */
    fun titleBoundsNorm(spec: OverlaySpec): RectF? {
        if (spec.title.isBlank()) return null
        val L = computeTitleLayout(spec)
        val halfW = L.maxLineW / 2f
        val cx = L.centerX
        val cy = L.centerY
        val padX = W * 0.02f
        val padY = H * 0.03f
        return RectF(
            ((cx - halfW - padX) / W).coerceIn(0f, 1f),
            ((cy - L.blockH / 2f - padY) / H).coerceIn(0f, 1f),
            ((cx + halfW + padX) / W).coerceIn(0f, 1f),
            ((cy + L.blockH / 2f + padY) / H).coerceIn(0f, 1f)
        )
    }

    /**
     * Small standalone preview of a single [effect] (sample text on a dark chip),
     * using the exact same layer stack as the real render. Used by the effect strip
     * so each swatch is a true live preview of the current title/glow colours.
     */
    fun sampleChip(
        effect: TextEffect,
        titleColor: Int,
        glowColor: Int,
        wPx: Int = 240,
        hPx: Int = 132
    ): Bitmap {
        val bmp = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val bg = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, hPx.toFloat(),
                Color.parseColor("#2A2F36"), Color.parseColor("#12151A"), Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, wPx.toFloat(), hPx.toFloat(), bg)

        val spec = OverlaySpec(
            title = "Aa", effect = effect, titleColor = titleColor,
            glowColor = glowColor, position = Position.CENTER, strokeColor = Color.BLACK
        )
        val tf = Typeface.create("sans-serif-black", Typeface.BOLD)
        val size = hPx * 0.46f
        drawTitleEffect(
            canvas, spec, listOf("Aa"),
            x = wPx / 2f, y0 = hPx / 2f + size * 0.36f,
            lineH = size * 1.06f, size = size, tf = tf, align = Paint.Align.CENTER
        )
        return bmp
    }

    /**
     * Average colour of the photo behind the title block (after the center-crop
     * mapping), for the contrast check. Samples a coarse grid inside the title box.
     */
    fun bgColorUnderTitle(source: Bitmap, spec: OverlaySpec): Int {
        val tb = titleBoundsNorm(spec) ?: return 0xFF808080.toInt()
        val scale = max(W.toFloat() / source.width, H.toFloat() / source.height)
        val dw = source.width * scale
        val dh = source.height * scale
        val left = (W - dw) / 2f
        val top = (H - dh) / 2f
        val x0 = (tb.left * W).toInt(); val x1 = (tb.right * W).toInt()
        val y0 = (tb.top * H).toInt(); val y1 = (tb.bottom * H).toInt()
        val stepX = max(1, (x1 - x0) / 12)
        val stepY = max(1, (y1 - y0) / 8)
        val samples = ArrayList<Int>()
        var cy = y0
        while (cy <= y1) {
            var cx = x0
            while (cx <= x1) {
                val sx = ((cx - left) / scale).toInt()
                val sy = ((cy - top) / scale).toInt()
                if (sx in 0 until source.width && sy in 0 until source.height) {
                    samples.add(source.getPixel(sx, sy))
                }
                cx += stepX
            }
            cy += stepY
        }
        return Contrast.averageColor(samples.toIntArray())
    }

    private fun drawStickers(canvas: Canvas, context: Context, stickers: List<Sticker>) {
        if (stickers.isEmpty()) return
        val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            setShadowLayer(10f, 0f, 6f, Color.argb(150, 0, 0, 0))
        }
        for (s in stickers) {
            val sizePx = s.scale * W
            val cx = s.cx * W
            val cy = s.cy * H
            canvas.save()
            if (s.rotation != 0f) canvas.rotate(s.rotation, cx, cy)
            when (val k = s.kind) {
                is StickerKind.Vector -> {
                    val d = ContextCompat.getDrawable(context, k.resId) ?: continue
                    val half = (sizePx / 2f).roundToInt()
                    d.setBounds(
                        (cx - half).roundToInt(), (cy - half).roundToInt(),
                        (cx + half).roundToInt(), (cy + half).roundToInt()
                    )
                    d.draw(canvas)
                }
                is StickerKind.Emoji -> {
                    emojiPaint.textSize = sizePx
                    val fm = emojiPaint.fontMetrics
                    val baseline = cy - (fm.ascent + fm.descent) / 2f
                    canvas.drawText(k.ch, cx, baseline, emojiPaint)
                }
                is StickerKind.Subscribe -> {
                    val wpx = sizePx
                    val hpx = wpx * 0.30f
                    val rect = RectF(cx - wpx / 2f, cy - hpx / 2f, cx + wpx / 2f, cy + hpx / 2f)
                    val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.parseColor("#FF0000")
                        setShadowLayer(hpx * 0.12f, 0f, hpx * 0.06f, Color.argb(150, 0, 0, 0))
                    }
                    canvas.drawRoundRect(rect, hpx * 0.22f, hpx * 0.22f, bg)
                    val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.WHITE
                        typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                        textAlign = Paint.Align.CENTER
                        textSize = hpx * 0.46f
                        letterSpacing = 0.04f
                    }
                    val fm = tp.fontMetrics
                    canvas.drawText("SUBSCRIBE", cx, cy - (fm.ascent + fm.descent) / 2f, tp)
                }
            }
            canvas.restore()
        }
    }

    /** Scale the photo to fill 1280x720, cropping the overflow (center crop). */
    private fun drawCenterCropped(canvas: Canvas, src: Bitmap) {
        val scale = max(W.toFloat() / src.width, H.toFloat() / src.height)
        val dw = src.width * scale
        val dh = src.height * scale
        val left = (W - dw) / 2f
        val top = (H - dh) / 2f
        canvas.drawBitmap(src, null, RectF(left, top, left + dw, top + dh), Paint(Paint.FILTER_BITMAP_FLAG))
    }

    /** Dark gradient behind the text area so light/busy photos stay legible. */
    private fun drawScrim(canvas: Canvas, pos: Position) {
        val top = pos == Position.UPPER_LEFT || pos == Position.UPPER_CENTER || pos == Position.UPPER_RIGHT
        val center = pos == Position.CENTER
        val p = Paint()
        val band = H * 0.55f
        when {
            center -> {
                p.color = Color.argb(110, 0, 0, 0)
                canvas.drawRect(0f, 0f, W.toFloat(), H.toFloat(), p)
            }
            top -> {
                p.shader = LinearGradient(0f, 0f, 0f, band, Color.argb(190, 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, W.toFloat(), band, p)
            }
            else -> {
                p.shader = LinearGradient(0f, H - band, 0f, H.toFloat(), Color.TRANSPARENT, Color.argb(200, 0, 0, 0), Shader.TileMode.CLAMP)
                canvas.drawRect(0f, H - band, W.toFloat(), H.toFloat(), p)
            }
        }
    }

    /** Resolved geometry of the title block, in canvas pixels. */
    private data class TitleLayout(
        val lines: List<String>,
        val size: Float,
        val lineH: Float,
        val x: Float,         // text-draw x (anchor for the Paint.Align)
        val y0: Float,        // baseline of the first line
        val align: Paint.Align,
        val blockH: Float,
        val maxLineW: Float,  // widest line, pixels
        val centerX: Float,   // block centre (rotation pivot / hit box)
        val centerY: Float
    )

    private fun computeTitleLayout(spec: OverlaySpec): TitleLayout {
        val title = (spec.accent.takeIf { it.isNotBlank() }?.let { "$it " } ?: "") +
            spec.title.uppercase()
        val free = spec.freeX != null && spec.freeY != null

        val tf = Typeface.create("sans-serif-black", Typeface.BOLD)
        val align = if (free) Paint.Align.CENTER else alignFor(spec.position)
        val measure = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = tf; textAlign = align }

        // Auto-fit: shrink until the title wraps into <= MAX_LINES within the safe
        // width. Explicit '\n' in the title forces hard breaks before greedy wrap.
        val rawLines = title.split('\n')
        val maxW = W - 2 * PAD
        var size = 150f
        var lines: List<String>
        while (true) {
            measure.textSize = size
            lines = rawLines.flatMap { wrap(it, measure, maxW) }
            if (lines.size <= MAX_LINES || size <= 56f) break
            size -= 6f
        }
        // Manual size multiplier (pinch-zoom). Re-measure widths at the final size.
        size = (size * spec.titleScale).coerceIn(28f, 320f)
        measure.textSize = size
        val maxLineW = lines.maxOf { l ->
            val b = Rect(); measure.getTextBounds(l, 0, l.length, b); b.width().toFloat()
        }

        val lineH = size * 1.06f
        val subSize = size * 0.42f
        val hasSub = spec.subtitle.isNotBlank()
        val blockH = lines.size * lineH + if (hasSub) subSize * 1.4f else 0f

        val x: Float
        val topY: Float
        if (free) {
            x = spec.freeX!! * W
            topY = spec.freeY!! * H - blockH / 2f
        } else {
            x = anchorX(spec.position)
            topY = anchorTop(spec.position, blockH)
        }
        val y0 = topY + size
        val centerX = when (align) {
            Paint.Align.CENTER -> x
            Paint.Align.RIGHT -> x - maxLineW / 2f
            else -> x + maxLineW / 2f
        }
        val centerY = topY + blockH / 2f
        return TitleLayout(lines, size, lineH, x, y0, align, blockH, maxLineW, centerX, centerY)
    }

    private fun drawText(canvas: Canvas, spec: OverlaySpec) {
        val L = computeTitleLayout(spec)
        val tf = Typeface.create("sans-serif-black", Typeface.BOLD)

        canvas.save()
        if (spec.rotation != 0f) canvas.rotate(spec.rotation, L.centerX, L.centerY)

        drawTitleEffect(canvas, spec, L.lines, L.x, L.y0, L.lineH, L.size, tf, L.align)

        if (spec.subtitle.isNotBlank()) {
            val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                textAlign = L.align
                textSize = L.size * 0.42f
                setShadowLayer(8f, 0f, 4f, Color.argb(180, 0, 0, 0))
            }
            val subY = L.y0 + (L.lines.size - 1) * L.lineH + L.lineH * 0.95f
            canvas.drawText(spec.subtitle.uppercase(), L.x, subY, subPaint)
        }
        canvas.restore()
    }

    /** Draws the title's [lines] with the layer stack for [OverlaySpec.effect]. */
    private fun drawTitleEffect(
        canvas: Canvas,
        spec: OverlaySpec,
        lines: List<String>,
        x: Float,
        y0: Float,
        lineH: Float,
        size: Float,
        tf: Typeface,
        align: Paint.Align
    ) {
        fun basePaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tf; textAlign = align; textSize = size
        }
        fun lines(p: Paint) { var y = y0; for (l in lines) { canvas.drawText(l, x, y, p); y += lineH } }

        val fill = basePaint().apply { color = spec.titleColor }
        val stroke = basePaint().apply {
            style = Paint.Style.STROKE; color = spec.strokeColor; strokeWidth = size * 0.085f
        }

        when (spec.effect) {
            TextEffect.PLAIN -> { lines(stroke); lines(fill) }

            TextEffect.SHADOW -> {
                fill.setShadowLayer(14f, 0f, 9f, Color.argb(175, 0, 0, 0))
                lines(stroke); lines(fill)
            }

            TextEffect.OUTLINE -> {
                stroke.strokeWidth = size * 0.14f
                lines(stroke); lines(fill)
            }

            TextEffect.GLOW -> {
                val glow = basePaint().apply {
                    color = spec.glowColor
                    maskFilter = BlurMaskFilter(size * 0.22f, BlurMaskFilter.Blur.NORMAL)
                }
                lines(glow); lines(glow)                 // doubled = brighter halo
                val edge = basePaint().apply {
                    style = Paint.Style.STROKE; color = Color.argb(220, 0, 0, 0)
                    strokeWidth = size * 0.05f
                }
                lines(edge); lines(fill)
            }

            TextEffect.NEON -> {
                val glow = basePaint().apply {
                    color = spec.glowColor
                    maskFilter = BlurMaskFilter(size * 0.30f, BlurMaskFilter.Blur.NORMAL)
                }
                lines(glow); lines(glow); lines(glow)
                val edge = basePaint().apply {
                    style = Paint.Style.STROKE; color = spec.glowColor; strokeWidth = size * 0.045f
                }
                val core = basePaint().apply { color = Color.WHITE }
                lines(edge); lines(core)                 // white-hot core in a coloured glow
            }

            TextEffect.GRADIENT -> {
                val top = y0 - size * 0.85f
                val bottom = y0 + (lines.size - 1) * lineH + size * 0.2f
                fill.shader = LinearGradient(
                    0f, top, 0f, bottom,
                    intArrayOf(lighten(spec.titleColor, 0.35f), spec.titleColor, darken(spec.titleColor, 0.25f)),
                    floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
                )
                fill.setShadowLayer(12f, 0f, 7f, Color.argb(160, 0, 0, 0))
                stroke.strokeWidth = size * 0.06f
                lines(stroke); lines(fill)
            }

            TextEffect.POP -> {
                stroke.color = spec.titleColor
                stroke.strokeWidth = size * 0.15f
                stroke.setShadowLayer(16f, 0f, 10f, Color.argb(185, 0, 0, 0))
                fill.color = Color.WHITE
                lines(stroke); lines(fill)
            }
        }
    }

    private fun lighten(c: Int, f: Float): Int = Color.rgb(
        (Color.red(c) + (255 - Color.red(c)) * f).roundToInt().coerceIn(0, 255),
        (Color.green(c) + (255 - Color.green(c)) * f).roundToInt().coerceIn(0, 255),
        (Color.blue(c) + (255 - Color.blue(c)) * f).roundToInt().coerceIn(0, 255)
    )

    private fun darken(c: Int, f: Float): Int = Color.rgb(
        (Color.red(c) * (1 - f)).roundToInt().coerceIn(0, 255),
        (Color.green(c) * (1 - f)).roundToInt().coerceIn(0, 255),
        (Color.blue(c) * (1 - f)).roundToInt().coerceIn(0, 255)
    )

    private fun alignFor(pos: Position): Paint.Align = when (pos) {
        Position.UPPER_CENTER, Position.CENTER, Position.LOWER_CENTER -> Paint.Align.CENTER
        Position.UPPER_RIGHT, Position.LOWER_RIGHT -> Paint.Align.RIGHT
        else -> Paint.Align.LEFT
    }

    private fun anchorX(pos: Position): Float = when (alignFor(pos)) {
        Paint.Align.CENTER -> W / 2f
        Paint.Align.RIGHT -> W - PAD
        else -> PAD
    }

    private fun anchorTop(pos: Position, blockH: Float): Float = when (pos) {
        Position.UPPER_LEFT, Position.UPPER_CENTER, Position.UPPER_RIGHT -> PAD
        Position.CENTER -> (H - blockH) / 2f
        else -> H - PAD - blockH
    }

    /** Greedy word wrap to a max pixel width. */
    private fun wrap(text: String, paint: Paint, maxW: Float): List<String> {
        val words = text.split(" ").filter { it.isNotBlank() }
        if (words.isEmpty()) return listOf(text)
        val lines = ArrayList<String>()
        var cur = StringBuilder()
        val bounds = Rect()
        for (w in words) {
            val trial = if (cur.isEmpty()) w else "$cur $w"
            paint.getTextBounds(trial, 0, trial.length, bounds)
            if (bounds.width() > maxW && cur.isNotEmpty()) {
                lines.add(cur.toString())
                cur = StringBuilder(w)
            } else {
                cur = StringBuilder(trial)
            }
        }
        if (cur.isNotEmpty()) lines.add(cur.toString())
        return lines
    }
}
