package io.github.odnwks.mimir.ui

import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.data.Question
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.performClick
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.test.performTouchInput
import io.github.odnwks.mimir.ui.theme.MimirTheme
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class P09ATaskScreenTest {
    @Test fun p09Item1AttachedPathsBecomeNames() {
        val (words, names) = AttachedNames.split("Look at these\n\nAttached: /home/x/task-files/t-1/1-plan.pdf, /home/x/task-files/t-1/2-photo.png")
        assertEquals("Look at these", words)
        assertEquals(listOf("plan.pdf", "photo.png"), names)
        assertEquals("no tail" to emptyList<String>(), AttachedNames.split("no tail"))
    }

    @Test fun p09Item3DeleteTaskCallAndStation() {
        val st = FakeStation()
        var result: String? = "unset"
        st.deleteTask("nope") { result = it }
        assertEquals(null, result)
        st.deleteRefusal = "It is working."
        st.deleteTask("nope") { result = it }
        assertEquals("It is working.", result)
    }

    // P43: one Back from a task is the project page it was opened from (P24 had made it home).
    @Test fun p09Item5BackFromATaskIsItsProjectInOneStep() {
        val nav = Navigator()
        nav.open(Screen.Settings)
        nav.open(Screen.Project("p"))
        nav.open(Screen.Task("t"))
        assertTrue(nav.back())
        assertEquals(Screen.Project("p"), nav.current)
    }

    @Test fun p09Item10StopModes() {
        val confirm = StopGuard(StopMode.Confirm)
        assertEquals(StopAction.Arm, confirm.tap(0))
        assertEquals(StopAction.Fire, confirm.tap(1000))
        assertEquals(StopAction.Arm, confirm.tap(10_000))
        assertEquals(StopAction.Arm, confirm.tap(10_000 + STOP_CONFIRM_MS + 1))
        assertEquals(StopAction.Hint, StopGuard(StopMode.Hold).tap(0))
        assertEquals(StopAction.Arm, StopGuard(StopMode.Hold).tap(0, accessible = true))
        assertEquals(StopAction.Fire, StopGuard(StopMode.Off).tap(0))
        assertEquals(StopMode.Confirm, StopMode.of(null))
    }

    @Test fun p09Item22QuestionIsOneQuotedLine() {
        val q = Question("q1", "**Which** colour\nshall I use?", "", "")
        assertEquals("Which colour shall I use?", questionQuote(q, null))
        assertEquals("Last line?", questionQuote(Question("q2", "", "", ""), "Intro\n\nLast line?"))
    }

    @Test fun p09FixBQuoteIsTheQuestionSentenceNotTheLeadIn() {
        val answer = "Tomatoes are botanically fruits, though they are used as vegetables in cooking. Do you prefer cherry or beefsteak?"
        assertEquals("Do you prefer cherry or beefsteak?", questionQuote(Question("q3", "", "", ""), "First.\n\n$answer"))
        // No question mark: the PC's text as it is.
        assertEquals("Pick a colour", questionQuote(Question("q4", "Pick a colour", "", ""), null))
        // The PC's own text wins and the question sentence is taken from it.
        assertEquals("Which one?", questionQuote(Question("q5", "Two are free. Which one?", "", ""), "other?"))
        // Very long question: shortened from the front, the end stays.
        val long = "Is it " + "really ".repeat(40) + "the one you want?"
        val out = questionQuote(Question("q6", long, "", ""), null)
        assertTrue(out.startsWith("…") && out.endsWith("the one you want?") && out.length <= 91)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class P09ATaskRowsTest {
    @get:Rule val compose = createComposeRule()

    private fun rowNodes() = compose.onAllNodes(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.CustomActions))
        .filter(androidx.compose.ui.test.hasText("Messages arrive in order"))
    private fun rows() = rowNodes().fetchSemanticsNodes().size

    @Test fun p09Item4ArchivedAndDeletedTasksLeaveTheProjectRows() {
        val station = FakeStation()
        val nav = Navigator()
        compose.setContent { MimirTheme { AppRoot(station, nav) } }
        val before = run { compose.waitForIdle(); rows() }
        nav.open(Screen.Project("Budget-App")); compose.waitForIdle()
        val onPage = rows()
        assertTrue("the project page lists the task", onPage > before)
        val row = rowNodes().onFirst()
        val archive = row.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.CustomActions].first { it.label == "Archive" }
        compose.runOnUiThread { archive.action() }
        compose.waitForIdle()
        assertEquals(before, rows())
        compose.onNodeWithContentDescription("Undo: Archived Messages arrive in order").performClick()
        compose.waitForIdle()
        assertEquals(onPage, rows())
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class P09AQuestionLineTest {
    @get:Rule val compose = createComposeRule()
    private val id = io.github.odnwks.mimir.data.ExampleData.TASK_ALLOW

    @Test fun p09Item22TheQuestionIsOneLineAboveTheBoxAndGoesWhenThePersonReplies() {
        val q = "Which herb do you prefer: basil or mint?"
        val st = FakeStation().also { fs ->
            fs.set { s -> s.copy(tasks = s.tasks.map { if (it.id == id) it.copy(permission = null, question = io.github.odnwks.mimir.data.Question("q1", q, "", ""), busy = false, stopped = false, status = "Needs you",
                messages = listOf(io.github.odnwks.mimir.data.TaskMessage("1", true, q))) else it }) }
        }
        val nav = Navigator()
        compose.setContent { MimirTheme(reduceMotion = true) { AppRoot(st, nav) } }
        compose.waitForIdle(); nav.open(Screen.Task(id)); compose.waitForIdle()
        compose.onNodeWithText("\u201c$q\u201d", substring = true).assertExists()
        compose.onNodeWithContentDescription("Reply to this task").performTextInput("mint")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithText("\u201c$q\u201d", substring = true).fetchSemanticsNodes().isEmpty())
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class P09AHoldFillTest {
    @get:Rule val compose = createComposeRule()

    @Test fun p09FixAHeldStopKeepsItsSizeInsideATallBar() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MimirTheme(reduceMotion = true) {
                androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.androidxFill()) {
                    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        androidx.compose.material3.Text("A task title", androidx.compose.ui.Modifier.weight(1f))
                        StopControl({}, shape = StopShape.Pill, mode = StopMode.Hold)
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(100)
        val stop = compose.onNodeWithContentDescription("Stop")
        val before = stop.fetchSemanticsNode().size.height
        stop.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        val during = stop.fetchSemanticsNode().size.height
        stop.performTouchInput { up() }
        assertEquals("the pill keeps its height while held", before, during)
    }

    @Test fun p09Fix6ReleasingAfterAFinishedHoldDoesNotSayHoldToStop() {
        var stopped = 0
        compose.setContent { MimirTheme(reduceMotion = true) { StopControl({ stopped++ }, shape = StopShape.Pill, mode = StopMode.Hold) } }
        compose.mainClock.autoAdvance = false
        val stop = compose.onNodeWithContentDescription("Stop")
        stop.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(STOP_HOLD_MS + 300L)
        stop.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(100)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(1, stopped)
        compose.onNodeWithText("Hold to stop").assertDoesNotExist()
    }
}

private fun androidx.compose.ui.Modifier.androidxFill() = this.fillMaxSize()

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class P09AFixDeleteTest {
    @get:Rule val compose = createComposeRule()
    private val ctx get() = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun p09Fix1DeleteIsOfferedOnlyWhereThePcWouldDoIt() {
        val st = FakeStation().state.value
        val needs = st.tasks.first { it.showsStop }
        val quiet = st.tasks.first { !it.showsStop }
        assertEquals(false, canDeleteNow(needs, io.github.odnwks.mimir.data.Chip.NeedsYou))
        assertEquals(true, canDeleteNow(quiet, io.github.odnwks.mimir.data.Chip.Done))
        assertEquals(false, canDeleteNow(null, io.github.odnwks.mimir.data.Chip.NeedsYou))
    }

    @Test fun p09Fix2ADeletedTaskLeavesEveryList() {
        val st = FakeStation().state.value
        val id = io.github.odnwks.mimir.data.ExampleData.TASK_ALLOW
        val gone = st.without(listOf(id))
        assertTrue(st.tasks.any { it.id == id } && st.needsYou.any { it.taskId == id })
        assertTrue(gone.tasks.none { it.id == id })
        assertTrue(gone.needsYou.none { it.taskId == id })
        assertTrue(gone.projects.all { p -> p.tasks.none { it.taskId == id } })
        assertEquals(st, st.without(emptyList()))
    }

    private fun confirmDelete(st: FakeStation, nav: Navigator, id: String) {
        compose.setContent { MimirTheme(reduceMotion = true) { AppRoot(st, nav) } }
        compose.waitForIdle(); nav.openSheet(io.github.odnwks.mimir.nav.Sheet.DeleteTask(id)); compose.waitForIdle()
        compose.onNodeWithText("Delete task").performClick(); compose.waitForIdle()
    }

    @Test fun p09Fix3AConfirmedDeleteSurvivesTheAppClosingAndFix1TheRefusalIsSaid() {
        ctx.getSharedPreferences(PHONE_CHAT_PREFS, 0).edit().clear().commit()
        val id = io.github.odnwks.mimir.data.ExampleData.TASK_ALLOW
        val st = FakeStation().also { it.deleteRefusal = "It has a separate copy." }
        val nav = Navigator()
        confirmDelete(st, nav, id)
        assertEquals("kept on the phone until sent", setOf(id), PendingDeletes.all(ctx))
        assertTrue(id in nav.hiddenTasks)
        compose.mainClock.advanceTimeBy(9_000); compose.waitForIdle()
        compose.onNodeWithText("Not deleted: It has a separate copy.").assertExists()
        assertTrue(id !in nav.hiddenTasks)
        assertEquals(emptySet<String>(), PendingDeletes.all(ctx))
    }

    @Test fun p09Fix2DeletingFromTheTaskScreenLeavesItAtOnce() {
        ctx.getSharedPreferences(PHONE_CHAT_PREFS, 0).edit().clear().commit()
        val id = io.github.odnwks.mimir.data.ExampleData.TASK_ALLOW
        val st = FakeStation()
        val nav = Navigator()
        compose.setContent { MimirTheme(reduceMotion = true) { AppRoot(st, nav) } }
        compose.waitForIdle(); nav.open(Screen.Task(id)); compose.waitForIdle()
        nav.openSheet(io.github.odnwks.mimir.nav.Sheet.DeleteTask(id)); compose.waitForIdle()
        compose.onNodeWithText("Delete task").performClick(); compose.waitForIdle()
        assertTrue("still on the deleted task: ${nav.current}", nav.current != Screen.Task(id))
        compose.onNodeWithText("Getting this task from your PC", substring = true).assertDoesNotExist()
    }

    @Test fun p09Fix2DeletingATaskJustStartedOnThisPhoneAlsoLeavesItsScreen() {
        ctx.getSharedPreferences(PHONE_CHAT_PREFS, 0).edit().clear().commit()
        val id = io.github.odnwks.mimir.data.ExampleData.TASK_ALLOW
        val st = FakeStation().also { fs -> fs.set { s -> s.copy(startedTasks = mapOf("Just started" to id)) } }
        val nav = Navigator()
        compose.setContent { MimirTheme(reduceMotion = true) { AppRoot(st, nav) } }
        compose.waitForIdle(); nav.open(Screen.Task("Just started")); compose.waitForIdle()
        nav.openSheet(io.github.odnwks.mimir.nav.Sheet.DeleteTask(id)); compose.waitForIdle()
        compose.onNodeWithText("Delete task").performClick(); compose.waitForIdle()
        assertTrue("still on the deleted task: ${nav.current}", nav.current !is Screen.Task)
    }

    @Test fun p09Fix3AppStartSendsADeleteThatWasNeverSent() {
        ctx.getSharedPreferences(PHONE_CHAT_PREFS, 0).edit().clear().commit()
        val id = io.github.odnwks.mimir.data.ExampleData.TASK_ALLOW
        PendingDeletes.add(ctx, id)
        val st = FakeStation()
        compose.setContent { MimirTheme(reduceMotion = true) { AppRoot(st, Navigator()) } }
        compose.waitForIdle()
        assertTrue("the PC was asked", st.state.value.tasks.none { it.id == id })
        assertEquals(emptySet<String>(), PendingDeletes.all(ctx))
    }
}

class P09AFix5StopSourceTest {
    @Test fun p09Fix5NoStandaloneStopIsAPlainOneTapButton() {
        val f = listOf("src/main", "app/src/main").map { java.io.File(it, "java/io/github/odnwks/mimir/ui/StandaloneScreens.kt") }.first { it.exists() }
        val stops = f.readLines().filter { "Stop the reply" in it }
        assertTrue("there is a Stop on the standalone chat", stops.isNotEmpty())
        assertTrue("every Stop there is the shared guarded control: $stops", stops.all { "StopControl(" in it })
    }
}
