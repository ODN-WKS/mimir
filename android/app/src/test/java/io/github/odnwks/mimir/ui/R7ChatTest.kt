package io.github.odnwks.mimir.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.data.LinkStation
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.online.Source
import io.github.odnwks.mimir.standalone.Engine
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

/**
 * Redesign R7: the owner's words in a bubble, Mímir's answer as plain text with Listen, Copy and Try
 * again under it; "Who should answer?"; and a chat menu with only things about that chat.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class R7ChatTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val nav = Navigator()
    private val voice = FakeReadAloud()
    private lateinit var engine: Engine

    private val question = "Give me three easy dinner ideas"
    private val answer = "Pasta with tomatoes."

    private fun unpaired() = FakeStation(LinkStation.empty("test").copy(paired = false))

    private fun start(
        station: FakeStation = unpaired(), turns: List<Turn> = listOf(Turn(true, question), Turn(false, answer)),
        ready: Boolean = true, supported: Boolean = true,
    ) {
        engine = testEngine(tmp.newFolder(), ready = ready, turns = turns, supported = supported)
        if (turns.isNotEmpty()) engine.newChat()
        compose.setContent {
            CompositionLocalProvider(LocalPhoneEngine provides engine, LocalReadAloud provides voice) { MimirTheme(reduceMotion = true) { AppRoot(station, nav) } }
        }
        compose.waitForIdle()
    }

    /** The chat as it was left: the engine opens the earlier chat when the test needs it open. */
    private fun startOpen(station: FakeStation = unpaired(), turns: List<Turn> = listOf(Turn(true, question), Turn(false, answer))) {
        engine = testEngine(tmp.newFolder(), ready = true, turns = turns)
        compose.setContent {
            CompositionLocalProvider(LocalPhoneEngine provides engine, LocalReadAloud provides voice) { MimirTheme(reduceMotion = true) { AppRoot(station, nav) } }
        }
        compose.waitForIdle()
    }

    private fun waitFor(what: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!what() && System.currentTimeMillis() < until) { Thread.sleep(10); compose.waitForIdle() }
    }

    private fun clipboard(): String {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        return cm.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
    }

    // ---- 1. The messages ----

    @Test fun anAnswerIsInItsBubbleWithListenCopyAndShareAndNoName() {
        startOpen()
        compose.onNodeWithText(answer).assertIsDisplayed()
        // P13 (owner-approved look): Listen, Copy and Share under the answer; Try again stays in the long press.
        compose.onNodeWithText(LISTEN).assertIsDisplayed()
        compose.onNodeWithText(COPY).assertIsDisplayed()
        compose.onNodeWithText(SHARE).assertIsDisplayed()
        compose.onNodeWithText(TRY_AGAIN).assertDoesNotExist()
        compose.onNodeWithText("Hold an answer for Listen, Copy and Share").assertDoesNotExist()
        // No "You" above the owner's bubble, no "Mímir" above the answer, no "Your PC"/phone mark under it.
        compose.onNodeWithText("You").assertDoesNotExist()
        compose.onNodeWithText(AI_NAME).assertDoesNotExist()
        compose.onNodeWithContentDescription("Answered on: This phone").assertDoesNotExist()
        compose.onNodeWithText(question).assertIsDisplayed()
    }

    @Test fun listenReadsTheAnswerAndCopyPutsItOnTheClipboard() {
        startOpen()
        compose.holdAnswerAndPick(answer, LISTEN_ITEM)
        assertEquals(listOf(answer), voice.read)
        // While it reads, a Stop reading control stays one tap away.
        compose.onNodeWithText(STOP_LISTENING).assertIsDisplayed()
        compose.holdAnswerAndPick(answer, COPY)
        assertEquals(answer, clipboard())
    }

    @Test fun tryAgainReplacesTheAnswerAndKeepsTheQuestionOnce() {
        startOpen()
        val firstId = engine.state.value.chat.turns.first().id
        compose.holdAnswerAndPick(answer, TRY_AGAIN)
        waitFor { !engine.state.value.writing && engine.state.value.chat.turns.size >= 2 && engine.state.value.chat.turns.last().version > 1 }
        val turns = engine.state.value.chat.turns
        // No second turn: the question once, and its one answer, written again with a higher version.
        assertEquals(2, turns.size)
        assertEquals(question, turns[0].text)
        assertTrue(turns[0].user)
        assertEquals(firstId, turns[0].id)
        assertFalse(turns[1].user)
        assertTrue(turns[1].version > 1)
    }

    @Test fun aLookedUpTagShowsWhenTheAnswerUsedTheInternet() {
        startOpen(turns = listOf(Turn(true, "What's the weather in Paris today?"), Turn(false, "Paris: 14 degrees and cloudy.", sources = listOf(Source("open-meteo.com", "https://open-meteo.com/", "Paris")))))
        compose.onNodeWithText(LOOKED_UP_TAG).assertIsDisplayed()
        compose.onNodeWithText("open-meteo.com").assertIsDisplayed()
    }

    @Test fun noLookedUpTagWithoutSources() {
        startOpen()
        compose.onNodeWithText(LOOKED_UP_TAG).assertDoesNotExist()
    }

    @Test fun aLongPressOnAMessageOpensCopySelectShareAndListen() {
        startOpen()
        compose.onNodeWithText(answer).performSemanticsAction(SemanticsActions.OnLongClick); compose.waitForIdle()
        for (item in listOf(COPY, SELECT_TEXT, SHARE, LISTEN_ITEM)) compose.onAllNodesWithText(item).fetchSemanticsNodes().let { assertTrue("$item in the menu", it.isNotEmpty()) }
        compose.onNodeWithText("Delete").assertDoesNotExist()
        compose.onNodeWithText(SELECT_TEXT).performClick(); compose.waitForIdle()
        compose.onNodeWithText("Done").assertIsDisplayed()
    }

    @Test fun aLongPressOnTheOwnersMessageHasTheSameMenu() {
        startOpen()
        compose.onNodeWithText(question).performSemanticsAction(SemanticsActions.OnLongClick); compose.waitForIdle()
        compose.onAllNodesWithText(SHARE).onLast().assertIsDisplayed()
        compose.onAllNodesWithText(COPY).onLast().performClick(); compose.waitForIdle()
        assertEquals(question, clipboard())
    }

    // ---- 2. Who should answer? ----

    @Test fun theSheetsCardsSayExactlyWhatTheBriefSaysInEachState() {
        // Unpaired, the phone can run a model: one card, and a way to connect the PC.
        assertEquals(
            listOf(WhoCard(Answerer.Phone, "This phone", "Works anywhere, even with no signal. Your chats stay on this phone.", true, true)),
            whoCards(Answerer.Phone, phoneCan = true, paired = false, claudeOffered = false),
        )
        // The phone can't run a model: its card says so and is disabled, and is never the chosen one.
        val cant = whoCards(Answerer.Pc, phoneCan = false, paired = true, claudeOffered = false)
        assertEquals("This phone can't run a model by itself", cant[0].about)
        assertFalse(cant[0].enabled); assertFalse(cant[0].chosen)
        // Paired: This phone and Your PC.
        val paired = whoCards(Answerer.Pc, phoneCan = true, paired = true, claudeOffered = false)
        assertEquals(listOf("This phone", "Your PC"), paired.map { it.title })
        assertEquals("Smarter and faster. Your chats stay between this phone and your PC.", paired[1].about)
        assertEquals(listOf(false, true), paired.map { it.chosen })
        // Claude offered: the third card.
        val claude = whoCards(Answerer.Claude, phoneCan = true, paired = true, claudeOffered = true)
        assertEquals(listOf("This phone", "Your PC", "Online"), claude.map { it.title })
        assertEquals("Uses your Anthropic plan through your PC. Your messages go to Anthropic.", claude[2].about)
        assertEquals(listOf(false, false, true), claude.map { it.chosen })
        // Claude is never offered unpaired.
        assertEquals(listOf("This phone"), whoCards(Answerer.Phone, true, paired = false, claudeOffered = true).map { it.title })
    }

    @Test fun unpairedTheSheetOffersThisPhoneAndConnectYourPc() {
        start(turns = emptyList())
        compose.openWhoAnswers("This phone")
        compose.onNodeWithText(WHO_TITLE).assertIsDisplayed()
        compose.onNodeWithText(WHO_PHONE_ABOUT).assertIsDisplayed()
        compose.onNodeWithText(WHO_PC_ABOUT).assertDoesNotExist()
        compose.onNodeWithText(WHO_CLAUDE_ABOUT).assertDoesNotExist()
        // Not Quick/Balanced/Best: effort and model live in the chat box.
        compose.onNodeWithText(WHO_CONNECT).performClick(); compose.waitForIdle()
        assertEquals(Screen.Pair, nav.current)
    }

    private fun pairedStation(claude: Boolean) = FakeStation().also { st ->
        st.set { s -> s.copy(pcTalks = true, pc = s.pc.copy(name = "Your PC"), models = s.models.copy(chatsEngine = if (claude) "free" else null)) }
    }

    @Test fun pairedTheSheetOffersThePcAndChoosingItIsKept() {
        start(pairedStation(claude = false), turns = emptyList())
        compose.openWhoAnswers()
        compose.onNodeWithText(WHO_PHONE_ABOUT).assertIsDisplayed()
        compose.onNodeWithText(WHO_PC_ABOUT).assertIsDisplayed()
        compose.onNodeWithText(WHO_CLAUDE_ABOUT).assertDoesNotExist()
        compose.onNodeWithText(WHO_CONNECT).assertDoesNotExist()
        compose.onNodeWithText(WHO_PHONE_ABOUT).performClick(); compose.waitForIdle()
        assertFalse(engine.state.value.usePc)
    }

    @Test fun claudeIsOfferedOnlyWhenThePcOffersIt() {
        val station = pairedStation(claude = true)
        start(station, turns = emptyList())
        compose.openWhoAnswers()
        compose.onNodeWithText(WHO_CLAUDE_ABOUT).assertIsDisplayed()
        compose.onNodeWithText(WHO_CLAUDE_ABOUT).performClick(); compose.waitForIdle()
        assertEquals("paid", station.state.value.models.chatsEngine)
    }

    @Test fun aPhoneThatCantRunAModelHasItsCardDisabled() {
        start(pairedStation(claude = false), turns = emptyList(), ready = false, supported = false)
        compose.openWhoAnswers()
        compose.onNodeWithText(WHO_PHONE_CANT).assertIsDisplayed()
        compose.onNodeWithText(WHO_PHONE_ABOUT).assertDoesNotExist()
    }

    // ---- 3. The chat menu ----

    @Test fun theChatMenuHasExactlyTheRowsAboutThatChat() {
        startOpen()
        compose.openChatOptions()
        for (row in listOf(CHAT_RENAME, CHAT_COPY, CHAT_RULES, CHAT_SENT, CHAT_DELETE)) compose.onNodeWithText(row).assertIsDisplayed()
        compose.onNodeWithText("Things $AI_NAME should always do here").assertIsDisplayed()
        compose.onNodeWithText("nothing left this phone").assertIsDisplayed()
        compose.onNodeWithText("You can undo for a few seconds").assertIsDisplayed()
        // Gone from this sheet.
        compose.onNodeWithText("Whisper").assertDoesNotExist()
        compose.onNodeWithContentDescription("Read-aloud speed", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Reading voice", substring = true).assertDoesNotExist()
        compose.onNodeWithText(PRIVACY_LINE).assertDoesNotExist()
    }

    @Test fun theMenuButtonShowsOnlyWhileAChatWithMessagesIsOpen() {
        start(turns = emptyList())
        compose.onNodeWithContentDescription(CHAT_MENU).assertDoesNotExist()
        engine.ask(question)
        waitFor { !engine.state.value.writing && engine.state.value.chat.turns.size >= 2 }
        compose.waitForIdle()
        compose.onNodeWithContentDescription(CHAT_MENU).assertIsDisplayed()
    }

    @Test fun copyTheWholeChatPutsEveryTurnOnTheClipboard() {
        startOpen()
        compose.openChatOptions()
        compose.onNodeWithText(CHAT_COPY).performClick(); compose.waitForIdle()
        assertEquals("You: $question\n\n$AI_NAME: $answer", clipboard())
    }

    @Test fun rulesForThisChatOpensThisChatsOwnRulesAndWhatWasSentShowsThisChatsNumbers() {
        startOpen()
        compose.openChatOptions()
        compose.onNodeWithText(CHAT_RULES).performClick(); compose.waitForIdle()
        // A small sheet of this chat's own rules, not the global page.
        assertEquals(Screen.Chat, nav.current)
        compose.onNodeWithText("Rules for this chat", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Save").performClick(); compose.waitForIdle()
        compose.openChatOptions()
        compose.onNodeWithText(CHAT_SENT).performClick(); compose.waitForIdle()
        assertEquals(Screen.Chat, nav.current)
        compose.onNodeWithText("Nothing from this chat has left this phone.").assertIsDisplayed()
        compose.onNodeWithText("No lookups were made in this chat.").assertIsDisplayed()
    }

    @Test fun deleteAsksAndCanBeUndone() {
        startOpen()
        compose.openChatOptions()
        compose.onNodeWithText(CHAT_DELETE).performClick(); compose.waitForIdle()
        compose.onNodeWithText(UNDO_LINE).assertIsDisplayed()
    }

    @Test fun sentWordsSayWhereTheBytesWent() {
        assertEquals("nothing left this phone", sentWords(0, pc = false))
        assertEquals("12 KB, only to your PC", sentWords(12_000, pc = true))
        assertEquals("0 KB, only to your PC", sentWords(0, pc = true))
    }

    @Test fun aWholeChatAsPlainTextNamesWhoSaidWhat() {
        assertEquals("You: hi\n\nClaude: hello", chatAsPlainText(listOf(true to "hi", false to "hello "), "Claude"))
    }

    // ---- Findability: every removed row's function is still reachable ----

    @Test fun everyRemovedRowsFunctionIsStillReachable() {
        startOpen()
        // Chat history, New Whisper, New chat: the side menu.
        compose.openMenu()
        compose.onNodeWithText(MENU_CHATS.uppercase()).assertIsDisplayed()
        compose.onAllNodesWithText(MENU_WHISPER).fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
        compose.onAllNodesWithText(MENU_NEW_CHAT).fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
    }

    @Test fun pressAndHoldOnAChatInTheSideMenuOpensItsMenu() {
        startOpen()
        engine.rename("Dinner ideas"); compose.waitForIdle()
        compose.menuRowHold("Dinner ideas")
        compose.onNodeWithText(CHAT_COPY).assertIsDisplayed()
        compose.onNodeWithText(CHAT_DELETE).assertIsDisplayed()
    }
}
