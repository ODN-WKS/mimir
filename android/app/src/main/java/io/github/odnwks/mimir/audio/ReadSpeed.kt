package io.github.odnwks.mimir.audio

import kotlin.math.roundToInt

/**
 * The read-aloud speed people choose (Settings › Voice, and a chat's or task's sheet): 50% to
 * 150% in steps of 5%, on top of each voice's own pace (Ingrid 1.10, the phone's own
 * voice 1.0) and of the PC's spoken updates.
 */
object ReadSpeed {
    const val MIN = 0.5f
    const val MAX = 1.5f
    const val STEP = 0.05f
    /** The slider's stops between the two ends (21 places in all). */
    const val STEPS = 19

    /** The nearest step, within 50% to 150% (an older saved speed, such as 2×, lands on one too). */
    fun snap(speed: Float): Float {
        val v = if (speed.isNaN()) 1f else speed.coerceIn(MIN, MAX)
        return (v / STEP).roundToInt() / 20f
    }

    fun percent(speed: Float): String = "${(snap(speed) * 100).roundToInt()}%"
}
