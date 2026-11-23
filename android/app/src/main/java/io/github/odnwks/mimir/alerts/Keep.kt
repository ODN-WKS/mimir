package io.github.odnwks.mimir.alerts

/**
 * When the link-keeping service runs (N4b): while any task works or waits for the person, and
 * for [GRACE_MS] after that. Pure rules, so they are tested without a phone.
 */
class Keep(private val now: () -> Long = System::currentTimeMillis) {
    private val startedAt = now()
    private var busyAt = startedAt

    /**
     * Called every few seconds while the service runs: true = keep going, false = stop now.
     * While the PC is not connected the last counts are stale, so they count as nothing (the
     * grace runs and the 5-minute check takes over); one run never passes [MAX_RUN_MS].
     */
    fun tick(connected: Boolean, working: Int, waiting: Int): Boolean {
        val t = now()
        if (t - startedAt >= MAX_RUN_MS) return false
        if (connected && (working > 0 || waiting > 0)) busyAt = t
        return t - busyAt < GRACE_MS
    }

    companion object {
        const val GRACE_MS = 2 * 60_000L
        const val CHECK_MS = 10_000L
        /** A backstop: no run lasts longer than this. */
        const val MAX_RUN_MS = 6 * 60 * 60_000L

        /**
         * Whether to start the service: paired, the PC sends finished events (an older PC keeps
         * today's way: the 5-minute check only), the person kept "Show Working while a task runs" on (Android
         * needs the ongoing note for an instant link), and something of theirs works or waits.
         */
        fun shouldStart(paired: Boolean, pcFinishedEvents: Boolean, working: Int, waiting: Int, showWorking: Boolean = true): Boolean =
            paired && pcFinishedEvents && showWorking && (working > 0 || waiting > 0)
    }
}
