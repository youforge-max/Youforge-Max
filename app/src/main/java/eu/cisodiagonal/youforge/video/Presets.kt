package eu.cisodiagonal.youforge.video

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/**
 * Plain immutable snapshot of all DSP parameters, read by the offline pipeline
 * on a background thread (no Compose state access off the UI thread).
 */
data class DspConfig(
    val masterOn: Boolean,
    val inputGainDb: Float,
    val normalizeOn: Boolean,
    val targetLufs: Float,
    val bandOn: BooleanArray,
    val threshold: FloatArray,
    val attack: FloatArray,
    val release: FloatArray,
    val ratio: FloatArray,
    val knee: FloatArray,
    val makeup: FloatArray,
    val limOn: Boolean,
    val limThr: Float,
    val limAtk: Float,
    val limRel: Float,
    val limRatio: Float,
    val limPost: Float,
)

/**
 * Single observable source of truth for every control. Compose reads/writes it;
 * snapshot() freezes it for the worker; JSON (de)serialises presets.
 * Mirrors five-band-comp's UiState plus normalize target + per-band knee.
 */
class UiState {
    private val N = NUM_BANDS

    var masterOn by mutableStateOf(true)
    var inputGain by mutableFloatStateOf(0f)
    var normalizeOn by mutableStateOf(true)
    var targetLufs by mutableFloatStateOf(-14f)

    val bandOn = mutableStateListOf(*Array(N) { true })
    val threshold = mutableStateListOf(*Array(N) { -24f })
    val attack = mutableStateListOf(*Array(N) { 20f })
    val release = mutableStateListOf(*Array(N) { 150f })
    val ratio = mutableStateListOf(*Array(N) { 4f })
    val knee = mutableStateListOf(*Array(N) { 6f })
    val makeup = mutableStateListOf(*Array(N) { 0f })

    var limOn by mutableStateOf(true)
    var limThr by mutableFloatStateOf(-1f)
    var limAtk by mutableFloatStateOf(1f)
    var limRel by mutableFloatStateOf(60f)
    var limRatio by mutableFloatStateOf(10f)
    var limPost by mutableFloatStateOf(0f)

    fun snapshot(): DspConfig = DspConfig(
        masterOn = masterOn,
        inputGainDb = inputGain,
        normalizeOn = normalizeOn,
        targetLufs = targetLufs,
        bandOn = bandOn.toBooleanArray(),
        threshold = threshold.toFloatArray(),
        attack = attack.toFloatArray(),
        release = release.toFloatArray(),
        ratio = ratio.toFloatArray(),
        knee = knee.toFloatArray(),
        makeup = makeup.toFloatArray(),
        limOn = limOn, limThr = limThr, limAtk = limAtk, limRel = limRel,
        limRatio = limRatio, limPost = limPost,
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("masterOn", masterOn)
        put("inputGain", inputGain.toDouble())
        put("normalizeOn", normalizeOn)
        put("targetLufs", targetLufs.toDouble())
        put("bandOn", JSONArray(bandOn.toList()))
        put("threshold", farr(threshold)); put("attack", farr(attack))
        put("release", farr(release)); put("ratio", farr(ratio))
        put("knee", farr(knee)); put("makeup", farr(makeup))
        put("limOn", limOn); put("limThr", limThr.toDouble()); put("limAtk", limAtk.toDouble())
        put("limRel", limRel.toDouble()); put("limRatio", limRatio.toDouble())
        put("limPost", limPost.toDouble())
    }

    fun fromJson(o: JSONObject) {
        masterOn = o.optBoolean("masterOn", true)
        inputGain = o.optDouble("inputGain", 0.0).toFloat()
        normalizeOn = o.optBoolean("normalizeOn", true)
        targetLufs = o.optDouble("targetLufs", -14.0).toFloat()
        readBool(o, "bandOn", bandOn)
        readF(o, "threshold", threshold); readF(o, "attack", attack)
        readF(o, "release", release); readF(o, "ratio", ratio)
        readF(o, "knee", knee); readF(o, "makeup", makeup)
        limOn = o.optBoolean("limOn", true)
        limThr = o.optDouble("limThr", -1.0).toFloat()
        limAtk = o.optDouble("limAtk", 1.0).toFloat()
        limRel = o.optDouble("limRel", 60.0).toFloat()
        limRatio = o.optDouble("limRatio", 10.0).toFloat()
        limPost = o.optDouble("limPost", 0.0).toFloat()
    }

    private fun farr(l: List<Float>) = JSONArray().apply { l.forEach { put(it.toDouble()) } }
    private fun readF(o: JSONObject, k: String, dst: MutableList<Float>) {
        val a = o.optJSONArray(k) ?: return
        for (i in 0 until minOf(a.length(), dst.size)) dst[i] = a.optDouble(i).toFloat()
    }
    private fun readBool(o: JSONObject, k: String, dst: MutableList<Boolean>) {
        val a = o.optJSONArray(k) ?: return
        for (i in 0 until minOf(a.length(), dst.size)) dst[i] = a.optBoolean(i)
    }
}

/** Named presets in SharedPreferences (one JSON blob per name). */
class PresetRepo(ctx: Context) {
    private val sp = ctx.getSharedPreferences("presets", Context.MODE_PRIVATE)

    /** User-facing preset names — excludes the reserved last-session slot. */
    fun names(): List<String> = sp.all.keys.filter { it != LAST_KEY }.sorted()

    fun save(name: String, state: UiState) {
        sp.edit().putString(name, state.toJson().toString()).apply()
    }

    fun load(name: String, into: UiState): Boolean {
        val s = sp.getString(name, null) ?: return false
        return try { into.fromJson(JSONObject(s)); true } catch (_: Exception) { false }
    }

    fun delete(name: String) = sp.edit().remove(name).apply()

    /** Autosaved working settings so the screen reopens on the last-used profile. */
    fun saveLast(state: UiState) {
        sp.edit().putString(LAST_KEY, state.toJson().toString()).apply()
    }

    fun loadLast(into: UiState): Boolean {
        val s = sp.getString(LAST_KEY, null) ?: return false
        return try { into.fromJson(JSONObject(s)); true } catch (_: Exception) { false }
    }

    private companion object { const val LAST_KEY = "__last_session__" }
}
