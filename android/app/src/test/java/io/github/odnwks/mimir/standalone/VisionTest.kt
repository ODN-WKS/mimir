package io.github.odnwks.mimir.standalone

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Mímir seeing pictures: the vision part kept with its model, and pictures sent where they go. */
@RunWith(RobolectricTestRunner::class)
class VisionTest {
    @get:Rule val tmp = TemporaryFolder()

    private object Plain : Store.Codec {
        override fun seal(text: String) = text.toByteArray()
        override fun open(bytes: ByteArray) = String(bytes)
    }

    /** Remembers prompts and the pictures that came with them. */
    private class Seeing(private val canSee: Boolean = true) : Runner {
        val prompts = ArrayList<String>()
        val pictures = ArrayList<List<ByteArray>>()
        var seen = 0
        override fun open(path: String) = File(path).exists()
        override fun see(mmproj: String): Boolean { seen++; return canSee && File(mmproj).exists() }
        override fun reply(prompt: String, maxTokens: Int, sink: PieceSink): String = reply(prompt, maxTokens, sink, emptyList())
        override fun reply(prompt: String, maxTokens: Int, sink: PieceSink, pictures: List<ByteArray>): String {
            prompts += prompt
            this.pictures += pictures
            sink.onPiece("A red apple.".toByteArray())
            return """{"gen_tokens":3,"gen_ms":10}"""
        }
        override fun stop() {}
        override fun close() {}
    }

    private val e2b get() = Catalog.byId("gemma4-e2b-heretic-q4km")
    private lateinit var dir: File

    @Before fun builtIn() = Catalog.reset()

    private fun engine(runner: Runner, vision: Boolean): Engine {
        dir = tmp.newFolder()
        val models = File(dir, "models").apply { mkdirs() }
        File(models, e2b.file).writeText("model")
        if (vision) File(models, e2b.visionFile).writeText("vision")
        Store(File(dir, "standalone.bin"), Plain).save(Saved(e2b.id, emptyList(), files = mapOf(e2b.id to e2b.sha256)))
        return Engine(dir, Store(File(dir, "standalone.bin"), Plain), runner, supported = true)
    }

    private fun waitFor(e: Engine) {
        val until = System.currentTimeMillis() + 5_000
        while (e.state.value.writing && System.currentTimeMillis() < until) Thread.sleep(10)
        assertFalse(e.state.value.writing)
    }

    private val apple = TurnPicture.of("Apple", byteArrayOf(1, 2, 3))

    @Test fun theGemmaModelsComeWithTheirVisionPart() {
        assertNotNull(e2b.vision)
        assertNotNull(Catalog.byId("gemma4-e4b-ultra-heretic-q4km").vision)
        assertEquals(null, Catalog.default.vision)
        assertEquals(e2b.bytes + e2b.vision!!.bytes, e2b.totalBytes)
        // The PC's list carries it too, and a bad one is ignored.
        val back = LocalModel.fromJson(e2b.toJson())!!
        assertEquals(e2b.vision, back.vision)
        assertEquals(null, LocalModel.fromJson(e2b.toJson().put("vision", JSONObject().put("url", "http://x").put("bytes", 1).put("sha256", "00")))!!.vision)
    }

    @Test fun aPictureGoesToAModelThatSeesWhereItWasSent() {
        val runner = Seeing()
        val e = engine(runner, vision = true)
        assertTrue(e2b.id in e.state.value.sees)
        assertEquals(ModelStatus.Ready, e.state.value.status[e2b.id])
        e.send("What is this?", pictures = listOf(apple))
        waitFor(e)
        assertEquals(1, runner.seen)
        assertTrue(runner.prompts.last().contains("<|turn>user\n$PICTURE_MARKER\nWhat is this?<turn|>"))
        assertEquals(listOf(listOf(3)), runner.pictures.map { l -> l.map { it.size } })
        // The next message sends the chat's picture again (the runner keeps it), without loading again.
        e.send("And its colour?")
        waitFor(e)
        assertEquals(1, runner.seen)
        assertEquals(1, runner.pictures.last().size)
        assertEquals("A red apple.", e.state.value.chat.turns.last().text)
    }

    @Test fun withoutItsVisionPartTheModelGetsANoteAndUpdateAddsIt() {
        val runner = Seeing()
        val e = engine(runner, vision = false)
        assertFalse(e2b.id in e.state.value.sees)
        assertEquals("Update adds the vision part", ModelStatus.Outdated, e.state.value.status[e2b.id])
        e.send("What is this?", pictures = listOf(apple))
        waitFor(e)
        assertEquals(0, runner.seen)
        assertTrue(runner.prompts.last().contains("(A picture was shared here.)\nWhat is this?"))
        assertFalse(runner.prompts.last().contains(PICTURE_MARKER))
        assertTrue(runner.pictures.last().isEmpty())
    }

    @Test fun aMarkerTypedInAMessageOrFileIsTakenOut() {
        val t = Turn(true, "look $PICTURE_MARKER here", listOf(TurnFile("a.txt", "x$PICTURE_MARKER")))
        assertFalse(t.forModel(sees = true).contains(PICTURE_MARKER))
        assertEquals(2, Turn(true, "", pictures = listOf(apple, TurnPicture.of("b", byteArrayOf(9)))).forModel(sees = true).split(PICTURE_MARKER).size - 1)
    }

    @Test fun deletingTheModelOrLosingItsFileTakesTheVisionPartToo() {
        val e = engine(Seeing(), vision = true)
        e.delete(e2b.id)
        assertFalse(File(dir, "models/${e2b.visionFile}").exists())
        // A vision part left without its model goes when the phone tidies (the next start).
        val models = File(dir, "models")
        File(models, e2b.visionFile).writeText("vision")
        Engine(dir, Store(File(dir, "standalone.bin"), Plain), Seeing(), supported = true)
        assertFalse(File(models, e2b.visionFile).exists())
    }

    @Test fun picturesAreKeptWithTheChatAndAPictureAloneHasATopic() {
        val store = Store(File(tmp.newFolder(), "s.bin"), Plain)
        val turn = Turn(true, "", pictures = listOf(apple))
        store.save(Saved(e2b.id, listOf(LocalChat("c", e2b.id, listOf(turn)))))
        val back = store.load().chats.single().turns.single().pictures.single()
        assertEquals(apple, back)
        assertTrue(apple.jpeg.contentEquals(back.jpeg))
        assertEquals("Apple", LocalChat("c", e2b.id, listOf(turn)).topic)
    }
}
