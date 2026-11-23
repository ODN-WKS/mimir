package io.github.odnwks.mimir.link

/**
 * The link to the PC, in Rust (crates/phone-core). Calls and events cross as JSON in the PC's own
 * shapes (crates/proto); [Wire] builds and reads them.
 */
object Native {
    /** False when this phone has no build of the core: the link and Tor then stay off (see [NativeLink]). */
    val available: Boolean get() = io.github.odnwks.mimir.NativeGuard.available(io.github.odnwks.mimir.NativeGuard.PHONE_CORE)

    @JvmStatic external fun generateKey(): ByteArray
    /** A handle, or 0 if the network side can't open. */
    @JvmStatic external fun start(key: ByteArray, relays: Boolean): Long
    @JvmStatic external fun stop(handle: Long)
    @JvmStatic external fun id(handle: Long): String
    /** "host:port" where the PC listens, for a direct connection (checks). */
    @JvmStatic external fun addHint(handle: Long, addr: String)
    /** `{"words": [...]}` or `{"error": "..."}`. */
    @JvmStatic external fun pairStart(handle: Long, code: String, phoneName: String): String
    /** `{"pc_id", "pc_name"}` once the owner confirmed on the PC, or `{"error"}`. */
    @JvmStatic external fun pairFinish(handle: Long, code: String, timeoutMs: Long): String
    /** "" once connected, or why not. */
    @JvmStatic external fun connect(handle: Long, pcId: String): String
    /** The call's number, or -1. */
    @JvmStatic external fun call(handle: Long, json: String): Long
    /** One event as JSON, or null after [timeoutMs]. */
    @JvmStatic external fun nextEvent(handle: Long, timeoutMs: Long): String?
    @JvmStatic external fun connected(handle: Long): Boolean
    /** The phone's network changed, or the app came back to the front (iroh can't see this itself on Android). */
    @JvmStatic external fun networkChanged(handle: Long)
    /** Why the last session ended ("not paired" once the PC removed this phone), or "". */
    @JvmStatic external fun endedBecause(handle: Long): String

    // Tor, for a Whisper with internet: the handle is 0 when it couldn't start.
    @JvmStatic external fun torNew(stateDir: String, cacheDir: String): Long
    /** "" once Tor is ready, else why not. Waits. */
    @JvmStatic external fun torConnect(handle: Long, timeoutMs: Long): String
    /** How far connecting has got, in percent. */
    @JvmStatic external fun torProgress(handle: Long): Long
    /** `{"status","location","type","body"}` or `{"error"}`. Waits. */
    @JvmStatic external fun torGet(handle: Long, url: String, headersJson: String, timeoutMs: Long, maxBytes: Long): String
    @JvmStatic external fun torCancel(handle: Long)
    @JvmStatic external fun torStop(handle: Long)
}
