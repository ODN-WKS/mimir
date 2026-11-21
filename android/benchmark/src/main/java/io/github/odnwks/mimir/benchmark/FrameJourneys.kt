package io.github.odnwks.mimir.benchmark

import android.content.Intent
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The five journeys of the frame budget (redesign proposal, section 6), on the check build's app
 * opened with its example data, so no PC is needed. Each one's frames are measured; the budget
 * (under 5% late frames, 99th percentile under 16 ms) is checked by tools/frame-check.sh.
 */
@RunWith(AndroidJUnit4::class)
class FrameJourneys {
    @get:Rule val rule = MacrobenchmarkRule()

    private fun journey(steps: MacrobenchmarkScope.() -> Unit) = rule.measureRepeated(
        packageName = APP,
        metrics = listOf(FrameTimingMetric()),
        iterations = 5,
        startupMode = StartupMode.COLD,
        setupBlock = {
            startActivityAndWait(
                Intent().setClassName(APP, "$BASE.MainActivity")
                    .putExtra("check", true).putExtra("example", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            )
            device.wait(Until.hasObject(By.desc("Message Claude")), WAIT)
        },
    ) { steps() }

    private fun MacrobenchmarkScope.tap(selector: androidx.test.uiautomator.BySelector) {
        device.wait(Until.findObject(selector), WAIT)?.click()
        device.waitForIdle()
    }

    /** Open the app's tabs one after another and back. */
    @Test fun switchTabs() = journey {
        tap(By.text("Status")); tap(By.text("Updates")); tap(By.text("Chat"))
    }

    /** Open the keyboard, type, send. */
    @Test fun keyboardAndSend() = journey {
        tap(By.desc("Message Claude"))
        device.findObject(By.desc("Message Claude"))?.text = "Check the release notes"
        device.waitForIdle()
        tap(By.desc("Send"))
        device.pressBack()
        device.waitForIdle()
    }

    /** A task starts from a message, and its line under the message appears. */
    @Test fun taskStarts() = journey {
        device.findObject(By.desc("Message Claude"))?.text = "Make the tiles bigger"
        tap(By.desc("Send"))
        device.wait(Until.hasObject(By.descContains("Make the tiles")), WAIT)
        device.waitForIdle()
    }

    /** Open a task from its line in the chat, and come back. */
    @Test fun openAndCloseATask() = journey {
        tap(By.descContains("Opens the task"))
        device.pressBack()
        device.waitForIdle()
    }

    /** Scroll Updates down and up. */
    @Test fun scrollUpdates() = journey {
        tap(By.text("Updates"))
        val list = device.findObject(By.scrollable(true)) ?: return@journey
        list.setGestureMargin(device.displayWidth / 5)
        list.fling(Direction.DOWN); device.waitForIdle()
        list.fling(Direction.UP); device.waitForIdle()
    }

    private companion object {
        const val BASE = "io.github.odnwks.mimir"
        const val APP = "$BASE.dev"
        const val WAIT = 5_000L
    }
}
