package io.github.odnwks.mimir.data

/** What a call that was waiting for the PC is told when the link goes away before the PC answered. */
/** What a new task kept on the phone says while the PC is away. */
const val WAITING_WORDS = "Waiting for your PC"
const val WENT_AWAY = "Your PC went away, try again."

/** The calls sent to the PC that have not been answered yet, by call id. */
class CallBook {
    private val waiting = HashMap<Long, (Any?, String?) -> Unit>()

    @Synchronized fun register(id: Long, onReply: (Any?, String?) -> Unit) { waiting[id] = onReply }

    /** The PC answered call [id]: who was waiting, if anyone (they are not waiting any more). */
    @Synchronized fun answered(id: Long): ((Any?, String?) -> Unit)? = waiting.remove(id)

    /** The link went away: every call still waiting is told so, and the book is empty. */
    fun failAll() {
        val all = synchronized(this) { waiting.values.toList().also { waiting.clear() } }
        all.forEach { cb -> runCatching { cb(null, WENT_AWAY) } }
    }

    @Synchronized fun size() = waiting.size
}

/**
 * Things kept on the phone until the PC has answered Ok (messages, replies, recordings). One goes
 * out at a time per id; when the link goes away before the answer, it stays and is sent again
 * (each has an id, so a resend is harmless).
 */
class Delivery {
    private val onItsWay = HashSet<String>()

    /**
     * Sends item [id] with [call] (it hands the question to the link and gives false if the link
     * refuses). [finished] runs when the PC answered, with its answer or its error, but not when the link
     * went away: the item stays then. Returns null if it is already on its way, else what [call] gave.
     */
    /** Whether item [id] is out with the link right now (handed over, not yet answered). */
    fun active(id: String): Boolean = synchronized(this) { id in onItsWay }

    fun start(id: String, call: ((Any?, String?) -> Unit) -> Boolean, finished: (Any?, String?) -> Unit): Boolean? {
        synchronized(this) { if (!onItsWay.add(id)) return null }
        val handed = call { ok, err ->
            synchronized(this) { onItsWay.remove(id) }
            if (err != WENT_AWAY) finished(ok, err)
        }
        if (!handed) synchronized(this) { onItsWay.remove(id) }
        return handed
    }
}
