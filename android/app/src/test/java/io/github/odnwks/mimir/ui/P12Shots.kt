package io.github.odnwks.mimir.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.odnwks.mimir.data.ExampleData
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * P12: the project page, a task and the Mímir chat at 390x844, at font scale 1 and 1.3. The words
 * in the reply box's row are never cut off. Pictures are written only when P12_SHOT names a folder.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class P12Shots {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val out = System.getenv("P12_SHOT")

    private fun shot(name: String) {
        compose.mainClock.advanceTimeBy(1_000); compose.waitForIdle()
        out?.let { compose.onRoot().captureRoboImage("$it/$name.png") }
        // Every line of words in the row (the chip) shows whole: nothing ends in "…".
        val texts = compose.onAllNodes(androidx.compose.ui.test.hasText("", substring = true) and hasAnyAncestor(hasTestTag(REPLY_ROW_TAG)), useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("the row has words", texts.isNotEmpty())
        texts.forEach { n ->
            val layouts = mutableListOf<TextLayoutResult>()
            n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            val words = n.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
            // One line, no "…", and as wide as it needs within its room (Robolectric's didOverflowWidth is not used: it says yes for text that fits).
            layouts.forEach { l -> assertTrue("\"$words\" is cut off in $name (lines ${l.lineCount}, ${l.size.width} of ${l.layoutInput.constraints.maxWidth} px)", l.lineCount == 1 && !l.isLineEllipsized(0) && l.size.width <= l.layoutInput.constraints.maxWidth) }
        }
    }

    private fun app(st: FakeStation, nav: Navigator) {
        val engine = testEngine(tmp.newFolder(), ready = true)
        compose.setContent { CompositionLocalProvider(LocalPhoneEngine provides engine) { MimirTheme(reduceMotion = true) { AppRoot(st, nav) } } }
        compose.waitForIdle()
    }

    private fun project(scale: String) {
        val nav = Navigator(); app(FakeStation(), nav)
        nav.open(Screen.Project("Budget-App")); shot("p12_project_$scale")
    }

    private fun task(scale: String) {
        val st = FakeStation().also { f -> f.set { s -> s.copy(tasks = s.tasks.map { if (it.id == ExampleData.TASK_ALLOW) it.copy(permission = null, question = null, busy = false, status = "Done", model = "Opus 5.5") else it }) } }
        val nav = Navigator(); app(st, nav)
        nav.open(Screen.Task(ExampleData.TASK_ALLOW)); shot("p12_task_$scale")
    }

    private fun mimirChat(scale: String) {
        // A PC that talks: the paired chat is Mímir's own chat.
        val st = FakeStation().also { it.set { s -> s.copy(pcTalks = true) } }
        app(st, Navigator()); shot("p12_mimir_chat_$scale")
    }

    @Test fun projectPage() = project("1.0")
    @Test fun aTask() = task("1.0")
    @Test fun theMimirChat() = mimirChat("1.0")

    @Config(fontScale = 1.3f) @Test fun projectPageAt13() = project("1.3")
    @Config(fontScale = 1.3f) @Test fun aTaskAt13() = task("1.3")
    @Config(fontScale = 1.3f) @Test fun theMimirChatAt13() = mimirChat("1.3")
}
