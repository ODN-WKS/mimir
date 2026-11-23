package io.github.odnwks.mimir.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Settings › Who answers gets its two lines when the PC has "engines". */
@RunWith(RobolectricTestRunner::class)
class EnginesPanelTest {
    @get:Rule val compose = createComposeRule()

    @Test fun theTwoLinesShowAndSetWhatAnswers() {
        val sent = ArrayList<Pair<String?, String?>>()
        compose.setContent { MimirTheme { EnginesPanel("free", "claude") { c, p -> sent += c to p } } }
        compose.onNodeWithText("Chats and lookups").assertExists()
        compose.onNodeWithText("Project work").assertExists()
        // Both lines read "Your PC's model" and "Online": the first pair is chats, the second is project work.
        compose.onAllNodesWithText("Online")[0].performClick()
        compose.onAllNodesWithText("Your PC's model")[1].performClick()
        assertEquals(listOf<Pair<String?, String?>>("paid" to null, null to "free"), sent)
    }
}
