package io.github.odnwks.mimir.ui

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Guards against double pairing attempts (PHU-02, PHD-28).
 *
 * - [tryStart] returns true once and sets the flag; a second call returns false
 *   until [cancel] or [reset] clears it.
 * - [cancel] clears the flag so a fresh attempt is allowed.
 * - [isPairing] reads the current state.
 */
class PairingGuard {
    private val pairing = AtomicBoolean(false)

    /** True if a pairing attempt is in progress. */
    val isPairing: Boolean get() = pairing.get()

    /**
     * Atomically tries to start a new pairing attempt.
     * Returns true if the attempt was started (flag was false → now true).
     * Returns false if already pairing (flag was already true).
     */
    fun tryStart(): Boolean = pairing.compareAndSet(false, true)

    /** Cancels the current attempt: resets the flag to false. */
    fun cancel() {
        pairing.set(false)
    }

    /** Alias for [cancel]. */
    fun reset() = cancel()
}
