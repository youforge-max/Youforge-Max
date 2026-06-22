package eu.cisodiagonal.youforge.thumb

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-wide download status shared between [ModelDownloadService] (the writer, which
 * keeps running while the app is backgrounded) and the UI (the reader). The service owns
 * the actual work; the UI just observes this so progress shows whenever the model dialog
 * is open, regardless of which screen kicked the download off.
 */
object ModelDownloads {

    data class State(
        val running: Boolean = false,
        val slug: String = "",        // "all" for the batch download
        val name: String = "",
        val progress: Float = 0f,     // 0..1, or -1 when the size is unknown
        val message: String = "",
        val finishedAt: Long = 0L,    // bumped each time a run ends (one-shot trigger for UI)
        val success: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    fun update(transform: (State) -> State) { _state.value = transform(_state.value) }

    fun starting(slug: String, name: String, indeterminate: Boolean) = update {
        it.copy(running = true, slug = slug, name = name,
            progress = if (indeterminate) -1f else 0f, message = "Downloading $name…")
    }

    fun progress(name: String, p: Float, message: String) = update {
        it.copy(name = name, progress = p, message = message)
    }

    fun finished(success: Boolean, message: String) = update {
        it.copy(running = false, success = success, message = message,
            finishedAt = System.currentTimeMillis())
    }
}
