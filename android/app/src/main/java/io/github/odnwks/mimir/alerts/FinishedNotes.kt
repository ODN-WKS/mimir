package io.github.odnwks.mimir.alerts

import android.content.Context
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
    fun fresh(items: List<LFinished>, seen: Collection<String>, seeded: Boolean, nowS: Long): List<LFinished> {
        if (!seeded) return emptyList()
        val known = seen.toHashSet()
        return items.filter { it.id !in known }.distinctBy { it.id }
            .filter { it.how != "Stopped" && nowS - it.at <= MAX_AGE_S }
    }

    /** The ids to remember after [items] came in, in the order they came: the oldest drop first, at most [KEEP_IDS]. */
    fun remember(seen: List<String>, items: List<LFinished>): List<String> =
        (seen + items.map { it.id }).distinct().takeLast(KEEP_IDS)

    /** The generic words: title and body. */
    fun words(how: String): Pair<String, String> =
        if (how == "Failed") "Mímir: A task needs a look" to "Open the app to see it"
        else "Mímir: A task finished" to "Open the app to see it"
}

/** What this phone has already told about, kept in order in its settings; gone with the pairing. */
class FinishedSeen(context: Context) {
    private val prefs = context.getSharedPreferences("finished_notified", Context.MODE_PRIVATE)

    /** The items to show now; every id of [items] is remembered (the first list ever only seeds). */
    @Synchronized
    fun take(items: List<LFinished>, nowS: Long): List<LFinished> {
        val seen = prefs.getString("ids", "").orEmpty().split('\n').filter { it.isNotEmpty() }
        val fresh = FinishedNotes.fresh(items, seen, prefs.getBoolean("seeded", false), nowS)
        prefs.edit().putString("ids", FinishedNotes.remember(seen, items).joinToString("\n")).putBoolean("seeded", true).apply()
        return fresh
    }

    @Synchronized
    fun clear() { prefs.edit().clear().apply() }
}
