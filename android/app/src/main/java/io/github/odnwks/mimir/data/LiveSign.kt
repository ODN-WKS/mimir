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
    /** The task waits for the person (a question or a permission): silence is then expected. */
    val needsYou: Boolean = false,
    /** The PC has since said the turn is over (a newer header says it is not busy). */
    val turnEnded: Boolean = false,
) {
    companion object {
        /** Long thinking or a long command is quiet for this long on a healthy task. */
        const val QUIET_AFTER_MS = 60_000L
        /** "Your PC got it" shows at least this long. */
        const val GOT_IT_MS = 2_500L
        /** It stays up until the first step or the end of the turn, but never longer than this (an old PC sends no steps). */
        const val GOT_IT_HOLD_MS = 15_000L

        /** How long it has worked: "5 s", "1 min 20 s", "12 min". */
        fun workedFor(secs: Long): String = when {
            secs < 60 -> "$secs s"
            secs < 600 -> if (secs % 60 == 0L) "${secs / 60} min" else "${secs / 60} min ${secs % 60} s"
            else -> "${secs / 60} min"
        }
    }

    /** What is shown: the state [words] (announced when they change) and a ticking [timer] (never announced). */
    data class Shown(val words: String, val timer: String = "") {
        override fun toString(): String = if (timer.isEmpty()) words else "$words$timer"
    }

    /** The one line to show at [nowMs], or null when there is nothing to say. */
    fun line(nowMs: Long): String? = shown(nowMs)?.toString()

    fun shown(nowMs: Long): Shown? {
        val working = doing.isNotEmpty()
        if (sending && linkUp) return Shown("Sending…")
        if (working && !linkUp) return Shown("Not connected to your PC. It shows again when it is back.")
        if (working) {
            val waitingForPerson = needsYou || doing.startsWith("Waiting for your")
            val quiet = if (newsAt > 0) (nowMs - newsAt) / 1000 else 0
            if (!waitingForPerson && quiet * 1000 >= QUIET_AFTER_MS) return Shown("Still working; no news from your PC", " for ${workedFor(quiet)}")
            val took = if (turnUnix > 0) nowMs / 1000 - turnUnix else -1
            return if (took >= 0) Shown(doing, " · ${workedFor(took)}") else Shown(doing)
        }
        // After a reply: held until the first step shows or the turn ends (a first step after 2.5 s leaves no blank gap).
        if (gotItAt > 0 && !turnEnded && nowMs - gotItAt < GOT_IT_HOLD_MS) return Shown("Your PC got it")
        if (gotItAt > 0 && nowMs - gotItAt < GOT_IT_MS) return Shown("Your PC got it")
        return null
    }
}
