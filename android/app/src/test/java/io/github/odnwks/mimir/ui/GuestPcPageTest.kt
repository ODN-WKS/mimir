package io.github.odnwks.mimir.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import io.github.odnwks.mimir.data.ExampleData
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A phone paired as another person sees the PC's name but not the owner-only Rename row or the anywhere switch. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class GuestPcPageTest {
    @get:Rule val compose = createComposeRule()

    private fun show(owner: Boolean) {
        val st = FakeStation(ExampleData.awake().copy(owner = owner))
        compose.setContent { MimirTheme { PhonePcScreen(st.state.value, st, Navigator()) } }
        compose.waitForIdle()
    }

    @Test fun aGuestSeesNoRenameRowOrAnywhereSwitch() {
        show(false)
        compose.onNodeWithText("Disconnect this phone").assertExists()
        compose.onNodeWithText("Rename").assertDoesNotExist()
        compose.onNodeWithText("Let this phone reach your PC from anywhere").assertDoesNotExist()
    }

    @Test fun theOwnerStillSeesBoth() {
        show(true)
        compose.onNodeWithText("Rename").assertExists()
        compose.onNodeWithText("Let this phone reach your PC from anywhere").assertExists()
    }
}
