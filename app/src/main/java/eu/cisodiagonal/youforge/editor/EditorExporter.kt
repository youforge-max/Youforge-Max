package eu.cisodiagonal.youforge.editor

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File

/**
 * Renders an [EditorProject] to a single MP4 with Media3 Transformer — on-device,
 * MediaCodec-based, no FFmpeg, no watermark. Each clip becomes an [EditedMediaItem]
 * (trim via ClippingConfiguration; speed via a later phase) and the clips are joined
 * into one [EditedMediaItemSequence]; the [Composition] is exported in one pass.
 *
 * Transformer must be created and started on a thread with a Looper — we use the main
 * thread and poll progress on it.
 */
/**
 * A black [BitmapOverlay] whose alpha ramps the clip in from / out to black at its
 * edges — a fade transition between consecutive clips (and an intro/outro). The tiny
 * black bitmap is scaled up to cover the whole frame; [getOverlaySettings] varies the
 * alpha over the clip's output timeline.
 */
@UnstableApi
private class FadeOverlay(private val clipUs: Long) : androidx.media3.effect.BitmapOverlay() {
    private val fadeUs = minOf(400_000L, (clipUs / 3).coerceAtLeast(1))
    private val black = android.graphics.Bitmap
        .createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888)
        .apply { eraseColor(android.graphics.Color.BLACK) }
    private val cover = androidx.media3.effect.OverlaySettings.Builder().setScale(1000f, 1000f)

    override fun getBitmap(presentationTimeUs: Long) = black

    override fun getOverlaySettings(presentationTimeUs: Long): androidx.media3.effect.OverlaySettings {
        val a = when {
            presentationTimeUs < fadeUs -> 1f - presentationTimeUs.toFloat() / fadeUs
            presentationTimeUs > clipUs - fadeUs -> (presentationTimeUs - (clipUs - fadeUs)).toFloat() / fadeUs
            else -> 0f
        }.coerceIn(0f, 1f)
        return cover.setAlphaScale(a).build()
    }
}

/** Audio gain via a channel-mixing identity matrix scaled by [v] (mono + stereo). */
@OptIn(UnstableApi::class)
private fun gainProcessor(v: Float): androidx.media3.common.audio.ChannelMixingAudioProcessor {
    fun scaled(ch: Int): androidx.media3.common.audio.ChannelMixingMatrix {
        val coef = FloatArray(ch * ch)
        for (i in 0 until ch) coef[i * ch + i] = v
        return androidx.media3.common.audio.ChannelMixingMatrix(ch, ch, coef)
    }
    return androidx.media3.common.audio.ChannelMixingAudioProcessor().apply {
        putChannelMixingMatrix(scaled(1))
        putChannelMixingMatrix(scaled(2))
    }
}

/** Renders one [Sticker] to a transparent bitmap (emoji/text drawn at its pixel size). */
private fun stickerBitmap(s: Sticker): android.graphics.Bitmap {
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textSize = s.sizePx.toFloat()
        color = android.graphics.Color.WHITE
        setShadowLayer(8f, 0f, 0f, android.graphics.Color.BLACK)
    }
    val w = (paint.measureText(s.text).toInt() + s.sizePx / 2).coerceAtLeast(1)
    val fm = paint.fontMetrics
    val h = (fm.descent - fm.ascent).toInt().coerceAtLeast(1)
    val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    android.graphics.Canvas(bmp).drawText(s.text, w / 2f - paint.measureText(s.text) / 2f, -fm.ascent, paint)
    return bmp
}

/**
 * A sticker overlay that only shows within [startUs]..[endUs] of the output timeline —
 * alpha is 1 inside the window, 0 outside (per-sticker timing). The anchor is fixed.
 */
@UnstableApi
private class TimedSticker(
    private val bmp: android.graphics.Bitmap,
    anchorX: Float, anchorY: Float,
    private val startUs: Long, private val endUs: Long,
) : androidx.media3.effect.BitmapOverlay() {
    private val visible = androidx.media3.effect.OverlaySettings.Builder()
        .setBackgroundFrameAnchor(anchorX, anchorY).setAlphaScale(1f).build()
    private val hidden = androidx.media3.effect.OverlaySettings.Builder()
        .setBackgroundFrameAnchor(anchorX, anchorY).setAlphaScale(0f).build()
    override fun getBitmap(presentationTimeUs: Long) = bmp
    override fun getOverlaySettings(presentationTimeUs: Long) =
        if (presentationTimeUs in startUs..endUs) visible else hidden
}

/** Sticker overlays anchored on the output canvas, each gated by its own timing. */
@OptIn(UnstableApi::class)
fun stickerOverlayEffect(stickers: List<Sticker>): Effect? {
    val visible = stickers.filter { it.text.isNotBlank() }
    if (visible.isEmpty()) return null
    val overlays = visible.map { s ->
        // NDC anchor: x in [0,1] -> [-1,1]; y top-left -> NDC y up.
        val ax = s.x * 2f - 1f
        val ay = (1f - s.y) * 2f - 1f
        val bmp = stickerBitmap(s)
        if (s.startMs <= 0L && s.endMs < 0L) {
            // Always-on: cheaper static overlay.
            val settings = androidx.media3.effect.OverlaySettings.Builder()
                .setBackgroundFrameAnchor(ax, ay).build()
            androidx.media3.effect.BitmapOverlay.createStaticBitmapOverlay(bmp, settings)
        } else {
            val endUs = if (s.endMs < 0L) Long.MAX_VALUE else s.endMs * 1000L
            TimedSticker(bmp, ax, ay, s.startMs * 1000L, endUs)
        }
    }
    return androidx.media3.effect.OverlayEffect(
        com.google.common.collect.ImmutableList.copyOf(overlays.map { it as androidx.media3.effect.TextureOverlay })
    )
}

/** Shared filter -> Media3 effect mapping, used by both export and the live preview. */
@OptIn(UnstableApi::class)
object EditorEffects {
    fun forFilter(filter: VideoFilter): List<Effect> = when (filter) {
        VideoFilter.NONE -> emptyList()
        VideoFilter.GRAYSCALE -> listOf(androidx.media3.effect.RgbFilter.createGrayscaleFilter())
        VideoFilter.VIVID -> listOf(
            androidx.media3.effect.HslAdjustment.Builder().adjustSaturation(40f).build()
        )
        VideoFilter.WARM -> listOf(
            androidx.media3.effect.RgbAdjustment.Builder().setRedScale(1.15f).setBlueScale(0.88f).build()
        )
        VideoFilter.COOL -> listOf(
            androidx.media3.effect.RgbAdjustment.Builder().setRedScale(0.88f).setBlueScale(1.15f).build()
        )
        VideoFilter.CONTRAST -> listOf(androidx.media3.effect.Contrast(0.35f))
    }
}

@OptIn(UnstableApi::class)
class EditorExporter(private val context: Context) {

    interface Callback {
        fun onProgress(percent: Int)
        fun onDone(output: File)
        fun onError(message: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private var transformer: Transformer? = null

    fun export(project: EditorProject, callback: Callback) {
        if (project.isEmpty) { callback.onError("No clips to export."); return }
        main.post { startOnMain(project, callback) }
    }

    private fun startOnMain(project: EditorProject, callback: Callback) {
        val editedItems = project.clips.map { clip ->
            val item = MediaItem.Builder()
                .setUri(clip.uri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(clip.trimStartMs)
                        .setEndPositionMs(clip.trimEndMs)
                        .build()
                )
                .build()
            val vfx = mutableListOf<Effect>()
            val afx = mutableListOf<androidx.media3.common.audio.AudioProcessor>()
            if (clip.rotationDeg % 360 != 0) {
                vfx.add(
                    androidx.media3.effect.ScaleAndRotateTransformation.Builder()
                        .setRotationDegrees(clip.rotationDeg.toFloat())
                        .build()
                )
            }
            // Per-clip volume (skip when muted — audio is already removed).
            if (!clip.muted && clip.volume != 1f) afx.add(gainProcessor(clip.volume))
            if (clip.speed != 1f) {
                // Match audio + video speed so they stay in sync.
                afx.add(androidx.media3.common.audio.SonicAudioProcessor().apply { setSpeed(clip.speed) })
                vfx.add(androidx.media3.effect.SpeedChangeEffect(clip.speed))
            }
            if (project.transition == Transition.FADE && project.clips.size > 1) {
                // Fade each clip in/out of black at its edges (added after speed so it
                // works in the clip's output timeline).
                vfx.add(
                    androidx.media3.effect.OverlayEffect(
                        com.google.common.collect.ImmutableList.of<androidx.media3.effect.TextureOverlay>(
                            FadeOverlay(clip.outMs * 1000L)
                        )
                    )
                )
            }
            val builder = EditedMediaItem.Builder(item).setRemoveAudio(clip.muted)
            if (vfx.isNotEmpty() || afx.isNotEmpty()) {
                builder.setEffects(androidx.media3.transformer.Effects(afx, vfx))
            }
            builder.build()
        }
        val videoSequence = EditedMediaItemSequence(editedItems)
        // Optional background music as a second (audio-only) sequence; the Composition
        // mixes it with the clips' own audio. Truncated to the video length.
        val sequences = mutableListOf(videoSequence)
        project.musicUri?.let { music ->
            val musicItem = EditedMediaItem.Builder(MediaItem.fromUri(music)).build()
            sequences.add(EditedMediaItemSequence(listOf(musicItem)))
        }
        // Composition-level video effect: normalise every clip to the chosen canvas so
        // mixed-resolution/aspect clips merge cleanly. SOURCE keeps the source shape
        // (scale to height, width follows source); the named ratios crop-fill to a fixed
        // WxH (output height = resolution.height, width = height * w / h, rounded even).
        val presentation = if (project.aspect == AspectRatio.SOURCE) {
            Presentation.createForHeight(project.resolution.height)
        } else {
            val h = project.resolution.height
            var w = Math.round(h.toFloat() * project.aspect.w / project.aspect.h)
            if (w % 2 != 0) w += 1
            // Letterbox = fit the whole frame inside the canvas (black bars); otherwise
            // crop-fill so the canvas is fully covered.
            val layout = if (project.letterbox) Presentation.LAYOUT_SCALE_TO_FIT
                else Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
            Presentation.createForWidthAndHeight(w, h, layout)
        }
        val videoEffects: MutableList<Effect> = mutableListOf(presentation)
        videoEffects.addAll(EditorEffects.forFilter(project.filter))
        // Optional burned-in title (YouForge text on video) via a Media3 text overlay.
        if (project.title.isNotBlank()) {
            val span = android.text.SpannableString(project.title).apply {
                setSpan(android.text.style.AbsoluteSizeSpan(64), 0, length, 0)
                setSpan(android.text.style.ForegroundColorSpan(android.graphics.Color.WHITE), 0, length, 0)
                setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, length, 0)
            }
            val overlay = androidx.media3.effect.TextOverlay.createStaticTextOverlay(span)
            videoEffects.add(
                androidx.media3.effect.OverlayEffect(
                    com.google.common.collect.ImmutableList.of<androidx.media3.effect.TextureOverlay>(overlay)
                )
            )
        }
        // Burned-in stickers (emoji/text) anchored on the output canvas.
        stickerOverlayEffect(project.stickers)?.let { videoEffects.add(it) }
        val composition = Composition.Builder(sequences)
            .setEffects(androidx.media3.transformer.Effects(emptyList(), videoEffects))
            .build()

        val outDir = File(context.getExternalFilesDir("Movies"), "YouForge").apply { mkdirs() }
        val output = File(outDir, "edit_${System.currentTimeMillis()}.mp4")

        val t = Transformer.Builder(context)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, result: ExportResult) {
                    stopPolling()
                    callback.onProgress(100)
                    callback.onDone(output)
                }
                override fun onError(
                    composition: Composition, result: ExportResult, exception: ExportException
                ) {
                    stopPolling()
                    callback.onError(exception.message ?: "Export failed.")
                }
            })
            .build()
        transformer = t
        t.start(composition, output.absolutePath)
        pollProgress(callback)
    }

    private val progressHolder = ProgressHolder()
    private val poll = object : Runnable {
        override fun run() {
            val t = transformer ?: return
            val state = t.getProgress(progressHolder)
            if (state != Transformer.PROGRESS_STATE_NOT_STARTED) {
                // callback set in pollProgress via field
            }
            cb?.onProgress(progressHolder.progress)
            main.postDelayed(this, 200)
        }
    }
    private var cb: Callback? = null

    private fun pollProgress(callback: Callback) {
        cb = callback
        main.postDelayed(poll, 200)
    }

    private fun stopPolling() {
        main.removeCallbacks(poll)
        cb = null
    }

    fun cancel() {
        main.post {
            stopPolling()
            transformer?.cancel()
            transformer = null
        }
    }
}
