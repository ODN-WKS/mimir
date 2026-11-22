package io.github.odnwks.mimir.link

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** The phone reads and writes exactly the JSON the PC's Rust side does (crates/proto/tests/fixtures). */
@RunWith(RobolectricTestRunner::class)
class WireTest {
    private val dir = File("../../crates/proto/tests/fixtures")

    private fun lines(name: String) = File(dir, name).readLines().filter { it.isNotBlank() }

    /** JSON with keys in a fixed order, so equal shapes compare equal. */
    private fun canon(v: Any?): String = when (v) {
        is JSONObject -> v.keys().asSequence().sorted().joinToString(",", "{", "}") { "\"$it\":" + canon(v.get(it)) }
        is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { canon(v.get(it)) }
        is String -> JSONObject.quote(v)
        JSONObject.NULL, null -> "null"
        else -> v.toString()
    }

    private fun parse(s: String) = canon(JSONTokener(s).nextValue())

    @Test
    fun the_phone_reads_each_tasks_real_permission_mode() {
        val line = lines("events.jsonl").first { it.contains("\"Task\":{\"header\"") }
        for (m in listOf("Ask", "AcceptEdits", "Plan", "Auto", "NoAsk")) {
            val ev = Wire.event(line.replace("\"mode\":\"Ask\"", "\"mode\":\"$m\"")).second as Ev.TaskHeader
            assertEquals(m, ev.task.mode)
            assertEquals(m == "Plan", ev.task.plan)
        }
        // Auto-edit, Auto and Never ask get an amber chip; asking or planning first gets none.
        val chip = { m: String -> io.github.odnwks.mimir.data.Task("t", "t", "", "", "", emptyList(), emptyList(), null, null, null, mode = m).riskyMode }
        assertEquals(listOf(null, "Auto-edit", null, "Auto", "YOLO"), listOf("Ask", "AcceptEdits", "Plan", "Auto", "NoAsk").map(chip))
    }

    @Test
    fun the_phone_reads_whether_a_task_goes_on_elsewhere() {
        val line = lines("events.jsonl").first { it.contains("\"Task\":{\"header\"") }
        assertEquals(false, (Wire.event(line).second as Ev.TaskHeader).task.liveElsewhere)
        val on = line.replace("\"mode\":\"Ask\"", "\"mode\":\"Ask\",\"live_elsewhere\":true")
        assertEquals(true, (Wire.event(on).second as Ev.TaskHeader).task.liveElsewhere)
    }

    @Test
    fun every_event_the_pc_sends_a_phone_decodes() {
        val evs = lines("events.jsonl").map { Wire.event(it).second }
        assertTrue("no unknown events: $evs", evs.none { it is Ev.Other })
        val cards = evs.filterIsInstance<Ev.Updates>().single().cards
        assertEquals("u1794000100-ab12.ogg", cards[0].audio)
        assertEquals("1794-1-1", cards[0].task)
        assertEquals("Voice not downloaded on the PC", cards[1].audioNote)
        assertEquals(null, cards[1].task)
        val task = evs.filterIsInstance<Ev.TaskHeader>().single().task
        assertEquals("Make Allow respond faster", task.title)
        assertEquals(false, task.archived)
        assertEquals("0.17", evs.filterIsInstance<Ev.Welcome>().single().pcVersion)
        // An older PC sends no features: the phone then never sends it a search or a lookup.
        assertEquals(setOf("search", "lookup"), evs.filterIsInstance<Ev.Welcome>().single().features)
        assertEquals(emptySet<String>(), (Wire.event("{\"seq\":1,\"event\":{\"Welcome\":{\"proto\":2,\"pc_name\":\"Old PC\",\"pc_version\":\"0.20\",\"min_app\":\"\",\"run_id\":\"r\"}}}").second as Ev.Welcome).features)
        assertEquals("Change a file", task.asks.single().title)
        assertEquals("crates/core/src/remote.rs", task.asks.single().subject)
        val entries = evs.filterIsInstance<Ev.Entries>().single().entries
        assertEquals(LEntry.You("Make Allow faster"), entries[0])
        assertEquals(LEntry.Step("Ran a command: cargo build", true), entries[1])
        assertEquals(LEntry.Claude("Done: 0.2 s now."), entries[2])
        val replies = evs.filterIsInstance<Ev.Reply>()
        assertEquals("1794-1-2", replies[0].ok)
        assertEquals("A phone can't do that.", replies[1].err)
        assertTrue(evs.contains(Ev.Ready))

        val items = evs.filterIsInstance<Ev.NeedsYou>().single().items
        assertEquals(listOf("Permission", "Question", "CheckFix", "CheckFailing"), items.map { it.kind })
        assertEquals(listOf("-a", "+b"), items[0].preview)
        assertEquals("1794-1-1", items[0].task)
        assertEquals(listOf("Release as 0.16", "Keep for later"), items[1].choices)
        assertEquals(false, items[2].serious)
        assertEquals("https://example.com/notes", items[2].link)
        assertEquals(null, items[3].task)

        assertEquals(Filed.Idea("/home/me/Projects/Recipe-Box", "idea:0a0b0c0d0e0f"), Wire.filed(replies[2].ok))
        assertEquals(
            Filed.Inbox("inbox:0a0b0c0d0e0f", listOf("/home/me/Projects/Garden-Planner", "/home/me/p")),
            Wire.filed(replies[3].ok),
        )
        assertEquals(Filed.Status(listOf("1 thing needs you.", "Everything else is passing or idle.")), Wire.filed(replies[4].ok))
        val page = Wire.statusPage(replies[5].ok)!!
        val p = page.projects.single()
        assertEquals("Tune-Sorter", p.name)
        assertEquals(4, p.checks!!.passing)
        assertEquals(listOf("CF-3" to "Calls on cell signal connect."), p.checks!!.failing)
        assertEquals(Triple("CF-3", "Calls on cell signal connect.", false), p.checks!!.features[1])
        assertEquals(0.62, page.weeklyUsed!!, 0.001)
        // The PC's Labs line is only there while its switch is on.
        assertEquals(null, page.pcLine)
        val withLine = JSONObject(replies[5].ok.toString()).put("pc_line", "Your PC: Working on it in the background · 3 jobs")
        assertEquals("Your PC: Working on it in the background · 3 jobs", Wire.statusPage(withLine)!!.pcLine)
    }

    @Test
    fun every_call_the_phone_makes_is_what_the_pc_reads() {
        val expected = lines("calls.jsonl").map { parse(it) }
        val mine = listOf(
            Wire.hello("0.17", "Samsung EX-100", features = emptyList()),
            Wire.hello("0.22", "Samsung EX-100"),
            Wire.sync(12),
            Wire.projects(),
            Wire.newTask("/home/me/p", "Fix it", "m-1"),
            Wire.send("t", "And this", "m-2"),
            Wire.stop("t"),
            Wire.chat("m-3", "Recipes: idea - a dark mode switch"),
            Wire.answerItem("perm:t:0a1b", ItemAnswer.Plain("Allow")),
            Wire.answerItem("perm:t:0a1b", ItemAnswer.Plain("AllowFileThisTask")),
            Wire.answerItem("q:t:7", ItemAnswer.Option(1)),
            Wire.answerItem("q:t:7", ItemAnswer.Text("Release it")),
            Wire.answerItem("ask:1", ItemAnswer.Plain("Works")),
            Wire.answerItem("check:1", ItemAnswer.Plain("AskClaudeToFix")),
            Wire.status(),
            Wire.inbox(),
            Wire.ideas("/home/me/p"),
            Wire.fileInbox("inbox:1", "/home/me/p"),
            Wire.moveTask("t", "/home/me/p"),
            Wire.moveTo("t", "/home/me/p"),
            Wire.undoIdea("/home/me/p", "idea:1"),
            Wire.updates(),
            Wire.getFile("u1794000000-ab12.ogg"),
            Wire.speak("t", 3),
            Wire.listen("UklGRg=="),
            Wire.models(),
            Wire.setFreeChoice(true, null),
            Wire.continueOnClaude("t"),
            Wire.newIncognito("/home/me/p", "Read the tax letter", "ollama:qwen3.6:35b-a3b-coding"),
            Wire.endIncognito("t"),
            Wire.setWeeklyCheck(false),
            Wire.phoneSettings(),
            Wire.setPcName("Desk PC"),
            Wire.setRelays(false),
            Wire.alwaysAllowed("t"),
            Wire.stopAllowing("t", "/home/me/p/notes.txt"),
            Wire.webSearch("weather Portland", 5),
            Wire.lookup("https://api.open-meteo.com/v1/forecast?latitude=39.73&longitude=-121.84"),
            Wire.talk("c-1", "t-1", listOf(norway), effort = "low", rules = "Be brief."),
            Wire.talk(
                "c-1", "t-3", listOf(LChatTurn("t-3", true, "And when was it released?", at = 1794000000000)),
                lookup = "[Information the app fetched from open-meteo.com]", sources = listOf(LChatSource("open-meteo.com", "https://open-meteo.com/")), task = "1794-1-1",
            ),
            Wire.talk("c-1", "t-4", listOf(LChatTurn("t-4", true, "Is Recipes a good name for a recipe app?", at = 1794000000000)), chatOnly = true),
            Wire.talk("c-1", "t-5", listOf(LChatTurn("t-5", true, "What does this show?", at = 1794000000000, pictures = listOf(LPicture("image/jpeg", "/9j/4AAQ")))), chatOnly = true),
            Wire.talk("c-1", "t-6", listOf(LChatTurn("t-6", true, "What is this about?", at = 1794000000000, files = listOf(LFile("Lease.pdf", "application/pdf", "JVBERi0x")))), chatOnly = true),
            Wire.talkStop("c-1", "t-1"),
            Wire.talkTurn("c-1", "t-1"),
            Wire.chatPut("c-1", null, listOf(norway, oslo.copy(text = "Oslo.", ran = "phone", version = 2, seq = 0), LChatTurn("t-0", true, "", at = 1794000000000, version = 3, gone = true))),
            Wire.chatPut("c-2", null, emptyList(), deleted = true),
            Wire.setEngines("paid", null),
            Wire.setChatModel("sonnet"),
            Wire.chatsSince(40),
            Wire.setGroundRules("Be brief.", 1794000000000),
        ).map { parse(it) }
        assertEquals(expected, mine)
    }

    private val norway = LChatTurn("t-1", true, "What's the capital of Norway?", at = 1794000000000)
    private val oslo = LChatTurn(
        "t-1.a", false, "Oslo is the capital of Norway.",
        sources = listOf(LChatSource("open-meteo.com", "https://open-meteo.com/", "Oslo: 4 °C, light rain", via = "your PC")),
        ran = "pc", modelWords = "Qwen 3.6 · Your PC", at = 1794000000000, version = 1, seq = 41,
    )

    /** N3: the PC's note under an answer a different model wrote; an older PC sends none. */
    @Test
    fun the_switch_note_is_read_when_it_is_there_and_absent_when_not() {
        val withNote = Wire.chatTurn(org.json.JSONObject(oslo.json().put("switch_note", "Answered by Qwen 3.5 because your usual model can't look at pictures.").toString()))
        assertEquals("Answered by Qwen 3.5 because your usual model can't look at pictures.", withNote.switchNote)
        assertEquals(null, Wire.chatTurn(org.json.JSONObject(oslo.json().toString())).switchNote)
        assertEquals(false, oslo.json().has("switch_note"))
    }

    /** Stage 1 (H1): the one chat, its history and the truth, as the PC sends them. */
    @Test
    fun the_phone_reads_the_pcs_chat_answers_history_and_truth() {
        val evs = lines("events.jsonl").map { Wire.event(it).second }
        val pieces = evs.filterIsInstance<Ev.TalkPiece>()
        assertEquals(Ev.TalkPiece("c-1", "t-1.a", 0, "Oslo", false), pieces[0])
        assertTrue("thinking is marked, never the answer", pieces[1].thinking)
        val done = evs.filterIsInstance<Ev.TalkDone>()
        assertEquals(oslo, done[0].turn)
        assertEquals(null, done[0].err)
        assertEquals("Your PC's model stopped.", done[1].err)
        val changed = evs.filterIsInstance<Ev.ChatChanged>()
        assertEquals(listOf(norway.copy(seq = 40), oslo), changed[0].chat.turns)
        assertEquals(null, changed[0].chat.deleted)
        assertEquals(1794000100000L, changed[1].chat.deleted)
        val truth = evs.filterIsInstance<Ev.Truth>().single().truth
        assertEquals("r1", truth.runId)
        assertEquals(7L, truth.rev)
        assertEquals(LTruthPc("Your PC", "Background", "1 task working"), truth.pc)
        assertEquals(LTruthTask("1794-1-1", "/home/me/Projects/Garden-Planner", "Make Allow respond faster", "Working", false, 1794000100L, true), truth.tasks.single())
        assertEquals(1, truth.working)
        assertEquals(0, truth.needsYou)
        assertEquals(null, truth.projects.single().checks)
        assertEquals(true, truth.engine.paidOn)
        assertEquals(62.0, truth.usage!!.weekly!!.first, 0.001)
        assertEquals(null, truth.usage!!.fiveHour)
        // A restart of the PC's service (new run) is always newer; the same run needs a higher revision.
        assertTrue(truth.newerThan(null))
        assertTrue(truth.copy(runId = "r2", rev = 1).newerThan(truth))
        assertTrue(!truth.copy(rev = 6).newerThan(truth))

        val replies = evs.filterIsInstance<Ev.Reply>().associateBy { it.callId }
        assertEquals(Filed.Talk("t-1.a", "pc", "Qwen 3.6 · Your PC", "Waking your PC's model…", listOf(LFileNote("Lease.pdf", "Read pages 1 to 40 of 120"))), Wire.filed(replies[20]!!.ok))
        assertEquals(Filed.OnPhone("Your PC is busy; this phone answers instead."), Wire.filed(replies[21]!!.ok))
        assertEquals(LTalkState.Answering("Oslo"), Wire.talkState(replies[22]!!.ok))
        assertEquals(LTalkState.Done(oslo), Wire.talkState(replies[23]!!.ok))
        assertEquals(LTalkState.Failed("Stopped."), Wire.talkState(replies[24]!!.ok))
        assertEquals(LTalkState.Unknown, Wire.talkState(replies[25]!!.ok))
        val page = Wire.chatsPage(replies[26]!!.ok)!!
        assertEquals(42L, page.seq)
        assertEquals("Norway", page.chats.single().name)
        assertEquals("paid", page.chats.single().turns.single().ran)
        assertEquals(2, page.chats.single().turns.single().version)
        assertEquals(LChatPutDone(43, false), Wire.chatPutDone(replies[27]!!.ok))
        assertEquals(LGroundRules("Be brief.", 1794000000000), Wire.groundRules(replies[28]!!.ok))
    }

    @Test
    fun the_phones_ground_rules_go_with_a_task_a_chat_message_and_a_whisper_and_are_left_out_when_off() {
        val rules = "Be brief."
        for (call in listOf(Wire.newTask("/p", "Fix it", "m-1", rules = rules), Wire.chat("m-3", "Hi", rules), Wire.newIncognito("/p", "Hi", "ollama:x", rules))) {
            val body = org.json.JSONObject(call).let { it.getJSONObject(it.keys().next()) }
            org.junit.Assert.assertEquals(rules, body.getString("ground_rules"))
        }
        val off = org.json.JSONObject(Wire.chat("m-3", "Hi", null)).getJSONObject("Chat")
        org.junit.Assert.assertFalse(off.has("ground_rules"))
    }

    /** The feature words the phone looks for in the PC's Welcome are the ones the PC lists (crates/proto's FEATURES). */
    @Test
    fun the_feature_words_the_phone_checks_are_in_the_pcs_own_list() {
        val src = File("../../crates/proto/src/lib.rs").readText()
        val list = Regex("pub const FEATURES: &\\[&str\\] = &\\[(.*?)\\];", RegexOption.DOT_MATCHES_ALL).find(src)!!.groupValues[1]
        val pcFeatures = Regex("\"([a-z_]+)\"").findAll(list).map { it.groupValues[1] }.toSet()
        for (word in listOf(Wire.TALK, Wire.CHAT_SYNC, Wire.TRUTH, Wire.CHAT_ONLY, Wire.ENGINES)) assertTrue("$word is not in the PC's FEATURES: $pcFeatures", word in pcFeatures)
        // What the phone tells the PC it knows must be words the PC knows too.
        assertTrue(pcFeatures.containsAll(Wire.FEATURES))
        assertEquals("chat_only", Wire.CHAT_ONLY)
        assertEquals("engines", Wire.ENGINES)
    }

    @Test
    fun the_models_reply_carries_the_two_engine_lines() {
        val reply = JSONObject("""{"free":[],"free_model":null,"switch_to_free":false,"ollama":"","chats_engine":"free","project_engine":"claude"}""")
        val m = Wire.modelList(reply)!!
        assertEquals("free", m.chatsEngine)
        assertEquals("claude", m.projectEngine)
        // An older PC doesn't send them.
        val old = Wire.modelList(JSONObject("""{"free":[],"free_model":null,"switch_to_free":false,"ollama":""}"""))!!
        assertEquals(null, old.chatsEngine)
        assertEquals(null, old.projectEngine)
    }

    @Test fun theModelsReplyTellsWhetherThePcCanLookAtPicturesAndAnOlderPcSaysNo() {
        assertTrue(Wire.modelList(org.json.JSONObject("""{"free":[],"sees_pictures":true}"""))!!.seesPictures)
        assertFalse(Wire.modelList(org.json.JSONObject("""{"free":[],"sees_pictures":false}"""))!!.seesPictures)
        assertFalse(Wire.modelList(org.json.JSONObject("""{"free":[]}"""))!!.seesPictures)
    }

    @Test fun theModelsReplyCarriesTheClaudeChoicesAndAnOlderPcSaysNone() {
        val v = org.json.JSONObject("""{"free":[],"chats_engine":"paid","chats_model":"sonnet","chat_models":[{"id":"auto","name":"Auto-pick","about":"Claude Code chooses"},{"id":"haiku","name":"Haiku","about":"Quick"}]}""")
        val m = Wire.modelList(v)!!
        assertEquals("sonnet", m.chatsModel)
        assertEquals(listOf(LChoice("auto", "Auto-pick", "Claude Code chooses"), LChoice("haiku", "Haiku", "Quick")), m.chatModels)
        val old = Wire.modelList(org.json.JSONObject("""{"free":[],"chats_engine":"paid"}"""))!!
        assertEquals(null, old.chatsModel)
        assertTrue(old.chatModels.isEmpty())
    }
}
