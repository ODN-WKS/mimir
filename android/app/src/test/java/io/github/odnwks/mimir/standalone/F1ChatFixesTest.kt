package io.github.odnwks.mimir.standalone

import io.github.odnwks.mimir.online.Source
import io.github.odnwks.mimir.ui.WhisperStart
import io.github.odnwks.mimir.ui.chatSentLine
import io.github.odnwks.mimir.ui.chatSentLines
import io.github.odnwks.mimir.ui.testEngine
import io.github.odnwks.mimir.ui.whisperStartFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** QA1 fixes for the phone chat (F1): titles, the honest lookup rule, per-chat rules and numbers, Whisper's place, Try again, the fresh start. */
@RunWith(RobolectricTestRunner::class)
class F1ChatFixesTest {
    @get:Rule val tmp = TemporaryFolder()

    // ---- 6. Titles keep their words and punctuation ----

    @Test fun aTitleKeepsTheFirstWordsWithTheirPunctuation() {
        assertEquals("TEST: what's the weather in Paris", LocalChat.topicOf("TEST: what's the weather in Paris"))
        assertEquals("How rainbows form, simply", LocalChat.topicOf("Explain how rainbows form, simply"))
    }

    @Test fun aLongTitleIsCutAtAWordWithAnEllipsis() {
        val t = LocalChat.topicOf("Plan a long weekend trip to the mountains with two kids and a dog in autumn")!!
        assertTrue(t, t.endsWith("…"))
        assertTrue(t, t.length <= LocalChat.TITLE_CHARS + 1)
        assertEquals("Plan a long weekend trip to the…", t)
        // Never cut in the middle of a word.
        assertTrue("Plan a long weekend trip to the mountains with two kids and a dog in autumn".startsWith(t.removeSuffix("…")))
    }

    @Test fun oneVeryLongWordIsCutToo() {
        val t = LocalChat.topicOf("x".repeat(80))!!
        assertEquals(LocalChat.TITLE_CHARS, t.length)
        assertTrue(t.endsWith("…"))
    }

    // ---- 7. No invented live numbers ----

    @Test fun everySystemTextTellsTheModelNotToGuessLiveNumbers() {
        val garbled = listOf(Turn(true, "wether tdoay in pariss??"))
        for (rules in listOf<String?>(null, "Be brief.")) for (online in listOf(false, true)) {
            val sys = Prompt.system(rules, online)
            assertTrue(sys, sys.contains(Prompt.NO_GUESS))
            val prompt = Prompt.build(Format.ChatMl, garbled, rules = rules, online = online)
            assertTrue(prompt.contains("If no looked-up facts are given, say you couldn't look it up; never guess live numbers like weather, prices or dates."))
        }
    }

    @Test fun thePcIsToldTheSameRule() {
        val sent = Prompt.rulesForPc(null, "")
        assertTrue(sent.contains(Prompt.NO_GUESS))
        assertTrue(Prompt.rulesForPc("Ground.", "Answer in French.").let { it.startsWith("Ground.") && it.contains("Answer in French.") && it.contains(Prompt.NO_GUESS) })
    }

    // ---- 5. Rules and numbers of one chat ----

    @Test fun aChatsOwnRulesAreToldFirstInThatChatOnly() {
        val with = Prompt.build(Format.ChatMl, listOf(Turn(true, "Hi")), chatRules = "Always answer in French.")
        val without = Prompt.build(Format.ChatMl, listOf(Turn(true, "Hi")))
        assertTrue(with.contains("Always answer in French."))
        assertFalse(without.contains("French"))
        assertTrue(with.indexOf("French") < with.indexOf("Hi"))
    }

    @Test fun aChatsRulesAreKeptWithTheChatAndEmptyByDefault() {
        val dir = tmp.newFolder()
        val e = testEngine(dir, ready = true, turns = listOf(Turn(true, "Hi"), Turn(false, "Hello")))
        e.newChat() // the chat above is now an earlier one
        val id = e.state.value.earlier.first().id
        assertEquals("", e.state.value.earlier.first().rules)
        e.setChatRules("  Be short.  ", id)
        assertEquals("Be short.", e.state.value.earlier.first { it.id == id }.rules)
        assertEquals("", e.state.value.chat.rules)
    }

    @Test fun theStoreKeepsRulesAndTheBytesSent() {
        val store = Store(java.io.File(tmp.newFolder(), "s.bin"), io.github.odnwks.mimir.ui.PlainCodec)
        store.save(Saved("m", listOf(LocalChat("c", "m", listOf(Turn(true, "a")), rules = "Be short.", sentToPc = 1234))))
        val back = store.load().chats.single()
        assertEquals("Be short.", back.rules)
        assertEquals(1234L, back.sentToPc)
    }

    @Test fun whatWasSentListsThisChatsLookupsWithTheirWords() {
        val chat = LocalChat("c", "m", listOf(
            Turn(true, "weather in Paris"),
            Turn(false, "Cloudy.", sources = listOf(Source("met.no", "https://met.no/", "Paris"))),
        ), sentToPc = 620)
        val lines = chatSentLines(chat, pcAnswers = true)
        assertEquals("Sent to your PC for this chat: under 1 KB. Only your PC gets it.", lines[0])
        assertEquals("1 lookup was made in this chat:", lines[1])
        assertEquals("• met.no: Paris", lines[2])
        assertEquals("nothing left this phone", chatSentLine(0, 0))
        assertEquals("under 1 KB to your PC, 1 lookup", chatSentLine(620, 1))
        assertEquals("No lookups were made in this chat.", chatSentLines(LocalChat("d", "m", emptyList()), false)[1])
    }

    // ---- 1. Where a Whisper starts ----

    @Test fun aWhisperStartsOnThePcWhenPairedAndOtherwiseOnThePhone() {
        // Paired, a free model there: the PC (even on a phone that can't run one).
        assertEquals(WhisperStart.Pc, whisperStartFor(paired = true, pcFree = true, pcOnline = true, phoneReady = false, phoneSupported = false))
        assertEquals(WhisperStart.Pc, whisperStartFor(true, true, true, phoneReady = true, phoneSupported = true))
        // The PC is away and the phone can answer: the phone.
        assertEquals(WhisperStart.Phone, whisperStartFor(true, true, pcOnline = false, phoneReady = true, phoneSupported = true))
        // Not paired: the phone, or the one-time download, or nowhere.
        assertEquals(WhisperStart.Phone, whisperStartFor(false, false, false, true, true))
        assertEquals(WhisperStart.NeedsModel, whisperStartFor(false, false, false, false, true))
        assertEquals(WhisperStart.Nowhere, whisperStartFor(false, false, false, false, false))
        assertNotEquals(WhisperStart.Pc, whisperStartFor(true, false, true, false, true))
    }

    // ---- 8. A fresh start opens on the home screen ----

    @Test fun aFreshStartOpensOnAnEmptyChatAndTheLastOneIsOneTapAway() {
        val e = testEngine(tmp.newFolder(), ready = true, turns = listOf(Turn(true, "Hi"), Turn(false, "Hello")))
        val lastId = e.state.value.chat.id
        assertEquals(2, e.state.value.chat.turns.size)
        e.startOnHome()
        assertTrue(e.state.value.chat.turns.isEmpty())
        assertEquals(lastId, e.state.value.earlier.first().id)
        // Already on a fresh chat: nothing more is made.
        val n = e.state.value.earlier.size
        e.startOnHome()
        assertEquals(n, e.state.value.earlier.size)
    }

    // ---- 3. Try again ----

    @Test fun tryAgainGivesTheNewAnswerTheSameIdAndAHigherVersion() {
        val e = testEngine(tmp.newFolder(), ready = true, turns = listOf(Turn(true, "Hi", id = "q1", at = 1), Turn(false, "Hello", id = "q1.a", at = 2)))
        assertTrue(e.tryAgain())
        val until = System.currentTimeMillis() + 5_000
        while ((e.state.value.writing || e.state.value.chat.turns.size < 2) && System.currentTimeMillis() < until) Thread.sleep(10)
        val turns = e.state.value.chat.turns
        assertEquals(listOf("q1", "q1.a"), turns.map { it.id })
        assertEquals(2, turns.last().version)
        assertEquals(0L, turns.last().seq) // not known to the PC yet: it goes there and replaces its copy
    }

    @Test fun tryAgainDoesNothingWithoutAnAnswerToReplace() {
        val e = testEngine(tmp.newFolder(), ready = true, turns = listOf(Turn(true, "Hi", id = "q1", at = 1)))
        assertFalse(e.tryAgain())
        assertEquals(1, e.state.value.chat.turns.size)
    }
}
