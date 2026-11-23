package io.github.odnwks.mimir.data

/**
 * What a task shows from the moment the person sends until the answer is there, so it never looks
 * stuck: "Sending…", "Your PC got it", then what the PC says Claude is doing ("Thinking…",
 * "Reading main.rs", "Running a command", "Waiting for your OK") and how long it has worked. When
 * nothing came in for [QUIET_AFTER_MS] while it works, it says so; when the link is down, it says that.
 * The PC chooses the words ([doing]); this only picks and draws them.
 */
data class LiveSign(
    /** A message of this task is on its way to the PC right now. */
    val sending: Boolean = false,
    /** When the PC answered Ok to the last message (ms), 0 if it has not or it is long ago. */
    val gotItAt: Long = 0,
    /** What the PC says Claude is doing; "" when it says it is not working. */
    val doing: String = "",
    /** When this turn began (seconds since 1970), 0 if the PC didn't say. */
    val turnUnix: Long = 0,
    /** When anything last came in for this task (ms). */
    val newsAt: Long = 0,
    /** The link to the PC is up. */
    val linkUp: Boolean = true,
) {
    companion object {
        const val QUIET_AFTER_MS = 20_000L
        /** "Your PC got it" shows at least this long, and until the first real step. */
        const val GOT_IT_MS = 2_500L

        /** How long it has worked: "5 s", "1 min 20 s", "12 min". */
        fun workedFor(secs: Long): String = when {
            secs < 60 -> "$secs s"
            secs < 600 -> if (secs % 60 == 0L) "${secs / 60} min" else "${secs / 60} min ${secs % 60} s"
            else -> "${secs / 60} min"
        }
    }

    /** The one line to show at [nowMs], or null when there is nothing to say. */
    fun line(nowMs: Long): String? {
        val working = doing.isNotEmpty()
        if (sending && linkUp) return "Sending…"
        if (working && !linkUp) return "Not connected to your PC. It shows again when it is back."
        if (working) {
            val quiet = if (newsAt > 0) (nowMs - newsAt) / 1000 else 0
            if (quiet * 1000 >= QUIET_AFTER_MS) return "Still working; no news from your PC for $quiet s"
            val took = if (turnUnix > 0) nowMs / 1000 - turnUnix else -1
            return if (took >= 0) "$doing · ${workedFor(took)}" else doing
        }
        if (gotItAt > 0 && nowMs - gotItAt < GOT_IT_MS) return "Your PC got it"
        return null
    }
}
