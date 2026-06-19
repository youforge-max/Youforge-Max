package eu.cisodiagonal.youforge.thumb

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Owns the on-device model file: where it lives, whether it's present, and the
 * one-time download from a user-configured URL. The model stays on the tablet
 * after that — the LLM runs fully offline.
 */
class ModelManager(context: Context) {

    private val appContext = context.applicationContext
    val modelFile: File = File(appContext.filesDir, "gemma.task")

    fun isPresent(): Boolean = modelFile.exists() && modelFile.length() > 1_000_000L

    fun delete(): Boolean {
        OnDeviceLlm.close()
        return modelFile.delete()
    }

    /**
     * Downloads the model to a temp file, then atomically renames into place.
     * [onProgress] is fed 0..1 (or -1 when total size is unknown).
     */
    suspend fun download(url: String, onProgress: (Float) -> Unit): Result<Unit> =
        withContext(Dispatchers.IO) {
            val tmp = File(appContext.filesDir, "gemma.task.part")
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30_000
                    readTimeout = 60_000
                    instanceFollowRedirects = true
                }
                conn.connect()
                if (conn.responseCode !in 200..299) {
                    return@withContext Result.failure(Exception("HTTP ${conn.responseCode}"))
                }
                val total = conn.contentLengthLong
                tmp.delete()
                conn.inputStream.use { input ->
                    tmp.outputStream().use { output ->
                        val buf = ByteArray(1 shl 16)
                        var read: Int
                        var done = 0L
                        var lastPct = -1
                        while (input.read(buf).also { read = it } != -1) {
                            output.write(buf, 0, read)
                            done += read
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                if (pct != lastPct) { lastPct = pct; onProgress(pct / 100f) }
                            } else {
                                onProgress(-1f)
                            }
                        }
                    }
                }
                if (tmp.length() < 1_000_000L) {
                    tmp.delete()
                    return@withContext Result.failure(Exception("Downloaded file too small"))
                }
                modelFile.delete()
                if (!tmp.renameTo(modelFile)) {
                    tmp.delete()
                    return@withContext Result.failure(Exception("Could not move model into place"))
                }
                onProgress(1f)
                Result.success(Unit)
            } catch (e: Exception) {
                tmp.delete()
                Result.failure(e)
            }
        }
}
