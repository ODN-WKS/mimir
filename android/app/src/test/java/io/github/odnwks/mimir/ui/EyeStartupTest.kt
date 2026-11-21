package io.github.odnwks.mimir.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import io.github.odnwks.mimir.data.ExampleData
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.data.LinkStation
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
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

/** R15: the eye's startup animation: the frame table, the rules for when it plays, and the Settings switch. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class EyeStartupTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    // The clock is stepped by hand here, so the eye's layers are made in place (the app makes them off the main thread).
    @org.junit.Before fun inlineLayers() { StartupLayers.background = false }
    @org.junit.After fun backgroundLayers() { StartupLayers.background = true }

    private fun ctx() = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun prefs() = ctx().getSharedPreferences("phone_chat", 0)

    private fun open(hasChatted: Boolean, reduce: Boolean = false, nav: Navigator = Navigator()) {
        prefs().edit().putBoolean("has_chatted", hasChatted).commit()
        val engine = testEngine(tmp.newFolder(), ready = true)
        val station = FakeStation(LinkStation.empty("test").copy(paired = false))
        compose.setContent {
            CompositionLocalProvider(LocalPhoneEngine provides engine) { MimirTheme(reduceMotion = reduce) { AppRoot(station, nav) } }
        }
    }

    // ---- the frame table (the same numbers as render(t) in the mock-up)

    private fun near(want: Float, got: Float, what: String) = assertEquals(what, want, got, 0.002f)

    private fun check(t: Float, open: Float, sy: Float, closed: Float, scar: Float, glow: Float, globe: Float, gs: Float, text: Float, rise: Float) {
        val f = eyeFrame(t)
        near(open, f.openAlpha, "open at $t"); near(sy, f.openScaleY, "squash at $t"); near(closed, f.closedAlpha, "closed at $t")
        near(scar, f.scarAlpha, "scar at $t"); near(glow, f.scarGlow, "scar glow at $t"); near(globe, f.globeAlpha, "globe at $t")
        near(gs, f.globeScale, "globe scale at $t"); near(text, f.textAlpha, "text at $t"); near(rise, f.textRise, "rise at $t")
        near(text, f.bottomAlpha, "box at $t")
    }

    @Test fun theFrameTableMatchesTheMockUp() {
        check(0f, 0f, 1f, 0f, 0f, .55f, 0f, .6f, 0f, 10f)
        check(0.5f, 1f, 1f, 0f, 0f, .55f, 0f, .6f, 0f, 10f)
        check(0.9f, .88f, .4038f, .12f, 0f, .55f, 0f, .6f, 0f, 10f)
        check(1.06f, 0f, .08f, 1f, .5f, 1f, 0f, .6f, 0f, 10f)
        check(1.7f, 0f, .08f, 1f, 1f, .55f, 0f, .6f, 0f, 10f)
        check(2.2f, 0f, .08f, 1f, 1f, .55f, .7407f, .8963f, 0f, 10f)
        check(2.7f, 0f, .08f, 1f, 1f, .55f, 1f, 1f, 0f, 10f)
        check(3.3f, 0f, .08f, 1f, .5f, .55f, 0f, 1f, 0f, 10f)
        check(3.6f, 1f, .8176f, 0f, 0f, .55f, 0f, 1f, 0f, 10f)
        check(4.0f, 1f, 1f, 0f, 0f, .55f, 0f, 1f, .3762f, 6.238f)
        check(4.6f, 1f, 1f, 0f, 0f, .55f, 0f, 1f, 1f, 0f)
    }

    @Test fun theScarStrikesWhiteAndSettlesTeal() {
        assertEquals(0x47E6D4, eyeFrame(0.5f).scarInk)
        val flash = eyeFrame(1.06f).scarInk // flash 0.5: half way to white
        assertEquals(163, (flash shr 16) and 0xFF); assertEquals(243.0, ((flash shr 8) and 0xFF).toDouble(), 1.0); assertEquals(234.0, (flash and 0xFF).toDouble(), 1.0)
        assertEquals(0x47E6D4, eyeFrame(1.7f).scarInk)
        assertEquals(EYE_DUR, 4.6f, 0f)
    }

    // ---- when it plays

    @Test fun playsOncePerStart() {
        val a = ctx()
        assertTrue(StartupGate.claim(a)); assertFalse(StartupGate.claim(a)); assertFalse(StartupGate.claim(a.applicationContext))
        assertTrue(startupPlays(switchOn = true, reduceMotion = false, opensOnHome = true))
    }

    @Test fun playsOnAFreshStartAndASecondOpenInTheSameProcessDoesNot() {
        open(hasChatted = false); compose.waitForIdle()
        assertTrue("first start plays", StartupGate.last!!.t < EYE_DUR || StartupGate.last!!.playing.not())
        val first = StartupGate.last!!
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(100)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals("ended on its own", EYE_DUR, first.t, 0f)
        // A new composition in the same process (a resume or a rotation) makes a startup that plays nothing.
        val again = EyeStartup(false)
        assertFalse(again.playing)
    }

    @Test fun startsPlayingOnTheFirstHelloAndEndsOnTheHello() {
        compose.mainClock.autoAdvance = false
        open(hasChatted = false)
        compose.mainClock.advanceTimeBy(1000)
        val s = StartupGate.last!!
        assertTrue("playing at 1 s (${s.t})", s.playing && s.t > 0.5f)
        compose.onNodeWithTag("hello-eye").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(4000)
        assertFalse(s.playing)
        compose.onNodeWithText(HELLO).assertIsDisplayed()
        compose.onNodeWithTag("hello-eye").assertIsDisplayed()
    }

    @Test fun anyTouchSkipsToTheEnd() {
        compose.mainClock.autoAdvance = false
        open(hasChatted = false)
        compose.mainClock.advanceTimeBy(800)
        val s = StartupGate.last!!
        assertTrue(s.playing)
        compose.onRoot().performTouchInput { click(center) }
        assertFalse(s.playing)
        assertEquals(EYE_DUR, s.t, 0f)
        // The touch that skipped is not also a tap on what lay beneath it, once.
        assertTrue(s.takeSwallowedTap()); assertFalse(s.takeSwallowedTap())
    }

    @Test fun typingSkipsToTheEnd() {
        val s = EyeStartup(true)
        assertTrue(s.playing)
        s.skip()
        assertFalse(s.playing)
        s.onTouch() // a touch after the end swallows nothing
        assertFalse(s.takeSwallowedTap())
    }

    @Test fun doesNotPlayWithAnimationsRemoved() {
        open(hasChatted = false, reduce = true)
        compose.waitForIdle()
        assertFalse(StartupGate.last!!.playing)
        compose.onNodeWithText(HELLO).assertIsDisplayed()
        assertFalse(startupPlays(switchOn = true, reduceMotion = true, opensOnHome = true))
    }

    @Test fun doesNotPlayWhenOpenedOnAnotherScreen() {
        assertFalse(startupPlays(switchOn = true, reduceMotion = false, opensOnHome = false))
        open(hasChatted = false, nav = Navigator(Screen.Settings))
        compose.waitForIdle()
        assertFalse(StartupGate.last!!.playing)
    }

    @Test fun doesNotPlayWithTheSwitchOff() {
        prefs().edit().putBoolean(STARTUP_ANIMATION, false).commit()
        open(hasChatted = false)
        compose.waitForIdle()
        assertFalse(StartupGate.last!!.playing)
        assertFalse(startupPlays(switchOn = false, reduceMotion = false, opensOnHome = true))
        compose.onNodeWithText(HELLO).assertIsDisplayed()
    }

    @Test fun theEverydayScreenGetsAnOverlayThatFadesIntoTheGreeting() {
        compose.mainClock.autoAdvance = false
        open(hasChatted = true)
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("startup-overlay").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(4000)
        compose.onNodeWithTag("startup-overlay").assertDoesNotExist()
        compose.onNodeWithText(EVERYDAY_LEAD).assertIsDisplayed()
    }

    @Test fun overlayIsSolidThenFadesWithTheWords() {
        near(1f, overlayAlpha(3.0f), "solid"); near(0f, overlayAlpha(4.6f), "gone"); assertTrue(overlayAlpha(4.0f) in 0.01f..0.99f)
    }

    // ---- the Settings switch

    private fun openLook(reduce: Boolean = false) {
        val station = FakeStation(ExampleData.awake())
        val engine = testEngine(tmp.newFolder(), ready = true)
        val nav = Navigator()
        compose.setContent { CompositionLocalProvider(LocalPhoneEngine provides engine) { MimirTheme(reduceMotion = reduce) { AppRoot(station, nav) } } }
        compose.waitForIdle()
        nav.open(Screen.Settings); compose.waitForIdle()
        compose.onNodeWithText("Look and size").performScrollTo().performClick(); compose.waitForIdle()
    }

    @Test fun theSwitchIsUnderLookAndSizeOnByDefaultAndOffSavesIt() {
        openLook()
        compose.onNodeWithText("Startup animation").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("The eye opens when $AI_NAME starts").assertIsDisplayed()
        val sw = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState) and androidx.compose.ui.test.hasText("Startup animation"))
        sw.assertIsOn()
        assertTrue(startupAnimationOn(ctx()))
        sw.performClick(); compose.waitForIdle()
        sw.assertIsOff()
        assertFalse(startupAnimationOn(ctx()))
    }

    @Test fun theSwitchSaysWhyWhenThePhoneRemovesAnimations() {
        openLook(reduce = true)
        compose.onNodeWithText("Animations are off in this phone's settings").performScrollTo().assertIsDisplayed()
        val sw = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState) and androidx.compose.ui.test.hasText("Startup animation"))
        sw.assertIsOff()
        sw.performClick(); compose.waitForIdle()
        assertTrue("a disabled switch changes nothing", startupAnimationOn(ctx()))
    }

    @Test fun settingsSearchFindsIt() {
        for (q in listOf("startup animation", "eye animation", "animation", "startup")) {
            assertTrue(q, findSettings(q, paired = false).any { it.title == "Look and size" })
        }
    }
}
