package eu.cisodiagonal.youforge.video

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Offline video-audio normalizer.
 *
 *   pass 1 (optional): decode audio -> measure integrated loudness -> target gain
 *   pass 2: decode audio -> 5-band comp/limiter (+ target gain) -> AAC encode
 *           -> mux (original video track copied verbatim + new audio) -> MP4
 *
 * Encoded audio is buffered in memory before muxing (so the video track can be
 * added first); fine for typical clips, heavy for multi-hour files.
 */
object MediaProcessor {

    private const val TAG = "MediaProcessor"
    private const val TIMEOUT_US = 10_000L
    private const val AAC_BITRATE = 192_000
    private const val GAIN_CLAMP_DB = 24f

    class ProcessException(msg: String) : Exception(msg)

    /** progress(phase, fraction 0..1). Runs on the calling (worker) thread. */
    fun process(
        ctx: Context,
        inputUri: Uri,
        outputUri: Uri,
        cfg: DspConfig,
        progress: (String, Float) -> Unit,
    ) {
        val cr = ctx.contentResolver

        // --- discover tracks / duration --------------------------------------
        var sampleRate = 48000
        var channels = 2
        var durationUs = 0L
        run {
            val ex = MediaExtractor()
            cr.openFileDescriptor(inputUri, "r")!!.use { ex.setDataSource(it.fileDescriptor) }
            try {
                val a = audioTrack(ex) ?: throw ProcessException("No audio track in input")
                val f = ex.getTrackFormat(a)
                sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                if (f.containsKey(MediaFormat.KEY_DURATION)) durationUs = f.getLong(MediaFormat.KEY_DURATION)
            } finally { ex.release() }
        }
        if (channels < 1) channels = 1

        // --- pass 1: loudness ------------------------------------------------
        var extraGainDb = 0f
        if (cfg.normalizeOn) {
            val meter = LoudnessMeter(sampleRate.toFloat(), channels)
            decodeAudio(ctx, inputUri, durationUs, onFormat = { _, _ -> }) { block, frames, _ ->
                meter.add(block, frames)
                progress("Analysing loudness", 0.45f * progressFrac())
            }
            val measured = meter.lufs()
            extraGainDb = (cfg.targetLufs - measured).toFloat()
                .coerceIn(-GAIN_CLAMP_DB, GAIN_CLAMP_DB)
            Log.i(TAG, "Measured ${"%.1f".format(measured)} LUFS -> gain ${"%.1f".format(extraGainDb)} dB")
        }

        // --- pass 2: process + encode (buffer packets) -----------------------
        val proc = BandProcessor(sampleRate.toFloat(), channels, cfg, extraGainDb)
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val outFmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AAC_BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
        }
        encoder.configure(outFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        val encoded = ArrayList<EncPacket>()
        var encoderFormat: MediaFormat? = null
        var encInFrames = 0L
        val info = MediaCodec.BufferInfo()

        fun drainEncoder(endOfStream: Boolean) {
            while (true) {
                val idx = encoder.dequeueOutputBuffer(info, if (endOfStream) TIMEOUT_US else 0)
                when {
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> encoderFormat = encoder.outputFormat
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return else continue
                    idx >= 0 -> {
                        val buf = encoder.getOutputBuffer(idx)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                            val data = ByteArray(info.size)
                            buf.position(info.offset); buf.limit(info.offset + info.size)
                            buf.get(data)
                            encoded.add(EncPacket(data, info.presentationTimeUs, info.flags))
                        }
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        encoder.releaseOutputBuffer(idx, false)
                        if (eos) return
                    }
                }
                if (!endOfStream) return
            }
        }

        // PCM byte queue feeding the encoder input buffers.
        fun feedPcm(bytes: ByteArray, eos: Boolean) {
            var off = 0
            while (off < bytes.size || eos) {
                val inIdx = encoder.dequeueInputBuffer(TIMEOUT_US)
                if (inIdx < 0) { drainEncoder(false); if (off >= bytes.size && !eos) return; continue }
                val inBuf = encoder.getInputBuffer(inIdx)!!
                inBuf.clear()
                val n = minOf(inBuf.remaining(), bytes.size - off)
                if (n > 0) inBuf.put(bytes, off, n)
                val pts = encInFrames * 1_000_000L / sampleRate
                val frames = n / (2 * channels)
                encInFrames += frames
                off += n
                val flags = if (eos && off >= bytes.size) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                encoder.queueInputBuffer(inIdx, 0, n, pts, flags)
                drainEncoder(false)
                if (eos && off >= bytes.size) return
            }
        }

        decodeAudio(ctx, inputUri, durationUs, onFormat = { _, _ -> }) { block, frames, _ ->
            proc.processBlock(block, frames)
            feedPcm(interleave(block, frames, channels), eos = false)
            progress("Processing audio", 0.45f + 0.45f * progressFrac())
        }
        feedPcm(ByteArray(0), eos = true)
        drainEncoder(true)
        val aacFormat = encoderFormat ?: run {
            encoder.stop(); encoder.release(); throw ProcessException("Encoder produced no output format")
        }
        encoder.stop(); encoder.release()

        // --- mux: copy video track + write encoded audio ---------------------
        progress("Writing output", 0.92f)
        writeOutput(ctx, inputUri, outputUri, aacFormat, encoded)
        progress("Done", 1f)
    }

    private class EncPacket(val data: ByteArray, val ptsUs: Long, val flags: Int)

    /** Decoded audio held in memory for realtime preview. */
    class PreviewBuffer(
        val samples: Array<FloatArray>,   // [channel][frame], whole captured segment
        val sampleRate: Int,
        val channels: Int,
    ) {
        val frames: Int get() = if (samples.isEmpty()) 0 else samples[0].size
    }

    /** Audio duration in milliseconds (0 if unknown). */
    fun durationMs(ctx: Context, inputUri: Uri): Long {
        val r = android.media.MediaMetadataRetriever()
        return try {
            ctx.contentResolver.openFileDescriptor(inputUri, "r")!!.use { r.setDataSource(it.fileDescriptor) }
            r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }

    /**
     * Decode up to [maxSeconds] of the audio track into memory for preview,
     * starting at [startSeconds]. Returns null if there is no audio track.
     */
    fun decodeToBuffer(ctx: Context, inputUri: Uri, startSeconds: Int, maxSeconds: Int): PreviewBuffer? {
        var sr = 48000
        var ch = 2
        val chans = ArrayList<ArrayList<Float>>()
        var capFrames = Long.MAX_VALUE
        try {
            decodeAudio(ctx, inputUri, 0L, startUs = startSeconds.toLong() * 1_000_000L, onFormat = { s, c ->
                sr = s; ch = c
                capFrames = maxSeconds.toLong() * sr
                while (chans.size < ch) chans.add(ArrayList(sr * maxSeconds))
            }, capFramesProvider = { capFrames }) { block, frames, _ ->
                for (c in 0 until block.size) {
                    val dst = chans[c]
                    val src = block[c]
                    for (i in 0 until frames) dst.add(src[i])
                }
            }
        } catch (e: ProcessException) {
            return null
        }
        if (chans.isEmpty() || chans[0].isEmpty()) return null
        val out = Array(chans.size) { c -> FloatArray(chans[c].size) { chans[c][it] } }
        return PreviewBuffer(out, sr, ch)
    }

    private var lastFrac = 0f
    private fun progressFrac(): Float = lastFrac

    // ---- decode helper ------------------------------------------------------

    /**
     * Decode the audio track to deinterleaved float blocks.
     * onBlock(block[ch][frames], frames, sampleRate). onFormat(sr, ch) first.
     */
    private fun decodeAudio(
        ctx: Context,
        inputUri: Uri,
        durationUs: Long,
        startUs: Long = 0L,
        onFormat: (Int, Int) -> Unit,
        capFramesProvider: () -> Long = { Long.MAX_VALUE },
        onBlock: (Array<FloatArray>, Int, Int) -> Unit,
    ) {
        val ex = MediaExtractor()
        val pfd = ctx.contentResolver.openFileDescriptor(inputUri, "r")!!
        ex.setDataSource(pfd.fileDescriptor)
        val track = audioTrack(ex) ?: run { ex.release(); pfd.close(); throw ProcessException("No audio track") }
        ex.selectTrack(track)
        if (startUs > 0L) ex.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        val inFmt = ex.getTrackFormat(track)
        val mime = inFmt.getString(MediaFormat.KEY_MIME)!!
        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(inFmt, null, null, 0)
        decoder.start()

        var channels = inFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var sampleRate = inFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        onFormat(sampleRate, channels)

        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var producedFrames = 0L
        try {
            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val buf = decoder.getInputBuffer(inIdx)!!
                        val sz = ex.readSampleData(buf, 0)
                        if (sz < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, sz, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val outIdx = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = decoder.outputFormat
                        channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        onFormat(sampleRate, channels)
                    }
                    outIdx >= 0 -> {
                        if (info.size > 0) {
                            val buf = decoder.getOutputBuffer(outIdx)!!
                            buf.position(info.offset); buf.limit(info.offset + info.size)
                            val block = deinterleave(buf, info.size, channels)
                            val frames = info.size / (2 * channels)
                            if (durationUs > 0) lastFrac = (info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f)
                            onBlock(block, frames, sampleRate)
                            producedFrames += frames
                            if (producedFrames >= capFramesProvider()) outputDone = true
                        }
                        decoder.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } finally {
            decoder.stop(); decoder.release(); ex.release(); pfd.close()
        }
    }

    private fun writeOutput(
        ctx: Context,
        inputUri: Uri,
        outputUri: Uri,
        aacFormat: MediaFormat,
        encoded: List<EncPacket>,
    ) {
        val outPfd = ctx.contentResolver.openFileDescriptor(outputUri, "rw")!!
        val muxer = MediaMuxer(outPfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        // Video source extractor.
        val vex = MediaExtractor()
        val vpfd = ctx.contentResolver.openFileDescriptor(inputUri, "r")!!
        vex.setDataSource(vpfd.fileDescriptor)
        val vTrack = videoTrack(vex)
        val videoMux = if (vTrack >= 0) muxer.addTrack(vex.getTrackFormat(vTrack)) else -1
        val audioMux = muxer.addTrack(aacFormat)
        muxer.start()

        try {
            // Audio: write buffered packets (monotonic pts).
            val info = MediaCodec.BufferInfo()
            for (p in encoded) {
                val bb = ByteBuffer.wrap(p.data)
                info.set(0, p.data.size, p.ptsUs, p.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv())
                muxer.writeSampleData(audioMux, bb, info)
            }
            // Video: copy samples verbatim.
            if (vTrack >= 0) {
                vex.selectTrack(vTrack)
                val cap = maxInputSize(vex.getTrackFormat(vTrack))
                val buf = ByteBuffer.allocate(cap)
                val vInfo = MediaCodec.BufferInfo()
                while (true) {
                    val sz = vex.readSampleData(buf, 0)
                    if (sz < 0) break
                    vInfo.presentationTimeUs = vex.sampleTime
                    vInfo.flags = sampleFlagsToBufferFlags(vex.sampleFlags)
                    vInfo.offset = 0
                    vInfo.size = sz
                    muxer.writeSampleData(videoMux, buf, vInfo)
                    vex.advance()
                }
            }
        } finally {
            try { muxer.stop() } catch (e: Exception) { Log.e(TAG, "muxer stop", e) }
            muxer.release()
            vex.release(); vpfd.close(); outPfd.close()
        }
    }

    // ---- small helpers ------------------------------------------------------

    private fun audioTrack(ex: MediaExtractor): Int? {
        for (i in 0 until ex.trackCount) {
            val m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (m.startsWith("audio/")) return i
        }
        return null
    }

    private fun videoTrack(ex: MediaExtractor): Int {
        for (i in 0 until ex.trackCount) {
            val m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (m.startsWith("video/")) return i
        }
        return -1
    }

    private fun maxInputSize(f: MediaFormat): Int =
        if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(256 * 1024)
        else 1024 * 1024

    private fun sampleFlagsToBufferFlags(sampleFlags: Int): Int {
        var f = 0
        if (sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) f = f or MediaCodec.BUFFER_FLAG_KEY_FRAME
        return f
    }

    /** Interleaved 16-bit LE ByteBuffer -> [channel][frame] floats in [-1,1]. */
    private fun deinterleave(buf: ByteBuffer, sizeBytes: Int, channels: Int): Array<FloatArray> {
        val sb = buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val total = sizeBytes / 2
        val frames = total / channels
        val out = Array(channels) { FloatArray(frames) }
        var idx = 0
        for (i in 0 until frames) {
            for (c in 0 until channels) out[c][i] = sb.get(idx++) / 32768f
        }
        return out
    }

    /** [channel][frame] floats -> interleaved 16-bit LE bytes. */
    private fun interleave(block: Array<FloatArray>, frames: Int, channels: Int): ByteArray {
        val out = ByteArray(frames * channels * 2)
        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        val sb = bb.asShortBuffer()
        var idx = 0
        for (i in 0 until frames) {
            for (c in 0 until channels) {
                val v = (block[c][i] * 32767f).roundToInt().coerceIn(-32768, 32767)
                sb.put(idx++, v.toShort())
            }
        }
        return out
    }
}
