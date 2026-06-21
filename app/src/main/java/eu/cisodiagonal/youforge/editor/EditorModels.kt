package eu.cisodiagonal.youforge.editor

import android.net.Uri

/**
 * One clip on the editor timeline. [trimStartMs]/[trimEndMs] are the kept range
 * within the source; [speed] is a playback multiplier (1.0 = normal). The editor is
 * a flat single-track sequence for now (YouForge Max P1) — multi-track/overlays land
 * in later phases.
 */
data class Clip(
    val uri: Uri,
    val durationMs: Long,
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = durationMs,
    val speed: Float = 1f,
    val muted: Boolean = false,
) {
    /** Output duration of this clip after trim + speed. */
    val outMs: Long get() = ((trimEndMs - trimStartMs).coerceAtLeast(0) / speed).toLong()
}

/** Export resolution (output height; width follows source aspect). */
enum class ExportResolution(val label: String, val height: Int) {
    P480("480p", 480), P720("720p", 720), P1080("1080p", 1080);
}

/** The whole edit: an ordered list of clips rendered head-to-tail. */
data class EditorProject(
    val clips: List<Clip> = emptyList(),
    val resolution: ExportResolution = ExportResolution.P720,
    val title: String = "",
) {
    val totalOutMs: Long get() = clips.sumOf { it.outMs }
    val isEmpty: Boolean get() = clips.isEmpty()
}
