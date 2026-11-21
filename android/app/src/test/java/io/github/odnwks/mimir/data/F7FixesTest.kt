package io.github.odnwks.mimir.data

import io.github.odnwks.mimir.link.Wire
import io.github.odnwks.mimir.link.Ev
import io.github.odnwks.mimir.ui.startersFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class F7FixesTest {
    @Test fun theModelsChangedEventIsKnownAndAskedFor() {
        assertTrue(Wire.event("{\"seq\":3,\"event\":\"ModelsChanged\"}").second is Ev.ModelsChanged)
        assertTrue(Wire.MODELS_CHANGED in Wire.FEATURES)
    }

    @Test fun onlineTakenAwayWhileChosenIsSaidToThePersonButNeverTheOwner() {
        assertTrue(onlineWithdrawn("paid", null, owner = false))
        assertTrue(onlineWithdrawn("paid", "free", owner = false))
        assertFalse(onlineWithdrawn("free", null, owner = false))
        assertFalse(onlineWithdrawn(null, null, owner = false))
        assertFalse(onlineWithdrawn("paid", "paid", owner = false))
        assertFalse(onlineWithdrawn("paid", null, owner = true))
        assertEquals("Your PC's owner turned off Online for you, so your PC's free model answers now.", ONLINE_WITHDRAWN)
    }

    @Test fun anotherPersonGetsNoWhatsRunningStarter() {
        val owner = startersFor(false, true, 0, null, null).map { it.title }
        val other = startersFor(false, false, 0, null, null).map { it.title }
        assertTrue("What's running?" in owner)
        assertFalse("What's running?" in other)
    }
}
