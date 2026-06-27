package eu.youforgemax.editor

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import kotlin.math.roundToInt
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * Youforge-Max — video editor, YouCut-style layout: a large preview filling the top of
 * the screen, a horizontal clip timeline beneath it, and a bottom tool bar whose buttons
 * open a contextual panel of controls for the selected tool. Import clips, trim/speed/
 * rotate/volume each, add title + sticker overlays, music, colour filters, transitions
 * and aspect crop, then export a merged MP4 via [EditorExporter] (Media3 Transformer).
 * Export runs in a foreground service ([VideoExportService]).
 */
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
    var project by remember { mutableStateOf(EditorProject()) }
    var selected by remember { mutableIntStateOf(-1) }
    var status by remember { mutableStateOf("Add clips to start.") }
    var progress by remember { mutableStateOf(0) }
    var exporting by remember { mutableStateOf(false) }
    // The open tool panel; null = the tool bar is shown.
    var tool by remember { mutableStateOf<Tool?>(null) }

    // Export runs in a foreground service (survives backgrounding, YouCut-style); the UI
    // just observes its process-wide progress so it reattaches if the screen is left/re-entered.
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

    // Single-stack undo: discrete edits snapshot the prior project (trim/title drags
    // stay direct so they don't spam history).
    var undo by remember { mutableStateOf<List<EditorProject>>(emptyList()) }
    var redo by remember { mutableStateOf<List<EditorProject>>(emptyList()) }
    fun edit(p: EditorProject) { undo = (undo + project).takeLast(30); redo = emptyList(); project = p }
    /** Mutate the selected clip without snapshotting history (for sliders). */
    fun mutateClip(transform: (Clip) -> Clip) {
        val c = project.clips.getOrNull(selected) ?: return
        project = project.copy(clips = project.clips.toMutableList().also { it[selected] = transform(c) })
    }

    var stickerText by remember { mutableStateOf("") }
    var stickerSel by remember { mutableIntStateOf(-1) }
    // Pixel size of the preview area — used to map normalised sticker x/y for drag.
    var previewSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current

    // ExoPlayer for the preview; released when the screen leaves composition.
    val player = remember {
        ExoPlayer.Builder(context).build().apply { playWhenReady = false }
    }
    DisposableEffect(Unit) { onDispose { player.release() } }

    // (Re)load the preview when the selected clip changes.
    LaunchedEffect(selected, project.clips.size, project.filter) {
        val c = project.clips.getOrNull(selected)
        if (c != null) {
            player.setMediaItem(MediaItem.fromUri(c.uri))
            // Live filter preview (best-effort; the filter always applies at export).
            runCatching { player.setVideoEffects(EditorEffects.forFilter(project.filter)) }
            player.prepare()
        }
    }

    // The export runs in a foreground service whose progress notification is runtime-
    // permissioned on Android 13+. Without the grant the service still exports, but the
    // notification is silently hidden — so request it (fire-and-forget) before exporting.
    val notifPerm = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    fun requestNotifIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) notifPerm.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    val musicPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            project = project.copy(musicUri = uri)
            status = "Music added · ${project.clips.size} clip(s)"
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        // Always add every picked clip — never drop one because its duration didn't
        // read (some containers report no duration); fall back so the clip still shows.
        val added = uris.map { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val dur = durationOf(context, uri)?.takeIf { it > 0 } ?: 60_000L
            Clip(uri = uri, durationMs = dur)
        }
        val newClips = project.clips + added
        edit(project.copy(clips = newClips))
        // Jump the preview to the newest clip so a just-added clip is visibly there.
        selected = newClips.size - 1
        status = "${newClips.size} clip(s) · ${fmt(newClips.sumOf { it.outMs })} total"
    }

    fun startExport() {
        focus.clearFocus()
        requestNotifIfNeeded()
        exporting = true; progress = 0; status = "Exporting…"
        VideoExportService.start(context, project)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        // --- Top bar: history + save/load + the primary Export action ---
        Surface(tonalElevation = 2.dp) {
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = { if (undo.isNotEmpty()) { redo = (redo + project).takeLast(30); project = undo.last(); undo = undo.dropLast(1); status = "Undone" } },
                        enabled = undo.isNotEmpty() && !exporting
                    ) { Text("↶") }
                    TextButton(
                        onClick = { if (redo.isNotEmpty()) { undo = (undo + project).takeLast(30); project = redo.last(); redo = redo.dropLast(1); status = "Redone" } },
                        enabled = redo.isNotEmpty() && !exporting
                    ) { Text("↷") }
                    Text(
                        status, fontSize = 12.sp, maxLines = 1,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                    )
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { menu = true }, enabled = !exporting) { Text("⋯") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Save project") }, enabled = !project.isEmpty,
                                onClick = { menu = false; saveProject(context, project); status = "Project saved" })
                            DropdownMenuItem(text = { Text("Load project") }, onClick = {
                                menu = false
                                loadProject(context)?.let { edit(it); selected = if (it.clips.isEmpty()) -1 else 0; status = "Project loaded · ${it.clips.size} clip(s)" }
                                    ?: run { status = "No saved project" }
                            })
                        }
                    }
                    if (exporting) OutlinedButton(onClick = { VideoExportService.cancel(context) }) { Text("Cancel") }
                    else Button(onClick = { startExport() }, enabled = !project.isEmpty) { Text("Export") }
                }
                if (exporting) {
                    if (exportState.indeterminate) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                }
            }
        }

        // --- Preview (fills the top, ~the YouCut "stage") ---
        Box(
            Modifier.fillMaxWidth().weight(1f).background(Color.Black).onSizeChanged { previewSize = it },
            contentAlignment = Alignment.Center
        ) {
            if (project.isEmpty) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("🎬", fontSize = 48.sp)
                    Text("No clips yet", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Button(onClick = { picker.launch(arrayOf("video/*")) }, enabled = !exporting) { Text("Add clips") }
                }
            } else {
                PreviewStage(project, player, previewSize, density, stickerSel,
                    onStickerSel = { stickerSel = it }, onProject = { project = it })
            }
        }

        // --- Timeline strip ---
        TimelineStrip(
            context, project, selected, exporting,
            onSelect = { selected = it },
            onAdd = { picker.launch(arrayOf("video/*")) },
        )

        // --- Bottom: tool bar, or the open tool's panel ---
        Surface(tonalElevation = 3.dp) {
            Box(Modifier.fillMaxWidth().heightIn(min = 132.dp)) {
                val t = tool
                if (t == null) {
                    ToolBar(
                        enabled = !exporting,
                        hasClip = selected in project.clips.indices,
                        onAdd = { picker.launch(arrayOf("video/*")) },
                        onPick = { tool = it },
                    )
                } else {
                    ToolPanel(
                        tool = t,
                        onClose = { tool = null },
                        project = project,
                        selected = selected,
                        exporting = exporting,
                        focus = focus,
                        stickerText = stickerText,
                        stickerSel = stickerSel,
                        onProject = { project = it },
                        onEdit = ::edit,
                        onMutateClip = ::mutateClip,
                        onSelect = { selected = it },
                        onStatus = { status = it },
                        onStickerText = { stickerText = it },
                        onStickerSel = { stickerSel = it },
                        onAddMusic = {
                            if (project.musicUri != null) { edit(project.copy(musicUri = null)); status = "Music removed" }
                            else musicPicker.launch(arrayOf("audio/*"))
                        },
                    )
                }
            }
        }
    }
}

/** The video stage: ExoPlayer shaped to the chosen aspect + draggable sticker overlays. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun PreviewStage(
    project: EditorProject,
    player: ExoPlayer,
    previewSize: IntSize,
    density: androidx.compose.ui.unit.Density,
    stickerSel: Int,
    onStickerSel: (Int) -> Unit,
    onProject: (EditorProject) -> Unit,
) {
    val a = project.aspect
    val viewMod = if (a == AspectRatio.SOURCE) Modifier.fillMaxSize()
        else Modifier.fillMaxHeight().aspectRatio(a.w.toFloat() / a.h.toFloat())
    AndroidView(
        factory = { ctx -> PlayerView(ctx).apply { this.player = player; useController = true } },
        update = { view ->
            view.resizeMode = if (a == AspectRatio.SOURCE || project.letterbox)
                AspectRatioFrameLayout.RESIZE_MODE_FIT
            else AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        },
        modifier = viewMod
    )
    if (previewSize.width > 0) project.stickers.forEachIndexed { i, s ->
        if (s.text.isBlank()) return@forEachIndexed
        val fontSp = with(density) { (s.sizePx / 720f * previewSize.height).toSp() }
        var sz by remember(i) { mutableStateOf(IntSize.Zero) }
        Text(
            s.text,
            color = Color.White,
            fontSize = fontSp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .onSizeChanged { sz = it }
                .offset {
                    IntOffset(
                        (s.x * previewSize.width - sz.width / 2f).roundToInt(),
                        (s.y * previewSize.height - sz.height / 2f).roundToInt()
                    )
                }
                .then(if (i == stickerSel) Modifier.background(Color(0x3300E5FF)) else Modifier)
                .pointerInput(i, previewSize) {
                    detectDragGestures(onDragStart = { onStickerSel(i) }) { change, drag ->
                        change.consume()
                        val cur = project.stickers.getOrNull(i) ?: return@detectDragGestures
                        val nx = (cur.x + drag.x / previewSize.width).coerceIn(0f, 1f)
                        val ny = (cur.y + drag.y / previewSize.height).coerceIn(0f, 1f)
                        onProject(project.copy(
                            stickers = project.stickers.toMutableList().also { it[i] = cur.copy(x = nx, y = ny) }
                        ))
                    }
                }
        )
    }
}

/** Horizontal clip timeline: tap a thumbnail to select; trailing "+" appends clips. */
@Composable
private fun TimelineStrip(
    context: android.content.Context,
    project: EditorProject,
    selected: Int,
    exporting: Boolean,
    onSelect: (Int) -> Unit,
    onAdd: () -> Unit,
) {
    Surface(tonalElevation = 1.dp) {
        Row(
            Modifier.fillMaxWidth().height(84.dp).horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            project.clips.forEachIndexed { idx, clip ->
                val thumb = remember(clip.uri, clip.trimStartMs) { frameAt(context, clip.uri, clip.trimStartMs) }
                Box(
                    Modifier.size(96.dp, 60.dp).clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .then(
                            if (idx == selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                            else Modifier
                        )
                        .clickable(enabled = !exporting) { onSelect(idx) },
                    contentAlignment = Alignment.BottomCenter
                ) {
                    if (thumb != null) Image(
                        bitmap = thumb.asImageBitmap(), contentDescription = null,
                        contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
                    )
                    Text(
                        fmt(clip.outMs), fontSize = 10.sp, color = Color.White,
                        modifier = Modifier.fillMaxWidth().background(Color(0x99000000)),
                        textAlign = TextAlign.Center
                    )
                }
            }
            // Append tile
            Box(
                Modifier.size(60.dp, 60.dp).clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .clickable(enabled = !exporting) { onAdd() },
                contentAlignment = Alignment.Center
            ) { Text("＋", fontSize = 24.sp) }
        }
    }
}

/** Scrollable row of tool buttons (YouCut-style). */
@Composable
private fun ToolBar(
    enabled: Boolean,
    hasClip: Boolean,
    onAdd: () -> Unit,
    onPick: (Tool) -> Unit,
) {
    Row(
        Modifier.fillMaxSize().horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        ToolButton("➕", "Add", enabled, onAdd)
        Tool.entries.forEach { t ->
            // Clip-scoped tools stay tappable; the panel shows a "select a clip" hint when none.
            ToolButton(t.icon, t.label, enabled) { onPick(t) }
        }
    }
}

@Composable
private fun ToolButton(icon: String, label: String, enabled: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.width(68.dp).clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled) { onClick() }.padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(icon, fontSize = 22.sp)
        Text(label, fontSize = 11.sp, maxLines = 1,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
    }
}

/** Contextual panel for the open [tool], with a header to close back to the tool bar. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ToolPanel(
    tool: Tool,
    onClose: () -> Unit,
    project: EditorProject,
    selected: Int,
    exporting: Boolean,
    focus: androidx.compose.ui.focus.FocusManager,
    stickerText: String,
    stickerSel: Int,
    onProject: (EditorProject) -> Unit,
    onEdit: (EditorProject) -> Unit,
    onMutateClip: ((Clip) -> Clip) -> Unit,
    onSelect: (Int) -> Unit,
    onStatus: (String) -> Unit,
    onStickerText: (String) -> Unit,
    onStickerSel: (Int) -> Unit,
    onAddMusic: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("${tool.icon}  ${tool.label}", fontSize = 14.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("Done") }
        }
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val clip = project.clips.getOrNull(selected)
            if (tool.needsClip && clip == null) {
                Text("Select a clip in the timeline first.", fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.outline)
                return@Column
            }
            when (tool) {
                Tool.TRIM -> clip?.let { c ->
                    Text("Trim — ${fmt(c.trimStartMs)} → ${fmt(c.trimEndMs)}", fontSize = 13.sp)
                    Slider(value = c.trimStartMs.toFloat(),
                        onValueChange = { v -> onMutateClip { it.copy(trimStartMs = v.toLong().coerceAtMost(it.trimEndMs - 100)) } },
                        valueRange = 0f..c.durationMs.toFloat(), enabled = !exporting)
                    Slider(value = c.trimEndMs.toFloat(),
                        onValueChange = { v -> onMutateClip { it.copy(trimEndMs = v.toLong().coerceAtLeast(it.trimStartMs + 100)) } },
                        valueRange = 0f..c.durationMs.toFloat(), enabled = !exporting)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(enabled = selected > 0 && !exporting, onClick = {
                            onEdit(project.copy(clips = project.clips.toMutableList().also { java.util.Collections.swap(it, selected, selected - 1) }))
                            onSelect(selected - 1)
                        }) { Text("◀ Move") }
                        TextButton(enabled = selected < project.clips.size - 1 && !exporting, onClick = {
                            onEdit(project.copy(clips = project.clips.toMutableList().also { java.util.Collections.swap(it, selected, selected + 1) }))
                            onSelect(selected + 1)
                        }) { Text("Move ▶") }
                        TextButton(enabled = !exporting, onClick = {
                            val list = project.clips.toMutableList().also { it.removeAt(selected) }
                            onEdit(project.copy(clips = list))
                            onSelect(selected.coerceIn(-1, list.size - 1))
                            onStatus("${list.size} clip(s)")
                        }) { Text("🗑 Delete") }
                    }
                }
                Tool.SPEED -> clip?.let { c ->
                    ChipRow("Speed:", listOf(0.25f, 0.5f, 1f, 1.5f, 2f), { c.speed == it }, { if (it == 1f) "1×" else "${it}×" }, !exporting) { sp ->
                        onEdit(project.copy(clips = project.clips.toMutableList().also { it[selected] = c.copy(speed = sp) }))
                        onStatus("${project.clips.size} clip(s) · ${fmt(project.totalOutMs)} total")
                    }
                }
                Tool.VOLUME -> clip?.let { c ->
                    FilterChip(selected = c.muted,
                        onClick = { onEdit(project.copy(clips = project.clips.toMutableList().also { it[selected] = c.copy(muted = !c.muted) })) },
                        label = { Text(if (c.muted) "Muted" else "Mute") }, enabled = !exporting)
                    if (!c.muted) {
                        Text("Volume: ${(c.volume * 100).toInt()}%", fontSize = 13.sp)
                        Slider(value = c.volume, onValueChange = { v -> onMutateClip { it.copy(volume = v) } },
                            valueRange = 0f..2f, enabled = !exporting)
                    }
                }
                Tool.ROTATE -> clip?.let { c ->
                    Text("Rotation: ${c.rotationDeg}°", fontSize = 13.sp)
                    Button(enabled = !exporting && !c.muted, onClick = {
                        onEdit(project.copy(clips = project.clips.toMutableList().also { it[selected] = c.copy(rotationDeg = (c.rotationDeg + 90) % 360) }))
                    }) { Text("Rotate 90°") }
                }
                Tool.FILTER -> ChipRow("Filter:", VideoFilter.entries, { project.filter == it }, { it.label }, !exporting) { onEdit(project.copy(filter = it)) }
                Tool.TRANSITION -> ChipRow("Transition:", Transition.entries, { project.transition == it }, { it.label }, !exporting) { onEdit(project.copy(transition = it)) }
                Tool.TEXT -> {
                    OutlinedTextField(value = project.title, onValueChange = { onProject(project.copy(title = it)) },
                        label = { Text("Title overlay") }, singleLine = true, enabled = !exporting,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                        modifier = Modifier.fillMaxWidth())
                }
                Tool.STICKER -> StickerPanel(project, stickerText, stickerSel, exporting, focus, onProject, onEdit, onStickerText, onStickerSel)
                Tool.MUSIC -> OutlinedButton(onClick = onAddMusic, enabled = !exporting) {
                    Text(if (project.musicUri != null) "Music ✓ (tap to remove)" else "Add music")
                }
                Tool.RATIO -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AspectRatio.entries.forEach { a ->
                        FilterChip(selected = project.aspect == a, onClick = { onEdit(project.copy(aspect = a)) },
                            label = { Text(a.label) }, enabled = !exporting)
                    }
                    if (project.aspect != AspectRatio.SOURCE) FilterChip(
                        selected = project.letterbox, onClick = { onEdit(project.copy(letterbox = !project.letterbox)) },
                        label = { Text("Letterbox") }, enabled = !exporting)
                }
                Tool.QUALITY -> ChipRow("Quality:", ExportResolution.entries, { project.resolution == it }, { it.label }, !exporting) { onEdit(project.copy(resolution = it)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun StickerPanel(
    project: EditorProject,
    stickerText: String,
    stickerSel: Int,
    exporting: Boolean,
    focus: androidx.compose.ui.focus.FocusManager,
    onProject: (EditorProject) -> Unit,
    onEdit: (EditorProject) -> Unit,
    onStickerText: (String) -> Unit,
    onStickerSel: (Int) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(value = stickerText, onValueChange = onStickerText,
            label = { Text("Emoji / text") }, singleLine = true, enabled = !exporting,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
            modifier = Modifier.width(200.dp))
        Button(onClick = {
            if (stickerText.isNotBlank()) {
                onEdit(project.copy(stickers = project.stickers + Sticker(stickerText)))
                onStickerSel(project.stickers.size); onStickerText(""); focus.clearFocus()
            }
        }, enabled = !exporting) { Text("Add") }
        if (project.stickers.isNotEmpty()) TextButton(
            onClick = { onEdit(project.copy(stickers = emptyList())); onStickerSel(-1) },
            enabled = !exporting) { Text("Clear (${project.stickers.size})") }
    }
    if (project.stickers.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        project.stickers.forEachIndexed { i, s ->
            FilterChip(selected = stickerSel == i, onClick = { onStickerSel(i) },
                label = { Text(s.text.take(8)) }, enabled = !exporting)
        }
    }
    project.stickers.getOrNull(stickerSel)?.let { st ->
        fun update(s: Sticker) { onProject(project.copy(stickers = project.stickers.toMutableList().also { it[stickerSel] = s })) }
        Text("Position", fontSize = 13.sp)
        Slider(value = st.x, onValueChange = { update(st.copy(x = it)) }, valueRange = 0f..1f, enabled = !exporting)
        Slider(value = st.y, onValueChange = { update(st.copy(y = it)) }, valueRange = 0f..1f, enabled = !exporting)
        val total = project.totalOutMs
        if (total > 0L) {
            val endShown = if (st.endMs < 0L) total else st.endMs
            Text("Show ${fmt(st.startMs)} → ${fmt(endShown)}", fontSize = 13.sp)
            Slider(value = st.startMs.coerceIn(0L, total).toFloat(),
                onValueChange = { update(st.copy(startMs = it.toLong().coerceAtMost(endShown - 100))) },
                valueRange = 0f..total.toFloat(), enabled = !exporting)
            Slider(value = endShown.coerceIn(0L, total).toFloat(),
                onValueChange = { v ->
                    val end = if (v >= total.toFloat() - 50f) -1L else v.toLong().coerceAtLeast(st.startMs + 100)
                    update(st.copy(endMs = end))
                }, valueRange = 0f..total.toFloat(), enabled = !exporting)
        }
        TextButton(onClick = {
            val list = project.stickers.toMutableList().also { it.removeAt(stickerSel) }
            onEdit(project.copy(stickers = list))
            onStickerSel((stickerSel - 1).coerceIn(-1, list.size - 1))
        }, enabled = !exporting) { Text("Delete sticker") }
    }
}

/** Labelled row of single-select [FilterChip]s — used for speed/filter/transition/etc. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipRow(
    label: String,
    items: List<T>,
    isSelected: (T) -> Boolean,
    labelOf: (T) -> String,
    enabled: Boolean,
    onPick: (T) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 13.sp, modifier = Modifier.align(Alignment.CenterVertically))
        items.forEach { item ->
            FilterChip(selected = isSelected(item), onClick = { onPick(item) },
                label = { Text(labelOf(item)) }, enabled = enabled)
        }
    }
}

private fun saveProject(context: android.content.Context, p: EditorProject) {
    fun enc(s: String) = android.util.Base64.encodeToString(s.toByteArray(), android.util.Base64.NO_WRAP)
    val sb = StringBuilder()
    sb.append("v5|${p.resolution.name}|${enc(p.title)}|${p.musicUri?.let { enc(it.toString()) } ?: ""}|${p.filter.name}|${p.transition.name}|${p.aspect.name}|${p.letterbox}\n")
    p.clips.forEach { c ->
        sb.append("${enc(c.uri.toString())}|${c.durationMs}|${c.trimStartMs}|${c.trimEndMs}|${c.speed}|${c.muted}|${c.volume}|${c.rotationDeg}\n")
    }
    p.stickers.forEach { s ->
        sb.append("S|${enc(s.text)}|${s.x}|${s.y}|${s.sizePx}|${s.startMs}|${s.endMs}\n")
    }
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
            Clip(
                Uri.parse(dec(c[0])), c[1].toLong(), c[2].toLong(), c[3].toLong(), c[4].toFloat(), c[5].toBoolean(),
                c.getOrNull(6)?.toFloatOrNull() ?: 1f, c.getOrNull(7)?.toIntOrNull() ?: 0
            )
        }
        val stickers = body.filter { it.startsWith("S|") }.mapNotNull { ln ->
            val s = ln.split("|")
            runCatching {
                Sticker(
                    dec(s[1]), s[2].toFloat(), s[3].toFloat(), s[4].toInt(),
                    s.getOrNull(5)?.toLongOrNull() ?: 0L, s.getOrNull(6)?.toLongOrNull() ?: -1L
                )
            }.getOrNull()
        }
        val transition = m.getOrNull(5)?.let { runCatching { Transition.valueOf(it) }.getOrNull() } ?: Transition.NONE
        val aspect = m.getOrNull(6)?.let { runCatching { AspectRatio.valueOf(it) }.getOrNull() } ?: AspectRatio.SOURCE
        val letterbox = m.getOrNull(7)?.toBoolean() ?: false
        EditorProject(clips, ExportResolution.valueOf(m[1]), dec(m[2]), music, VideoFilter.valueOf(m[4]), transition, aspect, stickers, letterbox)
    }.getOrNull()
}

/** A single preview frame at [atMs], scaled small for the timeline list. */
private fun frameAt(context: android.content.Context, uri: Uri, atMs: Long): Bitmap? = runCatching {
    MediaMetadataRetriever().use { r ->
        r.setDataSource(context, uri)
        r.getScaledFrameAtTime(
            atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 144, 88
        )
    }
}.getOrNull()

private fun durationOf(context: android.content.Context, uri: Uri): Long? = runCatching {
    MediaMetadataRetriever().use { r ->
        r.setDataSource(context, uri)
        r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong()
    }
}.getOrNull()

private fun fmt(ms: Long): String {
    val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60)
}
