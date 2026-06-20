package eu.cisodiagonal.youforge.thumb

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Owns the on-device LLM models. Multiple `.task` files can live side-by-side in
 * filesDir/models (one per slug); one is the "active" model used for inference.
 * Everything is offline once downloaded.
 */
class ModelManager(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("yf_models", Context.MODE_PRIVATE)
    private val modelsDir = File(appContext.filesDir, "models").apply { mkdirs() }
    private val legacy = File(appContext.filesDir, "gemma.task")   // pre-multi-model file

    companion object {
        const val MIN_BYTES = 1_000_000L
        const val IMPORTED_SLUG = "imported"
    }

    private fun fileFor(slug: String, format: ModelFormat) = File(modelsDir, "$slug.${format.ext}")

    /** The installed file for [slug] in whichever format is on disk, or null. */
    private fun installedFileFor(slug: String): File? =
        ModelFormat.values()
            .map { File(modelsDir, "$slug.${it.ext}") }
            .firstOrNull { it.exists() && it.length() > MIN_BYTES }

    /** Format of an on-disk model file, inferred from its extension (defaults TASK). */
    fun formatOfFile(file: File): ModelFormat =
        ModelFormat.values().firstOrNull { file.name.endsWith(".${it.ext}") } ?: ModelFormat.TASK

    fun isPresent(slug: String): Boolean = installedFileFor(slug) != null

    /** Slugs of all installed models (suggested + imported), plus legacy if present. */
    fun installedSlugs(): List<String> {
        val out = ArrayList<String>()
        SuggestedModels.all.forEach { if (isPresent(it.slug)) out.add(it.slug) }
        if (isPresent(IMPORTED_SLUG)) out.add(IMPORTED_SLUG)
        return out
    }

    var activeSlug: String?
        get() = prefs.getString("active", null)
        set(v) = prefs.edit().putString("active", v).apply()

    /** The model file used for inference, or null if none installed. */
    fun activeFile(): File? {
        activeSlug?.let { installedFileFor(it)?.let { f -> return f } }
        installedSlugs().firstOrNull()?.let { activeSlug = it; return installedFileFor(it) }
        if (legacy.exists() && legacy.length() > MIN_BYTES) return legacy
        return null
    }

    /** Format of the active model (drives which engine runs it). Null if none. */
    fun activeFormat(): ModelFormat? = activeFile()?.let { formatOfFile(it) }

    /** Any usable model installed? */
    fun isPresent(): Boolean = activeFile() != null

    private fun closeEngines() { OnDeviceLlm.close(); LlamaCppEngine.close() }

    fun setActive(slug: String): Boolean {
        if (!isPresent(slug)) return false
        if (activeSlug != slug) { closeEngines(); activeSlug = slug }
        return true
    }

    fun delete(slug: String): Boolean {
        if (activeSlug == slug) { closeEngines(); activeSlug = null }
        // Remove whichever format(s) are on disk for this slug.
        return ModelFormat.values().map { File(modelsDir, "$slug.${it.ext}").delete() }.any { it }
    }

    /** Delete every installed model (and legacy). */
    fun deleteAll() {
        closeEngines()
        activeSlug = null
        modelsDir.listFiles()?.forEach { it.delete() }
        legacy.delete()
    }

    /**
     * Download [url] into the slot for [slug]; on success it becomes active.
     * [onProgress] gets 0..1, or -1 when the server doesn't report a size.
     * When [expectedSha256] is non-null the finished file's SHA-256 must match
     * (lower-case hex) or the download is rejected and discarded.
     */
    suspend fun download(
        slug: String,
        url: String,
        expectedSha256: String? = null,
        format: ModelFormat = ModelFormat.TASK,
        onProgress: (Float) -> Unit
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            val dest = fileFor(slug, format)
            val tmp = File(modelsDir, "$slug.part")
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
                val digest = expectedSha256?.let { MessageDigest.getInstance("SHA-256") }
                tmp.delete()
                conn.inputStream.use { input ->
                    tmp.outputStream().use { output ->
                        val buf = ByteArray(1 shl 16)
                        var read: Int
                        var done = 0L
                        var lastPct = -1
                        while (input.read(buf).also { read = it } != -1) {
                            output.write(buf, 0, read)
                            digest?.update(buf, 0, read)
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
                if (tmp.length() < MIN_BYTES) {
                    tmp.delete()
                    return@withContext Result.failure(Exception("Downloaded file too small"))
                }
                if (digest != null) {
                    val got = digest.digest().joinToString("") { "%02x".format(it) }
                    if (!got.equals(expectedSha256, ignoreCase = true)) {
                        tmp.delete()
                        return@withContext Result.failure(
                            Exception("Checksum mismatch — file rejected")
                        )
                    }
                }
                dest.delete()
                if (!tmp.renameTo(dest)) {
                    tmp.delete()
                    return@withContext Result.failure(Exception("Could not move model into place"))
                }
                // Drop any other-format file for this slug so the active format is unambiguous.
                ModelFormat.values().filter { it != format }
                    .forEach { File(modelsDir, "$slug.${it.ext}").delete() }
                setActive(slug)
                onProgress(1f)
                Result.success(Unit)
            } catch (e: Exception) {
                tmp.delete()
                Result.failure(e)
            }
        }

    /**
     * Download every suggested (ungated) model not already present, sequentially.
     * [onModel] reports the current model name + its 0..1 progress; [onDone] the
     * per-model success. Stops nothing on a single failure — continues the rest.
     */
    suspend fun downloadAll(
        onModel: (name: String, index: Int, count: Int, progress: Float) -> Unit
    ): Result<Int> = withContext(Dispatchers.IO) {
        val todo = SuggestedModels.all.filter { !it.gated && !isPresent(it.slug) }
        if (todo.isEmpty()) return@withContext Result.success(0)
        var ok = 0
        todo.forEachIndexed { i, m ->
            onModel(m.name, i + 1, todo.size, 0f)
            val res = download(m.slug, m.url, m.sha256, m.format) { p -> onModel(m.name, i + 1, todo.size, p) }
            if (res.isSuccess) ok++
        }
        Result.success(ok)
    }

    /**
     * Import a model the user already has (SAF document URI) — copied once into the
     * "imported" slot and made active. No network.
     */
    suspend fun importFromFile(uri: android.net.Uri): Result<Unit> =
        withContext(Dispatchers.IO) {
            val tmp = File(modelsDir, "$IMPORTED_SLUG.part")
            try {
                tmp.delete()
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { input.copyTo(it, 1 shl 16) }
                } ?: return@withContext Result.failure(Exception("Cannot open that file"))
                if (tmp.length() < MIN_BYTES) {
                    tmp.delete()
                    return@withContext Result.failure(Exception("File too small to be a model"))
                }
                // Sniff the format from the file's magic: "GGUF" => GGUF, else .task (a zip).
                val format = if (sniffIsGguf(tmp)) ModelFormat.GGUF else ModelFormat.TASK
                val dest = fileFor(IMPORTED_SLUG, format)
                // Clear any previous imported file of either format.
                ModelFormat.values().forEach { File(modelsDir, "$IMPORTED_SLUG.${it.ext}").delete() }
                if (!tmp.renameTo(dest)) {
                    tmp.delete()
                    return@withContext Result.failure(Exception("Could not move model into place"))
                }
                setActive(IMPORTED_SLUG)
                Result.success(Unit)
            } catch (e: Exception) {
                tmp.delete()
                Result.failure(e)
            }
        }

    /** GGUF files begin with the ASCII magic "GGUF". */
    private fun sniffIsGguf(file: File): Boolean = try {
        val b = ByteArray(4)
        file.inputStream().use { it.read(b) == 4 } &&
            b.contentEquals(byteArrayOf(0x47, 0x47, 0x55, 0x46))
    } catch (_: Exception) { false }
}
