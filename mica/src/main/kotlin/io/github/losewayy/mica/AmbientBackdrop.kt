package io.github.losewayy.mica

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.luminance

/**
 * Plain window backdrop for when no wallpaper image is in play.
 *
 * The base stays a solid surface color with an extremely slow same-hue drift —
 * the point is to give glass effects *signal* to bend (refraction on a pure
 * flat color is invisible), not to decorate. Two faint cool glows supply a
 * gentle color gradient without pulling the surface off its measured color.
 */
@Composable
fun AmbientBackdrop(
    modifier: Modifier = Modifier,
    topColor: Color = Color(0xFF181818),
    midColor: Color = Color(0xFF181818),
    bottomColor: Color = Color(0xFF171717),
    /** Accessibility: callers should pass the OS/user reduced-motion flag. */
    reducedMotion: Boolean = false,
) {
    // When reduced motion is on, do not start an infinite transition at all —
    // that would be a permanent per-frame invalidation source. A fixed phase
    // keeps the gradient (the glass still has signal), it just doesn't drift.
    val phase = if (reducedMotion) {
        0.35f
    } else {
        val transition = rememberInfiniteTransition(label = "backdrop")
        val p by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(60_000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "phase",
        )
        p
    }
    val isLightBg = topColor.luminance() > 0.5f

    Canvas(modifier.fillMaxSize()) {
        drawRect(
            brush = Brush.verticalGradient(listOf(topColor, midColor, bottomColor)),
            size = size,
        )
        // Two very faint cool glows: enough gradient for lenses to refract, but
        // kept below visibility as decoration. Fainter still on light themes,
        // where a dark smudge on white reads as dirt.
        val glowAlpha = if (isLightBg) 0.02f else 0.04f
        glow(Color(0xFF26303F), 0.22f, 0.10f, phase, size.width * 0.55f, glowAlpha)
        glow(Color(0xFF2E2636), 0.80f, 0.72f, phase + 0.5f, size.width * 0.48f, glowAlpha * 0.75f)
    }
}

private fun DrawScope.glow(
    color: Color,
    anchorX: Float,
    anchorY: Float,
    phase: Float,
    radius: Float,
    alpha: Float,
) {
    val drift = kotlin.math.sin(phase * 2f * Math.PI.toFloat())
    val cross = kotlin.math.cos(phase * 2f * Math.PI.toFloat())
    val center = Offset(
        x = size.width * (anchorX + 0.02f * drift),
        y = size.height * (anchorY + 0.03f * cross),
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = alpha), color.copy(alpha = 0f)),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
}
