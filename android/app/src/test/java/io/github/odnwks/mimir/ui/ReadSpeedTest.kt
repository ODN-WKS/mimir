package io.github.odnwks.mimir.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import io.github.odnwks.mimir.audio.ReadSpeed
import io.github.odnwks.mimir.data.ExampleData
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.nav.Sheet
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Owner, 2026-11-19: the read-aloud speed is a slider from 50% to 150% in 5% steps, in Settings ›
 * Voice and a chat's sheet, on top of each voice's own pace; a sample plays only when tapped.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w339dp-h740dp-xhdpi")
class ReadSpeedTest {
    @get:Rule val compose = createComposeRule()
    private val nav = Navigator()
    private val voice = FakeReadAloud()
    private lateinit var station: FakeStation

    private fun start(saved: Float = 1f) {
        voice.speed = saved
        station = FakeStation(ExampleData.awake())
        compose.setContent {
            CompositionLocalProvider(LocalReadAloud provides voice, LocalPlayerRuns provides false) { MimirTheme { AppRoot(station, nav) } }
        }
        compose.waitForIdle()
    }

    private fun slide(to: Float) {
        compose.onNodeWithContentDescription("Read-aloud speed").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(to) }
        compose.waitForIdle()
    }

    @Test fun stepsAreFivePercentFromFiftyToOneFifty() {
        assertEquals(0.75f, ReadSpeed.snap(0.75f))
        assertEquals(1.35f, ReadSpeed.snap(1.33f))
        assertEquals(1.5f, ReadSpeed.snap(2f))
        assertEquals(0.5f, ReadSpeed.snap(0.3f))
        assertEquals("115%", ReadSpeed.percent(1.15f))
        assertEquals(21, ReadSpeed.STEPS + 2)
    }

    @Test fun theSavedSpeedIsKeptAndAnOldOneMovesToTheNearestStep() {
        // 2× was a speed of the old cycling button: it becomes 150%.
        start(saved = 2f)
        assertEquals(1.5f, station.state.value.player.speed, 0.001f)
        assertEquals(1.5f, voice.speed, 0.001f)
    }

    @Test fun settingsVoiceHasTheSliderAndASampleOnlyWhenTapped() {
        start(saved = 1.25f)
        assertEquals(1.25f, station.state.value.player.speed, 0.001f)
        nav.open(Screen.Settings); nav.open(Screen.Voice); compose.waitForIdle()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "125%")).assertExists()
        slide(1.15f)
        assertEquals(1.15f, station.state.value.player.speed, 0.001f)
        // The phone's voices read at the same chosen speed.
        assertEquals(1.15f, voice.speed, 0.001f)
        // Nothing was read aloud by itself.
        assertTrue(voice.read.isEmpty())
        compose.onNodeWithContentDescription("Hear a sample at 115%").performScrollTo().performClick(); compose.waitForIdle()
        assertEquals(listOf(SPEED_SAMPLE), voice.read)
        // A second tap stops it.
        compose.onNodeWithContentDescription("Stop the sample").performClick(); compose.waitForIdle()
        assertEquals(null, voice.reading.value)
    }

    @Test fun hearItSaysTheVoiceIsGettingReadyUntilTheFirstSound() {
        start()
        voice.voiceName = "Ingrid"
        nav.open(Screen.Settings); nav.open(Screen.Voice); compose.waitForIdle()
        compose.onNodeWithText("Play at this speed").performScrollTo().performClick()
        voice.readying.value = SPEED_SAMPLE_ID; compose.waitForIdle()
        compose.onNodeWithText("Getting Ingrid ready…").assertExists()
        // Sound: the button says Stop.
        voice.readying.value = null; compose.waitForIdle()
        compose.onNodeWithText("Stop").assertExists()
        compose.onNodeWithText("Getting Ingrid ready…").assertDoesNotExist()
    }

    @Test fun theChatMenuNoLongerHasTheSliderButSettingsVoiceDoes() {
        start()
        nav.openSheet(Sheet.ChatOptions("Three easy dinner ideas", canRename = true, whisper = false, canWhisper = true)); compose.waitForIdle()
        compose.onNodeWithContentDescription("Read-aloud speed").assertDoesNotExist()
        nav.closeSheet(); nav.open(Screen.Settings); nav.open(Screen.Voice); compose.waitForIdle()
        slide(0.6f)
        assertEquals(0.6f, station.state.value.player.speed, 0.001f)
        assertTrue(voice.read.isEmpty())
    }

    @Test fun searchingSpeedFindsIt() {
        start()
        nav.open(Screen.Settings); compose.waitForIdle()
        compose.onNodeWithContentDescription("Search settings").performTextReplacement("speed"); compose.waitForIdle()
        compose.onNodeWithText("Read-aloud speed").performClick(); compose.waitForIdle()
        assertEquals(Screen.Voice, nav.current)
        compose.onNodeWithContentDescription("Read-aloud speed").assertExists()
    }
}
