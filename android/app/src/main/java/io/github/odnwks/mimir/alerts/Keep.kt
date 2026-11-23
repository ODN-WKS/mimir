package io.github.odnwks.mimir.alerts

/**
 * When the link-keeping service runs (N4b): while any task works or waits for the person, and
 * for [GRACE_MS] after that. Pure rules, so they are tested without a phone.
 */
class Keep(private val now: () -> Long = System::currentTimeMillis) {
    private var busyAt = now()

    /** Called every few seconds while the service runs: true = keep going, false = stop now. */
    fun tick(working: Int, waiting: Int): Boolean {
        val t = now()
        if (working > 0 || waiting > 0) busyAt = t
        return t - busyAt < GRACE_MS
    }

    companion object {
        const val GRACE_MS = 2 * 60_000L
        const val CHECK_MS = 10_000L

        /**
         * Whether to start the service: paired, the PC sends finished events (an older PC keeps
         * today's way: the 5-minute check only), and something works or waits.
         */
        fun shouldStart(paired: Boolean, pcFinishedEvents: Boolean, working: Int, waiting: Int): Boolean =
            paired && pcFinishedEvents && (working > 0 || waiting > 0)
    }
}
