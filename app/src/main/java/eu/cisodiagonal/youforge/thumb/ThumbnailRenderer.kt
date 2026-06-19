package eu.cisodiagonal.youforge.thumb

import android.content.Context
import android.graphics.Bitmap
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
    private const val MAX_LINES = 2

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
            drawScrim(canvas, spec.position)
            drawText(canvas, spec)
        }
        drawStickers(canvas, context, stickers)
        return out
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

    private fun drawText(canvas: Canvas, spec: OverlaySpec) {
        val title = (spec.accent.takeIf { it.isNotBlank() }?.let { "$it " } ?: "") +
            spec.title.uppercase()

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = spec.titleColor
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            textAlign = alignFor(spec.position)
            setShadowLayer(10f, 0f, 6f, Color.argb(160, 0, 0, 0))
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = spec.strokeColor
            typeface = fill.typeface
            textAlign = fill.textAlign
            style = Paint.Style.STROKE
        }

        // Auto-fit: shrink until the title wraps into <= MAX_LINES within the safe width.
        val maxW = W - 2 * PAD
        var size = 150f
        var lines: List<String>
        while (true) {
            fill.textSize = size
            lines = wrap(title, fill, maxW)
            if (lines.size <= MAX_LINES || size <= 56f) break
            size -= 6f
        }
        stroke.textSize = size
        stroke.strokeWidth = size * 0.085f

        val lineH = size * 1.06f
        val subSize = size * 0.42f
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            textAlign = fill.textAlign
            textSize = subSize
            setShadowLayer(8f, 0f, 4f, Color.argb(180, 0, 0, 0))
        }
        val hasSub = spec.subtitle.isNotBlank()
        val blockH = lines.size * lineH + if (hasSub) subSize * 1.4f else 0f

        val x = anchorX(spec.position)
        var y = anchorTop(spec.position, blockH) + size   // baseline of first line

        for (line in lines) {
            canvas.drawText(line, x, y, stroke)
            canvas.drawText(line, x, y, fill)
            y += lineH
        }
        if (hasSub) {
            canvas.drawText(spec.subtitle.uppercase(), x, y + subSize * 0.1f, subPaint)
        }
    }

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
