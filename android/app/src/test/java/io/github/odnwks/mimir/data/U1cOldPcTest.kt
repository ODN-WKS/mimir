package io.github.odnwks.mimir.data

import io.github.odnwks.mimir.link.Ev
import io.github.odnwks.mimir.link.Wire
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** An older PC doesn't say whether something runs: Stop must not vanish. */
@RunWith(RobolectricTestRunner::class)
class U1cOldPcTest {
    private fun header(extra: String): io.github.odnwks.mimir.link.LinkTask {
        val json = "{\"seq\":1,\"event\":{\"Task\":{\"header\":{\"id\":\"t\",\"title\":\"T\",\"project\":\"/p/X\",\"status\":\"Needs you\",\"busy\":false$extra,\"model\":null,\"created_unix\":0,\"asks\":[]}}}}"
        return (Wire.event(json).second as Ev.TaskHeader).task
    }

    @Test fun aPcThatDoesNotSayRunningIsTakenAsRunning() {
        assertTrue(header("").running)
    }

    @Test fun aPcThatSaysNotRunningIsBelieved() {
        assertFalse(header(",\"running\":false").running)
    }
}
