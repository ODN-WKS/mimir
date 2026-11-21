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

/** The model and effort chips on Mímir's message box, with no PC. */
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

    @Test fun theEffortChipPicksQuickBalancedOrThorough() {
        val (engine, _) = show()
        compose.onNodeWithContentDescription("Effort: Balanced. Change effort").assertIsDisplayed().performClick()
        compose.onNodeWithText("Short answers, straight away").assertIsDisplayed()
        compose.onNodeWithText("Quick").performClick()
        compose.runOnIdle { assertEquals(Effort.Quick, engine.state.value.effort) }
        compose.onNodeWithContentDescription("Effort: Quick. Change effort").assertIsDisplayed()
    }

    @Test fun theModelChipShowsWhatAnswersAndListsTheModelsOnThisPhone() {
        val (_, nav) = show()
        val name = Catalog.default.chipName
        compose.onNodeWithContentDescription("Model: $name. Change model").assertIsDisplayed().performClick()
        compose.onNodeWithText(Catalog.default.sizeText, substring = true).assertIsDisplayed()
        compose.onNodeWithText("More in Settings › Who answers").performClick()
        compose.runOnIdle { assertEquals(Screen.Models, nav.current) }
    }

    /** The box is one tidy row under the words (owner, 2026-11-19): +, mic, chips and Send never wrap, at 100% and 150%. */
    private fun oneRow(scale: Float, paired: Boolean = false) {
        androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSharedPreferences(io.github.odnwks.mimir.ui.theme.AppSize.PREFS, 0).edit()
            .putFloat(io.github.odnwks.mimir.ui.theme.AppSize.SCALE, scale).commit()
        try {
            show(paired = paired)
            compose.waitForIdle()
            val send = compose.onNodeWithContentDescription("Send").fetchSemanticsNode().boundsInRoot
            val where = if (paired) "Model: " else "Model: ${Catalog.default.chipName}. Change model"
            // With the least room the effort moves into the + menu instead of wrapping.
            val effortChip = compose.onAllNodesWithContentDescription("Effort: Balanced. Change effort").fetchSemanticsNodes().isNotEmpty()
            for (d in listOfNotNull("Attach", "Talk instead of typing", where, "Effort: Balanced. Change effort".takeIf { effortChip })) {
                val b = compose.onNodeWithContentDescription(d, substring = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                assertEquals("$d is on Send's row at ${scale}x", send.center.y, b.center.y, 2f)
                org.junit.Assert.assertTrue("$d is left of Send at ${scale}x", b.right <= send.left + 1f)
            }
            compose.onRoot().captureRoboImage("${System.getProperty("roborazzi.outputDir")}/mimir_composer_${scale}${if (paired) "_paired" else ""}.png")
            if (scale == 1f) org.junit.Assert.assertTrue("at normal size the effort has its own chip", effortChip)
            if (!effortChip) {
                compose.onNodeWithContentDescription("Attach").performClick(); compose.waitForIdle()
                compose.onNodeWithText("Effort: Balanced").assertIsDisplayed()
            }
        } finally {
            androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
                .getSharedPreferences(io.github.odnwks.mimir.ui.theme.AppSize.PREFS, 0).edit().clear().commit()
        }
    }

    @Test fun theBoxsBottomRowIsOneLineAtNormalSize() = oneRow(1f)

    @Test fun atSize150TheBoxsBottomRowStillDoesNotWrap() = oneRow(1.5f)

    @Test fun pairedTheBottomRowIsOneLineAtNormalSize() = oneRow(1f, paired = true)

    @Test fun pairedAtSize150TheBottomRowStillDoesNotWrap() = oneRow(1.5f, paired = true)

    @Test fun withNoModelOnThePhoneYetThereIsNoModelChip() {
        show(ready = false)
        compose.onNodeWithContentDescription("Model: ${Catalog.default.chipName}. Change model").assertDoesNotExist()
        compose.onNodeWithContentDescription("Effort: Balanced. Change effort").assertIsDisplayed()
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
        fun words(description: String): String = compose.onNodeWithContentDescription(description, substring = true).assertIsDisplayed()
            .fetchSemanticsNode().config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.joinToString { t -> t.text }.orEmpty()
        val model = words(if (paired) "Model: " else "Model: ${Catalog.default.chipName}. Change model")
        val eff = words("Effort: ${effort.words}. Change effort")
        org.junit.Assert.assertTrue("the model chip says words, not only an icon (was '$model')", model.isNotBlank())
        org.junit.Assert.assertTrue("the effort chip says its word (was '$eff')", eff.contains(effort.words))
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
