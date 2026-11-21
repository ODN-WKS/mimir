package io.github.odnwks.mimir.ui

import io.github.odnwks.mimir.online.ChatMode
import io.github.odnwks.mimir.online.LookupLog
import io.github.odnwks.mimir.standalone.Catalog
import io.github.odnwks.mimir.standalone.Engine
import io.github.odnwks.mimir.standalone.PieceSink
import io.github.odnwks.mimir.standalone.Runner
import io.github.odnwks.mimir.standalone.Store
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * B6 / PHE-03, PHE-18: after [Engine.endWhisper], the words the Whisper looked up
 * are gone from the lookup log and from memory. No search word must survive.
 */
@RunWith(RobolectricTestRunner::class)
class WhisperWordClearTest {
    @get:Rule val tmp = TemporaryFolder()

    private object Plain : Store.Codec {
        override fun seal(text: String) = text.toByteArray()
        override fun open(bytes: ByteArray) = String(bytes)
    }

    private object Quiet : Runner {
        override fun open(path: String) = true
        override fun reply(prompt: String, maxTokens: Int, sink: PieceSink) = """{"gen_tokens":0,"gen_ms":1}"""
        override fun stop() {}
        override fun close() {}
    }

    @Test fun afterEndWhisperSearchWordsAreGone() {
        Catalog.reset()
        LookupLog.clear()
        val dir = tmp.newFolder()
        val e = Engine(dir, Store(File(dir, "standalone.bin"), Plain), Quiet, supported = true)

        // Add a normal lookup (must survive) and a Whisper lookup (must vanish).
        LookupLog.add(
            LookupLog.Entry(1L, "example.com", "normal search words", LookupLog.Route.Phone, 100L),
            ChatMode.Normal,
        )
        e.startWhisper()
        LookupLog.add(
            LookupLog.Entry(2L, "private.example", "secret search words", LookupLog.Route.Tor, 50L),
            ChatMode.WhisperWeb,
        )
        assertEquals("both entries present before end", 2, LookupLog.entries.value.size)

        e.endWhisper()

        // The Whisper's words must be gone.
        val remaining = LookupLog.entries.value
        assertEquals("only the normal entry survives", 1, remaining.size)
        assertTrue("normal search words survive", remaining.any { it.site == "example.com" })
        assertTrue(
            "Whisper search words are gone",
            remaining.none { it.words.contains("secret") || it.site == "private.example" },
        )

        LookupLog.clear()
    }

    @Test fun startingNewChatAlsoClearsWhisperWords() {
        Catalog.reset()
        LookupLog.clear()
        val dir = tmp.newFolder()
        val e = Engine(dir, Store(File(dir, "standalone.bin"), Plain), Quiet, supported = true)

        e.startWhisper()
        LookupLog.add(
            LookupLog.Entry(3L, "whisper-site.example", "private words", LookupLog.Route.Tor, 20L),
            ChatMode.WhisperWeb,
        )
        assertEquals(1, LookupLog.entries.value.size)

        // Starting a new chat also ends the Whisper and clears its lookups.
        e.newChat()
        assertTrue(
            "Whisper words cleared when new chat starts",
            LookupLog.entries.value.none { it.words.isNotEmpty() },
        )

        LookupLog.clear()
    }
}
