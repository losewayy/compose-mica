package io.github.losewayy.mica

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import org.jetbrains.skia.Image
import java.io.File

/**
 * Wallpaper image pipeline: decode, thumbnail-grid sampling, luminance.
 *
 * `Modifier.blur` on Compose Desktop lowers directly to a Skia ImageFilter —
 * blurring is a first-class render-pipeline operation, not a background hack,
 * so the same bitmap can feed both the backdrop and liquid-glass sampling.
 */
object WallpaperImage {

    /**
     * Decodes an image file. Returns null on failure — callers fall back to a
     * plain backdrop rather than throwing or showing a half-painted frame.
     */
    fun load(path: String): ImageBitmap? {
        if (path.isBlank()) return null
        val file = File(path)
        if (!file.isFile) return null
        return try {
            Image.makeFromEncoded(file.readBytes()).toComposeImageBitmap()
        } catch (error: Throwable) {
            System.err.println("[compose-mica] image load failed: " + error.message)
            null
        }
    }

    /**
     * Downsamples an image to a `size`×`size` [PixelGrid] for color extraction.
     *
     * Cost note: `toPixelMap()` materializes the whole image (1920×1080 ≈ 8MB,
     * 4000×3000 ≈ 48MB). This runs once per wallpaper change, not per frame.
     * For very large images a Skia pre-scale to `size` would be cheaper.
     */
    fun buildGrid(image: ImageBitmap, size: Int = 64): PixelGrid {
        val pixels = image.toPixelMap()
        val packed = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val sx = (x * image.width / size).coerceIn(0, image.width - 1)
                val sy = (y * image.height / size).coerceIn(0, image.height - 1)
                val c = pixels[sx, sy]
                val r = (c.red * 255f).toInt().coerceIn(0, 255)
                val g = (c.green * 255f).toInt().coerceIn(0, 255)
                val b = (c.blue * 255f).toInt().coerceIn(0, 255)
                packed[y * size + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return PixelGrid(size, size, packed)
    }

    /**
     * Average Rec.709 luminance of the image over the same thumbnail grid used
     * by color extraction — sampling the whole grid, not a corner patch (a
     * corner can easily be off by an order of magnitude, e.g. a dark shadow
     * region on an otherwise bright photo).
     */
    fun sampleLuminance(image: ImageBitmap, sampleSize: Int = 64): Double {
        if (image.width <= 0 || image.height <= 0) return WallpaperDim.NEUTRAL_LUMINANCE
        val grid = buildGrid(image, sampleSize)
        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        for (y in 0 until grid.height) {
            for (x in 0 until grid.width) {
                val (r, g, b) = grid.sample(x / grid.width.toDouble(), y / grid.height.toDouble())
                sumR += r; sumG += g; sumB += b
            }
        }
        val n = (grid.width * grid.height).toDouble()
        return WallpaperDim.luminanceOf(sumR / n, sumG / n, sumB / n)
    }
}
