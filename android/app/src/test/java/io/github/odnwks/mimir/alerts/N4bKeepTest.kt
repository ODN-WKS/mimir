package io.github.odnwks.mimir.alerts

import io.github.odnwks.mimir.data.Reconnect
import io.github.odnwks.mimir.link.LFinished
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** N4b: when the link-keeping service runs, and what the finished alerts say and how often. */
class N4bKeepTest {
    private var t = 1_000_000L

    @Test fun theServiceStartsOnlyForAPairedPcThatSendsFinishedEventsWhileSomethingWorksOrWaits() {
        assertTrue(Keep.shouldStart(true, true, 1, 0))
        assertTrue(Keep.shouldStart(true, true, 0, 2))
        assertFalse("nothing working or waiting", Keep.shouldStart(true, true, 0, 0))
        assertFalse("an older PC keeps today's way", Keep.shouldStart(true, false, 3, 3))
        assertFalse("not paired", Keep.shouldStart(false, true, 3, 3))
    }

    @Test fun theServiceStopsAboutTwoMinutesAfterNothingWorksOrWaits() {
        val k = Keep { t }
        assertTrue(k.tick(2, 0))
        t += 5 * 60_000
        assertTrue("still working: stays", k.tick(1, 0))
        t += 1_000
        assertTrue("a short grace", k.tick(0, 0))
        t += Keep.GRACE_MS - 2_000
        assertTrue(k.tick(0, 0))
        t += 2_000
        assertFalse("two minutes of nothing", k.tick(0, 0))
    }

    @Test fun aTaskWaitingForThePersonAlsoKeepsIt() {
        val k = Keep { t }
        t += Keep.GRACE_MS - 1
        assertTrue(k.tick(0, 1))
        t += Keep.GRACE_MS - 1
        assertTrue(k.tick(0, 0))
    }

    @Test fun whileTheServiceRunsTheLinkReconnectsAsOnScreen() {
        val r = Reconnect { t }
        assertFalse("closed, nothing wanted", r.due(onScreen = false, busy = false))
        assertTrue("kept by the service", r.due(onScreen = false, busy = false, kept = true))
        r.tried(); r.failed()
        t += 3_000
        assertTrue("retries at 3 s like on screen", r.due(false, false, kept = true))
    }

    private fun f(id: String, how: String = "Ok", at: Long = 10_000) = LFinished(id, "task-$id", how, at)

    @Test fun eachFinishedIdShowsOnce() {
        val items = listOf(f("a"), f("b"), f("a"))
        val first = FinishedNotes.fresh(items, emptySet(), true, 10_100)
        assertEquals(listOf("a", "b"), first.map { it.id })
        val seen = FinishedNotes.remember(emptySet(), items)
        assertTrue("a reconnect's recent list shows nothing twice", FinishedNotes.fresh(items + f("c"), seen, true, 10_100).map { it.id } == listOf("c"))
    }

    @Test fun theVeryFirstListOnlySeedsAndOldOrStoppedOnesAreNotShown() {
        assertTrue(FinishedNotes.fresh(listOf(f("a")), emptySet(), false, 10_100).isEmpty())
        assertTrue("older than an hour", FinishedNotes.fresh(listOf(f("a", at = 1_000)), emptySet(), true, 10_000 + 3_700).isEmpty())
        assertTrue("the owner stopped it", FinishedNotes.fresh(listOf(f("a", "Stopped")), emptySet(), true, 10_100).isEmpty())
        assertEquals(1, FinishedNotes.fresh(listOf(f("a", "Failed")), emptySet(), true, 10_100).size)
    }

    @Test fun theRememberedIdsStayBounded() {
        val many = (1..300).map { f("i$it") }
        assertEquals(FinishedNotes.KEEP_IDS, FinishedNotes.remember(emptySet(), many).size)
    }

    @Test fun theWordsAreGenericAndNeverNameTheTask() {
        for (how in listOf("Ok", "Failed", "Stopped")) {
            val (title, body) = FinishedNotes.words(how)
            for (secret in listOf("task-a", "Garden planner", "/p/")) assertFalse(title + body, (title + body).contains(secret))
        }
        assertEquals("Mímir — a task finished", FinishedNotes.words("Ok").first)
        assertEquals("Mímir — a task needs a look", FinishedNotes.words("Failed").first)
    }
}
