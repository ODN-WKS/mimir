package io.github.odnwks.mimir.alerts

import io.github.odnwks.mimir.link.LFinished

/**
 * What the phone does with "a task finished" items from the PC: each id once, never old ones,
 * and only generic words (no project, no command, no result).
 */
object FinishedNotes {
    const val MAX_AGE_S = 60 * 60L
    const val KEEP_IDS = 100

    /**
     * The items to tell the person about. [seen] holds ids already handled. The first time ever
     * ([seeded] false) nothing is shown: a new pairing must not announce old work. Items the owner
     * stopped, and items older than an hour, are marked seen but not shown.
     */
    fun fresh(items: List<LFinished>, seen: Set<String>, seeded: Boolean, nowS: Long): List<LFinished> {
        if (!seeded) return emptyList()
        return items.filter { it.id !in seen }.distinctBy { it.id }
            .filter { it.how != "Stopped" && nowS - it.at <= MAX_AGE_S }
    }

    /** The ids to remember after [items] came in: all of them, newest kept, at most [KEEP_IDS]. */
    fun remember(seen: Set<String>, items: List<LFinished>): Set<String> =
        (seen + items.map { it.id }).let { all -> if (all.size > KEEP_IDS) all.toList().takeLast(KEEP_IDS).toSet() else all }

    /** The generic words: title and body. */
    fun words(how: String): Pair<String, String> =
        if (how == "Failed") "Mímir — a task needs a look" to "Open the app to see it"
        else "Mímir — a task finished" to "Open the app to see it"
}
