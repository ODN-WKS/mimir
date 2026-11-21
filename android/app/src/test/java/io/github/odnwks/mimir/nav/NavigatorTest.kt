package io.github.odnwks.mimir.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigatorTest {
    @Test fun startsOnChatAndBackLeavesTheApp() {
        val nav = Navigator()
        assertEquals(Screen.Chat, nav.current)
        assertEquals(Tab.Chat, nav.selectedTab)
        assertFalse(nav.canGoBack)
        assertFalse(nav.back())
    }

    @Test fun openPushesAndBackPops() {
        val nav = Navigator()
        nav.open(Screen.NeedsYou)
        nav.open(Screen.Task("t"))
        nav.open(Screen.Change("t"))
        assertEquals(listOf(Screen.Chat, Screen.NeedsYou, Screen.Task("t"), Screen.Change("t")), nav.stack.map { it.screen })
        assertEquals(Move.Forward, nav.lastMove)
        assertTrue(nav.back())
        assertEquals(Screen.Task("t"), nav.current)
        assertEquals(Move.Back, nav.lastMove)
    }

    @Test fun openingTheSameScreenTwiceDoesNothing() {
        val nav = Navigator()
        nav.open(Screen.NeedsYou)
        nav.open(Screen.NeedsYou)
        assertEquals(2, nav.stack.size)
    }

    @Test fun everyEntryHasItsOwnKey() {
        val nav = Navigator()
        nav.open(Screen.Task("a"))
        nav.open(Screen.Change("a"))
        nav.open(Screen.Task("a"))
        assertEquals(nav.stack.size, nav.stack.map { it.key }.toSet().size)
    }

    @Test fun tabsStartTheirOwnStackAndSlideTheWayYouWent() {
        val nav = Navigator()
        nav.open(Screen.NeedsYou)
        nav.selectTab(Tab.Status)
        assertEquals(listOf(Screen.Status), nav.stack.map { it.screen })
        assertEquals(Move.ToTab(toRight = true), nav.lastMove)
        nav.selectTab(Tab.Chat)
        assertEquals(Move.ToTab(toRight = false), nav.lastMove)
        assertEquals(Tab.Chat, nav.selectedTab)
    }

    @Test fun tappingTheShownTabGoesBackToItsFirstScreen() {
        val nav = Navigator()
        nav.selectTab(Tab.Status)
        nav.open(Screen.Project("Budget-App"))
        nav.open(Screen.Task("t"))
        nav.selectTab(Tab.Status)
        assertEquals(listOf(Screen.Status), nav.stack.map { it.screen })
    }

    @Test fun backFromAnotherSectionGoesToChatFirst() {
        val nav = Navigator()
        nav.selectTab(Tab.Updates)
        assertTrue(nav.canGoBack)
        assertTrue(nav.back())
        assertEquals(Screen.Chat, nav.current)
        assertFalse(nav.back())
    }

    @Test fun innerScreensKeepTheirSectionHighlightedButNeedsYouHighlightsNone() {
        val nav = Navigator()
        nav.selectTab(Tab.Status)
        nav.open(Screen.Settings)
        nav.open(Screen.Models)
        assertEquals(Tab.Status, nav.selectedTab)
        nav.open(Screen.NeedsYou)
        assertNull(nav.selectedTab)
    }

    @Test fun aSheetClosesBeforeTheScreen() {
        val nav = Navigator()
        nav.open(Screen.Task("t"))
        nav.openSheet(Sheet.TaskOptions("t"))
        assertTrue(nav.back())
        assertNull(nav.sheet)
        assertEquals(Screen.Task("t"), nav.current)
    }

    @Test fun openingAScreenClosesTheSheet() {
        val nav = Navigator()
        nav.open(Screen.Task("t"))
        nav.openSheet(Sheet.TaskOptions("t"))
        nav.open(Screen.Change("t"))
        assertNull(nav.sheet)
        assertEquals(Screen.Change("t"), nav.current)
    }

    @Test fun aTappedCardGrowsFromTheTapPoint() {
        val nav = Navigator()
        nav.open(Screen.Task("t"), TapPoint(0.25f, 1.4f))
        assertEquals(Move.CardOpen(0.25f, 1f), nav.lastMove)
    }

    @Test fun pairingFlow() {
        val nav = Navigator()
        nav.open(Screen.Settings)
        nav.open(Screen.PhonePc)
        nav.open(Screen.Pair)
        nav.open(Screen.PairDone)
        nav.replace(Screen.PairError)
        assertEquals(listOf(Screen.Chat, Screen.Settings, Screen.PhonePc, Screen.Pair, Screen.PairError), nav.stack.map { it.screen })
        nav.replace(Screen.Pair)
        nav.open(Screen.PairDone)
        nav.resetTo(Screen.Chat)
        assertEquals(listOf(Screen.Chat), nav.stack.map { it.screen })
    }

    @Test fun theBottomBarHidesWhereTheBoardsHaveNone() {
        val withBar = listOf(Screen.Chat, Screen.Status, Screen.Settings, Screen.NeedsYou, Screen.Project("p"),
            Screen.Models, Screen.PhonePc, Screen.Notifications)
        val without = listOf(Screen.Task("t"), Screen.Change("t"), Screen.IncognitoTask,
            Screen.Pair, Screen.PairDone, Screen.PairError)
        assertTrue(withBar.all { it.bottomBar })
        assertTrue(without.none { it.bottomBar })
    }
}
