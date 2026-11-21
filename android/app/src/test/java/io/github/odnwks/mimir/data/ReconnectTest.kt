package io.github.odnwks.mimir.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stage 1, 4.4: how the link tries the PC again. */
class ReconnectTest {
    private var t = 1_000_000L
    private val r = Reconnect { t }

    @Test fun it_backs_off_3_6_12_then_stays_at_30_seconds() {
        assertTrue("the first try is at once", r.due(onScreen = true, busy = false))
        val waits = ArrayList<Long>()
        repeat(6) {
            r.tried(); r.failed()
            waits += r.waitMs()
            t += r.waitMs() - 1
            assertFalse(r.due(true, false))
            t += 1
            assertTrue(r.due(true, false))
        }
        assertEquals(listOf(3_000L, 6_000L, 12_000L, 30_000L, 30_000L, 30_000L), waits)
    }

    @Test fun a_network_change_or_the_app_in_front_tries_at_once() {
        repeat(4) { r.tried(); r.failed() }
        assertFalse(r.due(true, false))
        r.soon()
        assertTrue(r.due(true, false))
        r.tried(); r.failed()
        assertEquals("and from 3 s again", 3_000L, r.waitMs())
    }

    @Test fun with_the_app_closed_only_a_check_or_work_under_way_keeps_trying() {
        // Battery saver, nothing going on: no tries while the app is closed.
        assertFalse(r.due(onScreen = false, busy = false))
        // The 5-minute check (or a wake-up push) wants it for 25 s.
        r.want(25_000)
        assertTrue(r.due(false, false))
        t += 26_000
        assertFalse(r.due(false, false))
        // A task the PC still works on keeps it for 10 minutes after the app left the screen.
        val busy = Reconnect { t }
        assertTrue(busy.due(true, true))
        t += 1
        assertTrue(busy.due(false, true))
        t += 9 * 60_000
        assertTrue(busy.due(false, true))
        t += 61_000
        assertFalse(busy.due(false, true))
        // Back in front: tries again.
        assertTrue(busy.due(true, true))
    }
}
