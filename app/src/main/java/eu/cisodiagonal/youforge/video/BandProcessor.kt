package eu.cisodiagonal.youforge.video

/**
 * Stateful per-sample processing chain for one audio stream. Build once per run,
 * then feed deinterleaved blocks via [processBlock]; filter/envelope state
 * carries across blocks.
 *
 *   x -> inputGain -> [per channel] LR4 5-band split
 *     -> [per band, stereo-linked] soft-knee compressor + makeup
 *     -> sum bands -> limiter (+ brickwall ceiling) -> out
 *
 * Crossovers are built once; [updateParams] retunes the compressor/limiter live
 * (used by the preview player) without rebuilding filter state, so edits don't
 * click. The detector envelopes intentionally persist across param changes.
 */
class BandProcessor(
    private val fs: Float,
    private val channels: Int,
    cfg: DspConfig,
    extraGainDb: Float,
) {
    private var masterOn = true
    private var inputGainLin = 1f

    private val crossovers = Array(channels) { Crossover(fs) }
    private val comps = Array(NUM_BANDS) { BandCompressor(fs) }
    private val limiter = Limiter(fs)

    // Reused scratch.
    private val bandsOf = Array(channels) { FloatArray(NUM_BANDS) }
    private val chTmp = FloatArray(channels)
    private val mix = FloatArray(channels)

    init { updateParams(cfg, extraGainDb) }

    /** Retune everything except the crossover filter state. Cheap; thread-safe to call between blocks. */
    fun updateParams(cfg: DspConfig, extraGainDb: Float) {
        masterOn = cfg.masterOn
        inputGainLin = Math.pow(10.0, ((cfg.inputGainDb + extraGainDb) / 20.0)).toFloat()
        for (b in 0 until NUM_BANDS) comps[b].apply {
            enabled = cfg.bandOn[b]
            thrDb = cfg.threshold[b]
            ratio = cfg.ratio[b]
            kneeDb = cfg.knee[b]
            makeupDb = cfg.makeup[b]
            setTimes(cfg.attack[b], cfg.release[b])
        }
        limiter.apply {
            enabled = cfg.limOn
            thrDb = cfg.limThr
            ratio = cfg.limRatio
            postDb = cfg.limPost
            setTimes(cfg.limAtk, cfg.limRel)
        }
    }

    /** Process [frames] samples in place. block = [channel][frame]. */
    fun processBlock(block: Array<FloatArray>, frames: Int) {
        if (!masterOn) {
            if (inputGainLin != 1f)
                for (c in 0 until channels) { val s = block[c]; for (i in 0 until frames) s[i] *= inputGainLin }
            return
        }
        for (i in 0 until frames) {
            for (c in 0 until channels) crossovers[c].split(block[c][i] * inputGainLin, bandsOf[c])
            for (c in 0 until channels) mix[c] = 0f
            for (b in 0 until NUM_BANDS) {
                for (c in 0 until channels) chTmp[c] = bandsOf[c][b]
                comps[b].process(chTmp, channels)
                for (c in 0 until channels) mix[c] += chTmp[c]
            }
            limiter.process(mix, channels)
            for (c in 0 until channels) block[c][i] = mix[c]
        }
    }
}
