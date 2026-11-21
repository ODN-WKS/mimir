package io.github.odnwks.mimir.ui

import io.github.odnwks.mimir.standalone.Catalog
import io.github.odnwks.mimir.standalone.Engine
import io.github.odnwks.mimir.standalone.LocalChat
import io.github.odnwks.mimir.standalone.PieceSink
import io.github.odnwks.mimir.standalone.Runner
import io.github.odnwks.mimir.standalone.Saved
import io.github.odnwks.mimir.standalone.Store
import io.github.odnwks.mimir.standalone.Turn
import java.io.File

/** Chats kept as plain text (checks have no Keystore). */
object PlainCodec : Store.Codec {
    override fun seal(text: String) = text.toByteArray()
    override fun open(bytes: ByteArray) = String(bytes)
}

/** A stand-in model that answers with fixed words. */
class WordsRunner(private val words: List<String> = listOf("Rainbows ", "form ", "when ", "sunlight ", "bends.")) : Runner {
    override fun open(path: String) = File(path).exists()
    override fun reply(prompt: String, maxTokens: Int, sink: PieceSink): String {
        for (w in words) if (!sink.onPiece(w.toByteArray())) break
        return """{"gen_tokens":${words.size},"gen_ms":100}"""
    }
    override fun stop() {}
    override fun close() {}
}

/**
 * The AI on this phone for checks, in [dir]: with the default model downloaded or not, and
 * earlier [turns] in the current chat.
 */
fun testEngine(dir: File, ready: Boolean, turns: List<Turn> = emptyList(), supported: Boolean = true, runner: Runner = WordsRunner()): Engine {
    Catalog.reset()
    val now = 1_800_000_000_000L
    if (ready) File(dir, "models").apply { mkdirs() }.resolve(Catalog.default.file).apply { writeText("model"); setLastModified(now) }
    val store = Store(File(dir, "standalone.bin"), PlainCodec)
    if (turns.isNotEmpty()) store.save(Saved(Catalog.default.id, listOf(LocalChat("c1", Catalog.default.id, turns))))
    return Engine(dir, store, runner, supported = supported, now = { now })
}

/** Puts the default model in place, as a finished download would. */
fun modelArrives(dir: File) {
    File(dir, "models").apply { mkdirs() }.resolve(Catalog.default.file).writeText("model")
}

/** Read aloud without a real voice: what was asked to be read, and Stop. */
class FakeReadAloud : io.github.odnwks.mimir.audio.ReadAloud {
    val read = mutableListOf<String>()
    private val _reading = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    override val reading: kotlinx.coroutines.flow.StateFlow<String?> = _reading
    override fun toggle(id: String, text: String) { if (_reading.value == id) stop() else { read += text; _reading.value = id } }
    override fun stop() { _reading.value = null }
    override var speed: Float = 1f
    override var voiceName: String? = "English (United Kingdom)"
    /** Set by a check: the message whose voice is still getting ready. */
    val readying = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    override val gettingReady: kotlinx.coroutines.flow.StateFlow<String?> = readying
}
