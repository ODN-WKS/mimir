package io.github.odnwks.mimir.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.odnwks.mimir.data.Answer
import io.github.odnwks.mimir.data.ExampleData
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.data.Heard
import io.github.odnwks.mimir.data.LinkStation
import io.github.odnwks.mimir.data.NeedsKind
import io.github.odnwks.mimir.data.NeedsYouItem
import io.github.odnwks.mimir.data.Player
import io.github.odnwks.mimir.data.StationState
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.nav.Sheet
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Redesign R8: Needs you, Updates and Your PC. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class R8ScreensTest {
    @get:Rule val compose = createComposeRule()

    private val ask = NeedsYouItem("q1", NeedsKind.Question, "Garden planner", "12 min", "OK to delete the old builds? It frees about 40 GB.", "t1", listOf("Yes, delete them", "No, keep them"))
    private fun base(): StationState = ExampleData.awake()

    private fun show(state: StationState, nav: Navigator = Navigator(), station: FakeStation = FakeStation(state), body: @androidx.compose.runtime.Composable (StationState, FakeStation, Navigator) -> Unit): FakeStation {
        compose.setContent { MimirTheme(reduceMotion = true) { body(state, station, nav) } }
        compose.waitForIdle()
        return station
    }

    // ---- Needs you ----

    @Test fun needsYouWithNothingWaitingSaysSoAndListsWhatWorks() {
        val s = base().copy(needsYou = emptyList())
        show(s) { st, sta, n -> NeedsYouScreen(st, sta, n) }
        compose.onNodeWithText("Nothing needs you right now.").assertIsDisplayed()
        compose.onNodeWithText("WORKING WITHOUT YOU").assertIsDisplayed()
        compose.onNodeWithText("Fix calls on cell signal").assertIsDisplayed()
    }

    @Test fun needsYouLeavesOutTheWorkingListWhenNothingWorks() {
        val s = base().copy(needsYou = emptyList(), tasks = emptyList())
        show(s) { st, sta, n -> NeedsYouScreen(st, sta, n) }
        compose.onNodeWithText("Nothing needs you right now.").assertIsDisplayed()
        compose.onNodeWithText("WORKING WITHOUT YOU").assertDoesNotExist()
    }

    @Test fun aQuestionShowsItsOptionsAsTheButtons() {
        val s = base().copy(needsYou = listOf(ask))
        show(s) { st, sta, n -> NeedsYouScreen(st, sta, n) }
        compose.onNodeWithText("Garden planner · waiting 12 min").assertIsDisplayed()
        compose.onNodeWithText("Yes, delete them").assertIsDisplayed()
        compose.onNodeWithText("No, keep them").assertIsDisplayed()
        compose.onNodeWithText("Say something else…").assertIsDisplayed()
        compose.onNodeWithText("Nothing needs you right now.").assertDoesNotExist()
    }

    @Test fun sayingSomethingElseOpensTheTask() {
        val nav = Navigator()
        show(base().copy(needsYou = listOf(ask)), nav) { st, sta, n -> NeedsYouScreen(st, sta, n) }
        compose.onNodeWithText("Say something else…").performClick()
        assertEquals(Screen.Task("t1"), nav.current)
    }

    @Test fun theButtonsSayWhatHappensAndTheFirstIsTheMainOne() {
        val picked = mutableListOf<String>()
        val chosen = mutableListOf<Int>()
        val q = askButtons(ask, { picked += it.name }, { chosen += it })
        assertEquals(listOf("Yes, delete them", "No, keep them"), q.map { it.label })
        q[1].act()
        assertEquals(listOf(1), chosen)
        val fix = askButtons(NeedsYouItem("f", NeedsKind.CheckFix, "Recipe box", "1 h", "Did it start?", null), { picked += it.name }, { })
        assertEquals(listOf("It works", "Still broken"), fix.map { it.label })
        fix[0].act()
        assertEquals(listOf(Answer.ItWorks.name), picked)
        val allow = askButtons(NeedsYouItem("p", NeedsKind.Permission, "Recipe box", "now", "Edit a file?", null), { }, { })
        assertEquals(listOf("Allow", "Don't allow"), allow.map { it.label })
        val four = askButtons(NeedsYouItem("p", NeedsKind.Permission, "Recipe box", "now", "Edit?", null, listOf("Allow", "Don't allow", "Always allow", "Answer on the PC")), { }, { })
        assertEquals(listOf("Allow", "Don't allow", "Answer on the PC"), four.map { it.label })
        assertEquals("waiting just now", waitingWords("now"))
    }

    // ---- Updates ----

    @Test fun updatesHasOneBigPlayButtonCountingTheNewOnesAndSplitsNewFromHeard() {
        val s = base()
        val newCount = s.updates.count { it.heard == Heard.New }
        val station = show(s.copy(player = Player(null, false, 0, 1f))) { st, sta, n -> UpdatesScreen(st, sta, n) }
        compose.onNodeWithText(playNewWords(newCount)).assertIsDisplayed()
        compose.onNodeWithText("NEW").assertIsDisplayed()
        compose.onNodeWithText("HEARD").assertIsDisplayed()
        compose.onNodeWithText("General · 6 min ago · 14 sec").assertIsDisplayed()
        compose.onNodeWithText("Voice:", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Change").assertIsDisplayed()
        compose.onNodeWithText(playNewWords(newCount)).performClick()
        compose.waitForIdle()
        assertEquals("u-incog", station.state.value.player.updateId)
    }

    @Test fun updatesWithEverythingHeardHasNoBigButtonAndNoNewSection() {
        val s = base().let { it.copy(updates = it.updates.map { u -> u.copy(heard = Heard.Heard) }) }
        show(s) { st, sta, n -> UpdatesScreen(st, sta, n) }
        compose.onNodeWithText("HEARD").assertIsDisplayed()
        compose.onNodeWithText("NEW").assertDoesNotExist()
        compose.onNodeWithText("Play 1 new update").assertDoesNotExist()
    }

    @Test fun updatesWithNothingSaysWhatWillShowHere() {
        show(base().copy(updates = emptyList())) { st, sta, n -> UpdatesScreen(st, sta, n) }
        compose.onNodeWithText(UPDATES_EMPTY).assertIsDisplayed()
        assertEquals("No updates yet. When Mímir finishes something on your PC, you'll hear about it here.", UPDATES_EMPTY)
    }

    @Test fun theLinesUnderEachUpdateAndTheVoiceLineAreInPlainWords() {
        assertEquals("Play 1 new update", playNewWords(1))
        assertEquals("Play 2 new updates", playNewWords(2))
        assertEquals("Voice: Ingrid · Speed: normal", voiceLine("Ingrid", 1f))
        assertEquals("Voice: Ingrid · Speed: 125%", voiceLine("Ingrid", 1.25f))
    }

    // ---- Your PC ----

    @Test fun yourPcConnectedShowsDoingNowAndDisconnect() {
        val nav = Navigator()
        show(base(), nav) { st, sta, n -> StatusScreen(st, sta, n) }
        compose.onNodeWithText("Connected").assertIsDisplayed()
        compose.onNodeWithText("Private").assertIsDisplayed()
        compose.onNodeWithText("DOING NOW").assertIsDisplayed()
        compose.onNodeWithText("Fix calls on cell signal").assertIsDisplayed()
        // The phone is not sent the PC's processor and memory numbers, so Health is not shown.
        compose.onNodeWithText("HEALTH").assertDoesNotExist()
        compose.onNodeWithText("Pair another phone").assertDoesNotExist()
        compose.onNodeWithText("Disconnect this phone").performClick()
        assertEquals(Sheet.Unpair, nav.sheet)
    }

    @Test fun yourPcNotReachedSaysNotConnected() {
        val s = base().let { it.copy(pc = it.pc.copy(online = false, lastSeen = "11:42")) }
        show(s) { st, sta, n -> StatusScreen(st, sta, n) }
        compose.onNodeWithText("Not connected").assertIsDisplayed()
        compose.onNodeWithText("Private").assertDoesNotExist()
    }

    @Test fun yourPcNotPairedShowsThreeStepsAndOneConnectButton() {
        val nav = Navigator()
        show(LinkStation.empty("test").copy(paired = false), nav) { st, sta, n -> StatusScreen(st, sta, n) }
        assertEquals(3, YOUR_PC_STEPS.size)
        YOUR_PC_STEPS.forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onNodeWithText("Disconnect this phone").assertDoesNotExist()
        compose.onNodeWithText("Pair another phone").assertDoesNotExist()
        compose.onNodeWithText("Connect your PC").performClick()
        assertTrue(nav.current == Screen.Pair)
    }

    @Test fun waitingTasksAreListedWithTheWorkingOnes() {
        show(base()) { st, sta, n -> StatusScreen(st, sta, n) }
        // "Make Allow respond faster" is waiting on the owner.
        compose.onNodeWithText("Make Allow respond faster").assertIsDisplayed()
    }

    @Test fun startedWordsSaysHowLongAgo() {
        assertEquals("Started 9 min ago", startedWords(1000 - 540, 1000))
        assertEquals("Started just now", startedWords(990, 1000))
        assertEquals("Working now", startedWords(0, 1000))
    }
}
