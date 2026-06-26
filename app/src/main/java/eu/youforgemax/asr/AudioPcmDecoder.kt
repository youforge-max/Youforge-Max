package eu.youforgemax.asr

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/**
 * Decodes a video/audio file's first audio track to 16 kHz mono 16-bit PCM —
 * the format Vosk wants. Pure Android MediaCodec, no cloud. Bounded to
 * [maxSeconds] so long clips don't blow memory or time.
 */
object AudioPcmDecoder {

    private const val TIMEOUT_US = 10_000L

    fun decodeTo16kMono(context: Context, uri: Uri, maxSeconds: Int = 180): ShortArray {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    track = i; format = f; break
                }
            }
            if (track < 0 || format == null) return ShortArray(0)
            extractor.selectTrack(track)

            val mime = format.getString(MediaFormat.KEY_MIME)!!
            var srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            val mono = ArrayList<Short>(srcRate * min(maxSeconds, 30))
            var sawInputEnd = false
            var sawOutputEnd = false
            val maxMonoSamples = srcRate.toLong() * maxSeconds

            while (!sawOutputEnd && mono.size < maxMonoSamples) {
                if (!sawInputEnd) {
                    val inIdx = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)!!
                        val sz = extractor.readSampleData(inBuf, 0)
                        if (sz < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEnd = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, sz, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIdx >= 0 -> {
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEnd = true
                        if (info.size > 0) {
                            val outBuf = codec.getOutputBuffer(outIdx)!!
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            appendMono(outBuf, channels, mono)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = codec.outputFormat
                        srcRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                }
            }
            val monoArr = ShortArray(mono.size) { mono[it] }
            return resample(monoArr, srcRate, 16000)
        } catch (_: Throwable) {
            return ShortArray(0)
        } finally {
            try { codec?.stop() } catch (_: Throwable) {}
            try { codec?.release() } catch (_: Throwable) {}
            try { extractor.release() } catch (_: Throwable) {}
        }
    }

    /** Read 16-bit LE samples from [buf], downmix [channels] to mono, append. */
    private fun appendMono(buf: ByteBuffer, channels: Int, out: ArrayList<Short>) {
        val sb = buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val n = sb.remaining()
        if (channels <= 1) {
            for (i in 0 until n) out.add(sb.get())
        } else {
            var i = 0
            while (i + channels <= n) {
                var acc = 0
                for (c in 0 until channels) acc += sb.get().toInt()
                out.add((acc / channels).toShort())
                i += channels
            }
        }
    }

    /** Linear resample mono PCM from [inRate] to [outRate]. */
    private fun resample(input: ShortArray, inRate: Int, outRate: Int): ShortArray {
        if (input.isEmpty() || inRate == outRate) return input
        val outLen = (input.size.toLong() * outRate / inRate).toInt()
        val out = ShortArray(outLen)
        val ratio = inRate.toDouble() / outRate
        for (i in 0 until outLen) {
            val pos = i * ratio
            val i0 = pos.toInt()
            val frac = pos - i0
            val s0 = input[i0].toInt()
            val s1 = input[min(i0 + 1, input.size - 1)].toInt()
            out[i] = (s0 * (1 - frac) + s1 * frac).toInt().toShort()
        }
        return out
    }
}
