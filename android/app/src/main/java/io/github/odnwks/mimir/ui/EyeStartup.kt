package io.github.odnwks.mimir.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas

/*
 * The startup animation of the eye (R15), a 1:1 port of render(t) in the owner-approved mock-up
 * (mockups/eye-startup-anim.html): the open eye fades in, closes, the scar strikes (Whisper eye),
 * the globe pops in (Whisper-with-internet eye), both fade, the eye reopens and stays, then the
 * words and the box fade in. 4.6 s, smoothstep easing, no sound, no haptics.
 */

/** Total length in seconds. */
const val EYE_DUR = 4.6f

/** Where the clock starts once the layers are ready: the eye's fade-in (0 to .35 s) is over, since the plain eye was already showing. */
const val RESUME_T = 0.35f

/** The prefs key of the Settings switch "Startup animation" (in the phone chat's prefs); ON unless switched off. */
const val STARTUP_ANIMATION = "startup_animation"

fun startupAnimationOn(ctx: Context): Boolean = ctx.getSharedPreferences(PHONE_CHAT_PREFS, 0).getBoolean(STARTUP_ANIMATION, true)

private fun cl(x: Float) = if (x < 0f) 0f else if (x > 1f) 1f else x
private fun seg(t: Float, a: Float, b: Float) = cl((t - a) / (b - a))
private fun ease(x: Float) = x * x * (3f - 2f * x)

/**
 * What is on screen at [t] seconds. Opacities are clamped to 0..1 as CSS does (the mock-up's glow and halo
 * "boost" values go past 1). [scarInk] is 0xRRGGBB. [textRise] is in dp.
 */
data class EyeFrame(
    val openAlpha: Float, val openScaleY: Float, val closedAlpha: Float,
    val scarAlpha: Float, val scarGlow: Float, val scarInk: Int, val halo: Float,
    val globeAlpha: Float, val globeScale: Float,
    val textAlpha: Float, val textRise: Float, val bottomAlpha: Float,
    /** 0..1: how far the scar's ink is towards white. */
    val scarFlash: Float = 0f,
)

fun eyeFrame(t: Float): EyeFrame {
    val fadeIn = ease(seg(t, 0f, .35f))
    val close = ease(seg(t, .75f, 1.0f))
    val reopen = ease(seg(t, 3.35f, 3.7f))
    val shut = close * (1f - reopen) // 0 open .. 1 shut
    val strike = seg(t, 1.0f, 1.12f)
    val scarOut = seg(t, 3.2f, 3.4f)
    val flash = strike * (1f - ease(seg(t, 1.12f, 1.5f))) // bright flash that settles
    val ink = if (flash > 0.05f) {
        (Math.round(71 + 184 * flash) shl 16) or (Math.round(230 + 25 * flash) shl 8) or Math.round(212 + 43 * flash)
    } else 0x47E6D4
    val gin = ease(seg(t, 2.0f, 2.3f))
    val gout = ease(seg(t, 3.0f, 3.25f))
    val tx = ease(seg(t, 3.75f, 4.35f))
    return EyeFrame(
        openAlpha = fadeIn * (1f - seg(shut, .6f, 1f)),
        openScaleY = 1f - 0.92f * shut,
        closedAlpha = seg(shut, .6f, 1f),
        scarAlpha = strike * (1f - scarOut),
        scarGlow = cl(.55f + 1.2f * flash),
        scarInk = ink,
        halo = cl(1f + 1.6f * flash),
        globeAlpha = gin * (1f - gout),
        globeScale = .6f + .4f * gin,
        textAlpha = tx, textRise = 10f * (1f - tx), bottomAlpha = tx, scarFlash = if (flash > 0.05f) flash else 0f,
    )
}

/**
 * One startup play: [t] is the clock in seconds (the end value when nothing plays). Touching the screen
 * or typing skips to the end at once ([skip]); a touch that skips is not also a tap on what lies under it.
 */
@Stable
class EyeStartup(plays: Boolean) {
    /** True when this start was set to play: the eye then stays the cached layers' last frame instead of being redrawn with its blur. */
    val used: Boolean = plays
    /** The clock. Read only inside draw and graphics-layer blocks, never in composition (it changes every frame). */
    var t by mutableFloatStateOf(if (plays) 0f else EYE_DUR)
        private set
    private var active by androidx.compose.runtime.mutableStateOf(plays)
    /** True while it plays. Changes once, at the end, so reading it in composition is cheap. */
    val playing: Boolean get() = active
    private var swallow = false
    /** The eye's layers are drawn and ready (they are made off the main thread, while the plain resting eye shows). */
    @Volatile private var layersUp = false
    /** True once an eye was composed that makes its layers in the background (nothing to wait for otherwise). */
    @Volatile private var expects = false

    fun expectLayers(): Boolean { expects = true; return true }
    /** Where the clock starts when the layers come: the open eye is already there, so its fade-in is over. */
    private var from = 0f

    /** The layers are ready: the clock starts, with the eye already open (the plain eye was showing meanwhile). */
    fun layersBuilt() {
        if (layersUp) return
        layersUp = true
        if (active && t < RESUME_T) { from = RESUME_T; t = RESUME_T }
    }

    fun skip() { t = EYE_DUR; active = false }

    /** Any finger down: it skips, and the tap that did so is swallowed by [takeSwallowedTap]. */
    fun onTouch() { swallow = false; if (playing) { skip(); swallow = true } }

    /** True once, for the tap whose touch skipped the animation. */
    fun takeSwallowedTap(): Boolean = swallow.also { swallow = false }

    /** Runs the clock on the frame clock until the end or a skip. */
    suspend fun run() {
        if (!playing) return
        // Not before the layers are ready (a slow phone makes them in the background), but never for more than ~3 s.
        var waited = 0
        while (playing && expects && !layersUp && waited < 180) { androidx.compose.runtime.withFrameNanos { }; waited++ }
        var start = -1L
        var warm = 0
        while (playing) {
            androidx.compose.runtime.withFrameNanos { n ->
                // The first frames upload the eye's layers and may be slow: time starts after them.
                if (warm < 3) { warm++; start = n }
                if (playing) t = (from + (n - start) / 1_000_000_000f).coerceAtMost(EYE_DUR)
            }
            if (t >= EYE_DUR) active = false
        }
    }
}

/** Provided by AppRoot; outside it nothing plays. */
val LocalEyeStartup = compositionLocalOf { EyeStartup(false) }

/** Once per process (a fresh app start), per application: a rotation, a resume or a later visit never replays. */
object StartupGate {
    private val claimed = java.util.Collections.newSetFromMap(java.util.WeakHashMap<Context, Boolean>())

    /** True the first time it is asked for this application. */
    @Synchronized fun claim(ctx: Context): Boolean = claimed.add(ctx.applicationContext)

    /** The latest startup made, for tests to look at. */
    @Volatile internal var last: EyeStartup? = null
}

/** The rule: the switch is on, the phone isn't removing animations, and the app opened on the home screen. */
fun startupPlays(switchOn: Boolean, reduceMotion: Boolean, opensOnHome: Boolean): Boolean = switchOn && !reduceMotion && opensOnHome

/** The startup of this app start: asked once, on the first composition of the root. */
@Composable
fun rememberEyeStartup(opensOnHome: Boolean): EyeStartup {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val reduce = io.github.odnwks.mimir.ui.theme.LocalReduceMotion.current || io.github.odnwks.mimir.ui.theme.Motion.systemRemovesAnimations(ctx)
    val s = remember {
        // Claimed whatever happens, so a later visit to the home screen never plays it.
        val first = StartupGate.claim(ctx)
        EyeStartup(first && startupPlays(startupAnimationOn(ctx), reduce, opensOnHome)).also { StartupGate.last = it }
    }
    androidx.compose.runtime.LaunchedEffect(s) { s.run() }
    return s
}

/** The words and the box at the end of the first hello: fade in and (for text) rise 10dp. */
fun Modifier.startupWords(s: EyeStartup, on: Boolean = true): Modifier =
    if (!on) this else graphicsLayer {
        if (s.t < EYE_DUR) { val f = eyeFrame(s.t); alpha = f.textAlpha; translationY = f.textRise * density; compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha }
    }

fun Modifier.startupBottom(s: EyeStartup, on: Boolean = true): Modifier =
    if (!on) this else graphicsLayer { if (s.t < EYE_DUR) { alpha = eyeFrame(s.t).bottomAlpha; compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha } }

/** The overlay's alpha on the everyday screen: solid while the eye plays, fading out as the words would come in. */
fun overlayAlpha(t: Float): Float = 1f - ease(seg(t, 3.75f, 4.35f))

/** Where the layers are made: off the main thread (the app); checks that step the clock by hand make them in place. */
object StartupLayers { @Volatile var background = true }

/** The eye's layers, each drawn once into a bitmap with its glow: afterwards a frame only moves, scales and fades them. */
internal class EyeLayers(size: Int) {
    val k = size / EyeArt.SIZE
    val open = layer(size) { paths(EyeArt.Open, it, glow = true) }
    val closed = layer(size) { paths(EyeArt.Closed.filterIndexed { i, _ -> i != 1 }, it, glow = true) }
    val globe = layer(size) { paths(EyeArt.Globe, it, glow = true) }
    val scarGlow = layer(size) { paths(listOf(EyeArt.Closed[1]), it, glow = true, sharp = false, fullGlow = true) }
    val scarTeal = layer(size) { paths(listOf(EyeArt.Closed[1]), it, glow = false) }
    val scarWhite = layer(size) { paths(listOf(EyeArt.Closed[1]), it, glow = false, white = true) }
    /** The soft halo, baked: a gradient this wide drawn every frame costs a slow phone more than a frame. */
    /** Half the eye's resolution: the halo is a smooth gradient, and drawn scaled up it is the same to the eye at a quarter of the pixels. */
    val haloScale = 0.5f
    val haloSize = Math.ceil(2.0 * EyeArt.HALO_R * k * haloScale).toInt().coerceAtLeast(1)
    val halo: android.graphics.Bitmap = android.graphics.Bitmap.createBitmap(haloSize, haloSize, android.graphics.Bitmap.Config.ARGB_8888).also { bmp ->
        val stops = EyeArt.haloStops()
        val shader = android.graphics.RadialGradient(
            haloSize / 2f, haloSize / 2f, haloSize / 2f,
            IntArray(stops.size) { EyeInkDark.copy(alpha = stops[it].second).toArgb() }, FloatArray(stops.size) { stops[it].first },
            android.graphics.Shader.TileMode.CLAMP,
        )
        android.graphics.Canvas(bmp).drawPaint(android.graphics.Paint().apply { this.shader = shader })
    }

    private fun layer(size: Int, draw: (android.graphics.Canvas) -> Unit): android.graphics.Bitmap {
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        c.scale(k, k); c.translate(-EyeArt.LEFT, -EyeArt.TOP)
        draw(c)
        return bmp
    }

    private fun paths(art: List<EyePath>, c: android.graphics.Canvas, glow: Boolean, sharp: Boolean = true, fullGlow: Boolean = false, white: Boolean = false) {
        val list = androidPaths(art)
        val (blur, ink) = eyePaints(EyeInkDark)
        if (white) ink.color = android.graphics.Color.WHITE
        if (glow) {
            val n = if (fullGlow) 255 else 140 // 0.55 as the mock-up's glow
            c.saveLayerAlpha(EyeArt.LEFT - 100f, EyeArt.TOP - 100f, EyeArt.LEFT + EyeArt.SIZE + 100f, EyeArt.TOP + EyeArt.SIZE + 100f, n)
            list.forEach { c.drawPath(it, blur) }
            c.restore()
        }
        if (sharp) list.forEach { c.drawPath(it, ink) }
    }
}

/**
 * The eye of the startup, from the owner's shapes: cached layers moved by the clock [EyeStartup.t] (read in draw only).
 * The layers are drawn off the main thread; until they are ready the plain resting eye shows at once (never a blank).
 */
@Composable
fun StartupEye(s: EyeStartup, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
        val px = with(androidx.compose.ui.platform.LocalDensity.current) { minOf(maxWidth, maxHeight).roundToPx() }.coerceAtLeast(1)
        remember(s) { s.expectLayers() }
        val layers: EyeLayers? = if (StartupLayers.background) {
            val made by androidx.compose.runtime.produceState<EyeLayers?>(null, px) {
                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { EyeLayers(px) }
                s.layersBuilt()
            }
            made
        } else remember(px) { EyeLayers(px).also { s.layersBuilt() } }
        if (layers == null) MimirEye(EyeMode.Normal, Modifier.fillMaxSize())
        else StartupEyeDraw(s, layers, Modifier.fillMaxSize())
    }
}

@Composable
private fun StartupEyeDraw(s: EyeStartup, layers: EyeLayers, modifier: Modifier) {
    val paint = remember { android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG) }
    Spacer(modifier.drawWithCache {
        val px = size.minDimension.toInt().coerceAtLeast(1)
        val dx = (size.width - px) / 2f
        val dy = (size.height - px) / 2f
        var warmed = false
        onDrawBehind {
            val f = eyeFrame(s.t)
            val k = layers.k
            drawIntoCanvas { c ->
                val nc = c.nativeCanvas
                fun put(bmp: android.graphics.Bitmap, alpha: Float, sx: Float = 1f, sy: Float = 1f, px0: Float = 0f, py0: Float = 0f) {
                    if (alpha <= 0.002f) return
                    paint.alpha = (alpha * 255f + .5f).toInt().coerceIn(0, 255)
                    nc.save()
                    nc.translate(dx + (px0 - EyeArt.LEFT) * k, dy + (py0 - EyeArt.TOP) * k)
                    nc.scale(sx, sy)
                    nc.drawBitmap(bmp, -(px0 - EyeArt.LEFT) * k, -(py0 - EyeArt.TOP) * k, paint)
                    nc.restore()
                }
                // The first frames also touch every layer at 1/255 opacity (invisible), so the phone uploads
                // them now and no later frame stalls on it.
                if (!warmed) {
                    warmed = true
                    paint.alpha = 1
                    for (b in listOf(layers.open, layers.closed, layers.scarGlow, layers.scarTeal, layers.scarWhite, layers.globe)) nc.drawBitmap(b, dx, dy, paint)
                }
                paint.alpha = (f.halo * 255f + .5f).toInt().coerceIn(0, 255)
                nc.save(); nc.translate(dx + (EyeArt.HALO_X - EyeArt.HALO_R - EyeArt.LEFT) * k, dy + (EyeArt.HALO_Y - EyeArt.HALO_R - EyeArt.TOP) * k); nc.scale(1f / layers.haloScale, 1f / layers.haloScale)
                nc.drawBitmap(layers.halo, 0f, 0f, paint); nc.restore()
                put(layers.open, f.openAlpha, 1f, f.openScaleY, 284f, 232f)
                put(layers.closed, f.closedAlpha)
                put(layers.scarGlow, f.scarAlpha * f.scarGlow)
                put(layers.scarTeal, f.scarAlpha)
                put(layers.scarWhite, f.scarAlpha * f.scarFlash)
                put(layers.globe, f.globeAlpha, f.globeScale, f.globeScale, 492f, 438f)
            }
        }
    })
}
