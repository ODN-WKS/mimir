package io.github.odnwks.mimir.alerts

import android.app.Service
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.odnwks.mimir.link.LFinished
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/** N4b review fixes: the service never runs for ever, never restarts itself in the background, remembers ids in order, forgets them with the pairing. */
@RunWith(RobolectricTestRunner::class)
class N4bReviewTest {
    private var t = 1_000_000L

    @Test fun whileThePcIsAwayStaleCountsDoNotKeepTheServiceAlive() {
        val k = Keep { t }
        assertTrue(k.tick(connected = false, working = 3, waiting = 2))
        t += Keep.GRACE_MS - 1
        assertTrue(k.tick(connected = false, working = 3, waiting = 2))
        t += 2
        assertFalse("grace over although the old counts still say busy", k.tick(connected = false, working = 3, waiting = 2))
    }

    @Test fun aLinkThatComesBackKeepsItGoing() {
        val k = Keep { t }
        t += Keep.GRACE_MS - 1
        assertTrue(k.tick(connected = true, working = 1, waiting = 0))
        t += Keep.GRACE_MS - 1
        assertTrue(k.tick(connected = false, working = 1, waiting = 0))
    }

    @Test fun oneRunNeverPassesTheHardCapEvenWhenBusy() {
        val k = Keep { t }
        t += Keep.MAX_RUN_MS - 1
        assertTrue(k.tick(true, 1, 0))
        t += 2
        assertFalse(k.tick(true, 1, 0))
    }

    @Test fun theServiceDoesNotAskToBeRestartedByAndroid() {
        val controller = Robolectric.buildService(LinkKeepService::class.java)
        val service = controller.create().get()
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1))
    }

    private fun f(id: String, at: Long = 10_000) = LFinished(id, "t", "Ok", at)

    @Test fun rememberedIdsKeepTheirOrderAndDropTheOldestFirst() {
        var seen = emptyList<String>()
        for (i in 1..150) seen = FinishedNotes.remember(seen, listOf(f("i$i")))
        assertEquals(FinishedNotes.KEEP_IDS, seen.size)
        assertEquals("i51", seen.first())
        assertEquals("i150", seen.last())
        assertEquals(listOf("i150"), FinishedNotes.remember(emptyList(), listOf(f("i150"), f("i150"))))
    }

    @Test fun anOldIdIsNotShownAgainAfterTheListIsFull() {
        var seen = emptyList<String>()
        val many = (1..120).map { f("i$it") }
        seen = FinishedNotes.remember(seen, many)
        assertTrue("one of the newest 100 is still known", FinishedNotes.fresh(listOf(f("i120")), seen, true, 10_100).isEmpty())
    }

    @Test fun anotherPairingStartsSeedingAgain() {
        val c = ApplicationProvider.getApplicationContext<Context>()
        val seen = FinishedSeen(c)
        seen.clear()
        assertTrue("the very first list only seeds", seen.take(listOf(f("a")), 10_100).isEmpty())
        assertEquals(listOf("b"), seen.take(listOf(f("a"), f("b")), 10_100).map { it.id })
        seen.clear()
        assertTrue("a new pairing seeds again, no old alerts", seen.take(listOf(f("a"), f("b")), 10_100).isEmpty())
    }
}
