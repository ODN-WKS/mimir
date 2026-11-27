package io.github.odnwks.mimir.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import io.github.odnwks.mimir.data.ExampleData
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** P43: Back from a Settings page or a task goes where you came from, and a task says what it is. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h780dp-xxhdpi")
class P43BackAndTaskNameTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val tmp = TemporaryFolder()
    private val nav = Navigator()
    private val station = FakeStation(ExampleData.awake())
    private val id = ExampleData.TASK_ALLOW

    private fun start() {
        val engine = testEngine(tmp.newFolder(), ready = true)
        compose.setContent { CompositionLocalProvider(LocalPhoneEngine provides engine) { MimirTheme(reduceMotion = true) { AppRoot(station, nav) } } }
        compose.waitForIdle()
    }

    private fun systemBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun arrow() { compose.onAllNodesWithContentDescription("Back").onFirst().performClick(); compose.waitForIdle() }

    private fun open(vararg screens: Screen) { screens.forEach { compose.runOnUiThread { nav.open(it) }; compose.waitForIdle() } }

    private fun at(vararg screens: Screen) {
        assertEquals(screens.toList(), nav.stack.map { it.screen })
        assertFalse("the side menu stays shut", nav.menuOpen)
    }

    @Test fun backFromASettingsPageIsSettings() {
        start()
        for (page in listOf(Screen.Voice, Screen.Models, Screen.PrivacyCheck, Screen.General, Screen.Notifications, Screen.Storage, Screen.Help)) {
            for (press in listOf(::systemBack, ::arrow)) {
                compose.runOnUiThread { nav.resetTo(Screen.Chat) }; compose.waitForIdle()
                open(Screen.Settings, page)
                press()
                assertEquals("back from $page", listOf(Screen.Chat, Screen.Settings), nav.stack.map { it.screen })
                assertFalse(nav.menuOpen)
            }
        }
    }

    @Test fun backFromAHelpArticleIsHelpThenSettingsThenTheMenu() {
        start()
        open(Screen.Settings, Screen.Help, Screen.HelpAnswer("lost"))
        systemBack(); at(Screen.Chat, Screen.Settings, Screen.Help)
        arrow(); at(Screen.Chat, Screen.Settings)
        systemBack()
        assertEquals(listOf(Screen.Chat), nav.stack.map { it.screen })
        assertTrue(nav.menuOpen)
    }

    @Test fun backFromASettingsPageOpenedInAChatIsTheChat() {
        start()
        open(Screen.Models)
        systemBack(); at(Screen.Chat)
    }

    // A menu row is still a place of the menu's own: Back from it is the menu again.
    @Test fun backFromAMenuRowStillOpensTheMenu() {
        start()
        compose.menuRow(MENU_USAGE)
        assertEquals(Screen.Models, nav.current)
        systemBack()
        assertEquals(listOf(Screen.Chat), nav.stack.map { it.screen })
        assertTrue(nav.menuOpen)
    }

    @Test fun aTaskShowsAnArrowItsNameAndItsProject() {
        start()
        open(Screen.Task(id))
        val task = station.state.value.task(id)!!
        compose.onAllNodesWithContentDescription("Back").onFirst().assertIsDisplayed()
        compose.onAllNodesWithText(task.title).onFirst().assertIsDisplayed()
        compose.onAllNodesWithText(task.project).onFirst().assertIsDisplayed()
    }

    @Test fun oneBackFromATaskIsItsProject() {
        start()
        val project = station.state.value.task(id)!!.project
        for (press in listOf(::systemBack, ::arrow)) {
            compose.runOnUiThread { nav.resetTo(Screen.Chat) }; compose.waitForIdle()
            open(Screen.Project(project), Screen.Task(id))
            press(); at(Screen.Chat, Screen.Project(project))
        }
    }

    @Test fun oneBackFromATaskIsWhereverItWasOpenedFrom() {
        start()
        for (from in listOf(Screen.NeedsYou, Screen.Recent)) {
            compose.runOnUiThread { nav.resetTo(Screen.Chat) }; compose.waitForIdle()
            open(from, Screen.Task(id))
            systemBack(); at(Screen.Chat, from)
        }
        compose.runOnUiThread { nav.resetTo(Screen.Chat) }; compose.waitForIdle()
        open(Screen.Task(id))
        arrow(); at(Screen.Chat)
    }

    // A task carried on into a fresh one: still one press to where the first was opened from.
    @Test fun aTaskOpenedFromATaskStillLeavesInOnePress() {
        start()
        open(Screen.NeedsYou, Screen.Task(id), Screen.Task(ExampleData.TASK_ALLOW + "-next"))
        systemBack(); at(Screen.Chat, Screen.NeedsYou)
    }

    @Test fun inTwoPanesATaskGoesBackToTheProjectBesideIt() {
        val n = Navigator().also { it.fitPanes(true) }
        n.open(Screen.Project("Recipe box")); n.open(Screen.Task("t"))
        assertTrue(n.back())
        assertEquals(listOf(Screen.Project("Recipe box")), n.detail.map { it.screen })
        n.resetTo(Screen.Chat); n.open(Screen.Settings); n.open(Screen.Help); n.open(Screen.HelpAnswer("lost"))
        assertTrue(n.back())
        assertEquals(listOf(Screen.Help), n.detail.map { it.screen })
    }
}
