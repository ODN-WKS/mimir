package io.github.odnwks.mimir.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import io.github.odnwks.mimir.data.ExampleData
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.data.Heard
import io.github.odnwks.mimir.data.LinkStation
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.nav.Tab
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

/** The side menu (redesign R3): its order, Updates always there, Projects below Chats, folds remembered, every place reachable. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class SideMenuTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val nav = Navigator()
    private lateinit var engine: Engine

    private fun start(station: FakeStation = FakeStation(ExampleData.awake()), withChats: Boolean = true) {
        engine = testEngine(
            tmp.newFolder(), ready = true,
            turns = if (withChats) listOf(io.github.odnwks.mimir.standalone.Turn(true, "Give me three easy dinner ideas"), io.github.odnwks.mimir.standalone.Turn(false, "Pasta.")) else emptyList(),
        )
        if (withChats) { engine.newChat() }
        compose.setContent { CompositionLocalProvider(LocalPhoneEngine provides engine) { MimirTheme(reduceMotion = true) { AppRoot(station, nav) } } }
        compose.waitForIdle()
        // A fresh run: folds back to their defaults.
        androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>().getSharedPreferences(MenuFold.PREFS, 0).edit().clear().commit()
    }

    private fun top(text: String) = compose.onAllNodesWithText(text).onLast().getUnclippedBoundsInRoot().top

    @Test fun theMenuListsEverythingInTheOrderOfTheMockup() {
        start()
        compose.openMenu()
        val order = listOf(MENU_SEARCH, MENU_NEW_CHAT, MENU_NEEDS_YOU, MENU_UPDATES, MENU_PC, MENU_WHISPER, MENU_CHATS.uppercase(), MENU_PROJECTS.uppercase(), MENU_SETTINGS)
        val tops = order.map { w ->
            if (w == MENU_SEARCH) compose.onNodeWithContentDescription(MENU_SEARCH, substring = true).getUnclippedBoundsInRoot().top else top(w)
        }
        assertEquals("order of $order is $tops", tops.sorted(), tops)
        // Settings is pinned at the bottom of the menu.
        assertTrue(top(MENU_SETTINGS) > 700.dp)
        // No logo, no app name.
        compose.onNodeWithText(AI_NAME).assertDoesNotExist()
    }

    @Test fun updatesIsAlwaysThereEvenWithNoneAndUnpaired() {
        start(FakeStation(ExampleData.awake().let { it.copy(updates = emptyList()) }))
        compose.openMenu()
        compose.onNodeWithText(MENU_UPDATES).assertIsDisplayed()
        compose.onNodeWithText(MENU_UPDATES).performClick(); compose.waitForIdle()
        assertEquals(Tab.Updates, nav.selectedTab)
    }

    @Test fun unpairedThereIsNoProjectsSectionAndYourPcOpensPairing() {
        start(FakeStation(LinkStation.empty("test").copy(paired = false)))
        compose.openMenu()
        compose.onNodeWithText(MENU_UPDATES).assertIsDisplayed()
        compose.onNodeWithText(MENU_PROJECTS.uppercase()).assertDoesNotExist()
        compose.onNodeWithText("not connected").assertIsDisplayed()
        compose.onNodeWithText(MENU_PC).performClick(); compose.waitForIdle()
        assertEquals(Screen.Pair, nav.current)
    }

    @Test fun projectsSitBelowChatsFoldedAtFirstWithTheirCountAndOpenOnATap() {
        val st = FakeStation(ExampleData.awake().let { s -> s.copy(projects = s.projects.mapIndexed { i, p -> if (i == 0) p.copy(needsYou = 1) else p }) })
        start(st)
        compose.openMenu()
        assertTrue(top(MENU_PROJECTS.uppercase()) > top(MENU_CHATS.uppercase()))
        val name = st.state.value.projects.first().name
        // Folded: the heading says how many, and that one needs you.
        compose.onNodeWithText("${st.state.value.projects.size} · 1 needs you").assertIsDisplayed()
        compose.onNodeWithText(name).assertDoesNotExist()
        compose.onNodeWithText(MENU_PROJECTS.uppercase()).performClick(); compose.waitForIdle()
        compose.onNodeWithText(name).assertIsDisplayed()
        compose.onNodeWithText("needs you").assertIsDisplayed()
        compose.onNodeWithText(name).performClick(); compose.waitForIdle()
        assertEquals(Screen.Project(name), nav.current)
    }

    @Test fun chatsFoldAndTheFoldIsRemembered() {
        start()
        compose.openMenu()
        compose.onNodeWithText("Three easy dinner ideas").assertIsDisplayed()
        compose.onNodeWithText(MENU_CHATS.uppercase()).performClick(); compose.waitForIdle()
        compose.onNodeWithText("Three easy dinner ideas").assertDoesNotExist()
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val again = MenuFold(ctx.getSharedPreferences(MenuFold.PREFS, 0))
        assertFalse("Chats stays folded next time", again.chatsOpen)
        assertFalse("Projects starts folded", again.projectsOpen)
        again.toggleProjects()
        assertTrue(MenuFold(ctx.getSharedPreferences(MenuFold.PREFS, 0)).projectsOpen)
    }

    @Test fun countsAreOnTheMenuButtonAndTheRows() {
        val st = FakeStation(ExampleData.awake())
        start(st)
        val needs = st.state.value.needsYou.size
        val unheard = st.state.value.updates.count { it.heard == Heard.New }
        assertEquals(needs + unheard, menuCount(st.state.value))
        compose.onNodeWithContentDescription("$MENU_BUTTON, ${needs + unheard} waiting").assertIsDisplayed()
    }

    @Test fun everyPlaceIsReachableFromTheMenu() {
        start(FakeStation(ExampleData.awake().copy(pcTalks = true)))
        compose.menuRow(MENU_NEEDS_YOU); assertEquals(Screen.NeedsYou, nav.current)
        nav.back(); compose.waitForIdle()
        compose.menuRow(MENU_UPDATES); assertEquals(Tab.Updates, nav.selectedTab)
        nav.back(); compose.waitForIdle()
        compose.menuRow(MENU_PC); assertEquals(Tab.Status, nav.selectedTab)
        nav.back(); compose.waitForIdle()
        compose.menuRow(MENU_SETTINGS); assertEquals(Screen.Settings, nav.current)
        nav.back(); compose.waitForIdle()
        // Whisper starts one, New chat starts a chat.
        compose.menuRow(MENU_WHISPER)
        assertTrue(engine.state.value.whisper)
        compose.menuRow(MENU_NEW_CHAT)
        assertFalse(engine.state.value.whisper)
    }

    @Test fun whisperAndNewChatFromTheMenu() {
        start(FakeStation(ExampleData.awake().copy(pcTalks = true)))
        compose.menuRow(MENU_WHISPER)
        assertTrue(engine.state.value.whisper)
        compose.menuRow(MENU_NEW_CHAT)
        assertFalse(engine.state.value.whisper)
    }

    /** QA2 #31, #35: opening the list never opens the keyboard; a tap on the search box does. */
    @Test fun theSearchBoxTakesFocusOnlyWhenTapped() {
        start()
        compose.openMenu()
        compose.onNodeWithContentDescription(MENU_SEARCH, substring = true).assertIsNotFocused()
        compose.onNodeWithContentDescription(MENU_SEARCH, substring = true).performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription(MENU_SEARCH, substring = true).assertIsFocused()
    }

    @Test fun searchingFiltersTheChatsByTitle() {
        start()
        compose.openMenu()
        compose.onNodeWithContentDescription(MENU_SEARCH, substring = true).performTextInputCompat("dinner")
        compose.onNodeWithText("Three easy dinner ideas").assertIsDisplayed()
        compose.onNodeWithContentDescription(MENU_SEARCH, substring = true).performTextInputCompat("zzz")
        compose.onNodeWithText("No chat has that in its name.").assertIsDisplayed()
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteraction.performTextInputCompat(text: String) {
    this.performTextReplacement(text)
}
