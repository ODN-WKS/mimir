package io.github.odnwks.mimir.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import io.github.odnwks.mimir.data.ExampleData
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.RowKind
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.nav.Sheet
import io.github.odnwks.mimir.standalone.Catalog
import io.github.odnwks.mimir.standalone.Engine
import io.github.odnwks.mimir.standalone.LocalChat
import io.github.odnwks.mimir.standalone.Saved
import io.github.odnwks.mimir.standalone.Store
import io.github.odnwks.mimir.standalone.Turn
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** P29: press and hold on rows, "Delete forever", and project work offered from a chat. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class HoldMenuTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val nav = Navigator()
    private lateinit var engine: Engine
    private lateinit var station: FakeStation
    private lateinit var dir: File

    private val soil = listOf(Turn(true, "Best soil for tomatoes"), Turn(false, "Loose and rich."))
    private val handoff = listOf(Turn(true, "Continue from the hand-off"), Turn(false, "I can't do that from this chat."))

    private fun start(open: List<Turn> = soil) {
        androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>().getSharedPreferences(MenuFold.PREFS, 0).edit().clear().commit()
        dir = tmp.newFolder()
        testEngine(dir, ready = true)
        Store(File(dir, "standalone.bin"), PlainCodec).save(Saved(Catalog.default.id, listOf(LocalChat("c1", Catalog.default.id, open), LocalChat("c2", Catalog.default.id, listOf(Turn(true, "Sourdough without a starter"), Turn(false, "Yes."))))))
        engine = Engine(dir, Store(File(dir, "standalone.bin"), PlainCodec), WordsRunner(), supported = true, now = { 1_800_000_000_000L })
        station = FakeStation(ExampleData.awake().copy(pcTalks = true))
        compose.setContent { CompositionLocalProvider(LocalPhoneEngine provides engine, LocalReadAloud provides FakeReadAloud()) { MimirTheme(reduceMotion = true) { AppRoot(station, nav) } } }
        compose.waitForIdle()
    }

    private fun chats() = (listOf(engine.state.value.chat) + engine.state.value.earlier).filter { it.turns.isNotEmpty() }.map { it.id }.toSet()
    private fun savedIds() = Store(File(dir, "standalone.bin"), PlainCodec).load()!!.chats.map { it.id }

    private fun tap(text: String) { compose.onAllNodesWithText(text).onLast().performClick(); compose.waitForIdle() }

    private fun openProjects() { compose.openMenu(); tap(MENU_PROJECTS.uppercase()) }

    /** The sum the sheet shows, answered right. */
    private fun answerTheSum() {
        val words = compose.onNode(hasText("To go on, type what", substring = true)).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString("")
        val (a, b) = Regex("(\\d+) \\+ (\\d+)").find(words)!!.destructured
        compose.onNodeWithContentDescription(DELETE_SUM).performTextInput("${a.toInt() + b.toInt()}")
        compose.waitForIdle()
    }

    private fun pass(ms: Long) { compose.mainClock.advanceTimeBy(ms); compose.waitForIdle() }

    private fun gone(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()

    @Test fun holdingAProjectOffersItsMenuAndArchiveCanBeBroughtBack() {
        start()
        openProjects()
        compose.menuRowHold("Recipe box")
        listOf(ROW_TOP, ROW_COLLAPSE_PROJECTS, ROW_ARCHIVE, ROW_DELETE).forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        assertEquals(Screen.Chat, nav.current)
        tap(ROW_ARCHIVE)
        assertTrue(gone("Recipe box"))
        compose.onNodeWithText("Archived Recipe box").assertIsDisplayed()
        // Nothing was asked of the PC, and the Archived row brings it back.
        assertTrue(station.deletedProjects.isEmpty())
        // Under the last project, as far down as a person would scroll.
        compose.onAllNodes(hasScrollToNodeAction()).onLast().performScrollToNode(hasText(ARCHIVED_ROW))
        tap(ARCHIVED_ROW)
        compose.onAllNodes(hasScrollToNodeAction()).onLast().performScrollToNode(hasText("Recipe box"))
        tap("Recipe box")
        compose.onNodeWithText("Recipe box").assertExists()
        compose.onNodeWithText(ARCHIVED_ROW).assertDoesNotExist()
    }

    @Test fun movingAProjectToTheTopStays() {
        start()
        openProjects()
        compose.menuRowHold("Garden planner")
        tap(ROW_TOP)
        val keep = RowKeep(androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>().getSharedPreferences(MenuFold.PREFS, 0))
        assertEquals("Garden planner", keep.listed(station.state.value.projects).first().name)
    }

    @Test fun deleteForeverWantsTheSumThenTenSecondsAndIsAskedOnce() {
        start()
        openProjects()
        compose.menuRowHold("Recipe box")
        tap(ROW_DELETE)
        compose.onNode(hasText("stay on your PC", substring = true)).assertExists()
        compose.onNodeWithText(DELETE_YES).assertIsNotEnabled()
        compose.onNodeWithContentDescription(DELETE_SUM).performTextInput("3")
        compose.onNodeWithText(DELETE_YES).assertIsNotEnabled()
        compose.onNodeWithContentDescription(DELETE_SUM).performTextClearance()
        answerTheSum()
        compose.onNodeWithText(DELETE_YES).assertIsEnabled()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText(DELETE_YES).performClick()
        pass(9_000)
        assertTrue("deleted before the ten seconds were up", station.deletedProjects.isEmpty())
        pass(2_000)
        assertEquals(listOf("Recipe box"), station.deletedProjects)
        pass(30_000)
        assertEquals(listOf("Recipe box"), station.deletedProjects)
    }

    @Test fun keepItAndBackDuringTheWaitDeleteNothing() {
        start()
        for (stop in listOf({ compose.onNodeWithText(DELETE_KEEP).performClick(); Unit }, { compose.runOnIdle { nav.back() }; Unit })) {
            compose.runOnIdle { nav.openSheet(Sheet.DeleteForever(RowKind.Project, "Recipe box", "Recipe box")) }
            compose.waitForIdle()
            answerTheSum()
            compose.mainClock.autoAdvance = false
            compose.onNodeWithText(DELETE_YES).performClick()
            pass(5_000)
            stop()
            pass(20_000)
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            assertTrue(station.deletedProjects.isEmpty())
        }
    }

    @Test fun holdingAChatStaysInTheMenuAndDeleteForeverRemovesItFromTheStore() {
        start()
        compose.menuRowHold("Sourdough without a starter")
        listOf(ROW_TAKE, ROW_COLLAPSE_CHATS, ROW_ARCHIVE, ROW_RENAME, ROW_DELETE).forEach { compose.onNodeWithText(it).assertExists() }
        assertEquals("c1", engine.state.value.chat.id)
        compose.onNodeWithText(ROW_DELETE).performScrollTo().performClick(); compose.waitForIdle()
        answerTheSum()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText(DELETE_YES).performClick()
        pass(11_000)
        compose.mainClock.autoAdvance = true
        compose.waitUntil(5_000) { "c2" !in savedIds() }
        assertEquals(setOf("c1"), chats())
    }

    @Test fun anArchivedChatLeavesTheListAndComesBack() {
        start()
        compose.menuRowHold("Sourdough without a starter")
        tap(ROW_ARCHIVE)
        assertTrue(gone("Sourdough without a starter"))
        assertEquals(setOf("c1", "c2"), chats())
        compose.onNodeWithText(ARCHIVED_ROW).performClick(); compose.waitForIdle()
        tap("Sourdough without a starter")
        compose.onNodeWithText("Sourdough without a starter").assertExists()
    }

    @Test fun deleteThisChatOnTheChatScreenDeletesIt() {
        start()
        compose.openChatOptions()
        compose.onNodeWithText(CHAT_DELETE).performScrollTo().performClick(); compose.waitForIdle()
        compose.onNode(hasText("Delete") and hasClickAction() and hasContentDescription("Delete", substring = true)).performClick(); compose.waitForIdle()
        compose.onNodeWithText("Chat deleted").assertIsDisplayed()
        assertEquals(setOf("c2"), chats())
    }

    @Test fun deleteThisChatOnTheOlderPhoneChatPageDeletesItToo() {
        start()
        compose.runOnIdle { nav.open(Screen.Standalone) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription(CHAT_OPTIONS).performClick(); compose.waitForIdle()
        compose.onNodeWithText(CHAT_DELETE).performScrollTo().performClick(); compose.waitForIdle()
        compose.onNode(hasText("Delete") and hasClickAction() and hasContentDescription("Delete", substring = true)).performClick(); compose.waitForIdle()
        assertEquals(setOf("c2"), chats())
    }

    @Test fun aChatThatNeedsThePcOffersProjectWorkAndOnlyFillsTheBox() {
        start(handoff)
        val before = station.state.value.tasks.size
        compose.onNodeWithText(PROJECT_WORK).performClick(); compose.waitForIdle()
        tap("Garden planner")
        assertEquals(Screen.Project("Garden planner"), nav.current)
        val draft by nav.projectDraft("Garden planner")
        assertEquals("Continue from the hand-off", draft)
        assertEquals("nothing starts until Send", before, station.state.value.tasks.size)
    }

    @Test fun anEverydayChatOffersNoProjectWork() {
        start()
        compose.onNodeWithText(PROJECT_WORK).assertDoesNotExist()
        assertTrue(needsProjectWork("look on the desktop", "Sure."))
        assertTrue(needsProjectWork("tidy it", "I can't do that from this chat, but it can be a task in one of your projects."))
        assertTrue(needsProjectWork("tidy it", "I can’t change files from this chat."))
        assertFalse(needsProjectWork("Best soil for tomatoes", "Loose and rich."))
        assertFalse(needsProjectWork("what is a hand grenade", "A weapon."))
    }

    @Test fun holdingATaskOffersItsMenuAndFinishedTasksFold() {
        start()
        compose.runOnIdle { nav.open(Screen.Project("Budget-App")) }
        compose.waitForIdle()
        compose.menuRowHold("Messages arrive in order")
        listOf(ROW_MOVE_TASK, ROW_COLLAPSE_DONE, ROW_ARCHIVE, "$ROW_RENAME…", ROW_DELETE).forEach { compose.onNodeWithText(it).assertExists() }
        compose.onNodeWithText(ROW_COLLAPSE_DONE).performScrollTo().performClick(); compose.waitForIdle()
        compose.onNodeWithText("Messages arrive in order").assertDoesNotExist()
        compose.onNodeWithText("Finished · 1").performClick(); compose.waitForIdle()
        compose.onNodeWithText("Messages arrive in order").assertExists()
    }
}
