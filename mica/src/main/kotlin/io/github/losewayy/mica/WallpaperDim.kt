package io.github.losewayy.mica

/**
 * Wallpaper configuration and luminance-adaptive dimming.
 *
 * The dim curve is a product behavior, not an implementation detail: a bright
 * wallpaper must be dimmed harder than a dark one or foreground text becomes
 * unreadable, while a dark wallpaper should not be buried under a needless veil.
 */
data class WallpaperConfig(
    val enabled: Boolean = false,
    /** Absolute image path on disk. */
    val imagePath: String = "",
    /** Blur radius in px-equivalents, 0-40. */
    val blur: Int = DEFAULT_BLUR,
    /** Dim strength in percent, 0-90. */
    val dim: Int = DEFAULT_DIM,
) {
    companion object {
        const val DEFAULT_BLUR = 10
        const val DEFAULT_DIM = 40
        val DEFAULT = WallpaperConfig()
    }
}

/**
 * Resolved dim amounts. The three fields must stay distinct — conflating them
 * produces "the whole window painted black":
 * - [base]: veil over chrome regions (sidebar/topbar). They already carry tint
 *   extracted from the image, so they only need a fraction of the content dim.
 * - [contentExtra]: additional veil on the content column on top of [base].
 * - [content]: final opacity the content column reaches (diagnostic/testing).
 */
data class ResolvedWallpaperDim(
    val base: Double,
    val contentExtra: Double,
    val content: Double,
)

object WallpaperDim {

    /** Adaptive-curve baseline. */
    const val DIM_ADAPTIVE_BASE = 0.55

    /** Chrome regions take this fraction of the content dim. */
    const val DIM_CHROME_RATIO = 0.55

    /** Neutral stand-in luminance before the real image has been sampled. */
    const val NEUTRAL_LUMINANCE = 0.45

    /**
     * Adaptive dim curve: `effective = dim * (0.55 + lum)`.
     *
     * Why multiplicative rather than linear:
     * - At the perceptual midpoint lum ≈ 0.45 the factor is ≈ 1.0, so the slider
     *   reads as its literal value;
     * - bright wallpapers get dimmed harder (keeps text readable), dark
     *   wallpapers get let off (not pointlessly buried);
     * - multiplication keeps dim=0 a true "off" and dim=90 saturating at the cap.
     */
    fun resolve(dimPercent: Int, luminance: Double): ResolvedWallpaperDim {
        val dim = dimPercent.toDouble().coerceIn(0.0, 90.0)
        val lum = luminance.coerceIn(0.0, 1.0)
        val content = (dim * (DIM_ADAPTIVE_BASE + lum)).coerceIn(0.0, 90.0)
        val base = (content * DIM_CHROME_RATIO).coerceIn(0.0, 90.0)
        // Two veils composite as 1-(1-a)(1-b); solve for contentExtra so the
        // content column lands exactly on `content`.
        val baseRemaining = 1 - base / 100
        val denominator = if (baseRemaining == 0.0) 1.0 else baseRemaining
        val contentExtra = maxOf(0.0, (1 - (1 - content / 100) / denominator) * 100)
        return ResolvedWallpaperDim(base, contentExtra, content)
    }

    /** Rec.709 relative luminance; inputs are averaged 0-255 components. */
    fun luminanceOf(red: Double, green: Double, blue: Double): Double =
        (0.2126 * red + 0.7152 * green + 0.0722 * blue) / 255.0
}
