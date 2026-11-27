package io.github.odnwks.mimir.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import io.github.odnwks.mimir.data.ExampleData
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.data.LinkStation
import io.github.odnwks.mimir.data.Usage
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Fewer words: no hour greeting, no "not read yet", no name said twice. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h740dp-xhdpi")
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class QWordsTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    @Test fun theEverydayStartScreenHasNoGreeting() {
        androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSharedPreferences("phone_chat", 0).edit().putBoolean("has_chatted", true).commit()
        val engine = testEngine(tmp.newFolder(), ready = true)
        val station = FakeStation(LinkStation.empty("test").copy(paired = false))
        compose.setContent { CompositionLocalProvider(LocalPhoneEngine provides engine) { MimirTheme { AppRoot(station, Navigator()) } } }
        compose.waitForIdle()
        compose.onNodeWithText(EVERYDAY_LEAD).assertIsDisplayed()
        compose.onNodeWithText("Good ", substring = true).assertDoesNotExist()
    }

    @Test fun anUnknownClaudeUseIsNotMentioned() {
        val state = ExampleData.awake().copy(usage = Usage("—", 0, ""))
        compose.setContent { MimirTheme(reduceMotion = true) { SideMenu(state, null, FakeStation(state), null, Navigator(), MenuFold(null)) } }
        compose.waitForIdle()
        compose.onNodeWithText(MENU_USAGE).assertDoesNotExist()
        compose.onNodeWithText("not read yet", substring = true).assertDoesNotExist()
        assertNull(usageLeftWords(Usage("—", 0, "")))
    }

    @Test fun aNameMadeFromTheFirstWordsIsNotRepeatedUnderIt() {
        assertEquals("", wordsBeyondName("Name three kinds of grapes", "name three kinds of grapes"))
        assertEquals("", wordsBeyondName("Fix the login bug", "Can you please fix the login bug. It fails on Monday."))
        assertEquals("", wordsBeyondName("Why does it fail?", "why does it fail? I tried twice"))
        assertEquals("", wordsBeyondName("A very long name that was c…", "a very long name that was cut short here"))
        assertEquals("which soil is best", wordsBeyondName("Best soil for tomatoes", "which soil is best"))
        assertEquals("make the log in page nicer", wordsBeyondName("Log", "make the log in page nicer"))
    }
}
