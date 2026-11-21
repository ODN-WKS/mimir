package io.github.odnwks.mimir.data

import io.github.odnwks.mimir.link.LinkTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** PHD-09, PHD-10, PHD-11: what the phone holds for a moment does not stay. */
class ExpiryTest {
    @get:Rule val tmp = TemporaryFolder()
    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L

    private fun file(dir: File, name: String, age: Long) = File(dir, name).apply { writeText("x"); setLastModified(now - age) }

    @Test fun readAloudAudioGoesAfterSevenDays() {
        val d = tmp.newFolder()
        val old = file(d, "speak-1.ogg", 8 * 24 * hour)
        val fresh = file(d, "speak-2.ogg", 6 * 24 * hour)
        val update = file(d, "update-3.ogg", 30 * 24 * hour)
        assertEquals(1, Expiry.speakFiles(d, now))
        assertFalse(old.exists())
        assertTrue(fresh.exists())
        assertTrue("an update's own audio is not touched here", update.exists())
    }

    @Test fun aRecordingThatWaitedADayIsRemoved() {
        val d = tmp.newFolder()
        val old = file(d, "m-a.wav", 25 * hour)
        val fresh = file(d, "m-b.wav", 23 * hour)
        val orphan = file(d, "s-c.wav", 1 * hour)
        val gone = Expiry.recordings(d, listOf("m-a", "m-b", "m-missing"), now)
        assertEquals(setOf("m-a", "m-missing"), gone)
        assertFalse(old.exists())
        assertTrue(fresh.exists())
        assertFalse("a sound the list doesn't name is not kept", orphan.exists())
    }

    @Test fun noFolderMeansNothingExpires() {
        assertEquals(emptySet<String>(), Expiry.recordings(null, listOf("m-a"), now))
    }

    @Test fun aWhisperNeverWaitsOnThePhone() {
        fun task(whisper: Boolean) = LinkTask("t", "T", "p", "running", false, null, 0, emptyList(), incognito = whisper)
        assertFalse(Expiry.mayWait(task(true)))
        assertTrue(Expiry.mayWait(task(false)))
        assertFalse("a task the phone doesn't know could be a Whisper: when unsure, refuse", Expiry.mayWait(null))
    }
}
