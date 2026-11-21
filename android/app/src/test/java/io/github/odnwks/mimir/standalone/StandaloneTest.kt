package io.github.odnwks.mimir.standalone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Standalone mode without the phone: prompts, the chat store, and the engine with a stand-in model. */
@RunWith(RobolectricTestRunner::class)
class StandaloneTest {
    @Test fun aChatsTopicIsAFewWordsFromItsFirstMessage() {
        assertEquals("Three easy dinner ideas", LocalChat.topicOf("Give me three easy dinner ideas"))
        assertEquals("Kind birthday message", LocalChat.topicOf("Help me write a kind birthday message"))
        assertEquals("How rainbows form, simply", LocalChat.topicOf("Explain how rainbows form, simply"))
        assertEquals(null, LocalChat("a", "m", emptyList()).topic)
        assertEquals("Mine", LocalChat("a", "m", listOf(Turn(true, "hello there")), name = "Mine").topic)
    }

    @get:Rule val tmp = TemporaryFolder()

    private object Plain : Store.Codec {
        override fun seal(text: String) = text.toByteArray()
        override fun open(bytes: ByteArray) = String(bytes)
    }

    /** Answers with fixed words, remembering the prompts it was given. */
    private class StandIn(private val words: List<String>, private val contextChars: Int = Int.MAX_VALUE) : Runner {
        val prompts = ArrayList<String>()
        val limits = ArrayList<Int>()
        var opened = 0
        override fun open(path: String): Boolean { opened++; return File(path).exists() }
        override fun reply(prompt: String, maxTokens: Int, sink: PieceSink): String {
            prompts += prompt
            limits += maxTokens
            if (prompt.length > contextChars) return """{"error":"context"}"""
            for (w in words) if (!sink.onPiece(w.toByteArray())) return """{"gen_tokens":1,"gen_ms":10,"stopped":true}"""
            return """{"gen_tokens":${words.size},"gen_ms":100}"""
        }
        override fun stop() {}
        override fun close() {}
    }

    private lateinit var dir: File
    private var clock = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    @org.junit.Before fun builtInList() = Catalog.reset()

    private fun engine(runner: Runner, ready: Boolean = true, folder: File? = null): Engine {
        dir = folder ?: tmp.newFolder()
        if (ready) File(dir, "models").apply { mkdirs() }.resolve(Catalog.default.file).apply { writeText("model"); setLastModified(clock) }
        return Engine(dir, Store(File(dir, "standalone.bin"), Plain), runner, supported = true, now = { clock })
    }

    private fun modelFile(id: String) = File(dir, "models/$id.gguf")

    private fun waitFor(e: Engine) {
        val until = System.currentTimeMillis() + 5_000
        while (e.state.value.writing && System.currentTimeMillis() < until) Thread.sleep(10)
        assertFalse("the reply never finished", e.state.value.writing)
    }

    @Test
    fun gemma_prompts_use_its_turns_and_end_where_the_reply_starts() {
        val p = Prompt.build(Format.Gemma4, listOf(Turn(true, "Hi"), Turn(false, "Hello!"), Turn(true, "Again")))
        assertTrue(p.startsWith("<|turn>system\n"))
        assertTrue(p.contains("<|turn>user\nHi<turn|>\n<|turn>model\nHello!<turn|>\n<|turn>user\nAgain<turn|>\n"))
        assertTrue(p.endsWith("<|turn>model\n"))
    }

    @Test
    fun the_ground_rules_are_the_phone_models_instructions_and_off_means_the_built_in_ones() {
        val p = Prompt.build(Format.ChatMl, listOf(Turn(true, "Hi")))
        assertTrue(p.startsWith("<|im_start|>system\n"))
        assertTrue(p.contains(io.github.odnwks.mimir.data.GroundRules.DEFAULT))
        val off = Prompt.build(Format.Gemma4, listOf(Turn(true, "Hi")), rules = null)
        assertTrue(off.contains(Prompt.SYSTEM))
        assertTrue(!off.contains(io.github.odnwks.mimir.data.GroundRules.DEFAULT))
        assertTrue(Prompt.build(Format.Gemma4, listOf(Turn(true, "Hi")), rules = "Be brief.").contains("Be brief.\n\n${Prompt.NO_GUESS}<turn|>"))
    }

    @Test
    fun qwen_prompts_use_chatml_and_skip_the_thinking_step_unless_asked() {
        val p = Prompt.build(Format.ChatMl, listOf(Turn(true, "Hi")))
        assertTrue(p.contains("<|im_start|>user\nHi<|im_end|>\n"))
        assertTrue(p.endsWith("<|im_start|>assistant\n<think>\n\n</think>\n\n"))
        assertTrue(Prompt.build(Format.ChatMl, listOf(Turn(true, "Hi")), think = true).endsWith("<|im_start|>assistant\n"))
    }

    @Test
    fun thinking_is_kept_apart_from_the_answer() {
        assertEquals("" to "Plain answer", Thought.split("Plain answer"))
        assertEquals("weighing it" to "", Thought.split("<think>weighing it"))
        assertEquals("weighing it" to "The answer.", Thought.split("<think>weighing it</think>\n\nThe answer."))
    }

    @Test
    fun with_think_first_on_only_the_answer_is_kept() {
        val runner = StandIn(listOf("<think>", "hmm, ", "let me see", "</think>", "\n\nForty-two."))
        val e = engine(runner)
        e.setThink(true)
        e.send("Hi")
        waitFor(e)
        assertEquals("Forty-two.", e.state.value.chat.turns.last().text)
        assertTrue(runner.prompts.last().endsWith("<|im_start|>assistant\n"))
    }

    @Test
    fun effort_sets_how_long_the_answer_may_be_and_whether_it_thinks_and_is_kept() {
        val runner = StandIn(listOf("Fine."))
        val folder = tmp.newFolder()
        val e = engine(runner, folder = folder)
        assertEquals("the usual effort", Effort.Balanced, e.state.value.effort)
        e.setEffort(Effort.Quick)
        e.send("Hi")
        waitFor(e)
        assertEquals(Effort.QUICK_TOKENS, runner.limits.last())
        assertTrue("Quick asks for a short answer", runner.prompts.last().contains(Prompt.BRIEF.trim()))
        assertFalse(e.state.value.think)
        e.setEffort(Effort.Thorough)
        assertTrue("Thorough is Think first", e.state.value.think)
        e.send("Again")
        waitFor(e)
        assertEquals(Engine.MAX_THINKING_TOKENS, runner.limits.last())
        assertTrue("the default model thinks first", runner.prompts.last().endsWith("<|im_start|>assistant\n"))
        assertFalse(runner.prompts.last().contains(Prompt.BRIEF.trim()))
        // Turning Think first off in Settings goes back to Balanced.
        e.setThink(false)
        assertEquals(Effort.Balanced, e.state.value.effort)
        e.setEffort(Effort.Quick)
        // The choice is kept for next time.
        val again = Engine(folder, Store(File(folder, "standalone.bin"), Plain), runner, supported = true, now = { clock })
        assertEquals(Effort.Quick, again.state.value.effort)
        // A model that can't think writes a longer answer on Thorough instead.
        val plain = Catalog.models.first { !it.thinking }
        assertEquals(Effort.THOROUGH_TOKENS, Effort.Thorough.maxTokens(plain))
        assertFalse(Effort.Thorough.thinks(plain))
    }

    @Test
    fun a_file_from_before_effort_keeps_think_first_as_thorough() {
        val folder = tmp.newFolder()
        Store(File(folder, "standalone.bin"), Plain).save(Saved(Catalog.default.id, emptyList(), think = true))
        val e = Engine(folder, Store(File(folder, "standalone.bin"), Plain), StandIn(emptyList()), supported = true, now = { clock })
        assertEquals(Effort.Thorough, e.state.value.effort)
    }

    @Test
    fun every_model_has_a_checksum_and_a_size_and_the_default_is_first() {
        assertEquals("qwen35-4b-sompoa-heresy2-q4km", Catalog.default.id)
        Catalog.models.forEach {
            assertEquals(64, it.sha256.length)
            assertTrue(it.bytes > 1_000_000_000)
            assertTrue(it.url.startsWith("https://huggingface.co/"))
        }
    }

    @Test
    fun a_reply_is_written_word_by_word_and_kept_with_the_chat() {
        val runner = StandIn(listOf("Hello", " there", " 👋"))
        val e = engine(runner)
        e.send("Hi")
        waitFor(e)
        val turns = e.state.value.chat.turns
        assertEquals(listOf(true to "Hi", false to "Hello there 👋"), turns.map { it.user to it.text })
        // Ids made here, the answer's from its message's; it ran on this phone.
        assertEquals("${turns[0].id}.a", turns[1].id)
        assertEquals("phone", turns[1].ran)
        assertEquals("30 tokens a second", e.state.value.speed)
        // The chat survives a restart (the store).
        val again = Engine(dir, Store(File(dir, "standalone.bin"), Plain), runner, true)
        assertEquals(turns, again.state.value.chat.turns)
    }

    /** Stage 1, 1.6: the phone's model is let go after 3 idle minutes, or at once when the PC answers. */
    @Test
    fun the_model_is_let_go_after_three_idle_minutes_and_loads_again_for_the_next_reply() {
        val runner = StandIn(listOf("ok"))
        val e = engine(runner)
        e.send("Hi"); waitFor(e)
        assertTrue(e.modelLoaded)
        clock += 2 * 60_000
        e.unloadIfIdle()
        assertTrue("two minutes is not idle yet", e.modelLoaded)
        clock += 61_000
        e.unloadIfIdle()
        assertFalse(e.modelLoaded)
        e.send("Again"); waitFor(e)
        assertTrue(e.modelLoaded)
        assertEquals(2, runner.opened)
        // The PC took over: let go at once.
        e.unloadIfIdle(force = true)
        assertFalse(e.modelLoaded)
    }

    @Test
    fun a_full_memory_drops_the_oldest_exchange_and_tries_again() {
        // Room for one exchange and a message, not two exchanges and a message.
        val one = "message number 0, a little longer than the others"
        val room = Prompt.build(Catalog.default.format, listOf(Turn(true, one), Turn(false, "ok"), Turn(true, one)), online = true).length + 10
        val runner = StandIn(listOf("ok"), contextChars = room)
        val e = engine(runner)
        repeat(3) { e.send("message number $it, a little longer than the others"); waitFor(e) }
        assertEquals("ok", e.state.value.chat.turns.last().text)
        assertTrue("it never retried", runner.prompts.size > 3)
        assertFalse(runner.prompts.last().contains("message number 0"))
    }

    @Test
    fun nothing_is_sent_until_a_model_is_downloaded() {
        val runner = StandIn(listOf("x"))
        val e = engine(runner, ready = false)
        assertFalse(e.state.value.ready)
        e.send("Hi")
        assertTrue(e.state.value.chat.turns.isEmpty())
        assertEquals(0, runner.opened)
    }

    @Test
    fun a_new_chat_keeps_the_last_one_under_earlier_chats() {
        val e = engine(StandIn(listOf("ok")))
        e.send("First")
        waitFor(e)
        e.newChat()
        assertTrue(e.state.value.chat.turns.isEmpty())
        assertEquals("First", e.state.value.earlier.single().title)
        e.open(e.state.value.earlier.single().id)
        assertEquals("First", e.state.value.chat.title)
    }

    @Test
    fun the_pcs_list_is_used_and_kept_for_when_there_is_no_pc() {
        val e = engine(StandIn(listOf("ok")))
        val one = Catalog.builtIn[1].toJson()
        e.useCatalog(org.json.JSONObject().put("models", org.json.JSONArray().put(one)).toString())
        assertEquals(listOf(Catalog.builtIn[1].id), Catalog.models.map { it.id })
        assertEquals(Catalog.builtIn[1].id, e.state.value.model)
        // The default's file isn't offered any more, but a downloaded model is never deleted because the list changed (PHE-17).
        assertTrue(modelFile(Catalog.builtIn[0].id).exists())
        // A restart with no PC keeps the PC's list.
        Catalog.reset()
        engine(StandIn(listOf("ok")), ready = false, folder = dir)
        assertEquals(listOf(Catalog.builtIn[1].id), Catalog.models.map { it.id })
    }

    @Test
    fun a_broken_list_from_the_pc_changes_nothing() {
        val e = engine(StandIn(listOf("ok")))
        e.useCatalog("""{"models": [{"id": "x", "url": "http://plain"}]}""")
        assertEquals(Catalog.builtIn, Catalog.models)
        assertTrue(modelFile(Catalog.default.id).exists())
    }

    @Test
    fun a_model_unused_for_fourteen_days_is_removed_with_a_note() {
        val e = engine(StandIn(listOf("ok")))
        e.send("Hi"); waitFor(e)
        clock += 15 * day
        val later = engine(StandIn(listOf("ok")), ready = false, folder = dir)
        assertFalse(modelFile(Catalog.default.id).exists())
        assertFalse(later.state.value.ready)
        assertTrue(later.state.value.note!!.startsWith("Removed ${Catalog.default.name}, unused"))
        // Used within the fourteen days, it stays.
        engine(StandIn(listOf("ok")))
        clock += 13 * day
        engine(StandIn(listOf("ok")), ready = false, folder = dir)
        assertTrue(modelFile(Catalog.default.id).exists())
    }

    @Test
    fun switching_models_removes_the_old_file_once_the_new_one_has_loaded() {
        val e = engine(StandIn(listOf("ok")))
        val other = Catalog.builtIn[1]
        e.send("Hi"); waitFor(e)
        modelFile(other.id).writeText("other")  // downloaded while the first is in use
        e.choose(other.id)
        assertTrue(modelFile(Catalog.default.id).exists())  // nothing goes before the new one loads
        e.send("Again"); waitFor(e)
        assertFalse(modelFile(Catalog.default.id).exists())
        assertTrue(modelFile(other.id).exists())
        assertTrue(e.state.value.note!!.contains("since you switched"))
    }

    @Test
    fun a_newer_file_in_the_list_offers_an_update_and_the_model_stays_usable() {
        val e = engine(StandIn(listOf("ok")))
        val newer = Catalog.default.copy(sha256 = "f".repeat(64)).toJson()
        val list = org.json.JSONArray().put(newer)
        Catalog.builtIn.drop(1).forEach { list.put(it.toJson()) }
        e.useCatalog(org.json.JSONObject().put("models", list).toString())
        assertEquals(ModelStatus.Outdated, e.state.value.status[Catalog.default.id])
        assertTrue(e.state.value.ready)
    }

    @Test
    fun a_list_that_drops_a_model_never_deletes_it_and_paused_downloads_keep_seven_days() {
        dir = tmp.newFolder()
        val models = File(dir, "models").apply { mkdirs() }
        // A model the PC's list no longer offers, used recently, stays (PHE-17).
        File(models, "gone-model.gguf").apply { writeText("x"); setLastModified(clock) }
        // Paused downloads of other models: a recent one stays, one older than seven days goes.
        File(models, "${Catalog.builtIn[2].id}.gguf.part").apply { writeText("x"); setLastModified(clock - 6 * day) }
        File(models, "${Catalog.builtIn[1].id}.gguf.part").apply { writeText("x"); setLastModified(clock - 8 * day) }
        File(models, "${Catalog.default.id}.gguf.part").apply { writeText("x"); setLastModified(clock) }
        engine(StandIn(listOf("ok")), ready = false, folder = dir)
        assertTrue(File(models, "gone-model.gguf").exists())
        assertTrue(File(models, "${Catalog.builtIn[2].id}.gguf.part").exists())
        assertFalse(File(models, "${Catalog.builtIn[1].id}.gguf.part").exists())
        assertTrue(File(models, "${Catalog.default.id}.gguf.part").exists())
    }

    @Test
    fun old_phones_are_told_rather_than_crashing() {
        val old = tmp.newFile("cpuinfo").apply { writeText("Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32\n") }
        val new = tmp.newFile("cpuinfo2").apply { writeText("Features\t: fp asimd asimddp i8mm bf16\n") }
        assertFalse(Llm.supported(old))
        assertTrue(Llm.supported(new))
    }

    @Test
    fun a_stopped_download_shows_as_paused_and_its_part_file_can_be_removed() {
        val e = engine(StandIn(listOf("x")), ready = false)
        val m = Catalog.default
        val part = File(dir, "models/${m.file}.part").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(1234)) }
        e.choose(m.id)
        val st = e.state.value.status[m.id]
        assertTrue("a part download is paused, not missing: $st", st is ModelStatus.Paused)
        assertEquals(1234L, (st as ModelStatus.Paused).done)
        e.removePartial(m.id)
        assertFalse("the part file is gone", part.exists())
        assertEquals(ModelStatus.Missing, e.state.value.status[m.id])
    }

    @Test
    fun a_message_left_without_an_answer_can_be_asked_again() {
        val runner = StandIn(listOf("Answer."))
        val folder = tmp.newFolder()
        val first = engine(runner, folder = folder)
        // The app was closed mid-reply: the chat ends with the owner's message.
        first.send("Question?")
        waitFor(first)
        val store = Store(File(folder, "standalone.bin"), Plain)
        val saved = store.load()
        store.save(saved.copy(chats = saved.chats.map { it.copy(turns = it.turns.take(1)) }))
        val again = engine(runner, folder = folder)
        assertTrue(again.state.value.chat.turns.last().user)
        again.retry()
        waitFor(again)
        assertEquals(listOf(true to "Question?", false to "Answer."), again.state.value.chat.turns.map { it.user to it.text })
    }

    @Test
    fun a_new_chat_mid_reply_says_why_it_waits() {
        val slow = object : Runner {
            override fun open(path: String) = true
            override fun reply(prompt: String, maxTokens: Int, sink: PieceSink): String { Thread.sleep(300); return """{"gen_tokens":1,"gen_ms":10}""" }
            override fun stop() {}
            override fun close() {}
        }
        val e = engine(slow)
        e.send("Hi")
        e.newChat()
        assertEquals("Stop the reply first, then start a new chat.", e.state.value.problem)
        waitFor(e)
    }

    @Test
    fun every_model_says_plainly_that_it_is_uncensored() {
        assertEquals(
            listOf(
                "Balanced · Uncensored (recommended)", "Stronger · Uncensored", "Smartest · Uncensored",
                "Quick · Uncensored, sees photos", "Most open · sees photos",
            ),
            Catalog.builtIn.map { it.plainName },
        )
        assertEquals(listOf("Uncensored", "Uncensored", "Uncensored", "Uncensored", "Uncensored"), Catalog.builtIn.map { it.chip })
        val big = Catalog.builtIn[1]
        assertTrue(big.details.startsWith("Huihui Qwen 3.5 9B abliterated · 5.6 GB"))
        // The full name is shown, not cut at a comma.
        assertEquals("Old name · uncensored · 1.0 GB", Catalog.builtIn[0].copy(name = "Old name, uncensored", bytes = 1_000_000_000).details)
        assertEquals(Catalog.builtIn.size, Catalog.builtIn.map { it.id }.toSet().size)
    }

    @Test
    fun a_model_can_ask_for_memory_and_old_lists_still_read() {
        assertEquals(listOf(0, 10, 10, 0, 0), Catalog.builtIn.map { it.minRamGb })
        val round = LocalModel.fromJson(Catalog.builtIn[1].toJson())!!
        assertEquals(10, round.minRamGb)
        val old = Catalog.builtIn[0].toJson()
        assertFalse(old.has("min_ram_gb"))
        assertEquals(0, LocalModel.fromJson(old)!!.minRamGb)
        assertEquals(0, LocalModel.fromJson(old.put("min_ram_gb", "lots"))!!.minRamGb)
        // A 12 GB phone (about 11.2 GB reported) runs the 9B ones; an 8 GB phone (about 7.4 GB) is told what it needs.
        val nine = Catalog.builtIn[1]
        assertEquals(Fit.Tight, Fit.of(nine.totalBytes, 11_200_000_000, nine.minRamGb))
        assertEquals(Fit.NeedsMemory, Fit.of(nine.totalBytes, 7_400_000_000, nine.minRamGb))
        assertEquals("Needs a phone with 12 GB of memory", Fit.NeedsMemory.words)
        assertEquals(Fit.Easily, Fit.of(Catalog.builtIn[0].totalBytes, 11_200_000_000, 0))
    }

    @Test
    fun gemma_thinks_first_inside_its_own_thinking_channel() {
        val p = Prompt.build(Format.Gemma4, listOf(Turn(true, "Hi")), think = true)
        assertTrue(p.endsWith("<|turn>model\n<|channel>thought\n"))
        assertTrue(Prompt.build(Format.Gemma4, listOf(Turn(true, "Hi"))).endsWith("<|turn>model\n"))
        assertEquals("plan" to "", Thought.split("plan", Format.Gemma4, true))
        assertEquals("plan" to "The answer.", Thought.split("plan<channel|>The answer.", Format.Gemma4, true))
        assertEquals("" to "Plain.", Thought.split("Plain.", Format.Gemma4, false))
        // A model that opens an empty thought on its own is read the same way.
        assertEquals("" to "Hi", Thought.split("<|channel>thought\n<channel|>Hi", Format.Gemma4, false))
        assertTrue(Catalog.builtIn.last().thinking)
    }

    @Test
    fun a_vision_part_stays_with_its_model_when_the_list_changes() {
        dir = tmp.newFolder()
        val models = File(dir, "models").apply { mkdirs() }
        val withVision = Catalog.builtIn.first { it.vision != null }
        File(models, withVision.file).apply { writeText("x"); setLastModified(clock) }
        File(models, withVision.visionFile).apply { writeText("v"); setLastModified(clock) }
        // A vision part with no model file of its own goes; one with its model stays even if the list drops it.
        File(models, "orphan-model.vision").apply { writeText("v"); setLastModified(clock) }
        Catalog.use(org.json.JSONObject().put("models", org.json.JSONArray().put(Catalog.builtIn.first { it.vision == null }.toJson())).toString())
        engine(StandIn(listOf("ok")), ready = false, folder = dir)
        assertTrue(File(models, withVision.visionFile).exists())
        assertFalse(File(models, "orphan-model.vision").exists())
    }
}
