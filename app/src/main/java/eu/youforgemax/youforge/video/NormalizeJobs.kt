package eu.youforgemax.youforge.video

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-wide normalize status shared between [NormalizeService] (the writer, which keeps
 * the offline DSP render running while the app is backgrounded) and the Video Normalizer UI
 * (the reader). Mirrors the editor's [eu.youforgemax.youforge.editor.ExportJobs] and the
 * model-download pattern: the service owns the work; the UI just observes this so progress
 * shows whenever the screen is open and reattaches if it's left/re-entered.
 *
 * [pending] is a one-shot handoff — [Request] carries the SAF in/out grants and the frozen
 * [DspConfig] (not Parcelable), parked by the UI for the service to pick up (single job).
 */
object NormalizeJobs {

    data class Request(val input: Uri, val output: Uri, val cfg: DspConfig, val outName: String)

    data class State(
        val running: Boolean = false,
        val frac: Float = 0f,         // 0..1
        val phase: String = "",
        val message: String = "",     // result text (success/error/cancel), shown when not running
        val finishedAt: Long = 0L,    // bumped each time a run ends (one-shot trigger for UI)
        val success: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    @Volatile var pending: Request? = null

    fun update(transform: (State) -> State) { _state.value = transform(_state.value) }

    fun starting() = update { State(running = true, frac = 0f, phase = "Starting") }

    fun progress(phase: String, frac: Float) = update {
        it.copy(running = true, phase = phase, frac = frac.coerceIn(0f, 1f))
    }

    fun finished(success: Boolean, message: String) = update {
        it.copy(running = false, success = success, message = message,
            finishedAt = System.currentTimeMillis())
    }
}
