package io.github.losewayy.mica

import androidx.compose.ui.graphics.Color
import kotlin.math.round

/**
 * Palette extracted from a wallpaper image: per-surface tints for chrome,
 * sidebar, composer and cards, plus a border and overall luminance.
 */
data class WallpaperColors(
    val dominant: Color,
    val topbarBg: Color,
    val sidebarBg: Color,
    val composerBg: Color,
    val cardBg: Color,
    val border: Color,
    val luminance: Double,
)

/**
 * A small pixel grid. Sampling ("read a pixel") is deliberately decoupled from
 * the color math so the math stays a pure function that can be exhaustively
 * verified offline — no "looks right on my machine" testing.
 */
class PixelGrid(val width: Int, val height: Int, private val rgb: IntArray) {
    /** Color components (0-255) at normalized coordinates (u, v) ∈ [0,1). */
    fun sample(u: Double, v: Double): Triple<Int, Int, Int> {
        val x = (u * width).toInt().coerceIn(0, width - 1)
        val y = (v * height).toInt().coerceIn(0, height - 1)
        val packed = rgb[y * width + x]
        return Triple((packed shr 16) and 0xFF, (packed shr 8) and 0xFF, packed and 0xFF)
    }
}

/**
 * Region-weighted wallpaper color extraction.
 *
 * Instead of sampling fixed pixel bands, regions are expressed in normalized
 * coordinates — resolution-independent, unlike pixel-anchored sampling that
 * silently assumes a pre-scaled thumbnail:
 * - top strip (v < 0.1875) → topbar tint
 * - left strip (u < 0.25) → sidebar tint
 * - bottom-center band (v ≥ 0.625, u ∈ [0.25, 0.75]) → composer tint
 * - full-grid average → card tint, border, dominant color
 *
 * Each region average is blended toward a dark base so surfaces stay readable
 * on any wallpaper; blend ratios and per-surface alphas are tunable constants
 * at the top of [extract].
 */
object WallpaperColorExtractor {

    /** 0-255 components + alpha → Compose Color (the Float overload wants 0..1). */
    private fun argb(r: Int, g: Int, b: Int, alpha: Float): Color =
        Color(r / 255f, g / 255f, b / 255f, alpha)

    /** Blend toward a dark base: `channel * ratio + base * (1 - ratio)`. */
    private fun blend(channel: Int, base: Int, ratio: Double): Int =
        round(channel * ratio + base * (1 - ratio)).toInt().coerceIn(0, 255)

    fun extract(grid: PixelGrid): WallpaperColors {
        var allR = 0.0; var allG = 0.0; var allB = 0.0; var allN = 0
        var topR = 0.0; var topG = 0.0; var topB = 0.0; var topN = 0
        var leftR = 0.0; var leftG = 0.0; var leftB = 0.0; var leftN = 0
        var compR = 0.0; var compG = 0.0; var compB = 0.0; var compN = 0

        // One pass over the normalized grid. 64×64 is enough — this pipeline
        // wants "which way does the image lean", not photometric accuracy.
        val steps = 64
        for (iy in 0 until steps) {
            for (ix in 0 until steps) {
                val u = (ix + 0.5) / steps
                val v = (iy + 0.5) / steps
                val (r, g, b) = grid.sample(u, v)
                allR += r; allG += g; allB += b; allN++
                if (v < 0.1875) { topR += r; topG += g; topB += b; topN++ }
                if (u < 0.25) { leftR += r; leftG += g; leftB += b; leftN++ }
                if (v >= 0.625 && u >= 0.25 && u <= 0.75) {
                    compR += r; compG += g; compB += b; compN++
                }
            }
        }

        fun avg(sumR: Double, sumG: Double, sumB: Double, n: Int, fallbackR: Double, fallbackG: Double, fallbackB: Double): Triple<Int, Int, Int> =
            if (n > 0) Triple(round(sumR / n).toInt(), round(sumG / n).toInt(), round(sumB / n).toInt())
            else Triple(round(fallbackR).toInt(), round(fallbackG).toInt(), round(fallbackB).toInt())

        val aAllR = allR / allN; val aAllG = allG / allN; val aAllB = allB / allN
        val (aTopR, aTopG, aTopB) = avg(topR, topG, topB, topN, aAllR, aAllG, aAllB)
        val (aLeftR, aLeftG, aLeftB) = avg(leftR, leftG, leftB, leftN, aAllR, aAllG, aAllB)
        val (aCompR, aCompG, aCompB) = avg(compR, compG, compB, compN, aAllR, aAllG, aAllB)

        // Blend bases and ratios: topbar/sidebar base (14,14,18), composer (20,20,26), card (16,16,22).
        val topbar = argb(
            blend(aTopR, 14, 0.35),
            blend(aTopG, 14, 0.35),
            blend(aTopB, 18, 0.35),
            0.60f,
        )
        val sidebar = argb(
            blend(aLeftR, 14, 0.38),
            blend(aLeftG, 14, 0.38),
            blend(aLeftB, 18, 0.38),
            0.68f,
        )
        val composer = argb(
            blend(aCompR, 20, 0.30),
            blend(aCompG, 20, 0.30),
            blend(aCompB, 26, 0.30),
            0.80f,
        )
        val card = argb(
            blend(round(aAllR).toInt(), 16, 0.25),
            blend(round(aAllG).toInt(), 16, 0.25),
            blend(round(aAllB).toInt(), 22, 0.25),
            0.45f,
        )
        val border = argb(
            (round(aAllR).toInt() + 70).coerceAtMost(255),
            (round(aAllG).toInt() + 70).coerceAtMost(255),
            (round(aAllB).toInt() + 70).coerceAtMost(255),
            0.12f,
        )
        return WallpaperColors(
            dominant = Color(round(aAllR).toInt(), round(aAllG).toInt(), round(aAllB).toInt(), 255),
            topbarBg = topbar,
            sidebarBg = sidebar,
            composerBg = composer,
            cardBg = card,
            border = border,
            luminance = WallpaperDim.luminanceOf(aAllR, aAllG, aAllB),
        )
    }
}
