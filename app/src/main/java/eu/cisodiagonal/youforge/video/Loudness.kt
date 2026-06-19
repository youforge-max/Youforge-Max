package eu.cisodiagonal.youforge.video

import kotlin.math.log10
import kotlin.math.tan

/**
 * Integrated-loudness meter (ungated), simplified ITU-R BS.1770 K-weighting.
 *
 * Two-stage K-weighting (high-shelf "head" + RLB high-pass), then mean-square
 * summed across channels with unity channel weights, then -0.691 dB offset.
 * Gating is omitted — for normalize-to-target this is accurate enough and
 * matches the EBU R128 target convention used elsewhere in the workspace.
 *
 * Coefficient formulas follow pyloudnorm (analog prototype + bilinear), so the
 * filters are correct at any sample rate, not just 48 kHz.
 */
class LoudnessMeter(fs: Float, private val channels: Int) {

    private val shelf = Array(channels) { Biquad() }
    private val hp = Array(channels) { Biquad() }

    private var sumSq = 0.0
    private var samples = 0L

    init {
        // Stage 1: high-shelf ~ +4 dB.
        run {
            val fc = 1681.974450955533
            val g = 3.999843853973347
            val q = 0.7071752369554196
            val k = tan(Math.PI * fc / fs)
            val vh = Math.pow(10.0, g / 20.0)
            val vb = Math.pow(vh, 0.4996667741545416)
            val a0 = 1.0 + k / q + k * k
            val b0 = (vh + vb * k / q + k * k) / a0
            val b1 = 2.0 * (k * k - vh) / a0
            val b2 = (vh - vb * k / q + k * k) / a0
            val a1 = 2.0 * (k * k - 1.0) / a0
            val a2 = (1.0 - k / q + k * k) / a0
            for (c in 0 until channels)
                shelf[c].setCoeffs(b0.toFloat(), b1.toFloat(), b2.toFloat(), a1.toFloat(), a2.toFloat())
        }
        // Stage 2: RLB high-pass.
        run {
            val fc = 38.13547087602444
            val q = 0.5003270373238773
            val k = tan(Math.PI * fc / fs)
            val a0 = 1.0 + k / q + k * k
            val a1 = 2.0 * (k * k - 1.0) / a0
            val a2 = (1.0 - k / q + k * k) / a0
            // passband-gain-1 high-pass: b = [1, -2, 1]
            for (c in 0 until channels)
                hp[c].setCoeffs(1f, -2f, 1f, a1.toFloat(), a2.toFloat())
        }
    }

    /** Feed one block of deinterleaved samples: [ch][frames]. */
    fun add(block: Array<FloatArray>, frames: Int) {
        for (c in 0 until channels) {
            val src = block[c]
            val sh = shelf[c]; val h = hp[c]
            var acc = 0.0
            for (i in 0 until frames) {
                val y = h.process(sh.process(src[i]))
                acc += (y.toDouble() * y.toDouble())
            }
            sumSq += acc
        }
        samples += frames.toLong()
    }

    /** Integrated loudness in LUFS, or -120 for silence. */
    fun lufs(): Double {
        if (samples == 0L) return -120.0
        val meanSq = sumSq / samples.toDouble()   // summed over channels, per-frame mean
        if (meanSq <= 0.0) return -120.0
        return -0.691 + 10.0 * log10(meanSq)
    }
}
