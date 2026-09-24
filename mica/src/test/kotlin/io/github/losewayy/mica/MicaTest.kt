package io.github.losewayy.mica

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WallpaperDimTest {

    @Test
    fun `dim 0 is a true off regardless of luminance`() {
        for (lum in listOf(0.0, 0.45, 1.0)) {
            val r = WallpaperDim.resolve(0, lum)
            assertEquals(0.0, r.base)
            assertEquals(0.0, r.content)
            assertEquals(0.0, r.contentExtra)
        }
    }

    @Test
    fun `brighter wallpaper gets dimmed harder`() {
        val dark = WallpaperDim.resolve(40, 0.1)
        val bright = WallpaperDim.resolve(40, 0.9)
        assertTrue(bright.content > dark.content)
    }

    @Test
    fun `chrome takes only a fraction of content dim`() {
        val r = WallpaperDim.resolve(60, 0.5)
        assertTrue(r.base < r.content)
    }

    @Test
    fun `content is capped at 90 percent`() {
        val r = WallpaperDim.resolve(90, 1.0)
        assertTrue(r.content <= 90.0)
    }

    @Test
    fun `rec709 luminance endpoints`() {
        assertEquals(0.0, WallpaperDim.luminanceOf(0.0, 0.0, 0.0), 1e-9)
        assertEquals(1.0, WallpaperDim.luminanceOf(255.0, 255.0, 255.0), 1e-9)
    }
}

class WallpaperColorExtractorTest {

    private fun solidGrid(r: Int, g: Int, b: Int): PixelGrid {
        val packed = IntArray(8 * 8) { (0xFF shl 24) or (r shl 16) or (g shl 8) or b }
        return PixelGrid(8, 8, packed)
    }

    @Test
    fun `solid image yields its own dominant color`() {
        val colors = WallpaperColorExtractor.extract(solidGrid(200, 100, 50))
        assertEquals(200, (colors.dominant.red * 255).toInt())
        assertEquals(100, (colors.dominant.green * 255).toInt())
        assertEquals(50, (colors.dominant.blue * 255).toInt())
    }

    @Test
    fun `surface tints blend toward dark bases`() {
        // A fully white image still produces dark surfaces, not white ones.
        val colors = WallpaperColorExtractor.extract(solidGrid(255, 255, 255))
        assertTrue(colors.sidebarBg.red < 0.7f)
        assertTrue(colors.composerBg.alpha == 0.80f)
    }
}

class SystemThemeTest {

    @Test
    fun `registry value 1 is light, everything else dark`() {
        assertEquals(ThemeMode.LIGHT, SystemTheme.resolve(1))
        assertEquals(ThemeMode.DARK, SystemTheme.resolve(0))
        assertEquals(ThemeMode.DARK, SystemTheme.resolve(null))
    }

    @Test
    fun `explicit preference bypasses the OS read`() {
        assertEquals(ThemeMode.LIGHT, SystemTheme.resolve(ThemePreference.LIGHT))
        assertEquals(ThemeMode.DARK, SystemTheme.resolve(ThemePreference.DARK))
    }
}
