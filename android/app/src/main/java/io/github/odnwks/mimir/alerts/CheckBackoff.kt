package io.github.odnwks.mimir.alerts

/**
 * PHD-14: Tracks consecutive failed PC checks and backs off the alarm interval so a PC that is
 * off for a long time is not polled every 5 minutes.
 *
 * *   First 2 misses: keep the normal 5-minute interval.
 * *   After 3 consecutive misses: randomise between 30 and 60 minutes.
 * *   Reset to 5 minutes on the first success.
 */
class CheckBackoff(private val random: () -> Int = { (30..60).random() }) {
    private var consecutiveMisses = 0

    fun missed() { consecutiveMisses++ }
    fun succeeded() { consecutiveMisses = 0 }

    /** Interval in ms before the next alarm. */
    fun nextIntervalMs(): Long =
        if (consecutiveMisses >= 3) random() * 60_000L else Stay.EVERY_MS

    val misses: Int get() = consecutiveMisses
}
