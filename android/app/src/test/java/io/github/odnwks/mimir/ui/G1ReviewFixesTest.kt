package io.github.odnwks.mimir.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.odnwks.mimir.data.Chip
import io.github.odnwks.mimir.data.ExampleData
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.data.PcSession
import io.github.odnwks.mimir.data.PcSessionState
import io.github.odnwks.mimir.data.ProjectTask
import io.github.odnwks.mimir.data.Task
import io.github.odnwks.mimir.data.TaskMessage
import io.github.odnwks.mimir.data.shortTitle
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.nav.Sheet
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Review findings on the 0.07 task screens (G1). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class G1ReviewFixesTest {
    @get:Rule val compose = createComposeRule()
    private val nav = Navigator()
    private val now = 1_800_000_000L
    private val id = ExampleData.TASK_ALLOW

    private fun elsewhere() = PcSession("s-1", "Plan", "Garden planner", false, true, id, PcSessionState.Working, now)
    private fun station(mut: (Task) -> Task, sessions: List<PcSession>?) = FakeStation().also { st ->
        st.set { s -> s.copy(pcSessions = sessions, tasks = s.tasks.map { if (it.id == id) mut(it) else it }) }
    }
    private fun show(st: FakeStation) { compose.setContent { MimirTheme(reduceMotion = true) { AppRoot(st, nav) } }; compose.waitForIdle(); nav.open(Screen.Task(id)); compose.waitForIdle() }

    /** The PC's list says it works elsewhere, but the task itself doesn't say so yet: Stop shows at once. */
    @Test fun theListSayingItWorksElsewhereShowsStopAtOnce() {
        show(station({ it.copy(liveElsewhere = false, stopped = false) }, listOf(elsewhere())))
        compose.onNodeWithContentDescription(FOLLOW_STOP).assertIsDisplayed()
    }

    /** An older PC that lists no sessions: the menu keeps a Stop row for a task going on elsewhere. */
    @Test fun anOlderPcsMenuKeepsStopForATaskGoingOnElsewhere() {
        show(station({ it.copy(liveElsewhere = true, stopped = false) }, null))
        nav.openSheet(Sheet.TaskOptions(id)); compose.waitForIdle()
        compose.onNodeWithText(STOP_MENU_ROW).assertIsDisplayed()
    }
}
