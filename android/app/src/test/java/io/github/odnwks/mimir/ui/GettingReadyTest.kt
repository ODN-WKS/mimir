package io.github.odnwks.mimir.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A read that waits for the voice to load says "Getting Ingrid ready…" instead of going quiet. */
@RunWith(RobolectricTestRunner::class)
class GettingReadyTest {
    @get:Rule val compose = createComposeRule()

    @Test fun theFirstReadShowsGettingIngridReadyUntilItSounds() {
        val voice = FakeReadAloud().apply { voiceName = "Ingrid" }
        compose.setContent { MimirTheme { CompositionLocalProvider(LocalReadAloud provides voice) { ReadAloudButton("m1", "Hello there.") } } }
        compose.onNodeWithText("Getting Ingrid ready…").assertDoesNotExist()
        compose.onNodeWithContentDescription("Read aloud").performClick()
        compose.runOnIdle { voice.readying.value = "m1" }
        compose.onNodeWithText("Getting Ingrid ready…").assertExists()
        compose.runOnIdle { voice.readying.value = null }
        compose.onNodeWithText("Getting Ingrid ready…").assertDoesNotExist()
    }
}
