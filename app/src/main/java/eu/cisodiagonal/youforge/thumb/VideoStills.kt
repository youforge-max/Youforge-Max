package eu.cisodiagonal.youforge.thumb

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pulls candidate still frames out of a video so the user can pick one as a thumbnail
 * background. Two modes: evenly spaced across the clip (the first offer) or random
 * timestamps (the "give me 10 new ones" shuffle). Frame grabs use MediaMetadataRetriever's
 * scaled-frame API so the grid previews stay small/fast; the chosen still is re-grabbed at
 * full thumbnail size via [frameAt]. Frames are exact (OPTION_CLOSEST decodes to the target
 * timestamp, not the nearest keyframe) so the user gets the moment they actually saw.
 */
object VideoStills {

    /** One candidate: its source timestamp (µs) and a small preview bitmap. */
    data class Still(val timeUs: Long, val bmp: Bitmap)

    /** Video duration in milliseconds, or 0 if it can't be read. */
    fun durationMs(context: Context, uri: Uri): Long = runCatching {
        MediaMetadataRetriever().use { r ->
            r.setDataSource(context, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
        }
    }.getOrDefault(0L)

    /**
     * Timestamps (µs) for [count] stills. [random] = false spaces them evenly over the
     * middle ~90% of the clip; [random] = true picks [count] distinct random points (sorted).
     */
    fun timestamps(durationMs: Long, count: Int, random: Boolean): List<Long> {
        if (durationMs <= 0L || count <= 0) return emptyList()
        val fracs: List<Double> = if (random) {
            generateSequence { 0.02 + Math.random() * 0.96 }
                .distinctBy { (it * 1000).toInt() }
                .take(count).sorted().toList()
        } else {
            (0 until count).map { 0.05 + 0.90 * (it + 0.5) / count }
        }
        return fracs.map { (it * durationMs * 1000).toLong() }
    }

    /**
     * Decode [count] preview stills one at a time, invoking [onFrame] (on the main thread)
     * after each so the grid can fill in progressively — exact-frame decoding is slow, so
     * showing thumbs as they arrive beats a long blank spinner. [still] is null if that
     * timestamp couldn't be decoded. Frames scaled into [previewW] × (previewW*9/16).
     */
    suspend fun extractStreaming(
        context: Context,
        uri: Uri,
        durationMs: Long,
        count: Int = 10,
        random: Boolean = false,
        previewW: Int = 480,
        onFrame: suspend (index: Int, total: Int, still: Still?) -> Unit,
    ) {
        val ts = timestamps(durationMs, count, random)
        val previewH = previewW * 9 / 16
        withContext(Dispatchers.Default) {
            runCatching {
                MediaMetadataRetriever().use { r ->
                    r.setDataSource(context, uri)
                    ts.forEachIndexed { i, tUs ->
                        val still = r.getScaledFrameAtTime(
                            tUs, MediaMetadataRetriever.OPTION_CLOSEST, previewW, previewH
                        )?.let { Still(tUs, it) }
                        withContext(Dispatchers.Main) { onFrame(i, ts.size, still) }
                    }
                }
            }
        }
    }

    /** Re-grab a single frame at [timeUs] scaled to fit [w] × [h] (full thumbnail size). */
    fun frameAt(context: Context, uri: Uri, timeUs: Long, w: Int, h: Int): Bitmap? = runCatching {
        MediaMetadataRetriever().use { r ->
            r.setDataSource(context, uri)
            r.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, w, h)
        }
    }.getOrNull()
}
