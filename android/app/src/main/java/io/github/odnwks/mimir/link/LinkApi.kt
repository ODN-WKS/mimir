package io.github.odnwks.mimir.link

/** The phone's link to the PC (iroh in the phone core): what [io.github.odnwks.mimir.data.LinkStation] uses, so a check can stand in a link of its own. */
interface LinkApi {
    fun start(key: ByteArray, relays: Boolean): Long
    fun stop(handle: Long)
    fun addHint(handle: Long, addr: String)
    fun pairStart(handle: Long, code: String, phoneName: String): String
    fun pairFinish(handle: Long, code: String, timeoutMs: Long): String
    fun connect(handle: Long, pcId: String): String
    fun call(handle: Long, json: String): Long
    fun nextEvent(handle: Long, timeoutMs: Long): String?
    fun connected(handle: Long): Boolean
    fun networkChanged(handle: Long)
    fun endedBecause(handle: Long): String
    /** False when this phone has no phone core (the link can never open). */
    val available: Boolean get() = true
}

/** The real link. With no phone core on this phone it stays closed and says so in one line. */
object NativeLink : LinkApi {
    private val off = io.github.odnwks.mimir.NativeGuard.LINK_OFF
    private val on get() = Native.available
    override val available get() = on
    override fun start(key: ByteArray, relays: Boolean) = if (on) Native.start(key, relays) else 0L
    override fun stop(handle: Long) { if (on) Native.stop(handle) }
    override fun addHint(handle: Long, addr: String) { if (on) Native.addHint(handle, addr) }
    override fun pairStart(handle: Long, code: String, phoneName: String) = if (on) Native.pairStart(handle, code, phoneName) else "{\"error\":\"$off\"}"
    override fun pairFinish(handle: Long, code: String, timeoutMs: Long) = if (on) Native.pairFinish(handle, code, timeoutMs) else "{\"error\":\"$off\"}"
    override fun connect(handle: Long, pcId: String) = if (on) Native.connect(handle, pcId) else off
    override fun call(handle: Long, json: String) = if (on) Native.call(handle, json) else -1L
    override fun nextEvent(handle: Long, timeoutMs: Long) = if (on) Native.nextEvent(handle, timeoutMs) else null
    override fun connected(handle: Long) = on && Native.connected(handle)
    override fun networkChanged(handle: Long) { if (on) Native.networkChanged(handle) }
    override fun endedBecause(handle: Long) = if (on) Native.endedBecause(handle) else ""
}
