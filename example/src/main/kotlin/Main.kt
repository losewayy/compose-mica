import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import io.github.losewayy.glass.GlassCapsule
import io.github.losewayy.glass.GlassSurface
import io.github.losewayy.glass.GlassTier
import io.github.losewayy.mica.AmbientBackdrop
import io.github.losewayy.mica.MicaWallpaperLayer
import io.github.losewayy.mica.rememberSystemWallpaperSurface
import kotlin.math.roundToInt

private val CardShape = RoundedCornerShape(12.dp)

/**
 * Scene content that sits *under* the glass — it must be inside the
 * `layerBackdrop` recording scope so refraction has signal to bend.
 */
@Composable
private fun BackdropScene(modifier: Modifier = Modifier) {
    val surface = rememberSystemWallpaperSurface()
    val windowState = LocalWindowState.current
    Box(modifier.fillMaxSize()) {
        if (surface != null) {
            // Fake-Mica path: the OS wallpaper, blurred and cropped to wherever
            // this window currently sits on screen.
            val pos = windowState.position
            MicaWallpaperLayer(
                surface = surface,
                windowX = if (pos.isSpecified) pos.x.value else 0f,
                windowY = if (pos.isSpecified) pos.y.value else 0f,
                blurDp = 30f,
                veilAlpha = 0.35f,
            )
        } else {
            // Non-Windows / no wallpaper: ambient drifting gradient.
            AmbientBackdrop()
        }

        // Some content so refraction has signal.
        Column(
            Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(52.dp).clip(CardShape).background(Color(0xFFFF6B6B)))
                Box(Modifier.size(52.dp).clip(CardShape).background(Color(0xFF4ECDC4)))
                Box(Modifier.size(52.dp).clip(CardShape).background(Color(0xFFFFD93D)))
                Box(Modifier.size(52.dp).clip(CardShape).background(Color(0xFF95A5F7)))
            }
            FakeMessages.forEachIndexed { i, line ->
                val isUser = i % 3 == 2
                Box(
                    Modifier
                        .fillMaxWidth(if (isUser) 0.62f else 0.9f)
                        .align(if (isUser) Alignment.End else Alignment.Start)
                        .clip(CardShape)
                        .background(if (isUser) Color(0x334A6FA5) else Color(0x22FFFFFF))
                        .padding(12.dp),
                ) {
                    BasicText(line, style = TextStyle(Color(0xFFE8ECF4), 15.sp))
                }
            }
        }
    }
}

private val FakeMessages = listOf(
    "Quantum computing uses qubits, which can exist in superposition —",
    "measuring one collapses its state instantly.",
    "The quick brown fox jumps over the lazy dog. 0123456789",
    "Liquid glass bends light; acrylic just blurs it.",
    "Drag the glass card — the text underneath is truly refracted.",
    "Chromatic aberration splits the edge into a prism fringe.",
)

@Composable
private fun DraggableGlassCard(backdrop: Backdrop) {
    var dx by remember { mutableFloatStateOf(0f) }
    var dy by remember { mutableFloatStateOf(0f) }
    GlassSurface(
        backdrop = backdrop,
        tier = GlassTier.OVERLAY,
        cornerRadius = 28.dp,
        modifier = Modifier
            .offset { IntOffset(dx.roundToInt(), dy.roundToInt()) }
            .width(340.dp)
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    dx += drag.x
                    dy += drag.y
                }
            },
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BasicText("Liquid Glass — drag me", style = TextStyle(Color.White, 17.sp))
            BasicText(
                "refraction + frost + depth edge, over your real wallpaper",
                style = TextStyle(Color.White.copy(alpha = 0.75f), 13.sp),
            )
        }
    }
}

@Composable
private fun ComposerPill(backdrop: Backdrop) {
    GlassCapsule(
        backdrop = backdrop,
        modifier = Modifier.width(560.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 22.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText("Message…", style = TextStyle(Color.White.copy(0.6f), 15.sp))
            Spacer(Modifier.weight(1f))
            BasicText("↑", style = TextStyle(Color.White, 18.sp))
        }
    }
}

// Hoisted so the scene can read window position without threading it through.
private val LocalWindowState = androidx.compose.runtime.staticCompositionLocalOf<WindowState> {
    error("WindowState not provided")
}

@Composable
fun App() {
    val backdrop = rememberLayerBackdrop()
    Box(Modifier.fillMaxSize()) {
        BackdropScene(Modifier.layerBackdrop(backdrop))
        Box(Modifier.align(Alignment.Center).padding(bottom = 90.dp)) {
            DraggableGlassCard(backdrop)
        }
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp)) {
            ComposerPill(backdrop)
        }
    }
}

fun main() = application {
    val windowState = rememberWindowState(
        position = WindowPosition(Alignment.Center),
        width = 980.dp,
        height = 720.dp,
    )
    Window(
        onCloseRequest = ::exitApplication,
        state = windowState,
        title = "compose-mica demo — fake-Mica backdrop + liquid glass",
    ) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            androidx.compose.runtime.CompositionLocalProvider(LocalWindowState provides windowState) {
                App()
            }
        }
    }
}
