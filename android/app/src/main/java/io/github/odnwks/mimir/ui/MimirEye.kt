package io.github.odnwks.mimir.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.PathParser

/** The eyes' teal on a dark ground, and on a light one (from make.py). */
val EyeInkDark = Color(0xFF47E6D4)
val EyeInkLight = Color(0xFF0B8A7F)

/**
 * The mode eye, drawn from the owner's shapes ([EyeArt]) so it stays sharp at any size: open for
 * normal mode, closed with the scar for a Whisper, closed with the small globe for a Whisper with
 * internet. As in the SVGs: a soft round halo, the shapes' glow (a blur under them), the shapes.
 */
@Composable
fun MimirEye(mode: EyeMode, modifier: Modifier = Modifier, light: Boolean = false) {
    val ink = if (light) EyeInkLight else EyeInkDark
    val glow = if (light) 0.30f else 0.55f
    val shapes = remember(mode) {
        androidPaths(when (mode) {
            EyeMode.Normal -> EyeArt.Open
            EyeMode.Whisper -> EyeArt.Closed
            EyeMode.WhisperWeb -> EyeArt.Closed + EyeArt.Globe
        })
    }
    val open = mode == EyeMode.Normal
    val (haloX, haloY) = EyeArt.haloCenter(open)
    val halo = remember(ink, glow, open) {
        Brush.radialGradient(
            colorStops = EyeArt.haloStops(peak = 0.24f * glow / 0.55f).map { (o, a) -> o to ink.copy(alpha = a) }.toTypedArray(),
            center = Offset(haloX, haloY), radius = EyeArt.HALO_R,
        )
    }
    val paints = remember(ink) { eyePaints(ink) }
    Canvas(modifier) {
        val k = size.minDimension / EyeArt.SIZE
        val dx = (size.width - size.minDimension) / 2f
        val dy = (size.height - size.minDimension) / 2f
        withTransform({
            translate(dx, dy)
            scale(k, k, pivot = Offset.Zero)
            translate(-EyeArt.LEFT, -EyeArt.TOP)
        }) {
            drawCircle(halo, radius = EyeArt.HALO_R, center = Offset(haloX, haloY))
            drawIntoCanvas { c ->
                shapes.forEach { c.nativeCanvas.drawPath(it, paints.first) }
                shapes.forEach { c.nativeCanvas.drawPath(it, paints.second) }
            }
        }
    }
}

/** The shapes as Android paths, built once (never per frame). */
internal fun androidPaths(art: List<EyePath>): List<android.graphics.Path> = art.map { p ->
    PathParser().parsePathString(p.d).toPath().apply {
        fillType = if (p.evenOdd) PathFillType.EvenOdd else PathFillType.NonZero
    }.asAndroidPath()
}

/** The glow paint (a blur under the shapes) and the sharp paint, in that order. */
internal fun eyePaints(ink: Color): Pair<android.graphics.Paint, android.graphics.Paint> {
    val sharp = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = ink.toArgb() }
    // Android turns a blur radius r into sigma 0.57735·r + 0.5 (in the view box's units here).
    val blur = android.graphics.Paint(sharp).apply {
        maskFilter = android.graphics.BlurMaskFilter((EyeArt.GLOW_SIGMA - 0.5f) / 0.57735f, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }
    return blur to sharp
}
