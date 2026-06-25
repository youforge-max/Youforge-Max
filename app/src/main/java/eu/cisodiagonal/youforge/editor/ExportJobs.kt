package eu.cisodiagonal.youforge.editor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-wide export status shared between [VideoExportService] (the writer, which keeps
 * the Media3 export running while the app is backgrounded) and the editor UI (the reader).
 * Mirrors the model-download pattern ([eu.cisodiagonal.youforge.thumb.ModelDownloads]): the
 * service owns the work; the UI observes this so progress shows whenever the editor is open,
 * and survives the screen leaving/re-entering composition.
 *
 * [pending] is a one-shot handoff for the [EditorProject] — it isn't Parcelable and only one
 * export runs at a time, so the UI parks it here and the service picks it up on start.
 */
object ExportJobs {

    data class State(
        val running: Boolean = false,
        val progress: Int = 0,        // 0..100
        val message: String = "",
        val finishedAt: Long = 0L,    // bumped each time a run ends (one-shot trigger for UI)
        val success: Boolean = false,
        val outputName: String = "",  // file name of the finished export
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /** Project parked by the UI for the service to pick up (single active export). */
    @Volatile var pending: EditorProject? = null

    fun update(transform: (State) -> State) { _state.value = transform(_state.value) }

    fun starting() = update {
        State(running = true, progress = 0, message = "Exporting…")
    }

    fun progress(percent: Int) = update {
        it.copy(running = true, progress = percent.coerceIn(0, 100))
    }

    fun finished(success: Boolean, message: String, outputName: String = "") = update {
        it.copy(running = false, success = success, message = message,
            outputName = outputName, progress = if (success) 100 else it.progress,
            finishedAt = System.currentTimeMillis())
    }
}
