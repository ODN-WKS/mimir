package io.github.odnwks.mimir.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.data.StatusCards
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.online.FakeWeb
import io.github.odnwks.mimir.online.Online
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Asking "status" shows a card in that chat; nothing goes to any model and nothing is kept. */
@RunWith(RobolectricTestRunner::class)
class StatusCardUiTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    @After fun tearDown() = StatusCards.clear()

    @Test fun inATaskStatusShowsTheCardAndClaudeIsNotSentIt() {
        val station = FakeStation()
        val task = station.state.value.tasks.first { it.question == null && it.permission == null && !it.liveElsewhere }
        val nav = Navigator()
        compose.setContent { MimirTheme(reduceMotion = true) { AppRoot(station, nav) } }
        compose.waitForIdle(); nav.open(Screen.Task(task.id)); compose.waitForIdle()
        compose.onNodeWithContentDescription("Reply to this task").performTextInput("status")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(STATUS_CARD_TAG).assertExists()
        compose.onNodeWithText("Made by Mímir. The AI never reads it.").assertExists()
        assertEquals(task.messages.size, station.state.value.task(task.id)!!.messages.size)
        // A second ask replaces the card.
        compose.onNodeWithContentDescription("Reply to this task").performTextInput("status?")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(STATUS_CARD_TAG).assertExists()
        assertEquals(2, StatusCards.shown!!.asked)
    }

    @Test fun inTheOneChatStatusAsksNoModelAndAddsNoMessage() {
        FakeWeb().install()
        Online.set(Online.Settings())
        val pc = FakePc()
        val engine = testEngine(tmp.newFolder(), ready = true).also { it.pc = pc }
        val station = FakeStation()
        compose.setContent { MimirTheme(reduceMotion = true) { PhoneChat(engine, Navigator(), paired = true, pcOnline = true, station = station) } }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Message ${AI_NAME}").performTextInput("status")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(STATUS_CARD_TAG).assertIsDisplayed()
        assertTrue(pc.sent.isEmpty())
        assertTrue(engine.state.value.chat.turns.isEmpty())
        assertTrue(engine.state.value.chat.queued.isEmpty())
        // New chat takes the card away, though the empty chat stays the same one.
        compose.onNodeWithContentDescription("New chat").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(STATUS_CARD_TAG).assertDoesNotExist()
    }
}
