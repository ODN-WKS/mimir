package io.github.odnwks.mimir.ui

import io.github.odnwks.mimir.online.Net
import io.github.odnwks.mimir.standalone.ChatDrafts
import io.github.odnwks.mimir.standalone.Draft
import io.github.odnwks.mimir.standalone.Store
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Q2 review fixes: a lookup that failed is never "not found"; unknown sight is not "can't see"; drafts outlive screens. */
@RunWith(RobolectricTestRunner::class)
class Q2bReviewFixesTest {
    @get:Rule val tmp = TemporaryFolder()

    // 1. "No results" from one source while another failed is a network failure, not "not found".
    @Test fun noResultsPlusAFailureIsAFailureNotNothing() {
        val e = runCatching { Net.firstGood<String>(listOf({ null }, { throw java.io.IOException("down") })) }.exceptionOrNull()
        assertTrue(e.toString(), e is Net.Failed)
    }

    @Test fun noResultsFromEverySourceIsNothing() {
        assertNull(Net.firstGood<String>(listOf({ null }, { null })))
    }

    @Test fun noResultsPlusAGoodAnswerIsTheAnswer() {
        assertEquals("x", Net.firstGood(listOf({ null }, { "x" })))
    }

    // 2. Sight that is not known yet is neither "sees" nor "can't see".
    @Test fun unknownSightIsNotCannotSee() {
        assertEquals(Sight.Sees, sightFor(pcSight = true, pcReady = true, phoneSees = false, phoneVision = null))
        assertEquals(Sight.PcCannot, sightFor(pcSight = false, pcReady = true, phoneSees = false, phoneVision = null))
        assertEquals(Sight.PcUnknown, sightFor(pcSight = null, pcReady = true, phoneSees = false, phoneVision = null))
        assertEquals(Sight.Cannot, sightFor(pcSight = null, pcReady = false, phoneSees = false, phoneVision = null))
        assertEquals(Sight.Sees, sightFor(pcSight = null, pcReady = false, phoneSees = true, phoneVision = null))
        assertFalse("unknown is not worded as a no", PC_SIGHT_UNKNOWN.contains("None of"))
    }

    // 3. Drafts live outside the screen, survive a restart (words), and go with a deleted chat.
    @Test fun draftsKeepEveryChatAcrossScreens() {
        val d = ChatDrafts()
        d.put("a", Draft("words A", emptyList(), emptyList()))
        d.put("b", Draft("words B", emptyList(), emptyList()))
        assertEquals("words A", d.get("a").text)
        assertEquals("words B", d.get("b").text)
        d.put("a", Draft("", emptyList(), emptyList()))
        assertTrue(d.get("a").isEmpty)
    }

    @Test fun draftWordsSurviveARestart() {
        val store = Store(tmp.newFile("standalone.bin"), PlainCodec)
        val first = ChatDrafts(store.loadDrafts())
        first.put("a", Draft("keep me", emptyList(), emptyList()))
        store.saveDrafts(first.texts())
        val second = ChatDrafts(Store(java.io.File(tmp.root, "standalone.bin"), PlainCodec).loadDrafts())
        assertEquals("keep me", second.get("a").text)
    }

    @Test fun aDeletedChatsDraftGoesWithIt() {
        val dir = tmp.newFolder()
        val e = testEngine(dir, ready = true)
        e.drafts.put("gone-chat", Draft("lost words", emptyList(), emptyList()))
        e.drafts.put("other", Draft("kept words", emptyList(), emptyList()))
        e.forgetChatDraft("gone-chat")
        assertTrue(e.drafts.get("gone-chat").isEmpty)
        assertEquals("kept words", e.drafts.get("other").text)
    }
}
