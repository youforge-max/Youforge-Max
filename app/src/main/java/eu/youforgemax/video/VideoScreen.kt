package eu.youforgemax.video

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Hosted by the app's root MainActivity (see YouforgeMaxApp). This file provides
// the Video Normalizer screen only.
@Composable
fun VideoNormalizerScreen() {
        val ctx = LocalContext.current
        val scope = (ctx as ComponentActivity).lifecycleScope
        val ui = remember { UiState() }
        val repo = remember { PresetRepo(ctx) }

        var inputUri by remember { mutableStateOf<Uri?>(null) }
        var inputName by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var phase by remember { mutableStateOf("") }
        var frac by remember { mutableFloatStateOf(0f) }
        var status by remember { mutableStateOf<String?>(null) }
        var linkBands by remember { mutableStateOf(false) }

        var preview by remember { mutableStateOf<PreviewPlayer?>(null) }
        var previewLoading by remember { mutableStateOf(false) }
        var previewOn by remember { mutableStateOf(false) }
        var durationSec by remember { mutableIntStateOf(0) }
        var posSec by remember { mutableFloatStateOf(0f) }

        // Reopen on the last-used profile (autosaved working settings).
        LaunchedEffect(Unit) { repo.loadLast(ui) }

        // Normalize runs in a foreground service (survives backgrounding, YouCut-style); the
        // UI observes its process-wide progress so it reattaches if the screen is left/re-entered.
        val normState by NormalizeJobs.state.collectAsState()
        LaunchedEffect(normState) {
            busy = normState.running
            phase = normState.phase
            frac = normState.frac
            if (!normState.running && normState.finishedAt != 0L) status = normState.message
        }

        fun stopPreview() { preview?.stop(); previewOn = false }

        /** (Re)load the 60 s preview window starting at [startSec] and play it. */
        fun loadAndPlay(startSec: Int) {
            val src = inputUri ?: return
            stopPreview(); preview = null
            previewLoading = true; status = null
            scope.launch {
                val pb = withContext(Dispatchers.Default) {
                    runCatching { MediaProcessor.decodeToBuffer(ctx, src, startSec, 60) }
                }
                previewLoading = false
                pb.onSuccess { b ->
                    if (b == null) status = "Error: no audio to preview"
                    else { val pl = PreviewPlayer(b) { ui.snapshot() }; preview = pl; pl.start(); previewOn = true }
                }.onFailure { status = "Error: ${it.message}" }
            }
        }

        // Drop any loaded preview when the source changes; probe duration.
        LaunchedEffect(inputUri) {
            stopPreview(); preview = null; posSec = 0f; durationSec = 0
            val src = inputUri
            if (src != null) {
                val ms = withContext(Dispatchers.Default) { MediaProcessor.durationMs(ctx, src) }
                durationSec = (ms / 1000).toInt()
            }
        }
        DisposableEffect(Unit) { onDispose { preview?.stop(); repo.saveLast(ui) } }

        val pickInput = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) {
                inputUri = uri
                inputName = queryName(ctx, uri)
                status = null
            }
        }

        // The normalize runs in a foreground service whose progress notification is runtime-
        // permissioned on Android 13+. Without the grant it still runs, but the notification is
        // silently hidden — request it (fire-and-forget) before kicking the job off.
        val notifPerm = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { }
        fun requestNotifIfNeeded() {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
                ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) notifPerm.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        val createOutput = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("video/mp4")
        ) { outUri ->
            val src = inputUri
            if (outUri != null && src != null) {
                stopPreview()
                status = null
                // Persist the working profile and hand the job to the foreground service so it
                // keeps rendering if the app is backgrounded; UI progress comes from NormalizeJobs.
                requestNotifIfNeeded()
                repo.saveLast(ui)
                NormalizeService.start(ctx, src, outUri, ui.snapshot(), suggestedName(inputName))
            }
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("5-Band Video Normalizer", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                "Offline 5-band compressor + limiter for video audio. Same DSP as the live app.",
                fontSize = 12.sp, color = Color.Gray
            )

            // ---- file pick + quick preset (top row) ----
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Card(Modifier.weight(1f)) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Source video", fontWeight = FontWeight.SemiBold)
                    Text(if (inputName.isEmpty()) "(none selected)" else inputName,
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    Button(onClick = { pickInput.launch(arrayOf("video/*")) }, enabled = !busy) {
                        Text("Pick video…")
                    }
                } }
                Card(Modifier.weight(1f)) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Preset", fontWeight = FontWeight.SemiBold)
                    PresetPicker(repo, ui, enabled = !busy)
                } }
            }

            // ---- preview ----
            Card { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Preview", fontWeight = FontWeight.SemiBold)
                Text("Loops a 60 s window through the same DSP. Adjust any setting while it plays — changes are audible live.",
                    fontSize = 12.sp, color = Color.Gray)

                if (durationSec > 1) {
                    val maxStart = (durationSec - 1).coerceAtLeast(0)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Start", fontSize = 13.sp)
                        Text("${fmtTime(posSec.toInt())} / ${fmtTime(durationSec)}",
                            fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                    }
                    Slider(
                        value = posSec.coerceIn(0f, maxStart.toFloat()),
                        onValueChange = { posSec = it },
                        valueRange = 0f..maxStart.toFloat(),
                        enabled = inputUri != null && !busy && !previewLoading,
                        // Reload at the new position only when already previewing.
                        onValueChangeFinished = { if (previewOn) loadAndPlay(posSec.toInt()) }
                    )
                }

                Button(
                    enabled = inputUri != null && !busy && !previewLoading,
                    onClick = { if (previewOn) stopPreview() else loadAndPlay(posSec.toInt()) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        when {
                            previewLoading -> "Loading preview…"
                            previewOn -> "■  Stop preview"
                            else -> "▶  Preview from ${fmtTime(posSec.toInt())} (60 s, looped)"
                        }
                    )
                }
            } }

            // ---- normalize ----
            Card { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ToggleRow("Loudness normalize", ui.normalizeOn) { ui.normalizeOn = it }
                SliderRow("Target", ui.targetLufs, -30f, -8f, "LUFS", ui.normalizeOn) { ui.targetLufs = it }
                SliderRow("Input gain", ui.inputGain, -24f, 24f, "dB", true) { ui.inputGain = it }
                ToggleRow("Master enable", ui.masterOn) { ui.masterOn = it }
            } }

            // ---- bands ----
            Card { Column(Modifier.padding(12.dp)) {
                ToggleRow("Link attack/release across bands", linkBands) { linkBands = it }
                Text("When on, changing a band's Attack (or Release) applies it to all 5 bands. " +
                    "Attack and Release stay independent; the output limiter is never affected.",
                    fontSize = 12.sp, color = Color.Gray)
            } }
            for (b in 0 until NUM_BANDS) {
                BandCard(b, ui, linkBands)
            }

            // ---- limiter ----
            Card { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ToggleRow("Output limiter", ui.limOn) { ui.limOn = it }
                SliderRow("Ceiling", ui.limThr, -6f, 0f, "dB", ui.limOn) { ui.limThr = it }
                SliderRow("Ratio", ui.limRatio, 2f, 20f, ":1", ui.limOn) { ui.limRatio = it }
                SliderRow("Attack", ui.limAtk, 0.1f, 20f, "ms", ui.limOn) { ui.limAtk = it }
                SliderRow("Release", ui.limRel, 10f, 500f, "ms", ui.limOn) { ui.limRel = it }
            } }

            // ---- process ----
            Button(
                onClick = { createOutput.launch(suggestedName(inputName)) },
                enabled = inputUri != null && !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (busy) "Working…" else "Normalize & Save…") }

            if (busy) {
                LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("$phase  ${(frac * 100).toInt()}%", fontSize = 12.sp, color = Color.Gray)
                    OutlinedButton(onClick = { NormalizeService.cancel(ctx) }) { Text("Cancel") }
                }
            }
            status?.let { Text(it, color = if (it.startsWith("Error")) Color(0xFFFF6B6B) else Color(0xFF6BCB77)) }

            Spacer(Modifier.height(24.dp))
        }
    }

    @Composable
    private fun BandCard(b: Int, ui: UiState, linkBands: Boolean) {
        val lo = if (b == 0) 20f else DEFAULT_CUTOFFS[b - 1]
        val hi = DEFAULT_CUTOFFS[b]
        Card { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ToggleRow("Band ${b + 1}   ${fmtHz(lo)}–${fmtHz(hi)}", ui.bandOn[b]) { ui.bandOn[b] = it }
            val on = ui.bandOn[b]
            SliderRow("Threshold", ui.threshold[b], -60f, 0f, "dB", on) { ui.threshold[b] = it }
            SliderRow("Ratio", ui.ratio[b], 1f, 20f, ":1", on) { ui.ratio[b] = it }
            SliderRow("Knee", ui.knee[b], 0f, 24f, "dB", on) { ui.knee[b] = it }
            // When linked, Attack/Release write all bands at once (limiter untouched, the two
            // controls stay independent of each other).
            SliderRow("Attack", ui.attack[b], 0.5f, 200f, "ms", on) {
                if (linkBands) for (i in 0 until NUM_BANDS) ui.attack[i] = it else ui.attack[b] = it
            }
            SliderRow("Release", ui.release[b], 10f, 2000f, "ms", on) {
                if (linkBands) for (i in 0 until NUM_BANDS) ui.release[i] = it else ui.release[b] = it
            }
            SliderRow("Makeup", ui.makeup[b], 0f, 24f, "dB", on) { ui.makeup[b] = it }
        } }
    }

    /**
     * Compact, self-contained preset control next to the source picker: apply a saved
     * preset, save the current settings as a new one, or delete one — no separate bar.
     */
    @Composable
    private fun PresetPicker(repo: PresetRepo, ui: UiState, enabled: Boolean) {
        var expanded by remember { mutableStateOf(false) }
        var current by remember { mutableStateOf("(custom)") }
        var names by remember { mutableStateOf(repo.names()) }
        var showSave by remember { mutableStateOf(false) }
        var newName by remember { mutableStateOf("") }

        Box {
            OutlinedButton(
                onClick = { names = repo.names(); expanded = true }, enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            ) { Text(current, maxLines = 1) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text("＋ Save current…") },
                    onClick = { expanded = false; newName = ""; showSave = true }
                )
                if (names.isNotEmpty()) HorizontalDivider()
                names.forEach { n ->
                    DropdownMenuItem(
                        text = { Text(n, maxLines = 1) },
                        onClick = { repo.load(n, ui); current = n; expanded = false },
                        trailingIcon = {
                            Text("✕", color = Color.Gray, modifier = Modifier.clickable {
                                repo.delete(n); names = repo.names()
                                if (current == n) current = "(custom)"
                            })
                        }
                    )
                }
            }
        }

        if (showSave) {
            AlertDialog(
                onDismissRequest = { showSave = false },
                title = { Text("Save preset") },
                text = {
                    OutlinedTextField(
                        value = newName, onValueChange = { newName = it },
                        label = { Text("name") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = newName.isNotBlank(),
                        onClick = {
                            repo.save(newName.trim(), ui); current = newName.trim()
                            names = repo.names(); showSave = false
                        }
                    ) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { showSave = false }) { Text("Cancel") } }
            )
        }
    }

    @Composable
    private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontWeight = FontWeight.SemiBold)
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }

    @Composable
    private fun SliderRow(
        label: String, value: Float, min: Float, max: Float, unit: String,
        enabled: Boolean, onChange: (Float) -> Unit
    ) {
        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label, fontSize = 13.sp)
                Text("${fmtVal(value)} $unit", fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                    color = if (enabled) Color.White else Color.Gray)
            }
            Slider(value = value, onValueChange = onChange, valueRange = min..max, enabled = enabled)
        }
    }

    private fun fmtVal(v: Float): String =
        if (kotlin.math.abs(v) >= 100f || v == v.toInt().toFloat()) v.toInt().toString()
        else "%.1f".format(v)

    private fun fmtHz(hz: Float): String =
        if (hz >= 1000f) "%.1fk".format(hz / 1000f) else "${hz.toInt()}"

    private fun fmtTime(sec: Int): String = "%d:%02d".format(sec / 60, sec % 60)

    private fun suggestedName(input: String): String {
        val base = input.substringBeforeLast('.', input).ifBlank { "video" }
        return "${base}_normalized.mp4"
    }

    private fun queryName(ctx: android.content.Context, uri: Uri): String {
        return runCatching {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) c.getString(i) else uri.lastPathSegment ?: "video"
            } ?: uri.lastPathSegment ?: "video"
        }.getOrDefault(uri.lastPathSegment ?: "video")
    }
