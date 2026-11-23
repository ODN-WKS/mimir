package io.github.odnwks.mimir.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.odnwks.mimir.data.ExampleData
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.data.Station
import io.github.odnwks.mimir.link.LongNotice
import io.github.odnwks.mimir.link.Wire
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** L2: a calm line on a task whose conversation has run long, drawn from the field the PC sends. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h740dp-xhdpi")
class LongNoticeTest {
    @get:Rule val compose = createComposeRule()
    private val base = FakeStation()
    private val started = mutableListOf<String>()
    private val station = object : Station by base { override fun startFresh(taskId: String) { started += taskId } }
    private val nav = Navigator()
    private val id = ExampleData.TASK_CELL
    private val online = LongNotice(very = false, own = false, elsewhere = false, pages = 340)

    private fun open(n: LongNotice?) {
        base.set { s -> s.copy(tasks = s.tasks.map { if (it.id == id) it.copy(long = n) else it }) }
        compose.setContent { MimirTheme { AppRoot(station, nav) } }
        nav.open(Screen.Task(id)); compose.waitForIdle()
    }

    private val line = "This conversation is long. Each reply re-reads all of it, so it uses more of your plan."

    @Test fun theLineAndButtonShowFromTheField_andTheButtonStartsFresh() {
        open(online)
        compose.onNodeWithText(line).assertIsDisplayed()
        compose.onNodeWithText("About the length of a 340-page book.").assertIsDisplayed()
        compose.onNodeWithText("Start fresh with a summary").performClick()
        assertEquals(listOf(id), started)
    }

    @Test fun noFieldMeansNoLine() {
        open(null)
        compose.onNodeWithText("Start fresh with a summary").assertDoesNotExist()
    }

    @Test fun onTheOwnModelItSaysSlowerAndForgotten() {
        open(online.copy(own = true))
        compose.onNodeWithText("This conversation is long. Answers get slower, and the start may be forgotten.").assertIsDisplayed()
    }

    @Test fun elsewhereHasNoButtonAndSaysSo() {
        open(online.copy(elsewhere = true))
        compose.onNodeWithText(line + " It is going on in another app, so it can't be started fresh from here.").assertIsDisplayed()
        compose.onNodeWithText("Start fresh with a summary").assertDoesNotExist()
    }

    @Test fun dismissedStaysDismissedUntilVeryLong() {
        open(online)
        compose.onNodeWithText("Not now").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(line).assertDoesNotExist()
        // The same level stays hidden; very long brings it back, once.
        assertTrue(longHidden(false, online))
        assertFalse(longHidden(false, online.copy(very = true)))
        assertTrue(longHidden(true, online.copy(very = true)))
        assertFalse(longHidden(null, online))
    }

    @Test fun veryLongIsAlittleStronger() {
        open(online.copy(very = true))
        compose.onNodeWithText(line + " Starting fresh soon will save a lot.").assertIsDisplayed()
    }

    @Test fun noTokenCountsOnScreen() {
        assertFalse(online.line.contains("000") || online.line.lowercase().contains("token"))
    }

    @Test fun fitsAt360dpWithFont13() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 1.3f)) {
                MimirTheme { Box(Modifier.width(360.dp)) { LongLine(online.copy(very = true), {}, {}) } }
            }
        }
        compose.onNodeWithText("Start fresh with a summary").assertIsDisplayed()
        assertTrue(compose.onNodeWithText("Start fresh with a summary").getUnclippedBoundsInRoot().let { it.right <= 360.dp && it.left >= 0.dp })
        compose.onNodeWithText("Not now").assertIsDisplayed()
    }

    @Test fun anOldPcThatSendsNoFieldGivesNone() {
        val dir = File("../../crates/proto/tests/fixtures")
        val line = File(dir, "events.jsonl").readLines().first { it.contains("\"Task\":{\"header\"") }
        assertNull((Wire.event(line).second as io.github.odnwks.mimir.link.Ev.TaskHeader).task.long)
        val with = line.replace("\"mode\":\"Ask\"", "\"mode\":\"Ask\",\"long\":{\"very\":true,\"why\":\"Own\",\"elsewhere\":false,\"pages\":340}")
        val n = (Wire.event(with).second as io.github.odnwks.mimir.link.Ev.TaskHeader).task.long!!
        assertEquals(LongNotice(very = true, own = true, elsewhere = false, pages = 340), n)
    }
}
