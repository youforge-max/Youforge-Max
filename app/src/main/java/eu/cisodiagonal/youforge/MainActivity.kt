package eu.youforgemax.youforge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.youforgemax.youforge.editor.VideoEditorScreen
import eu.youforgemax.youforge.thumb.ThumbnailScreen
import eu.youforgemax.youforge.video.VideoNormalizerScreen

/** Single launcher activity hosting both tools behind a home screen. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    YouForgeApp()
                }
            }
        }
    }
}

private enum class Tool { Home, Video, Thumb, Editor }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouForgeApp() {
    var tool by remember { mutableStateOf(Tool.Home) }
    // Once the Thumbnail Maker is opened, keep it composed so an in-progress edit
    // (photo, title, stickers, model state) survives bouncing back to Home.
    var thumbVisited by remember { mutableStateOf(false) }
    if (tool == Tool.Thumb) thumbVisited = true

    BackHandler(enabled = tool != Tool.Home) { tool = Tool.Home }

    Box(Modifier.fillMaxSize()) {
        when (tool) {
            Tool.Home -> HomeScreen(onOpen = { tool = it })
            Tool.Thumb -> Unit  // rendered by the keep-alive block below
            Tool.Video -> Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("Video Normalizer") },
                        navigationIcon = { TextButton(onClick = { tool = Tool.Home }) { Text("←") } }
                    )
                }
            ) { pad -> Box(Modifier.padding(pad)) { VideoNormalizerScreen() } }
            Tool.Editor -> Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("Video Editor") },
                        navigationIcon = { TextButton(onClick = { tool = Tool.Home }) { Text("←") } }
                    )
                }
            ) { pad -> Box(Modifier.padding(pad)) { VideoEditorScreen() } }
        }

        // Thumbnail Maker is never removed once visited — fills the screen when
        // active, collapses to zero size (state retained) when another screen shows.
        if (thumbVisited) {
            Box(if (tool == Tool.Thumb) Modifier.fillMaxSize() else Modifier.size(0.dp)) {
                ThumbnailScreen(onBack = { tool = Tool.Home })
            }
        }
    }
}

@Composable
private fun HomeScreen(onOpen: (Tool) -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        Text("YouForge Max", fontSize = 34.sp, fontWeight = FontWeight.Black)
        Text("Offline creator toolkit", fontSize = 14.sp, color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(8.dp))

        ToolCard(
            title = "Thumbnail Maker",
            blurb = "Photo + description → 1280×720 thumbnail. On-device AI title, stickers, export.",
            onClick = { onOpen(Tool.Thumb) }
        )
        ToolCard(
            title = "Video Normalizer",
            blurb = "Offline 5-band compressor + limiter for video audio. Live preview, presets.",
            onClick = { onOpen(Tool.Video) }
        )
        ToolCard(
            title = "Video Editor (beta)",
            blurb = "Import clips, trim, and merge into one MP4. On-device Media3, no watermark.",
            onClick = { onOpen(Tool.Editor) }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolCard(title: String, blurb: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(blurb, fontSize = 13.sp, color = MaterialTheme.colorScheme.outline)
        }
    }
}
