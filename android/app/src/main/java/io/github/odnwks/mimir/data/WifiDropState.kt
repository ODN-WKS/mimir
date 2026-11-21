package io.github.odnwks.mimir.data

/**
 * PHW-15: Tracks a Wi-Fi drop that happened during an active answer so the phone can show
 * "Waiting for your PC…" within 5 s of the drop, rather than silently waiting 40 s.
 *
 * Usage:
 * 1. Call [answerStarted] when the PC starts writing.
 * 2. Call [wifiDropped] when the network is lost while answering.
 * 3. Call [wifiRestored] when the network comes back.
 * 4. Call [answerReceived] when the answer arrives (clear state).
 * 5. Read [showWaitingMessage] to know whether to show the banner.
 */
class WifiDropState(private val clock: () -> Long = System::currentTimeMillis) {
    private var answering = false
    private var dropTime: Long? = null

    fun answerStarted() { answering = true }

    fun wifiDropped() {
        if (answering) dropTime = clock()
    }

    fun wifiRestored() { dropTime = null }

    fun answerReceived() {
        answering = false
        dropTime = null
    }

    /**
     * True when a Wi-Fi drop happened during an active answer and within
     * [DROP_WINDOW_MS] of the drop (PHW-15: show within 5 s).
     */
    val showWaitingMessage: Boolean
        get() = dropTime?.let { clock() - it <= DROP_WINDOW_MS } ?: false

    companion object {
        /** How long after the Wi-Fi drop to show the waiting banner (ms). */
        const val DROP_WINDOW_MS = 5_000L
        /** The message shown to the owner. */
        const val WAITING_MESSAGE = "Waiting for your PC…"
    }
}
