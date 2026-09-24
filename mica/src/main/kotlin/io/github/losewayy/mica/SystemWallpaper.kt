package io.github.losewayy.mica

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import java.io.File

/**
 * System wallpaper access for building a *visual equivalent* of Windows Mica:
 * the desktop wallpaper is laid out across the whole screen, blurred, veiled,
 * and then the window shows the slice it is currently covering.
 *
 * Why paint it instead of asking DWM for real Mica:
 * - Real Mica needs `DwmSetWindowAttribute(DWMWA_SYSTEMBACKDROP_TYPE)` on an HWND
 *   that Compose Desktop does not expose. A per-pixel transparent window would
 *   also leak *other applications'* windows through — that is not Mica's
 *   semantic (Mica samples the wallpaper only, never live windows).
 * - The content here is the actual system wallpaper file
 *   (`HKCU\Control Panel\Desktop\Wallpaper`), so when the user changes their
 *   wallpaper this layer changes with it — same signal source as real Mica.
 *
 * Honesty boundary (stated, not disguised):
 * - Blur radius and veil strength are eyeball-matched empirical constants;
 *   Mica's luminosity blend has no public formula.
 * - The crop offset tracks window position per frame — no blur recomputation
 *   while the window moves.
 */
object SystemWallpaper {

    /** Fill mode (`HKCU\Control Panel\Desktop\WallpaperStyle`). */
    enum class Style { CENTER, TILE, STRETCH, FIT, FILL, SPAN, UNKNOWN }

    data class Info(val path: String, val style: Style)

    /**
     * Reads the system wallpaper configuration. On Windows this shells out to
     * `reg query` (~50ms — call off the UI thread); non-Windows or unreadable
     * returns null so callers can fall back to a plain surface.
     *
     * `WallpaperStyle`: 0=center 1=tile 2=stretch 6=fit 10=fill 22=span;
     * `TileWallpaper=1` means tiled (style is usually still 0).
     */
    fun locate(): Info? {
        if (!System.getProperty("os.name", "").lowercase().contains("win")) return null
        return try {
            val output = ProcessBuilder(
                "reg", "query", "HKCU\\Control Panel\\Desktop",
                "/v", "Wallpaper",
            ).redirectErrorStream(true).start().apply { waitFor() }
                .inputStream.bufferedReader().readText()
            val path = Regex("""Wallpaper\s+REG_\w+\s+(.+)\s*$""")
                .find(output.trim().lines().lastOrNull { it.contains("Wallpaper") } ?: "")
                ?.groupValues?.get(1)?.trim()
            if (path.isNullOrBlank()) return null
            val styleOut = ProcessBuilder(
                "reg", "query", "HKCU\\Control Panel\\Desktop",
                "/v", "WallpaperStyle",
            ).redirectErrorStream(true).start().apply { waitFor() }
                .inputStream.bufferedReader().readText()
            val styleNum = Regex("""WallpaperStyle\s+REG_\w+\s+0x([0-9a-fA-F]+)""")
                .find(styleOut)?.groupValues?.get(1)?.toInt(16) ?: 10
            val style = when (styleNum) {
                0 -> Style.CENTER
                1 -> Style.TILE
                2 -> Style.STRETCH
                6 -> Style.FIT
                10 -> Style.FILL
                22 -> Style.SPAN
                else -> Style.UNKNOWN
            }
            if (File(path).isFile) Info(path, style) else null
        } catch (error: Throwable) {
            System.err.println("[compose-mica] wallpaper locate failed: " + error.message)
            null
        }
    }

    /**
     * Logical screen size (values are logical pixels — AWT screenSize already
     * accounts for DPI scaling).
     */
    fun screenSizeDp(): Pair<Int, Int> {
        return try {
            val size = java.awt.Toolkit.getDefaultToolkit().screenSize
            size.width to size.height
        } catch (_: Throwable) {
            1280 to 800
        }
    }

    /**
     * A materialized full-screen wallpaper surface: decoded image + fill style +
     * screen size. Produced once; moving the window only changes the crop offset.
     */
    data class Surface(
        val image: ImageBitmap,
        val style: Style,
        val screenW: Int,
        val screenH: Int,
    )
}

/**
 * Window-level fake-Mica layer.
 *
 * [windowX]/[windowY] is the window's top-left corner in screen-space logical
 * pixels (for offscreen capture, pass a virtual centered position).
 * [veilAlpha] is the darkening veil strength over the whole layer.
 *
 * This composable owns only the deterministic geometry: the wallpaper is laid
 * out in *screen coordinates* per its fill style, then a negative offset places
 * the window over the slice it should be seeing. Moving the window changes only
 * the offset — the wallpaper stays anchored in screen space, matching how real
 * Mica samples the wallpaper.
 */
@Composable
fun MicaWallpaperLayer(
    surface: SystemWallpaper.Surface,
    windowX: Float,
    windowY: Float,
    blurDp: Float,
    veilAlpha: Float,
    modifier: Modifier = Modifier,
) {
    val imageModifier = if (blurDp > 0f) {
        modifier.fillMaxSize().blur(blurDp.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
    } else {
        modifier.fillMaxSize()
    }
    Canvas(imageModifier) {
        // Resolve the full-screen fill rect in screen coordinates (logical px).
        val sw = surface.screenW.toFloat()
        val sh = surface.screenH.toFloat()
        val iw = surface.image.width.toFloat()
        val ih = surface.image.height.toFloat()
        val (drawW, drawH) = when (surface.style) {
            SystemWallpaper.Style.CENTER -> iw to ih
            SystemWallpaper.Style.STRETCH -> sw to sh
            SystemWallpaper.Style.FIT -> {
                val s = minOf(sw / iw, sh / ih)
                iw * s to ih * s
            }
            // FILL / SPAN / unknown: cover (fill=10 is what Windows reports by default).
            else -> {
                val s = maxOf(sw / iw, sh / ih)
                iw * s to ih * s
            }
        }
        val originX = (sw - drawW) / 2f
        val originY = (sh - drawH) / 2f
        drawImage(
            image = surface.image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(surface.image.width, surface.image.height),
            dstOffset = IntOffset((originX - windowX).toInt(), (originY - windowY).toInt()),
            dstSize = IntSize(drawW.toInt(), drawH.toInt()),
            filterQuality = FilterQuality.High,
        )
        // Equivalent of Mica's luminosity blend (empirical value).
        if (veilAlpha > 0f) drawRect(Color.Black.copy(alpha = veilAlpha))
    }
}

/**
 * Materializes the fake-Mica full-screen image: decodes the wallpaper file and
 * pairs it with fill style + screen size. Returns null on any failure so the
 * caller can fall back to a plain backdrop instead of showing a black window.
 */
@Composable
fun rememberSystemWallpaperSurface(): SystemWallpaper.Surface? {
    val surface by produceState<SystemWallpaper.Surface?>(null) {
        val info = SystemWallpaper.locate() ?: return@produceState
        val image = WallpaperImage.load(info.path)
        if (image == null) {
            System.err.println("[compose-mica] wallpaper decode failed: " + info.path)
            return@produceState
        }
        val (screenW, screenH) = SystemWallpaper.screenSizeDp()
        value = SystemWallpaper.Surface(image = image, style = info.style, screenW = screenW, screenH = screenH)
    }
    return surface
}
