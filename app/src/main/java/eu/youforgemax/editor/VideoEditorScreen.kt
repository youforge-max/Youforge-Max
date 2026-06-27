package eu.youforgemax.editor

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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
 * Youforge-Max — video editor. Redesigned as a full-bleed preview with a swipe-up
 * bottom sheet of grouped tools (Clips · Style · Audio), instead of one long scroll of
 * every control. Import clips, trim/speed/rotate each, add title + sticker overlays,
 * music, colour filters, transitions and aspect crop, then export a single merged MP4
 * via [EditorExporter] (Media3 Transformer). Live preview of the selected clip with
 * ExoPlayer; export runs in a foreground service ([VideoExportService]).
 */
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
    // Active tool group in the bottom sheet: 0 = Clips, 1 = Style, 2 = Audio.
    var tab by remember { mutableIntStateOf(0) }

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

    val scaffoldState = rememberBottomSheetScaffoldState(
        bottomSheetState = rememberStandardBottomSheetState(
            initialValue = SheetValue.PartiallyExpanded, skipHiddenState = true
        )
    )

    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        sheetPeekHeight = 280.dp,
        sheetContainerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            EditorTopBar(
                status = status,
                exporting = exporting,
                indeterminate = exportState.indeterminate,
                progress = progress,
                canUndo = undo.isNotEmpty(),
                canRedo = redo.isNotEmpty(),
                canSave = !project.isEmpty,
                canExport = !project.isEmpty,
                onUndo = { if (undo.isNotEmpty()) { redo = (redo + project).takeLast(30); project = undo.last(); undo = undo.dropLast(1); status = "Undone" } },
                onRedo = { if (redo.isNotEmpty()) { undo = (undo + project).takeLast(30); project = redo.last(); redo = redo.dropLast(1); status = "Redone" } },
                onSave = { saveProject(context, project); status = "Project saved" },
                onLoad = {
                    loadProject(context)?.let { edit(it); selected = if (it.clips.isEmpty()) -1 else 0; status = "Project loaded · ${it.clips.size} clip(s)" }
                        ?: run { status = "No saved project" }
                },
                onExport = { startExport() },
                onCancel = { VideoExportService.cancel(context) },
            )
        },
        sheetContent = {
            // Tab selector lives in the always-visible peek area.
            val tabs = listOf("Clips", "Style", "Audio")
            TabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
                tabs.forEachIndexed { i, label ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) })
                }
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 220.dp)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (tab) {
                    0 -> ClipsTab(
                        context, project, selected, exporting,
                        onAddClips = { picker.launch(arrayOf("video/*")) },
                        onSelect = { selected = it },
                        onEdit = ::edit,
                        onProject = { project = it },
                        onSelectedChange = { selected = it },
                        onStatus = { status = it },
                    )
                    1 -> StyleTab(
                        project, stickerText, stickerSel, exporting, focus,
                        onProject = { project = it },
                        onEdit = ::edit,
                        onStickerText = { stickerText = it },
                        onStickerSel = { stickerSel = it },
                        previewTotal = project.totalOutMs,
                    )
                    2 -> AudioTab(
                        project, selected, exporting,
                        onAddMusic = {
                            if (project.musicUri != null) { edit(project.copy(musicUri = null)); status = "Music removed" }
                            else musicPicker.launch(arrayOf("audio/*"))
                        },
                        onProject = { project = it },
                        onEdit = ::edit,
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    ) { padding ->
        // Full-bleed preview. The sheet floats over the content, so reserve the peek
        // height at the bottom to keep the player clear of it.
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(bottom = 280.dp)
                .padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            if (project.isEmpty) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("🎬", fontSize = 48.sp)
                    Text("No clips yet", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("Add video clips to start editing.", fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.outline)
                    Button(onClick = { picker.launch(arrayOf("video/*")) }, enabled = !exporting) {
                        Text("Add clips")
                    }
                }
            } else {
                PreviewArea(
                    project = project,
                    player = player,
                    previewSize = previewSize,
                    onPreviewSize = { previewSize = it },
                    density = density,
                    stickerSel = stickerSel,
                    onStickerSel = { stickerSel = it },
                    onProject = { project = it },
                )
            }
        }
    }
}

/** Slim header: title, live status, history/save/load + the primary Export action. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun EditorTopBar(
    status: String,
    exporting: Boolean,
    indeterminate: Boolean,
    progress: Int,
    canUndo: Boolean,
    canRedo: Boolean,
    canSave: Boolean,
    canExport: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSave: () -> Unit,
    onLoad: () -> Unit,
    onExport: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(tonalElevation = 2.dp) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Video Editor", fontSize = 20.sp, fontWeight = FontWeight.Black)
                    Text(status, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, maxLines = 1)
                }
                TextButton(onClick = onUndo, enabled = canUndo && !exporting) { Text("↶") }
                TextButton(onClick = onRedo, enabled = canRedo && !exporting) { Text("↷") }
                var menu by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { menu = true }, enabled = !exporting) { Text("⋯") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Save project") }, enabled = canSave,
                            onClick = { menu = false; onSave() })
                        DropdownMenuItem(text = { Text("Load project") },
                            onClick = { menu = false; onLoad() })
                    }
                }
                if (exporting) OutlinedButton(onClick = onCancel) { Text("Cancel") }
                else Button(onClick = onExport, enabled = canExport) { Text("Export") }
            }
            if (exporting) {
                if (indeterminate) LinearProgressIndicator(Modifier.fillMaxWidth())
                else LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** Live preview: ExoPlayer shaped to the chosen aspect + draggable sticker overlays. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun PreviewArea(
    project: EditorProject,
    player: ExoPlayer,
    previewSize: IntSize,
    onPreviewSize: (IntSize) -> Unit,
    density: androidx.compose.ui.unit.Density,
    stickerSel: Int,
    onStickerSel: (Int) -> Unit,
    onProject: (EditorProject) -> Unit,
) {
    Box(
        Modifier.fillMaxSize().onSizeChanged { onPreviewSize(it) },
        contentAlignment = Alignment.Center
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
            modifier = viewMod.clip(RoundedCornerShape(10.dp))
        )
        // Draggable sticker overlays — drag to reposition; a drag also selects it.
        // sizePx is relative to a 720-tall canvas (see EditorExporter); scale to preview.
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
}

/** Tools: add clips, the clip timeline, and trim/speed/rotate for the selected clip. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ClipsTab(
    context: android.content.Context,
    project: EditorProject,
    selected: Int,
    exporting: Boolean,
    onAddClips: () -> Unit,
    onSelect: (Int) -> Unit,
    onEdit: (EditorProject) -> Unit,
    onProject: (EditorProject) -> Unit,
    onSelectedChange: (Int) -> Unit,
    onStatus: (String) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = onAddClips, enabled = !exporting) { Text("Add clips") }
        Text("${project.clips.size} clip(s) · ${fmt(project.totalOutMs)}", fontSize = 13.sp,
            color = MaterialTheme.colorScheme.outline)
    }

    // Per-clip controls for the selected clip.
    project.clips.getOrNull(selected)?.let { clip ->
        SectionLabel("Clip ${selected + 1}")
        TrimPanel(clip) { updated ->
            onProject(project.copy(clips = project.clips.toMutableList().also { it[selected] = updated }))
            onStatus("${project.clips.size} clip(s) · ${fmt(project.totalOutMs)} total")
        }
        ChipRow("Speed:", listOf(0.25f, 0.5f, 1f, 1.5f, 2f), { clip.speed == it }, { if (it == 1f) "1×" else "${it}×" }, !exporting) { sp ->
            onEdit(project.copy(clips = project.clips.toMutableList().also { it[selected] = clip.copy(speed = sp) }))
            onStatus("${project.clips.size} clip(s) · ${fmt(project.totalOutMs)} total")
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Rotate: ${clip.rotationDeg}°", fontSize = 13.sp)
            TextButton(enabled = !exporting && !clip.muted, onClick = {
                onEdit(project.copy(clips = project.clips.toMutableList().also { it[selected] = clip.copy(rotationDeg = (clip.rotationDeg + 90) % 360) }))
            }) { Text("Rotate 90°") }
        }
    }

    // The timeline (vertical clip list for the MVP).
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        project.clips.forEachIndexed { idx, clip ->
            Card(
                onClick = { onSelect(idx) },
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (idx == selected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    Modifier.padding(10.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val thumb = remember(clip.uri, clip.trimStartMs) { frameAt(context, clip.uri, clip.trimStartMs) }
                    if (thumb != null) Image(
                        bitmap = thumb.asImageBitmap(), contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(72.dp, 44.dp).clip(RoundedCornerShape(6.dp))
                    )
                    Text("Clip ${idx + 1}  ·  ${fmt(clip.outMs)}", fontSize = 14.sp, modifier = Modifier.weight(1f))
                    TextButton(enabled = idx > 0 && !exporting, onClick = {
                        onEdit(project.copy(clips = project.clips.toMutableList().also { java.util.Collections.swap(it, idx, idx - 1) }))
                        onSelectedChange(idx - 1)
                    }) { Text("◀") }
                    TextButton(enabled = idx < project.clips.size - 1 && !exporting, onClick = {
                        onEdit(project.copy(clips = project.clips.toMutableList().also { java.util.Collections.swap(it, idx, idx + 1) }))
                        onSelectedChange(idx + 1)
                    }) { Text("▶") }
                    TextButton(onClick = {
                        val list = project.clips.toMutableList().also { it.removeAt(idx) }
                        onEdit(project.copy(clips = list))
                        onSelectedChange(selected.coerceIn(-1, list.size - 1))
                    }) { Text("✕") }
                }
            }
        }
    }
}

/** Tools: title overlay, colour filter, transition, aspect crop, resolution, stickers. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun StyleTab(
    project: EditorProject,
    stickerText: String,
    stickerSel: Int,
    exporting: Boolean,
    focus: androidx.compose.ui.focus.FocusManager,
    onProject: (EditorProject) -> Unit,
    onEdit: (EditorProject) -> Unit,
    onStickerText: (String) -> Unit,
    onStickerSel: (Int) -> Unit,
    previewTotal: Long,
) {
    OutlinedTextField(
        value = project.title,
        onValueChange = { onProject(project.copy(title = it)) },
        label = { Text("Title overlay (optional)") },
        singleLine = true,
        enabled = !exporting,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
        modifier = Modifier.fillMaxWidth()
    )
    ChipRow("Filter:", VideoFilter.entries, { project.filter == it }, { it.label }, !exporting) { onEdit(project.copy(filter = it)) }
    ChipRow("Transition:", Transition.entries, { project.transition == it }, { it.label }, !exporting) { onEdit(project.copy(transition = it)) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Aspect:", fontSize = 13.sp, modifier = Modifier.align(Alignment.CenterVertically))
        AspectRatio.entries.forEach { a ->
            FilterChip(selected = project.aspect == a, onClick = { onEdit(project.copy(aspect = a)) },
                label = { Text(a.label) }, enabled = !exporting)
        }
        if (project.aspect != AspectRatio.SOURCE) FilterChip(
            selected = project.letterbox, onClick = { onEdit(project.copy(letterbox = !project.letterbox)) },
            label = { Text("Letterbox") }, enabled = !exporting
        )
    }
    ChipRow("Export:", ExportResolution.entries, { project.resolution == it }, { it.label }, !exporting) { onEdit(project.copy(resolution = it)) }

    SectionLabel("Stickers")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = stickerText,
            onValueChange = onStickerText,
            label = { Text("Sticker (emoji/text)") },
            singleLine = true,
            enabled = !exporting,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
            modifier = Modifier.width(220.dp)
        )
        Button(
            onClick = {
                if (stickerText.isNotBlank()) {
                    onEdit(project.copy(stickers = project.stickers + Sticker(stickerText)))
                    onStickerSel(project.stickers.size) // index of the new one
                    onStickerText(""); focus.clearFocus()
                }
            },
            enabled = !exporting
        ) { Text("Add") }
        if (project.stickers.isNotEmpty()) TextButton(
            onClick = { onEdit(project.copy(stickers = emptyList())); onStickerSel(-1) },
            enabled = !exporting
        ) { Text("Clear (${project.stickers.size})") }
    }
    if (project.stickers.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        project.stickers.forEachIndexed { i, s ->
            FilterChip(selected = stickerSel == i, onClick = { onStickerSel(i) },
                label = { Text(s.text.take(8)) }, enabled = !exporting)
        }
    }
    project.stickers.getOrNull(stickerSel)?.let { st ->
        fun update(s: Sticker) {
            onProject(project.copy(stickers = project.stickers.toMutableList().also { it[stickerSel] = s }))
        }
        Text("Sticker “${st.text}” position", fontSize = 13.sp)
        Slider(value = st.x, onValueChange = { update(st.copy(x = it)) }, valueRange = 0f..1f, enabled = !exporting)
        Slider(value = st.y, onValueChange = { update(st.copy(y = it)) }, valueRange = 0f..1f, enabled = !exporting)
        if (previewTotal > 0L) {
            val endShown = if (st.endMs < 0L) previewTotal else st.endMs
            Text("Show ${fmt(st.startMs)} → ${fmt(endShown)}", fontSize = 13.sp)
            Slider(
                value = st.startMs.coerceIn(0L, previewTotal).toFloat(),
                onValueChange = { update(st.copy(startMs = it.toLong().coerceAtMost(endShown - 100))) },
                valueRange = 0f..previewTotal.toFloat(), enabled = !exporting
            )
            Slider(
                value = endShown.coerceIn(0L, previewTotal).toFloat(),
                onValueChange = { v ->
                    val end = if (v >= previewTotal.toFloat() - 50f) -1L else v.toLong().coerceAtLeast(st.startMs + 100)
                    update(st.copy(endMs = end))
                },
                valueRange = 0f..previewTotal.toFloat(), enabled = !exporting
            )
        }
        TextButton(
            onClick = {
                val list = project.stickers.toMutableList().also { it.removeAt(stickerSel) }
                onEdit(project.copy(stickers = list))
                onStickerSel((stickerSel - 1).coerceIn(-1, list.size - 1))
            },
            enabled = !exporting
        ) { Text("Delete sticker") }
    }
}

/** Tools: background music + per-clip volume/mute for the selected clip. */
@Composable
private fun AudioTab(
    project: EditorProject,
    selected: Int,
    exporting: Boolean,
    onAddMusic: () -> Unit,
    onProject: (EditorProject) -> Unit,
    onEdit: (EditorProject) -> Unit,
) {
    SectionLabel("Background music")
    OutlinedButton(onClick = onAddMusic, enabled = !exporting) {
        Text(if (project.musicUri != null) "Music ✓ (tap to remove)" else "Add music")
    }

    val clip = project.clips.getOrNull(selected)
    if (clip == null) {
        Text("Select a clip to adjust its audio.", fontSize = 13.sp, color = MaterialTheme.colorScheme.outline)
    } else {
        SectionLabel("Clip ${selected + 1} audio")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = clip.muted,
                onClick = { onEdit(project.copy(clips = project.clips.toMutableList().also { it[selected] = clip.copy(muted = !clip.muted) })) },
                label = { Text(if (clip.muted) "Muted" else "Mute") },
                enabled = !exporting
            )
        }
        if (!clip.muted) {
            Text("Volume: ${(clip.volume * 100).toInt()}%", fontSize = 13.sp)
            Slider(
                value = clip.volume,
                onValueChange = { v -> onProject(project.copy(clips = project.clips.toMutableList().also { it[selected] = clip.copy(volume = v) })) },
                valueRange = 0f..2f,
                enabled = !exporting
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
}

/** Labelled row of single-select [FilterChip]s — used for speed/filter/transition/resolution. */
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

@Composable
private fun TrimPanel(clip: Clip, onChange: (Clip) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Trim — ${fmt(clip.trimStartMs)} → ${fmt(clip.trimEndMs)}", fontSize = 13.sp)
        Slider(
            value = clip.trimStartMs.toFloat(),
            onValueChange = { onChange(clip.copy(trimStartMs = it.toLong().coerceAtMost(clip.trimEndMs - 100))) },
            valueRange = 0f..clip.durationMs.toFloat()
        )
        Slider(
            value = clip.trimEndMs.toFloat(),
            onValueChange = { onChange(clip.copy(trimEndMs = it.toLong().coerceAtLeast(clip.trimStartMs + 100))) },
            valueRange = 0f..clip.durationMs.toFloat()
        )
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
