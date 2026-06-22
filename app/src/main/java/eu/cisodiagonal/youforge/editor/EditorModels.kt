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
    val volume: Float = 1f,
    val rotationDeg: Int = 0,
) {
    /** Output duration of this clip after trim + speed. */
    val outMs: Long get() = ((trimEndMs - trimStartMs).coerceAtLeast(0) / speed).toLong()
}

/** Export resolution (output height; width follows source aspect). */
enum class ExportResolution(val label: String, val height: Int) {
    P480("480p", 480), P720("720p", 720), P1080("1080p", 1080), P2160("4K", 2160);
}

/** Colour filter applied to the whole timeline at export (and the live preview). */
enum class VideoFilter(val label: String) {
    NONE("None"), GRAYSCALE("B&W"), VIVID("Vivid"), WARM("Warm"), COOL("Cool"), CONTRAST("Punch");
}

/**
 * Transition at each clip's edges (applied in the clip's own output timeline).
 * FADE = fade in/out of black; SLIDE = slide in from left / out to right over black;
 * ZOOM = punch-zoom in at the start and out at the end; CROSSFADE = true dissolve between
 * consecutive clips (two overlapping sequences, alpha-ramped).
 */
enum class Transition(val label: String) {
    NONE("None"), FADE("Fade"), SLIDE("Slide"), ZOOM("Zoom"), CROSSFADE("Crossfade");
}

/**
 * Output canvas aspect ratio. SOURCE keeps the source shape (width follows source);
 * the others crop-fill to the named ratio. [w]/[h] are the ratio terms; output height
 * is [ExportResolution.height], width = height * w / h (rounded even).
 */
enum class AspectRatio(val label: String, val w: Int, val h: Int) {
    SOURCE("Source", 0, 0),
    LANDSCAPE("16:9", 16, 9),
    PORTRAIT("9:16", 9, 16),
    SQUARE("1:1", 1, 1),
    TALL("4:5", 4, 5);
}

/**
 * A burned-in sticker: [text] (emoji or short text) drawn at normalised position
 * [x]/[y] in [0,1] (top-left origin), [sizePx] tall on the output canvas. [startMs]/
 * [endMs] gate when it is visible on the output timeline ([endMs] < 0 = until the end).
 */
data class Sticker(
    val text: String,
    val x: Float = 0.5f,
    val y: Float = 0.5f,
    val sizePx: Int = 140,
    val startMs: Long = 0L,
    val endMs: Long = -1L,
)

/** The whole edit: an ordered list of clips rendered head-to-tail. */
data class EditorProject(
    val clips: List<Clip> = emptyList(),
    val resolution: ExportResolution = ExportResolution.P720,
    val title: String = "",
    val musicUri: Uri? = null,
    val filter: VideoFilter = VideoFilter.NONE,
    val transition: Transition = Transition.NONE,
    val aspect: AspectRatio = AspectRatio.SOURCE,
    val stickers: List<Sticker> = emptyList(),
    /** For a non-SOURCE [aspect]: true = letterbox (fit + black bars), false = crop-fill. */
    val letterbox: Boolean = false,
) {
    val totalOutMs: Long get() = clips.sumOf { it.outMs }
    val isEmpty: Boolean get() = clips.isEmpty()
}
