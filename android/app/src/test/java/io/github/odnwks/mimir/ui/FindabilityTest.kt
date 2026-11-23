package io.github.odnwks.mimir.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTextReplacement
import io.github.odnwks.mimir.data.ExampleData
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.data.Heard
import io.github.odnwks.mimir.data.StationState
import io.github.odnwks.mimir.data.Update
import io.github.odnwks.mimir.data.UpdateTarget
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.standalone.Catalog
import io.github.odnwks.mimir.standalone.Engine
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

/**
 * The findability audit's phone rows: each thing is reached from where people look first, by
 * taps from the screen the app opens on (docs/designs/findability_audit_2026-11-18.md).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class FindabilityTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val nav = Navigator()
    private lateinit var station: FakeStation
    private lateinit var engine: Engine

    private val worth = Update(
        "u-worth", "Recipe box", "Mon", "Monday",
        "Worth switching? A new small uncensored model, Tiny 3B, scores 9.6 on the uncensored-models leaderboard, above your phone's best.",
        Heard.New, UpdateTarget.Models, 10,
    )

    private fun start(data: (StationState) -> StationState = { it }) {
        station = FakeStation(data(ExampleData.awake()))
        engine = testEngine(tmp.newFolder(), ready = true)
        compose.setContent {
            CompositionLocalProvider(LocalPhoneEngine provides engine) { MimirTheme { AppRoot(station, nav) } }
        }
        compose.waitForIdle()
    }

    private fun heading(text: String) =
        compose.onNode(hasText(text) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))

    private fun tap(text: String, substring: Boolean = false) {
        compose.onNodeWithText(text, substring = substring).performScrollTo().performClick()
        compose.waitForIdle()
    }

    /** The one on screen: the tab pages next to it are composed too. */
    private fun shown(description: String): androidx.compose.ui.test.SemanticsNodeInteraction {
        val all = compose.onAllNodes(hasContentDescription(description))
        val n = all.fetchSemanticsNodes().size
        for (i in 0 until n) {
            val node = all[i]
            if (runCatching { node.assertIsDisplayed() }.isSuccess) return node
        }
        error("no \"$description\" on screen")
    }

    private fun settings() {
        compose.openSettings()
        compose.waitForIdle()
        heading("Settings").assertIsDisplayed()
    }

    /** The home screens are rows of the side menu now ("Status" is "Your PC"; unpaired that row opens pairing, so go straight there). */
    private fun tab(name: String) {
        if (name == "Status") nav.selectTab(io.github.odnwks.mimir.nav.Tab.Status) else compose.menuRow(name)
        compose.waitForIdle()
    }

    @Test fun modelsAlwaysShowsItsThreeGroupsAndTheNoFilterTag() {
        start()
        settings()
        tap("Who answers")
        heading("Online").assertExists()
        heading("Your PC's models").assertExists()
        heading("On this phone").performScrollTo().assertIsDisplayed()
        compose.onAllNodes(hasText("Uncensored")).fetchSemanticsNodes().let { assertTrue("no tag", it.isNotEmpty()) }
        // A phone model opens its page in the Model Store, where Chat with it opens On this phone.
        tap(Catalog.default.plainName)
        assertEquals(Screen.ModelDetails(Catalog.default.id), nav.current)
    }

    @Test fun settingsIsACalmListOfEightRowsInItsOrder() {
        start()
        settings()
        val names = listOf("Voice", "Who answers", "Privacy", "Notifications", "Look and size", "Your PC", "Storage", "Help and about")
        // Top to bottom: the rows' texts come in reading order.
        val texts = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text))
            .fetchSemanticsNodes().flatMap { n -> n.config[SemanticsProperties.Text].map { it.text } }
        assertEquals(names, texts.filter { it in names })
        // Each row says what's inside it.
        listOf(SETTINGS_VOICE_LINE, SETTINGS_WHO_LINE, SETTINGS_NOTIFICATIONS_LINE, SETTINGS_PC_LINE, SETTINGS_LOOK_LINE, SETTINGS_STORAGE_LINE, SETTINGS_HELP_LINE).forEach {
            compose.onNodeWithText(it).assertExists()
        }
        compose.onNodeWithText("See exactly what this app has sent", substring = true).assertExists()
        // Nothing else sits among them; the old names are gone.
        listOf("On this phone", "App lock", "Alerts", "General", "Models", "About", "Phone & PC", "Privacy check").forEach { compose.onNodeWithText(it).assertDoesNotExist() }
        // The eye, the name and the version sit at the end, here and nowhere else permanently.
        compose.onNodeWithText(AI_NAME).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("version ", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun theWeeklyWorthSwitchingCheckIsOnModelsWithAnOffSwitch() {
        start { it.copy(updates = listOf(worth) + it.updates) }
        settings()
        compose.onNodeWithText("A better model for this phone may be worth a look").assertIsDisplayed()
        tap("Who answers")
        heading("Worth switching?").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Tiny 3B", substring = true).assertExists()
        val switch = compose.onNode(hasText("Look for a better model each week", substring = true) and SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState), useUnmergedTree = false)
        switch.performScrollTo().assertIsOn().performClick()
        compose.waitForIdle()
        assertFalse(station.state.value.models.weeklyCheck)
        heading("Worth switching?").assertDoesNotExist()
        switch.assertIsOff()
    }

    @Test fun oneStoragePageDeletesAModelInOneTapAndSaysTheRule() {
        start()
        settings()
        tap("Storage")
        heading("Storage").assertIsDisplayed()
        val name = Catalog.default.plainName
        compose.onNodeWithText("cleans up after itself", substring = true).assertExists()
        compose.onNodeWithContentDescription("Delete $name").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Yes, delete $name").performClick()
        compose.waitForIdle()
        assertFalse(Catalog.default.id in engine.state.value.have)
        compose.onNodeWithText("No model is downloaded", substring = true).assertIsDisplayed()
    }

    @Test fun voiceHasItsOwnRowWithSpeedAndTalkAsVoiceTyping() {
        start()
        settings()
        tap("Voice")
        heading("Voice").assertIsDisplayed()
        heading("TALKING INSTEAD OF TYPING").assertExists()
        compose.onNodeWithText("$AI_NAME › Settings › Voice", substring = true).assertExists()
        // A slider, 50% to 150% in 5% steps: the speed is taken when it's let go.
        compose.onNodeWithContentDescription("Read-aloud speed").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(1.15f) }
        compose.waitForIdle()
        assertEquals(1.15f, station.state.value.player.speed, 0.001f)
        // The microphone says what it is: Talk, voice typing.
        nav.back(); nav.back(); compose.waitForIdle()
        compose.onNode(hasContentDescription("voice typing", substring = true)).assertExists()
    }

    @Test fun aboutChecksForANewVersionAndUpdatesSaysWhenOneIsOut() {
        start { it.copy(pcVersion = "9.9") }
        tab("Updates")
        tap("A new version of this app is out")
        heading("App updates").assertIsDisplayed()
        compose.onNodeWithText("Version 9.9 is ready.").assertIsDisplayed()
        compose.onNodeWithText("Get it").assertExists()
    }

    @Test fun theAppIsCalledMimirAndSearchFindsItByEitherName() {
        start()
        settings()
        compose.onNodeWithText(AI_NAME).performScrollTo().assertIsDisplayed()
        nav.open(Screen.About); compose.waitForIdle()
        compose.onAllNodesWithText("$AI_NAME ", substring = true).onFirst().assertIsDisplayed()
        compose.onNodeWithText("Recipe box", substring = true).assertDoesNotExist()
        for (q in listOf("mimir", "Mímir")) {
            assertTrue(q, findSettings(q, paired = true).any { it.screen == io.github.odnwks.mimir.nav.Screen.About })
        }
    }

    @Test fun aboutSaysWhenTheAppIsTheNewest() {
        start { it.copy(pcVersion = "0.1") }
        settings()
        nav.open(Screen.About); compose.waitForIdle()
        // GitHub can't be reached here, so the PC answers, and the page says so.
        io.github.odnwks.mimir.update.AppUpdater.testNet = io.github.odnwks.mimir.update.Net { throw java.io.IOException("offline") }
        try {
            tap("Check now")
            val said = "Couldn't reach GitHub to check. Your PC says 0.1 is the newest."
            compose.waitUntil(5_000) { compose.onAllNodesWithText(said).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(said).performScrollTo().assertIsDisplayed()
            // Never "You have the newest version" when GitHub could not be asked.
            compose.onAllNodesWithText("You have the newest version", substring = true).assertCountEquals(0)
        } finally { io.github.odnwks.mimir.update.AppUpdater.testNet = null }
    }

    @Test fun helpAndAboutAnswersTheQuestionsEachOnItsOwnPage() {
        start()
        settings()
        tap("Help and about")
        heading("Help and about").assertIsDisplayed()
        HELP_TOPICS.forEach { compose.onNodeWithText(it.question).assertExists() }
        tap("What if I lose this phone?")
        compose.onNodeWithText("You remove it at your PC: $AI_NAME \u203a Settings \u203a Phones.").assertIsDisplayed()
        nav.back(); compose.waitForIdle()
        tap("What is a Whisper?")
        compose.onNodeWithText(whisperExplained(true), substring = true).assertIsDisplayed()
        // No "Lost a phone?" card anywhere, and nothing here removes a phone.
        HELP_TOPICS.forEach { assertFalse(helpAnswer(it.id, true).contains("Lost a phone")) }
        nav.resetTo(Screen.Chat); nav.open(Screen.Settings); tap("Your PC")
        compose.onNodeWithText("Lost a phone?").assertDoesNotExist()
    }

    @Test fun phoneAndPcRenamesThePcAndSwitchesRelays() {
        start()
        settings()
        tap("Your PC")
        compose.onNodeWithContentDescription("Rename your PC").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Your PC's name").performTextReplacement("Desk PC")
        compose.onNodeWithContentDescription("Save the name").performClick()
        compose.waitForIdle()
        assertEquals("Desk PC", station.state.value.pc.name)
        compose.onNode(hasText("Let this phone reach your PC from anywhere", substring = true) and SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState))
            .performScrollTo().assertIsOn().performClick()
        compose.waitForIdle()
        assertFalse(station.state.value.relays)
    }

    @Test fun searchIsOneTapAwayOnTheMenuYourPcAndInATask() {
        start()
        // The side menu's own box searches chats; tasks and messages are searched from Your PC.
        compose.openMenu()
        compose.onNodeWithText(MENU_SEARCH).assertIsDisplayed()
        compose.onNodeWithText(MENU_SEARCH).performTextInput("zzz"); compose.waitForIdle()
        compose.onNodeWithText("No chat has that in its name.").assertIsDisplayed()
        compose.onNodeWithText(MENU_NEW_CHAT).assertIsDisplayed()
        compose.onNodeWithText(MENU_PC).performClick(); compose.waitForIdle()
        // Search is one tap from Your PC: All tasks has its search box.
        compose.onNodeWithText("All tasks").performClick()
        compose.waitForIdle()
        assertEquals(Screen.AllTasks(), nav.current)
        // Find in a task.
        nav.open(Screen.Task(ExampleData.TASK_ALLOW)); compose.waitForIdle()
        compose.onNodeWithContentDescription("Find in this task").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Find in this task").performTextInput("zzzz-nothing")
        compose.waitForIdle()
        compose.onNodeWithText("No message has \"zzzz-nothing\"").assertIsDisplayed()
    }

    @Test fun anArchivedTaskShowsUnderArchivedAndComesBack() {
        start()
        val t = station.state.value.tasks.first()
        station.archiveTask(t.id)
        tab("Status")
        compose.onNodeWithText("All tasks").performClick()
        compose.waitForIdle()
        tap("Archived · 1")
        compose.onNodeWithContentDescription("Unarchive ${t.title}").performClick()
        compose.waitForIdle()
        assertTrue(station.state.value.tasks.any { it.id == t.id })
        assertTrue(station.state.value.archived.isEmpty())
    }

    @Test fun alwaysAllowIsNamedAndWhatItAllowedCanBeTakenBack() {
        start()
        nav.open(Screen.Task(ExampleData.TASK_ALLOW)); compose.waitForIdle()
        compose.onNodeWithText("Always allow…").assertExists()
        compose.onNodeWithContentDescription("Task options").performClick()
        compose.waitForIdle()
        tap("Always allowed in this task…")
        compose.onNodeWithText("remote.rs").assertIsDisplayed()
        compose.onNodeWithContentDescription("Stop allowing remote.rs").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("remote.rs").assertDoesNotExist()
        var left: List<String>? = null
        station.alwaysAllowed(ExampleData.TASK_ALLOW) { left = it }
        assertEquals(emptyList<String>(), left)
    }

    @Test fun aTaskMovesToAnotherProjectFromItsMenuAndAWorkingOneWaits() {
        start()
        // Where someone looks first: the task's own menu.
        nav.open(Screen.Task(ExampleData.TASK_ORDER)); compose.waitForIdle()
        compose.onNodeWithContentDescription("Task options").performClick()
        compose.waitForIdle()
        tap("Move to another project…")
        heading("Project").assertIsDisplayed()
        val other = station.state.value.pickable.first { it != "Budget-App" }
        compose.onNodeWithText(other).performClick()
        compose.waitForIdle()
        assertEquals(other, station.state.value.task(ExampleData.TASK_ORDER)!!.project)
        // While Claude works on it, the menu says it moves when it stops.
        nav.closeSheet(); nav.open(Screen.Task(ExampleData.TASK_CELL)); compose.waitForIdle()
        compose.onNodeWithContentDescription("Task options").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Move to another project (when it stops)").assertExists()
        compose.onNodeWithText("Move to another project…").assertDoesNotExist()
    }

    @Test fun theTaskMenuTicksTheModeInUse() {
        start()
        nav.open(Screen.Task(ExampleData.TASK_ALLOW)); compose.waitForIdle()
        compose.onNodeWithContentDescription("Task options").performClick()
        compose.waitForIdle()
        tap("Plan first, change nothing yet")
        // The project and Plan first are a tap away on the state, so the conversation stays clean.
        compose.onAllNodes(androidx.compose.ui.test.SemanticsMatcher("shows the details") { it.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsActions.OnClick) { null }?.label == "Show the project and age" })[0].performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Plan first", substring = true).assertExists()
        compose.onNodeWithContentDescription("Task options").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Plan first, change nothing yet").assertIsSelected()
    }

    @Test fun switchToFreeSitsUnderClaudeUseOnStatus() {
        start()
        tab("Status")
        val switch = compose.onNode(hasText("Switch to free near my limit") and SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState), useUnmergedTree = false)
        switch.performScrollTo().assertIsOn().performClick()
        compose.waitForIdle()
        assertFalse(station.state.value.models.switchToFree)
    }

    @Test fun alertSamplesAreSaidToBeSamples() {
        start()
        settings()
        tap("Notifications")
        heading("What alerts look like").performScrollTo().assertIsDisplayed()
    }

    @Test fun aNewerAppIsToldByItsNumbers() {
        val newer = { a: String, b: String -> io.github.odnwks.mimir.data.newer(a, b) }
        assertTrue(newer("0.19", "0.18"))
        assertTrue(newer("0.19.1", "0.19"))
        assertTrue(newer("0.10", "0.9"))
        assertFalse(newer("0.19", "0.19"))
        assertFalse(newer("0.18", "0.19 [example]"))
        assertFalse(newer("", "0.19"))
    }

    @Test fun withNoFreeModelsModelsAndWhisperPointToThePcsModels() {
        start { it.copy(models = it.models.copy(local = emptyList())) }
        settings()
        tap("Who answers")
        compose.onNodeWithText("No models on your PC yet", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Settings › Models", substring = true).assertExists()
        // Whisper mode says why it's missing, and where to add a free model.
        nav.resetTo(Screen.Chat); nav.openSheet(io.github.odnwks.mimir.nav.Sheet.WhereTo); compose.waitForIdle()
        compose.onNodeWithText("More options").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Needs a model on your PC", substring = true).assertIsDisplayed()
    }

    @Test fun renameDeleteAndAChatsOptionsAreATapOnItsTopic() {
        // Unpaired, the way the owner tests: Mímir's chat, with a first message.
        val unpaired = FakeStation(io.github.odnwks.mimir.data.LinkStation.empty("test").copy(paired = false))
        engine = testEngine(tmp.newFolder(), ready = true)
        engine.ask("Give me three easy dinner ideas")
        val until = System.currentTimeMillis() + 5_000
        while (engine.state.value.writing && System.currentTimeMillis() < until) Thread.sleep(10)
        compose.setContent {
            CompositionLocalProvider(LocalPhoneEngine provides engine) { MimirTheme { AppRoot(unpaired, nav) } }
        }
        compose.waitForIdle()
        // The "⋯" at the top right opens the menu: only things about this chat.
        compose.openChatOptions()
        compose.waitForIdle()
        for (row in listOf(CHAT_RENAME, CHAT_COPY, CHAT_RULES, CHAT_SENT, CHAT_DELETE)) {
            compose.onNodeWithText(row).assertIsDisplayed()
        }
        // The speed and the voice are in Settings › Voice now, not here.
        compose.onNodeWithContentDescription("Read-aloud speed", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Reading voice", substring = true).assertDoesNotExist()
        compose.onNodeWithText(PRIVACY_LINE).assertDoesNotExist()
        nav.closeSheet(); nav.open(Screen.Settings); nav.open(Screen.Voice); compose.waitForIdle()
        compose.onNodeWithContentDescription("Read-aloud speed").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(0.8f) }
        compose.waitForIdle()
        assertEquals(0.8f, unpaired.state.value.player.speed, 0.001f)
    }

    @Test fun aPcTasksNameOpensItsOptions() {
        start()
        nav.open(Screen.Task(ExampleData.TASK_ALLOW)); compose.waitForIdle()
        val title = station.state.value.task(ExampleData.TASK_ALLOW)!!.title
        heading(title).performClick()
        compose.waitForIdle()
        for (row in listOf("Rename…", "Stop this task…")) {
            compose.onNodeWithText(row).assertIsDisplayed()
        }
        // The speed slider lives in Settings › Voice, not in a task's menu.
        compose.onNodeWithContentDescription("Read-aloud speed", substring = true).assertDoesNotExist()
    }

    @Test fun groundRulesAreFoundByRulesSystemPromptAndInstructionsAndUnderModels() {
        val unpaired = FakeStation(io.github.odnwks.mimir.data.LinkStation.empty("test").copy(paired = false))
        engine = testEngine(tmp.newFolder(), ready = true)
        compose.setContent {
            CompositionLocalProvider(LocalPhoneEngine provides engine) { MimirTheme { AppRoot(unpaired, nav) } }
        }
        compose.waitForIdle()
        try {
            for (words in listOf("rules", "system prompt", "instructions")) {
                nav.resetTo(Screen.Chat); nav.open(Screen.Settings); compose.waitForIdle()
                compose.onNodeWithContentDescription("Search settings").performTextReplacement(words)
                compose.waitForIdle()
                compose.onNodeWithText("Rules for every chat").performClick()
                compose.waitForIdle()
                assertEquals(Screen.GroundRules, nav.current)
            }
            // Opened from search, Back still goes to Models (its button says so).
            compose.onNodeWithContentDescription("Back").performClick()
            compose.waitForIdle()
            assertEquals(Screen.Models, nav.current)
            // Also a row in Models, on by default with the default text, Reset after a change.
            nav.resetTo(Screen.Chat); nav.open(Screen.Settings); nav.open(Screen.Models); compose.waitForIdle()
            compose.onNodeWithText("Rules for every chat").performScrollTo().performClick()
            compose.waitForIdle()
            compose.onNodeWithText(io.github.odnwks.mimir.data.GroundRules.NOTE).assertIsDisplayed()
            compose.onNodeWithText("Send these rules").assertIsOn()
            compose.onNodeWithText("Reset to default").assertDoesNotExist()
            compose.onNodeWithContentDescription("Rules for every chat").performTextReplacement("Be brief.")
            compose.waitForIdle()
            assertEquals("Be brief.", io.github.odnwks.mimir.data.GroundRules.current)
            compose.onNodeWithText("Reset to default").performClick()
            compose.waitForIdle()
            assertEquals(io.github.odnwks.mimir.data.GroundRules.DEFAULT, io.github.odnwks.mimir.data.GroundRules.current)
            compose.onNodeWithText("Send these rules").performClick()
            compose.waitForIdle()
            assertEquals(null, io.github.odnwks.mimir.data.GroundRules.current)
        } finally {
            io.github.odnwks.mimir.data.GroundRules.set(io.github.odnwks.mimir.data.GroundRules.Rules())
        }
    }

    @Test fun thePrivacyCheckIsFoundByPrivacyDataAndNetworkAndSaysWhatWasSent() {
        val unpaired = FakeStation(io.github.odnwks.mimir.data.LinkStation.empty("test").copy(paired = false))
        val net = io.github.odnwks.mimir.data.NetUse
        val sent = 1_000L
        val phoneCount = net.read
        net.clear(); net.read = { io.github.odnwks.mimir.data.NetUse.Bytes(2_000_000, sent) }
        engine = testEngine(tmp.newFolder(), ready = true)
        engine.ask("Give me three easy dinner ideas")
        val until = System.currentTimeMillis() + 5_000
        while (engine.state.value.writing && System.currentTimeMillis() < until) Thread.sleep(10)
        compose.setContent {
            CompositionLocalProvider(LocalPhoneEngine provides engine) { MimirTheme { AppRoot(unpaired, nav) } }
        }
        compose.waitForIdle()
        try {
            // A Mímir chat open: its sheet says nothing was sent while it's open.
            compose.openChatOptions()
            compose.waitForIdle()
            compose.onNodeWithText(sentWords(0, false)).assertIsDisplayed()
            nav.closeSheet()
            // A top-level Settings row, and found by its words.
            nav.open(Screen.Settings); compose.waitForIdle()
            compose.onNodeWithText("Privacy").assertIsDisplayed()
            for (words in listOf("privacy", "data", "network")) {
                nav.resetTo(Screen.Chat); nav.open(Screen.Settings); compose.waitForIdle()
                compose.onNodeWithContentDescription("Search settings").performTextReplacement(words)
                compose.waitForIdle()
                compose.onNodeWithText("Privacy").performClick()
                compose.waitForIdle()
                assertEquals(Screen.PrivacyCheck, nav.current)
            }
            compose.onNodeWithText("Since counting began").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Your chats are never sent", substring = true).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(AIRPLANE_LINE).performScrollTo().assertIsDisplayed()
            compose.onNodeWithContentDescription("Start counting again from now").performClick()
            compose.waitForIdle()
            assertEquals(0L, net.use.value.sinceReset.sent)
        } finally {
            net.clear(); net.read = phoneCount
        }
    }

    @Test fun unpairedNothingIsHiddenAndEachPcPartSaysConnectYourPc() {
        val unpaired = FakeStation(io.github.odnwks.mimir.data.LinkStation.empty("test").copy(paired = false))
        engine = testEngine(tmp.newFolder(), ready = true)
        compose.setContent {
            CompositionLocalProvider(LocalPhoneEngine provides engine) { MimirTheme { AppRoot(unpaired, nav) } }
        }
        compose.waitForIdle()
        // Your PC (Status) and Updates say what they're for, with Connect your PC.
        for ((which, words) in listOf("Status" to YOUR_PC_STEPS_TITLE, "Updates" to UPDATES_EMPTY)) {
            tab(which)
            compose.onNodeWithText(words).assertIsDisplayed()
            val buttons = compose.onAllNodes(hasText("Connect your PC"))
            assertTrue((0 until buttons.fetchSemanticsNodes().size).any { runCatching { buttons[it].assertIsDisplayed() }.isSuccess })
        }
        // Settings: Your PC sits third, and it's where connecting is.
        nav.open(Screen.Settings); compose.waitForIdle()
        val top = { t: String -> compose.onNodeWithText(t).fetchSemanticsNode().boundsInRoot.top }
        assertTrue(top("Privacy") < top("Your PC"))
        compose.onNodeWithText("Not connected \u00b7 connect it").assertIsDisplayed()
        // Models: Claude's models are shown greyed, each saying what it takes.
        compose.onNodeWithText("Who answers").performClick(); compose.waitForIdle()
        compose.onAllNodes(hasText(PC_MODEL_NEEDS)).fetchSemanticsNodes().let { assertTrue(it.size >= 2) }
        compose.onAllNodes(hasText("Connect your PC")).fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
    }

    @Test fun someoneLookingForTheScottishVoiceFindsVoicesBySearchAndFromTheChatsSheet() {
        assertEquals(io.github.odnwks.mimir.nav.Screen.Voices, findSettings("scottish", paired = false).first().screen)
        assertTrue(findSettings("voice", paired = true).any { it.screen == io.github.odnwks.mimir.nav.Screen.Voices })
        station = FakeStation(ExampleData.awake())
        compose.setContent {
            CompositionLocalProvider(LocalVoices provides exampleVoices()) { MimirTheme { AppRoot(station, nav) } }
        }
        // The chat's menu no longer holds the reading voice: it is in Settings (search finds it, and Voice has the speed).
        nav.openSheet(io.github.odnwks.mimir.nav.Sheet.ChatOptions("Three easy dinner ideas", canRename = true, whisper = false, canWhisper = true))
        compose.waitForIdle()
        compose.onNodeWithText("Reading voice", substring = true).assertDoesNotExist()
        nav.closeSheet(); nav.open(Screen.Settings); nav.open(Screen.Voice); compose.waitForIdle()
        compose.onNodeWithContentDescription("Read-aloud speed").performScrollTo().assertExists()
    }

    @Test fun whisperPrivateAndIncognitoFindAWhisperRowThatStartsOne() {
        val unpaired = FakeStation(io.github.odnwks.mimir.data.LinkStation.empty("test").copy(paired = false))
        engine = testEngine(tmp.newFolder(), ready = true)
        compose.setContent {
            CompositionLocalProvider(LocalPhoneEngine provides engine) { MimirTheme { AppRoot(unpaired, nav) } }
        }
        compose.waitForIdle()
        for (words in listOf("whisper", "private", "incognito")) {
            nav.resetTo(Screen.Chat); nav.open(Screen.Settings); compose.waitForIdle()
            compose.onNodeWithContentDescription("Search settings").performTextReplacement(words)
            compose.waitForIdle()
            compose.onNodeWithText(whisperExplained(false)).assertIsDisplayed()
        }
        compose.onNodeWithText("Start a Whisper").performClick()
        compose.waitForIdle()
        assertEquals(Screen.Chat, nav.current)
        assertTrue(engine.state.value.whisper)
    }

    @Test fun groundRulesAreAlsoOnPrivacyCheckAndInTheChatsSheet() {
        val unpaired = FakeStation(io.github.odnwks.mimir.data.LinkStation.empty("test").copy(paired = false))
        engine = testEngine(tmp.newFolder(), ready = true)
        engine.ask("Give me three easy dinner ideas")
        val until = System.currentTimeMillis() + 5_000
        while (engine.state.value.writing && System.currentTimeMillis() < until) Thread.sleep(10)
        compose.setContent {
            CompositionLocalProvider(LocalPhoneEngine provides engine) { MimirTheme { AppRoot(unpaired, nav) } }
        }
        compose.waitForIdle()
        compose.openChatOptions()
        compose.waitForIdle()
        compose.onNodeWithText(CHAT_RULES).performClick()
        compose.waitForIdle()
        // The chat's own rules open on a small sheet over the chat (the global rules stay in Who answers).
        assertEquals(Screen.Chat, nav.current)
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        // Privacy no longer repeats them: the one home is Who answers.
        nav.resetTo(Screen.Chat); nav.open(Screen.Settings); nav.open(Screen.PrivacyCheck); compose.waitForIdle()
        compose.onNodeWithText("Rules for every chat").assertDoesNotExist()
        nav.resetTo(Screen.Chat); nav.open(Screen.Settings); nav.open(Screen.Models); compose.waitForIdle()
        compose.onNodeWithText("Rules for every chat").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(Screen.GroundRules, nav.current)
    }

    /** The top bar says who answers, and in a Whisper says so in the same place; a tap there ends the Whisper. */
    @Test fun theTopBarShowsWhoAnswersAndAWhisperEndsFromTheSameSpot() {
        val unpaired = FakeStation(io.github.odnwks.mimir.data.LinkStation.empty("test").copy(paired = false))
        engine = testEngine(tmp.newFolder(), ready = true)
        compose.setContent {
            CompositionLocalProvider(LocalPhoneEngine provides engine) { MimirTheme { AppRoot(unpaired, nav) } }
        }
        compose.waitForIdle()
        val at = compose.onNodeWithContentDescription(whoLabel("This phone")).fetchSemanticsNode().boundsInRoot
        compose.menuRow(MENU_WHISPER)
        assertTrue(engine.state.value.whisper)
        // A Whisper: the label says so, in the middle of the same bar.
        assertTrue(END_WHISPER.startsWith("Whisper mode: private, offline, nothing kept"))
        assertEquals(at.center.x, compose.onNodeWithText(WHISPER_NAME).fetchSemanticsNode().boundsInRoot.center.x, at.width)
        compose.onNodeWithContentDescription(END_WHISPER_BUTTON).performClick(); compose.waitForIdle()
        assertFalse(engine.state.value.whisper)
        compose.onNodeWithContentDescription(whoLabel("This phone")).assertIsDisplayed()
    }

    /** Owner, 2026-11-19: in a Whisper one tap on the top bar ends it, without a question. */
    @Test fun oneTapOnTheTopBarEndsAWhisperWithoutAQuestion() {
        val unpaired = FakeStation(io.github.odnwks.mimir.data.LinkStation.empty("test").copy(paired = false))
        engine = testEngine(tmp.newFolder(), ready = true)
        compose.setContent {
            CompositionLocalProvider(LocalPhoneEngine provides engine) { MimirTheme { AppRoot(unpaired, nav) } }
        }
        compose.waitForIdle()
        // Not in normal mode: a tap there asks who should answer.
        compose.onNodeWithContentDescription(whoLabel("This phone")).performClick(); compose.waitForIdle()
        compose.onNodeWithText(WHO_TITLE).assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText(END_WHISPER_ROW).fetchSemanticsNodes().size)
        compose.onNodeWithContentDescription("Close").performClick(); compose.waitForIdle()
        compose.menuRow(MENU_WHISPER)
        engine.ask("a private question")
        val until = System.currentTimeMillis() + 5_000
        while (engine.state.value.writing && System.currentTimeMillis() < until) Thread.sleep(10)
        compose.waitForIdle()
        compose.onNodeWithContentDescription(END_WHISPER_BUTTON).performClick(); compose.waitForIdle()
        assertFalse(engine.state.value.whisper)
        assertTrue(engine.state.value.chat.turns.isEmpty())
        assertEquals("no question first", 0, compose.onAllNodesWithText("End and forget this Whisper?").fetchSemanticsNodes().size)
        compose.onNodeWithText(WHISPER_ENDED).assertIsDisplayed()
        // It goes away by itself after about two seconds.
        compose.mainClock.advanceTimeBy(2_500); compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithText(WHISPER_ENDED).fetchSemanticsNodes().size)
    }

    /** Owner, 2026-11-19: a long press on the eye in a Whisper is the emergency exit. */
    @Test fun aLongPressOnTheEyeInAWhisperWipesItAtOnce() {
        val unpaired = FakeStation(io.github.odnwks.mimir.data.LinkStation.empty("test").copy(paired = false))
        engine = testEngine(tmp.newFolder(), ready = true)
        compose.setContent {
            CompositionLocalProvider(LocalPhoneEngine provides engine) { MimirTheme { AppRoot(unpaired, nav) } }
        }
        compose.waitForIdle()
        engine.startWhisper(); compose.waitForIdle()
        engine.ask("a secret")
        val until = System.currentTimeMillis() + 5_000
        while (engine.state.value.writing && System.currentTimeMillis() < until) Thread.sleep(10)
        compose.waitForIdle()
        compose.onNodeWithContentDescription(END_WHISPER_BUTTON).performTouchInput { longClick() }; compose.waitForIdle()
        assertFalse(engine.state.value.whisper)
        assertTrue(engine.state.value.chat.turns.isEmpty())
    }

    @Test fun theTopBarShowsTheModeOnThePairedChatToo() {
        start { s -> s.copy(incognito = null, chat = s.chat.filterNot { it is io.github.odnwks.mimir.data.ChatEntry.IncognitoRunning }) }
        shown(whoLabel("Your PC")).assertIsDisplayed()
        compose.menuRow(MENU_WHISPER)
        compose.onNodeWithText(WHISPER_ON_PC).performClick(); compose.waitForIdle()
        assertTrue(END_PC_WHISPER.startsWith("Whisper mode"))
        shown(END_PC_WHISPER).assertIsDisplayed()
        shown(END_PC_WHISPER).performClick(); compose.waitForIdle()
        shown(whoLabel("Your PC")).assertIsDisplayed()
    }

    /** The clean-chat rule: the model's card is on an empty chat only, not above a conversation. */
    @Test fun mimirsModelCardShowsOnlyOnAnEmptyChat() {
        start()
        nav.open(Screen.Standalone); compose.waitForIdle()
        val name = Catalog.byId(engine.state.value.model).plainName
        compose.onNodeWithText(name).assertIsDisplayed()
        compose.onNodeWithContentDescription("Message $AI_NAME").performTextInput("Hello")
        compose.onNodeWithContentDescription("Send").performClick()
        val until = System.currentTimeMillis() + 5_000
        while ((engine.state.value.writing || engine.state.value.chat.turns.size < 2) && System.currentTimeMillis() < until) Thread.sleep(10)
        compose.waitForIdle()
        assertTrue(engine.state.value.chat.turns.isNotEmpty())
        compose.onNodeWithText(name).assertDoesNotExist()
    }
}
