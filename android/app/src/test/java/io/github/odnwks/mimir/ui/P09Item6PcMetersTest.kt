package io.github.odnwks.mimir.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 0.09 items 6/12 and 11: the PC's three meters on Your PC, and the week's use whenever the PC has it. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class P09Item6PcMetersTest {
    @get:Rule val compose = createComposeRule()

    private fun show(state: StationState) {
        compose.setContent { MimirTheme(reduceMotion = true) { StatusScreen(state, FakeStation(state), Navigator()) } }
        compose.waitForIdle()
    }

    private val truthJson = """{"run_id":"r1","rev":1,"as_of":5,"pc":{"name":"Your PC","level":"Free","line":""},
        "tasks":[],"counts":{"working":0,"needs_you":0},"projects":[],
        "engine":{"chats_where":"Your PC","chats_model_words":"","answering":false,"paid_on":false},
        "usage":{"weekly":{"used":41.0,"resets_at":9999999999},"five_hour":null,"as_of":4},
        "stats":{"cpu":37.5,"mem_used":34359738368,"mem_total":68719476736,"gpu":12.0}}"""

    @Test fun theTruthCarriesTheMetersAndTheUseWithoutAPaidModel() {
        val t = Wire.truth(JSONObject(truthJson))
        assertEquals(37.5, t.stats!!.cpu, 0.001)
        assertEquals(68719476736L, t.stats!!.memTotal)
        assertEquals(12.0, t.stats!!.gpu!!, 0.001)
        assertEquals(41.0, t.usage!!.weekly!!.first, 0.001)
        assertEquals(false, t.engine.paidOn)
    }

    @Test fun anOlderPcWithoutMetersGivesNone() {
        val o = JSONObject(truthJson); o.remove("stats")
        assertNull(Wire.truth(o).stats)
    }

    @Test fun yourPcShowsThreeMetersInTheOldAppsWords() {
        show(ExampleData.awake().copy(pcStats = PcStats(38, 34359738368L, 68719476736L, 12)))
        compose.onNodeWithText("THIS PC").assertIsDisplayed()
        compose.onNodeWithText("Processor").assertIsDisplayed()
        compose.onNodeWithText("38%").assertIsDisplayed()
        compose.onNodeWithText("PC memory (RAM)").assertIsDisplayed()
        compose.onNodeWithText("32 of 64 GB").assertIsDisplayed()
        compose.onNodeWithText("Graphics").assertIsDisplayed()
        compose.onNodeWithText("12%").assertIsDisplayed()
    }

    @Test fun graphicsIsLeftOutWhenThePcCannotTell() {
        show(ExampleData.awake().copy(pcStats = PcStats(5, 1_073_741_824L, 8_589_934_592L, null)))
        compose.onNodeWithText("Processor").assertIsDisplayed()
        compose.onNodeWithText("Graphics").assertDoesNotExist()
    }

    @Test fun noMetersFromAnOlderPcShowsNoCard() {
        show(ExampleData.awake().copy(pcStats = null))
        compose.onNodeWithText("THIS PC").assertDoesNotExist()
    }

    @Test fun theMetersAlsoSitAtTheTopOfYourPcWhenThePcListsItsSessions() {
        val st = ExampleData.awake().copy(pcSessions = emptyList(), pcStats = PcStats(38, 34359738368L, 68719476736L, 12))
        compose.setContent { MimirTheme(reduceMotion = true) { PcSessionsScreen(st, FakeStation(st), Navigator()) } }
        compose.waitForIdle()
        compose.onNodeWithText("THIS PC").assertIsDisplayed()
        compose.onNodeWithText("38%").assertIsDisplayed()
    }
}
