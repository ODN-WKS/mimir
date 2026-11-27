package io.github.odnwks.mimir.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import io.github.odnwks.mimir.data.ExampleData
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.data.LinkStation
import io.github.odnwks.mimir.data.Station
import io.github.odnwks.mimir.nav.ChatAsk
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.standalone.Engine
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P33 (owner, 2026-11-26): Send closes the keyboard on every message box; coming back to the app
 * never opens it by itself and leaves an open chat at its bottom; New chat still opens it.
 * A box with the cursor is a box with the keyboard, so these look at the cursor.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class P33KeyboardTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val tmp = TemporaryFolder()
    private val nav = Navigator()
    private var height by mutableStateOf(844)
    private lateinit var engine: Engine

    private fun start(station: Station, phone: Boolean = false) {
        compose.activity.getSharedPreferences(PHONE_CHAT_PREFS, 0).edit().putBoolean(KEYBOARD_AUTO, true).commit()
        if (phone) engine = testEngine(tmp.newFolder(), ready = true)
        compose.setContent {
            CompositionLocalProvider(LocalPhoneEngine provides if (phone) engine else null) {
                MimirTheme(reduceMotion = true) { Box(Modifier.height(height.dp)) { AppRoot(station, nav) } }
            }
        }
        settle()
    }

    /** Past a reply box's own 300 ms wait before it takes the cursor. */
    private fun settle() { compose.waitForIdle(); compose.mainClock.advanceTimeBy(700); compose.waitForIdle() }

    private fun onUi(act: () -> Unit) { compose.runOnUiThread(act); settle() }

    private fun open(screen: Screen) = onUi { nav.open(screen) }

    private fun box(label: String) = compose.onNodeWithContentDescription(label)

    private fun focused(label: String) = box(label).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused) == true

    private fun words(label: String) = box(label).fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    private fun type(label: String, text: String) {
        box(label).performClick()
        box(label).performTextInput(text)
        settle()
        assertTrue("the box has the cursor while typing", focused(label))
    }

    private fun tapSend(description: String = "Send") { compose.onNodeWithContentDescription(description).performClick(); settle() }

    private fun assertClosed(label: String) = assertTrue("the keyboard is still up on \"$label\"", !focused(label))

    private fun leaveAndReturn() {
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.waitForIdle()
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        settle()
    }

    /** Recents and straight back: paused, never stopped. */
    private fun pauseAndResume() {
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.waitForIdle()
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        settle()
    }

    private fun phoneAlone() = FakeStation(LinkStation.empty("test").copy(paired = false))

    private val project get() = ExampleData.awake().projects.first().name

    // ---------- Send closes the keyboard ----------

    @Test fun sendClosesTheKeyboardOnATask() {
        start(FakeStation()); open(Screen.Task(ExampleData.TASK_ALLOW))
        type(REPLY, "and the labels")
        tapSend()
        assertEquals("", words(REPLY))
        assertClosed(REPLY)
    }

    @Test fun sendClosesTheKeyboardInChat() {
        start(FakeStation(ExampleData.awake().copy(pcTalks = false)))
        type(CHAT, "make the title bigger")
        tapSend()
        assertClosed(CHAT)
    }

    @Test fun sendClosesTheKeyboardOnANewTaskAndTheOpenedTaskKeepsItAway() {
        start(FakeStation()); open(Screen.Project(project))
        type("New task in $project", "tidy the shed list")
        tapSend()
        assertTrue("the new task opened", nav.current is Screen.Task)
        assertClosed(REPLY)
    }

    @Test fun sendClosesTheKeyboardInThePhoneModelChat() {
        start(phoneAlone(), phone = true)
        type(CHAT, "water the beds")
        tapSend()
        compose.waitUntil(5_000) { !engine.state.value.writing }
        settle()
        assertClosed(CHAT)
    }

    @Test fun sendClosesTheKeyboardOnThePhoneModelPage() {
        start(FakeStation(ExampleData.awake()), phone = true); open(Screen.Standalone)
        type(CHAT, "water the beds")
        tapSend()
        compose.waitUntil(5_000) { !engine.state.value.writing }
        settle()
        assertClosed(CHAT)
    }

    // ---------- Coming back never opens it ----------

    @Test fun leavingATaskAndReturningKeepsItAwayAndKeepsTheWords() {
        start(FakeStation()); open(Screen.Task(ExampleData.TASK_ALLOW))
        type(REPLY, "not sent yet")
        leaveAndReturn()
        assertClosed(REPLY)
        assertEquals("not sent yet", words(REPLY))
    }

    @Test fun aShortLookAtRecentsKeepsItAwayToo() {
        start(FakeStation()); open(Screen.Task(ExampleData.TASK_ALLOW))
        type(REPLY, "not sent yet")
        pauseAndResume()
        assertClosed(REPLY)
        assertEquals("not sent yet", words(REPLY))
    }

    @Test fun leavingChatAndReturningKeepsItAway() {
        start(FakeStation(ExampleData.awake().copy(pcTalks = false)))
        type(CHAT, "not sent yet")
        leaveAndReturn()
        assertClosed(CHAT)
        assertEquals("not sent yet", words(CHAT))
    }

    @Test fun leavingThePhoneModelChatAndReturningKeepsItAway() {
        start(phoneAlone(), phone = true)
        type(CHAT, "not sent yet")
        leaveAndReturn()
        assertClosed(CHAT)
        assertEquals("not sent yet", words(CHAT))
    }

    /** A box built again on the way back (after the app's own lock, or a tapped notification) takes no cursor. */
    @Test fun aReplyBoxBuiltWhileComingBackTakesNoCursor() {
        start(FakeStation())
        leaveAndReturn()
        open(Screen.Task(ExampleData.TASK_ALLOW))
        assertClosed(REPLY)
    }

    /** After a touch the person is driving again: a task opened then has the cursor, as before. */
    @Test fun aTaskOpenedAfterATouchStillTakesTheCursor() {
        start(FakeStation())
        leaveAndReturn()
        compose.onRoot().performTouchInput { down(androidx.compose.ui.geometry.Offset(center.x, 4f)); up() }
        open(Screen.Task(ExampleData.TASK_ALLOW))
        assertTrue("a task opened by hand has the cursor", focused(REPLY))
    }

    // ---------- New chat still opens it (0.09 item 23) ----------

    @Test fun newChatStillOpensTheKeyboardAfterSendingAndAfterReturning() {
        start(phoneAlone(), phone = true)
        type(CHAT, "water the beds")
        tapSend()
        compose.waitUntil(5_000) { !engine.state.value.writing }
        leaveAndReturn()
        assertClosed(CHAT)
        onUi { nav.askChat(ChatAsk.NewChat) }
        assertTrue("New chat brings the cursor", focused(CHAT))
    }

    // ---------- A chat at its bottom stays there ----------

    private fun conversation(): ScrollAxisRange = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
        .fetchSemanticsNodes().map { it.config[SemanticsProperties.VerticalScrollAxisRange] }.maxBy { it.maxValue() }

    private fun longTask() {
        val station = FakeStation()
        repeat(12) { station.reply(ExampleData.TASK_ALLOW, "Line $it of a long conversation about the planting plan.") }
        start(station); open(Screen.Task(ExampleData.TASK_ALLOW))
    }

    private fun assertAtBottom(what: String) = conversation().let {
        assertTrue("nothing to scroll", it.maxValue() > 0f)
        assertEquals(what, it.maxValue(), it.value(), 2f)
    }

    /** The space above the box changing size (the keyboard still sliding after a return) leaves the end in view. */
    @Test fun aChatAtItsBottomStaysThereWhenTheSpaceShrinks() {
        longTask()
        assertAtBottom("opens at the bottom")
        leaveAndReturn()
        for (h in intArrayOf(760, 640, 520)) { height = h; settle() }
        assertAtBottom("slid off the bottom")
    }

    @Test fun aPersonReadingAboveIsLeftWhereTheyAre() {
        longTask()
        compose.onNode(SemanticsMatcher("the conversation") { (it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.maxValue?.invoke() ?: 0f) > 0f }).performTouchInput { swipeDown() }
        settle()
        val before = conversation().value()
        assertTrue("scrolled up", before < conversation().maxValue() - 100f)
        height = 640; settle()
        assertEquals(before, conversation().value(), 2f)
    }

    private companion object {
        const val REPLY = "Reply to this task"
        val CHAT = "Message $AI_NAME"
    }
}
