package io.github.odnwks.mimir.standalone

import io.github.odnwks.mimir.link.Ev
import io.github.odnwks.mimir.link.Wire
import io.github.odnwks.mimir.ui.testEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** R13: a phone paired as another person keeps nothing of the earlier person, and says nothing of it to the PC. */
@RunWith(RobolectricTestRunner::class)
class R13PeopleTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun welcome(person: String?) =
        """{"seq":1,"event":{"Welcome":{"proto":2,"pc_name":"PC","pc_version":"0.21","min_app":"","run_id":"r1","features":["talk","people"]${if (person != null) ",\"person\":\"$person\"" else ""}}}}"""

    @Test fun theWelcomeNamesWhoseThePhoneIs() {
        assertEquals("ab12", (Wire.event(welcome("ab12")).second as Ev.Welcome).person)
        assertEquals("", (Wire.event(welcome(null)).second as Ev.Welcome).person)
    }

    @Test fun aPhoneThatNeverRecordedAPersonWasTheOwnersAndChangesWhenPairedAsSomeoneElse() {
        assertTrue(io.github.odnwks.mimir.data.personChanged(null, "ab12"))
        assertTrue(!io.github.odnwks.mimir.data.personChanged(null, "owner"))
        assertTrue(io.github.odnwks.mimir.data.personChanged("owner", "ab12"))
        assertTrue(!io.github.odnwks.mimir.data.personChanged("ab12", "ab12"))
    }

    @Test fun anotherPersonsChatsGoFromThePhoneAndAreNeverSentAsDeletes() {
        val e = testEngine(tmp.newFolder(), ready = true, turns = listOf(io.github.odnwks.mimir.standalone.Turn(true, "Hi"), io.github.odnwks.mimir.standalone.Turn(false, "Hello")))
        e.newChat() // the chat above is now an earlier one
        assertEquals(1, e.state.value.earlier.size)
        e.newPerson()
        val end = System.currentTimeMillis() + 5_000
        while (e.state.value.earlier.isNotEmpty() && System.currentTimeMillis() < end) Thread.sleep(20)
        assertTrue("the earlier person's chats were kept", e.state.value.earlier.isEmpty())
        assertTrue(e.state.value.chat.turns.isEmpty())
        // Nothing waits to be sent as a delete: the PC keeps each person's chats apart.
        val deletes = Engine::class.java.getDeclaredField("pendingDeletes").apply { isAccessible = true }.get(e) as Collection<*>
        assertTrue("deletes were queued for the PC", deletes.isEmpty())
    }
}
