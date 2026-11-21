package io.github.odnwks.mimir.data

/**
 * Answers (Allow, Don't allow, a choice) given while the PC can't be reached. They wait on the
 * phone like messages do and go out when the link is back; until then the thing answered no
 * longer asks here, and the Needs you page says an answer is waiting.
 * Each is (the ask's id, the call to send).
 */
object Held {
    /** A new answer replaces an earlier one for the same ask (the last tap is the one meant). */
    fun put(held: List<Pair<String, String>>, ask: String, call: String): List<Pair<String, String>> =
        held.filterNot { it.first == ask } + (ask to call)

    /** What is still worth sending: answers whose ask the PC still lists. */
    fun stillAsked(held: List<Pair<String, String>>, asked: Set<String>): List<Pair<String, String>> =
        held.filter { it.first in asked }

    /** The items that still ask on this phone: those with no answer waiting. */
    fun <T> asking(items: List<T>, held: List<Pair<String, String>>, id: (T) -> String): List<T> {
        val answered = held.map { it.first }.toSet()
        return items.filterNot { id(it) in answered }
    }
}
