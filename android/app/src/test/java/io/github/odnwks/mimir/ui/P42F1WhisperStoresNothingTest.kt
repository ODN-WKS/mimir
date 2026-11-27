package io.github.odnwks.mimir.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.github.odnwks.mimir.data.ExampleData
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.nav.ChatAsk
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Sheet
import io.github.odnwks.mimir.standalone.Draft
import io.github.odnwks.mimir.standalone.Engine
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** P42 fix 1: a Whisper stores nothing on any path, starts where it can answer, ends on leaving, and moves no task. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class P42F1WhisperStoresNothingTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val nav = Navigator()
    private lateinit var engine: Engine
    private val secret = "secret whisper words"
    private val started = mutableListOf<String>()

    private fun show(registry: SaveableStateRegistry? = null, paired: Boolean = false, ready: Boolean = true, projects: Boolean = true) {
        engine = testEngine(tmp.newFolder(), ready = ready, supported = true)
        compose.setContent {
            val content = @androidx.compose.runtime.Composable {
                CompositionLocalProvider(LocalPhoneEngine provides engine) {
                    MimirTheme(reduceMotion = true) {
                        if (paired) PhoneChat(engine, nav, paired = true, pcOnline = true, pcWhisperFree = true, pcWhisperProjects = projects, startPcWhisper = { started += it; true })
                        else PhoneChat(engine, nav)
                    }
                }
            }
            if (registry != null) CompositionLocalProvider(LocalSaveableStateRegistry provides registry) { content() } else content()
        }
        compose.waitForIdle()
    }

    private fun stored(): Map<String, String> = engine.drafts.texts()

    // F1: words typed in a Whisper on this phone never go into the draft store, during or after it.
    @Test fun aPhoneWhispersWordsNeverReachTheDraftStore() {
        show()
        engine.startWhisper(); compose.waitForIdle()
        assertTrue(engine.state.value.whisper)
        compose.onNodeWithContentDescription("Whisper to $AI_NAME").performTextInput(secret)
        compose.mainClock.advanceTimeBy(2_000); compose.waitForIdle()
        assertTrue("kept during the Whisper: ${stored()}", stored().values.none { it.contains(secret) })
        engine.endWhisper(); compose.waitForIdle()
        compose.mainClock.advanceTimeBy(2_000); compose.waitForIdle()
        assertTrue("kept after End: ${stored()}", stored().values.none { it.contains(secret) })
    }

    // F1: anything held under a Whisper's chat goes at End.
    @Test fun endingAWhisperForgetsWhatWasInItsBox() {
        engine = testEngine(tmp.newFolder(), ready = true, supported = true)
        engine.startWhisper()
        val id = engine.state.value.chat.id
        engine.drafts.put(id, Draft(secret, emptyList(), emptyList()))
        engine.endWhisper()
        assertEquals("", engine.drafts.get(id).text)
        assertTrue(stored().isEmpty())
    }

    // F1: a Whisper's words are not in the screen state Android keeps.
    @Test fun aPhoneWhispersWordsAreNotInTheSavedScreenState() {
        val registry = SaveableStateRegistry(null) { true }
        show(registry)
        engine.startWhisper(); compose.waitForIdle()
        compose.onNodeWithContentDescription("Whisper to $AI_NAME").performTextInput(secret)
        compose.waitForIdle()
        val saved = compose.runOnIdle { registry.performSave() }
        assertFalse("in the saved state: $saved", saved.toString().contains(secret))
    }

    // F1: the same box outside a Whisper is still kept (nothing else changed).
    @Test fun anOrdinaryChatsBoxIsStillKept() {
        val registry = SaveableStateRegistry(null) { true }
        show(registry)
        compose.onNodeWithContentDescription("Message $AI_NAME").performTextInput("half a thought")
        compose.mainClock.advanceTimeBy(2_000); compose.waitForIdle()
        assertEquals("half a thought", engine.drafts.get(engine.state.value.chat.id).text)
        assertTrue(compose.runOnIdle { registry.performSave() }.toString().contains("half a thought"))
    }

    // F2: the PC has no project but this phone can answer: the Whisper runs on this phone.
    @Test fun withNoProjectOnThePcAPhoneThatCanAnswerTakesTheWhisper() {
        show(paired = true, projects = false)
        nav.askChat(ChatAsk.StartWhisper); compose.waitForIdle()
        assertTrue(engine.state.value.whisper)
        compose.onNodeWithText(WHISPER_NEEDS_PROJECT).assertDoesNotExist()
        compose.onNodeWithContentDescription("Whisper to $AI_NAME").assertIsDisplayed()
    }

    @Test fun whereAWhisperStarts() {
        assertEquals(WhisperStart.Phone, whisperStartFor(paired = true, pcFree = true, pcOnline = true, phoneReady = true, phoneSupported = true, pcProjects = false))
        assertEquals(WhisperStart.Pc, whisperStartFor(paired = true, pcFree = true, pcOnline = true, phoneReady = false, phoneSupported = true, pcProjects = false))
        assertEquals(WhisperStart.Pc, whisperStartFor(paired = true, pcFree = true, pcOnline = true, phoneReady = true, phoneSupported = true, pcProjects = true))
        assertEquals(WhisperStart.Phone, whisperStartFor(paired = true, pcFree = true, pcOnline = false, phoneReady = true, phoneSupported = true, pcProjects = true))
    }

    // F3: "End Whispers when I leave the app" also ends a Whisper on the PC still waiting for its first words.
    @Test fun leavingTheAppEndsAPcWhisperNotYetSent() {
        show(paired = true, ready = false)
        nav.askChat(ChatAsk.StartWhisper); compose.waitForIdle()
        compose.onNodeWithText(WHISPER_CARD_TITLE).assertIsDisplayed()
        compose.onNodeWithContentDescription("Whisper to $AI_NAME").performTextInput(secret)
        compose.waitForIdle()
        engine.appLeft(endOnLeave = true, rotating = false); compose.waitForIdle()
        compose.onNodeWithText(WHISPER_CARD_TITLE).assertDoesNotExist()
        compose.onNodeWithText(secret).assertDoesNotExist()
        assertTrue(started.isEmpty())
    }

    @Test fun turningThePhoneOrTheSettingOffKeepsAPcWhisper() {
        show(paired = true, ready = false)
        nav.askChat(ChatAsk.StartWhisper); compose.waitForIdle()
        engine.appLeft(endOnLeave = true, rotating = true); compose.waitForIdle()
        engine.appLeft(endOnLeave = false, rotating = false); compose.waitForIdle()
        compose.onNodeWithText(WHISPER_CARD_TITLE).assertIsDisplayed()
    }

    // F4: a project made from the Whisper's "Make a project" never becomes the shared pick (it could move a task later).
    @Test fun aProjectMadeForAWhisperIsNeverThePick() {
        val st = FakeStation(ExampleData.awake())
        compose.setContent { MimirTheme(reduceMotion = true) { AppRoot(st, nav) } }
        compose.waitForIdle()
        nav.openSheet(Sheet.ProjectPicker("", newProject = true, whisper = true)); compose.waitForIdle()
        compose.onNodeWithContentDescription("New project name", substring = true).performTextInput("Recipe box")
        compose.waitForIdle()
        compose.onNodeWithText("Make the project").performClick()
        compose.mainClock.advanceTimeBy(2_000); compose.waitForIdle()
        assertTrue("Recipe box" in st.state.value.pickable)
        assertNull(nav.pickedProject)
        assertNull(nav.sheet)
    }
}
