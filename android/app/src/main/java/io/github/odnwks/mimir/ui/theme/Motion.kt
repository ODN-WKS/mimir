package io.github.odnwks.mimir.ui.theme

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Timings from the Motion board. Only position, size, fade and colour animate, never layout.
 * With the phone's "Remove animations" setting on, every change happens at once.
 */
object Motion {
    const val SCREEN_MS = 300
    const val CARD_OPEN_MS = 280
    const val GAUGE_MS = 600
    const val NEW_ITEM_MS = 220
    const val NEEDS_YOU_BAR_MS = 200
    const val SHEET_MS = 250
    /** An answered item turns into its receipt. */
    const val RECEIPT_MS = 150

    /** cubic-bezier(.2, 0, 0, 1), the boards' easing. */
    val Ease = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** A tween of [ms] with the boards' easing, or an instant change when motion is removed. */
    fun <T> spec(ms: Int, reduce: Boolean): FiniteAnimationSpec<T> =
        if (reduce) snap() else tween(durationMillis = ms, easing = Ease)

    /** True when Android's "Remove animations" is on (animator duration scale 0). */
    fun systemRemovesAnimations(context: Context): Boolean =
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}

/** True when animations should be skipped (the system setting, read by MainActivity). */
val LocalReduceMotion = staticCompositionLocalOf { false }

/** [Motion.spec] with the current reduce-motion setting. */
@Composable
@ReadOnlyComposable
fun <T> motionSpec(ms: Int): FiniteAnimationSpec<T> = Motion.spec(ms, LocalReduceMotion.current)
