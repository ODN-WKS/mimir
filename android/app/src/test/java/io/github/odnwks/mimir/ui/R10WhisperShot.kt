package io.github.odnwks.mimir.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.data.LinkStation
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.standalone.Turn
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Draws a Whisper once to a picture (the phone's Whisper screen can't be screenshotted on a device). Writes only when asked. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h740dp-xhdpi")
class R10WhisperShot {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    @Test fun whisper() {
        val out = System.getenv("R10_SHOT") ?: return
        val engine = testEngine(tmp.newFolder(), ready = true)
        val station = FakeStation(LinkStation.empty("test").copy(paired = false))
        compose.setContent { CompositionLocalProvider(LocalPhoneEngine provides engine) { MimirTheme(reduceMotion = true) { AppRoot(station, Navigator()) } } }
        compose.waitForIdle()
        engine.startWhisper(); compose.waitForIdle()
        engine.ask("What are the signs of burnout?")
        val until = System.currentTimeMillis() + 5_000
        while (engine.state.value.writing && System.currentTimeMillis() < until) Thread.sleep(10)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(out)
    }
}
