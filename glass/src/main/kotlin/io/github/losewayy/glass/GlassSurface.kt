package io.github.losewayy.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Whether glass surfaces should use the light-theme tint curve (dark veil)
 * instead of the dark-theme one (light veil). Provide it once at the app root;
 * defaults to dark.
 */
val LocalGlassDarkTheme = compositionLocalOf { true }

/**
 * Usage tiers for glass surfaces.
 *
 * Each tier maps to a [GlassRecipe] in [GlassRecipes]. The mapping of
 * parameters follows the upstream library's documented semantics:
 * - `refractionHeight` = lens band width (must stay ≤ the shape's minimum
 *   corner radius, or corners show discontinuities);
 * - `refractionAmount` = peak displacement;
 * - `blur`/`saturate` map to `blur()`/`colorControls()`;
 * - `depthEffect`/`chromaticAberration` are opt-in lens switches that are off
 *   by default upstream — enable only with a reason.
 */
enum class GlassTier {
    /** Window chrome / top bar — no refraction, blur 16 + saturate 1.15. */
    CHROME,

    /** Sidebar — restrained edge bend; on straight-edged panels the lens clamps to 0. */
    SIDEBAR,

    /** Auxiliary labels — no refraction, blur 12 + saturate 1.5, readability only. */
    TOOLTIP,

    /** Menus / popovers / toasts — small lens band 8/12 + blur 8 + saturate 1.5. */
    MENU,

    /** Focus surfaces — no refraction, blur 2 + saturate 1.5 (pure translucency + a hint of frost). */
    PANEL,

    /** Dialogs / command palettes — stronger separation band 16/24 + blur 12 + depth. */
    OVERLAY,

    /**
     * Small interactive controls (buttons/chips) — blur 2 + lens 12/24 with
     * chromatic aberration. Dispersion belongs on small interactive elements,
     * not on large surfaces.
     */
    CONTROL,
}

data class GlassRecipe(
    val blurDp: Float,
    val lensRadiusDp: Float,
    val lensDepthDp: Float,
    val depthEffect: Boolean,
    val chromaticAberration: Boolean,
    /** `null` = skip colorControls entirely (equivalent to saturation 1.0). */
    val saturate: Float?,
    val surfaceAlpha: Float,
)

/**
 * Per-tier glass recipes.
 *
 * Numeric basis: the upstream backdrop library author's official examples and
 * API docs — the glass capability is taken from the library author's own
 * guidance, not approximated from screenshots of another implementation.
 *
 * Hard constraints (from the library's documentation):
 * - effect order is fixed **color filter ⇒ blur ⇒ lens**;
 * - `lens(refractionHeight, refractionAmount, depthEffect = false,
 *   chromaticAberration = false)` — both switches default to false;
 * - **`refractionHeight` must lie within `[0, shape.minCornerRadius]`** —
 *   exceeding it produces visible discontinuities at the corners. Recipes here
 *   are clamped to the corner radii they are used with.
 */
object GlassRecipes {
    /** Chrome: band/amount 0, blur 16, saturate 1.15. */
    val CHROME = GlassRecipe(16f, 0f, 0f, depthEffect = false, chromaticAberration = false, saturate = 1.15f, surfaceAlpha = 0.05f)

    /**
     * Sidebar: band 10 / amount 14 / blur 0 / saturate 1.5 nominally — but for a
     * flush-edged straight panel (`cornerRadius = 0`) the refractionHeight bound
     * clamps the lens to 0, so the effective lens is 0/0.
     */
    val SIDEBAR = GlassRecipe(0f, 0f, 0f, depthEffect = false, chromaticAberration = false, saturate = 1.5f, surfaceAlpha = 0.05f)

    /** Tooltip: band/amount 0, blur 12, saturate 1.5. */
    val TOOLTIP = GlassRecipe(12f, 0f, 0f, depthEffect = false, chromaticAberration = false, saturate = 1.5f, surfaceAlpha = 0.07f)

    /** Menu: band 8 / amount 12 / blur 8 / saturate 1.5. */
    val MENU = GlassRecipe(8f, 8f, 12f, depthEffect = false, chromaticAberration = false, saturate = 1.5f, surfaceAlpha = 0.07f)

    /** Panel (focus surface): band/amount 0, blur 2, saturate 1.5. */
    val PANEL = GlassRecipe(2f, 0f, 0f, depthEffect = false, chromaticAberration = false, saturate = 1.5f, surfaceAlpha = 0.06f)

    /** Overlay: band 16 / amount 24 / blur 12 / saturate 1.5 / depthEffect on. */
    val OVERLAY = GlassRecipe(12f, 16f, 24f, depthEffect = true, chromaticAberration = false, saturate = 1.5f, surfaceAlpha = 0.07f)

    /** Interactive control (button/chip, ~10dp corner): thin blur + lens 12/24 + chromatic. */
    val CONTROL = GlassRecipe(2f, 12f, 24f, depthEffect = false, chromaticAberration = true, saturate = null, surfaceAlpha = 0.08f)

    fun of(tier: GlassTier): GlassRecipe = when (tier) {
        GlassTier.CHROME -> CHROME
        GlassTier.SIDEBAR -> SIDEBAR
        GlassTier.TOOLTIP -> TOOLTIP
        GlassTier.MENU -> MENU
        GlassTier.PANEL -> PANEL
        GlassTier.OVERLAY -> OVERLAY
        GlassTier.CONTROL -> CONTROL
    }
}

/**
 * Surface-veil scaling for light themes.
 *
 * Dark-theme surfaces take a white veil at 5–8%; light themes want a black
 * veil at 3–5% — lighter overall. Scaling recipe alphas by 0.6 maps
 * 0.05→0.03 and 0.08→0.048, landing inside that band.
 */
private const val LIGHT_TINT_SCALE = 0.6f

/** Outline families supported by [GlassSurface]. */
enum class GlassShape { ROUNDED, CAPSULE }

/**
 * A glass surface's drop shadow, in CSS `box-shadow` semantics (px units).
 *
 * Describes a single layer only — multi-stop elevation ramps should be
 * composed by the caller (e.g. a separate stroke for the hairline term).
 */
data class GlassShadow(
    /** CSS `box-shadow` blur radius in px (not sigma — see conversion below). */
    val blurRadiusPx: Float,
    /** CSS offsets; usually only the y component is non-zero. */
    val offsetXPx: Float = 0f,
    val offsetYPx: Float,
    /** Color including alpha (e.g. `color-mix(black 35%)` → `Color(0x59000000)`). */
    val color: Color,
)

/**
 * CSS `box-shadow` → the backdrop library's `Shadow`.
 *
 * **Blur radius vs sigma — the only arithmetic that matters in this file**:
 * the library ultimately calls `MaskFilter.makeBlur(mode, sigma)` where the
 * float is a Skia sigma, while CSS defines the blur radius as *twice* the
 * standard deviation — so `sigma = blurRadius / 2`. Skipping the conversion
 * draws a 32px shadow twice as thick.
 */
private fun GlassShadow.toBackdropShadow(): Shadow = Shadow(
    radius = (blurRadiusPx / 2f).dp, // CSS blur 32px → sigma 16
    offset = DpOffset(offsetXPx.dp, offsetYPx.dp),
    color = color,
)

/**
 * Official "press deforms the glass surface" gesture+animation driver.
 *
 * Feed [PressDeformation.progress] to `GlassSurface(layerBlock = ...)`. Two
 * points are straight from the upstream tutorial's pitfall notes:
 * 1. spec `spring(dampingRatio = 0.5f, stiffness = 300f, visibilityThreshold = 0.001f)`;
 * 2. deformation must go through `layerBlock` (the surface moves, the refracted
 *    content does not) — writing it into `graphicsLayer` is the known mistake.
 */
@Composable
fun rememberPressDeformation(): PressDeformation {
    val animationScope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }
    val spec = remember { spring<Float>(dampingRatio = 0.5f, stiffness = 300f, visibilityThreshold = 0.001f) }
    return remember(animationScope, progress) {
        PressDeformation(progress = progress.value, scope = animationScope, spec = spec, animation = progress)
    }
}

/** Carrier for [rememberPressDeformation]; [progress] is read during draw. */
class PressDeformation(
    val progress: Float,
    private val scope: CoroutineScope,
    private val spec: AnimationSpec<Float>,
    private val animation: Animatable<Float, AnimationVector1D>,
) {
    /** Pressed: swell toward `maxScale` — the surface "puffs" like a droplet. */
    fun press() { scope.launch { animation.animateTo(1f, spec) } }

    /** Released/cancelled: spring back. The spring overshoots on its own. */
    fun release() { scope.launch { animation.animateTo(0f, spec) } }
}

/**
 * Turns a [PressDeformation] into a `layerBlock`: `scale = lerp(1, (w+16dp)/w, progress)`.
 * The `16dp` swell amount comes from the upstream interactive tutorial.
 */
fun pressLayerBlock(deformation: PressDeformation): GraphicsLayerScope.() -> Unit = {
    val w = size.width
    if (w > 0f) {
        val maxScale = (w + 16f.dp.toPx()) / w
        val scale = lerp(1f, maxScale, deformation.progress)
        scaleX = scale
        scaleY = scale
    }
}

/**
 * Liquid glass surface — the single place where backdrop effects are applied.
 *
 * Parameters come only from [GlassRecipes]: glass parameters are acceptance
 * facts ("the composer refracts the text beneath it"), and scattering them
 * across call sites makes them impossible to audit.
 *
 * **Why the shadow goes through `drawBackdrop`'s `shadow` slot instead of
 * `Modifier.shadow`:** glass surfaces use `com.kyant.shapes.RoundedRectangle` —
 * a *continuous-curvature* (squircle) outline — while `Modifier.shadow` takes a
 * Compose `Shape`, whose nearest equivalent `RoundedCornerShape` is a quarter
 * circle. At the same `cornerRadius` those are not the same curve, and corners
 * are exactly where a shadow outline mismatch is most visible ("the shadow's
 * corners look squarer than the glass's"). The library's `shadow` slot shares
 * one `ShapeProvider` with the glass effect itself, so the shadow and the
 * surface are pixel-identical outlines — there is nothing to align.
 * Trade-off: the shadow draws outside the element's layout bounds, so parent
 * containers need spare room.
 */
@Composable
fun GlassSurface(
    backdrop: Backdrop,
    tier: GlassTier,
    modifier: Modifier = Modifier,
    shape: GlassShape = GlassShape.ROUNDED,
    cornerRadius: Dp = 10.dp,
    surfaceAlpha: Float? = null,
    /**
     * Explicit surface tint. Default follows the veil semantics below; reading
     * surfaces (e.g. sidebars) should pass an opaque panel color instead —
     * a 5% white veil alone cannot produce a solid reading surface.
     */
    surfaceColor: Color? = null,
    /**
     * Drop shadow under the surface. `null` (default) = no shadow, which keeps
     * every existing call site pixel-identical. Only floating surfaces that
     * actually carry a `box-shadow` should pass a value.
     */
    elevation: GlassShadow? = null,
    /**
     * Surface deformation layer — the only correct entry point for "liquid".
     *
     * The upstream interactive tutorial documents the trap: scaling via
     * `Modifier.graphicsLayer` scales the refracted backdrop *content* together
     * with the surface — wrong. `layerBlock` deforms the glass surface while
     * the sampled content stays put; that desync is what makes it feel like
     * jelly.
     *
     * `null` (default) = no deformation.
     */
    layerBlock: (GraphicsLayerScope.() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val recipe = GlassRecipes.of(tier)
    // The theme flag must be read in composable scope: onDrawSurface is a draw
    // lambda and cannot read CompositionLocals.
    val lightTheme = !LocalGlassDarkTheme.current
    // Remember the shadow spec: drawBackdrop's shadow slot is `() -> Shadow?` —
    // a fresh lambda every recomposition would break modifier equality.
    val shadowSpec = remember(elevation) { elevation?.toBackdropShadow() }
    Box(
        modifier.drawBackdrop(
            backdrop = backdrop,
            // Shadow and glass share one shape lambda (see KDoc above), so the
            // outlines are pixel-identical — no squircle-vs-arc mismatch.
            shadow = { shadowSpec },
            shape = {
                when (shape) {
                    GlassShape.ROUNDED -> RoundedRectangle(cornerRadius)
                    GlassShape.CAPSULE -> Capsule()
                }
            },
            effects = {
                // Hard order constraint: color filter ⇒ blur ⇒ lens.
                // `saturate` maps to colorControls; recipes with saturate=null
                // keep the upstream examples' vibrancy().
                if (recipe.saturate != null) colorControls(saturation = recipe.saturate) else vibrancy()
                blur(recipe.blurDp.dp.toPx())
                lens(
                    recipe.lensRadiusDp.dp.toPx(),
                    recipe.lensDepthDp.dp.toPx(),
                    depthEffect = recipe.depthEffect,
                    chromaticAberration = recipe.chromaticAberration,
                )
            },
            layerBlock = layerBlock,
            onDrawSurface = {
                val tint = surfaceColor
                if (tint != null) {
                    drawRect(tint)
                } else {
                    // Veil semantics: dark theme → white veil 5–8%, light theme →
                    // black veil 3–5%. A white veil on a light background lifts
                    // the surface into the backdrop; light themes need a dark
                    // veil to separate the surface instead.
                    val alpha = surfaceAlpha ?: recipe.surfaceAlpha
                    if (lightTheme) {
                        drawRect(Color.Black.copy(alpha = alpha * LIGHT_TINT_SCALE))
                    } else {
                        drawRect(Color.White.copy(alpha = alpha))
                    }
                }
            },
        ),
        content = content,
    )
}

/** Capsule-shaped glass (send-button-style interactive controls). */
@Composable
fun GlassCapsule(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) = GlassSurface(
    backdrop = backdrop,
    tier = GlassTier.CONTROL,
    modifier = modifier,
    shape = GlassShape.CAPSULE,
    content = content,
)
