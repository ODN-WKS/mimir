package io.github.odnwks.mimir.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.data.LinkStation
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.standalone.Catalog
import io.github.odnwks.mimir.standalone.Effort
import io.github.odnwks.mimir.standalone.Engine
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The model and effort chip on Mímir's message box (P12: one "Model · Effort" chip and the task's sheet). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w339dp-h740dp-xhdpi")
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class MimirChipsTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private fun show(ready: Boolean = true, paired: Boolean = false): Pair<Engine, Navigator> {
        val engine = testEngine(tmp.newFolder(), ready = ready)
        val nav = Navigator()
        val unpaired = if (paired) FakeStation().also { it.set { s -> s.copy(pcTalks = true, pc = s.pc.copy(name = "Your PC")) } }
            else FakeStation(LinkStation.empty("test").copy(paired = false))
        compose.setContent {
            CompositionLocalProvider(LocalPhoneEngine provides engine) { MimirTheme { AppRoot(unpaired, nav) } }
        }
        return engine to nav
    }

    private val chipLabel get() = "Model and effort: ${chipShort(Catalog.default.chipWord, 16)} · Balanced. Change"

    @Test fun theChipsSheetPicksQuickBalancedOrThorough() {
        val (engine, nav) = show()
        compose.onNodeWithContentDescription(chipLabel).assertIsDisplayed().performClick()
        compose.onNodeWithText("Short answers, straight away").assertIsDisplayed()
        compose.onNodeWithText("Quick").performClick()
        compose.runOnIdle { assertEquals(Effort.Quick, engine.state.value.effort) }
        // A pick keeps the sheet open, as on a task.
        compose.onNodeWithText("Model for this chat").assertIsDisplayed()
        compose.runOnIdle { nav.closeSheet() }
        compose.onNodeWithContentDescription("Model and effort: ${chipShort(Catalog.default.chipWord, 16)} · Quick. Change").assertIsDisplayed()
    }

    @Test fun theChipShowsWhatAnswersAndItsSheetListsTheModelsOnThisPhone() {
        val (_, nav) = show()
        compose.onNodeWithContentDescription(chipLabel).assertIsDisplayed().performClick()
        compose.onNodeWithText(Catalog.default.sizeText, substring = true).assertIsDisplayed()
        compose.onNodeWithText("More in Settings › Who answers").performClick()
        compose.runOnIdle { assertEquals(Screen.Models, nav.current) }
    }

    /** The box is one tidy row under the words (P12): +, the chip, the mic (an empty chat) and Send never wrap, at 100% and 150%. */
    private fun oneRow(scale: Float, paired: Boolean = false) {
        androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSharedPreferences(io.github.odnwks.mimir.ui.theme.AppSize.PREFS, 0).edit()
            .putFloat(io.github.odnwks.mimir.ui.theme.AppSize.SCALE, scale).commit()
        try {
            show(paired = paired)
            compose.waitForIdle()
            val send = compose.onNodeWithContentDescription("Send").fetchSemanticsNode().boundsInRoot
            var left = -1f
            // An empty chat has no three dots: its mic stays, beside Send.
            for (d in listOf("Attach", "Model and effort: ", "Talk instead of typing")) {
                val b = compose.onNodeWithContentDescription(d, substring = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                assertEquals("$d is on Send's row at ${scale}x", send.center.y, b.center.y, 2f)
                org.junit.Assert.assertTrue("$d is left of Send at ${scale}x", b.right <= send.left + 1f)
                org.junit.Assert.assertTrue("$d is right of the one before at ${scale}x", b.left >= left)
                left = b.right
            }
            compose.onRoot().captureRoboImage("${System.getProperty("roborazzi.outputDir")}/mimir_composer_${scale}${if (paired) "_paired" else ""}.png")
        } finally {
            androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
                .getSharedPreferences(io.github.odnwks.mimir.ui.theme.AppSize.PREFS, 0).edit().clear().commit()
        }
    }

    @Test fun theBoxsBottomRowIsOneLineAtNormalSize() = oneRow(1f)

    @Test fun atSize150TheBoxsBottomRowStillDoesNotWrap() = oneRow(1.5f)

    @Test fun pairedTheBottomRowIsOneLineAtNormalSize() = oneRow(1f, paired = true)

    @Test fun pairedAtSize150TheBottomRowStillDoesNotWrap() = oneRow(1.5f, paired = true)

    @Test fun withNoModelOnThePhoneYetTheChipNamesOnlyTheEffort() {
        show(ready = false)
        compose.onNodeWithContentDescription(chipLabel).assertDoesNotExist()
        compose.onNodeWithContentDescription("Model and effort: Balanced. Change").assertIsDisplayed()
    }

    @Test fun withAModelThatCantSeeThePlusOffersAFileAndLeadsToModelsForPictures() {
        val (_, nav) = show()
        compose.onNodeWithContentDescription("Attach").assertIsDisplayed().performClick()
        compose.onNodeWithText("Text files: notes, lists, code").assertIsDisplayed()
        compose.onNodeWithText("This model can't see pictures: switch to a model that sees photos in Settings › Who answers").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(Screen.Models, nav.current) }
    }

    @Test fun withAModelThatSeesThePlusOffersPhotosAndTheCamera() {
        val dir = tmp.newFolder()
        val e2b = Catalog.byId("gemma4-e2b-heretic-q4km")
        java.io.File(dir, "models").apply { mkdirs() }.let { java.io.File(it, e2b.file).writeText("m"); java.io.File(it, e2b.visionFile).writeText("v") }
        io.github.odnwks.mimir.standalone.Store(java.io.File(dir, "standalone.bin"), PlainCodec)
            .save(io.github.odnwks.mimir.standalone.Saved(e2b.id, emptyList()))
        val engine = io.github.odnwks.mimir.standalone.Engine(dir, io.github.odnwks.mimir.standalone.Store(java.io.File(dir, "standalone.bin"), PlainCodec), WordsRunner(), supported = true)
        val unpaired = FakeStation(LinkStation.empty("test").copy(paired = false))
        compose.setContent { CompositionLocalProvider(LocalPhoneEngine provides engine) { MimirTheme { AppRoot(unpaired, Navigator()) } } }
        compose.onNodeWithContentDescription("Attach").performClick()
        compose.onNodeWithText("Photo or video").assertIsDisplayed()
        compose.onNodeWithText("Take a photo").assertIsDisplayed()
    }

    @Test fun aFileSentWithAMessageShowsUnderIt() {
        val (engine, _) = show()
        compose.runOnIdle {
            engine.send("Sum it up", listOf(io.github.odnwks.mimir.standalone.TurnFile("shopping.md", "Eggs")),
                listOf(io.github.odnwks.mimir.standalone.TurnPicture.of("Fridge", byteArrayOf(1, 2, 3))))
        }
        compose.waitUntil(5000) { !engine.state.value.writing }
        compose.onNodeWithText("shopping.md").assertIsDisplayed()
        compose.onNodeWithContentDescription("Picture: Fridge").assertIsDisplayed()
    }

    @Test fun attachedFilesShowAsChipsThatCanBeRemovedAndLetTheMessageGoWithNoWords() {
        val files = androidx.compose.runtime.mutableStateListOf(io.github.odnwks.mimir.standalone.TurnFile("notes.txt", "Hi", cut = true))
        var sent = 0
        compose.setContent {
            MimirTheme {
                val voice = VoiceInput(androidx.compose.ui.platform.LocalContext.current, station = null, phoneSpeech = { null to null })
                MimirComposer("", {}, waiting = false, writing = false, voice = voice, onSend = { sent++ }, onStop = {},
                    hasFiles = files.isNotEmpty(), above = { files.toList().forEach { f -> FileChip(f) { files.remove(f) } } })
            }
        }
        compose.onNodeWithText("notes.txt · start only").assertIsDisplayed()
        compose.onNodeWithContentDescription("Send").performClick()
        compose.runOnIdle { assertEquals(1, sent) }
        compose.onNodeWithContentDescription("Remove notes.txt").performClick()
        compose.onNodeWithContentDescription("Talk instead of typing").assertIsDisplayed()
    }

    private fun bothChipsShowWords(effort: Effort, paired: Boolean = false) {
        val (engine, _) = show(paired = paired)
        compose.runOnIdle { engine.setEffort(effort) }
        compose.waitForIdle()
        val words = compose.onNodeWithContentDescription("Model and effort: ", substring = true).assertIsDisplayed()
            .fetchSemanticsNode().config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.joinToString { t -> t.text }.orEmpty()
        org.junit.Assert.assertTrue("the chip names a model before the effort (was '$words')", words.substringBefore(" · ").isNotBlank() && words.contains(" · "))
        org.junit.Assert.assertTrue("the chip says the effort's word (was '$words')", words.endsWith(effort.words))
        compose.onRoot().captureRoboImage("${System.getProperty("roborazzi.outputDir")}/mimir_chips_${effort.words}_${paired}.png")
    }

    @Test fun atThePhoneWidthTheLongestEffortAndTheModelChipBothShowWords() = bothChipsShowWords(Effort.Thorough)

    @Test @Config(qualifiers = "+w320dp") fun atTheNarrowestWidthTheChipsStillShowWords() = bothChipsShowWords(Effort.Thorough)

    @Test @Config(qualifiers = "+w320dp") fun pairedAtTheNarrowestWidthTheChipsStillShowWords() = bothChipsShowWords(Effort.Thorough, paired = true)

    @Test fun aLongModelNameIsShortenedToWholeWordsNeverCutToAnIcon() {
        assertEquals("Qwen 3.6", chipShort("Qwen 3.6 35B-A3B"))
        assertEquals("Auto-pick", chipShort("Auto-pick"))
        assertEquals("Smartest", chipShort("Smartest"))
        assertEquals("Supercalif…", chipShort("Supercalifragilistic", 11))
    }
}
