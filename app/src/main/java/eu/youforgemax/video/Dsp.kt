package eu.youforgemax.video

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Offline software port of the live app's 5-band compressor / limiter.
 *
 * The live app (five-band-comp) uses android.media.audiofx.DynamicsProcessing,
 * a REALTIME effect bolted to the output mix — it has no file-render API. To
 * process a file we reimplement the same signal chain in pure Kotlin on PCM:
 *
 *   input gain -> LR4 5-band crossover -> per-band soft-knee compressor
 *              -> makeup -> sum -> limiter (+ brickwall ceiling)
 *
 * The per-band gain computer is the same soft-knee downward-compression curve
 * as DspEngine.estimateGrDb(), so presets behave consistently across the apps.
 *
 * Crossover note: this uses sequential Linkwitz-Riley 4th-order splits without
 * allpass phase compensation. Because each band is independently gain-shaped,
 * perfect magnitude reconstruction is unattainable anyway; minor coloration at
 * the crossover frequencies is expected and matches typical multiband behaviour.
 */

private const val EPS = 1e-9f

/** Upper crossover cutoffs (Hz); same defaults as the live app. */
val DEFAULT_CUTOFFS = floatArrayOf(150f, 600f, 2_500f, 7_000f, 20_000f)
const val NUM_BANDS = 5

/** Soft-knee downward gain reduction (dB, >=0). Mirrors DspEngine.estimateGrDb(). */
fun grDb(levelDb: Float, thrDb: Float, ratio: Float, kneeDb: Float): Float {
    val r = ratio.coerceAtLeast(1f)
    val w = kneeDb.coerceAtLeast(0f)
    val over = levelDb - thrDb
    val slope = 1f - 1f / r
    return when {
        2f * over < -w -> 0f
        w > 0f && 2f * abs(over) <= w -> {
            val x = over + w / 2f
            slope * x * x / (2f * w)
        }
        else -> slope * over
    }.coerceAtLeast(0f)
}

private fun lin(db: Float): Float = Math.pow(10.0, (db / 20.0)).toFloat()
private fun db(lin: Float): Float = (20.0 * Math.log10((abs(lin) + EPS).toDouble())).toFloat()

/** Transposed-Direct-Form-II biquad. */
class Biquad {
    private var b0 = 1f; private var b1 = 0f; private var b2 = 0f
    private var a1 = 0f; private var a2 = 0f
    private var z1 = 0f; private var z2 = 0f

    fun reset() { z1 = 0f; z2 = 0f }

    fun setCoeffs(b0: Float, b1: Float, b2: Float, a1: Float, a2: Float) {
        this.b0 = b0; this.b1 = b1; this.b2 = b2; this.a1 = a1; this.a2 = a2
    }

    /** RBJ cookbook low-pass. */
    fun lowpass(fs: Float, fc: Float, q: Float) {
        val w0 = 2.0 * Math.PI * (fc / fs)
        val cs = cos(w0); val sn = sin(w0)
        val alpha = sn / (2.0 * q)
        val a0 = 1 + alpha
        setCoeffs(
            (((1 - cs) / 2) / a0).toFloat(),
            ((1 - cs) / a0).toFloat(),
            (((1 - cs) / 2) / a0).toFloat(),
            ((-2 * cs) / a0).toFloat(),
            ((1 - alpha) / a0).toFloat()
        )
    }

    /** RBJ cookbook high-pass. */
    fun highpass(fs: Float, fc: Float, q: Float) {
        val w0 = 2.0 * Math.PI * (fc / fs)
        val cs = cos(w0); val sn = sin(w0)
        val alpha = sn / (2.0 * q)
        val a0 = 1 + alpha
        setCoeffs(
            (((1 + cs) / 2) / a0).toFloat(),
            (-(1 + cs) / a0).toFloat(),
            (((1 + cs) / 2) / a0).toFloat(),
            ((-2 * cs) / a0).toFloat(),
            ((1 - alpha) / a0).toFloat()
        )
    }

    fun process(x: Float): Float {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }
}

/**
 * One-channel sequential LR4 splitter producing NUM_BANDS outputs.
 * LR4 = two cascaded Butterworth (Q = 1/sqrt2) biquads.
 */
class Crossover(fs: Float) {
    private val splits = NUM_BANDS - 1
    private val q = (1.0 / sqrt(2.0)).toFloat()
    private val lp = Array(splits) { Array(2) { Biquad() } }
    private val hp = Array(splits) { Array(2) { Biquad() } }

    init {
        for (k in 0 until splits) {
            val fc = DEFAULT_CUTOFFS[k]
            lp[k][0].lowpass(fs, fc, q); lp[k][1].lowpass(fs, fc, q)
            hp[k][0].highpass(fs, fc, q); hp[k][1].highpass(fs, fc, q)
        }
    }

    /** Split one sample into [out] (size NUM_BANDS). */
    fun split(x: Float, out: FloatArray) {
        var acc = x
        for (k in 0 until splits) {
            val low = lp[k][1].process(lp[k][0].process(acc))
            val high = hp[k][1].process(hp[k][0].process(acc))
            out[k] = low
            acc = high
        }
        out[NUM_BANDS - 1] = acc
    }
}

/** Per-band feed-forward compressor with linked-stereo detection. */
class BandCompressor(private val fs: Float) {
    var enabled = true
    var thrDb = -24f
    var ratio = 4f
    var kneeDb = 6f
    var makeupDb = 0f

    private var atkCoeff = 0f
    private var relCoeff = 0f
    private var grSmoothed = 0f   // current smoothed gain reduction (dB, >=0)

    fun setTimes(attackMs: Float, releaseMs: Float) {
        atkCoeff = tc(attackMs)
        relCoeff = tc(releaseMs)
    }

    private fun tc(ms: Float): Float {
        val t = (ms.coerceAtLeast(0.01f)) * 0.001f
        return exp((-1.0 / (t * fs))).toFloat()
    }

    /** Apply compression to the band samples for all channels (linked detector). */
    fun process(ch: FloatArray, n: Int) {
        if (!enabled) return
        var peak = EPS
        for (c in 0 until n) { val a = abs(ch[c]); if (a > peak) peak = a }
        val target = grDb(db(peak), thrDb, ratio, kneeDb)
        val coeff = if (target > grSmoothed) atkCoeff else relCoeff
        grSmoothed = target + coeff * (grSmoothed - target)
        val gain = lin(makeupDb - grSmoothed)
        for (c in 0 until n) ch[c] *= gain
    }
}

/** Output limiter: fast linked peak compressor + hard brickwall ceiling. */
class Limiter(private val fs: Float) {
    var enabled = true
    var thrDb = -1f
    var ratio = 10f
    var postDb = 0f

    private var atkCoeff = 0f
    private var relCoeff = 0f
    private var grSmoothed = 0f

    fun setTimes(attackMs: Float, releaseMs: Float) {
        atkCoeff = exp((-1.0 / ((attackMs.coerceAtLeast(0.01f) * 0.001f) * fs))).toFloat()
        relCoeff = exp((-1.0 / ((releaseMs.coerceAtLeast(0.01f) * 0.001f) * fs))).toFloat()
    }

    private fun ceil(): Float = lin(thrDb)

    fun process(ch: FloatArray, n: Int) {
        if (enabled) {
            var peak = EPS
            for (c in 0 until n) { val a = abs(ch[c]); if (a > peak) peak = a }
            val target = grDb(db(peak), thrDb, ratio.coerceAtLeast(2f), 2f)
            val coeff = if (target > grSmoothed) atkCoeff else relCoeff
            grSmoothed = target + coeff * (grSmoothed - target)
            val gain = lin(postDb - grSmoothed)
            for (c in 0 until n) ch[c] *= gain
        }
        // Brickwall safety: never exceed the ceiling regardless of ballistics.
        val cap = ceil()
        for (c in 0 until n) {
            if (ch[c] > cap) ch[c] = cap
            else if (ch[c] < -cap) ch[c] = -cap
        }
    }
}
