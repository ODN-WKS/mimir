package io.github.odnwks.mimir.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHU-02, PHD-28: cancel mid-pairing resets the guard; no second attempt possible without
 * user action.
 */
class PairingCancelTest {

    @Test
    fun `cancel mid-pairing resets AtomicBoolean to false`() {
        val guard = PairingGuard()
        assertTrue("first tryStart succeeds", guard.tryStart())
        assertTrue("isPairing is true while in progress", guard.isPairing)

        guard.cancel()

        assertFalse("isPairing is false after cancel", guard.isPairing)
    }

    @Test
    fun `no second attempt possible without cancel`() {
        val guard = PairingGuard()
        assertTrue("first tryStart succeeds", guard.tryStart())
        assertFalse("second tryStart blocked", guard.tryStart())
        assertFalse("still blocked on third try", guard.tryStart())
    }

    @Test
    fun `after cancel a new attempt is allowed`() {
        val guard = PairingGuard()
        assertTrue(guard.tryStart())
        guard.cancel()
        assertTrue("tryStart allowed again after cancel", guard.tryStart())
    }
}
