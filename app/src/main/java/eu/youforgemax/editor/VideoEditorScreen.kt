package eu.youforgemax.editor

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.view.LayoutInflater
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import eu.youforgemax.R
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * Youforge-Max — video editor. Dark, cyan-accented YouCut-style layout per EDITOR_DESIGN.md:
 * a top bar (history / timecode / Export), a large black preview stage with custom cyan
 * scrub + play controls, a horizontal filmstrip timeline with a centre playhead, and a
 * tool rail whose buttons slide up a contextual control panel. Export → merged MP4 via
 * [EditorExporter] (Media3 Transformer) in a foreground service ([VideoExportService]).
 *
 * The preview uses a PlayerView forced to a TextureView surface (res/layout/yf_player.xml):
 * a SurfaceView renders black inside Compose and can't be overlaid by the sticker/controls.
 */

/** Brand palette (dark · cyan). The editor is its own dark surface, independent of app theme. */
private object Ink {
    val stage = Color(0xFF000000)
    val bg = Color(0xFF0E0E10)
    val surface = Color(0xFF1A1A1E)
    val surfaceHi = Color(0xFF26262C)
    val stroke = Color(0xFF2E2E36)
    val accent = Color(0xFF00E5FF)
    val text = Color(0xFFFFFFFF)
    val textMute = Color(0xFF9A9AA6)
    val textDim = Color(0xFF5A5A66)
    val danger = Color(0xFFFF4D5E)
}

private enum class Tool(val icon: String, val label: String, val needsClip: Boolean) {
    TRIM("✂️", "Trim", true),
    SPEED("⏩", "Speed", true),
    VOLUME("🔊", "Volume", true),
    ROTATE("🔄", "Rotate", true),
    FILTER("🎞️", "Filter", false),
    TRANSITION("✨", "Transition", false),
    TEXT("🔤", "Text", false),
    STICKER("😀", "Sticker", false),
    MUSIC("🎵", "Music", false),
    RATIO("🖼️", "Ratio", false),
    QUALITY("🎚️", "Quality", false),
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun VideoEditorScreen() {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val density = LocalDensity.current
    var project by remember { mutableStateOf(EditorProject()) }
    var selected by remember { mutableIntStateOf(-1) }
    var status by remember { mutableStateOf("Add clips to start.") }
    var progress by remember { mutableStateOf(0) }
    var exporting by remember { mutableStateOf(false) }
    var tool by remember { mutableStateOf<Tool?>(null) }

    val exportState by ExportJobs.state.collectAsState()
    LaunchedEffect(exportState) {
        exporting = exportState.running
        progress = exportState.progress
        status = when {
            exportState.running && exportState.indeterminate -> "Exporting…"
            exportState.running -> "Exporting… ${exportState.progress}%"
            exportState.finishedAt != 0L -> exportState.message
            else -> status
        }
    }

    var undo by remember { mutableStateOf<List<EditorProject>>(emptyList()) }
    var redo by remember { mutableStateOf<List<EditorProject>>(emptyList()) }
    fun edit(p: EditorProject) { undo = (undo + project).takeLast(30); redo = emptyList(); project = p }
    fun mutateClip(idx: Int, transform: (Clip) -> Clip) {
        val c = project.clips.getOrNull(idx) ?: return
        project = project.copy(clips = project.clips.toMutableList().also { it[idx] = transform(c) })
    }

    var stickerText by remember { mutableStateOf("") }
    var stickerSel by remember { mutableIntStateOf(-1) }
    var previewSize by remember { mutableStateOf(IntSize.Zero) }

    // Preview player + custom transport state.
    val player = remember { ExoPlayer.Builder(context).build().apply { playWhenReady = false } }
    DisposableEffect(Unit) { onDispose { player.release() } }
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableStateOf(0L) }
    var clipDurMs by remember { mutableStateOf(0L) }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(p: Boolean) { isPlaying = p }
        }
        player.addListener(l); onDispose { player.removeListener(l) }
    }
    LaunchedEffect(player) {
        while (true) {
            positionMs = player.currentPosition.coerceAtLeast(0)
            clipDurMs = player.duration.let { if (it > 0) it else 0L }
            delay(200)
        }
    }

    LaunchedEffect(selected, project.clips.size, project.filter) {
        val c = project.clips.getOrNull(selected)
        if (c != null) {
            player.setMediaItem(MediaItem.fromUri(c.uri))
            // Only attach effects when a filter is active — the plain decode path is the
            // most reliable for first-frame render.
            runCatching {
                player.setVideoEffects(if (project.filter == VideoFilter.NONE) emptyList() else EditorEffects.forFilter(project.filter))
            }
            player.prepare()
            runCatching { player.seekTo(c.trimStartMs) }
        }
    }

    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun requestNotifIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) notifPerm.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    val musicPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            project = project.copy(musicUri = uri); status = "Music added"
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        val added = uris.map { uri ->
            runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            Clip(uri = uri, durationMs = durationOf(context, uri)?.takeIf { it > 0 } ?: 60_000L)
        }
        val newClips = project.clips + added
        edit(project.copy(clips = newClips)); selected = newClips.size - 1
        status = "${newClips.size} clip(s) · ${fmt(newClips.sumOf { it.outMs })} total"
    }

    fun startExport() {
        focus.clearFocus(); requestNotifIfNeeded()
        exporting = true; progress = 0; status = "Exporting…"
        VideoExportService.start(context, project)
    }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(Ink.bg).imePadding()) {
        // ---- Top bar ----
        Column {
            Row(
                Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconTextButton("↶", enabled = undo.isNotEmpty() && !exporting) {
                    if (undo.isNotEmpty()) { redo = (redo + project).takeLast(30); project = undo.last(); undo = undo.dropLast(1); status = "Undone" }
                }
                IconTextButton("↷", enabled = redo.isNotEmpty() && !exporting) {
                    if (redo.isNotEmpty()) { undo = (undo + project).takeLast(30); project = redo.last(); redo = redo.dropLast(1); status = "Redone" }
                }
                val total = project.totalOutMs
                Text(
                    if (project.isEmpty) "Add clips" else "${fmt(positionMs)} / ${fmt(total)}",
                    color = Ink.textMute, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                )
                var menu by remember { mutableStateOf(false) }
                Box {
                    IconTextButton("⋯", enabled = !exporting) { menu = true }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.background(Ink.surfaceHi)) {
                        DropdownMenuItem(text = { Text("Save project", color = Ink.text) }, enabled = !project.isEmpty,
                            onClick = { menu = false; saveProject(context, project); status = "Project saved" })
                        DropdownMenuItem(text = { Text("Load project", color = Ink.text) }, onClick = {
                            menu = false
                            loadProject(context)?.let { edit(it); selected = if (it.clips.isEmpty()) -1 else 0; status = "Loaded · ${it.clips.size} clip(s)" }
                                ?: run { status = "No saved project" }
                        })
                    }
                }
                Spacer(Modifier.width(4.dp))
                if (exporting) OutlinedButton(onClick = { VideoExportService.cancel(context) },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink.danger)) { Text("Cancel") }
                else Button(onClick = { startExport() }, enabled = !project.isEmpty,
                    colors = ButtonDefaults.buttonColors(containerColor = Ink.accent, contentColor = Color.Black,
                        disabledContainerColor = Ink.surfaceHi, disabledContentColor = Ink.textDim),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp)) {
                    Text("Export", fontWeight = FontWeight.SemiBold)
                }
            }
            if (exporting) {
                if (exportState.indeterminate) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = Ink.accent, trackColor = Ink.stroke)
                else LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth().height(2.dp), color = Ink.accent, trackColor = Ink.stroke)
            }
        }

        // ---- Preview stage ----
        Box(
            Modifier.fillMaxWidth().weight(1f).background(Ink.stage).onSizeChanged { previewSize = it },
            contentAlignment = Alignment.Center
        ) {
            if (project.isEmpty) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("🎬", fontSize = 48.sp)
                    Text("No clips yet", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink.text)
                    Text("Add a video to start", fontSize = 13.sp, color = Ink.textMute)
                    Button(onClick = { picker.launch(arrayOf("video/*")) }, enabled = !exporting,
                        colors = ButtonDefaults.buttonColors(containerColor = Ink.accent, contentColor = Color.Black)) { Text("Add video") }
                }
            } else {
                PreviewStage(
                    project, player, previewSize, density, isPlaying,
                    positionMs = positionMs, clipDurMs = clipDurMs,
                    onTogglePlay = { if (isPlaying) player.pause() else player.play() },
                    onSeek = { player.seekTo(it) },
                    stickerSel = stickerSel, onStickerSel = { stickerSel = it }, onProject = { project = it },
                )
            }
        }

        // ---- Filmstrip timeline ----
        FilmstripTimeline(
            context, project, selected, exporting, density,
            onSelect = { selected = it },
            onAdd = { picker.launch(arrayOf("video/*")) },
            onTrim = { idx, t -> mutateClip(idx, t) },
        )

        // ---- Tool rail + sliding panel ----
        Box(Modifier.fillMaxWidth().height(88.dp).background(Ink.surface)) {
            ToolRail(enabled = !exporting, onAdd = { picker.launch(arrayOf("video/*")) }, onPick = { tool = it })
        }
    }

    // Tool panel slides up over the whole bottom area.
    AnimatedVisibility(
        visible = tool != null,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
    ) {
        Box(Modifier.fillMaxSize().background(Color(0x80000000)).clickable { tool = null }, contentAlignment = Alignment.BottomCenter) {
            val t = tool
            Surface(color = Ink.surfaceHi, shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp),
                modifier = Modifier.fillMaxWidth().clickable(enabled = false) {}) {
                if (t != null) ToolPanel(
                    tool = t, onClose = { tool = null }, project = project, selected = selected, exporting = exporting, focus = focus,
                    stickerText = stickerText, stickerSel = stickerSel,
                    onProject = { project = it }, onEdit = ::edit, onMutateClip = { tr -> mutateClip(selected, tr) },
                    onSelect = { selected = it }, onStatus = { status = it },
                    onStickerText = { stickerText = it }, onStickerSel = { stickerSel = it },
                    onAddMusic = { if (project.musicUri != null) { edit(project.copy(musicUri = null)); status = "Music removed" } else musicPicker.launch(arrayOf("audio/*")) },
                )
            }
        }
    }
    }
}

// ---------------------------------------------------------------------------
// Preview stage
// ---------------------------------------------------------------------------
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun PreviewStage(
    project: EditorProject,
    player: ExoPlayer,
    previewSize: IntSize,
    density: androidx.compose.ui.unit.Density,
    isPlaying: Boolean,
    positionMs: Long,
    clipDurMs: Long,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    stickerSel: Int,
    onStickerSel: (Int) -> Unit,
    onProject: (EditorProject) -> Unit,
) {
    var showControls by remember { mutableStateOf(true) }
    Box(Modifier.fillMaxSize().clickable { showControls = !showControls }, contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ctx ->
                (LayoutInflater.from(ctx).inflate(R.layout.yf_player, null) as PlayerView).apply { this.player = player }
            },
            update = { view ->
                val a = project.aspect
                view.resizeMode = if (a == AspectRatio.SOURCE || project.letterbox)
                    AspectRatioFrameLayout.RESIZE_MODE_FIT else AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            },
            modifier = Modifier.fillMaxSize()
        )

        // Sticker overlays (draggable).
        if (previewSize.width > 0) project.stickers.forEachIndexed { i, s ->
            if (s.text.isBlank()) return@forEachIndexed
            val fontSp = with(density) { (s.sizePx / 720f * previewSize.height).toSp() }
            var sz by remember(i) { mutableStateOf(IntSize.Zero) }
            Text(
                s.text, color = Color.White, fontSize = fontSp, fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .onSizeChanged { sz = it }
                    .offset {
                        IntOffset(
                            (s.x * previewSize.width - sz.width / 2f).roundToInt(),
                            (s.y * previewSize.height - sz.height / 2f).roundToInt()
                        )
                    }
                    .then(if (i == stickerSel) Modifier.border(1.dp, Ink.accent, RoundedCornerShape(4.dp)) else Modifier)
                    .pointerInput(i, previewSize) {
                        detectDragGestures(onDragStart = { onStickerSel(i) }) { change, drag ->
                            change.consume()
                            val cur = project.stickers.getOrNull(i) ?: return@detectDragGestures
                            val nx = (cur.x + drag.x / previewSize.width).coerceIn(0f, 1f)
                            val ny = (cur.y + drag.y / previewSize.height).coerceIn(0f, 1f)
                            onProject(project.copy(stickers = project.stickers.toMutableList().also { it[i] = cur.copy(x = nx, y = ny) }))
                        }
                    }
            )
        }

        // Centre play / pause.
        AnimatedVisibility(visible = showControls, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier.size(64.dp).clip(CircleShape).background(Color(0xA0000000)).clickable { onTogglePlay() },
                contentAlignment = Alignment.Center
            ) { Text(if (isPlaying) "⏸" else "▶", color = Ink.accent, fontSize = 26.sp) }
        }

        // Scrub row.
        AnimatedVisibility(visible = showControls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Row(
                Modifier.fillMaxWidth().background(Color(0x80000000)).padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(fmt(positionMs), color = Color.White, fontSize = 11.sp)
                Slider(
                    value = positionMs.coerceIn(0L, clipDurMs).toFloat(),
                    onValueChange = { onSeek(it.toLong()) },
                    valueRange = 0f..(clipDurMs.takeIf { it > 0 } ?: 1L).toFloat(),
                    colors = SliderDefaults.colors(thumbColor = Ink.accent, activeTrackColor = Ink.accent, inactiveTrackColor = Ink.stroke),
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                )
                Text(fmt(clipDurMs), color = Color.White, fontSize = 11.sp)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Filmstrip timeline
// ---------------------------------------------------------------------------
@Composable
private fun FilmstripTimeline(
    context: android.content.Context,
    project: EditorProject,
    selected: Int,
    exporting: Boolean,
    density: androidx.compose.ui.unit.Density,
    onSelect: (Int) -> Unit,
    onAdd: () -> Unit,
    onTrim: (Int, (Clip) -> Clip) -> Unit,
) {
    val scroll = rememberScrollState()
    Box(Modifier.fillMaxWidth().height(72.dp).background(Ink.surface)) {
        Row(
            Modifier.fillMaxSize().horizontalScroll(scroll).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            project.clips.forEachIndexed { idx, clip ->
                val widthDp = ((clip.outMs / 1000f) * 6f).coerceIn(56f, 320f).dp
                val sel = idx == selected
                Box(
                    Modifier.width(widthDp).height(56.dp).clip(RoundedCornerShape(8.dp))
                        .background(Ink.surfaceHi)
                        .then(if (sel) Modifier.border(2.dp, Ink.accent, RoundedCornerShape(8.dp)) else Modifier)
                        .clickable(enabled = !exporting) { onSelect(idx) }
                ) {
                    // Repeated sampled frames across the strip.
                    val nFrames = with(density) { (widthDp.toPx() / with(density) { 48.dp.toPx() }).roundToInt() }.coerceIn(1, 6)
                    val frames = remember(clip.uri, clip.trimStartMs, clip.trimEndMs, nFrames) {
                        sampleFrames(context, clip, nFrames)
                    }
                    Row(Modifier.fillMaxSize()) {
                        frames.forEach { bmp ->
                            Image(
                                bitmap = bmp.asImageBitmap(), contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.weight(1f).fillMaxHeight()
                            )
                        }
                    }
                    Text(
                        fmt(clip.outMs), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.align(Alignment.BottomStart).background(Color(0x99000000)).padding(horizontal = 4.dp)
                    )
                    // Edge trim handles for the selected clip.
                    if (sel && !exporting) {
                        val wPx = with(density) { widthDp.toPx() }
                        val span = (clip.durationMs).coerceAtLeast(1)
                        TrimHandle(Alignment.CenterStart) { dx ->
                            onTrim(idx) { c -> c.copy(trimStartMs = (c.trimStartMs + (dx / wPx * span).toLong()).coerceIn(0L, c.trimEndMs - 100)) }
                        }
                        TrimHandle(Alignment.CenterEnd) { dx ->
                            onTrim(idx) { c -> c.copy(trimEndMs = (c.trimEndMs + (dx / wPx * span).toLong()).coerceIn(c.trimStartMs + 100, c.durationMs)) }
                        }
                    }
                }
            }
            // Append tile
            Box(
                Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(Ink.surfaceHi)
                    .border(1.dp, Ink.stroke, RoundedCornerShape(8.dp)).clickable(enabled = !exporting) { onAdd() },
                contentAlignment = Alignment.Center
            ) { Text("＋", color = Ink.accent, fontSize = 24.sp) }
        }
        // Fixed centre playhead.
        Canvas(Modifier.fillMaxSize()) {
            val x = size.width / 2f
            drawLine(Ink.accent, start = androidx.compose.ui.geometry.Offset(x, 0f), end = androidx.compose.ui.geometry.Offset(x, size.height), strokeWidth = 2f)
        }
    }
}

@Composable
private fun BoxScope.TrimHandle(align: Alignment, onDrag: (Float) -> Unit) {
    Box(
        Modifier.align(align).fillMaxHeight().width(14.dp).background(Ink.accent.copy(alpha = 0.85f))
            .pointerInput(Unit) {
                detectDragGestures { change, drag -> change.consume(); onDrag(drag.x) }
            },
        contentAlignment = Alignment.Center
    ) { Text("⋮", color = Color.Black, fontSize = 12.sp) }
}

// ---------------------------------------------------------------------------
// Tool rail + panel
// ---------------------------------------------------------------------------
@Composable
private fun ToolRail(enabled: Boolean, onAdd: () -> Unit, onPick: (Tool) -> Unit) {
    Row(
        Modifier.fillMaxSize().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        ToolButton("➕", "Add", enabled, onAdd)
        Tool.entries.forEach { t -> ToolButton(t.icon, t.label, enabled) { onPick(t) } }
    }
}

@Composable
private fun ToolButton(icon: String, label: String, enabled: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.width(64.dp).clip(RoundedCornerShape(10.dp)).clickable(enabled = enabled) { onClick() }.padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(icon, fontSize = 22.sp)
        Text(label, fontSize = 11.sp, maxLines = 1, color = if (enabled) Ink.textMute else Ink.textDim)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ToolPanel(
    tool: Tool, onClose: () -> Unit, project: EditorProject, selected: Int, exporting: Boolean,
    focus: androidx.compose.ui.focus.FocusManager, stickerText: String, stickerSel: Int,
    onProject: (EditorProject) -> Unit, onEdit: (EditorProject) -> Unit, onMutateClip: ((Clip) -> Clip) -> Unit,
    onSelect: (Int) -> Unit, onStatus: (String) -> Unit, onStickerText: (String) -> Unit, onStickerSel: (Int) -> Unit,
    onAddMusic: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 360.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${tool.icon}  ${tool.label}", color = Ink.text, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose, colors = ButtonDefaults.textButtonColors(contentColor = Ink.accent)) { Text("Done") }
        }
        HorizontalDivider(color = Ink.stroke)
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val clip = project.clips.getOrNull(selected)
            if (tool.needsClip && clip == null) {
                Text("Select a clip in the timeline first.", color = Ink.textMute, fontSize = 13.sp)
                return@Column
            }
            when (tool) {
                Tool.TRIM -> clip?.let { c ->
                    Text("Trim — ${fmt(c.trimStartMs)} → ${fmt(c.trimEndMs)}", color = Ink.text, fontSize = 13.sp)
                    InkSlider(c.trimStartMs.toFloat(), 0f..c.durationMs.toFloat(), !exporting) { v -> onMutateClip { it.copy(trimStartMs = v.toLong().coerceAtMost(it.trimEndMs - 100)) } }
                    InkSlider(c.trimEndMs.toFloat(), 0f..c.durationMs.toFloat(), !exporting) { v -> onMutateClip { it.copy(trimEndMs = v.toLong().coerceAtLeast(it.trimStartMs + 100)) } }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        InkTextButton("◀ Move", selected > 0 && !exporting) {
                            onEdit(project.copy(clips = project.clips.toMutableList().also { java.util.Collections.swap(it, selected, selected - 1) })); onSelect(selected - 1)
                        }
                        InkTextButton("Move ▶", selected < project.clips.size - 1 && !exporting) {
                            onEdit(project.copy(clips = project.clips.toMutableList().also { java.util.Collections.swap(it, selected, selected + 1) })); onSelect(selected + 1)
                        }
                        TextButton(onClick = {
                            val list = project.clips.toMutableList().also { it.removeAt(selected) }
                            onEdit(project.copy(clips = list)); onSelect(selected.coerceIn(-1, list.size - 1)); onStatus("${list.size} clip(s)")
                        }, enabled = !exporting, colors = ButtonDefaults.textButtonColors(contentColor = Ink.danger)) { Text("🗑 Delete") }
                    }
                }
                Tool.SPEED -> clip?.let { c ->
                    ChipRow("Speed:", listOf(0.25f, 0.5f, 1f, 1.5f, 2f), { c.speed == it }, { if (it == 1f) "1×" else "${it}×" }, !exporting) { sp ->
                        onEdit(project.copy(clips = project.clips.toMutableList().also { it[selected] = c.copy(speed = sp) })); onStatus("${project.clips.size} clip(s) · ${fmt(project.totalOutMs)} total")
                    }
                }
                Tool.VOLUME -> clip?.let { c ->
                    FilterChip(selected = c.muted, onClick = { onEdit(project.copy(clips = project.clips.toMutableList().also { it[selected] = c.copy(muted = !c.muted) })) },
                        label = { Text(if (c.muted) "Muted" else "Mute") }, enabled = !exporting, colors = chipColors())
                    if (!c.muted) {
                        Text("Volume: ${(c.volume * 100).toInt()}%", color = Ink.text, fontSize = 13.sp)
                        InkSlider(c.volume, 0f..2f, !exporting) { v -> onMutateClip { it.copy(volume = v) } }
                    }
                }
                Tool.ROTATE -> clip?.let { c ->
                    Text("Rotation: ${c.rotationDeg}°", color = Ink.text, fontSize = 13.sp)
                    Button(onClick = { onEdit(project.copy(clips = project.clips.toMutableList().also { it[selected] = c.copy(rotationDeg = (c.rotationDeg + 90) % 360) })) },
                        enabled = !exporting && !c.muted, colors = ButtonDefaults.buttonColors(containerColor = Ink.accent, contentColor = Color.Black)) { Text("Rotate 90°") }
                }
                Tool.FILTER -> ChipRow("Filter:", VideoFilter.entries, { project.filter == it }, { it.label }, !exporting) { onEdit(project.copy(filter = it)) }
                Tool.TRANSITION -> ChipRow("Transition:", Transition.entries, { project.transition == it }, { it.label }, !exporting) { onEdit(project.copy(transition = it)) }
                Tool.TEXT -> OutlinedTextField(value = project.title, onValueChange = { onProject(project.copy(title = it)) },
                    label = { Text("Title overlay") }, singleLine = true, enabled = !exporting, colors = inkField(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                    modifier = Modifier.fillMaxWidth())
                Tool.STICKER -> StickerPanel(project, stickerText, stickerSel, exporting, focus, onProject, onEdit, onStickerText, onStickerSel)
                Tool.MUSIC -> OutlinedButton(onClick = onAddMusic, enabled = !exporting, colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink.accent)) {
                    Text(if (project.musicUri != null) "Music ✓ (tap to remove)" else "Add music")
                }
                Tool.RATIO -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AspectRatio.entries.forEach { a ->
                        FilterChip(selected = project.aspect == a, onClick = { onEdit(project.copy(aspect = a)) }, label = { Text(a.label) }, enabled = !exporting, colors = chipColors())
                    }
                    if (project.aspect != AspectRatio.SOURCE) FilterChip(selected = project.letterbox, onClick = { onEdit(project.copy(letterbox = !project.letterbox)) },
                        label = { Text("Letterbox") }, enabled = !exporting, colors = chipColors())
                }
                Tool.QUALITY -> ChipRow("Quality:", ExportResolution.entries, { project.resolution == it }, { it.label }, !exporting) { onEdit(project.copy(resolution = it)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun StickerPanel(
    project: EditorProject, stickerText: String, stickerSel: Int, exporting: Boolean, focus: androidx.compose.ui.focus.FocusManager,
    onProject: (EditorProject) -> Unit, onEdit: (EditorProject) -> Unit, onStickerText: (String) -> Unit, onStickerSel: (Int) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(value = stickerText, onValueChange = onStickerText, label = { Text("Emoji / text") }, singleLine = true, enabled = !exporting, colors = inkField(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }), modifier = Modifier.width(200.dp))
        Button(onClick = {
            if (stickerText.isNotBlank()) { onEdit(project.copy(stickers = project.stickers + Sticker(stickerText))); onStickerSel(project.stickers.size); onStickerText(""); focus.clearFocus() }
        }, enabled = !exporting, colors = ButtonDefaults.buttonColors(containerColor = Ink.accent, contentColor = Color.Black)) { Text("Add") }
        if (project.stickers.isNotEmpty()) InkTextButton("Clear (${project.stickers.size})", !exporting) { onEdit(project.copy(stickers = emptyList())); onStickerSel(-1) }
    }
    if (project.stickers.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        project.stickers.forEachIndexed { i, s -> FilterChip(selected = stickerSel == i, onClick = { onStickerSel(i) }, label = { Text(s.text.take(8)) }, enabled = !exporting, colors = chipColors()) }
    }
    project.stickers.getOrNull(stickerSel)?.let { st ->
        fun update(s: Sticker) { onProject(project.copy(stickers = project.stickers.toMutableList().also { it[stickerSel] = s })) }
        Text("Position", color = Ink.text, fontSize = 13.sp)
        InkSlider(st.x, 0f..1f, !exporting) { update(st.copy(x = it)) }
        InkSlider(st.y, 0f..1f, !exporting) { update(st.copy(y = it)) }
        val total = project.totalOutMs
        if (total > 0L) {
            val endShown = if (st.endMs < 0L) total else st.endMs
            Text("Show ${fmt(st.startMs)} → ${fmt(endShown)}", color = Ink.text, fontSize = 13.sp)
            InkSlider(st.startMs.coerceIn(0L, total).toFloat(), 0f..total.toFloat(), !exporting) { update(st.copy(startMs = it.toLong().coerceAtMost(endShown - 100))) }
            InkSlider(endShown.coerceIn(0L, total).toFloat(), 0f..total.toFloat(), !exporting) { v ->
                update(st.copy(endMs = if (v >= total.toFloat() - 50f) -1L else v.toLong().coerceAtLeast(st.startMs + 100)))
            }
        }
        InkTextButton("Delete sticker", !exporting) {
            val list = project.stickers.toMutableList().also { it.removeAt(stickerSel) }; onEdit(project.copy(stickers = list)); onStickerSel((stickerSel - 1).coerceIn(-1, list.size - 1))
        }
    }
}

// ---------------------------------------------------------------------------
// Small styled building blocks
// ---------------------------------------------------------------------------
@Composable
private fun IconTextButton(glyph: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, colors = ButtonDefaults.textButtonColors(contentColor = Ink.text, disabledContentColor = Ink.textDim)) {
        Text(glyph, fontSize = 18.sp)
    }
}

@Composable
private fun InkTextButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, colors = ButtonDefaults.textButtonColors(contentColor = Ink.accent, disabledContentColor = Ink.textDim)) { Text(label) }
}

@Composable
private fun InkSlider(value: Float, range: ClosedFloatingPointRange<Float>, enabled: Boolean, onChange: (Float) -> Unit) {
    Slider(value = value, onValueChange = onChange, valueRange = range, enabled = enabled,
        colors = SliderDefaults.colors(thumbColor = Ink.accent, activeTrackColor = Ink.accent, inactiveTrackColor = Ink.stroke,
            disabledThumbColor = Ink.textDim, disabledActiveTrackColor = Ink.textDim))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun chipColors() = FilterChipDefaults.filterChipColors(
    containerColor = Ink.surfaceHi, labelColor = Ink.text, selectedContainerColor = Ink.accent, selectedLabelColor = Color.Black
)

@Composable
private fun inkField() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Ink.accent, unfocusedBorderColor = Ink.stroke, focusedTextColor = Ink.text, unfocusedTextColor = Ink.text,
    focusedLabelColor = Ink.accent, unfocusedLabelColor = Ink.textMute, cursorColor = Ink.accent,
    focusedContainerColor = Ink.surfaceHi, unfocusedContainerColor = Ink.surfaceHi
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipRow(label: String, items: List<T>, isSelected: (T) -> Boolean, labelOf: (T) -> String, enabled: Boolean, onPick: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = Ink.text, fontSize = 13.sp, modifier = Modifier.align(Alignment.CenterVertically))
        items.forEach { item -> FilterChip(selected = isSelected(item), onClick = { onPick(item) }, label = { Text(labelOf(item)) }, enabled = enabled, colors = chipColors()) }
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------
/** Evenly-sampled thumbnail frames across a clip's kept range (cheap; no cache yet). */
private fun sampleFrames(context: android.content.Context, clip: Clip, count: Int): List<Bitmap> {
    val span = (clip.trimEndMs - clip.trimStartMs).coerceAtLeast(1)
    return (0 until count).mapNotNull { i -> frameAt(context, clip.uri, clip.trimStartMs + span * i / count) }
}

private fun saveProject(context: android.content.Context, p: EditorProject) {
    fun enc(s: String) = android.util.Base64.encodeToString(s.toByteArray(), android.util.Base64.NO_WRAP)
    val sb = StringBuilder()
    sb.append("v5|${p.resolution.name}|${enc(p.title)}|${p.musicUri?.let { enc(it.toString()) } ?: ""}|${p.filter.name}|${p.transition.name}|${p.aspect.name}|${p.letterbox}\n")
    p.clips.forEach { c -> sb.append("${enc(c.uri.toString())}|${c.durationMs}|${c.trimStartMs}|${c.trimEndMs}|${c.speed}|${c.muted}|${c.volume}|${c.rotationDeg}\n") }
    p.stickers.forEach { s -> sb.append("S|${enc(s.text)}|${s.x}|${s.y}|${s.sizePx}|${s.startMs}|${s.endMs}\n") }
    java.io.File(context.filesDir, "yf_project.txt").writeText(sb.toString())
}

private fun loadProject(context: android.content.Context): EditorProject? {
    val f = java.io.File(context.filesDir, "yf_project.txt")
    if (!f.isFile) return null
    return runCatching {
        fun dec(s: String) = String(android.util.Base64.decode(s, android.util.Base64.NO_WRAP))
        val lines = f.readLines().filter { it.isNotBlank() }
        val m = lines.first().split("|")
        val music = m[3].takeIf { it.isNotEmpty() }?.let { Uri.parse(dec(it)) }
        val body = lines.drop(1)
        val clips = body.filterNot { it.startsWith("S|") }.map { ln ->
            val c = ln.split("|")
            Clip(Uri.parse(dec(c[0])), c[1].toLong(), c[2].toLong(), c[3].toLong(), c[4].toFloat(), c[5].toBoolean(), c.getOrNull(6)?.toFloatOrNull() ?: 1f, c.getOrNull(7)?.toIntOrNull() ?: 0)
        }
        val stickers = body.filter { it.startsWith("S|") }.mapNotNull { ln ->
            val s = ln.split("|")
            runCatching { Sticker(dec(s[1]), s[2].toFloat(), s[3].toFloat(), s[4].toInt(), s.getOrNull(5)?.toLongOrNull() ?: 0L, s.getOrNull(6)?.toLongOrNull() ?: -1L) }.getOrNull()
        }
        val transition = m.getOrNull(5)?.let { runCatching { Transition.valueOf(it) }.getOrNull() } ?: Transition.NONE
        val aspect = m.getOrNull(6)?.let { runCatching { AspectRatio.valueOf(it) }.getOrNull() } ?: AspectRatio.SOURCE
        val letterbox = m.getOrNull(7)?.toBoolean() ?: false
        EditorProject(clips, ExportResolution.valueOf(m[1]), dec(m[2]), music, VideoFilter.valueOf(m[4]), transition, aspect, stickers, letterbox)
    }.getOrNull()
}

private fun frameAt(context: android.content.Context, uri: Uri, atMs: Long): Bitmap? = runCatching {
    MediaMetadataRetriever().use { r ->
        r.setDataSource(context, uri)
        r.getScaledFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 144, 88)
    }
}.getOrNull()

private fun durationOf(context: android.content.Context, uri: Uri): Long? = runCatching {
    MediaMetadataRetriever().use { r -> r.setDataSource(context, uri); r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() }
}.getOrNull()

private fun fmt(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }
