package io.github.odnwks.mimir.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.data.StationState
import io.github.odnwks.mimir.link.Wire
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Tab
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * P23: "When online use runs out, use your PC's model" on the phone's Status: the PC's own setting, the same
 * words as the PC window, changeable while the PC is reached and has a model, and disabled with the reason
 * otherwise. The old "near my limit" switch and its percentage are gone.
 */
@RunWith(RobolectricTestRunner::class)
class P23FreeWhenOutTest {
    @get:Rule val compose = createComposeRule()
    private val nav = Navigator()

    private fun start(change: (StationState) -> StationState = { it }): FakeStation {
        val st = FakeStation().also { it.set(change) }
        compose.setContent { MimirTheme { AppRoot(st, nav) } }
        compose.waitForIdle()
        nav.selectTab(Tab.Status)
        compose.waitForIdle()
        return st
    }

    private fun switch() = compose.onNode(
        hasText(FreeWhenOut.TITLE) and SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState),
    )

    @Test fun theSwitchShowsThePcsStateAndChangesIt() {
        val st = start { it.copy(models = it.models.copy(switchToFree = true)) }
        switch().performScrollTo().assertIsEnabled().assertIsOn()
        compose.onNodeWithText(FreeWhenOut.ABOUT).assertExists()
        switch().performClick()
        compose.waitForIdle()
        assertFalse(st.state.value.models.switchToFree)
        switch().assertIsOff()
    }

    @Test fun itShowsWithoutAnOnlineChoiceToo() {
        // The old switch hid when no online choice was on; this one is the PC's and always shows.
        start { it.copy(paidOn = false) }
        switch().performScrollTo().assertIsEnabled()
    }

    @Test fun withoutAModelOnThePcItIsOffAndSaysWhy() {
        val st = start { it.copy(models = it.models.copy(local = emptyList(), switchToFree = true)) }
        switch().performScrollTo().assertIsNotEnabled().assertIsOff()
        compose.onNodeWithText(FreeWhenOut.NO_MODEL).assertExists()
        switch().performClick()
        compose.waitForIdle()
        assertTrue("a disabled switch changed", st.state.value.models.switchToFree)
    }

    @Test fun whileThePcIsAwayItCantBeChanged() {
        start { it.copy(pc = it.pc.copy(online = false)) }
        switch().performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(FreeWhenOut.AWAY).assertExists()
    }

    @Test fun someoneElsesPhoneDoesNotShowTheOwnersSwitch() {
        start { it.copy(owner = false) }
        switch().assertDoesNotExist()
    }

    @Test fun theNearMyLimitWordsAreGone() {
        start()
        compose.onNodeWithText("near my limit", substring = true).assertDoesNotExist()
        compose.onNodeWithText("small jobs use", substring = true).assertDoesNotExist()
    }

    @Test fun thePcsSettingIsWhatThePhoneShows() {
        for (on in listOf(true, false)) {
            val m = Wire.modelList(JSONObject("""{"free":[],"free_model":null,"switch_to_free":$on,"ollama":""}"""))!!
            assertEquals(on, m.switchToFree)
        }
        // The phone sends the field older PCs know.
        val call = JSONObject(Wire.setFreeChoice(true, null))
        assertEquals(true, call.getJSONObject("SetFreeChoice").getBoolean("switch_to_free"))
    }
}
