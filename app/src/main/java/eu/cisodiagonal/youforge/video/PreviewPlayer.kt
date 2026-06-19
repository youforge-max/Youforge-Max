package eu.cisodiagonal.youforge.video

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Realtime preview of the offline chain. Loops a decoded segment through an
 * AudioTrack, applying the SAME BandProcessor used for file rendering, and
 * retunes from a live config provider each block so slider edits are audible
 * immediately. What you hear here is what the render produces (loop seam aside).
 */
class PreviewPlayer(
    private val buf: MediaProcessor.PreviewBuffer,
    private val configProvider: () -> DspConfig,
) {
    private val fs = buf.sampleRate
    private val channels = buf.channels.coerceAtLeast(1)

    // Integrated loudness of the preview segment, for the normalize gain.
    private val measuredLufs: Double = run {
        val m = LoudnessMeter(fs.toFloat(), channels)
        m.add(buf.samples, buf.frames)
        m.lufs()
    }

    @Volatile private var running = false
    private var thread: Thread? = null

    val isPlaying: Boolean get() = running

    fun start() {
        if (running || buf.frames == 0) return
        running = true
        thread = Thread { runLoop() }.also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(800)
        thread = null
    }

    private fun extraGain(cfg: DspConfig): Float =
        if (cfg.normalizeOn) (cfg.targetLufs - measuredLufs).toFloat().coerceIn(-24f, 24f) else 0f

    private fun runLoop() {
        val chMask = if (channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minBuf = AudioTrack.getMinBufferSize(fs, chMask, AudioFormat.ENCODING_PCM_16BIT)
        val block = 4096
        val bufBytes = maxOf(minBuf, block * channels * 2 * 4)
        @Suppress("DEPRECATION")
        val track = AudioTrack(
            AudioManager.STREAM_MUSIC, fs, chMask,
            AudioFormat.ENCODING_PCM_16BIT, bufBytes, AudioTrack.MODE_STREAM
        )
        val proc = BandProcessor(fs.toFloat(), channels, configProvider(), 0f)
        val work = Array(channels) { FloatArray(block) }
        val out = ShortArray(block * channels)
        val total = buf.frames
        var pos = 0
        try {
            track.play()
            while (running) {
                val cfg = configProvider()
                proc.updateParams(cfg, extraGain(cfg))
                val n = min(block, total - pos)
                for (c in 0 until channels) System.arraycopy(buf.samples[c], pos, work[c], 0, n)
                proc.processBlock(work, n)
                var k = 0
                for (i in 0 until n) for (c in 0 until channels) {
                    val v = (work[c][i] * 32767f).roundToInt().coerceIn(-32768, 32767)
                    out[k++] = v.toShort()
                }
                track.write(out, 0, n * channels)
                pos += n
                if (pos >= total) pos = 0   // loop
            }
        } catch (_: Exception) {
        } finally {
            try { track.stop() } catch (_: Exception) {}
            track.release()
        }
    }
}
