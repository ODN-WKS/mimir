package io.github.odnwks.mimir.standalone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/** Your own models: the GGUF header reader, the folder listing, the memory check, and using one from the folder. */
@RunWith(RobolectricTestRunner::class)
class OwnModelsTest {
    // ---- a tiny made-up GGUF header ----
    private class Gg {
        val out = ByteArrayOutputStream()
        fun u32(v: Int) = apply { for (i in 0..3) out.write((v ushr (8 * i)) and 0xFF) }
        fun u64(v: Long) = apply { for (i in 0..7) out.write(((v ushr (8 * i)) and 0xFF).toInt()) }
        fun str(s: String) = apply { val b = s.toByteArray(); u64(b.size.toLong()); out.write(b) }
        fun magic(version: Int, tensors: Long, keys: Long) = apply { out.write("GGUF".toByteArray()); u32(version); u64(tensors); u64(keys) }
        fun kvString(k: String, v: String) = apply { str(k); u32(8); str(v) }
        fun kvInt(k: String, v: Int) = apply { str(k); u32(4); u32(v) }
        fun kvStrings(k: String, vs: List<String>) = apply { str(k); u32(9); u32(8); u64(vs.size.toLong()); vs.forEach { str(it) } }
        fun kvFloats(k: String, n: Int) = apply { str(k); u32(9); u32(6); u64(n.toLong()); repeat(n) { u32(0x3f800000) } }
        fun bytes() = out.toByteArray()
    }

    private fun gguf(arch: String, template: String?, keys: Long = if (template == null) 4 else 5): ByteArray = Gg().magic(3, 291, keys)
        .kvString("general.architecture", arch)
        .kvInt("general.file_type", 15)
        .kvStrings("tokenizer.ggml.tokens", listOf("<s>", "hello", "wörld", "<|im_end|>"))
        .kvFloats("tokenizer.ggml.scores", 4)
        .apply { if (template != null) kvString("tokenizer.chat_template", template) }
        .bytes()

    private val QWEN = "{% for m in messages %}<|im_start|>{{ m.role }}\n{{ m.content }}<|im_end|>\n{% endfor %}{% if think %}<think>{% endif %}"
    private val GEMMA = "<|turn>user\n{{ content }}<turn|>\n<|turn>model\n<|channel>thought\n"

    @Test fun the_header_reader_finds_the_kind_and_the_chat_template_past_other_entries() {
        val h = Gguf.read(ByteArrayInputStream(gguf("qwen35", QWEN)))!!
        assertEquals("qwen35", h.architecture)
        assertEquals(QWEN, h.chatTemplate)
    }

    @Test fun the_header_reader_says_no_to_files_that_are_not_gguf_or_are_cut_short() {
        assertNull(Gguf.read(ByteArrayInputStream("not a model at all, just words".toByteArray())))
        assertNull(Gguf.read(ByteArrayInputStream(ByteArray(0))))
        val whole = gguf("llama", QWEN)
        assertNull(Gguf.read(ByteArrayInputStream(whole.copyOf(whole.size - 20))))
        // A strange version, and a count that no real file has.
        assertNull(Gguf.read(ByteArrayInputStream(Gg().magic(9, 1, 1).bytes())))
        assertNull(Gguf.read(ByteArrayInputStream(Gg().magic(3, 1, 5_000_000).bytes())))
    }

    @Test fun a_file_with_no_chat_template_has_none() {
        val h = Gguf.read(ByteArrayInputStream(gguf("bert", null)))!!
        assertEquals("bert", h.architecture)
        assertNull(h.chatTemplate)
    }

    @Test fun the_template_tells_the_kind_and_whether_it_can_think() {
        assertEquals(Format.ChatMl to true, OwnModels.kindOf(QWEN))
        assertEquals(Format.ChatMl to false, OwnModels.kindOf("<|im_start|>user\n{{x}}<|im_end|>"))
        assertEquals(Format.Gemma4 to true, OwnModels.kindOf(GEMMA))
        assertNull(OwnModels.kindOf("<start_of_turn>user\nhi<end_of_turn>"))
        assertNull(OwnModels.kindOf(null))
    }

    @Test fun only_the_file_name_can_mark_a_model_as_having_no_filter() {
        listOf("Qwen3.5-9B-abliterated.Q4_K_M.gguf", "x-UNCENSORED-v2.gguf", "gemma-4-heretic-Q4.gguf", "Qwen-heresy.gguf").forEach { assertTrue(it, OwnModels.uncensored(it)) }
        assertFalse(OwnModels.uncensored("Qwen3-4B-Instruct-Q4_K_M.gguf"))
    }

    @Test fun a_model_over_seventy_percent_of_the_phones_memory_is_too_big() {
        assertTrue(OwnModels.tooBig(5_700_000_000, 8_000_000_000))
        assertFalse(OwnModels.tooBig(5_500_000_000, 8_000_000_000))
        assertFalse(OwnModels.tooBig(50_000_000_000, 0))  // memory unknown: nothing is called too big
    }

    @Test fun a_photo_part_goes_with_the_model_whose_name_starts_the_same() {
        assertTrue(OwnModels.pairs("Qwen3-VL-4B-Instruct-Q4_K_M.gguf", "mmproj-Qwen3-VL-4B-Instruct-F16.gguf"))
        assertTrue(OwnModels.pairs("gemma-4-E2B-it-Q4_K_M.gguf", "mmproj-gemma-4-E2B-it-BF16.gguf"))
        assertTrue(OwnModels.pairs("gemma-4-E2B-it-Q4_K_M.gguf", "gemma-4-E2B-it-mmproj-F16.gguf".replace("-mmproj", "")))
        assertFalse(OwnModels.pairs("Qwen3-4B-Q4_K_M.gguf", "mmproj-gemma-4-E2B-it-F16.gguf"))
        assertFalse(OwnModels.pairs("mmproj-x.gguf", "mmproj.gguf"))
    }

    // ---- a fake folder ----
    private class Fake(val name0: String = "Mimir models") : OwnFolder {
        val docs = LinkedHashMap<String, Pair<OwnFile, ByteArray>>()
        var unreadable = false
        var opened = 0
        var copied = 0
        val closed = ArrayList<String>()
        /** The files the runner is told it can load. */
        var loadable = true
        lateinit var dir: File
        fun add(name: String, bytes: ByteArray, size: Long = bytes.size.toLong()) { docs["doc:$name"] = OwnFile("doc:$name", name, size) to bytes }
        override val name get() = name0
        override fun files() = if (unreadable) null else docs.values.map { it.first }
        override fun header(docId: String): InputStream? = docs[docId]?.let { ByteArrayInputStream(it.second) }
        override fun open(docId: String): OwnOpen? {
            val d = docs[docId] ?: return null
            opened++
            val f = File(dir, if (loadable) "ok-${d.first.name}" else "bad-${d.first.name}").apply { writeText("model") }
            return object : OwnOpen { override val path = f.path; override fun close() { closed += docId } }
        }
        override fun copy(docId: String, to: File, progress: (Long) -> Unit): Boolean {
            if (docs[docId] == null) return false
            copied++
            to.writeText("model")
            progress(1)
            return true
        }
    }

    @get:Rule val tmp = TemporaryFolder()

    @Test fun the_folder_listing_shows_each_model_its_kind_and_its_photo_part_and_leaves_out_the_rest() {
        val f = Fake().apply { dir = tmp.newFolder() }
        f.add("Qwen3-VL-4B-abliterated-Q4_K_M.gguf", gguf("qwen3vl", QWEN), 2_700_000_000)
        f.add("mmproj-Qwen3-VL-4B-abliterated-F16.gguf", gguf("clip", null), 800_000_000)
        f.add("gemma-4-E2B-it-Q4_K_M.gguf", gguf("gemma4", GEMMA), 3_400_000_000)
        f.add("llama-2-7b.Q4_0.gguf", gguf("llama", "<s>[INST] {{x}} [/INST]"), 3_800_000_000)
        f.add("renamed-download.gguf", "<html>not a model</html>".toByteArray())
        f.add("notes.txt".replace(".txt", ".gguf"), ByteArray(0))
        val list = OwnModels.scan(f)!!
        assertEquals(listOf("gemma-4-E2B-it-Q4_K_M.gguf", "llama-2-7b.Q4_0.gguf", "Qwen3-VL-4B-abliterated-Q4_K_M.gguf"), list.map { it.file })
        val (gemma, llama, qwen) = list
        assertEquals("Gemma-style", gemma.kind); assertTrue(gemma.thinking); assertFalse(gemma.uncensored)
        assertEquals("Can't run this kind yet", llama.kind); assertNull(llama.format)
        assertEquals("Qwen-style", qwen.kind); assertTrue(qwen.uncensored)
        assertEquals("mmproj-Qwen3-VL-4B-abliterated-F16.gguf", qwen.vision?.name)
        assertNull(gemma.vision)
        assertEquals("2.7 GB", qwen.sizeText)
        assertEquals(2_700_000_000 + 800_000_000, qwen.totalBytes)
        assertNull(OwnModels.scan(f.also { it.unreadable = true }))
    }

    // ---- using one ----
    private object Plain : Store.Codec {
        override fun seal(text: String) = text.toByteArray()
        override fun open(bytes: ByteArray) = String(bytes)
    }

    private class StandIn : Runner {
        val paths = ArrayList<String>()
        override fun open(path: String): Boolean { paths += path; return !File(path).name.startsWith("bad-") && File(path).exists() }
        override fun reply(prompt: String, maxTokens: Int, sink: PieceSink): String {
            sink.onPiece("Hello from my own model".toByteArray())
            return """{"gen_tokens":5,"gen_ms":100}"""
        }
        override fun stop() {}
        override fun close() {}
    }

    private lateinit var lastDir: File

    private fun engine(folder: Fake, runner: Runner = StandIn(), dir: File = tmp.newFolder()): Engine {
        Catalog.reset()
        lastDir = dir
        folder.dir = tmp.newFolder()
        return Engine(dir, Store(File(dir, "standalone.bin"), Plain), runner, supported = true, ownFolders = { if (it == "tree:good") folder else null })
    }

    private fun until(what: String, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!ok() && System.currentTimeMillis() < end) Thread.sleep(10)
        assertTrue(what, ok())
    }

    private fun Engine.answered() = until("the reply never finished") { !state.value.writing && state.value.chat.turns.size >= 2 }

    private fun folderWithQwen(): Fake = Fake().apply { add("Qwen3-4B-Q4_K_M.gguf", gguf("qwen3", QWEN), 2_500_000_000) }

    @Test fun a_chosen_folder_is_listed_and_a_file_from_it_answers_without_being_copied() {
        val f = folderWithQwen()
        val runner = StandIn()
        val e = engine(f, runner)
        e.setOwnFolder("tree:good")
        until("listing") { e.state.value.own.isNotEmpty() }
        assertEquals("Mimir models", e.state.value.ownFolder)
        e.useOwn("doc:Qwen3-4B-Q4_K_M.gguf")
        assertTrue(e.state.value.ready)
        assertEquals("own:doc:Qwen3-4B-Q4_K_M.gguf", e.state.value.model)
        e.send("Hi")
        e.answered()
        assertEquals("Hello from my own model", e.state.value.chat.turns.last().text)
        assertEquals(0, f.copied)
        assertTrue(runner.paths.single().endsWith("ok-Qwen3-4B-Q4_K_M.gguf"))
        // The PC's list never holds it.
        assertTrue(Catalog.models.none { it.own })
        assertTrue(f.closed.isEmpty())
        e.unloadNow()
        until("file let go with the model") { f.closed.isNotEmpty() }
    }

    @Test fun using_an_own_model_frees_the_downloaded_one_once_it_has_loaded() {
        val f = folderWithQwen()
        val e = engine(f)
        File(File(lastDir, "models").apply { mkdirs() }, Catalog.default.file).writeText("model")
        e.setOwnFolder("tree:good")
        until("listing") { e.state.value.own.isNotEmpty() }
        e.useOwn("doc:Qwen3-4B-Q4_K_M.gguf")
        val models = File(lastDir, "models")
        e.send("Hi")
        e.answered()
        assertFalse(File(models, Catalog.default.file).exists())
    }

    @Test fun the_choice_and_the_folder_are_remembered_after_a_restart() {
        val f = folderWithQwen()
        val dir = tmp.newFolder()
        val e = engine(f, dir = dir)
        e.setOwnFolder("tree:good")
        until("listing") { e.state.value.own.isNotEmpty() }
        e.useOwn("doc:Qwen3-4B-Q4_K_M.gguf")
        val again = Engine(dir, Store(File(dir, "standalone.bin"), Plain), StandIn(), supported = true, ownFolders = { if (it == "tree:good") f else null })
        assertEquals("own:doc:Qwen3-4B-Q4_K_M.gguf", again.state.value.model)
        assertTrue(again.state.value.ready)
        assertEquals("Mimir models", again.state.value.ownFolder)
        assertNotNull(Catalog.own)
    }

    @Test fun a_file_taken_out_of_the_folder_goes_back_to_no_model_and_says_why() {
        val f = folderWithQwen()
        val e = engine(f)
        e.setOwnFolder("tree:good")
        until("listing") { e.state.value.own.isNotEmpty() }
        e.useOwn("doc:Qwen3-4B-Q4_K_M.gguf")
        f.docs.clear()
        e.send("Hi")
        until("the reply attempt ended") { !e.state.value.writing }
        val s = e.state.value
        assertFalse(s.ready)
        assertEquals(Catalog.default.id, s.model)
        assertTrue(s.note, s.note!!.contains("no longer in its folder"))
        assertNull(Catalog.own)
        // Looking again later notices it too.
        val g = folderWithQwen()
        val e2 = engine(g)
        e2.setOwnFolder("tree:good")
        until("listing") { e2.state.value.own.isNotEmpty() }
        e2.useOwn("doc:Qwen3-4B-Q4_K_M.gguf")
        g.docs.clear()
        e2.lookAgain()
        until("noticed") { !e2.state.value.ready }
        assertTrue(e2.state.value.note!!.contains("no longer in its folder"))
    }

    @Test fun a_folder_that_cannot_be_read_any_more_is_said_so() {
        val f = folderWithQwen()
        val e = engine(f)
        e.setOwnFolder("tree:good")
        until("listing") { e.state.value.own.isNotEmpty() }
        e.useOwn("doc:Qwen3-4B-Q4_K_M.gguf")
        f.unreadable = true
        e.lookAgain()
        until("noticed") { e.state.value.ownLost && e.state.value.note != null }
        assertFalse(e.state.value.ready)
        assertTrue(e.state.value.note!!.contains("Choose the folder again"))
        // A folder address Android no longer lets the app read is refused.
        e.setOwnFolder("tree:bad")
        assertTrue(e.state.value.ownLost)
    }

    @Test fun if_the_runner_cannot_load_straight_from_the_folder_the_file_is_copied_once() {
        val f = folderWithQwen().apply { loadable = false }
        val runner = StandIn()
        val e = engine(f, runner)
        e.setOwnFolder("tree:good")
        until("listing") { e.state.value.own.isNotEmpty() }
        e.useOwn("doc:Qwen3-4B-Q4_K_M.gguf")
        e.send("Hi")
        e.answered()
        assertEquals(1, f.copied)
        assertEquals("Hello from my own model", e.state.value.chat.turns.last().text)
        assertNull(e.state.value.ownCopy)
        // Going back to a built-in model takes the copy away.
        e.choose(Catalog.default.id)
        until("copy removed") { !File(lastDir, "own-copy/model.gguf").exists() }
        assertNull(Catalog.own)
    }

    @Test fun a_model_of_a_kind_it_cannot_run_is_not_used() {
        val f = Fake().apply { add("llama-2-7b.gguf", gguf("llama", "<s>[INST] {{x}} [/INST]"), 3_800_000_000) }
        val e = engine(f)
        e.setOwnFolder("tree:good")
        until("listing") { e.state.value.own.isNotEmpty() }
        e.useOwn("doc:llama-2-7b.gguf")
        assertNull(Catalog.own)
        assertFalse(e.state.value.ready)
    }
}
