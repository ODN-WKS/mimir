package io.github.odnwks.mimir.benchmark

import android.content.Intent
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The journeys of the frame budget, on the check build's app opened with its example data, so no
 * PC is needed. Each one's frames are measured; the budget (under 5% late frames, 95th percentile
 * within the frame time) is checked by tools/frame-check.sh. The same steps ([Steps]) are what
 * ProfileGenerator walks to make the app's baseline profile.
 */
@RunWith(AndroidJUnit4::class)
class FrameJourneys {
    @get:Rule val rule = MacrobenchmarkRule()

    // Only the cold start journey restarts the app's process; the others open it fresh (the setup
    // starts it again on a cleared task) and measure just their own steps.
    private fun journey(startup: StartupMode? = null, setup: MacrobenchmarkScope.() -> Unit = { Steps.start(this) }, steps: MacrobenchmarkScope.() -> Unit) =
        rule.measureRepeated(
            packageName = Steps.APP,
            metrics = listOf(FrameTimingMetric()),
            iterations = 5,
            startupMode = startup,
            setupBlock = setup,
        ) { steps() }

    /** Cold start to the chat screen. */
    @Test fun coldStartToChat() = journey(startup = StartupMode.COLD, setup = { pressHome() }) { Steps.start(this) }

    /** Open the side menu and close it again. */
    @Test fun sideMenu() = journey { Steps.menu(this) }

    /** Open Settings from the menu, and come back. */
    @Test fun settings() = journey { Steps.settings(this) }

    /** Write a message, send it, and scroll the chat down and up. */
    @Test fun chatScroll() = journey { Steps.chatScroll(this) }

    /** Turn the device sideways and back (tablets only: phones stay upright). */
    @Test fun rotate() {
        val m = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics
        assumeTrue("phones stay upright", minOf(m.widthPixels, m.heightPixels) / m.density >= 600)
        journey { Steps.rotate(this) }
    }
}

/** The steps, shared by the measured journeys and the profile generator. */
object Steps {
    const val BASE = "io.github.odnwks.mimir"
    const val APP = "$BASE.dev"
    const val WAIT = 5_000L

    fun start(s: MacrobenchmarkScope) {
        s.startActivityAndWait(
            Intent().setClassName(APP, "$BASE.MainActivity")
                .putExtra("check", true).putExtra("example", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        s.device.wait(Until.hasObject(By.desc("Message Mímir")), WAIT)
    }

    private fun MacrobenchmarkScope.tap(selector: BySelector) {
        val found = device.wait(Until.findObject(selector), WAIT)
        found?.click()
        device.waitForIdle()
    }

    private fun MacrobenchmarkScope.openMenu() = tap(By.descStartsWith("Menu"))

    fun menu(s: MacrobenchmarkScope) = with(s) {
        openMenu()
        device.pressBack(); device.waitForIdle()
    }

    fun settings(s: MacrobenchmarkScope) = with(s) {
        openMenu()
        tap(By.text("Settings"))
        device.wait(Until.gone(By.text("voice, models, privacy")), WAIT)
        device.waitForIdle()
        device.pressBack(); device.waitForIdle()
    }

    fun chatScroll(s: MacrobenchmarkScope) = with(s) {
        tap(By.desc("Message Mímir"))
        device.findObject(By.desc("Message Mímir"))?.text = "Check the release notes"
        device.waitForIdle()
        tap(By.desc("Send"))
        device.pressBack(); device.waitForIdle()
        val list = device.wait(Until.findObject(By.scrollable(true)), WAIT) ?: return
        list.setGestureMargin(device.displayWidth / 5)
        list.fling(Direction.DOWN); device.waitForIdle()
        list.fling(Direction.UP); device.waitForIdle()
    }

    fun rotate(s: MacrobenchmarkScope) = with(s) {
        device.setOrientationLeft(); device.waitForIdle()
        device.setOrientationNatural(); device.waitForIdle()
    }
}
