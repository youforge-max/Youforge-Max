package eu.cisodiagonal.youforge.editor

import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/**
 * YouForge Max — video editor (P1 MVP). Import clips, trim each, and export a single
 * merged MP4 via [EditorExporter] (Media3 Transformer). Live preview of the selected
 * clip with ExoPlayer. Speed / text+sticker overlays / music / filters / transitions
 * are the next phases (the engine and overlay renderer already exist to back them).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoEditorScreen() {
    val context = LocalContext.current
    var project by remember { mutableStateOf(EditorProject()) }
    var selected by remember { mutableIntStateOf(-1) }
    var status by remember { mutableStateOf("Add clips to start.") }
    var progress by remember { mutableStateOf(0) }
    var exporting by remember { mutableStateOf(false) }

    val exporter = remember { EditorExporter(context) }

    // ExoPlayer for the preview; released when the screen leaves composition.
    val player = remember {
        ExoPlayer.Builder(context).build().apply { playWhenReady = false }
    }
    DisposableEffect(Unit) { onDispose { player.release() } }

    // (Re)load the preview when the selected clip changes.
    LaunchedEffect(selected, project.clips.size) {
        val c = project.clips.getOrNull(selected)
        if (c != null) {
            player.setMediaItem(MediaItem.fromUri(c.uri))
            player.prepare()
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        val added = uris.mapNotNull { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            durationOf(context, uri)?.let { dur -> Clip(uri = uri, durationMs = dur) }
        }
        project = project.copy(clips = project.clips + added)
        if (selected < 0 && project.clips.isNotEmpty()) selected = 0
        status = "${project.clips.size} clip(s) · ${fmt(project.totalOutMs)} total"
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Video Editor", fontSize = 22.sp, fontWeight = FontWeight.Black)
        Text(status, fontSize = 13.sp, color = MaterialTheme.colorScheme.outline)

        // Preview
        AndroidView(
            factory = { ctx -> PlayerView(ctx).apply { this.player = player; useController = true } },
            modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp)
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { picker.launch(arrayOf("video/*")) }, enabled = !exporting) {
                Text("Add clips")
            }
            Button(
                onClick = {
                    exporting = true; progress = 0; status = "Exporting…"
                    exporter.export(project, object : EditorExporter.Callback {
                        override fun onProgress(percent: Int) { progress = percent }
                        override fun onDone(output: java.io.File) {
                            exporting = false; progress = 100
                            status = "Saved: ${output.name}"
                        }
                        override fun onError(message: String) {
                            exporting = false; status = "Error: $message"
                        }
                    })
                },
                enabled = !exporting && !project.isEmpty
            ) { Text("Export") }
        }
        if (exporting) LinearProgressIndicator(
            progress = { progress / 100f }, modifier = Modifier.fillMaxWidth()
        )

        // Trim panel for the selected clip
        project.clips.getOrNull(selected)?.let { clip ->
            TrimPanel(clip) { updated ->
                project = project.copy(
                    clips = project.clips.toMutableList().also { it[selected] = updated }
                )
                status = "${project.clips.size} clip(s) · ${fmt(project.totalOutMs)} total"
            }
        }

        // Clip list (the timeline, as a vertical list for the MVP)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
            items(project.clips) { clip ->
                val idx = project.clips.indexOf(clip)
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
                        Modifier.padding(12.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Clip ${idx + 1}  ·  ${fmt(clip.outMs)}", fontSize = 14.sp)
                        TextButton(onClick = {
                            val list = project.clips.toMutableList().also { it.removeAt(idx) }
                            project = project.copy(clips = list)
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

private fun durationOf(context: android.content.Context, uri: Uri): Long? = runCatching {
    MediaMetadataRetriever().use { r ->
        r.setDataSource(context, uri)
        r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong()
    }
}.getOrNull()

private fun fmt(ms: Long): String {
    val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60)
}
