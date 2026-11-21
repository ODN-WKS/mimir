package io.github.odnwks.mimir.data

/**
 * When the link tries to reach the PC again (Stage 1, 4.4):
 *
 * *   at once when the network changes or the app comes to the front ([soon]);
 * *   otherwise after 3 s, then 6, 12 and 30 s, staying at 30 s, so a PC that's off isn't asked
 *     every 3 s forever;
 * *   with the app closed, the owner's "Staying connected" choice decides: the link is tried only
 *     while something wants it ([want]: the 5-minute check, a wake-up push), and for up to
 *     10 minutes after the app left the screen while the PC still works on something.
 */
class Reconnect(private val now: () -> Long = System::currentTimeMillis) {
    private var fails = 0
    private var lastTry = 0L
    private var wantedUntil = 0L
    private var leftAt = -1L

    /** How long to wait after the last try before the next one. */
    fun waitMs(): Long = if (fails == 0) 0 else STEPS[minOf(fails - 1, STEPS.lastIndex)]

    /** Whether to try now. [onScreen]: the app is in front; [busy]: the PC is working on something for this phone. */
    fun due(onScreen: Boolean, busy: Boolean): Boolean {
        val t = now()
        if (onScreen) leftAt = -1L else if (leftAt < 0) leftAt = t
        val wanted = onScreen || t < wantedUntil || (busy && leftAt >= 0 && t - leftAt < KEEP_AFTER_LEAVING_MS)
        return wanted && t - lastTry >= waitMs()
    }

    fun tried() { lastTry = now() }
    fun failed() { fails++ }
    fun connected() { fails = 0 }

    /** The network changed or the app came to the front: try at once, and from 3 s again. */
    fun soon() { fails = 0; lastTry = 0 }

    /** Something needs the PC for the next [ms] (the 5-minute check, a wake-up push), with the app closed. */
    fun want(ms: Long) { wantedUntil = maxOf(wantedUntil, now() + ms); soon() }

    companion object {
        val STEPS = longArrayOf(3_000, 6_000, 12_000, 30_000)
        const val KEEP_AFTER_LEAVING_MS = 10 * 60_000L
    }
}
