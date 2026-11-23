package io.github.odnwks.mimir.data

/**
 * Where each task's turn clock starts on this phone. The PC says when a turn began by its own clock; when
 * that clock is off from the phone's by more than [SKEW_S] seconds, the time worked would be wrong, so a
 * turn seen starting (the task was idle a moment before) counts from when this phone got its first header.
 */
class TurnClock {
    private val seen = HashMap<String, Pair<Long, Long>>()
    private val idle = HashSet<String>()

    /** A header of task [id] came in at [nowMs]: [busy] says a turn is on, [turnUnix] is the PC's start (0 if unsaid). */
    fun header(id: String, busy: Boolean, turnUnix: Long, nowMs: Long) = synchronized(this) {
        if (!busy || turnUnix <= 0) { idle += id; return }
        if (seen[id]?.first == turnUnix) return
        val nowS = nowMs / 1000
        val mine = if (id in idle) {
            // Seen starting: nothing has worked yet, so any large gap is the PC's clock.
            if (kotlin.math.abs(nowS - turnUnix) > SKEW_S) nowS else turnUnix
        } else minOf(turnUnix, nowS)
        seen[id] = turnUnix to mine
        idle -= id
    }

    /** The start to count from for task [id], in seconds since 1970 (the PC's [turnUnix] when nothing better is known). */
    fun start(id: String, turnUnix: Long): Long = synchronized(this) { seen[id]?.takeIf { it.first == turnUnix }?.second ?: turnUnix }

    fun forget(id: String) = synchronized(this) { seen.remove(id); idle.remove(id) }
    fun clear() = synchronized(this) { seen.clear(); idle.clear() }

    companion object { const val SKEW_S = 5L }
}
