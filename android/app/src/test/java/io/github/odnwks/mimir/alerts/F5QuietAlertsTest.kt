package io.github.odnwks.mimir.alerts

import android.app.NotificationManager
import android.content.Context
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import io.github.odnwks.mimir.audio.ReleasePolicy
import io.github.odnwks.mimir.data.NeedsKind
import io.github.odnwks.mimir.link.LFinished
import io.github.odnwks.mimir.link.LItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** F5: alerts stay few, true and clearable; the person's switches decide what is posted. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class F5QuietAlertsTest {
    private lateinit var c: Context
    private val nm get() = c.getSystemService(NotificationManager::class.java)

    @Before fun setUp() {
        c = ApplicationProvider.getApplicationContext()
        c.getSharedPreferences("alert_choices", Context.MODE_PRIVATE).edit().clear().commit()
        c.getSharedPreferences("notified", Context.MODE_PRIVATE).edit().clear().commit()
        c.getSharedPreferences("finished_notified", Context.MODE_PRIVATE).edit().clear().commit()
        Notifier.channels(c)
        Notifier.foreground = false
        Notifier.working(c, emptyList())
        nm.cancelAll()
        FinishedGate.clear()
    }

    private fun item(id: String, kind: String = "Permission", task: String? = "t1") =
        LItem(id, kind, "Garden planner", task, "Run a command", "text", emptyList(), emptyList(), true, 1L, null)

    private fun fin(id: String, task: String, at: Long = System.currentTimeMillis() / 1000) = LFinished(id, task, "Ok", at)

    private fun posted() = nm.activeNotifications.filter { it.packageName == c.packageName }

    @Test fun defaultsAreAsTheOwnerAsked() {
        val d = AlertStore.get(c)
        assertTrue(d.needsYou && d.taskFinished && d.updates && d.showWorking)
        assertEquals(OutsideMode.NeedMe, d.outside)
    }

    @Test fun choicesSurviveARestart() {
        AlertStore.change(c) { it.copy(needsYou = false, outside = OutsideMode.Off, showWorking = false) }
        // A new read from the saved settings, as after the app was closed.
        val back = AlertStore.get(c)
        assertFalse(back.needsYou)
        assertFalse(back.showWorking)
        assertEquals(OutsideMode.Off, back.outside)
        assertTrue(back.taskFinished)
    }

    @Test fun theNeedsSwitchDecidesWhatIsPosted() {
        Notifier.show(c, listOf(item("a1")), outside = { false }, known = { true })
        assertEquals(1, posted().size)
        nm.cancelAll()
        AlertStore.change(c) { it.copy(needsYou = false) }
        Notifier.show(c, listOf(item("a1"), item("a2")), outside = { false }, known = { true })
        assertEquals("switched off: nothing new", 0, posted().size)
    }

    @Test fun theUpdatesSwitchGatesReleaseNotes() {
        AlertStore.change(c) { it.copy(updates = false) }
        Notifier.show(c, listOf(item("r1", kind = "Release")), outside = { false }, known = { true })
        assertEquals(0, posted().size)
        Notifier.show(c, listOf(item("r1", kind = "Release"), item("p1")), outside = { false }, known = { true })
        assertEquals(1, posted().size)
    }

    @Test fun anOutsideSessionAlertsOnlyWhenItNeedsYouByDefault() {
        Notifier.show(c, listOf(item("o1", task = "outside")), outside = { it == "outside" }, known = { true })
        assertEquals("a question from an outside session still alerts", 1, posted().size)
        nm.cancelAll()
        Notifier.showFinished(c, listOf(fin("x0", "outside")), outside = { it == "outside" }, known = { true })
        Notifier.showFinished(c, listOf(fin("x1", "outside"), fin("x2", "outside")), outside = { it == "outside" }, known = { true })
        assertEquals("a finished outside session never alerts by default", 0, posted().size)
    }

    @Test fun outsideSessionsCanAlsoTellWhenTheyFinishOrBeOff() {
        AlertStore.change(c) { it.copy(outside = OutsideMode.AlsoFinished) }
        Notifier.showFinished(c, listOf(fin("s0", "z")), outside = { true }, known = { true })
        Notifier.showFinished(c, listOf(fin("s1", "z")), outside = { true }, known = { true })
        assertEquals(1, posted().size)
        nm.cancelAll()
        AlertStore.change(c) { it.copy(outside = OutsideMode.Off) }
        Notifier.show(c, listOf(item("o2", task = "outside")), outside = { true }, known = { true })
        assertEquals("off: not even when they need me", 0, posted().size)
    }

    @Test fun oneFinishedAlertPerTaskAcrossManyTurns() {
        Notifier.showFinished(c, listOf(fin("seed", "other")), outside = { false }, known = { true })
        for (n in 1..5) Notifier.showFinished(c, listOf(fin("turn$n", "mine")), outside = { false }, known = { true })
        assertEquals(1, posted().size)
        assertEquals(Notifier.finishedTag("mine"), posted().single().tag)
        // Several turns arriving together make one too.
        nm.cancelAll()
        Notifier.showFinished(c, listOf(fin("b1", "m2"), fin("b2", "m2"), fin("b3", "m2")), outside = { false }, known = { true })
        assertEquals(1, posted().size)
    }

    @Test fun theFinishedSwitchDecidesAndTheNotesCanBeCleared() {
        Notifier.showFinished(c, listOf(fin("seed", "other")), outside = { false }, known = { true })
        AlertStore.change(c) { it.copy(taskFinished = false) }
        Notifier.showFinished(c, listOf(fin("f1", "off")), outside = { false }, known = { true })
        assertEquals(0, posted().size)
        AlertStore.change(c) { it.copy(taskFinished = true) }
        Notifier.showFinished(c, listOf(fin("f2", "mine"), fin("f3", "yours")), outside = { false }, known = { true })
        assertEquals(2, posted().size)
        assertTrue("every finished note can be cleared", posted().all { it.isClearable })
        Notifier.clearFinished(c, "mine")
        assertEquals(1, posted().size)
        Notifier.clearFinished(c)
        assertEquals("opening the app clears what is unread", 0, posted().size)
    }

    @Test fun theWorkingNoteNamesTheTaskAndGoesWhenTheWorkEnds() {
        Notifier.working(c, listOf("Garden planner"))
        val n = posted().single()
        assertEquals("Working on: Garden planner", n.notification.extras.getString(android.app.Notification.EXTRA_TITLE))
        assertFalse("while Mímir's own task runs, this one is the ongoing note", n.isClearable)
        Notifier.working(c, listOf("Garden planner", "Recipe box"))
        assertEquals("Working on 2 tasks", posted().single().notification.extras.getString(android.app.Notification.EXTRA_TITLE))
        Notifier.working(c, emptyList())
        assertEquals(0, posted().size)
    }

    @Test fun noWorkingNoteWhenSwitchedOff() {
        AlertStore.change(c) { it.copy(showWorking = false) }
        Notifier.working(c, listOf("Garden planner"))
        assertEquals(0, posted().size)
        assertFalse("no instant link without the note", Keep.shouldStart(true, true, 1, 0, showWorking = false))
        assertTrue(Keep.shouldStart(true, true, 1, 0, showWorking = true))
    }

    @Test fun carryOnHereThenReplyMakesItMineForGoodEvenOnAnOlderPc() {
        OutsideTasks.init(c)
        OutsideTasks.forgetAll()
        OutsideTasks.header("t9", live = true, pcSays = null)
        assertTrue(OutsideTasks.contains("t9"))
        OutsideTasks.header("t9", live = false, pcSays = null)
        assertTrue("still outside after it goes quiet", OutsideTasks.contains("t9"))
        OutsideTasks.mine("t9")
        assertFalse(OutsideTasks.contains("t9"))
        OutsideTasks.header("t9", live = true, pcSays = null)
        assertFalse("the next live header never undoes mine", OutsideTasks.contains("t9"))
        assertFalse(OutsideTasks.contains(null))
        OutsideTasks.removed("t9")
        OutsideTasks.header("t9", live = true, pcSays = null)
        assertTrue("the PC dropped it: a new life", OutsideTasks.contains("t9"))
        OutsideTasks.forgetAll()
    }

    @Test fun aPcThatSaysItIsBelieved() {
        OutsideTasks.init(c)
        OutsideTasks.forgetAll()
        OutsideTasks.header("p1", live = false, pcSays = true)
        assertTrue("outside although it is quiet now", OutsideTasks.contains("p1"))
        OutsideTasks.header("p1", live = true, pcSays = false)
        assertFalse("a Mimir task once flagged elsewhere, now its own", OutsideTasks.contains("p1"))
        OutsideTasks.newConnection()
        assertFalse(OutsideTasks.known("p1"))
        assertTrue(OutsideTasks.known(null))
        OutsideTasks.forgetAll()
    }

    @Test fun aMimirTaskOnceFlaggedElsewhereAlertsNormallyWhenItRunsHere() {
        OutsideTasks.init(c)
        OutsideTasks.forgetAll()
        OutsideTasks.header("own", live = false, pcSays = false)
        Notifier.showFinished(c, listOf(fin("a0", "other")), outside = OutsideTasks::contains)
        Notifier.showFinished(c, listOf(fin("a1", "own")), outside = OutsideTasks::contains)
        assertEquals(1, posted().size)
        OutsideTasks.forgetAll()
    }

    @Test fun anItemBeforeItsHeaderWaitsAndThenIsJudged() {
        var known = false
        Notifier.show(c, listOf(item("w1", task = "late")), outside = { true }, known = { known })
        assertEquals("no header yet: no alert", 0, posted().size)
        // The header came: an outside session that needs the person still alerts (default), once.
        known = true
        Notifier.show(c, listOf(item("w1", task = "late")), outside = { true }, known = { known })
        assertEquals(1, posted().size)
        Notifier.show(c, listOf(item("w1", task = "late")), outside = { true }, known = { known })
        assertEquals("only once", 1, posted().size)
    }

    @Test fun aReconnectDoesNotFloodAnOlderPcsOutsideSessions() {
        OutsideTasks.init(c)
        OutsideTasks.forgetAll()
        OutsideTasks.header("o", live = true, pcSays = null)
        OutsideTasks.newConnection()
        assertFalse(OutsideTasks.known("o"))
        assertTrue("the mark stays across the reconnect", OutsideTasks.contains("o"))
        OutsideTasks.header("o", live = false, pcSays = null)
        assertTrue(OutsideTasks.contains("o"))
        OutsideTasks.forgetAll()
    }

    @Test fun theWorkingNoteHidesTheTitleOnTheLockScreen() {
        Notifier.working(c, listOf("Garden planner"))
        val n = posted().single().notification
        assertEquals(androidx.core.app.NotificationCompat.VISIBILITY_PRIVATE, n.visibility)
        assertEquals("Working on 1 task", n.publicVersion.extras.getString(android.app.Notification.EXTRA_TITLE))
    }

    @Test fun aFinishedBeforeItsHeaderWaitsThenIsJudged() {
        Notifier.showFinished(c, listOf(fin("seed", "other")), outside = { false }, known = { true })
        Notifier.showFinished(c, listOf(fin("h1", "late")), outside = { false }, known = { false })
        assertEquals("header unknown: nothing posted", 0, posted().size)
        OutsideTasks.init(c)
        OutsideTasks.forgetAll()
        OutsideTasks.header("late", live = false, pcSays = false)
        Notifier.headerSeen(c, "late", "Done", busy = false)
        assertEquals(1, posted().size)
        OutsideTasks.forgetAll()
    }

    @Test fun oneAlertForEachTimeWorkEndsNotPerTurnAndNotAgainAfterASwipe() {
        Notifier.showFinished(c, listOf(fin("seed", "other")), outside = { false }, known = { true })
        FinishedGate.header("job", "Working", true)
        Notifier.showFinished(c, listOf(fin("j0", "job")), outside = { false }, known = { true })
        assertEquals("still working: not finished yet", 0, posted().size)
        FinishedGate.header("job", "Done", false)
        Notifier.showFinished(c, listOf(fin("j1", "job")), outside = { false }, known = { true })
        assertEquals(1, posted().size)
        nm.cancelAll() // swiped
        Notifier.showFinished(c, listOf(fin("j2", "job")), outside = { false }, known = { true })
        assertEquals("the same finish does not come back", 0, posted().size)
        // New work, a new finish.
        FinishedGate.header("job", "Working", true)
        FinishedGate.header("job", "Done", false)
        Notifier.showFinished(c, listOf(fin("j3", "job")), outside = { false }, known = { true })
        assertEquals(1, posted().size)
    }

    @Test fun listenIsLetGoAfterTheEndAndAfterTenMinutesPaused() {
        assertEquals(ReleasePolicy.ENDED_MS, ReleasePolicy.delayMs(Player.STATE_ENDED, false, true))
        assertTrue("a few seconds", ReleasePolicy.ENDED_MS <= 10_000)
        assertEquals(ReleasePolicy.ENDED_MS, ReleasePolicy.delayMs(Player.STATE_IDLE, false, true))
        assertEquals("replaced by nothing", ReleasePolicy.ENDED_MS, ReleasePolicy.delayMs(Player.STATE_READY, true, false))
        assertEquals(10 * 60_000L, ReleasePolicy.delayMs(Player.STATE_READY, false, true))
        assertNull("playing keeps it", ReleasePolicy.delayMs(Player.STATE_READY, true, true))
        assertNull("loading to play keeps it", ReleasePolicy.delayMs(Player.STATE_BUFFERING, true, true))
    }
}
