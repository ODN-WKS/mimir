package io.github.odnwks.mimir.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.odnwks.mimir.standalone.Format
import io.github.odnwks.mimir.standalone.LocalState
import io.github.odnwks.mimir.standalone.OwnEntry
import io.github.odnwks.mimir.standalone.OwnFile
import io.github.odnwks.mimir.ui.theme.MimirTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** "Your own models" in Settings › Who answers: choosing the folder, the listing, and using a file. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w339dp-h740dp-xhdpi")
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class OwnModelsUiTest {
    @get:Rule val compose = createComposeRule()

    private class Actions : PhoneModelActions {
        val used = ArrayList<String>()
        var looked = 0
        override fun download(id: String) {}
        override fun cancelDownload() {}
        override fun removePartial(id: String) {}
        override fun delete(id: String) {}
        override fun test(id: String) {}
        override fun cancelTest() {}
        override fun lookAgain() { looked++ }
        override fun useOwn(docId: String) { used += docId }
    }

    private val qwen = OwnEntry("d1", "Qwen3-4B-abliterated-Q4_K_M.gguf", 2_500_000_000, Format.ChatMl, true, true)
    private val gemma = OwnEntry("d2", "gemma-4-E2B-it-Q4_K_M.gguf", 3_400_000_000, Format.Gemma4, false, false, OwnFile("d3", "mmproj-gemma-4-E2B-it-F16.gguf", 900_000_000))
    private val llama = OwnEntry("d4", "llama-2-7b.gguf", 3_800_000_000, null, false, false)
    private val huge = OwnEntry("d5", "Qwen3-32B-Q4_K_M.gguf", 19_000_000_000, Format.ChatMl, true, false)

    private fun show(state: LocalState, actions: Actions = Actions()): Actions {
        val phone = PhoneModels(MutableStateFlow(state), {}, actions, 8_000_000_000)
        compose.setContent { MimirTheme { androidx.compose.foundation.layout.Column { OwnModelsSection(state, phone) } } }
        return actions
    }

    @Test fun beforeAFolderIsChosenItOffersToChooseOneAndSaysWhyItsNeeded() {
        show(LocalState(supported = true))
        compose.onNodeWithText("Your own models").assertIsDisplayed()
        compose.onNodeWithText("Choose a models folder").assertIsDisplayed()
        compose.onNodeWithText("Android only lets an app read Downloads after you choose the folder once.", substring = true).assertIsDisplayed()
    }

    @Test fun eachFileShowsItsKindAndSizeAndWhatStopsIt() {
        val a = show(LocalState(supported = true, ownFolder = "Mimir models", own = listOf(qwen, gemma, llama, huge)))
        compose.onNodeWithText("Folder: Mimir models").assertIsDisplayed()
        compose.onNodeWithText("Change").assertIsDisplayed()
        compose.onNodeWithText("Qwen-style · 2.5 GB").assertIsDisplayed()
        compose.onNodeWithText("Uncensored").assertIsDisplayed()
        compose.onNodeWithText("Gemma-style · 3.4 GB").assertIsDisplayed()
        compose.onNodeWithText("Can see photos (mmproj-gemma-4-E2B-it-F16.gguf)").assertIsDisplayed()
        compose.onNodeWithText("Can't run this kind yet").assertIsDisplayed()
        compose.onNodeWithText("Too big for this phone").assertIsDisplayed()
        compose.onNodeWithText("Look again").assertExists()
        // Looked at when the store opens.
        compose.runOnIdle { assertEquals(1, a.looked) }
    }

    @Test fun useThisOneUsesThatFileAndAGreyedOneHasNoButton() {
        val a = show(LocalState(supported = true, ownFolder = "Mimir models", own = listOf(qwen, llama)))
        compose.onNodeWithText("Use this one").performClick()
        compose.runOnIdle { assertEquals(listOf("d1"), a.used) }
    }

    @Test fun theOneInUseIsMarked() {
        show(LocalState(supported = true, ownFolder = "Mimir models", own = listOf(qwen), model = qwen.id, have = setOf(qwen.id)))
        compose.onNodeWithText("In use").assertIsDisplayed()
    }

    @Test fun aFolderThatCannotBeReadSaysSo() {
        show(LocalState(supported = true, ownFolder = "Mimir models", ownLost = true))
        compose.onNodeWithText("This folder can't be read any more. Choose it again with Change.").assertIsDisplayed()
    }
}
