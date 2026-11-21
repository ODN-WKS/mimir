package io.github.odnwks.mimir.data

import io.github.odnwks.mimir.link.Ev
import io.github.odnwks.mimir.link.Wire
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The phone's half of "phone and PC agree" (the PC's half is crates/proto/tests/agreement.rs): the same
 * fixtures and agreement.json feed both, so status words, the weekly percent and project names can't drift.
 */
@RunWith(RobolectricTestRunner::class)
class AgreementTest {
    private val dir = File("../../crates/proto/tests/fixtures")
    private val agreement = JSONObject(File(dir, "agreement.json").readText())
    private val events = File(dir, "events.jsonl").readLines().filter { it.isNotBlank() }.map { Wire.event(it).second }

    @Test
    fun the_phone_says_the_words_the_pc_agreement_lists() {
        val task = events.filterIsInstance<Ev.TaskHeader>().single().task
        val rows = agreement.getJSONArray("statuses")
        val known = agreement.getJSONArray("known_differences").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
        val differ = mutableSetOf<String>()
        for (i in 0 until rows.length()) {
            val r = rows.getJSONObject(i)
            val t = task.copy(status = r.getString("status"), busy = false, liveElsewhere = false, archived = false)
            val phone = Mapping.taskWords(t, asks = false, connected = true)
            assertEquals("the phone's word for ${r.getString("status")}", r.getString("phone"), phone)
            if (phone != r.getString("pc")) differ += r.getString("status")
        }
        // New differences fail; so does a fixed one still on the list.
        assertEquals("status words the PC and the phone say differently (agreement.json known_differences)", known, differ)
    }

    @Test
    fun the_phone_shows_the_weekly_percent_and_project_names_the_pc_does() {
        val page = events.filterIsInstance<Ev.Reply>().mapNotNull { Wire.statusPage(it.ok) }.first { it.weeklyUsed != null }
        assertEquals("62%", Mapping.usage(page, 0).percentText)
        assertEquals(agreement.getInt("weekly_percent"), Mapping.usage(page, 0).percent)
        val names = agreement.getJSONArray("project_names").let { a -> (0 until a.length()).map { a.getString(it) } }
        assertEquals(names, page.projects.map { it.name })
    }
}
