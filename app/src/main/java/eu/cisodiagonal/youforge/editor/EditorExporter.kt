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
            val builder = EditedMediaItem.Builder(item).setRemoveAudio(clip.muted)
            if (clip.speed != 1f) {
                // Match audio + video speed so they stay in sync.
                val audio = androidx.media3.common.audio.SonicAudioProcessor().apply { setSpeed(clip.speed) }
                builder.setEffects(
                    androidx.media3.transformer.Effects(
                        listOf<androidx.media3.common.audio.AudioProcessor>(audio),
                        listOf<Effect>(androidx.media3.effect.SpeedChangeEffect(clip.speed))
                    )
                )
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
        // Composition-level video effect: scale every clip to the chosen output height
        // (width follows source aspect), so mixed-resolution clips merge cleanly.
        val videoEffects: MutableList<Effect> = mutableListOf(
            Presentation.createForHeight(project.resolution.height)
        )
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
