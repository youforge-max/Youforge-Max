package eu.cisodiagonal.youforge

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
import eu.cisodiagonal.youforge.thumb.ThumbnailScreen
import eu.cisodiagonal.youforge.video.VideoNormalizerScreen

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

private enum class Tool { Home, Video, Thumb }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouForgeApp() {
    var tool by remember { mutableStateOf(Tool.Home) }

    BackHandler(enabled = tool != Tool.Home) { tool = Tool.Home }

    when (tool) {
        Tool.Home -> HomeScreen(onOpen = { tool = it })
        Tool.Thumb -> ThumbnailScreen(onBack = { tool = Tool.Home })
        Tool.Video -> Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Video Normalizer") },
                    navigationIcon = { TextButton(onClick = { tool = Tool.Home }) { Text("←") } }
                )
            }
        ) { pad -> Box(Modifier.padding(pad)) { VideoNormalizerScreen() } }
    }
}

@Composable
private fun HomeScreen(onOpen: (Tool) -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        Text("YouForge", fontSize = 34.sp, fontWeight = FontWeight.Black)
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
