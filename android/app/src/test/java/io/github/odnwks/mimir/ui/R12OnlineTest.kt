package io.github.odnwks.mimir.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.online.Net
import io.github.odnwks.mimir.online.OnlineModels
import io.github.odnwks.mimir.online.Setup
import io.github.odnwks.mimir.online.OnlineModelsTest
import io.github.odnwks.mimir.standalone.Engine
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Free online models for a phone that can't run one: the store's own screen, the setup, who answers, and the Whisper rule. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class R12OnlineTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val nav = Navigator()
    private val key = "sk-or-v1-ABCDEFGHIJKLMNOP1234567890wxyz"
    private fun setup(model: String? = "meta-llama/llama-3.3-70b-instruct:free") = Setup("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", key, model)

    @Before fun up() { Net.forgetAnswerHost("router.example.org"); OnlineModels.init(tmp.newFolder(), OnlineModelsTest.PlainSealer()); OnlineModels.guard = { } }
    @After fun down() { OnlineModels.guard = Net::answerGuard }

    private fun store(supported: Boolean): Engine {
        val e = testEngine(tmp.newFolder(), ready = false, supported = supported)
        compose.setContent {
            MimirTheme { Column(Modifier.verticalScroll(rememberScrollState())) { PhoneStore(PhoneModels(e.state, e::choose, null, 4_000_000_000L), nav) } }
        }
        compose.waitForIdle()
        return e
    }

    @Test fun aPhoneThatCantRunAModelSeesFreeOnlineModelsInsteadOfModelsItCantRun() {
        store(supported = false)
        compose.onNodeWithText("📱 This phone: 4 GB memory. That's too little to run a model by itself.").assertIsDisplayed()
        compose.onNodeWithText(ONLINE_STILL_CHAT, substring = true).assertExists()
        compose.onNodeWithText(ONLINE_HEADING).assertExists()
        compose.onNodeWithText("OpenRouter").assertExists()
        compose.onNodeWithText(ONLINE_OPENROUTER_ABOUT).assertExists()
        compose.onNodeWithText("Groq").assertExists()
        compose.onNodeWithText(ONLINE_GROQ_ABOUT).assertExists()
        compose.onNodeWithText(ONLINE_OWN_ABOUT).assertExists()
        compose.onNodeWithText(ONLINE_KNOW, substring = true).assertExists()
        compose.onNodeWithText(ONLINE_CONNECT_PC).assertExists()
        // None of the models it can't run are offered.
        assertTrue(compose.onAllNodesWithText("FOUND FOR THIS PHONE").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("Get").fetchSemanticsNodes().isEmpty())
    }

    @Test fun aPhoneThatCanRunModelsGetsTheSameSectionAtTheEnd() {
        store(supported = true)
        compose.onNodeWithText("FOUND FOR THIS PHONE").assertExists()
        compose.onNodeWithText(ONLINE_HEADING).assertExists()
        compose.onNodeWithText(ONLINE_KNOW, substring = true).assertExists()
        assertTrue(compose.onAllNodesWithText(ONLINE_STILL_CHAT, substring = true).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText(ONLINE_CONNECT_PC).fetchSemanticsNodes().isEmpty())
    }

    @Test fun aPastedKeyThatDoesntWorkSaysSoInRed() {
        OnlineModels.transport = { _, _ -> OnlineModels.Resp(401, "{}") }
        store(supported = false)
        compose.onNodeWithContentDescription("Set up OpenRouter").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Set up OpenRouter").assertIsDisplayed()
        compose.onNodeWithText("Open their website").assertExists()
        // The Paste button reads the clipboard.
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("k", "  $key\n"))
        compose.onNodeWithText("Paste").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(OnlineModels.BAD_KEY).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(OnlineModels.BAD_KEY).assertIsDisplayed()
        // Never in full on screen: the first 5 and last 4 only.
        assertTrue(compose.onAllNodesWithText(key, substring = true).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("sk-••••••wxyz").assertExists()
        assertFalse(OnlineModels.state.value.setups.any { it.id == "openrouter" })
    }

    @Test fun aKeyThatWorksIsTestedAtOnceAndDoneKeepsItAndAnswersOnAPhoneThatCantRunAModel() {
        OnlineModels.transport = { _, _ -> OnlineModels.Resp(200, """{"data":[{"id":"a/llama-3.3-70b-instruct:free"},{"id":"b/small-8b:free"}]}""") }
        store(supported = false)
        compose.onNodeWithContentDescription("Set up OpenRouter").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Your key").performTextInput(key)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("✓ The key works. 2 free models found.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(ONLINE_BEST).assertIsDisplayed()
        compose.onNodeWithText("Done").performClick()
        compose.waitForIdle()
        val s = OnlineModels.setup("openrouter")!!
        assertEquals(key, s.key); assertEquals("a/llama-3.3-70b-instruct:free", s.model); assertTrue(s.auto)
        assertEquals("openrouter", OnlineModels.answering()?.id)
    }

    @Test fun whoShouldAnswerGetsAnOnlineCardWithWhereMessagesGo() {
        val none = whoCards(Answerer.Phone, phoneCan = false, paired = false, claudeOffered = false)
        assertEquals(1, none.size)
        val cards = whoCards(Answerer.Phone, phoneCan = false, paired = false, claudeOffered = false, online = setup(), onlineChosen = true)
        assertEquals(2, cards.size)
        val c = cards.last()
        assertTrue(c.online && c.chosen && c.enabled)
        assertEquals("OpenRouter · llama-3.3-70b-instruct", c.title)
        assertEquals("Free, fast. Your messages go to OpenRouter.", c.about)
        assertFalse(cards.first().chosen)
        // Not answering: the card is offered, not chosen.
        assertFalse(whoCards(Answerer.Pc, true, true, true, setup(), false).last().chosen)
    }

    @Test fun anOnlineModelAnswersOnAPhoneThatCantRunOneAndTheReplyIsLabelledWithTheService() {
        OnlineModels.save(setup()); OnlineModels.answerWith("openrouter")
        OnlineModels.transport = { _, onLine ->
            "data: {\"choices\":[{\"delta\":{\"content\":\"Hi \"}}]}\n\ndata: {\"choices\":[{\"delta\":{\"content\":\"there.\"}}]}\n\ndata: [DONE]".lines().forEach { onLine?.invoke(it) }
            OnlineModels.Resp(200, "")
        }
        val e = testEngine(tmp.newFolder(), ready = false, supported = false)
        assertTrue(e.ask("Hello"))
        val end = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < end && e.state.value.chat.turns.size < 2) Thread.sleep(20)
        val reply = e.state.value.chat.turns.last()
        assertEquals("Hi there.", reply.text)
        assertEquals("OpenRouter · llama-3.3-70b-instruct", reply.modelWords)
        assertFalse(reply.user)
    }

    @Test fun aWhisperNeverUsesAnOnlineModel() {
        OnlineModels.save(setup()); OnlineModels.answerWith("openrouter")
        val e = testEngine(tmp.newFolder(), ready = true)
        e.startWhisper()
        assertFalse(e.state.value.whisper)
        assertEquals("A Whisper can't use an online model. Choose who answers instead, then start the Whisper.", e.state.value.problem)
        // Once the online model isn't answering, a Whisper starts as always.
        OnlineModels.answerWith(null)
        e.startWhisper()
        assertTrue(e.state.value.whisper)
        // ...and in a Whisper an online model answers nothing, even if it were chosen again.
        OnlineModels.answerWith("openrouter")
        assertFalse(e.onlineAnswers())
    }

    @Test fun anOwnAddressIsNotAskedUntilCheckKeyIsTappedAndTheHostIsShownFirst() {
        var asked = 0
        OnlineModels.transport = { _, _ -> asked++; OnlineModels.Resp(200, """{"data":[{"id":"m1"}]}""") }
        store(supported = false)
        compose.onNodeWithContentDescription("Add another service").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Service address").performTextInput("https://router.example.org/v1")
        compose.onNodeWithContentDescription("Your key").performTextInput(key)
        compose.waitForIdle(); Thread.sleep(900); compose.waitForIdle()
        // Not asked, not allowed: only the host is shown.
        assertEquals(0, asked)
        assertFalse(Net.answerHostAllowed("router.example.org"))
        compose.onNodeWithText(keySentTo("router.example.org")).assertExists()
        compose.onNodeWithText("Check key").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("✓ The key works. 1 model found.").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, asked)
        assertTrue(Net.answerHostAllowed("router.example.org"))
        Net.forgetAnswerHost("router.example.org")
    }

    @Test fun aFailedCheckOfAnOwnAddressForgetsTheHost() {
        OnlineModels.transport = { _, _ -> OnlineModels.Resp(401, "{}") }
        store(supported = false)
        compose.onNodeWithContentDescription("Add another service").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Service address").performTextInput("https://other.example.net/v1")
        compose.onNodeWithContentDescription("Your key").performTextInput(key)
        compose.onNodeWithText("Check key").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(OnlineModels.BAD_KEY).fetchSemanticsNodes().isNotEmpty() }
        assertFalse(Net.answerHostAllowed("other.example.net"))
    }

    @Test fun pastingTakesTheKeyOffTheClipboardAndSaysSo() {
        OnlineModels.transport = { _, _ -> OnlineModels.Resp(401, "{}") }
        store(supported = false)
        compose.onNodeWithContentDescription("Set up OpenRouter").performScrollTo().performClick()
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("k", key))
        compose.onNodeWithText("Paste").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(CLIPBOARD_CLEARED).assertExists()
        assertTrue(cm.primaryClip == null || cm.primaryClip!!.itemCount == 0 || cm.primaryClip!!.getItemAt(0).text.isNullOrEmpty())
    }

    @Test fun theKeyIsNotKeptInSavedState() {
        OnlineModels.transport = { _, _ -> OnlineModels.Resp(401, "{}") }
        val restore = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        restore.setContent { MimirTheme { OnlineSetupDialog(OnlineModels.OPENROUTER, canRun = false, onClose = {}) } }
        compose.onNodeWithContentDescription("Your key").performTextInput(key)
        restore.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithText("Paste your key here").fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun onlineAnsweringIsSaidInTheFirstWordsAndTheChatMenu() {
        val svc = OnlineModels.OPENROUTER
        assertEquals("OpenRouter answers this chat, so your messages go to OpenRouter.", firstOpenFine(Answerer.Online, svc))
        assertFalse(firstOpenFine(Answerer.Online, svc).contains("stay on this phone"))
        assertFalse(firstOpenLead(Answerer.Online, svc).contains("your phone"))
        assertEquals("your messages go to OpenRouter", sentWords(0, false, "OpenRouter"))
        assertEquals("nothing left this phone", sentWords(0, false))
    }

    @Test fun theMaskShowsAtMostThreeAndFourCharacters() {
        for (k in listOf("sk-or-v1-ABCDEFGHIJKLMNOP", "gsk_abcdefghijklmnop12", "x".repeat(60))) {
            val m = io.github.odnwks.mimir.online.KeyVault.mask(k)
            assertTrue(m.startsWith(k.take(3)) && m.endsWith(k.takeLast(4)))
            assertTrue(m.count { it != '•' } <= 7)
        }
    }
}
