package io.github.odnwks.mimir.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which reply the PC's voice is reading to this phone (asked for, or playing), by the same id the
 * Listen buttons use. The buttons show Stop while it is set, a second tap stops it at once (the
 * sound and the notification), taps in quick succession count once, and an answer that arrives
 * after a stop is dropped, so nothing queues up and nothing restarts later. Pure rules, so they
 * are tested without a phone.
 */
object PcReading {
    private const val QUIET_MS = 400L
    private val _id = MutableStateFlow<String?>(null)
    val id: StateFlow<String?> = _id.asStateFlow()

    /** Stops the player (the app sets it). */
    @Volatile var stopper: (() -> Unit)? = null
    /** Milliseconds, any clock that only goes forward (checks move it). */
    @Volatile var clock: () -> Long = { System.nanoTime() / 1_000_000 }

    private val lock = Any()
    private var token = 0
    private var active = 0
    private var lastTap = Long.MIN_VALUE / 2
    private var played = false

    /** True if a tap now is too close to the one before: it is ignored. */
    fun tooSoon(): Boolean = synchronized(lock) {
        val now = clock()
        if (now - lastTap < QUIET_MS) true else { lastTap = now; false }
    }

    /** A reply was asked for. Returns its ticket, or 0 when it is already being read (ignored). */
    fun begin(id: String): Int = synchronized(lock) {
        if (_id.value == id) return 0
        token += 1
        active = token
        played = false
        _id.value = id
        active
    }

    /** Whether [ticket] is still the one wanted (false after a stop or a newer reply). */
    fun wanted(ticket: Int): Boolean = synchronized(lock) { ticket != 0 && ticket == active }

    /** [ticket]'s reading ended or failed. */
    fun end(ticket: Int) = synchronized(lock) { if (ticket != 0 && ticket == active) clear() }

    /** The owner's Stop: the sound and its notification end now, and a late answer is dropped. */
    fun stop() {
        val had = synchronized(lock) { (_id.value != null).also { clear() } }
        if (had) stopper?.invoke()
    }

    /** The player's report: a reading that played and then stopped (or gave way to another track) has ended. */
    fun onPlayer(trackId: String?, playing: Boolean) = synchronized(lock) {
        val mine = _id.value ?: return@synchronized
        if (trackId == "msg:$mine" && playing) { played = true; return@synchronized }
        if (played && !(trackId == "msg:$mine" && playing)) clear()
    }

    private fun clear() { active = 0; played = false; _id.value = null }
}
