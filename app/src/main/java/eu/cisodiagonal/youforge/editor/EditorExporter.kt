package eu.cisodiagonal.youforge.editor

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
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
            EditedMediaItem.Builder(item).build()
        }
        val sequence = EditedMediaItemSequence(editedItems)
        val composition = Composition.Builder(listOf(sequence)).build()

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
