package io.github.odnwks.mimir.data

import io.github.odnwks.mimir.link.Ev
import io.github.odnwks.mimir.link.LEntry
import io.github.odnwks.mimir.link.LMeta
import io.github.odnwks.mimir.link.LReceipt
import io.github.odnwks.mimir.link.Wire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** P11: the times, receipts, model and effort the PC sends with a task's entries reach the phone's task messages. */
@RunWith(RobolectricTestRunner::class)
class P11MetaTest {
    private val events = File("../../crates/proto/tests/fixtures/events.jsonl").readLines().filter { it.isNotBlank() }

    private fun entries(): Ev.Entries = events.map { Wire.event(it).second }.filterIsInstance<Ev.Entries>().single()

    @Test
    fun the_phone_reads_each_entrys_small_print_from_the_pc() {
        val e = entries()
        assertEquals(e.entries.size, e.meta.size)
        assertEquals(LMeta(atMs = 1_794_000_000_000, receipt = LReceipt.Read, model = "claude-opus-5-5"), e.meta[0])
        assertEquals(LMeta(), e.meta[1])
        val reply = e.meta[2]
        assertEquals("claude-sonnet-5-5", reply.model)
        assertEquals("high", reply.effort)
        assertEquals("you picked it", reply.switched)
        assertEquals("You picked this model or effort level for the task.", reply.switchedWhy)
        assertNull(reply.receipt)
        assertEquals(LMeta(atMs = 1_794_000_120_000), e.meta[3])
    }

    @Test
    fun an_older_pc_sends_no_small_print_and_nothing_breaks() {
        val old = "{\"seq\":6,\"event\":{\"Entries\":{\"task\":\"t\",\"from\":0,\"entries\":[{\"You\":\"Hi\"},{\"Claude\":\"Hello\"}]}}}"
        val e = Wire.event(old).second as Ev.Entries
        assertEquals(listOf(LEntry.You("Hi"), LEntry.Claude("Hello")), e.entries)
        assertEquals(emptyList<LMeta>(), e.meta)
        val you = taskMessageOf("t", 0, e.entries[0], false)!!
        assertNull("no time rather than a wrong one", you.atMs)
        assertNull(you.receipt)
    }

    @Test
    fun task_messages_carry_the_time_receipt_model_and_switch() {
        val e = entries()
        val you = taskMessageOf("t", 0, e.entries[0], false, e.meta[0])!!
        assertEquals(1_794_000_000_000, you.atMs)
        assertEquals(MessageReceipt.Read, you.receipt)
        assertEquals("claude-opus-5-5", you.model)
        assertNull("the PC's default effort", you.effort)
        val reply = taskMessageOf("t", 2, e.entries[2], false, e.meta[2])!!
        assertNull(reply.receipt)
        assertEquals("claude-sonnet-5-5", reply.model)
        assertEquals("high", reply.effort)
        assertEquals("you picked it", reply.switched)
        assertEquals("You picked this model or effort level for the task.", reply.switchedWhy)
        val note = taskMessageOf("t", 3, e.entries[3], false, e.meta[3])!!
        assertEquals(1_794_000_120_000, note.atMs)
        assertNull(note.receipt)
    }

    @Test
    fun small_print_follows_the_entries_as_they_change() {
        val a = LMeta(atMs = 1, receipt = LReceipt.Delivered)
        val b = LMeta(atMs = 2)
        // The first two entries, then the message moves on to Read and a reply comes.
        val first = mergeMeta(emptyList(), 0, listOf(a, b), 2)
        assertEquals(listOf(a, b), first)
        val read = a.copy(receipt = LReceipt.Read)
        val c = LMeta(atMs = 3, model = "claude-opus-5-5")
        assertEquals(listOf(read, b, c), mergeMeta(first, 0, listOf(read, b, c), 3))
        // An older PC's update keeps what came before and adds none.
        assertEquals(listOf(a), mergeMeta(first, 1, emptyList(), 3))
        // New entries after a gap get empty small print up to them.
        assertEquals(listOf(a, LMeta(), c), mergeMeta(listOf(a), 2, listOf(c), 3))
    }
}
