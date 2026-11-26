package io.github.odnwks.mimir.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.nav.Move
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.nav.Tab
import io.github.odnwks.mimir.ui.theme.MimirTheme
import io.github.odnwks.mimir.ui.theme.Motion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Taps through the shell on Robolectric, the way the owner would. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class AppFlowTest {
    @get:Rule val compose = createComposeRule()
    private val station = FakeStation()
    private val nav = Navigator()

    private val voice = FakeReadAloud()

    private fun start(reduceMotion: Boolean = false) = compose.setContent {
        androidx.compose.runtime.CompositionLocalProvider(LocalReadAloud provides voice) {
            MimirTheme(reduceMotion = reduceMotion) { AppRoot(station, nav) }
        }
    }

    private fun heading(text: String) =
        compose.onNode(hasText(text) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))

    @Test fun answerAPermissionFromNeedsYou() {
        start()
        compose.onNodeWithText("4 things need you").performClick()
        heading("Needs you").assertIsDisplayed()
        compose.onNodeWithText("Edit remote.rs (2 lines) so Allow reaches Mímir faster. Mímir is paused until you answer.").assertIsDisplayed()
        compose.onAllNodesWithText("Say something else…")[0].performClick()
        // The task opens (its top keeps no name since the owner's 2026-11-25 layout).
        compose.waitForIdle()
        assertEquals(Screen.Task("t-allow"), nav.current)
        compose.onNodeWithText("Allow this edit").performClick()
        compose.waitForIdle()
        assertEquals(3, station.state.value.needsYou.size)
        // The answer leaves a receipt in the dock's place for a few seconds.
        compose.onNodeWithText("You allowed this edit", useUnmergedTree = true).assertIsDisplayed()
        compose.mainClock.advanceTimeBy(5000)
        compose.waitForIdle()
        compose.onNodeWithText("You allowed this edit", useUnmergedTree = true).assertDoesNotExist()
        // No Back button on a task's top now: the phone's own back goes back to Needs you.
        nav.back(); compose.waitForIdle()
        heading("Needs you").assertIsDisplayed()
    }

    @Test fun theSideMenuSwitchesSections() {
        start()
        compose.menuRow(MENU_PC)
        heading("Your PC").assertIsDisplayed()
        compose.menuRow(MENU_UPDATES)
        compose.waitForIdle()
        heading("Updates").assertIsDisplayed()
        assertEquals(Tab.Updates, nav.selectedTab)
        // Settings is the menu's last row.
        compose.openSettings()
        heading("Settings").assertIsDisplayed()
    }

    @Test fun sendingAMessageFilesIt() {
        start()
        compose.onNodeWithContentDescription("Message Mímir").performTextInput("Make the gauges bigger")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.onNodeWithText("Make the gauges bigger").assertIsDisplayed()
        // The chat keeps no standing line about the PC while it's online.
        compose.onNodeWithText("PC online · 4 working").assertDoesNotExist()
    }

    @Test fun stopAskFirstAndTheSafeChoiceKeepsGoing() {
        start()
        nav.open(Screen.Task("t-allow"))
        compose.onNodeWithContentDescription("Task options").performClick()
        compose.onNodeWithText("Stop this task…").performClick()
        compose.onNodeWithText("Keep going").performClick()
        compose.waitForIdle()
        assertNull(nav.sheet)
        assertEquals(false, station.state.value.task("t-allow")!!.stopped)
    }

    @Test fun startAnIncognitoTaskAndEndIt() {
        station.endIncognito()
        start()
        // ⋯ › To: › More options › Whisper mode, then the message starts it (P12: "To:" is in the three dots).
        compose.onNodeWithContentDescription(CHAT_MENU).performClick()
        compose.onNodeWithText("To: Auto").performClick()
        compose.onNodeWithText("More options").performClick()
        compose.onNodeWithText("Whisper mode").performClick()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithContentDescription("Message Mímir").performTextInput("Read the tax letter")
        compose.onNodeWithContentDescription("Send").performClick()
        heading("Read the tax letter").assertIsDisplayed()
        compose.onNodeWithText("End and forget…").performClick()
        compose.onNodeWithText("End and forget").performClick()
        compose.waitForIdle()
        assertNull(station.state.value.incognito)
        assertEquals(Screen.Chat, nav.current)
    }

    @Test fun pairing() {
        start()
        nav.open(Screen.Settings)
        nav.open(Screen.PhonePc)
        // Pairing starts at the PC: this phone offers no way to pair another.
        compose.onNodeWithText("Pair another phone").assertDoesNotExist()
        compose.onNodeWithText("New phones are paired from Mímir on the PC.").assertIsDisplayed()
        assertEquals(Screen.PhonePc, nav.current)
        nav.open(Screen.Pair)
        compose.onNodeWithText(CONNECT_TYPE_INSTEAD).performClick()
        compose.onNodeWithContentDescription("Pairing code").performTextInput("mimir1:pc:code:PC")
        compose.onNodeWithText("Pair").performClick()
        heading(ALMOST_THERE).assertIsDisplayed()
        station.state.value.pairWords.forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        // The PC said no: the phone says why and offers a new try.
        station.pairEnded(io.github.odnwks.mimir.data.PairProgress.Failed("The PC said the words don't match."))
        compose.waitForIdle()
        heading("Couldn't pair").assertIsDisplayed()
        compose.onNodeWithText("The PC said the words don't match.", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Scan the new code").performClick()
        heading(CONNECT_TITLE).assertIsDisplayed()
    }

    @Test fun removeAnimationsSwapsScreensAtOnce() {
        assertTrue(Motion.spec<Float>(Motion.SCREEN_MS, reduce = true) is SnapSpec)
        val t = Motion.spec<Float>(Motion.GAUGE_MS, reduce = false) as TweenSpec
        assertEquals(600, t.durationMillis)
        val none = transitionFor(Move.Forward, reduce = true)
        assertEquals(EnterTransition.None, none.targetContentEnter)
        assertEquals(ExitTransition.None, none.initialContentExit)
        // And the app still works with it on.
        start(reduceMotion = true)
        compose.onNodeWithText("4 things need you").performClick()
        heading("Needs you").assertIsDisplayed()
    }

    @Test fun theBoardTimings() {
        assertEquals(300, Motion.SCREEN_MS)
        assertEquals(280, Motion.CARD_OPEN_MS)
        assertEquals(600, Motion.GAUGE_MS)
        assertEquals(220, Motion.NEW_ITEM_MS)
        assertEquals(200, Motion.NEEDS_YOU_BAR_MS)
    }

    @Test fun theProjectPickerOpensAndPicksAProject() {
        start()
        nav.openSheet(io.github.odnwks.mimir.nav.Sheet.WhereTo)
        compose.waitForIdle()
        compose.onNodeWithText("More projects, or a new one…").performClick()
        compose.waitForIdle()
        heading("Project").assertIsDisplayed()
        val other = station.state.value.pickable.last()
        compose.onNodeWithText(other).performClick()
        compose.waitForIdle()
        // Back in "Where should this go?", with that project chosen.
        assertEquals(io.github.odnwks.mimir.nav.Sheet.WhereTo, nav.sheet)
        assertEquals(other, nav.composeTo.project)
    }

    @Test fun aNewProjectIsMadeOnThePcAndPicked() {
        start()
        nav.openSheet(io.github.odnwks.mimir.nav.Sheet.WhereTo)
        compose.waitForIdle()
        compose.onNodeWithText("More projects, or a new one…").performClick()
        compose.onNodeWithText("New project…").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("New project name").performTextInput("Garden Planner")
        compose.onNodeWithText("Make the project").performClick()
        compose.waitForIdle()
        assertTrue("Garden Planner" in station.state.value.pickable)
        assertEquals("Garden Planner", nav.composeTo.project)
        compose.onNodeWithText("Made the project Garden Planner on your PC").assertIsDisplayed()
    }

    @Test fun notPairedThePickerOffersPairingInsteadOfAPopUp() {
        val unpaired = FakeStation(station.state.value.copy(paired = false))
        compose.setContent { MimirTheme { AppRoot(unpaired, nav) } }
        nav.openSheet(io.github.odnwks.mimir.nav.Sheet.ProjectPicker(""))
        compose.waitForIdle()
        compose.onNodeWithText("Pair with your PC to use its projects. New projects are made as folders on the PC.").assertIsDisplayed()
        compose.onNodeWithText("Pair with your PC").performClick()
        compose.waitForIdle()
        assertTrue(nav.current == Screen.Pair)
    }

    @get:Rule val tmp = org.junit.rules.TemporaryFolder()

    private fun firstRun(engine: io.github.odnwks.mimir.standalone.Engine?, standaloneOk: Boolean = true) {
        val unpaired = FakeStation(io.github.odnwks.mimir.data.LinkStation.empty("test").copy(paired = false))
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalPhoneEngine provides engine, LocalReadAloud provides voice) {
                MimirTheme { AppRoot(unpaired, nav, standaloneOk = standaloneOk) }
            }
        }
        compose.waitForIdle()
    }

    @Test fun firstOpenIsAChatWithMimirAndThePcTabsSayWhatTheyAreFor() {
        firstRun(testEngine(tmp.newFolder(), ready = false))
        compose.onNodeWithText("Hi, I'm Mímir.").assertIsDisplayed()
        compose.onNodeWithText("Try one:").assertDoesNotExist()
        // No two-choice welcome; nothing hidden: the tabs are there, and each PC tab explains itself.
        for (gone in listOf("Pair with my PC", "Use a model on this phone", "Needs you")) {
            compose.onNodeWithText(gone, substring = true).assertDoesNotExist()
        }
        nav.selectTab(Tab.Status)
        compose.waitForIdle()
        assertTrue(nav.current == Screen.Status)
        compose.onNodeWithText(YOUR_PC_STEPS_TITLE).assertIsDisplayed()
        compose.onNodeWithText("Connect your PC").performClick()
        compose.waitForIdle()
        assertTrue(nav.current == Screen.Pair)
    }

    @Test fun theTopBarShowsNoNameUntilTheChatHasATopic() {
        val engine = testEngine(tmp.newFolder(), ready = true)
        firstRun(engine)
        // A new chat: no app name or title at the top, only the icons; the privacy line once.
        heading(AI_NAME).assertDoesNotExist()
        heading("Chat").assertDoesNotExist()
        compose.onNodeWithContentDescription(MENU_BUTTON, substring = true).assertIsDisplayed()
        // The old banner is gone: the first hello says it is private, and only until something is sent.
        compose.onNodeWithText(PRIVACY_LINE).assertDoesNotExist()
        compose.onNodeWithText("Your chats stay on this phone.", substring = true).assertIsDisplayed()
        // The first message gives the chat its topic; a tap on it opens its sheet: the note, and Rename.
        compose.onNodeWithContentDescription("Message $AI_NAME").performTextInput("Give me three easy dinner ideas")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.waitForIdle()
        compose.openChatOptions()
        compose.waitForIdle()
        compose.onNodeWithText(CHAT_COPY).assertIsDisplayed()
        compose.onNodeWithText(CHAT_RENAME).performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Chat name").performTextReplacement("Weeknight dinners")
        compose.onNodeWithText("Done").performClick()
        compose.waitForIdle()
        // The topic is not in the top bar any more; the side menu's chat list has it.
        compose.openMenu()
        compose.onNodeWithText("Weeknight dinners").assertIsDisplayed()
        assertEquals("Weeknight dinners", engine.state.value.chat.name)
    }

    private fun twoChats(): Pair<java.io.File, io.github.odnwks.mimir.standalone.Engine> {
        val dir = tmp.newFolder()
        val engine = testEngine(dir, ready = true, turns = listOf(io.github.odnwks.mimir.standalone.Turn(true, "Give me three easy dinner ideas"), io.github.odnwks.mimir.standalone.Turn(false, "Pasta.")))
        engine.newChat()
        engine.ask("Explain how rainbows form, simply")
        val until = System.currentTimeMillis() + 5_000
        while (engine.state.value.writing && System.currentTimeMillis() < until) Thread.sleep(10)
        engine.newChat()
        return dir to engine
    }

    private fun savedChats(dir: java.io.File) =
        io.github.odnwks.mimir.standalone.Store(java.io.File(dir, "standalone.bin"), PlainCodec).load().chats.map { it.title }

    private val question = UNDO_LINE

    @Test fun swipingAChatOnlyUncoversDeleteAndNothingIsDeletedWithoutAsking() {
        val (dir, engine) = twoChats()
        firstRun(engine)
        // The empty home is only a greeting now: the chats are listed in Chats.
        nav.open(Screen.Chats)
        compose.waitForIdle()
        // A full swipe left: the chat stays, a red Delete button shows beside it.
        compose.onNodeWithText("Three easy dinner ideas").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        // The row's name stays in view beside the Delete button.
        compose.onNodeWithText("Three easy dinner ideas").assertIsDisplayed()
        assertTrue(compose.onNodeWithText("Three easy dinner ideas").fetchSemanticsNode().boundsInRoot.left >= 0f)
        assertTrue("Three easy dinner ideas" in savedChats(dir))
        // Its Delete asks first; Cancel keeps it.
        compose.onNodeWithContentDescription("Delete Three easy dinner ideas").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Delete \u201cThree easy dinner ideas\u201d?").assertIsDisplayed()
        compose.onNodeWithText(question).assertIsDisplayed()
        // Undo follows a delete, so the question never says it can't be undone.
        compose.onNodeWithText("can't be undone", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(question).assertDoesNotExist()
        compose.onNodeWithText("Three easy dinner ideas").assertExists()
        assertEquals(2, engine.state.value.earlier.size)
    }

    @Test fun deleteThenUndoBringsItBackAndOnlyAfterUndoPassesIsItGoneFromStorage() {
        val (dir, engine) = twoChats()
        firstRun(engine)
        // The empty home is only a greeting now: the chats are listed in Chats.
        nav.open(Screen.Chats)
        compose.waitForIdle()
        compose.onNodeWithText("Three easy dinner ideas").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnLongClick)
        compose.waitForIdle()
        compose.onNodeWithText("Delete").performClick()
        compose.waitForIdle()
        compose.onNode(hasText("Delete") and androidx.compose.ui.test.hasClickAction() and androidx.compose.ui.test.hasContentDescription("Delete Three easy dinner ideas")).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Three easy dinner ideas").assertDoesNotExist()
        compose.onNodeWithText("Chat deleted").assertIsDisplayed()
        // Still in storage while Undo is offered.
        assertTrue("Three easy dinner ideas" in savedChats(dir))
        compose.onNodeWithText("Undo").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Three easy dinner ideas").assertExists()
        // Deleted again, and Undo passes: now it's gone from the phone.
        engine.deleteChat(engine.state.value.earlier.first { it.title == "Three easy dinner ideas" }.id)
        engine.forgetDeleted()
        assertTrue("Three easy dinner ideas" !in savedChats(dir))
    }

    @Test fun theOpenChatsMenuAsksBeforeDeletingIt() {
        val (dir, engine) = twoChats()
        engine.open(engine.state.value.earlier.first().id)
        firstRun(engine)
        compose.openChatOptions()
        compose.waitForIdle()
        compose.onNodeWithText(CHAT_DELETE).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText(question).assertIsDisplayed()
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Delete", substring = true) and androidx.compose.ui.test.hasClickAction() and hasText("Delete")).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Chat deleted").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(UNDO_MS + 1000)
        compose.waitForIdle()
        assertEquals(1, savedChats(dir).count { it != "New chat" })
    }

    @Test fun storageDeletesAllChatsAfterOneQuestionSayingHowMany() {
        val (dir, engine) = twoChats()
        firstRun(engine)
        nav.open(Screen.Settings); nav.open(Screen.Storage)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Delete all chats").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Delete all 2 chats from this phone? This can't be undone.").assertIsDisplayed()
        compose.onNodeWithText("Yes, delete 2 chats").performClick()
        compose.waitForIdle()
        assertEquals(0, savedChats(dir).count { it != "New chat" })
        compose.onNodeWithText("0 chats").assertIsDisplayed()
    }

    @Test fun mimirsReplyIsReadAloudByThePhonesVoiceAndASecondTapStops() {
        firstRun(testEngine(tmp.newFolder(), ready = true, turns = listOf(io.github.odnwks.mimir.standalone.Turn(true, "Give me three easy dinner ideas"), io.github.odnwks.mimir.standalone.Turn(false, "Pasta with tomatoes."))))
        compose.holdAnswerAndPick("Pasta with tomatoes.", LISTEN_ITEM)
        assertEquals(listOf("Pasta with tomatoes."), voice.read)
        compose.onNodeWithContentDescription("Stop reading").performClick()
        compose.waitForIdle()
        assertEquals(null, voice.reading.value)
        // A long press on the reply has it too.
        compose.onNodeWithText("Pasta with tomatoes.").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnLongClick)
        compose.waitForIdle()
        compose.onAllNodesWithText(LISTEN_ITEM).onLast().performClick()
        compose.waitForIdle()
        assertEquals(2, voice.read.size)
    }

    @Test fun someoneLookingForReadAloudFindsItInSettingsVoiceWithNoPc() {
        firstRun(testEngine(tmp.newFolder(), ready = true))
        compose.openSettings()
        compose.waitForIdle()
        compose.onNodeWithText("Voice").performScrollTo().performClick()
        compose.waitForIdle()
        // Ingrid, built in, reads from the first launch.
        compose.onNodeWithText("read by Ingrid, on this phone", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Read-aloud speed", substring = true)).assertExists()
    }

    @Test fun settingsVoiceOffersIngridBuiltInAndThePhonesOwnVoice() {
        val asked = ArrayList<String>()
        val voices = exampleVoices(asked = asked)
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalReadAloud provides voice, LocalVoices provides voices) {
                MimirTheme { AppRoot(station, nav) }
            }
        }
        nav.open(Screen.Settings)
        nav.open(Screen.Voice)
        compose.waitForIdle()
        // Ingrid reads from the first launch: nothing to download.
        compose.onNodeWithText("Ingrid").assertExists()
        compose.onNodeWithText("Download", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Einar", substring = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Play a sample of Ingrid").performScrollTo().performClick()
        nav.open(Screen.Voices); compose.waitForIdle()
        compose.onNodeWithContentDescription("Use the phone's own voice to read aloud").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf("sample alba", "choose null"), asked)
    }

    @Test fun withThePhonesOwnVoiceChosenIngridIsOneTapAway() {
        val asked = ArrayList<String>()
        val st = io.github.odnwks.mimir.audio.VoicesState(chosen = null)
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalReadAloud provides voice, LocalVoices provides exampleVoices(st, asked)) {
                MimirTheme { AppRoot(station, nav) }
            }
        }
        nav.open(Screen.Settings)
        nav.open(Screen.Voices)
        compose.waitForIdle()
        heading("Other voices").assertExists()
        compose.onNodeWithContentDescription("Use Ingrid to read aloud").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf("choose alba"), asked)
    }

    @Test fun withThePcAwayClaudesMessageIsReadByThePhone() {
        val away = FakeStation(io.github.odnwks.mimir.data.ExampleData.asleep())
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalReadAloud provides voice) { MimirTheme { AppRoot(away, nav) } }
        }
        nav.open(Screen.Task(io.github.odnwks.mimir.data.ExampleData.TASK_ALLOW))
        compose.waitForIdle()
        compose.holdAnswerAndPick("The fix cuts Allow", "Listen")
        compose.waitForIdle()
        assertEquals(1, voice.read.size)
    }

    @Test fun withThePcThereClaudesMessageIsReadByThePcsVoice() {
        start()
        nav.open(Screen.Task(io.github.odnwks.mimir.data.ExampleData.TASK_ALLOW))
        compose.waitForIdle()
        compose.holdAnswerAndPick("The fix cuts Allow", "Listen")
        compose.waitForIdle()
        assertTrue(voice.read.isEmpty())
        assertTrue(station.played.isNotEmpty())
    }

    @Test fun someoneLookingForAPrivateChatFindsWhisperOnTheChatScreen() {
        firstRun(testEngine(tmp.newFolder(), ready = true))
        // The side menu has a Whisper row that says what it is.
        compose.openMenu()
        compose.onAllNodesWithText(MENU_WHISPER).onLast().assertIsDisplayed()
        compose.onNodeWithText("a chat that's never kept").assertIsDisplayed()
    }

    @Test fun aWhisperRunsOnThePhoneIsNeverSavedAndIsForgottenWhenItEnds() {
        val dir = tmp.newFolder()
        val engine = testEngine(dir, ready = true)
        firstRun(engine)
        compose.menuRow(MENU_WHISPER)
        assertTrue(engine.state.value.whisper)
        compose.onNodeWithText(WHISPER_CARD_TITLE).assertIsDisplayed()
        compose.onNodeWithContentDescription("Whisper to $AI_NAME").performTextInput("A secret plan")
        compose.onNodeWithContentDescription("Send").performClick()
        val until = System.currentTimeMillis() + 5_000
        while ((engine.state.value.writing || engine.state.value.chat.turns.size < 2) && System.currentTimeMillis() < until) Thread.sleep(10)
        compose.waitForIdle()
        compose.onNodeWithText("A secret plan").assertIsDisplayed()
        // Nothing of it is in the phone's storage.
        assertTrue(!java.io.File(dir, "standalone.bin").exists() || !java.io.File(dir, "standalone.bin").readText().contains("A secret plan"))
        // The top bar again: one tap, and it's forgotten.
        compose.onNodeWithContentDescription(END_WHISPER_BUTTON).performClick()
        compose.waitForIdle()
        assertTrue(!engine.state.value.whisper)
        compose.onNodeWithText("A secret plan").assertDoesNotExist()
        assertTrue(engine.state.value.earlier.none { c -> c.turns.any { it.text == "A secret plan" } })
    }

    @Test fun withNoModelYetTheEyeSaysWhatWhisperNeedsAndOffersIt() {
        firstRun(testEngine(tmp.newFolder(), ready = false))
        compose.menuRow(MENU_WHISPER)
        compose.onNodeWithText("Whisper needs $AI_NAME on this phone").assertIsDisplayed()
        compose.onNodeWithText("Get $AI_NAME").performClick()
        compose.waitForIdle()
        assertTrue(nav.current == Screen.Models)
    }

    @Test fun sizeInGeneralScalesTheWholeAppAndIsKept() {
        start()
        nav.open(Screen.Settings); nav.open(Screen.General)
        compose.waitForIdle()
        heading("Size").assertIsDisplayed()
        compose.onNodeWithText("100%").assertIsDisplayed()
        compose.onNodeWithContentDescription("App size").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(0.5f) }
        compose.waitForIdle()
        compose.onNodeWithText("50%").assertIsDisplayed()
        compose.onNodeWithText("Below 80%, buttons get small and harder to tap.").assertIsDisplayed()
        val prefs = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSharedPreferences(io.github.odnwks.mimir.ui.theme.AppSize.PREFS, 0)
        assertEquals(0.5f, prefs.getFloat(io.github.odnwks.mimir.ui.theme.AppSize.SCALE, 1f), 0.001f)
        compose.onNodeWithContentDescription("App size").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(1.5f) }
        compose.waitForIdle()
        compose.onNodeWithText("150%").assertIsDisplayed()
        compose.onNodeWithText("Below 80%, buttons get small and harder to tap.").assertDoesNotExist()
        assertEquals(1.5f, prefs.getFloat(io.github.odnwks.mimir.ui.theme.AppSize.SCALE, 1f), 0.001f)
    }

    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun pinchingAChatMakesItsTextBiggerAndItIsKept() {
        firstRun(testEngine(tmp.newFolder(), ready = true, turns = listOf(io.github.odnwks.mimir.standalone.Turn(true, "Give me three easy dinner ideas"), io.github.odnwks.mimir.standalone.Turn(false, "Pasta with tomatoes."))))
        val before = compose.onNodeWithText("Pasta with tomatoes.").fetchSemanticsNode().size.height
        compose.onNodeWithText("Pasta with tomatoes.").performTouchInput {
            pinch(center - androidx.compose.ui.geometry.Offset(20f, 0f), center - androidx.compose.ui.geometry.Offset(200f, 0f), center + androidx.compose.ui.geometry.Offset(20f, 0f), center + androidx.compose.ui.geometry.Offset(200f, 0f))
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        val after = compose.onNodeWithText("Pasta with tomatoes.").fetchSemanticsNode().size.height
        val prefs = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSharedPreferences(io.github.odnwks.mimir.ui.theme.AppSize.PREFS, 0)
        val kept = prefs.getFloat(io.github.odnwks.mimir.ui.theme.AppSize.CHAT_TEXT, 1f)
        assertTrue("kept $kept", kept > 1.2f)
        assertTrue("text grew from $before to $after (kept $kept)", after > before)
    }

    @Test fun theFirstMessageWaitsForTheOneTimeDownloadAndIsAnsweredWhenItsReady() {
        val dir = tmp.newFolder()
        val engine = testEngine(dir, ready = false)
        firstRun(engine)
        compose.onNodeWithContentDescription("Message $AI_NAME").performTextInput("Explain how rainbows form, simply")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.waitForIdle()
        // The message shows, with one card to get the AI; nothing is answered yet.
        compose.onNodeWithText("Explain how rainbows form, simply").assertIsDisplayed()
        heading("Get Mímir (${io.github.odnwks.mimir.standalone.Catalog.default.sizeText})").assertIsDisplayed()
        compose.onNodeWithText("A one-time download of 2.7 GB, best on Wi-Fi. Your message is answered as soon as it's ready.").assertIsDisplayed()
        compose.onNodeWithText("Download (2.7 GB)").assertIsDisplayed()
        assertEquals(1, engine.state.value.chat.turns.size)
        // The download finishes: the waiting message is answered.
        modelArrives(dir)
        engine.answerIfWaiting()
        val until = System.currentTimeMillis() + 5_000
        while ((engine.state.value.writing || engine.state.value.chat.turns.size < 2) && System.currentTimeMillis() < until) Thread.sleep(10)
        compose.waitForIdle()
        compose.onNodeWithText("Rainbows form when sunlight bends.").assertIsDisplayed()
        heading("Get Mímir (${io.github.odnwks.mimir.standalone.Catalog.default.sizeText})").assertDoesNotExist()
    }

    @Test fun connectingAPcLivesInSettings() {
        firstRun(testEngine(tmp.newFolder(), ready = true))
        compose.openSettings()
        compose.waitForIdle()
        compose.onNodeWithText("Alerts").assertDoesNotExist()
        compose.onNodeWithText("Your PC").performClick()
        compose.waitForIdle()
        assertTrue(nav.current == Screen.Pair)
    }

    @Test fun nothingStandsUnderAChatAfterManyMessages() {
        // The clean-chat rule: no tip about a PC (or anything else) between the replies and the box.
        // Connect your PC lives in Settings › Privacy › Phones and on Status and Updates.
        val turns = (1..6).flatMap { listOf(io.github.odnwks.mimir.standalone.Turn(true, "Question $it"), io.github.odnwks.mimir.standalone.Turn(false, "Answer $it")) }
        firstRun(testEngine(tmp.newFolder(), ready = true, turns = turns))
        compose.onNodeWithText("Answer 6").assertIsDisplayed()
        compose.onNodeWithText("Have a PC?", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Connect it in Settings", substring = true).assertDoesNotExist()
    }

    @Test fun aPhoneThatCantRunAModelOffersThePcInstead() {
        firstRun(null, standaloneOk = false)
        compose.onNodeWithText("This phone can't run an AI by itself").assertIsDisplayed()
        compose.onNodeWithText("Connect your PC").performClick()
        compose.waitForIdle()
        assertTrue(nav.current == Screen.Pair)
    }

    @Test fun answersGivenWhileThePcIsAwayAreSaidToBeWaiting() {
        val away = FakeStation(station.state.value.copy(needsYou = emptyList(), answersWaiting = 2))
        compose.setContent { MimirTheme { AppRoot(away, nav) } }
        nav.open(Screen.NeedsYou)
        compose.waitForIdle()
        compose.onNodeWithText("2 answers waiting on this phone · sent when your PC is back").assertIsDisplayed()
        compose.onNodeWithText("Nothing needs you right now.").assertIsDisplayed()
    }

    @Test fun theTaskMenuOffersThePcsTaskActions() {
        start()
        val t = station.state.value.tasks.first()
        nav.open(Screen.Task(t.id))
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Task options").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Rename…").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Task name").performTextReplacement("Faster Allow")
        compose.onNodeWithText("Rename").performClick()
        compose.waitForIdle()
        assertEquals("Faster Allow", station.state.value.task(t.id)?.title)
    }

    /** QA F7: a conversation going on in a terminal or the Claude app has no reply box here. */
    @Test fun aTaskGoingOnElsewhereHasANoteInsteadOfTheReplyBox() {
        start()
        val id = io.github.odnwks.mimir.data.ExampleData.TASK_CELL
        nav.open(Screen.Task(id)); compose.waitForIdle()
        compose.onNodeWithContentDescription("Reply to this task").assertIsDisplayed()
        station.set { s -> s.copy(tasks = s.tasks.map { if (it.id == id) it.copy(liveElsewhere = true) else it }) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Reply to this task").assertDoesNotExist()
        compose.onNodeWithText(FOLLOWS_ALONG).assertIsDisplayed()
        compose.onNodeWithText("Open on claude.ai").assertIsDisplayed()
        // Quiet again: the box is back.
        station.set { s -> s.copy(tasks = s.tasks.map { if (it.id == id) it.copy(liveElsewhere = false) else it }) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Reply to this task").assertIsDisplayed()
    }

    /** N1: a problem card with one action: the button does what it says, then the card goes. */
    @Test fun aProblemCardsButtonDoesItsActionAndTheCardGoes() {
        var ran = 0
        station.set { s -> s.copy(chat = s.chat + io.github.odnwks.mimir.data.ChatEntry.Note("n-y", "That message can't be read aloud just now. Try again.", problem = true, action = io.github.odnwks.mimir.data.NoteAction("Do it again") { ran++ })) }
        start()
        compose.onNodeWithText("That message can't be read aloud just now. Try again.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Dismiss").assertExists()
        compose.onNodeWithText("Do it again").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1, ran)
        assertTrue(station.state.value.chat.none { it.id == "n-y" })
    }

    /** QA B2: a "That didn't work" note can be dismissed, and goes. */
    @Test fun aProblemNoteHasOkAndGoes() {
        station.set { s -> s.copy(chat = s.chat + io.github.odnwks.mimir.data.ChatEntry.Note("n-x", "Your answer wasn't used.", problem = true)) }
        start()
        compose.onNodeWithText("Your answer wasn't used.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("OK").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Your answer wasn't used.").assertDoesNotExist()
        assertTrue(station.state.value.chat.none { it.id == "n-x" })
    }

    /** QA H2: with YOLO from the phone off on the PC, the sheet says so and where; it never fails silently. */
    @Test fun yoloWithThePcSwitchOffSaysSoPlainly() {
        val id = io.github.odnwks.mimir.data.ExampleData.TASK_CELL
        station.yoloRefusal = "YOLO from the phone is off. Switch on \"Allow YOLO from my phone\" in the PC's Settings › Phone first."
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalReadAloud provides voice, LocalUnlockConfirm provides { _, ok -> ok() }) {
                MimirTheme { AppRoot(station, nav) }
            }
        }
        nav.open(Screen.Task(id)); nav.openSheet(io.github.odnwks.mimir.nav.Sheet.Yolo(id)); compose.waitForIdle()
        compose.onNodeWithText("Unlock to go ahead without asking").performClick(); compose.waitForIdle()
        // Still open, saying why and where to change it; the task still asks first.
        compose.onNodeWithText(YOLO_OFF_ON_PC).assertIsDisplayed()
        compose.onNodeWithText("Try again").assertIsDisplayed()
        assertEquals(false, station.state.value.task(id)!!.yolo)
        // Allowed on the PC: Try again turns it on and the sheet closes.
        station.yoloRefusal = null
        compose.onNodeWithText("Try again").performClick(); compose.waitForIdle()
        assertEquals(true, station.state.value.task(id)!!.yolo)
        compose.onNodeWithText(YOLO_OFF_ON_PC).assertDoesNotExist()
    }

    @Test fun sendWithNoWordsDoesNothing() {
        start()
        val before = station.state.value
        // Empty, Send is dimmed and a tap on it does nothing (and never starts the mic).
        compose.onNodeWithContentDescription("Send").assertIsNotEnabled().performClick()
        compose.onNodeWithContentDescription("Message Mímir").performTextInput("   ")
        compose.onNodeWithContentDescription("Send").assertIsNotEnabled().performClick()
        compose.onNodeWithContentDescription("Stop listening").assertDoesNotExist()
        assertEquals(before.chat.size, station.state.value.chat.size)
        assertEquals(before.tasks.size, station.state.value.tasks.size)
    }

    // P12: "To:" moved from the box into the three dots (no room for it beside the model chip at text size 1.3): one tap more.
    @Test fun aTaskInAChosenProjectStartsInFourTapsAndTheUsualModelStays() {
        start()
        val usual = station.state.value.models.selected
        compose.onNodeWithContentDescription("Message Mímir").performTextInput("Make the call screen bigger")
        compose.onNodeWithContentDescription(CHAT_MENU).performClick()                            // 1
        compose.onNodeWithText("To: Auto").performClick()                                         // 2
        compose.onNodeWithText("Budget-App").performClick()                                    // 3
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithContentDescription("Send").performClick()                               // 4
        compose.waitForIdle()
        val t = station.state.value.tasks.last()
        assertEquals("Budget-App", t.project)
        assertEquals(Screen.Task(t.id), nav.current)
        // The choice was for that message: the chip is back on Auto, the usual model unchanged.
        assertTrue(nav.composeTo.auto)
        assertEquals(usual, station.state.value.models.selected)
    }

    @Test fun aSentMessageLeadsToItsTaskAndShowsItsState() {
        start()
        compose.onNodeWithContentDescription("Message Mímir").performTextInput("Tidy the release notes")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.waitForIdle()
        val task = station.state.value.tasks.last()
        // The line under the message names the state and opens the task.
        compose.onNode(hasContentDescription("New task in Mímir. Starting. Opens the task")).performClick()
        compose.waitForIdle()
        assertEquals(Screen.Task(task.id), nav.current)
    }

    @Test fun aStoppedTaskOffersContinue() {
        start()
        nav.open(Screen.Task(io.github.odnwks.mimir.data.ExampleData.TASK_ORDER))
        compose.waitForIdle()
        compose.onNodeWithText("Continue").performScrollTo().assertIsDisplayed()
    }

    @Test fun everyScreenSaysThePcIsAsleepInTheSameWords() {
        val asleep = FakeStation(io.github.odnwks.mimir.data.ExampleData.asleep())
        val line = asleep.state.value.connectionLine
        assertEquals("Your PC is out of reach", line)
        compose.setContent { MimirTheme { AppRoot(asleep, nav) } }
        for (go in listOf<Navigator.() -> Unit>(
            { selectTab(Tab.Chat) }, { selectTab(Tab.Status) }, { selectTab(Tab.Updates) },
            { open(Screen.Task(io.github.odnwks.mimir.data.ExampleData.TASK_ALLOW)) },
            { selectTab(Tab.Status); open(Screen.Project("Budget-App")) },
        )) {
            nav.go(); compose.waitForIdle()
            compose.onNodeWithText(line, useUnmergedTree = true).assertIsDisplayed()
        }
    }

    @Test fun aRecordingSpokenWhileThePcIsAwayWaitsInItsTask() {
        val asleep = FakeStation(io.github.odnwks.mimir.data.ExampleData.asleep())
        val id = io.github.odnwks.mimir.data.ExampleData.TASK_ALLOW
        asleep.keepRecording(id, ByteArray(44 + 32000 * 3), 3)
        compose.setContent { MimirTheme { AppRoot(asleep, nav) } }
        nav.open(Screen.Task(id)); compose.waitForIdle()
        compose.onNodeWithText("Recording, 3 s", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Waiting: sends when your PC is back", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun theAmberBarGrowsSoNothingAboveItJumps() {
        val all = station.state.value.needsYou
        station.set { it.copy(needsYou = emptyList()) }
        start()
        compose.waitForIdle()
        val floor = compose.onNodeWithContentDescription("Message Mímir").getUnclippedBoundsInRoot().top
        compose.mainClock.autoAdvance = false
        station.set { it.copy(needsYou = all) }
        compose.mainClock.advanceTimeBy(100)
        val bar = compose.onNodeWithText("4 things need you", useUnmergedTree = true)
        val mid = bar.getUnclippedBoundsInRoot().top
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        val end = bar.getUnclippedBoundsInRoot().top
        // Halfway through, the bar has only part of its height: the list above slides up with it.
        assertTrue("grew part of the way ($floor, $mid, $end)", mid < floor && mid > end)
    }

    @Test fun aReleaseWaitsTenSecondsAndCanBeUndone() {
        start()
        val id = io.github.odnwks.mimir.data.ExampleData.TASK_ALLOW
        nav.open(Screen.Task(id)); nav.openSheet(io.github.odnwks.mimir.nav.Sheet.Release(id))
        compose.waitForIdle()
        compose.onNodeWithText("Release 0.16").performClick()
        compose.waitForIdle()
        // Nothing is sent yet: Undo takes it back.
        assertTrue(station.state.value.needsYou.any { it.id == "q-release" })
        compose.onNodeWithContentDescription("Undo: Releasing 0.16").performClick()
        compose.mainClock.advanceTimeBy(12_000); compose.waitForIdle()
        assertTrue(station.state.value.needsYou.any { it.id == "q-release" })
        // Again, and this time the ten seconds run out: it's sent.
        nav.openSheet(io.github.odnwks.mimir.nav.Sheet.Release(id)); compose.waitForIdle()
        compose.onNodeWithText("Release 0.16").performClick()
        compose.mainClock.advanceTimeBy(11_000); compose.waitForIdle()
        assertTrue(station.state.value.needsYou.none { it.id == "q-release" })
    }

    @Test fun yoloShowsTheFullWarningThenNeedsThePhonesUnlock() {
        var asked: String? = null
        var unlocked = false
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                LocalReadAloud provides voice,
                LocalUnlockConfirm provides { title, ok -> asked = title; if (unlocked) ok() },
            ) { MimirTheme(reduceMotion = false) { AppRoot(station, nav) } }
        }
        val id = io.github.odnwks.mimir.data.ExampleData.TASK_ALLOW
        nav.open(Screen.Task(id)); nav.openSheet(io.github.odnwks.mimir.nav.Sheet.TaskOptions(id))
        compose.waitForIdle()
        compose.onNodeWithText("Go ahead without asking (this task)…").performScrollTo().performClick()
        compose.waitForIdle()
        // The whole warning, every time.
        compose.onNodeWithText(YOLO_WARNING).assertIsDisplayed()
        compose.onNodeWithText("Unlock to go ahead without asking").performClick()
        compose.waitForIdle()
        // The unlock was asked for and cancelled: nothing changed.
        assertEquals("Go ahead without asking", asked)
        assertEquals("Ask", station.state.value.task(id)!!.mode)
        unlocked = true
        nav.openSheet(io.github.odnwks.mimir.nav.Sheet.Yolo(id)); compose.waitForIdle()
        compose.onNodeWithText("Unlock to go ahead without asking").performClick()
        compose.waitForIdle()
        assertTrue(station.state.value.task(id)!!.yolo)
        // A red YOLO chip by the task's state.
        compose.onNodeWithText("Go ahead").assertIsDisplayed()
    }

    @Test fun whileATaskWorksStopIsAlwaysThereBesideSend() {
        start()
        val id = io.github.odnwks.mimir.data.ExampleData.TASK_CELL
        nav.open(Screen.Task(id)); compose.waitForIdle()
        // U1: words typed add Send beside Stop; Stop never goes away while it works.
        compose.onNodeWithContentDescription("Reply to this task").performTextInput("hold on")
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Send").assertIsDisplayed()
        compose.onNodeWithContentDescription("Stop").assertIsDisplayed()
        compose.onNodeWithContentDescription("Reply to this task").performTextReplacement("")
        compose.mainClock.advanceTimeBy(300); compose.waitForIdle()
        compose.onNodeWithContentDescription("Stop").performClick() // 0.09: the first tap asks "Stop?"
        compose.onNodeWithContentDescription("Stop").performClick()
        compose.mainClock.advanceTimeBy(1_000); compose.waitForIdle()
        assertEquals("Stopped", station.state.value.task(id)!!.stateWords)
        // "Stopped · Continue", and the command that was running may have been cut off.
        compose.onNodeWithText("Stopped ·").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("A command may have been cut off.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Continue").assertIsDisplayed()
    }

    @Test fun anArchivedTaskComesBackWithUndo() {
        start()
        val id = io.github.odnwks.mimir.data.ExampleData.TASK_CELL
        nav.open(Screen.Task(id)); nav.openSheet(io.github.odnwks.mimir.nav.Sheet.TaskOptions(id))
        compose.waitForIdle()
        compose.onNodeWithText("Archive").performClick()
        compose.waitForIdle()
        // Out of every list but All tasks › Archived.
        assertTrue(station.state.value.tasks.none { it.id == id })
        assertEquals(id, station.state.value.archived.single().id)
        compose.onNodeWithContentDescription("Undo: Archived Fix calls on cell signal").performClick()
        compose.waitForIdle()
        assertTrue(station.state.value.tasks.any { it.id == id })
    }

    @Test fun yourPcListsWhatIsWorkingAndOpensAllTasks() {
        start()
        nav.selectTab(Tab.Status); compose.waitForIdle()
        compose.onNodeWithText("Fix calls on cell signal").assertIsDisplayed()
        compose.onNodeWithText("All tasks").performClick()
        compose.waitForIdle()
        assertEquals(Screen.AllTasks(), nav.current)
    }

    @Test fun noBottomTabsAndNoSwipeBetweenScreens() {
        start()
        compose.waitForIdle()
        // A sideways swipe in the middle no longer changes the screen: the menu is how to move around.
        compose.onRoot().performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(Tab.Chat, nav.selectedTab)
        compose.onNodeWithContentDescription(MENU_BUTTON, substring = true).assertIsDisplayed()
        compose.menuRow(MENU_PC)
        assertEquals(Tab.Status, nav.selectedTab)
        // Every home screen has the menu button to go on from.
        compose.menuRow(MENU_UPDATES)
        assertEquals(Tab.Updates, nav.selectedTab)
    }

    @Test fun aTabletSaysTabletWhereAPhoneSaysPhone() {
        val unpaired = FakeStation(io.github.odnwks.mimir.data.LinkStation.empty("test").copy(paired = false))
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                LocalPhoneEngine provides null, LocalReadAloud provides voice,
                io.github.odnwks.mimir.ui.adaptive.LocalDevice provides io.github.odnwks.mimir.ui.adaptive.Device.Tablet,
            ) { MimirTheme { AppRoot(unpaired, nav, standaloneOk = false) } }
        }
        compose.waitForIdle()
        compose.onNodeWithText("This tablet can't run an AI by itself").assertIsDisplayed()
        compose.onNodeWithText("It needs a tablet from about 2021 on", substring = true).assertIsDisplayed()
        compose.onNodeWithText("This phone", substring = true).assertDoesNotExist()
    }
}
