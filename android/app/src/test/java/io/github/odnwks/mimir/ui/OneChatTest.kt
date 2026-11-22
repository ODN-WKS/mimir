package io.github.odnwks.mimir.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.link.Filed
import io.github.odnwks.mimir.link.LChatTurn
import io.github.odnwks.mimir.link.LTalkState
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.online.FakeWeb
import io.github.odnwks.mimir.online.Online
import io.github.odnwks.mimir.standalone.Engine
import io.github.odnwks.mimir.standalone.PcTalk
import io.github.odnwks.mimir.standalone.TalkListener
import io.github.odnwks.mimir.standalone.TalkRequest
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

/** A PC that answers chats as told, remembering what it was sent. */
class FakePc : PcTalk {
    @Volatile var reachable = true
    val sent = java.util.concurrent.CopyOnWriteArrayList<TalkRequest>()
    val stopped = java.util.concurrent.CopyOnWriteArrayList<String>()
    @Volatile var listener: TalkListener? = null
    /** What the PC does with a message: by default it files it as an answer and writes [words]. */
    @Volatile var answer: (TalkRequest, TalkListener) -> Unit = { r, l ->
        l.filed(Filed.Talk("${r.turnId}.a", "pc", "Qwen 3.6 · Your PC"), null)
        words.forEach { l.piece(it, false) }
        // The PC keeps the message and its answer in its chat store, as the real one does.
        val a = LChatTurn("${r.turnId}.a", false, words.joinToString(""), ran = "pc", modelWords = "Qwen 3.6 · Your PC", at = r.turns.last().at + 1)
        val kept = synchronized(this) { chatPut(r.chatId, null, listOf(r.turns.last(), a), false) {}; seq }
        l.done(a.copy(seq = maxOf(kept, 41)), null)
    }
    var words = listOf("Oslo ", "is the capital.")
    /** Recordings the PC turns into words: what it hears, or null (an error). */
    @Volatile var heard: String? = "Run the checks again."
    val hearCalls = java.util.concurrent.atomic.AtomicInteger()
    override fun hear(wav: ByteArray, done: (String?, String?) -> Unit): Boolean {
        if (!reachable) return false
        hearCalls.incrementAndGet()
        Thread { done(heard, if (heard == null) "busy" else null) }.start()
        return true
    }
    @Volatile var state: LTalkState? = null
    val sightRefreshed = java.util.concurrent.atomic.AtomicInteger()
    override fun refreshSight() { sightRefreshed.incrementAndGet() }
    override fun canTalk() = reachable
    /** Pictures ("chat_pictures"): null is an older PC that doesn't know them; true its model sees; false it can't. */
    @Volatile var seesPictures: Boolean? = null
    override fun canSeePictures() = reachable && seesPictures == true
    override fun picturesRefused() = reachable && seesPictures == false
    /** Files ("chat_files"): true when this PC reads PDFs, documents and the like; false is an older PC. */
    @Volatile var takesFiles = false
    override fun canSendFiles() = reachable && takesFiles
    override fun pcName() = "Your PC"
    override fun talk(request: TalkRequest, listener: TalkListener): Boolean {
        if (!reachable) return false
        sent += request
        this.listener = listener
        Thread { answer(request, listener) }.start()
        return true
    }
    override fun stopTalk(chatId: String, turnId: String) { stopped += turnId }
    override fun talkTurn(chatId: String, turnId: String, done: (LTalkState?) -> Unit): Boolean { done(state); return true }
    override fun letGo(answerId: String) {}

    // A PC that keeps chats, as the PC's talk store does (Stage 1, 2.5).
    var seq = 0L
    val chats = LinkedHashMap<String, io.github.odnwks.mimir.link.LChatRecord>()
    override fun canSync() = reachable
    @Synchronized override fun chatPut(chatId: String, name: String?, turns: List<LChatTurn>, deleted: Boolean, done: (io.github.odnwks.mimir.link.LChatPutDone?) -> Unit): Boolean {
        if (!reachable) return false
        val had = chats[chatId]
        if (had?.deleted != null) { done(io.github.odnwks.mimir.link.LChatPutDone(seq, true)); return true }
        seq++
        if (deleted) chats[chatId] = io.github.odnwks.mimir.link.LChatRecord(chatId, null, 0, seq, emptyList(), deleted = 1)
        else {
            val mine = (had?.turns ?: emptyList()).associateBy { it.id }.toMutableMap()
            for (t in turns) if ((mine[t.id]?.version ?: 0) < t.version) mine[t.id] = t.copy(seq = seq)
            chats[chatId] = io.github.odnwks.mimir.link.LChatRecord(chatId, name ?: had?.name, turns.maxOfOrNull { it.at } ?: 0, seq, mine.values.toList())
        }
        done(io.github.odnwks.mimir.link.LChatPutDone(seq, deleted))
        return true
    }
    /** At most this many chats a page (the real PC pages by size). */
    var pageSize = Int.MAX_VALUE
    @Synchronized override fun chatsSince(seq: Long, done: (io.github.odnwks.mimir.link.LChatsPage?) -> Unit): Boolean {
        if (!reachable) return false
        val changed = chats.values.filter { it.seq > seq }.sortedBy { it.seq }
        val page = changed.take(pageSize)
        val more = changed.size > page.size
        done(io.github.odnwks.mimir.link.LChatsPage(if (more) page.last().seq else this.seq, page.map { r -> r.copy(turns = r.turns.filter { it.seq > seq }) }, more))
        return true
    }
    // A project named in the chat.
    val dropped = java.util.concurrent.CopyOnWriteArrayList<String>()
    val done = java.util.concurrent.CopyOnWriteArraySet<String>()
    @Volatile var chatOnlyOk = true
    @Volatile var projects = listOf("/home/me/Projects/Garden-planner-Mod-Manager")
    override fun taskDone(id: String) = id in done
    override fun projectNamed(text: String) = io.github.odnwks.mimir.data.ProjectNamed.of(text, projects)
    override fun canChatOnly() = chatOnlyOk
    override fun dropTask(id: String) { dropped += id }

    /** The PC's copy of every turn, by chat (deleted chats left out). */
    @Synchronized fun kept(): Map<String, List<String>> = chats.values.filter { it.deleted == null }.associate { r -> r.id to r.turns.map { it.text } }
}

/** Stage 1, H2 and H3: one chat, paired or not; the PC answers while it can, this phone otherwise. */
@RunWith(RobolectricTestRunner::class)
class OneChatTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val web = FakeWeb()
    private val pc = FakePc()
    private lateinit var engine: Engine

    @Before fun setUp() {
        web.install()
        Online.set(Online.Settings())
        engine = testEngine(tmp.newFolder(), ready = true).also { it.pc = pc }
    }

    @After fun tearDown() { Engine.PC_SILENT_MS = 30_000L }

    private fun settle() {
        val until = System.currentTimeMillis() + 5_000
        Thread.sleep(20)
        while (engine.state.value.writing && System.currentTimeMillis() < until) Thread.sleep(10)
        assertFalse("the answer never finished", engine.state.value.writing)
    }

    private fun waitUntil(what: String, f: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!f() && System.currentTimeMillis() < until) Thread.sleep(10)
        assertTrue(what, f())
    }

    @Test fun pairedTheMessageGoesToThePcAndItsAnswerIsMarkedYourPc() {
        engine.send("What's the capital of Norway?"); settle()
        val r = pc.sent.single()
        val turns = engine.state.value.chat.turns
        assertEquals(turns[0].id, r.turnId)
        assertEquals("What's the capital of Norway?", r.turns.last().text)
        assertEquals("medium", r.effort)
        assertEquals("Oslo is the capital.", turns[1].text)
        assertEquals("pc", turns[1].ran)
        assertEquals("${turns[0].id}.a", turns[1].id)
        assertTrue(turns[1].seq > 0)
        assertFalse("this phone's model is let go while the PC answers", engine.modelLoaded)
    }

    @Test fun withThePcAwayThisPhoneAnswersInTheSameChatWithNoError() {
        engine.send("What's the capital of Norway?"); settle()
        pc.reachable = false
        engine.send("And of Sweden?"); settle()
        val turns = engine.state.value.chat.turns
        assertEquals(4, turns.size)
        assertEquals(listOf("pc", "phone"), turns.filter { !it.user }.map { it.ran })
        assertEquals(null, engine.state.value.problem)
        assertEquals(1, pc.sent.size)
    }

    @Test fun thisPhoneOnlyKeepsEverythingHere() {
        engine.setUsePc(false)
        engine.send("Hi"); settle()
        assertTrue(pc.sent.isEmpty())
        assertEquals("phone", engine.state.value.chat.turns.last().ran)
    }

    @Test fun aWhisperNeverGoesToThePc() {
        engine.startWhisper()
        engine.send("A secret"); settle()
        assertTrue(pc.sent.isEmpty())
    }

    @Test fun whenThePcSaysItsBusyThisPhoneAnswers() {
        pc.answer = { _, l -> l.filed(Filed.OnPhone("Your PC is busy; this phone answers instead."), null) }
        engine.send("Hi"); settle()
        val last = engine.state.value.chat.turns.last()
        assertEquals("phone", last.ran)
        assertEquals("Your PC is busy; this phone answers instead.", engine.state.value.note)
    }

    @Test fun pairedWithNoModelHereAMessageGoesToThePcStraightAway() {
        val e = testEngine(tmp.newFolder(), ready = false).also { it.pc = pc }
        e.ask("Hi")
        val until = System.currentTimeMillis() + 5_000
        while ((e.state.value.writing || e.state.value.chat.turns.size < 2) && System.currentTimeMillis() < until) Thread.sleep(10)
        assertEquals("pc", e.state.value.chat.turns.last().ran)
    }

    /** Sent before the link is up: kept, then once the PC is reachable it answers; question first, then answer, nothing stuck waiting. */
    @Test fun sentBeforeTheLinkIsUpTheMessageGoesToThePcWhenItConnectsInOrder() {
        val dir = tmp.newFolder()
        val e = Engine(dir, io.github.odnwks.mimir.standalone.Store(java.io.File(dir, "standalone.bin"), PlainCodec),
            WordsRunner(), supported = false, now = System::currentTimeMillis).also { it.pc = pc }
        pc.reachable = false
        assertTrue(e.ask("Final check: what is 6 times 7"))
        // It waits in the chat, marked as waiting for the PC (never as a turn nobody answers).
        assertTrue(e.state.value.chat.turns.isEmpty())
        val first = e.state.value.chat.queued.single()
        // The PC's answer is stamped by the PC's own clock, a little after it got the message.
        pc.answer = { r, l ->
            Thread.sleep(300)
            l.filed(Filed.Talk("${r.turnId}.a", "pc", "Your PC"), null)
            val a = LChatTurn("${r.turnId}.a", false, "42", ran = "pc", modelWords = "Your PC", at = System.currentTimeMillis())
            val kept = synchronized(pc) { pc.chatPut(r.chatId, null, listOf(r.turns.last(), a), false) {}; pc.seq }
            l.done(a.copy(seq = kept), null)
        }
        Thread.sleep(1500)
        pc.reachable = true
        e.linkUp()
        val until = System.currentTimeMillis() + 5_000
        while ((e.state.value.writing || e.state.value.chat.turns.size < 2) && System.currentTimeMillis() < until) Thread.sleep(10)
        Thread.sleep(500)
        val turns = e.state.value.chat.turns
        assertEquals(listOf(true, false), turns.map { it.user })
        assertEquals("42", turns.last().text)
        assertFalse("nothing is left waiting", e.waiting)
        // A waiting message is dated when it goes (bcb6761), so its answer never lands before an earlier one's.
        assertTrue("the message is dated when it was sent, not when it was typed", turns[0].at >= first.at + 1_000)
        assertTrue("the answer follows its own message", turns[1].at >= turns[0].at)
        assertEquals(1, pc.sent.size)
    }

    /** Whatever the clocks say, an answer never sorts before the message it answers. */
    @Test fun anAnswerNeverSortsBeforeItsQuestion() {
        val q = io.github.odnwks.mimir.standalone.Turn(true, "6 times 7?", id = "u1", at = 2_000)
        val a = io.github.odnwks.mimir.standalone.Turn(false, "42", id = "u1.a", at = 1_000, ran = "pc")
        val other = io.github.odnwks.mimir.standalone.Turn(true, "Earlier", id = "u0", at = 500)
        assertEquals(listOf("u0", "u1", "u1.a"), io.github.odnwks.mimir.standalone.ChatSync.inOrder(listOf(a, q, other)).map { it.id })
    }

    /** H3: the link drops mid-answer; it comes back, and the PC's finished answer replaces the words so far, once. */
    @Test fun aDropMidAnswerWaitsForThePcAndTakesItsFinishedAnswer() {
        pc.answer = { r, l ->
            l.filed(Filed.Talk("${r.turnId}.a", "pc", "Qwen 3.6 · Your PC"), null)
            l.piece("Once upon ", false)
            l.lost()
        }
        engine.send("Tell me a long story")
        waitUntil("Waiting for your PC… shows") { engine.state.value.pcWaiting }
        assertEquals("Once upon ", engine.state.value.partial)
        val id = engine.state.value.chat.turns.last().id
        pc.state = LTalkState.Done(LChatTurn("$id.a", false, "Once upon a time, the end.", ran = "pc", at = 1, seq = 9))
        pc.listener!!.back()
        settle()
        val answers = engine.state.value.chat.turns.filter { !it.user }
        assertEquals(listOf("Once upon a time, the end."), answers.map { it.text })
    }

    /** H3: Answer on this phone while waiting: exactly one answer, from this phone, as version 2. */
    @Test fun answerOnThisPhoneLeavesExactlyOneAnswerThatReplacesThePcs() {
        pc.answer = { r, l ->
            l.filed(Filed.Talk("${r.turnId}.a", "pc", "Qwen 3.6 · Your PC"), null)
            l.lost()
        }
        engine.send("Tell me a long story")
        waitUntil("Waiting for your PC… shows") { engine.state.value.pcWaiting }
        engine.answerOnPhone()
        settle()
        val answers = engine.state.value.chat.turns.filter { !it.user }
        assertEquals(1, answers.size)
        assertEquals("phone", answers.single().ran)
        assertEquals(2, answers.single().version)
    }

    @Test fun stopEndsThePcsAnswerAndKeepsTheWordsSoFar() {
        pc.answer = { r, l ->
            l.filed(Filed.Talk("${r.turnId}.a", "pc", "Qwen 3.6 · Your PC"), null)
            l.piece("Once upon ", false)
        }
        engine.send("Tell me a long story")
        waitUntil("words arrive") { engine.state.value.partial.isNotEmpty() }
        engine.stop(); settle()
        assertEquals(listOf(engine.state.value.chat.turns.first().id), pc.stopped.toList())
        assertEquals("Once upon", engine.state.value.chat.turns.last().text)
    }

    /** Paired, "Who should answer?" has a clear way to this phone's own model (the "This phone" card) and back to the PC. */
    @Test fun whoShouldAnswerChoosesThisPhoneWhilePairedAndBackToThePc() {
        val nav = Navigator()
        val station = FakeStation().also { it.set { s -> s.copy(pcTalks = true, pc = s.pc.copy(name = "Your PC")) } }
        compose.setContent { CompositionLocalProvider(LocalPhoneEngine provides engine) { MimirTheme { AppRoot(station, nav) } } }
        compose.waitForIdle()
        compose.openWhoAnswers()
        compose.onNodeWithText(WHO_PHONE_ABOUT).performClick(); compose.waitForIdle()
        assertFalse(engine.state.value.usePc)
        compose.onNodeWithContentDescription("Message $AI_NAME").performTextInput("What's the capital of Norway?")
        compose.onNodeWithContentDescription("Send").performClick()
        settle(); compose.waitForIdle()
        assertEquals("phone", engine.state.value.chat.turns.last().ran)
        compose.openWhoAnswers("This phone")
        compose.onNodeWithText(WHO_PC_ABOUT).performClick(); compose.waitForIdle()
        assertTrue(engine.state.value.usePc)
    }

    /** With no model yet, the row is greyed with why, and a tap goes to Settings › Who answers. */
    @Test fun withNoModelThePhoneRowSaysWhyAndLeadsToModels() {
        val e = testEngine(tmp.newFolder(), ready = false).also { it.pc = pc }
        val nav = Navigator()
        val station = FakeStation().also { it.set { s -> s.copy(pcTalks = true, pc = s.pc.copy(name = "Your PC")) } }
        compose.setContent { CompositionLocalProvider(LocalPhoneEngine provides e) { MimirTheme { AppRoot(station, nav) } } }
        compose.waitForIdle()
        compose.openWhoAnswers()
        compose.onNodeWithText(WHO_PHONE_ABOUT).performClick(); compose.waitForIdle()
        assertTrue(e.state.value.usePc)
        assertEquals(io.github.odnwks.mimir.nav.Screen.Models, nav.current)
    }

    /** The owner's way (H2 on screen): the same chat screen; the mark under each answer says where it ran. */
    @Test fun theTopBarSaysWhoAnswersAndNoMarkSitsUnderTheAnswer() {
        val nav = Navigator()
        val station = FakeStation().also { it.set { s -> s.copy(pcTalks = true, pc = s.pc.copy(name = "Your PC")) } }
        compose.setContent {
            CompositionLocalProvider(LocalPhoneEngine provides engine) { MimirTheme { AppRoot(station, nav) } }
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Message $AI_NAME").performTextInput("What's the capital of Norway?")
        compose.onNodeWithContentDescription("Send").performClick()
        settle(); compose.waitForIdle()
        compose.onNodeWithText("Oslo is the capital.").assertIsDisplayed()
        assertEquals("pc", engine.state.value.chat.turns.last().ran)
        compose.onNodeWithContentDescription("Answered on: Your PC").assertDoesNotExist()
        pc.reachable = false
        compose.onNodeWithContentDescription("Message $AI_NAME").performTextInput("And of Sweden?")
        compose.onNodeWithContentDescription("Send").performClick()
        settle(); compose.waitForIdle()
        assertEquals("phone", engine.state.value.chat.turns.last().ran)
        compose.onNodeWithContentDescription("Answered on: This phone").assertDoesNotExist()
        // Who answers (top bar) offers the PC or this phone.
        compose.openWhoAnswers()
        compose.onNodeWithText(WHO_PHONE_ABOUT).performClick(); compose.waitForIdle()
        assertFalse(engine.state.value.usePc)
    }

    // ---- QA2 #7, #15, #19 (F4) ----

    /** N1: a PC that says why in its own plain words ("Your PC's ...") has that shown, with Try again; other texts stay hidden. */
    @Test fun aPcThatSaysWhyItCouldNotAnswerShowsItsOwnPlainWords() {
        val e = testEngine(tmp.newFolder(), ready = false, supported = false).also { it.pc = pc }
        val why = "Your PC's model isn't running, and it didn't start by itself. Open Settings \u203a Models to set it up, then try again."
        pc.answer = { _, l -> l.done(null, why) }
        e.ask("Hello?")
        waitUntil("the failed answer ends") { !e.state.value.writing && e.state.value.problem != null }
        assertEquals(why, e.state.value.problem)
    }

    /** A PC that is up but could not answer: one calm line (never "not reachable"), no "Claude Code", and the next send works. */
    @Test fun aFailedPcAnswerLeavesOneCalmLineAndSendStillWorks() {
        val e = testEngine(tmp.newFolder(), ready = false, supported = false).also { it.pc = pc }
        pc.answer = { _, l -> l.done(null, "Claude\u0020Code isn't on this PC.") }
        e.ask("Hello?")
        waitUntil("the failed answer ends") { !e.state.value.writing && e.state.value.problem != null }
        assertEquals(Engine.PC_COULD_NOT_ANSWER, e.state.value.problem)
        assertEquals(null, e.state.value.note)
        assertFalse(e.state.value.problem!!.contains("reachable"))
        // The PC answers pings: the next message goes through.
        pc.answer = FakePc().answer.let { _ -> { r, l -> l.done(io.github.odnwks.mimir.link.LChatTurn("${r.turnId}.a", false, "Fine.", ran = "pc", at = r.turns.last().at + 1), null) } }
        assertTrue(e.ask("Again?"))
        waitUntil("the answer comes") { e.state.value.chat.turns.any { !it.user && it.text == "Fine." } }
    }

    /** F7 #5: sending the same words again after a failed answer reuses the message (same id), never a second copy. */
    @Test fun sendingTheSameWordsAgainAfterAFailedAnswerKeepsOneMessage() {
        val e = testEngine(tmp.newFolder(), ready = false, supported = false).also { it.pc = pc }
        pc.answer = { _, l -> l.done(null, "Nope.") }
        e.ask("Garden question one")
        waitUntil("the failed answer ends") { !e.state.value.writing && e.state.value.problem != null }
        val first = e.state.value.chat.turns.single { it.user }
        pc.answer = { r, l -> l.done(io.github.odnwks.mimir.link.LChatTurn("${r.turnId}.a", false, "Fine.", ran = "pc", at = r.turns.last().at + 1), null) }
        assertTrue(e.ask("Garden question one"))
        waitUntil("the answer comes") { e.state.value.chat.turns.any { !it.user && it.text == "Fine." } }
        val mine = e.state.value.chat.turns.filter { it.user }
        assertEquals(1, mine.size)
        assertEquals(first.id, mine.single().id)
    }

    @Test fun theReasonThePcGivesNeverSaysClaudeCode() {
        assertEquals("Claude isn't on this PC.", Engine.plainPcWords("Claude Code isn't on this PC."))
        assertEquals(null, Engine.plainPcWords("  "))
    }

    // ---- Tester round 1 on the S8 ----

    /** A phone that can't run a model: the message is shown and kept, said plainly, and the PC answers it once back. */
    @Test fun aPhoneThatCantRunAModelKeepsTheMessageAndThePcAnswersItWhenBack() {
        val e = testEngine(tmp.newFolder(), ready = false, supported = false).also { it.pc = pc }
        e.setUsePc(false)
        pc.reachable = false
        e.ask("What's the capital of Norway?")
        assertEquals(listOf("What's the capital of Norway?"), e.state.value.chat.queued.map { it.text })
        pc.reachable = true
        e.linkUp()
        val until = System.currentTimeMillis() + 5_000
        while ((e.state.value.writing || e.state.value.chat.turns.size < 2) && System.currentTimeMillis() < until) Thread.sleep(10)
        assertEquals("pc", e.state.value.chat.turns.last().ran)
    }

    @Test fun onScreenAPhoneThatCantRunAModelSaysSoAndNeverOffersThisPhoneOnly() {
        val e = testEngine(tmp.newFolder(), ready = false, supported = false).also { it.pc = pc }
        pc.reachable = false
        val nav = Navigator()
        val station = FakeStation().also { it.set { s -> s.copy(pcTalks = true, pc = s.pc.copy(name = "Your PC", online = false)) } }
        compose.setContent { CompositionLocalProvider(LocalPhoneEngine provides e) { MimirTheme { AppRoot(station, nav) } } }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Message $AI_NAME").performTextInput("Hello?")
        compose.onNodeWithContentDescription("Send").performClick(); compose.waitForIdle()
        // The message waits in the chat, marked, with a way to take it back; nothing says "can't".
        compose.onNodeWithText("Hello?").assertIsDisplayed()
        compose.onNodeWithText("Sends when your PC is back").assertIsDisplayed()
        compose.onNodeWithContentDescription("Cancel this message: it won't be sent").assertIsDisplayed()
        // The box stays usable: a second message waits too.
        compose.onNodeWithContentDescription("Message $AI_NAME").performTextInput("And this?")
        compose.onNodeWithContentDescription("Send").performClick(); compose.waitForIdle()
        assertEquals(listOf("Hello?", "And this?"), e.state.value.chat.queued.map { it.text })
        compose.openWhoAnswers()
        compose.onNodeWithText(WHO_PHONE_CANT).performClick(); compose.waitForIdle()
        assertTrue("a phone that can't run a model can't choose This phone", e.state.value.usePc)
    }

    @Test fun thisPhoneOnlyWithNoModelYetShowsTheMessageAndOneTapToThePc() {
        val e = testEngine(tmp.newFolder(), ready = false).also { it.pc = pc }
        e.setUsePc(false)
        val nav = Navigator()
        val station = FakeStation().also { it.set { s -> s.copy(pcTalks = true, pc = s.pc.copy(name = "Your PC")) } }
        compose.setContent { CompositionLocalProvider(LocalPhoneEngine provides e) { MimirTheme { AppRoot(station, nav) } } }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Message $AI_NAME").performTextInput("Hello?")
        compose.onNodeWithContentDescription("Send").performClick(); compose.waitForIdle()
        assertEquals(listOf("Hello?"), e.state.value.chat.turns.map { it.text })
        compose.onNodeWithText("Use Your PC").performScrollTo().performClick()
        val until = System.currentTimeMillis() + 5_000
        while ((e.state.value.writing || e.state.value.chat.turns.size < 2) && System.currentTimeMillis() < until) Thread.sleep(10)
        assertEquals("pc", e.state.value.chat.turns.last().ran)
    }

    /** Wi-Fi back: the PC is asked at once how its answer stands (the words on the way were lost). */
    @Test fun aNetworkChangeAsksThePcForItsAnswerAtOnce() {
        pc.answer = { r, l ->
            l.filed(Filed.Talk("${r.turnId}.a", "pc", "Qwen 3.6 · Your PC"), null)
            l.piece("Once ", false)
            // ...the rest never arrives.
        }
        engine.send("Tell me a story")
        waitUntil("words arrive") { engine.state.value.partial.isNotEmpty() }
        val id = engine.state.value.chat.turns.last().id
        pc.state = LTalkState.Done(LChatTurn("$id.a", false, "Once upon a time.", ran = "pc", at = 1, seq = 4))
        val t0 = System.currentTimeMillis()
        pc.listener!!.nudge()
        settle()
        assertTrue("answered within 5 s", System.currentTimeMillis() - t0 < 5_000)
        assertEquals("Once upon a time.", engine.state.value.chat.turns.last().text)
    }

    @Test fun silenceFromThePcAlsoAsksHowItsAnswerStands() {
        Engine.PC_SILENT_MS = 300
        pc.answer = { r, l -> l.filed(Filed.Talk("${r.turnId}.a", "pc", "Qwen 3.6 · Your PC"), null) }
        engine.send("Tell me a story")
        waitUntil("waiting shows") { engine.state.value.pcWaiting }
        val id = engine.state.value.chat.turns.last().id
        pc.state = LTalkState.Done(LChatTurn("$id.a", false, "The end.", ran = "pc", at = 1, seq = 4))
        settle()
        assertEquals("The end.", engine.state.value.chat.turns.last().text)
    }

    @Test fun thePrivacyWordsFollowWhereTheChatRuns() {
        assertTrue(privacyLine(pc = true).contains("your PC"))
        assertFalse(privacyLine(pc = true).contains("never leave"))
        val nav = Navigator()
        val station = FakeStation().also { it.set { s -> s.copy(pcTalks = true) } }
        compose.setContent { CompositionLocalProvider(LocalPhoneEngine provides engine) { MimirTheme { AppRoot(station, nav) } } }
        compose.waitForIdle()
        // The first hello says it in the same words: the PC, never "stays on this phone".
        compose.onNodeWithText(firstOpenLead(Answerer.Pc)).assertIsDisplayed()
        compose.onNodeWithText("Your chats stay on this phone", substring = true).assertDoesNotExist()
        compose.onNodeWithText(PRIVACY_LINE).assertDoesNotExist()
    }
}
