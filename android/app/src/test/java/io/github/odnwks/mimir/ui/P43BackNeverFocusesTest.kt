package io.github.odnwks.mimir.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import io.github.odnwks.mimir.data.ExampleData
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Arriving on any screen by Back never puts the cursor in a text box, so the keyboard stays down. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w384dp-h832dp-xhdpi")
class P43BackNeverFocusesTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val tmp = TemporaryFolder()
    private val nav = Navigator()
    private val station = FakeStation(ExampleData.awake())
    private val id = ExampleData.TASK_ALLOW
    private val project get() = station.state.value.task(id)!!.project
    private val wrong = mutableListOf<String>()

    private fun start() {
        val engine = testEngine(tmp.newFolder(), ready = true)
        compose.setContent { CompositionLocalProvider(LocalPhoneEngine provides engine) { MimirTheme(reduceMotion = true) { AppRoot(station, nav) } } }
        settle()
    }

    private fun settle() { compose.mainClock.advanceTimeBy(1000); compose.waitForIdle() }

    /** The person touches the app, then does [act]. */
    private fun touch(act: () -> Unit) { compose.runOnUiThread { nav.keyboard.release(); act() }; settle() }

    private fun systemBack() = touch { compose.activity.onBackPressedDispatcher.onBackPressed() }
    private fun navBack() = touch { nav.back() }
    private fun close() = touch { nav.closeScreen() }

    private fun focused(): List<String> =
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Focused, true) and hasSetTextAction(), useUnmergedTree = true)
            .fetchSemanticsNodes().map { it.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString() ?: "a node" }

    private fun check(path: String) { focused().takeIf { it.isNotEmpty() }?.let { wrong += "$path: $it" } }

    private fun screens() = listOf(
        Screen.Chat, Screen.Status, Screen.Updates, Screen.Recent, Screen.Settings, Screen.NeedsYou, Screen.Task(id),
        Screen.Change(id), Screen.Project(project), Screen.Models, Screen.ModelDetails("none"), Screen.PhonePc,
        Screen.PcSettings, Screen.Notifications, Screen.IncognitoTask, Screen.Pair, Screen.PairDone, Screen.PairError,
        Screen.Standalone, Screen.StandaloneModels, Screen.AllTasks(), Screen.AllTasks(true), Screen.General,
        Screen.Storage, Screen.Voice, Screen.Voices, Screen.About, Screen.PrivacyCheck, Screen.GroundRules,
        Screen.Help, Screen.Labs, Screen.HelpAnswer("lost"), Screen.Chats,
    )

    @Test fun noBackPathEverFocusesABox() {
        start()
        for (screen in screens()) {
            // Back from the screen, to wherever it leads.
            for ((how, press) in listOf("system Back from" to ::systemBack, "closing" to ::close)) {
                touch { nav.resetTo(Screen.Chat) }
                touch { nav.open(screen) }
                press()
                check("$how $screen")
            }
            // Back onto the screen: from a task, a settings page, and a page that closes itself.
            for ((over, press) in listOf(Screen.Task(id) to ::systemBack, Screen.Task(id) to ::navBack, Screen.Voice to ::systemBack, Screen.Change(id) to ::close)) {
                if (over == screen) continue
                touch { nav.resetTo(Screen.Chat) }
                touch { nav.open(screen) }
                touch { nav.open(over) }
                press()
                check("back onto $screen from $over")
            }
        }
        assertEquals("", wrong.joinToString("\n"))
    }

    @Test fun backFromATaskIntoItsProjectLeavesItsBoxAlone() {
        start()
        for (inApp in listOf(false, true)) {
            touch { nav.resetTo(Screen.Chat) }
            touch { nav.open(Screen.Project(project)) }
            touch { nav.open(Screen.Task(id)) }
            if (inApp) navBack() else systemBack()
            assertEquals(Screen.Project(project), nav.current)
            assertEquals(emptyList<String>(), focused())
        }
    }

    @Test @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun besideEachOtherBackNeverFocusesEither() {
        start()
        touch { nav.open(Screen.Project(project)) }
        touch { nav.open(Screen.Task(id)) }
        systemBack()
        assertEquals(listOf(Screen.Project(project)), nav.detail.map { it.screen })
        assertEquals(emptyList<String>(), focused())
    }
}
