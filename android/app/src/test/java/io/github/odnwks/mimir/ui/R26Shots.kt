package io.github.odnwks.mimir.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.link.LChoice
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** R26: draws "Who answers", the model and effort sheet and a task screen at 339 dp. Writes only when R26_SHOT names a folder. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w339dp-h740dp-xhdpi")
class R26Shots {
    @get:Rule val compose = createComposeRule()
    private val out = System.getenv("R26_SHOT")

    @Test fun whoAnswers() {
        val dir = out ?: return
        compose.setContent {
            MimirTheme(reduceMotion = true) {
                Box(Modifier.fillMaxSize()) {
                    WhoAnswersSheet(open = true, chosen = Answerer.Claude, phoneCan = true, paired = true, claudeOffered = true, onChoose = {}, onConnect = {}, onClose = {})
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$dir/who_answers.png")
    }

    @Test fun chipMenu() {
        val dir = out ?: return
        val choices = listOf(
            LChoice("auto", "Auto-pick", "Mímir chooses"), LChoice("haiku", "Quick", "Short answers, fastest"),
            LChoice("sonnet", "Balanced", "The usual"), LChoice("opus", "Very capable", "For harder work"),
            LChoice("fable", "Most capable", "The newest and strongest"),
        )
        // P12: the chip's menu is the model and effort sheet, as on a task.
        val nav = Navigator()
        compose.setContent { MimirTheme(reduceMotion = true) { AppRoot(FakeStation(), nav) } }
        compose.waitForIdle()
        compose.runOnIdle {
            nav.chatChoices = io.github.odnwks.mimir.nav.ModelEffortChoices(
                choices.map { c -> io.github.odnwks.mimir.nav.SheetChoice(c.name, claudeAbout(c.id, c.about), c.id == "auto") {} } +
                    io.github.odnwks.mimir.nav.SheetChoice("Your PC's free model", "Quick answers that stay on your PC", false) {},
                listOf("Applies to all chats on this PC. $PC_PICKS", "Last answer: Sonnet 4.6"), {},
                io.github.odnwks.mimir.standalone.Effort.entries.map { e -> io.github.odnwks.mimir.nav.SheetChoice(e.words, e.about, e == io.github.odnwks.mimir.standalone.Effort.Balanced) {} },
            )
            nav.openSheet(io.github.odnwks.mimir.nav.Sheet.ChatModel)
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$dir/chip_menu.png")
    }

    @Test fun taskScreen() {
        val dir = out ?: return
        val station = FakeStation()
        val nav = Navigator()
        compose.setContent { MimirTheme(reduceMotion = true) { AppRoot(station, nav) } }
        compose.waitForIdle()
        nav.open(Screen.Task("t-allow"))
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$dir/task_screen.png")
    }
}
