package io.github.odnwks.mimir.data

import io.github.odnwks.mimir.link.Filed
import io.github.odnwks.mimir.link.ItemAnswer
import io.github.odnwks.mimir.link.LChecks
import io.github.odnwks.mimir.link.LItem
import io.github.odnwks.mimir.link.LProject
import io.github.odnwks.mimir.link.LStatus
import io.github.odnwks.mimir.link.LinkTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the PC sends, as the screens show it. */
class MappingTest {
    private val name = { p: String -> p.substringAfterLast('/') }

    private fun item(kind: String, created: Long = 1000) =
        LItem("id-$kind", kind, "/p/Recipe-Box", "t1", "Title", "Text", emptyList(), listOf("A", "B"), true, created, null)

    @Test
    fun needs_you_items_keep_their_kind_choices_and_age() {
        val got = Mapping.needs(listOf(item("Permission", 1000), item("Release", 400), item("FromTheFuture")), name, 1000 + 7 * 60)
        assertEquals(listOf(NeedsKind.Permission, NeedsKind.Release), got.map { it.kind })
        assertEquals("Recipe-Box", got[0].project)
        assertEquals("7 min", got[0].age)
        assertEquals("17 min", got[1].age)
        assertEquals(listOf("A", "B"), got[0].choices)
        assertEquals(true, got[0].serious)
    }

    @Test
    fun buttons_become_the_answers_the_pc_takes() {
        assertEquals(ItemAnswer.Plain("AllowFileThisTask"), Mapping.answer(Answer.AlwaysAllowFile))
        assertEquals(ItemAnswer.Plain("Works"), Mapping.answer(Answer.ItWorks))
        assertEquals(ItemAnswer.Plain("DontAllow"), Mapping.answer(Answer.DontAllow))
        assertEquals(null, Mapping.answer(Answer.Opened))
    }

    @Test
    fun a_filed_message_says_where_it_went() {
        // The line under the message knows its task, so it can open it and show its state.
        assertEquals(Filing(FilingKind.NewTask, "Garden planner", taskId = "t"), Mapping.filing(Filed.NewTask("/p/Garden planner", "t"), name))
        assertEquals("t", Mapping.filing(Filed.SameTask("/p/Garden planner", "t"), name)!!.taskId)
        assertEquals("Filed in Garden planner, Ideas", Mapping.filing(Filed.Idea("/p/Garden planner", "i"), name)!!.text)
        assertEquals(
            Filing(FilingKind.Inbox, "", listOf("Garden planner", "Recipe-Box")),
            Mapping.filing(Filed.Inbox("x", listOf("/p/Garden planner", "/p/Recipe-Box")), name),
        )
        assertEquals(null, Mapping.filing(Filed.Status(listOf("Nothing needs you.")), name))
    }

    @Test
    fun the_status_page_becomes_the_gauges() {
        val page = LStatus(
            projects = listOf(
                LProject(
                    "/p/Budget-App", "Budget-App", "1.53",
                    LChecks(4, 5, listOf("CF-3" to "Calls on cell signal connect."),
                        listOf(Triple("CF-1", "Messages arrive in order.", true), Triple("CF-3", "Calls on cell signal connect.", false)),
                        failingSince = 1000 - 2 * 3600),
                    tasksRunning = 2, needsYou = 1,
                    line = "1 of 5 failing: calls on cell signal connect · needs you: 1 · 2 tasks running",
                    lastActivity = 900,
                ),
                LProject("/p/Garden planner", "Garden planner", null, null, 0, 0, "No checks set up", null),
            ),
            weeklyUsed = 0.624, weeklyResets = 1000 + 2 * 86_400, reply = emptyList(), dataTime = 1000,
        )
        val tasks = listOf(LinkTask("t1", "Calls on cell", "/p/Budget-App", "Working", true, null, 900, emptyList()))
        val got = Mapping.projects(page, tasks, mapOf("/p/Budget-App" to listOf("Big caller photo")), 1000)
        val hm = got[0]
        assertEquals(CheckState.Fail, hm.state)
        assertEquals("1 of 5 failing", hm.words)
        assertEquals("Failing: Calls on cell signal connect · needs you: 1 · 2 tasks running", hm.line)
        assertEquals(listOf(Check("Messages arrive in order", true), Check("Calls on cell signal connect", false)), hm.checks)
        assertEquals("Failing for 2 h", hm.failingSince)
        assertEquals(listOf(Chip.Running, Chip.Idea), hm.tasks.map { it.chip })
        assertEquals("Budget-App: 1 of 5 failing, Calls on cell signal connect. 2 running. 1 waiting for you", hm.spokenLine)
        assertEquals(CheckState.None, got[1].state)
        assertTrue("no \"No checks yet\" (Stage 1)", "No checks" !in got[1].words)
        val u = Mapping.usage(page, 1000)
        assertEquals("62%", u.percentText)
        assertEquals("resets in 2 d", u.resets)
        // QA F1: the PC's 0.93 is 93% on the phone too, as the PC's sidebar shows it.
        val near = Mapping.usage(page.copy(weeklyUsed = 0.93), 1000)
        assertEquals("93%", near.percentText)
        assertEquals(93, near.percent)
        assertEquals("100%", Mapping.usage(page.copy(weeklyUsed = 1.0), 1000).percentText)
        assertEquals("0%", Mapping.usage(page.copy(weeklyUsed = 0.004), 1000).percentText)
    }

    private fun card(id: String, audio: String?, project: String = "/p/Recipe-Box", task: String? = null) =
        io.github.odnwks.mimir.link.LCard(id, project, "Text of $id.", 1000, task, audio, null)

    @Test
    fun play_latest_plays_the_newest_card_first_then_older_ones() {
        val dir = java.nio.file.Files.createTempDirectory("updates").toFile()
        listOf("c.ogg", "b.ogg", "a.ogg").forEach { java.io.File(dir, it).writeBytes(byteArrayOf(1)) }
        // Newest first, as the PC sends them; "x" has no audio yet.
        val cards = listOf(card("c", "c.ogg"), card("x", null), card("b", "b.ogg"), card("a", "a.ogg"), card("gone", "gone.ogg"))
        val all = Mapping.playlist(cards, "c", { "Recipe box" }) { java.io.File(dir, it) }
        assertEquals(listOf("c", "b", "a"), all.map { it.id })
        val fromB = Mapping.playlist(cards, "b", { "Recipe box" }) { java.io.File(dir, it) }
        assertEquals(listOf("b", "a"), fromB.map { it.id })
    }

    @Test
    fun speed_goes_round_and_update_cards_open_their_task() {
        assertEquals(1.25f, Mapping.nextSpeed(1f))
        assertEquals(0.75f, Mapping.nextSpeed(1.5f))
        val u = Mapping.update(card("c", "c.ogg", task = "t1"), { "Recipe box" }, heard = false, nowSeconds = 1120, lengthSeconds = null)
        assertEquals(UpdateTarget.TaskOf("t1"), u.opens)
        assertEquals(Heard.New, u.heard)
        assertEquals("2 min", u.whenShort)
        val noProject = Mapping.update(card("d", null, project = ""), { it }, heard = true, nowSeconds = 1000, lengthSeconds = 9)
        assertEquals(UpdateTarget.NeedsYou, noProject.opens)
        assertEquals(9, noProject.lengthSeconds)
    }

    @Test
    fun free_models_show_with_their_note_and_the_fallback() {
        val pc = io.github.odnwks.mimir.link.LModels(
            listOf(
                io.github.odnwks.mimir.link.LFree("ollama:a", "a", "Fixed both in 30 s", false),
                io.github.odnwks.mimir.link.LFree("ollama:b-abliterated", "b-abliterated", "Fixed both in 3 min", true),
            ), null, switchToFree = true, why = "",
        )
        val m = Mapping.models(pc, "ollama:b-abliterated")
        assertEquals(listOf("ollama:a", "ollama:b-abliterated"), m.local.map { it.id })
        assertEquals(true, m.local[1].refusalsRemoved)
        assertEquals("a", m.freeFallback)
        assertEquals("b-abliterated", m.selectedName)
        assertEquals("qwen (free)", Mapping.modelName("ollama:qwen"))
        assertEquals("Opus 5.5", Mapping.modelName("claude-opus-5-5"))
    }

    @Test
    fun the_as_of_time_is_a_clock_time_yesterday_or_a_date() {
        val utc = java.util.TimeZone.getTimeZone("UTC")
        val now = 1_763_470_000_000L // 2025-11-18 12:46 UTC
        assertEquals("", seenWords(0, now, utc))
        assertEquals("11:42", seenWords(now - 64 * 60_000, now, utc))
        assertEquals("yesterday 23:10", seenWords(now - (13 * 60 + 36) * 60_000L, now, utc))
        assertEquals("Nov 16", seenWords(now - 2 * 86_400_000L, now, utc))
    }

    @Test
    fun the_connection_line_says_whose_side_is_away() {
        val s = ExampleData.awake()
        assertEquals("PC online · 3 working", s.connectionLine)
        assertEquals(null, s.whatStillWorks)
        val away = s.copy(pc = s.pc.copy(online = false, lastSeen = "11:42"))
        // One side, never both, short enough for one line (QA I1); how old it is goes on the strip.
        assertEquals("Your PC isn't reachable", away.connectionLine)
        assertEquals("This phone is offline", away.copy(phoneOnline = false).connectionLine)
        assertEquals("As of 11:42. Messages wait and send when your PC is back.", away.whatStillWorks)
        assertEquals("As of 11:42. Messages wait and send when this phone is back online.", away.copy(phoneOnline = false).whatStillWorks)
        assertEquals("sends when this phone is back online", away.copy(phoneOnline = false).waitsUntil)
        assertEquals("Reaching your PC…", away.copy(pc = away.pc.copy(lastSeen = "not connected")).connectionLine)
        assertEquals("Not paired yet", away.copy(paired = false).connectionLine)
    }

    @Test
    fun the_working_note_counts_tasks_in_words() {
        assertEquals("Working on 1 task", io.github.odnwks.mimir.alerts.Notifier.workingWords(1))
        assertEquals("Working on 2 tasks", io.github.odnwks.mimir.alerts.Notifier.workingWords(2))
    }

    @Test
    fun a_questions_list_items_are_buttons_only_when_claude_offers_them() {
        // QA B1: a plain two-item list whose last item is a rhetorical question.
        val plain = "- The sky is blue.\n- Isn't that something?"
        assertEquals(emptyList<String>(), Mapping.offered(plain, listOf("The sky is blue.", "Isn't that something?")))
        val led = "Here's what I found:\n\n- Cats sleep a lot.\n- Is that odd?"
        // A lead-in with ":" offers them; one that's just a statement doesn't.
        assertEquals(2, Mapping.offered(led, listOf("Cats sleep a lot.", "Is that odd?")).size)
        assertEquals(emptyList<String>(), Mapping.offered("Two facts.\n- A\n- B?", listOf("A", "B?")))
        val which = "Which should I do first?\n\n1. Fix the build\n2. Write the notes"
        assertEquals(listOf("Fix the build", "Write the notes"), Mapping.offered(which, listOf("Fix the build", "Write the notes")))
        assertEquals(2, Mapping.offered("- Fix it\n- Leave it\n\n**Which one?**", listOf("Fix it", "Leave it")).size)
        // Through the mapping: a Question item without an offer has no buttons; other kinds keep theirs.
        val item = { kind: String -> io.github.odnwks.mimir.link.LItem("a1", kind, "/p/X", "t1", "T", plain, emptyList(), listOf("The sky is blue.", "Isn't that something?"), true, 1000, null) }
        assertEquals(emptyList<String>(), Mapping.needs(listOf(item("Question")), { it }, 1000).single().choices)
        assertEquals(2, Mapping.needs(listOf(item("Choose")), { it }, 1000).single().choices.size)
    }

    @Test
    fun one_rule_says_what_is_working_and_each_task_has_one_state_word() {
        val t = { status: String, busy: Boolean -> io.github.odnwks.mimir.link.LinkTask("t", "T", "/p/X", status, busy, null, 0, emptyList()) }
        // QA F2: a session working elsewhere (runner not busy, the PC says Working) is working.
        assertTrue(Mapping.working(t("Working", false)))
        assertTrue(Mapping.working(t("Done", true)))
        assertFalse(Mapping.working(t("Done", false)))
        assertEquals("Working", Mapping.taskWords(t("Working", false), asks = false, connected = true))
        assertEquals("Needs you", Mapping.taskWords(t("Working", true), asks = true, connected = true))
        // QA F3: a finished answer is Done at once, however recently it was written to.
        assertEquals("Done", Mapping.taskWords(t("Done", false).copy(activeUnix = System.currentTimeMillis() / 1000), asks = false, connected = true))
        // Live only while it goes on elsewhere; QA F5: an archived task is never Live.
        assertEquals("Live", Mapping.taskWords(t("Done", false).copy(liveElsewhere = true), asks = false, connected = true))
        assertEquals("Archived", Mapping.taskWords(t("Done", false).copy(liveElsewhere = true, archived = true), asks = false, connected = true))
        assertEquals("Was working (PC away)", Mapping.taskWords(t("Working", false), asks = false, connected = false))
    }

    @Test
    fun the_pc_is_called_by_its_set_name_not_the_hostname() {
        assertEquals("Your PC", Mapping.pcName("localhost"))
        assertEquals("Your PC", Mapping.pcName(""))
        assertEquals("Your PC", Mapping.pcName("Study PC"))
        assertEquals("Your PC", Mapping.pcName("homepc"))
    }

    /** Stage 1, H6: the phone takes states, counts, projects and usage from the PC's one truth. */
    @Test
    fun the_truth_is_the_one_source_for_states_projects_and_usage() {
        assertEquals(listOf("Working", "Needs you", "Live", "Stopped", "Done"),
            listOf("Working", "NeedsYou", "LiveElsewhere", "Stopped", "Done").map(Mapping::truthWords))
        val truth = io.github.odnwks.mimir.link.LTruth(
            "r1", 7, 1794000500,
            io.github.odnwks.mimir.link.LTruthPc("Your PC", "Background", "1 task working"),
            emptyList(), 1, 0,
            listOf(io.github.odnwks.mimir.link.LTruthProject("/p/Recipe-Box", "Recipe-Box", 1794000100, 1, 0, null)),
            io.github.odnwks.mimir.link.LTruthEngine("Your PC", "", false, true),
            io.github.odnwks.mimir.link.LTruthUsage(62.0 to 1794400000L, null, 1794000400),
        )
        val old = io.github.odnwks.mimir.link.LStatus(
            listOf(io.github.odnwks.mimir.link.LProject("/p/Recipe-Box", "Recipe-Box", "0.21", null, 0, 0, "old line", null)),
            0.5, 1, emptyList(), 0,
        )
        val page = Mapping.pageFromTruth(truth, old)
        val p = page.projects.single()
        assertEquals(1, p.tasksRunning)
        assertEquals("0.21", p.version)
        assertEquals("the truth says percent, the page a share", 0.62, page.weeklyUsed!!, 0.0001)
        assertEquals("62%", Mapping.usage(page, 1794000500).percentText)
        assertEquals("1 task working", page.pcLine)
        // No paid Claude choice: no usage at all (the gauge hides).
        assertEquals(null, Mapping.pageFromTruth(truth.copy(usage = null), old).weeklyUsed)
    }
}
