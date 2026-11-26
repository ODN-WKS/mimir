package io.github.odnwks.mimir.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.odnwks.mimir.data.ExampleData
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.data.PcStats
import io.github.odnwks.mimir.data.StationState
import io.github.odnwks.mimir.link.Wire
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** P16: the processor and graphics lines on Your PC start with their temperature, as plain text (owner, 2026-11-25). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class P16PcTemperaturesTest {
    @get:Rule val compose = createComposeRule()

    private val cpuLine = "61 °C · 14/32 threads · 44%"
    private val gpuLine = "54 °C · 12%"
    private val warm = PcStats(44, 34359738368L, 68719476736L, 12, threads = 32, cpuTempC = 61, gpuTempC = 54)

    private fun show(state: StationState, fontScale: Float = 1f) {
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
                MimirTheme(reduceMotion = true) { StatusScreen(state, FakeStation(state), Navigator()) }
            }
        }
        compose.waitForIdle()
    }

    private fun truthLine(): JSONObject {
        val line = File("../../crates/proto/tests/fixtures/events.jsonl").readLines().first { it.contains("\"Truth\"") }
        return JSONObject(line).getJSONObject("event").getJSONObject("Truth").getJSONObject("truth")
    }

    @Test fun theWireCarriesBothTemperatures() {
        val s = Wire.truth(truthLine()).stats!!
        assertEquals(61, s.cpuTempC)
        assertEquals(54, s.gpuTempC)
    }

    @Test fun anOlderPcWithoutTemperaturesStillParses() {
        val o = truthLine()
        o.getJSONObject("stats").apply { remove("cpu_temp_c"); remove("gpu_temp_c") }
        val s = Wire.truth(o).stats!!
        assertNull(s.cpuTempC)
        assertNull(s.gpuTempC)
        assertEquals(37.0, s.cpu, 0.001)
    }

    @Test fun theTemperatureComesFirstOnBothLines() {
        show(ExampleData.awake().copy(pcStats = warm))
        compose.onNodeWithText("Processor").assertIsDisplayed()
        compose.onNodeWithText(cpuLine).assertIsDisplayed()
        compose.onNodeWithText("Graphics").assertIsDisplayed()
        compose.onNodeWithText(gpuLine).assertIsDisplayed()
        compose.onAllNodesWithText("61 °C", substring = true).assertCountEquals(1)
        compose.onAllNodesWithText("54 °C", substring = true).assertCountEquals(1)
    }

    @Test fun nothingExtraWhenThePcCannotReadThem() {
        show(ExampleData.awake().copy(pcStats = warm.copy(cpuTempC = null, gpuTempC = null)))
        compose.onNodeWithText("14/32 threads · 44%").assertIsDisplayed()
        compose.onNodeWithText("12%").assertIsDisplayed()
        compose.onAllNodesWithText("°C", substring = true).assertCountEquals(0)
    }

    @Test @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun atTheNarrowestWidthAndLargeTextNoWordIsCut() {
        show(ExampleData.awake().copy(pcStats = warm), fontScale = 1.3f)
        for (t in listOf(cpuLine, gpuLine, "Processor", "Graphics")) {
            val n = compose.onNodeWithText(t, useUnmergedTree = true)
            n.assertIsDisplayed()
            val results = mutableListOf<TextLayoutResult>()
            n.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
            val r = results.single()
            assertTrue("clipped: $t", !r.hasVisualOverflow)
            assertTrue("past the edge: $t", n.getBoundsInRoot().right <= 320.dp)
            // Lines only ever break at a space, never inside a word.
            for (i in 0 until r.lineCount - 1) {
                val end = r.getLineEnd(i)
                assertTrue("word cut in \"$t\" at $end", t[end - 1] == ' ' || t[end] == ' ')
            }
        }
    }
}
