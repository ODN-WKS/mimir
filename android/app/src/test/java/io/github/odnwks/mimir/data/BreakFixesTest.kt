package io.github.odnwks.mimir.data

import io.github.odnwks.mimir.link.Filed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** What the break test on the S8 and the tablet found: answers given offline, the chat. */
@RunWith(RobolectricTestRunner::class)
class BreakFixesTest {
    @Test
    fun an_answer_given_offline_waits_and_its_card_stops_asking() {
        data class Item(val ask: String)
        val items = listOf(Item("a1"), Item("a2"))
        var held = Held.put(emptyList(), "a1", "{\"AnswerItem\":{\"ask_id\":\"a1\",\"answer\":\"DontAllow\"}}")
        // Tapped again with another answer: the last one is the one sent.
        held = Held.put(held, "a1", "{\"AnswerItem\":{\"ask_id\":\"a1\",\"answer\":\"Allow\"}}")
        assertEquals(1, held.size)
        assertTrue(held.single().second.contains("\"Allow\""))
        assertEquals(listOf(Item("a2")), Held.asking(items, held) { it.ask })
        // The PC is back and still asks a1: the answer is still worth sending.
        assertEquals(held, Held.stillAsked(held, setOf("a1", "a2")))
        // Answered on the PC meanwhile: nothing left to send.
        assertTrue(Held.stillAsked(held, setOf("a2")).isEmpty())
    }

    @Test
    fun the_chat_is_kept_with_where_each_message_went() {
        val chat = listOf(
            ChatEntry.Mine("m1", "Fix the login page", Filing(FilingKind.NewTask, "Website")),
            ChatEntry.Mine("m2", "Which one?", Filing(FilingKind.Inbox, "", listOf("Website", "Notes"))),
            ChatEntry.Mine("m3", "Still waiting", Filing(FilingKind.Waiting, "")),
            ChatEntry.Reply("r1", listOf("All good.", "Nothing needs you."), "Play the status"),
            ChatEntry.Note("n1", "The idea was taken out of Notes."),
            ChatEntry.IncognitoRunning("incognito", "Website"),
        )
        val filed = mapOf<String, Filed>(
            "m1" to Filed.NewTask("/home/u/Website", "t9"),
            "m2" to Filed.Inbox("in-4", listOf("/home/u/Website", "/home/u/Notes")),
        )
        val (back, where) = ChatLog.read(ChatLog.write(chat, filed))
        // Everything but Incognito's note comes back as it was.
        assertEquals(chat.filterNot { it is ChatEntry.IncognitoRunning }, back)
        assertEquals(filed, where)
        // A damaged log is an empty chat, not a crash.
        assertTrue(ChatLog.read("not json").first.isEmpty())
    }

    /** QA B2: a "That didn't work" note is about its moment: it isn't kept, and an old kept one is dropped. */
    @Test
    fun problem_notes_are_not_kept_and_old_ones_are_dropped() {
        val chat = listOf(ChatEntry.Note("n1", "Answer sent."), ChatEntry.Note("n2", "That answer doesn't fit this item.", problem = true))
        assertEquals(listOf(ChatEntry.Note("n1", "Answer sent.")), ChatLog.read(ChatLog.write(chat, emptyMap())).first)
        val old = """[{"id":"n2","kind":"note","text":"That answer doesn't fit this item.","problem":true}]"""
        assertTrue(ChatLog.read(old).first.isEmpty())
        // A refused answer is said in plain words.
        assertEquals("Your answer wasn't used: that question has moved on (it may have been answered on the PC).",
            answerProblem("That answer doesn't fit this item."))
    }
}
