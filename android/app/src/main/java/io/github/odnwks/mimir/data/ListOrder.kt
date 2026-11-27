package io.github.odnwks.mimir.data

import io.github.odnwks.mimir.standalone.LocalChat

/**
 * The order of every list of projects, chats and tasks: newest first by the person's last
 * message. Replies, steps, finishes and Mímir's own messages move nothing. With no message of
 * the person, creation time. The PC's `order` module has the same rules.
 */
object ListOrder {
    /** A task, in ms: [youMs] is 0 from an older PC or with no message of the person. */
    fun task(youMs: Long, createdUnix: Long): Long = if (youMs > 0) youMs else createdUnix * 1000

    /** A project, from its tasks' (youMs, createdUnix); with no task, last. */
    fun project(tasks: List<Pair<Long, Long>>): Long =
        tasks.maxOfOrNull { it.first }?.takeIf { it > 0 } ?: (tasks.maxOfOrNull { it.second } ?: 0L) * 1000

    /** One of this phone's chats: the person's newest message; with no times, when it began or [LocalChat.at]. */
    fun chat(c: LocalChat): Long = c.turns.filter { it.user && it.at > 0 }.maxOfOrNull { it.at }
        ?: c.turns.filter { it.at > 0 }.minOfOrNull { it.at } ?: c.at
}
