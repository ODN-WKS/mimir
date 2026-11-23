package io.github.odnwks.mimir.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import io.github.odnwks.mimir.audio.PcReading
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.junit.Assert.assertEquals

/** Owner, 2026-11-23: Listen must stop from inside the app, and repeated taps must not queue up. */
@RunWith(RobolectricTestRunner::class)
class ListenStopTest {
    @get:Rule val compose = createComposeRule()
    private var now = 0L
    private companion object { var base = System.nanoTime() / 1_000_000 + 10_000_000L }
    private var stops = 0

    @Before fun setUp() { PcReading.stop(); base += 100_000; now = base; PcReading.clock = { now }; stops = 0; PcReading.stopper = { stops += 1 } }
    @After fun tearDown() { PcReading.stop(); PcReading.stopper = null; PcReading.clock = { System.nanoTime() / 1_000_000 } }

    @Test fun thePcVoiceShowsStopAndAnotherTapEndsIt() {
        var asked = 0
        val voice = FakeReadAloud()
        // The PC's voice: asking marks the reply as being read, as the station does.
        val pc = { asked += 1; PcReading.begin("m1"); Unit }
        compose.setContent { MimirTheme { CompositionLocalProvider(LocalReadAloud provides voice) { ListenButton("m1", "Hello.", pc) } } }
        compose.onNodeWithContentDescription("Read aloud").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Stop reading").assertExists()
        now += 1_000
        compose.onNodeWithContentDescription("Stop reading").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Read aloud").assertExists()
        assertEquals(1, asked)
        assertEquals(1, stops)
    }

    @Test fun fiveQuickTapsAskOnce() {
        var asked = 0
        val voice = FakeReadAloud()
        val pc = { asked += 1; PcReading.begin("m1"); Unit }
        compose.setContent { MimirTheme { CompositionLocalProvider(LocalReadAloud provides voice) { ListenButton("m1", "Hello.", pc) } } }
        repeat(5) {
            now += 60
            compose.onNodeWithContentDescription(if (it == 0) "Read aloud" else "Stop reading").performClick()
            compose.waitForIdle()
        }
        assertEquals(1, asked)
        assertEquals(0, stops)
    }
}
