package io.github.odnwks.mimir.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.data.LinkStation
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.standalone.Catalog
import io.github.odnwks.mimir.standalone.Effort
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

/** The phone's empty chat (redesign R4): first hello, everyday question, starters, the amber pill, the box. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h740dp-xhdpi")
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class HomeEmptyTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private fun prefs() = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>().getSharedPreferences("phone_chat", 0)

    private fun open(hasChatted: Boolean) {
        prefs().edit().putBoolean("has_chatted", hasChatted).commit()
        val engine = testEngine(tmp.newFolder(), ready = true)
        val station = FakeStation(LinkStation.empty("test").copy(paired = false))
        compose.setContent { CompositionLocalProvider(LocalPhoneEngine provides engine) { MimirTheme { AppRoot(station, Navigator()) } } }
        compose.waitForIdle()
    }

    // ---- first open or everyday

    @Test fun firstOpenOnlyWhenNothingWasEverSent() {
        assertTrue(isFirstOpen(hasChatted = false, anyChat = false))
        assertFalse(isFirstOpen(hasChatted = true, anyChat = false))
        assertFalse(isFirstOpen(hasChatted = false, anyChat = true))
    }

    @Test fun theFirstOpenWordsAreExact() {
        assertEquals("Hi, I'm Mímir.", HELLO)
        assertEquals("Ask me anything. Private by design.", firstOpenLead(Answerer.Phone))
        assertEquals(
            "Your chats stay on this phone. Nobody else can see them. When I need the internet, like for the weather, I look things up discreetly: only the few words needed, with no name or account attached.",
            firstOpenFine(Answerer.Phone),
        )
        assertEquals("Ask me anything. Private by design.", firstOpenLead(Answerer.Pc))
        assertEquals(
            "When I need the internet, like for the weather, I look things up discreetly: only the few words needed, with no name or account attached.",
            firstOpenFine(Answerer.Pc),
        )
    }

    @Test fun whenClaudeAnswersTheDimLineSaysSo() {
        assertEquals("An online model answers this chat, so your messages go to Anthropic.", firstOpenFine(Answerer.Claude))
        compose.setContent { MimirTheme { HomeEmpty(first = true, answerer = Answerer.Claude) } }
        compose.onNodeWithText("An online model answers this chat, so your messages go to Anthropic.").assertIsDisplayed()
        compose.onNodeWithText("Your chats stay on this phone", substring = true).assertDoesNotExist()
    }

    @Test fun theVeryFirstOpenShowsTheHelloAndTheConnectLine() {
        open(hasChatted = false)
        compose.onNodeWithText("Hi, I'm Mímir.").assertIsDisplayed()
        compose.onNodeWithText(firstOpenLead(Answerer.Phone)).assertIsDisplayed()
        compose.onNodeWithText("What's the weather?", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Have a PC with Mímir?", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Hi! What can I help with?").assertDoesNotExist()
        compose.onNodeWithText("OK").assertDoesNotExist()
        // The ✕ hides the line for good.
        compose.onNodeWithContentDescription("Hide this").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Have a PC with Mímir?", substring = true).assertDoesNotExist()
        assertTrue(prefs().getBoolean("connect_hint_hidden", false))
    }

    @Test fun everydayIsOnlyTheQuestionAndTheBox() {
        open(hasChatted = true)
        compose.onNodeWithText("What can I help with?").assertIsDisplayed()
        compose.onNodeWithText("Hi, I'm Mímir.").assertDoesNotExist()
        compose.onNodeWithText("Have a PC with Mímir?", substring = true).assertDoesNotExist()
    }

    @Test fun tappingAStarterSendsIt() {
        val engine = testEngine(tmp.newFolder(), ready = true)
        val station = FakeStation(LinkStation.empty("test").copy(paired = false))
        compose.setContent { CompositionLocalProvider(LocalPhoneEngine provides engine) { MimirTheme { AppRoot(station, Navigator()) } } }
        compose.onNodeWithText("Explain it simply", substring = true).performClick()
        compose.waitUntil(5000) { engine.state.value.chat.turns.isNotEmpty() }
        assertEquals("Explain it simply", engine.state.value.chat.turns.first().text)
        assertTrue(prefs().getBoolean("has_chatted", false))
    }

    // ---- starters

    @Test fun firstOpenStartersAreTheThreeFixedOnes() {
        assertEquals(listOf("What's the weather?", "Explain it simply", "Help me write"), startersFor(true, false, 0, null, null).map { it.title })
        assertEquals(listOf("Today and tomorrow", "Anything, in plain words", "A message or a note"), startersFor(true, false, 0, null, null).map { it.sub })
    }

    @Test fun everydayStartersAreOnlyThoseThatApply() {
        val now = 1_800_000_000_000L
        val day = 86_400_000L
        assertEquals(listOf("What's the weather?"), startersFor(false, false, 0, null, null).map { it.title })
        val all = startersFor(false, true, 2, "Grocery list for the week", "c9", now - day, now)
        assertEquals(listOf("Read me my updates", "Grocery list for the week", "What's running?", "What's the weather?"), all.map { it.title })
        assertEquals("2 new", all[0].sub)
        assertEquals("Carry on from yesterday", all[1].sub)
        assertEquals("On your PC", all[2].sub)
        assertTrue(startersFor(false, false, 0, "Trip ideas", "c1", now - day, now).none { it.title == "What's running?" || it.title == "Read me my updates" })
    }

    // ---- the amber pill

    @Test fun thePillShowsHidesAndComesBackWhenTheCountChanges() {
        assertTrue(pillVisible(1, -1)); assertFalse(pillVisible(0, -1))
        assertFalse(pillVisible(2, 2)); assertTrue(pillVisible(3, 2))
    }

    @Test fun thePillSaysHowManyAndOpensNeedsYou() {
        var opened = 0
        compose.setContent { MimirTheme { NeedsYouPill(1, -1, {}, { opened++ }) } }
        compose.onNodeWithText("1 thing needs you").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test fun thePillSaysThingsNeedYouForMoreThanOne() {
        compose.setContent { MimirTheme { NeedsYouPill(3, -1, {}, {}) } }
        compose.onNodeWithText("3 things need you").assertIsDisplayed()
    }

    @Test fun aSidewaysSwipeDismissesThePillForThatCount() {
        var dismissed by mutableStateOf(-1)
        compose.setContent { MimirTheme { NeedsYouPill(2, dismissed, { dismissed = it }, {}) } }
        compose.onNodeWithText("2 things need you").performTouchInput { swipeRight() }
        compose.waitForIdle()
        assertEquals(2, dismissed)
        compose.onAllNodesWithText("2 things need you").assertCountEquals(0)
    }

    @Test fun thePillGoesByItselfAtZero() {
        var n by mutableStateOf(1)
        compose.setContent { MimirTheme { NeedsYouPill(n, -1, {}, {}) } }
        compose.onNodeWithText("1 thing needs you").assertIsDisplayed()
        n = 0
        compose.waitForIdle()
        compose.onAllNodesWithText("1 thing needs you").assertCountEquals(0)
    }

    // ---- the box

    @Test fun bothChipsAndSendFitInsideTheBoxAt360dp() {
        val model = Catalog.default
        compose.setContent {
            MimirTheme {
                val voice = VoiceInput(androidx.compose.ui.platform.LocalContext.current, station = null, phoneSpeech = { null to null })
                Box(Modifier.padding(horizontal = 12.dp)) {
                    MimirComposer("", {}, waiting = false, writing = false, voice = voice, onSend = {}, onStop = {},
                        chip = { OptionsChip("${model.chipWord} · ${Effort.Balanced.words}", "Model and effort: ${model.chipWord} · ${Effort.Balanced.words}. Change") {} })
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Message Mímir").assertIsDisplayed()
        // P12: one "Model · Effort" chip in the centre of the row, as on a task.
        compose.onNodeWithContentDescription("Model and effort: ${model.chipWord} · Balanced. Change").assertIsDisplayed()
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val send = compose.onNodeWithContentDescription("Send").fetchSemanticsNode().boundsInRoot
        val edge = root.right - 12f * 2f // 12 dp of padding at 2x
        assertTrue("Send (${send.right}) stays inside the box edge ($edge)", send.right <= edge)
        // The chip shows the beginner word and the effort's word.
        compose.onNodeWithText("${model.chipWord} · Balanced").assertIsDisplayed()
    }

    private fun chipsAt360() {
        val model = Catalog.default
        compose.setContent {
            MimirTheme {
                val voice = VoiceInput(androidx.compose.ui.platform.LocalContext.current, station = null, phoneSpeech = { null to null })
                Box(Modifier.padding(horizontal = 12.dp)) {
                    MimirComposer("", {}, waiting = false, writing = false, voice = voice, onSend = {}, onStop = {},
                        chip = { OptionsChip("${pcModelWord("auto")} · ${Effort.Balanced.words}", "Model and effort. Change") {} })
                }
            }
        }
        compose.waitForIdle()
        // The chip says what the model is and the effort (P12: "Model · Effort", as on a task).
        compose.onNodeWithText("Auto-pick · Balanced").assertIsDisplayed()
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val send = compose.onNodeWithContentDescription("Send").fetchSemanticsNode().boundsInRoot
        assertTrue(send.right <= root.right - 12f * root.width / 360f)
    }

    @Test fun theEffortChipSaysEffortAndThePcsAutoIsAutoPickAt360dp() = chipsAt360()

    @Config(fontScale = 1.15f)
    @Test fun theSameAtFontScale115() = chipsAt360()

    @Test fun thePlaceChipIsGoneFromTheBox() {
        val station = FakeStation().also { it.set { s -> s.copy(pcTalks = true, pc = s.pc.copy(name = "Your PC")) } }
        val engine = testEngine(tmp.newFolder(), ready = true)
        compose.setContent { CompositionLocalProvider(LocalPhoneEngine provides engine) { MimirTheme { AppRoot(station, Navigator()) } } }
        compose.waitForIdle()
        compose.onAllNodesWithContentDescription("Answers come from", substring = true).assertCountEquals(0)
        compose.onNodeWithContentDescription("Model and effort:", substring = true).assertIsDisplayed()
    }

    @Test fun haloIsCenteredOnThePupilAndTheClosedEye() {
        assertEquals(284f to 202f, EyeArt.haloCenter(open = true))
        assertEquals(284f to 229f, EyeArt.haloCenter(open = false))
    }

    @Test fun haloFadesSmoothlyToExactlyZero() {
        val st = EyeArt.haloStops()
        assertEquals(0f, st.last().second, 0f)
        assertEquals(0.24f, st.first().second, 1e-6f)
        assertEquals(1f, st.last().first, 0f)
        st.zipWithNext().forEach { (a, b) -> assertTrue(b.second < a.second && b.first > a.first) }
        assertEquals(0.04724f, EyeArt.haloAlpha(0.5f), 1e-4f)
    }

    @Test fun helloEyeIsAbout250ButShrinksOnShortScreens() {
        assertEquals(250f, helloEyeDp(700f, 1f), 0.01f)
        val short = helloEyeDp(350f, 1.15f)
        assertTrue(short in 64f..250f)
        assertTrue(short + 212f * 1.15f + 18f <= 350f || short == 64f)
    }

    @Config(qualifiers = "w360dp-h640dp-xhdpi", fontScale = 1.15f)
    @Test fun helloShowsEyeWithoutRingAndKeyboardHidesEyeAndSmallLine() {
        var kb by mutableStateOf(false)
        compose.setContent { MimirTheme { HomeEmpty(first = true, answerer = Answerer.Pc, keyboardOpen = kb) } }
        compose.onNodeWithTag("hello-eye").assertIsDisplayed()
        compose.onNodeWithText("Ask me anything. Private by design.").assertIsDisplayed()
        kb = true
        compose.waitForIdle()
        compose.onAllNodesWithTag("hello-eye").assertCountEquals(0)
        compose.onAllNodesWithText(firstOpenFine(Answerer.Pc)).assertCountEquals(0)
        compose.onNodeWithText("Hi, I'm Mímir.").assertIsDisplayed()
        compose.onNodeWithText("Ask me anything. Private by design.").assertIsDisplayed()
    }

    @Test fun aLastChatNamedLikeAStarterDoesNotRepeatOrCrash() {
        // Owner's S25, 2026-11-21: the last chat was called "What's running?", the same as a starter; two
        // chips with one key crashed the home screen.
        val s = startersFor(first = false, paired = true, unheard = 0, lastTitle = "What's running?", lastChatId = "c1")
        assertEquals(s.map { it.title.lowercase() }.distinct().size, s.size)
        assertTrue(s.first { it.title == "What's running?" }.action is StarterAction.OpenChat)
        compose.setContent { MimirTheme { StarterRow(listOf(s[0], s[0], s[0]), {}) } }
        compose.waitForIdle()
    }
}
