package io.github.odnwks.mimir.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.nav.Tab
import io.github.odnwks.mimir.online.FakeWeb
import io.github.odnwks.mimir.online.Online
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * QA C4: with nothing going on, a screen changes nothing and so draws nothing. Every state the UI
 * writes in 10 idle seconds is counted (an endless animation or a ticking clock writes one each
 * frame or second) and named when the check fails.
 */
@RunWith(RobolectricTestRunner::class)
class IdleRedrawTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private fun idleWrites(station: FakeStation, open: Navigator.() -> Unit): List<String> {
        FakeWeb().install()
        Online.set(Online.Settings())
        val nav = Navigator()
        val engine = testEngine(tmp.newFolder(), ready = true)
        compose.mainClock.autoAdvance = false
        compose.setContent { CompositionLocalProvider(LocalPhoneEngine provides engine) { MimirTheme { AppRoot(station, nav) } } }
        nav.open()
        // Let it settle: screen changes and first-show animations end.
        repeat(5) { compose.mainClock.advanceTimeBy(1_000) }
        val seen = ArrayList<String>()
        val handle = Snapshot.registerApplyObserver { changed, _ -> changed.forEach { seen += it.toString().take(120) } }
        try {
            repeat(100) { compose.mainClock.advanceTimeBy(100) }
        } finally {
            handle.dispose()
        }
        return seen
    }

    private fun check(name: String, writes: List<String>) {
        val top = writes.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(8)
        println("IDLE $name: ${writes.size} writes in 10 s; most: $top")
        assertTrue("$name keeps changing while idle (${writes.size} writes in 10 s): $top", writes.size <= 2)
    }

    @Test fun theOneChatIsStillWhenIdle() {
        val st = FakeStation().also { it.set { s -> s.copy(pcTalks = true) } }
        check("one chat", idleWrites(st) { selectTab(Tab.Chat) })
    }

    @Test fun theOldPairedChatIsStillWhenIdle() = check("paired chat", idleWrites(FakeStation()) { selectTab(Tab.Chat) })

    @Test fun statusIsStillWhenIdle() {
        // Nothing working: no pulsing dot has any reason to move.
        val st = FakeStation().also { it.set { s -> s.copy(tasks = s.tasks.map { t -> t.copy(busy = false, status = "Done") }, pc = s.pc.copy(tasksRunning = 0)) } }
        check("status", idleWrites(st) { selectTab(Tab.Status) })
    }

    @Test fun updatesIsStillWhenIdle() {
        // Nothing playing (a playing update's bar moves, as it should).
        val st = FakeStation().also { it.set { s -> s.copy(player = s.player.copy(playing = false)) } }
        check("updates", idleWrites(st) { selectTab(Tab.Updates) })
    }

    /** A task working: its dot pulses a few times when it shows, then holds still (it used to pulse for hours). */
    @Test fun aWorkingTaskPulsesThenHoldsStill() {
        val st = FakeStation().also { it.set { s -> s.copy(tasks = s.tasks.mapIndexed { i, t -> if (i == 0) t.copy(busy = true, status = "Working") else t }) } }
        check("status with a task working", idleWrites(st) { selectTab(Tab.Status) })
    }

    @Test fun anUnpairedChatIsStillWhenIdle() {
        val st = FakeStation(io.github.odnwks.mimir.data.LinkStation.empty("test").copy(paired = false))
        check("unpaired chat", idleWrites(st) { open(Screen.Chat) })
    }
}
