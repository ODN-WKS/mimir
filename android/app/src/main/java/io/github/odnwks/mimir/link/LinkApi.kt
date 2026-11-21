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
}

/** The real link. */
object NativeLink : LinkApi {
    override fun start(key: ByteArray, relays: Boolean) = Native.start(key, relays)
    override fun stop(handle: Long) = Native.stop(handle)
    override fun addHint(handle: Long, addr: String) = Native.addHint(handle, addr)
    override fun pairStart(handle: Long, code: String, phoneName: String) = Native.pairStart(handle, code, phoneName)
    override fun pairFinish(handle: Long, code: String, timeoutMs: Long) = Native.pairFinish(handle, code, timeoutMs)
    override fun connect(handle: Long, pcId: String) = Native.connect(handle, pcId)
    override fun call(handle: Long, json: String) = Native.call(handle, json)
    override fun nextEvent(handle: Long, timeoutMs: Long) = Native.nextEvent(handle, timeoutMs)
    override fun connected(handle: Long) = Native.connected(handle)
    override fun networkChanged(handle: Long) = Native.networkChanged(handle)
    override fun endedBecause(handle: Long) = Native.endedBecause(handle)
}
