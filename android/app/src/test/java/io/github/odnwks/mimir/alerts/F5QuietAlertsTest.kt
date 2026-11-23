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
    }

    private fun item(id: String, kind: String = "Permission", task: String? = "t1") =
        LItem(id, kind, "Garden planner", task, "Run a command", "text", emptyList(), emptyList(), true, 1L, null)

    private fun fin(id: String, task: String, at: Long = System.currentTimeMillis() / 1000) = LFinished(id, task, "Ok", at)

    private fun posted() = nm.activeNotifications.filter { it.packageName == c.packageName }

    @Test fun defaultsAreAsTheOwnerAsked() {
        val d = AlertStore.get(c)
        assertTrue(d.needsYou && d.taskFinished && d.chatReplies && d.updates && d.showWorking)
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
        Notifier.show(c, listOf(item("a1")), outside = { false })
        assertEquals(1, posted().size)
        nm.cancelAll()
        AlertStore.change(c) { it.copy(needsYou = false) }
        Notifier.show(c, listOf(item("a1"), item("a2")), outside = { false })
        assertEquals("switched off: nothing new", 0, posted().size)
    }

    @Test fun theUpdatesSwitchGatesReleaseNotes() {
        AlertStore.change(c) { it.copy(updates = false) }
        Notifier.show(c, listOf(item("r1", kind = "Release")), outside = { false })
        assertEquals(0, posted().size)
        Notifier.show(c, listOf(item("r1", kind = "Release"), item("p1")), outside = { false })
        assertEquals(1, posted().size)
    }

    @Test fun anOutsideSessionAlertsOnlyWhenItNeedsYouByDefault() {
        Notifier.show(c, listOf(item("o1", task = "outside")), outside = { it == "outside" })
        assertEquals("a question from an outside session still alerts", 1, posted().size)
        nm.cancelAll()
        Notifier.showFinished(c, listOf(fin("x0", "outside")), outside = { it == "outside" })
        Notifier.showFinished(c, listOf(fin("x1", "outside"), fin("x2", "outside")), outside = { it == "outside" })
        assertEquals("a finished outside session never alerts by default", 0, posted().size)
    }

    @Test fun outsideSessionsCanAlsoTellWhenTheyFinishOrBeOff() {
        AlertStore.change(c) { it.copy(outside = OutsideMode.AlsoFinished) }
        Notifier.showFinished(c, listOf(fin("s0", "z")), outside = { true })
        Notifier.showFinished(c, listOf(fin("s1", "z")), outside = { true })
        assertEquals(1, posted().size)
        nm.cancelAll()
        AlertStore.change(c) { it.copy(outside = OutsideMode.Off) }
        Notifier.show(c, listOf(item("o2", task = "outside")), outside = { true })
        assertEquals("off: not even when they need me", 0, posted().size)
    }

    @Test fun oneFinishedAlertPerTaskAcrossManyTurns() {
        Notifier.showFinished(c, listOf(fin("seed", "other")), outside = { false })
        for (n in 1..5) Notifier.showFinished(c, listOf(fin("turn$n", "mine")), outside = { false })
        assertEquals(1, posted().size)
        assertEquals(Notifier.finishedTag("mine"), posted().single().tag)
        // Several turns arriving together make one too.
        nm.cancelAll()
        Notifier.showFinished(c, listOf(fin("b1", "m2"), fin("b2", "m2"), fin("b3", "m2")), outside = { false })
        assertEquals(1, posted().size)
    }

    @Test fun theFinishedSwitchDecidesAndTheNotesCanBeCleared() {
        Notifier.showFinished(c, listOf(fin("seed", "other")), outside = { false })
        AlertStore.change(c) { it.copy(taskFinished = false) }
        Notifier.showFinished(c, listOf(fin("f1", "mine")), outside = { false })
        assertEquals(0, posted().size)
        AlertStore.change(c) { it.copy(taskFinished = true) }
        Notifier.showFinished(c, listOf(fin("f2", "mine"), fin("f3", "yours")), outside = { false })
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

    @Test fun outsideTasksStayMarkedUntilThePersonCarriesOnHere() {
        OutsideTasks.init(c)
        OutsideTasks.forgetAll()
        assertFalse(OutsideTasks.contains("t9"))
        OutsideTasks.seen("t9", live = true)
        assertTrue(OutsideTasks.contains("t9"))
        OutsideTasks.seen("t9", live = false)
        assertTrue("still outside after it goes quiet", OutsideTasks.contains("t9"))
        OutsideTasks.mine("t9")
        assertFalse(OutsideTasks.contains("t9"))
        assertFalse(OutsideTasks.contains(null))
        OutsideTasks.forgetAll()
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
