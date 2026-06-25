package eu.cisodiagonal.youforge.editor

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
 * YouForge Max — video editor (P1 MVP). Import clips, trim each, and export a single
 * merged MP4 via [EditorExporter] (Media3 Transformer). Live preview of the selected
 * clip with ExoPlayer. Speed / text+sticker overlays / music / filters / transitions
 * are the next phases (the engine and overlay renderer already exist to back them).
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

    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Video Editor", fontSize = 22.sp, fontWeight = FontWeight.Black)
        Text(status, fontSize = 13.sp, color = MaterialTheme.colorScheme.outline)

        // Preview — for a chosen aspect the view is shaped to that ratio and the video
        // is zoom-cropped to fill it, approximating the export crop. Source = fit.
        Box(
            Modifier.fillMaxWidth().height(240.dp).onSizeChanged { previewSize = it },
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
                modifier = viewMod
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
                            detectDragGestures(onDragStart = { stickerSel = i }) { change, drag ->
                                change.consume()
                                val cur = project.stickers.getOrNull(i) ?: return@detectDragGestures
                                val nx = (cur.x + drag.x / previewSize.width).coerceIn(0f, 1f)
                                val ny = (cur.y + drag.y / previewSize.height).coerceIn(0f, 1f)
                                project = project.copy(
                                    stickers = project.stickers.toMutableList().also { it[i] = cur.copy(x = nx, y = ny) }
                                )
                            }
                        }
                )
            }
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(onClick = { picker.launch(arrayOf("video/*")) }, enabled = !exporting) {
                Text("Add clips")
            }
            OutlinedButton(
                onClick = {
                    if (project.musicUri != null) { edit(project.copy(musicUri = null)); status = "Music removed" }
                    else musicPicker.launch(arrayOf("audio/*"))
                },
                enabled = !exporting
            ) { Text(if (project.musicUri != null) "Music ✓" else "Music") }
            Button(
                onClick = {
                    focus.clearFocus()
                    requestNotifIfNeeded()
                    exporting = true; progress = 0; status = "Exporting…"
                    VideoExportService.start(context, project)
                },
                enabled = !exporting && !project.isEmpty
            ) { Text("Export") }
            if (exporting) OutlinedButton(onClick = { VideoExportService.cancel(context) }) {
                Text("Cancel")
            }
        }
        if (exporting) {
            if (exportState.indeterminate) LinearProgressIndicator(Modifier.fillMaxWidth())
            else LinearProgressIndicator(
                progress = { progress / 100f }, modifier = Modifier.fillMaxWidth()
            )
        }

        // Undo + project save/load
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(
                onClick = { if (undo.isNotEmpty()) { redo = (redo + project).takeLast(30); project = undo.last(); undo = undo.dropLast(1); status = "Undone" } },
                enabled = undo.isNotEmpty() && !exporting
            ) { Text("Undo") }
            TextButton(
                onClick = { if (redo.isNotEmpty()) { undo = (undo + project).takeLast(30); project = redo.last(); redo = redo.dropLast(1); status = "Redone" } },
                enabled = redo.isNotEmpty() && !exporting
            ) { Text("Redo") }
            TextButton(onClick = { saveProject(context, project); status = "Project saved" }, enabled = !exporting && !project.isEmpty) { Text("Save") }
            TextButton(
                onClick = {
                    loadProject(context)?.let { edit(it); selected = if (it.clips.isEmpty()) -1 else 0; status = "Project loaded · ${it.clips.size} clip(s)" }
                        ?: run { status = "No saved project" }
                },
                enabled = !exporting
            ) { Text("Load") }
        }

        // Burned-in title overlay
        OutlinedTextField(
            value = project.title,
            onValueChange = { project = project.copy(title = it) },
            label = { Text("Title overlay (optional)") },
            singleLine = true,
            enabled = !exporting,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
            modifier = Modifier.fillMaxWidth()
        )

        // Export resolution (wraps so the chips never run off-screen)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Export:", fontSize = 13.sp, modifier = Modifier.align(Alignment.CenterVertically))
            ExportResolution.entries.forEach { res ->
                FilterChip(
                    selected = project.resolution == res,
                    onClick = { edit(project.copy(resolution = res)) },
                    label = { Text(res.label) },
                    enabled = !exporting
                )
            }
        }

        // Colour filter (live preview + applied at export)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Filter:", fontSize = 13.sp, modifier = Modifier.align(Alignment.CenterVertically))
            VideoFilter.entries.forEach { f ->
                FilterChip(
                    selected = project.filter == f,
                    onClick = { edit(project.copy(filter = f)) },
                    label = { Text(f.label) },
                    enabled = !exporting
                )
            }
        }

        // Transition between clips
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Transition:", fontSize = 13.sp, modifier = Modifier.align(Alignment.CenterVertically))
            Transition.entries.forEach { t ->
                FilterChip(
                    selected = project.transition == t,
                    onClick = { edit(project.copy(transition = t)) },
                    label = { Text(t.label) },
                    enabled = !exporting
                )
            }
        }

        // Output aspect ratio / canvas (crop-fill; applied at export)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Aspect:", fontSize = 13.sp, modifier = Modifier.align(Alignment.CenterVertically))
            AspectRatio.entries.forEach { a ->
                FilterChip(
                    selected = project.aspect == a,
                    onClick = { edit(project.copy(aspect = a)) },
                    label = { Text(a.label) },
                    enabled = !exporting
                )
            }
            // Letterbox vs crop-fill (only meaningful for a non-source aspect).
            if (project.aspect != AspectRatio.SOURCE) FilterChip(
                selected = project.letterbox,
                onClick = { edit(project.copy(letterbox = !project.letterbox)) },
                label = { Text("Letterbox") },
                enabled = !exporting
            )
        }

        // Stickers (emoji / short text burned onto the output canvas)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(
                value = stickerText,
                onValueChange = { stickerText = it },
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
                        edit(project.copy(stickers = project.stickers + Sticker(stickerText)))
                        stickerSel = project.stickers.size // index of the new one
                        stickerText = ""; focus.clearFocus()
                    }
                },
                enabled = !exporting
            ) { Text("Add") }
            if (project.stickers.isNotEmpty()) TextButton(
                onClick = { edit(project.copy(stickers = emptyList())); stickerSel = -1 },
                enabled = !exporting
            ) { Text("Clear (${project.stickers.size})") }
        }
        // Select an existing sticker to edit (position / timing / delete).
        if (project.stickers.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            project.stickers.forEachIndexed { i, s ->
                FilterChip(
                    selected = stickerSel == i,
                    onClick = { stickerSel = i },
                    label = { Text(s.text.take(8)) },
                    enabled = !exporting
                )
            }
        }
        // Position + timing for the selected sticker
        project.stickers.getOrNull(stickerSel)?.let { st ->
            fun update(s: Sticker) {
                project = project.copy(stickers = project.stickers.toMutableList().also { it[stickerSel] = s })
            }
            Text("Sticker “${st.text}” position", fontSize = 13.sp)
            Slider(value = st.x, onValueChange = { update(st.copy(x = it)) }, valueRange = 0f..1f, enabled = !exporting)
            Slider(value = st.y, onValueChange = { update(st.copy(y = it)) }, valueRange = 0f..1f, enabled = !exporting)
            // Per-sticker timing (when there's a timeline to place it on)
            val total = project.totalOutMs
            if (total > 0L) {
                val endShown = if (st.endMs < 0L) total else st.endMs
                Text("Show ${fmt(st.startMs)} → ${fmt(endShown)}", fontSize = 13.sp)
                Slider(
                    value = st.startMs.coerceIn(0L, total).toFloat(),
                    onValueChange = { update(st.copy(startMs = it.toLong().coerceAtMost(endShown - 100))) },
                    valueRange = 0f..total.toFloat(), enabled = !exporting
                )
                Slider(
                    value = endShown.coerceIn(0L, total).toFloat(),
                    onValueChange = { v ->
                        // Dragging to the far end means "until the end" (-1).
                        val end = if (v >= total.toFloat() - 50f) -1L else v.toLong().coerceAtLeast(st.startMs + 100)
                        update(st.copy(endMs = end))
                    },
                    valueRange = 0f..total.toFloat(), enabled = !exporting
                )
            }
            TextButton(
                onClick = {
                    val list = project.stickers.toMutableList().also { it.removeAt(stickerSel) }
                    edit(project.copy(stickers = list))
                    stickerSel = (stickerSel - 1).coerceIn(-1, list.size - 1)
                },
                enabled = !exporting
            ) { Text("Delete sticker") }
        }

        // Trim panel for the selected clip
        project.clips.getOrNull(selected)?.let { clip ->
            TrimPanel(clip) { updated ->
                project = project.copy(
                    clips = project.clips.toMutableList().also { it[selected] = updated }
                )
                status = "${project.clips.size} clip(s) · ${fmt(project.totalOutMs)} total"
            }
            // Per-clip speed
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Speed:", fontSize = 13.sp, modifier = Modifier.align(Alignment.CenterVertically))
                listOf(0.25f, 0.5f, 1f, 1.5f, 2f).forEach { sp ->
                    FilterChip(
                        selected = clip.speed == sp,
                        onClick = {
                            edit(project.copy(clips = project.clips.toMutableList().also { it[selected] = clip.copy(speed = sp) }))
                            status = "${project.clips.size} clip(s) · ${fmt(project.totalOutMs)} total"
                        },
                        label = { Text(if (sp == 1f) "1×" else "${sp}×") },
                        enabled = !exporting
                    )
                }
            }
            // Per-clip volume + rotate
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Rotate: ${clip.rotationDeg}°", fontSize = 13.sp, modifier = Modifier.align(Alignment.CenterVertically))
                TextButton(enabled = !exporting && !clip.muted, onClick = {
                    edit(project.copy(clips = project.clips.toMutableList().also { it[selected] = clip.copy(rotationDeg = (clip.rotationDeg + 90) % 360) }))
                }) { Text("Rotate 90°") }
            }
            if (!clip.muted) {
                Text("Volume: ${(clip.volume * 100).toInt()}%", fontSize = 13.sp)
                Slider(
                    value = clip.volume,
                    onValueChange = { v ->
                        project = project.copy(clips = project.clips.toMutableList().also { it[selected] = clip.copy(volume = v) })
                    },
                    valueRange = 0f..2f,
                    enabled = !exporting
                )
            }
        }

        // Clip list (the timeline, as a vertical list for the MVP)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            project.clips.forEachIndexed { idx, clip ->
                Card(
                    onClick = { selected = idx },
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
                        val thumb = remember(clip.uri, clip.trimStartMs) {
                            frameAt(context, clip.uri, clip.trimStartMs)
                        }
                        if (thumb != null) Image(
                            bitmap = thumb.asImageBitmap(), contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(72.dp, 44.dp).then(Modifier)
                        )
                        Text("Clip ${idx + 1}  ·  ${fmt(clip.outMs)}", fontSize = 14.sp,
                            modifier = Modifier.weight(1f))
                        TextButton(enabled = idx > 0 && !exporting, onClick = {
                            edit(project.copy(clips = project.clips.toMutableList().also { java.util.Collections.swap(it, idx, idx - 1) }))
                            selected = idx - 1
                        }) { Text("◀") }
                        TextButton(enabled = idx < project.clips.size - 1 && !exporting, onClick = {
                            edit(project.copy(clips = project.clips.toMutableList().also { java.util.Collections.swap(it, idx, idx + 1) }))
                            selected = idx + 1
                        }) { Text("▶") }
                        TextButton(onClick = {
                            edit(project.copy(clips = project.clips.toMutableList()
                                .also { it[idx] = clip.copy(muted = !clip.muted) }))
                        }) { Text(if (clip.muted) "Unmute" else "Mute") }
                        TextButton(onClick = {
                            val list = project.clips.toMutableList().also { it.removeAt(idx) }
                            edit(project.copy(clips = list))
                            selected = (selected).coerceIn(-1, list.size - 1)
                        }) { Text("Remove") }
                    }
                }
            }
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
