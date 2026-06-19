package eu.cisodiagonal.youforge.thumb

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThumbForgeScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = (context as ComponentActivity).lifecycleScope
    val modelMgr = remember { ModelManager(context) }
    val settings = remember { Settings(context) }

    var sourceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var rendered by remember { mutableStateOf<Bitmap?>(null) }
    var spec by remember { mutableStateOf<OverlaySpec?>(null) }
    var description by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("build r4 · Pick a photo to start.") }
    var showSettings by remember { mutableStateOf(false) }
    var modelReady by remember { mutableStateOf(modelMgr.isPresent()) }
    var stickers by remember { mutableStateOf<List<Sticker>>(emptyList()) }
    var selectedId by remember { mutableStateOf<Long?>(null) }
    var previewSize by remember { mutableStateOf(IntSize.Zero) }

    fun rerender() {
        val src = sourceBitmap ?: return
        rendered = ThumbnailRenderer.render(context, src, spec, stickers)
    }

    fun updateSelected(transform: (Sticker) -> Sticker) {
        val id = selectedId ?: return
        stickers = stickers.map { if (it.id == id) transform(it) else it }
        rerender()
    }

    fun addSticker(kind: StickerKind) {
        if (sourceBitmap == null) { status = "Pick a photo first."; return }
        val s = Sticker(id = Stickers.nextId(), kind = kind, scale = Stickers.defaultScale(kind))
        stickers = stickers + s
        selectedId = s.id
        rerender()
        status = "Sticker added — drag it on the preview, scale/delete below."
    }

    val picker = rememberLauncherForPicker { uri ->
        if (uri == null) return@rememberLauncherForPicker
        scope.launch {
            status = "Loading photo…"
            var why = ""
            val bmp = withContext(Dispatchers.IO) { decodeSoftware(context, uri) { why = it } }
            if (bmp == null) { status = "Could not read that image. $why"; return@launch }
            sourceBitmap = bmp
            spec = null
            stickers = emptyList()
            selectedId = null
            rendered = ThumbnailRenderer.render(context, bmp, null, emptyList())
            status = "Describe your thumbnail, then tap Suggest."
        }
    }

    fun generate(useAi: Boolean) {
        if (sourceBitmap == null) { status = "Pick a photo first."; return }
        if (description.isBlank()) { status = "Type a description first."; return }
        scope.launch {
            busy = true
            try {
                val sp = if (useAi && modelMgr.isPresent()) {
                    status = "AI thinking (on-device)…"
                    OnDeviceLlm(context, modelMgr.modelFile).suggest(description)
                } else {
                    if (useAi) status = "No model yet — used offline template."
                    TemplateProvider.suggest(description)
                }
                spec = sp
                rerender()
                if (status.startsWith("AI thinking")) status = "Done. Tweak below or export."
            } catch (e: Exception) {
                spec = TemplateProvider.suggest(description)
                rerender()
                status = "AI failed (${e.message}); used template."
            } finally {
                busy = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Thumbnail Maker") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("←") }
                },
                actions = {
                    TextButton(onClick = { showSettings = true }) {
                        Text(if (modelReady) "Model ✓" else "Model")
                    }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(onClick = {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }, modifier = Modifier.fillMaxWidth()) {
                Text(if (sourceBitmap == null) "Pick photo" else "Pick another photo")
            }

            // Preview (16:9) — capped so the controls below stay on screen.
            // Drag a sticker to move it; tap a sticker to select.
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
              Box(
                Modifier
                    // Height-capped so the editor controls below always stay on
                    // screen (some devices made the preview tall enough to push
                    // everything off-screen). Width follows from the 16:9 height.
                    .heightIn(max = 200.dp)
                    .aspectRatio(16f / 9f, matchHeightConstraintsFirst = true)
                    .clip(RoundedCornerShape(10.dp))
                    .onSizeChanged { previewSize = it }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { off ->
                                val w = previewSize.width.toFloat()
                                val h = previewSize.height.toFloat()
                                if (w <= 0 || h <= 0) return@detectDragGestures
                                val nx = off.x / w
                                val ny = off.y / h
                                // pick the topmost sticker whose box contains the touch
                                selectedId = stickers.lastOrNull { s ->
                                    val halfX = s.scale / 2f
                                    val halfY = s.scale * (ThumbnailRenderer.W.toFloat() / ThumbnailRenderer.H) / 2f
                                    abs(nx - s.cx) <= halfX && abs(ny - s.cy) <= halfY
                                }?.id
                            },
                            onDrag = { change, delta ->
                                change.consume()
                                val w = previewSize.width.toFloat()
                                val h = previewSize.height.toFloat()
                                if (w <= 0 || h <= 0) return@detectDragGestures
                                updateSelected {
                                    it.copy(
                                        cx = (it.cx + delta.x / w).coerceIn(0f, 1f),
                                        cy = (it.cy + delta.y / h).coerceIn(0f, 1f)
                                    )
                                }
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                val preview = rendered
                if (preview != null) {
                    Image(
                        bitmap = preview.asImageBitmap(),
                        contentDescription = "thumbnail preview",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text("No photo yet", color = MaterialTheme.colorScheme.outline)
                }
              }
            }

            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Describe it (e.g. moody solo wild camp, title \"VANISHED 3 DAYS\")") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { generate(true) },
                    enabled = !busy,
                    modifier = Modifier.weight(1f)
                ) { Text(if (modelReady) "Suggest (AI)" else "Suggest (template)") }
                OutlinedButton(
                    onClick = { generate(false) },
                    enabled = !busy,
                    modifier = Modifier.weight(1f)
                ) { Text("Template") }
            }

            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(status, style = MaterialTheme.typography.bodySmall)

            // Sticker palette (offline; tap to add, then drag on preview)
            if (sourceBitmap != null) {
                HorizontalDivider()
                Text("Stickers", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Stickers.vectors.forEach { (name, res) ->
                        OutlinedButton(onClick = { addSticker(StickerKind.Vector(res)) }) {
                            Image(
                                painter = painterResource(res),
                                contentDescription = name,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(Modifier.size(6.dp))
                            Text(name)
                        }
                    }
                    OutlinedButton(onClick = { addSticker(StickerKind.Subscribe) }) {
                        Text("Subscribe")
                    }
                    Stickers.emoji.forEach { e ->
                        OutlinedButton(
                            onClick = { addSticker(StickerKind.Emoji(e)) },
                            contentPadding = PaddingValues(horizontal = 10.dp)
                        ) { Text(e, style = MaterialTheme.typography.titleLarge) }
                    }
                }
                if (selectedId != null) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Selected:")
                        OutlinedButton(onClick = {
                            updateSelected { it.copy(scale = (it.scale - 0.03f).coerceAtLeast(0.05f)) }
                        }) { Text("–") }
                        OutlinedButton(onClick = {
                            updateSelected { it.copy(scale = (it.scale + 0.03f).coerceAtMost(0.9f)) }
                        }) { Text("+") }
                        OutlinedButton(onClick = {
                            val id = selectedId
                            stickers = stickers.filterNot { it.id == id }
                            selectedId = null
                            rerender()
                        }) { Text("Delete") }
                    }
                }
            }

            // Manual tweak controls
            spec?.let { sp ->
                HorizontalDivider()
                Text("Tweak", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = sp.title,
                    onValueChange = { spec = sp.copy(title = it); rerender() },
                    label = { Text("Title") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = sp.subtitle,
                    onValueChange = { spec = sp.copy(subtitle = it); rerender() },
                    label = { Text("Subtitle (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                PositionPicker(sp.position) { spec = sp.copy(position = it); rerender() }
                SwatchRow("Title colour", titleSwatches) { spec = sp.copy(titleColor = it); rerender() }
            }

            // Export — available once a photo is loaded (title optional)
            if (rendered != null) {
                Button(
                    onClick = {
                        val bmp = rendered ?: return@Button
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { exportToGallery(context, bmp) }
                            status = if (ok) "Saved to Pictures/ThumbForge." else "Export failed."
                            Toast.makeText(context, status, Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Export 1280×720 PNG") }
            }
        }
    }

    if (showSettings) {
        ModelDialog(
            settings = settings,
            modelMgr = modelMgr,
            onReadyChange = { modelReady = it },
            onDismiss = { showSettings = false }
        )
    }
}

private val titleSwatches = listOf(
    0xFFFFEC3D.toInt(), 0xFFFFFFFF.toInt(), 0xFFFF3D3D.toInt(),
    0xFFB6FF3D.toInt(), 0xFF3DC6FF.toInt(), 0xFFFF8A3D.toInt()
)

@Composable
private fun SwatchRow(label: String, colors: List<Int>, onPick: (Int) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            colors.forEach { c ->
                Surface(
                    color = androidx.compose.ui.graphics.Color(c),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(6.dp))
                ) { Box(Modifier.fillMaxSize().clickable { onPick(c) }) }
            }
        }
    }
}

@Composable
private fun PositionPicker(current: Position, onPick: (Position) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text("Position: ${current.id}") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Position.entries.forEach { p ->
                DropdownMenuItem(text = { Text(p.id) }, onClick = { onPick(p); open = false })
            }
        }
    }
}

@Composable
private fun ModelDialog(
    settings: Settings,
    modelMgr: ModelManager,
    onReadyChange: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = (context as ComponentActivity).lifecycleScope
    var url by remember { mutableStateOf(settings.modelUrl) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var msg by remember {
        mutableStateOf(if (modelMgr.isPresent()) "Model installed (offline ready)." else "No model yet.")
    }

    // Pick a .task already on the device (e.g. in Downloads) — no re-download.
    val filePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        progress = -1f
        msg = "Importing local model…"
        scope.launch {
            val res = modelMgr.importFromFile(uri)
            progress = null
            if (res.isSuccess) {
                msg = "Model installed from file (offline ready)."
                onReadyChange(true)
            } else {
                msg = "Import failed: ${res.exceptionOrNull()?.message}"
                onReadyChange(modelMgr.isPresent())
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                settings.modelUrl = url
                if (url.isBlank()) { msg = "Enter a model URL first."; return@TextButton }
                progress = 0f
                msg = "Downloading…"
                scope.launch {
                    val res = modelMgr.download(url) { p -> progress = p }
                    progress = null
                    if (res.isSuccess) {
                        msg = "Model installed (offline ready)."
                        onReadyChange(true)
                    } else {
                        msg = "Download failed: ${res.exceptionOrNull()?.message}"
                        onReadyChange(modelMgr.isPresent())
                    }
                }
            }) { Text("Download") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("On-device model") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("A small instruct model (.task) runs on this tablet. Default is Qwen2.5-1.5B (no login, ~1.6 GB) — or paste any MediaPipe .task URL. Downloads once, then works offline.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedButton(
                    onClick = { filePicker.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Pick local .task file (no download)") }
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("…or model .task URL") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                progress?.let { p ->
                    if (p < 0) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                    Text(if (p < 0) "downloading…" else "${(p * 100).toInt()}%")
                }
                Text(msg, style = MaterialTheme.typography.bodySmall)
                if (modelMgr.isPresent()) {
                    TextButton(onClick = {
                        modelMgr.delete(); onReadyChange(false); msg = "Model deleted."
                    }) { Text("Delete model") }
                }
            }
        }
    )
}

/* ---- helpers ---- */

@Composable
private fun rememberLauncherForPicker(onResult: (Uri?) -> Unit) =
    androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { onResult(it) }

/**
 * Robust decode for any picked image. Handles Huawei/HEIC + huge sensor photos:
 *  1) ImageDecoder, software allocator, downsample to <=2048px (avoids OOM).
 *  2) Fallback to BitmapFactory stream decode with inSampleSize.
 * Catches Throwable (incl. OutOfMemoryError). Reports the failure reason via [onError].
 */
private const val MAX_DIM = 2048

private fun decodeSoftware(
    context: android.content.Context,
    uri: Uri,
    onError: (String) -> Unit = {}
): Bitmap? {
    // 1) ImageDecoder with downscale
    try {
        val src = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > MAX_DIM) {
                val k = MAX_DIM.toFloat() / longest
                decoder.setTargetSize(
                    (info.size.width * k).toInt().coerceAtLeast(1),
                    (info.size.height * k).toInt().coerceAtLeast(1)
                )
            }
        }
    } catch (e: Throwable) {
        onError("decoder: ${e.javaClass.simpleName} ${e.message ?: ""}".trim())
    }
    // 2) BitmapFactory stream fallback (covers codecs ImageDecoder rejected, plus OOM via sampling)
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        var sample = 1
        while (bounds.outWidth / sample > MAX_DIM || bounds.outHeight / sample > MAX_DIM) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: run { onError("no stream"); null }
    } catch (e: Throwable) {
        onError("bitmapfactory: ${e.javaClass.simpleName} ${e.message ?: ""}".trim())
        null
    }
}

private fun exportToGallery(context: android.content.Context, bmp: Bitmap): Boolean = try {
    val name = "thumb_${System.currentTimeMillis()}.png"
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/ThumbForge")
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    if (uri == null) false else {
        resolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        true
    }
} catch (_: Exception) { false }
