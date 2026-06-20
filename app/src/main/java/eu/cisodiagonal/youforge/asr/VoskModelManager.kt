package eu.cisodiagonal.youforge.asr

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Owns the offline speech-to-text model (Vosk small English). Downloaded once as a
 * zip on first use, unpacked into filesDir, then everything runs on-device.
 */
class VoskModelManager(context: Context) {

    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "vosk-en")

    companion object {
        // ~40 MB, Apache-2.0, no login.
        const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    }

    /** Directory passed to Vosk's Model(), or null if not installed yet. */
    fun modelDir(): File? {
        if (!root.isDirectory) return null
        // A valid Vosk model dir contains a "conf" folder; it may be nested one level.
        if (File(root, "conf").isDirectory) return root
        return root.listFiles()?.firstOrNull { File(it, "conf").isDirectory }
    }

    fun isReady(): Boolean = modelDir() != null

    /** Download + unzip the model. [onProgress] gets 0..1 (or -1 if size unknown). */
    suspend fun ensure(onProgress: (Float) -> Unit): Result<Unit> = withContext(Dispatchers.IO) {
        if (isReady()) return@withContext Result.success(Unit)
        try {
            root.mkdirs()
            val conn = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000; readTimeout = 60_000; instanceFollowRedirects = true
            }
            conn.connect()
            if (conn.responseCode !in 200..299) {
                return@withContext Result.failure(Exception("HTTP ${conn.responseCode}"))
            }
            val total = conn.contentLengthLong
            var done = 0L
            var lastPct = -1
            ZipInputStream(conn.inputStream).use { zip ->
                var entry = zip.nextEntry
                val buf = ByteArray(1 shl 16)
                while (entry != null) {
                    val out = File(root, entry.name)
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { os ->
                            var r: Int
                            while (zip.read(buf).also { r = it } != -1) {
                                os.write(buf, 0, r)
                                done += r
                                if (total > 0) {
                                    val pct = (done * 100 / total).toInt()
                                    if (pct != lastPct) { lastPct = pct; onProgress(pct / 100f) }
                                } else onProgress(-1f)
                            }
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            if (isReady()) { onProgress(1f); Result.success(Unit) }
            else Result.failure(Exception("Unpack produced no model"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun delete(): Boolean = root.deleteRecursively()
}
