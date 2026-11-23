package io.github.odnwks.mimir.online

import org.json.JSONObject
import java.io.File

/**
 * Tor for a Whisper with internet: the phone core's built-in Tor client (Arti). It starts when such
 * a Whisper starts and stops when it ends. Only Tor's public list of relays is kept between
 * Whispers ([cacheDir]); everything else ([stateDir], with the chosen entry relays) is wiped at each
 * start and stop, so leftovers from an app closed mid-Whisper go too. It never falls back to a
 * direct connection.
 */
object TorLink {
    /** The phone core's Tor calls (swapped in checks). */
    interface Core {
        fun new(state: String, cache: String): Long
        fun connect(h: Long, timeoutMs: Long): String
        fun progress(h: Long): Long
        fun get(h: Long, url: String, headers: String, timeoutMs: Long, maxBytes: Long): String
        fun cancel(h: Long)
        fun stop(h: Long)
    }

    object Native : Core {
        private val n = io.github.odnwks.mimir.link.Native
        // No phone core on this phone: Tor can't start (handle 0), the same as when it won't open.
        override fun new(state: String, cache: String) = if (n.available) n.torNew(state, cache) else 0L
        override fun connect(h: Long, timeoutMs: Long) = if (n.available) n.torConnect(h, timeoutMs) else "Private lookups aren't available on this device. Use your PC for them."
        override fun progress(h: Long) = if (n.available) n.torProgress(h) else 0L
        override fun get(h: Long, url: String, headers: String, timeoutMs: Long, maxBytes: Long) = if (n.available) n.torGet(h, url, headers, timeoutMs, maxBytes) else "{\"error\":\"Private lookups aren't available on this device. Use your PC for them.\"}"
        override fun cancel(h: Long) { if (n.available) n.torCancel(h) }
        override fun stop(h: Long) { if (n.available) n.torStop(h) }
    }

    @Volatile var core: Core = Native
    @Volatile var stateDir: File? = null
    @Volatile var cacheDir: File? = null

    private var handle = 0L
    private var ready = false
    private var connecting: Thread? = null
    private val lock = Object()

    /** The first-connect time just measured on this phone (ms; 0 until there is one). */
    @Volatile var lastConnectMs = 0L
        private set

    /** The app's folders: state in [files] (wiped), the relay list in [noBackup] (kept). */
    fun setUp(files: File, noBackup: File) {
        stateDir = File(files, "tor/state")
        cacheDir = File(noBackup, "tor/cache")
        // Anything left by an app closed mid-Whisper goes now.
        wipeState()
        Net.torTransport = ::transport
    }

    private fun wipeState() {
        stateDir?.let { d -> d.walkBottomUp().forEach { it.delete() }; d.mkdirs() }
    }

    /** Starts connecting in the background (a Whisper with internet began). */
    fun start() = synchronized(lock) {
        // A connection that gave up is tried afresh on the next lookup.
        if (handle != 0L && !ready && connecting?.isAlive != true) { core.stop(handle); handle = 0L }
        if (handle != 0L) return
        wipeState()
        val s = stateDir ?: return
        val c = cacheDir ?: return
        c.mkdirs()
        handle = core.new(s.path, c.path)
        if (handle == 0L) return
        val h = handle
        connecting = Thread({
            val t0 = System.currentTimeMillis()
            val why = core.connect(h, CONNECT_MS)
            synchronized(lock) {
                if (handle == h && why.isEmpty()) { ready = true; lastConnectMs = System.currentTimeMillis() - t0 }
                lock.notifyAll()
            }
        }, "tor-connect").apply { isDaemon = true; start() }
    }

    /** Whether Tor is ready for lookups. */
    val isReady: Boolean get() = synchronized(lock) { ready }

    /** Whether Tor is still trying to connect (false once ready, given up, cancelled or stopped). */
    val connectingNow: Boolean get() = synchronized(lock) { !ready && handle != 0L && connecting?.isAlive == true }

    /** How far connecting has got, in percent. */
    fun progress(): Int = synchronized(lock) { if (handle == 0L) 0 else core.progress(handle).toInt() }

    /** Waits up to [ms] for Tor (starting it if needed); true once ready. */
    fun await(ms: Long): Boolean {
        start()
        val until = System.currentTimeMillis() + ms
        synchronized(lock) {
            while (!ready && handle != 0L && connecting?.isAlive == true) {
                val left = until - System.currentTimeMillis()
                if (left <= 0) break
                lock.wait(minOf(left, 250))
            }
            return ready
        }
    }

    /** Cancel: what's waiting on Tor stops (the Whisper goes on; lookups say Tor wasn't reached). */
    fun cancel() = synchronized(lock) {
        if (handle != 0L) core.cancel(handle)
        stop()
    }

    /** The Whisper ended: Tor stops, and its state is wiped. */
    fun stop() = synchronized(lock) {
        if (handle != 0L) core.stop(handle)
        handle = 0L
        ready = false
        lock.notifyAll()
        wipeState()
        // What the Whisper searched isn't kept either.
        Search.forget()
    }

    /** One read through Tor, for [Net.get]. */
    fun transport(r: Net.Request): Net.Reply {
        val h = synchronized(lock) { handle.takeIf { ready } } ?: if (await(CONNECT_MS)) synchronized(lock) { handle } else 0L
        if (h == 0L) throw Net.Refused("the hidden route is out of reach just now")
        val o = JSONObject(core.get(h, r.url.toString(), JSONObject(r.headers as Map<*, *>).toString(), r.timeoutMs.toLong(), r.maxBytes.toLong()))
        if (o.has("error")) throw java.io.IOException(o.getString("error"))
        val body = o.optString("body").toByteArray()
        return Net.Reply(o.optInt("status"), o.optString("location").takeIf { it.isNotEmpty() && it != "null" }, o.optString("type"), body)
    }

    /** How long a first connection may take before it's given up. */
    const val CONNECT_MS = 120_000L
}
