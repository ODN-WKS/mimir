package io.github.odnwks.mimir.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import io.github.odnwks.mimir.data.ExampleData
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.ui.theme.MimirTheme
import io.github.odnwks.mimir.update.UpdateStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** R27: Settings › Help and about › App updates carries the Updates section. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w339dp-h720dp-xhdpi")
class R27UpdatesScreenTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    @Test fun theUpdatesSectionIsOnTheAppUpdatesPage() {
        val nav = Navigator()
        val engine = testEngine(tmp.newFolder(), ready = true)
        compose.setContent { CompositionLocalProvider(LocalPhoneEngine provides engine) { MimirTheme { AppRoot(FakeStation(ExampleData.awake()), nav) } } }
        nav.open(Screen.Settings); nav.open(Screen.Help); nav.open(Screen.About); compose.waitForIdle()
        for (t in listOf("Updates", "Also on mobile data", "Check now", UPDATES_PRIVACY, UPDATES_TEST_BUILD)) {
            compose.onNodeWithText(t).performScrollTo().assertIsDisplayed()
        }
    }

    @Test fun theStatusLinesAreInPlainWords() {
        assertEquals("You have the newest version, 0.02.", updateStatusWords(UpdateStatus.Newest("0.02"), "0.02"))
        assertEquals("Version 0.03 is ready. Install now?", updateStatusWords(UpdateStatus.Ready("0.03", ""), "0.02"))
        assertEquals("Installed 0.03.", updateStatusWords(UpdateStatus.Installed("0.03"), "0.03"))
        assertEquals("The update didn't check out, so it wasn't installed.", updateStatusWords(UpdateStatus.Failed(io.github.odnwks.mimir.update.UpdateWords.DIDNT_CHECK_OUT), "0.02"))
    }
}
