package io.github.losewayy.mica

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/**
 * A decoded wallpaper image plus its resolved dim amounts — decoded once so the
 * layer and the content veil share the same bitmap (decoding a 4000×3000 photo
 * twice is a real, measurable cost).
 */
class WallpaperSurface(
    val image: ImageBitmap?,
    val resolved: ResolvedWallpaperDim,
)

/**
 * Decodes + meters + resolves dim for [config], memoized per input field so
 * dragging a blur/dim slider does not re-decode the image.
 */
@Composable
fun rememberWallpaperSurface(config: WallpaperConfig): WallpaperSurface {
    val image = remember(config.imagePath) { WallpaperImage.load(config.imagePath) }
    val luminance = remember(image) {
        image?.let { WallpaperImage.sampleLuminance(it) } ?: WallpaperDim.NEUTRAL_LUMINANCE
    }
    val resolved = remember(config.dim, luminance) {
        WallpaperDim.resolve(config.dim, luminance)
    }
    return remember(image, resolved) { WallpaperSurface(image, resolved) }
}

/**
 * Custom wallpaper layer: cover-fit image + blur + chrome veil.
 *
 * This layer must be *inside* whatever records the backdrop — refraction is
 * only meaningful if this image is part of the sampled content.
 *
 * [onColors] receives extracted palette colors once per image change; feed them
 * to your theme (sidebar/topbar tinting).
 */
@Composable
fun WallpaperLayer(
    config: WallpaperConfig,
    surface: WallpaperSurface,
    onColors: (WallpaperColors) -> Unit = {},
    modifier: Modifier = Modifier,
    /** Extra cover overscan in px — grows the backdrop box so blur edges never show white corners. */
    overscanPx: Float = 60f,
    /** Post-cover zoom applied around the image center. */
    zoom: Float = 1.06f,
) {
    val image = surface.image
    val resolved = surface.resolved

    LaunchedEffect(image) {
        if (image != null) onColors(WallpaperColorExtractor.extract(WallpaperImage.buildGrid(image)))
    }

    if (image == null) {
        // Configured image unreadable: fall back to a plain backdrop and keep the
        // reason in stderr — never a silent black window.
        AmbientBackdrop(modifier = modifier)
        return
    }

    Box(modifier) {
        // Blur only attaches the modifier when there is an actual radius —
        // no free ImageFilter pass for blur=0.
        val imageModifier = if (config.blur > 0) {
            // Radius compensation: Skia's blur radius is σ≈r while CSS blur(px)
            // behaves closer to σ≈px/2 with extra spread — a nominal match renders
            // noticeably softer on the web side. The empirical factor ≈1.6 makes
            // `blur = n` here read like `blur(n px)` in CSS comparisons.
            Modifier.fillMaxSize().blur(
                radius = config.blur.dp * 1.6f,
                edgeTreatment = BlurredEdgeTreatment.Unbounded,
            )
        } else {
            Modifier.fillMaxSize()
        }
        Canvas(imageModifier) {
            drawImageCover(image, overscanPx, zoom)
        }
        // First veil: chrome dim (whole window).
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Color.Black, alpha = (resolved.base / 100.0).toFloat())
        }
    }
}

/**
 * The extra veil that sits only on the content column (not the sidebar — the
 * sidebar already received the first veil). This is how "chrome takes only a
 * fraction of the dim" lands in practice.
 */
@Composable
fun WallpaperContentVeil(surface: WallpaperSurface) {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Color.Black, alpha = (surface.resolved.contentExtra / 100.0).toFloat())
    }
}

/**
 * Cover-fit draw with overscan: grow the destination box by [overscanPx] on
 * every side, cover-fit into it, then apply [zoom] around the center. The
 * overscan guarantees blur never exposes unpainted corners.
 */
private fun DrawScope.drawImageCover(image: ImageBitmap, overscanPx: Float, zoom: Float) {
    val boxW = size.width + overscanPx
    val boxH = size.height + overscanPx
    val cover = maxOf(boxW / image.width, boxH / image.height)
    val scaleFactor = cover * zoom
    val drawnW = image.width * scaleFactor
    val drawnH = image.height * scaleFactor
    val left = (size.width - drawnW) / 2f
    val top = (size.height - drawnH) / 2f
    drawImage(
        image = image,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(image.width, image.height),
        dstOffset = IntOffset(left.toInt(), top.toInt()),
        dstSize = IntSize(drawnW.toInt(), drawnH.toInt()),
        filterQuality = FilterQuality.Medium,
    )
}
