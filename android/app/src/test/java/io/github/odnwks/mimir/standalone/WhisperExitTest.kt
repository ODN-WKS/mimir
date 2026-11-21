package io.github.odnwks.mimir.standalone

import io.github.odnwks.mimir.audio.ReadAloud
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Ending a Whisper in one tap, the emergency exit's wipe, leaving the app, and nothing written to disk. */
@RunWith(RobolectricTestRunner::class)
class WhisperExitTest {
    @get:Rule val tmp = TemporaryFolder()

    private object Plain : Store.Codec {
        override fun seal(text: String) = text.toByteArray()
        override fun open(bytes: ByteArray) = String(bytes)
    }

    /** Answers with fixed words; with [hold] it waits until stopped, like a long answer. */
    private class StandIn(private val words: List<String>, private val hold: CountDownLatch? = null, val started: CountDownLatch = CountDownLatch(1)) : Runner {
        @Volatile var stopped = false
        override fun open(path: String): Boolean = File(path).exists()
        override fun reply(prompt: String, maxTokens: Int, sink: PieceSink): String {
            started.countDown()
            for (w in words) if (!sink.onPiece(w.toByteArray())) return """{"gen_tokens":1,"gen_ms":10,"stopped":true}"""
            if (hold != null) { hold.await(5, TimeUnit.SECONDS); sink.onPiece(" late words".toByteArray()) }
            return """{"gen_tokens":${words.size},"gen_ms":100}"""
        }
        override fun stop() { stopped = true; hold?.countDown() }
        override fun close() {}
    }

    private class Reader : ReadAloud {
        var stops = 0
        override val reading: StateFlow<String?> = MutableStateFlow("x")
        override fun toggle(id: String, text: String) {}
        override fun stop() { stops++ }
        override var speed = 1f
        override val voiceName: String? = null
    }

    private class Clip(var text: String?) : ClipboardWipe {
        override fun text() = text
        override fun clear() { text = null }
    }

    private lateinit var dir: File

    private fun engine(runner: Runner): Engine {
        Catalog.reset()
        dir = tmp.newFolder()
        File(dir, "models").apply { mkdirs() }.resolve(Catalog.default.file).writeText("model")
        return Engine(dir, Store(File(dir, "standalone.bin"), Plain), runner, supported = true)
    }

    private fun waitDone(e: Engine) {
        val until = System.currentTimeMillis() + 5_000
        while (e.state.value.writing && System.currentTimeMillis() < until) Thread.sleep(10)
        assertFalse("the reply never finished", e.state.value.writing)
    }

    private fun whisperWithMessage(e: Engine, text: String = "my secret plan") {
        e.startWhisper()
        e.send(text)
        waitDone(e)
        assertTrue(e.state.value.whisper)
        assertTrue(e.state.value.chat.turns.isNotEmpty())
    }

    @Test fun oneTapEndsAndForgetsAtOnce() {
        val e = engine(StandIn(listOf("Okay.")))
        whisperWithMessage(e)
        e.endWhisper(told = true)
        val s = e.state.value
        assertFalse(s.whisper)
        assertTrue(s.chat.turns.isEmpty())
        assertTrue("the note shows on the fresh chat", s.whisperEnded)
        e.dismissWhisperEnded()
        assertFalse(e.state.value.whisperEnded)
    }

    @Test fun theIndirectWaysSayNothing() {
        val e = engine(StandIn(listOf("Okay.")))
        whisperWithMessage(e)
        e.endWhisper()
        assertFalse(e.state.value.whisperEnded)
    }

    @Test fun theEmergencyExitWipesStopsReadingAndClearsTheClipboardWhenItCameFromTheWhisper() {
        val e = engine(StandIn(listOf("The answer is blue.")))
        whisperWithMessage(e)
        val reader = Reader()
        val clip = Clip("The answer is blue.")
        assertTrue(WhisperExit.run(e, reader, clip))
        assertFalse(e.state.value.whisper)
        assertTrue(e.state.value.chat.turns.isEmpty())
        assertEquals(1, reader.stops)
        assertEquals(null, clip.text)
    }

    @Test fun theClipboardIsLeftAloneWhenItsTextIsFromElsewhere() {
        val e = engine(StandIn(listOf("Okay.")))
        whisperWithMessage(e)
        val clip = Clip("a shopping list")
        WhisperExit.run(e, Reader(), clip)
        assertEquals("a shopping list", clip.text)
    }

    @Test fun aPieceOfWhatWasTypedCountsAsFromTheWhisper() {
        val e = engine(StandIn(listOf("Okay.")))
        whisperWithMessage(e, "tell me about my secret plan please")
        val clip = Clip("secret plan")
        WhisperExit.run(e, null, clip)
        assertEquals(null, clip.text)
    }

    @Test fun theEmergencyExitDoesNothingOutsideAWhisper() {
        val e = engine(StandIn(listOf("Okay.")))
        e.send("hello")
        waitDone(e)
        val reader = Reader()
        assertFalse(WhisperExit.run(e, reader, Clip("Okay.")))
        assertEquals(0, reader.stops)
        assertEquals(2, e.state.value.chat.turns.size)
    }

    @Test fun wipingWhileAnAnswerIsBeingWrittenStopsItAndLeavesNothingInTheNextChat() {
        val hold = CountDownLatch(1)
        val runner = StandIn(listOf("Half "), hold)
        val e = engine(runner)
        e.startWhisper()
        e.send("a long question")
        assertTrue(runner.started.await(5, TimeUnit.SECONDS))
        assertTrue(WhisperExit.run(e, null, Clip(null)))
        waitDone(e)
        assertTrue(runner.stopped)
        val s = e.state.value
        assertTrue("the late words went nowhere: ${s.chat.turns}", s.chat.turns.isEmpty())
        assertEquals("", s.partial)
        assertFalse(s.whisper)
    }

    @Test fun leavingTheAppEndsAWhisperOnlyWhenChosenAndNotOnARotation() {
        val e = engine(StandIn(listOf("Okay.")))
        whisperWithMessage(e)
        e.appLeft(endOnLeave = false, rotating = false)
        assertTrue(e.state.value.whisper)
        e.appLeft(endOnLeave = true, rotating = true)
        assertTrue(e.state.value.whisper)
        e.appLeft(endOnLeave = true, rotating = false)
        assertFalse(e.state.value.whisper)
        assertTrue(e.state.value.chat.turns.isEmpty())
    }

    @Test fun aWhispersWordsAreNeverWrittenToDisk() {
        val e = engine(StandIn(listOf("Reply words zebra.")))
        e.send("an ordinary chat message")
        waitDone(e)
        whisperWithMessage(e, "whisper words giraffe")
        // Everything under the app's folder, read as text: the Whisper's words are nowhere.
        val all = dir.walkTopDown().filter { it.isFile && it.length() < 1_000_000 }.joinToString("\n") { runCatching { it.readText() }.getOrDefault("") }
        assertTrue("the ordinary chat is kept", all.contains("an ordinary chat message"))
        assertFalse(all.contains("giraffe"))
        // A new engine reading the same file finds only the ordinary chat.
        val again = Engine(dir, Store(File(dir, "standalone.bin"), Plain), StandIn(emptyList()), supported = true)
        val kept = again.state.value.earlier + again.state.value.chat
        assertFalse(kept.any { c -> c.turns.any { it.text.contains("giraffe") } })
        // Ended, still nothing.
        e.endWhisper(told = true)
        assertFalse(dir.walkTopDown().filter { it.isFile }.joinToString("\n") { runCatching { it.readText() }.getOrDefault("") }.contains("giraffe"))
    }
}
