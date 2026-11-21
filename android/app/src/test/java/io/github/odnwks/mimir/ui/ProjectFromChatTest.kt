package io.github.odnwks.mimir.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.data.ProjectNamed
import io.github.odnwks.mimir.link.Filed
import io.github.odnwks.mimir.link.LChatTurn
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.online.FakeWeb
import io.github.odnwks.mimir.online.Online
import io.github.odnwks.mimir.standalone.Engine
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Stage 1, H5: a chat goes to a project only when it names one; one card; Just chat; offline. */
@RunWith(RobolectricTestRunner::class)
class ProjectFromChatTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val web = FakeWeb()
    private val pc = FakePc()
    private lateinit var engine: Engine
    private val project = "/home/me/Projects/Garden-planner-Mod-Manager"

    @Before fun setUp() {
        web.install()
        Online.set(Online.Settings())
        engine = testEngine(tmp.newFolder(), ready = true).also { it.pc = pc }
        // The PC: a message naming Garden planner starts a task there (or is added to the task it's in); anything else is a chat.
        pc.answer = { r, l ->
            val named = ProjectNamed.of(r.turns.last().text, pc.projects)
            when {
                r.chatOnly || (named == null && r.task == null) -> {
                    l.filed(Filed.Talk("${r.turnId}.a", "pc", "Qwen 3.6 · Your PC"), null)
                    l.done(LChatTurn("${r.turnId}.a", false, "A chat answer.", ran = "pc", at = 2, seq = 3), null)
                }
                named == null -> l.filed(Filed.SameTask(project, r.task!!), null)
                else -> l.filed(Filed.NewTask(project, "1794-1-1"), null)
            }
        }
    }

    private fun settle() {
        val until = System.currentTimeMillis() + 5_000
        Thread.sleep(20)
        while (engine.state.value.writing && System.currentTimeMillis() < until) Thread.sleep(10)
        assertFalse(engine.state.value.writing)
    }

    @Test fun theOneRuleForNamingAProject() {
        val names = listOf(project, "/home/me/Projects/Recipe-Box")
        assertEquals(project, ProjectNamed.of("In Garden planner Mod Manager, what version is it?", names))
        assertEquals(project, ProjectNamed.of("Garden-planner-Mod-Manager: add a dark mode", names))
        assertEquals("/home/me/Projects/Recipe-Box", ProjectNamed.of("@Recipe-Box build it", names))
        assertEquals(null, ProjectNamed.of("Give me a random number", names))
        assertEquals(null, ProjectNamed.of("What's the newest Android version?", names))
        assertEquals("a sentence with a colon is not a prefix", null, ProjectNamed.of("Here is what I think about windmills and more: nothing", names))
    }

    /** The owner's way (P5/H5): a named project opens a card; a follow-up goes to the same task; ✕ makes the next a chat. */
    @Test fun aNamedProjectBecomesOneCardAndAFollowUpGoesToItsTaskUntilLeft() {
        engine.send("In Garden planner Mod Manager, what version is it?"); settle()
        val card = engine.state.value.chat.turns.last()
        assertEquals("1794-1-1", card.task)
        assertEquals("Started in Garden-planner-Mod-Manager", card.text)
        engine.send("And when was it released?"); settle()
        assertEquals("1794-1-1", pc.sent.last().task)
        assertEquals("Added to the task in Garden-planner-Mod-Manager", engine.state.value.chat.turns.last().text)
        engine.leaveTask()
        engine.send("Give me a random number"); settle()
        assertEquals(null, pc.sent.last().task)
        assertEquals("A chat answer.", engine.state.value.chat.turns.last().text)
    }

    @Test fun aDoneTaskLetsTheChatGoBackToGeneral() {
        engine.send("In Garden planner Mod Manager, what version is it?"); settle()
        pc.done += "1794-1-1"
        engine.send("What's the capital of Norway?"); settle()
        assertEquals(null, pc.sent.last().task)
    }

    @Test fun justChatArchivesTheTaskAndAnswersAsAChat() {
        engine.send("In Garden planner Mod Manager, what version is it?"); settle()
        engine.justChat(); settle()
        assertEquals(listOf("1794-1-1"), pc.dropped.toList())
        assertTrue(pc.sent.last().chatOnly)
        val turns = engine.state.value.chat.turns
        assertEquals(listOf(true to "In Garden planner Mod Manager, what version is it?", false to "A chat answer."), turns.map { it.user to it.text })
    }

    @Test fun offlineANamedProjectGetsAPlainAnswerAndOneLine() {
        pc.reachable = false
        engine.send("In Garden planner Mod Manager, what version is it?"); settle()
        assertEquals("phone", engine.state.value.chat.turns.last().ran)
        assertEquals(Engine.PROJECT_NEEDS_PC, engine.state.value.note)
        assertEquals(null, engine.state.value.problem)
    }

    @Test fun onScreenTheCardOpensTheTaskAndChangeOffersJustChatFirst() {
        val nav = Navigator()
        val station = FakeStation().also { it.set { s -> s.copy(pcTalks = true) } }
        compose.setContent {
            CompositionLocalProvider(LocalPhoneEngine provides engine) { MimirTheme { AppRoot(station, nav) } }
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Message $AI_NAME").performTextInput("In Garden planner Mod Manager, what version is it?")
        compose.onNodeWithContentDescription("Send").performClick()
        settle(); compose.waitForIdle()
        compose.onNodeWithText("Started in Garden-planner-Mod-Manager").assertIsDisplayed()
        compose.onNodeWithText("In Garden-planner-Mod-Manager").assertIsDisplayed()
        compose.onNodeWithText("change").performClick(); compose.waitForIdle()
        compose.onNodeWithText("Just chat").assertIsDisplayed().performClick()
        compose.waitForIdle(); settle(); compose.waitForIdle()
        compose.onNodeWithText("A chat answer.").assertIsDisplayed()
        compose.onNodeWithText("In Garden-planner-Mod-Manager").assertDoesNotExist()
    }
}
