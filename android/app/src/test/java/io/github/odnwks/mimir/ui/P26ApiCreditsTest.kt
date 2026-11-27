package io.github.odnwks.mimir.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.data.StationState
import io.github.odnwks.mimir.data.TaskMessage
import io.github.odnwks.mimir.data.creditsFrom
import io.github.odnwks.mimir.link.Ev
import io.github.odnwks.mimir.link.LEntry
import io.github.odnwks.mimir.link.Wire
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Tab
import io.github.odnwks.mimir.standalone.Turn
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** P26: the owner's API credits on the phone: Status's switch, and "API credits" under answers that used them. */
@RunWith(RobolectricTestRunner::class)
class P26ApiCreditsTest {
    @get:Rule val compose = createComposeRule()
    private val nav = Navigator()

    private fun start(saved: Boolean?, on: Boolean = false, change: (StationState) -> StationState = { it }): FakeStation {
        val st = FakeStation().also { it.set { s -> change(s.copy(models = s.models.copy(apiKeySaved = saved, apiCredits = on))) } }
        compose.setContent { MimirTheme { AppRoot(st, nav) } }
        compose.waitForIdle()
        nav.selectTab(Tab.Status)
        compose.waitForIdle()
        return st
    }

    private fun switch() = compose.onNode(
        hasText(ApiCredits.TITLE) and SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState),
    )

    @Test fun withAKeySavedTheSwitchShowsThePcsStateAndChangesIt() {
        val st = start(saved = true)
        switch().performScrollTo().assertIsEnabled().assertIsOff()
        compose.onNodeWithText(ApiCredits.ABOUT).assertExists()
        compose.onNodeWithText(ApiCredits.NO_KEY).assertDoesNotExist()
        switch().performClick()
        compose.waitForIdle()
        assertTrue(st.state.value.models.apiCredits)
        switch().assertIsOn()
    }

    @Test fun withoutAKeyThereIsNoSwitchOnlyWhereToAddOne() {
        start(saved = false)
        compose.onNodeWithText(ApiCredits.NO_KEY).performScrollTo().assertExists()
        switch().assertDoesNotExist()
    }

    @Test fun anOlderPcShowsNeither() {
        start(saved = null)
        switch().assertDoesNotExist()
        compose.onNodeWithText(ApiCredits.NO_KEY).assertDoesNotExist()
    }

    @Test fun someoneElsesPhoneShowsNeither() {
        start(saved = true) { it.copy(owner = false) }
        switch().assertDoesNotExist()
        compose.onNodeWithText(ApiCredits.NO_KEY).assertDoesNotExist()
    }

    @Test fun whileThePcIsAwayItCantBeChanged() {
        start(saved = true, on = true) { it.copy(pc = it.pc.copy(online = false)) }
        switch().performScrollTo().assertIsNotEnabled().assertIsOn()
        // One line for both switches.
        compose.onNodeWithText(FreeWhenOut.AWAY).assertExists()
    }

    @Test fun thePcsReplyIsReadAndTheChangeSendsOnlyTheNewField() {
        fun list(extra: String) = Wire.modelList(JSONObject("""{"free":[],"free_model":null,"switch_to_free":false,"ollama":""$extra}"""))!!
        val m = list(""","api_credits":true,"api_key_saved":true""")
        assertEquals(true, m.apiKeySaved)
        assertTrue(m.apiCredits)
        assertNull("an older PC", list("").apiKeySaved)
        val call = JSONObject(Wire.setApiCredits(true)).getJSONObject("SetFreeChoice")
        assertTrue(call.getBoolean("api_credits"))
        assertTrue(call.isNull("switch_to_free"))
        assertTrue(call.isNull("free_model"))
        assertFalse(JSONObject(Wire.setFreeChoice(true, null)).getJSONObject("SetFreeChoice").has("api_credits"))
    }

    @Test fun aTaskHeaderSaysWhetherItRunsOnTheCredits() {
        fun header(extra: String) = (Wire.event(
            """{"seq":1,"event":{"Task":{"header":{"id":"t","title":"T","project":"/p/X","status":"Done","busy":false$extra,"model":null,"created_unix":0,"asks":[]}}}}""",
        ).second as Ev.TaskHeader).task
        assertTrue(header(""","via_api":true""").viaApi)
        assertFalse(header("").viaApi)
    }

    @Test fun onlyRepliesAfterTheMoveOntoTheCreditsAreMarked() {
        val es = listOf(LEntry.You("a"), LEntry.Claude("plan answer"), LEntry.Note(ApiCredits.TASK_NOTE), LEntry.Claude("paid answer"))
        assertEquals(3, creditsFrom(es, viaApi = true))
        assertEquals(es.size, creditsFrom(es, viaApi = false))
        assertEquals("no note, no claim", 2, creditsFrom(es.take(2), viaApi = true))
    }

    @Test fun theNoteThePhoneLooksForIsThePcsOwn() {
        val pc = java.io.File("../../crates/core/src/runner.rs").readText()
        assertTrue(pc.contains("pub const API_CREDITS_NOTE: &str = \"${ApiCredits.TASK_NOTE}\";"))
    }

    @Test fun aReplyOnTheCreditsSaysSoUnderIt() {
        compose.setContent {
            MimirTheme {
                ReplyMeta(TaskMessage("t-1", true, "Done.", model = "claude-sonnet-5-5", apiCredits = true), null)
            }
        }
        compose.onNodeWithText("API credits", substring = true).assertExists()
    }

    @Test fun aReplyWithNothingElseKnownStillSaysSo() {
        compose.setContent { MimirTheme { ReplyMeta(TaskMessage("t-1", true, "Done.", apiCredits = true), null) } }
        compose.onNodeWithText("API credits").assertExists()
    }

    @Test fun aChatAnswerOnTheCreditsKeepsTheWords() {
        fun author(words: String) = answerAuthor(Turn(false, "x", ran = "paid", modelWords = words), "Desk PC")
        assertEquals("Claude · API credits", author("Claude · API credits"))
        assertEquals("Claude · Sonnet 4.6 · API credits", author("Claude Sonnet 4.6 · API credits"))
        assertEquals("Claude · Sonnet 4.6", author("Claude Sonnet 4.6 · Online"))
    }
}
