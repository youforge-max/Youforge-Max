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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import eu.cisodiagonal.youforge.asr.AudioPcmDecoder
import eu.cisodiagonal.youforge.asr.AudioTranscriber
import eu.cisodiagonal.youforge.asr.VoskModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThumbnailScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = (context as ComponentActivity).lifecycleScope
    val modelMgr = remember { ModelManager(context) }
    val settings = remember { Settings(context) }

    var sourceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var originalBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var rendered by remember { mutableStateOf<Bitmap?>(null) }
    var spec by remember { mutableStateOf<OverlaySpec?>(null) }
    var description by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("build r14 · Pick a photo to start.") }
    var showSettings by remember { mutableStateOf(false) }
    var modelReady by remember { mutableStateOf(modelMgr.isPresent()) }
    var stickers by remember { mutableStateOf<List<Sticker>>(emptyList()) }
    var selectedId by remember { mutableStateOf<Long?>(null) }
    var titleSelected by remember { mutableStateOf(false) }
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

    fun updateTitle(transform: (OverlaySpec) -> OverlaySpec) {
        val sp = spec ?: return
        spec = transform(sp)
        rerender()
    }

    fun addSticker(kind: StickerKind) {
        if (sourceBitmap == null) { status = "Pick a photo first."; return }
        val s = Sticker(id = Stickers.nextId(), kind = kind, scale = Stickers.defaultScale(kind))
        stickers = stickers + s
        selectedId = s.id
        titleSelected = false
        rerender()
        status = "Sticker added — drag to move, two fingers to rotate/scale."
    }

    val picker = rememberLauncherForPicker { uri ->
        if (uri == null) return@rememberLauncherForPicker
        scope.launch {
            status = "Loading photo…"
            var why = ""
            val bmp = withContext(Dispatchers.IO) { decodeSoftware(context, uri) { why = it } }
            if (bmp == null) { status = "Could not read that image. $why"; return@launch }
            sourceBitmap = bmp
            originalBitmap = bmp
            spec = null
            stickers = emptyList()
            selectedId = null
            titleSelected = false
            rendered = ThumbnailRenderer.render(context, bmp, null, emptyList())
            status = "Describe your thumbnail, then tap Suggest."
        }
    }

    val voskMgr = remember { VoskModelManager(context) }

    // "Pick a still from a video" — extract ~10 candidate frames the user can choose from
    // (or shuffle for 10 new random ones). The chosen still becomes the thumbnail photo.
    var stillsUri by remember { mutableStateOf<Uri?>(null) }
    var stillsDurMs by remember { mutableStateOf(0L) }
    var stillCandidates by remember { mutableStateOf<List<VideoStills.Still>>(emptyList()) }
    var showStills by remember { mutableStateOf(false) }
    var stillCount by remember { mutableStateOf(10) }
    // Timestamp of the still currently used as the background (to mark it in the grid).
    var chosenStillTimeUs by remember { mutableStateOf<Long?>(null) }

    fun loadStills(random: Boolean) {
        val uri = stillsUri ?: return
        val total = stillCount
        scope.launch {
            busy = true
            stillCandidates = emptyList()
            showStills = true
            status = "Grabbing stills 0/$total…"
            try {
                VideoStills.extractStreaming(context, uri, stillsDurMs, total, random) { i, tot, still ->
                    if (still != null) stillCandidates = stillCandidates + still
                    status = "Grabbing stills ${i + 1}/$tot…"
                }
                if (stillCandidates.isEmpty()) {
                    status = "Couldn't read frames from that video."; showStills = false
                } else {
                    status = "Pick a still, or shuffle for new ones."
                }
            } finally { busy = false }
        }
    }

    val stillsPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            status = "Reading video…"
            try {
                val dur = withContext(Dispatchers.IO) { VideoStills.durationMs(context, uri) }
                if (dur <= 0L) { status = "Couldn't read that video."; return@launch }
                stillsUri = uri
                stillsDurMs = dur
            } finally { busy = false }
            loadStills(random = false)
        }
    }

    fun chooseStill(s: VideoStills.Still) {
        scope.launch {
            busy = true
            status = "Loading still…"
            try {
                val full = withContext(Dispatchers.Default) {
                    VideoStills.frameAt(
                        context, stillsUri ?: return@withContext null,
                        s.timeUs, ThumbnailRenderer.W, ThumbnailRenderer.H
                    )
                } ?: s.bmp
                chosenStillTimeUs = s.timeUs
                sourceBitmap = full
                originalBitmap = full
                spec = null
                stickers = emptyList()
                selectedId = null
                titleSelected = false
                rendered = ThumbnailRenderer.render(context, full, null, emptyList())
                showStills = false
                status = "Still chosen. Describe your thumbnail, then tap Suggest."
            } finally { busy = false }
        }
    }

    fun generate(useAi: Boolean) {
        if (sourceBitmap == null) { status = "Pick a photo first."; return }
        if (description.isBlank()) { status = "Type a description first."; return }
        scope.launch {
            busy = true
            try {
                val active = modelMgr.activeFile()
                val sp0 = if (useAi && active != null) {
                    status = "AI thinking (on-device)…"
                    val engine: AiProvider = when (modelMgr.formatOfFile(active)) {
                        ModelFormat.GGUF -> LlamaCppEngine(active)
                        else -> OnDeviceLlm(context, active)
                    }
                    engine.suggest(description)
                } else {
                    if (useAi) status = "No model yet — used offline template."
                    TemplateProvider.suggest(description)
                }
                // Brand kit (if saved) overrides the generated style for consistency.
                spec = settings.brandKit()?.applyTo(sp0) ?: sp0
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

    // Title-from-video: pick a clip, transcribe its speech on-device (Vosk), then
    // hand the transcript to the title generator. Fully offline after the one-time
    // speech-model download.
    val videoPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            var proceed: Boolean
            try {
                if (!voskMgr.isReady()) {
                    status = "Downloading speech model (~40 MB, once)…"
                    val r = voskMgr.ensure { p ->
                        status = if (p < 0) "Downloading speech model…"
                        else "Downloading speech model… ${(p * 100).toInt()}%"
                    }
                    if (r.isFailure) {
                        status = "Speech model failed: ${r.exceptionOrNull()?.message}"; return@launch
                    }
                }
                status = "Listening to the video…"
                val pcm = withContext(Dispatchers.Default) {
                    AudioPcmDecoder.decodeTo16kMono(context, uri)
                }
                if (pcm.isEmpty()) { status = "No audio found in that video."; return@launch }
                val dir = voskMgr.modelDir()?.absolutePath
                    ?: run { status = "Speech model missing."; return@launch }
                status = "Transcribing on device…"
                val text = withContext(Dispatchers.Default) { AudioTranscriber.transcribe(dir, pcm) }
                if (text.isBlank()) { status = "Couldn't make out any speech."; return@launch }
                description = text.take(400)
                status = "Heard: \"${text.take(80)}…\" — generating a title."
                proceed = true
            } finally {
                busy = false
            }
            if (proceed) generate(true)
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
                    // Tap to select the topmost element under the touch (sticker first,
                    // then the title block); tap empty space to deselect.
                    .pointerInput(stickers, spec) {
                        detectTapGestures { off ->
                            val w = previewSize.width.toFloat()
                            val h = previewSize.height.toFloat()
                            if (w <= 0 || h <= 0) return@detectTapGestures
                            val nx = off.x / w
                            val ny = off.y / h
                            val hit = stickers.lastOrNull { s ->
                                val halfX = s.scale / 2f
                                val halfY = s.scale * (ThumbnailRenderer.W.toFloat() / ThumbnailRenderer.H) / 2f
                                abs(nx - s.cx) <= halfX && abs(ny - s.cy) <= halfY
                            }
                            if (hit != null) {
                                selectedId = hit.id; titleSelected = false
                            } else {
                                val tb = spec?.let { ThumbnailRenderer.titleBoundsNorm(it) }
                                if (tb != null && nx in tb.left..tb.right && ny in tb.top..tb.bottom) {
                                    titleSelected = true; selectedId = null
                                } else {
                                    selectedId = null; titleSelected = false
                                }
                            }
                        }
                    }
                    // Drag = move, two fingers = rotate + scale, on the selected element.
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, rot ->
                            val w = previewSize.width.toFloat()
                            val h = previewSize.height.toFloat()
                            if (w <= 0 || h <= 0) return@detectTransformGestures
                            if (titleSelected) {
                                updateTitle { sp ->
                                    val ix = sp.freeX ?: sp.position.approxCenter().first
                                    val iy = sp.freeY ?: sp.position.approxCenter().second
                                    sp.copy(
                                        freeX = (ix + pan.x / w).coerceIn(0f, 1f),
                                        freeY = (iy + pan.y / h).coerceIn(0f, 1f),
                                        rotation = sp.rotation + rot,
                                        titleScale = (sp.titleScale * zoom).coerceIn(0.3f, 3f)
                                    )
                                }
                            } else if (selectedId != null) {
                                updateSelected {
                                    it.copy(
                                        cx = (it.cx + pan.x / w).coerceIn(0f, 1f),
                                        cy = (it.cy + pan.y / h).coerceIn(0f, 1f),
                                        scale = (it.scale * zoom).coerceIn(0.05f, 1.2f),
                                        rotation = it.rotation + rot
                                    )
                                }
                            }
                        }
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
                label = { Text("Describe it (e.g. epic mountain hike, title \"WE MADE IT\")") },
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

            // Pick a still frame from a video as the thumbnail background.
            OutlinedButton(
                onClick = {
                    stillsPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                    )
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text("🎞  Pick a still from video") }

            // Generate a title from a video's speech (on-device transcription).
            OutlinedButton(
                onClick = {
                    videoPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                    )
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text("🎙  Title from video (on-device)") }

            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(status, style = MaterialTheme.typography.bodySmall)

            // Still picker — grid of candidate frames; tap one to use it, or shuffle 10 new.
            if (showStills) AlertDialog(
                onDismissRequest = { showStills = false },
                confirmButton = {
                    TextButton(onClick = { loadStills(random = true) }, enabled = !busy) {
                        Text("🎲  $stillCount new")
                    }
                },
                dismissButton = { TextButton(onClick = { showStills = false }) { Text("Close") } },
                title = { Text("Pick a still") },
                text = {
                    Column(
                        Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // How many stills to offer.
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Count:", style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.align(Alignment.CenterVertically))
                            listOf(6, 10, 16).forEach { c ->
                                FilterChip(
                                    selected = stillCount == c,
                                    onClick = { if (!busy && c != stillCount) { stillCount = c; loadStills(random = false) } },
                                    label = { Text("$c") },
                                    enabled = !busy
                                )
                            }
                        }
                        stillCandidates.chunked(2).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                row.forEach { s ->
                                    val isChosen = s.timeUs == chosenStillTimeUs
                                    Column(
                                        Modifier.weight(1f),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Image(
                                            bitmap = s.bmp.asImageBitmap(),
                                            contentDescription = "still",
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .aspectRatio(16f / 9f)
                                                .clip(RoundedCornerShape(8.dp))
                                                .then(
                                                    if (isChosen) Modifier.border(
                                                        BorderStroke(3.dp, MaterialTheme.colorScheme.primary),
                                                        RoundedCornerShape(8.dp)
                                                    ) else Modifier
                                                )
                                                .clickable(enabled = !busy) { chooseStill(s) }
                                        )
                                        Text(fmtTimecode(s.timeUs), style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            )

            // Photo tools — on-device MediaPipe (background removal + face framing).
            if (sourceBitmap != null) {
                HorizontalDivider()
                Text("Photo (on-device AI)", style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold)

                fun applyPhotoOp(label: String, op: (Bitmap) -> Bitmap?) {
                    val src = sourceBitmap ?: return
                    scope.launch {
                        busy = true; status = "$label…"
                        val res = withContext(Dispatchers.Default) { op(src) }
                        busy = false
                        if (res == null) { status = "$label: couldn't process (no subject/face found, or on-device AI unavailable on this device)."; return@launch }
                        sourceBitmap = res
                        rerender()
                        status = "$label done."
                    }
                }

                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(enabled = !busy, onClick = {
                        applyPhotoOp("Cut out (dark bg)") {
                            VisionTools.removeBackground(context, it, VisionTools.BackgroundStyle.DARK)
                        }
                    }) { Text("Cut out · dark") }
                    OutlinedButton(enabled = !busy, onClick = {
                        applyPhotoOp("Cut out (blur bg)") {
                            VisionTools.removeBackground(context, it, VisionTools.BackgroundStyle.BLUR)
                        }
                    }) { Text("Cut out · blur") }
                    OutlinedButton(enabled = !busy, onClick = {
                        applyPhotoOp("Auto-frame face") { VisionTools.autoCropToFace(context, it) }
                    }) { Text("Auto-frame face") }
                    OutlinedButton(enabled = !busy && sourceBitmap !== originalBitmap, onClick = {
                        originalBitmap?.let { sourceBitmap = it; rerender(); status = "Photo restored." }
                    }) { Text("Restore photo") }
                }
            }

            // Sticker palette (offline; tap to add, then drag on preview)
            if (sourceBitmap != null) {
                HorizontalDivider()
                Text("Stickers", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)

                StickerGroupLabel("Shapes")
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
                }

                Stickers.emojiGroups.forEach { (label, glyphs) ->
                    StickerGroupLabel(label)
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        glyphs.forEach { e ->
                            OutlinedButton(
                                onClick = { addSticker(StickerKind.Emoji(e)) },
                                contentPadding = PaddingValues(horizontal = 10.dp)
                            ) { Text(e, style = MaterialTheme.typography.titleLarge) }
                        }
                    }
                }
                if (selectedId != null) {
                    val sel = stickers.firstOrNull { it.id == selectedId }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Sticker:")
                        OutlinedButton(onClick = {
                            updateSelected { it.copy(scale = (it.scale - 0.03f).coerceAtLeast(0.05f)) }
                        }) { Text("–") }
                        OutlinedButton(onClick = {
                            updateSelected { it.copy(scale = (it.scale + 0.03f).coerceAtMost(1.2f)) }
                        }) { Text("+") }
                        OutlinedButton(onClick = {
                            updateSelected { it.copy(rotation = 0f) }
                        }) { Text("0°") }
                        OutlinedButton(onClick = {
                            val id = selectedId
                            stickers = stickers.filterNot { it.id == id }
                            selectedId = null
                            rerender()
                        }) { Text("Delete") }
                    }
                    RotationSlider(sel?.rotation ?: 0f) { r ->
                        updateSelected { it.copy(rotation = r) }
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
                    label = { Text("Title (Enter = line break)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    minLines = 1
                )
                OutlinedTextField(
                    value = sp.subtitle,
                    onValueChange = { spec = sp.copy(subtitle = it); rerender() },
                    label = { Text("Subtitle (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                // Free move/rotate/scale of the title (also draggable on the preview).
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (titleSelected) "Title ✓" else "Title:")
                    OutlinedButton(onClick = {
                        spec = sp.copy(titleScale = (sp.titleScale - 0.1f).coerceAtLeast(0.3f)); rerender()
                    }) { Text("–") }
                    OutlinedButton(onClick = {
                        spec = sp.copy(titleScale = (sp.titleScale + 0.1f).coerceAtMost(3f)); rerender()
                    }) { Text("+") }
                    OutlinedButton(onClick = {
                        spec = sp.copy(rotation = 0f, titleScale = 1f, freeX = null, freeY = null)
                        titleSelected = false; rerender()
                    }) { Text("Reset") }
                }
                Text(
                    "Tap the title on the preview to grab it — drag to move, two fingers to rotate/scale.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                RotationSlider(sp.rotation) { r -> spec = sp.copy(rotation = r); rerender() }
                EffectStrip(sp.effect, sp.titleColor, sp.glowColor) {
                    spec = sp.copy(effect = it); rerender()
                }
                PositionPicker(sp.position) { spec = sp.copy(position = it); rerender() }
                SwatchRow("Title colour", titleSwatches) { spec = sp.copy(titleColor = it); rerender() }
                if (sp.effect == TextEffect.GLOW || sp.effect == TextEffect.NEON) {
                    SwatchRow("Glow colour", glowSwatches) { spec = sp.copy(glowColor = it); rerender() }
                }

                // One-tap style presets.
                StickerGroupLabel("Style presets")
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Presets.builtin.forEach { p ->
                        OutlinedButton(onClick = { spec = p.applyTo(sp); rerender() }) { Text(p.name) }
                    }
                }

                // Brand kit — save the current style and reuse it on future thumbnails.
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(onClick = {
                        settings.saveBrandKit(sp); status = "Saved as your style."
                    }) { Text("Save as my style") }
                    if (settings.brandKit() != null) {
                        OutlinedButton(onClick = {
                            settings.brandKit()?.let { spec = it.applyTo(sp); rerender() }
                        }) { Text("Use my style") }
                        OutlinedButton(onClick = {
                            settings.clearBrandKit(); status = "Brand style cleared."
                        }) { Text("Clear") }
                    }
                }

                // Legibility check: contrast of the title colour vs the photo behind it.
                sourceBitmap?.let { src ->
                    val bg = remember(sp.title, sp.position, sp.freeX, sp.freeY, sp.titleScale, src) {
                        ThumbnailRenderer.bgColorUnderTitle(src, sp)
                    }
                    val v = Contrast.check(sp.titleColor, bg)
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            (if (v.ok) "✓ " else "⚠ ") + v.advice +
                                "  (" + String.format("%.1f", v.ratio) + ":1)",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (v.ok) MaterialTheme.colorScheme.outline
                            else MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f)
                        )
                        if (!v.ok && v.suggest != null) {
                            OutlinedButton(onClick = {
                                spec = sp.copy(titleColor = v.suggest); rerender()
                            }) { Text("Fix") }
                        }
                    }
                }
            }

            // Export — available once a photo is loaded (title optional)
            if (rendered != null) {
                Button(
                    onClick = {
                        val bmp = rendered ?: return@Button
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { exportToGallery(context, bmp) }
                            status = if (ok) "Saved to Pictures/YouForge." else "Export failed."
                            Toast.makeText(context, status, Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Export 1280×720 PNG") }

                // A/B variants — same photo + stickers, 3 different title styles.
                spec?.let { base ->
                    OutlinedButton(
                        onClick = {
                            val src = sourceBitmap ?: return@OutlinedButton
                            scope.launch {
                                busy = true
                                val variants = Variants.make(base, 3)
                                val ok = withContext(Dispatchers.IO) {
                                    variants.mapIndexed { i, v ->
                                        val bmp = ThumbnailRenderer.render(context, src, v, stickers)
                                        exportToGallery(context, bmp, "ab${i + 1}")
                                    }.all { it }
                                }
                                busy = false
                                status = if (ok) "Saved ${variants.size} A/B variants to Pictures/YouForge."
                                    else "Variant export failed."
                                Toast.makeText(context, status, Toast.LENGTH_SHORT).show()
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Export 3 A/B variants") }
                }
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

/** -180°..180° rotation slider for the selected element. */
@Composable
private fun RotationSlider(value: Float, onChange: (Float) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("⟳ ${value.toInt()}°", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = value.coerceIn(-180f, 180f),
            onValueChange = onChange,
            valueRange = -180f..180f,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun StickerGroupLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.outline
    )
}

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

/** Horizontally-scrolling strip of live effect previews; tap a chip to apply it. */
@Composable
private fun EffectStrip(
    current: TextEffect,
    titleColor: Int,
    glowColor: Int,
    onPick: (TextEffect) -> Unit
) {
    StickerGroupLabel("Effect")
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TextEffect.entries.forEach { e ->
            val chip = remember(e, titleColor, glowColor) {
                ThumbnailRenderer.sampleChip(e, titleColor, glowColor).asImageBitmap()
            }
            val selected = e == current
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .border(
                        BorderStroke(
                            if (selected) 2.dp else 1.dp,
                            if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline
                        ),
                        RoundedCornerShape(8.dp)
                    )
                    .clickable { onPick(e) }
                    .padding(4.dp)
            ) {
                Image(
                    bitmap = chip,
                    contentDescription = e.label,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(width = 92.dp, height = 50.dp)
                        .clip(RoundedCornerShape(6.dp))
                )
                Text(e.label, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private val glowSwatches = listOf(
    0xFFFF2D7A.toInt(), 0xFF00E5FF.toInt(), 0xFF7C4DFF.toInt(),
    0xFF3DFF6E.toInt(), 0xFFFFA63D.toInt(), 0xFFFFFFFF.toInt()
)

@Composable
private fun ModelDialog(
    settings: Settings,
    modelMgr: ModelManager,
    onReadyChange: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = (context as ComponentActivity).lifecycleScope
    // Downloads run in a foreground service (survive backgrounding); this dialog only
    // observes the shared status + drives the buttons.
    val dl by ModelDownloads.state.collectAsState()
    var url by remember { mutableStateOf(settings.modelUrl) }
    var importing by remember { mutableStateOf(false) }   // local .task import (no service)
    var refresh by remember { mutableStateOf(0) }         // bump to recompute installed/active
    var msg by remember {
        mutableStateOf(if (modelMgr.isPresent()) "Model ready (offline)." else "No model yet — tap one below.")
    }

    val busy = dl.running || importing
    val curSlug = if (importing) ModelManager.IMPORTED_SLUG else if (dl.running) dl.slug else ""
    val progress: Float? = when { importing -> -1f; dl.running -> dl.progress; else -> null }

    // Mirror the service's status into the message line, and react when a run ends.
    LaunchedEffect(dl.message) { if (dl.message.isNotBlank()) msg = dl.message }
    LaunchedEffect(dl.finishedAt) {
        if (dl.finishedAt != 0L) { refresh++; onReadyChange(modelMgr.isPresent()) }
    }

    // Progress notification is runtime-permissioned on Android 13+; the service still runs
    // without it (the notification just won't show), so we fire-and-forget the request.
    val notifPerm = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    fun requestNotifIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) notifPerm.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    fun startDownload(
        slug: String, name: String, dlUrl: String,
        sha256: String? = null, format: ModelFormat = ModelFormat.TASK
    ) {
        if (busy) return
        requestNotifIfNeeded()
        ModelDownloadService.one(context, slug, name, dlUrl, sha256, format)
    }

    fun startAll() {
        if (busy) return
        requestNotifIfNeeded()
        ModelDownloadService.all(context)
    }

    // Pick a .task already on the device (e.g. in Downloads) — no re-download.
    val filePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null || busy) return@rememberLauncherForActivityResult
        importing = true; msg = "Importing local model…"
        scope.launch {
            val res = modelMgr.importFromFile(uri)
            importing = false; refresh++
            msg = if (res.isSuccess) "Imported — now active."
            else "Import failed: ${res.exceptionOrNull()?.message}"
            onReadyChange(modelMgr.isPresent())
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        confirmButton = {
            TextButton(onClick = { startAll() }, enabled = !busy) { Text("Download all") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Close") } },
        title = { Text("On-device models") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 500.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                refresh.let { }  // subscribe: recompute rows after installs
                Text("Small instruct models (.task) run on this tablet — tap one to download or to switch the active model. Multiple can be kept side by side. Fully offline once downloaded.",
                    style = MaterialTheme.typography.bodySmall)

                SuggestedModels.all.forEach { m ->
                    val installed = modelMgr.isPresent(m.slug)
                    val active = installed && modelMgr.activeSlug == m.slug
                    val downloading = busy && curSlug == m.slug
                    OutlinedButton(
                        onClick = {
                            if (busy) return@OutlinedButton
                            if (installed) {
                                modelMgr.setActive(m.slug); refresh++
                                msg = "Using ${m.name}."; onReadyChange(true)
                            } else startDownload(m.slug, m.name, m.url, m.sha256, m.format)
                        },
                        enabled = !busy || downloading,
                        modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(
                            if (active) 2.dp else 1.dp,
                            if (active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline
                        )
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${m.name}  ·  ${m.size}",
                                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                Text(
                                    when {
                                        active -> "● active"
                                        installed -> "✓ tap to use"
                                        else -> "↓ tap to get"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (active) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline
                                )
                            }
                            Text(m.note, style = MaterialTheme.typography.bodySmall)
                            if (downloading) {
                                val p = progress
                                if (p == null || p < 0) LinearProgressIndicator(Modifier.fillMaxWidth())
                                else LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                                Text(if (p == null || p < 0) "downloading…" else "${(p * 100).toInt()}%",
                                    style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }

                // Global progress (download-all / import) when not tied to one row.
                if (busy && curSlug !in SuggestedModels.all.map { it.slug }) {
                    val p = progress
                    if (p == null || p < 0) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                }
                Text(msg, style = MaterialTheme.typography.bodySmall)

                HorizontalDivider()
                OutlinedButton(
                    onClick = { filePicker.launch(arrayOf("*/*")) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Pick local .task file (no download)") }
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("…or paste a custom .task URL") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedButton(
                    onClick = {
                        settings.modelUrl = url
                        if (url.isBlank()) { msg = "Enter a URL first." }
                        else {
                            val fmt = if (url.substringBefore('?').endsWith(".gguf", true))
                                ModelFormat.GGUF else ModelFormat.TASK
                            startDownload("custom", "custom model", url, null, fmt)
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Download custom URL") }

                if (modelMgr.installedSlugs().isNotEmpty()) {
                    TextButton(onClick = {
                        modelMgr.deleteAll(); refresh++; onReadyChange(false); msg = "All models deleted."
                    }, enabled = !busy) { Text("Delete all models") }
                }
            }
        }
    )
}

/* ---- helpers ---- */

/** mm:ss for a still's source timestamp (µs). */
private fun fmtTimecode(timeUs: Long): String {
    val s = timeUs / 1_000_000
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun rememberLauncherForPicker(onResult: (Uri?) -> Unit) =
    androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { onResult(it) }

/**
 * Robust decode for any picked image. Handles odd OEM/HEIC + huge sensor photos:
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

private fun exportToGallery(context: android.content.Context, bmp: Bitmap, tag: String = ""): Boolean = try {
    val suffix = if (tag.isNotEmpty()) "_$tag" else ""
    val name = "thumb_${System.currentTimeMillis()}$suffix.png"
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/YouForge")
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    if (uri == null) false else {
        resolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        true
    }
} catch (_: Exception) { false }
