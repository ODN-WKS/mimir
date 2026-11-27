package io.github.odnwks.mimir.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.standalone.Skill
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
 * P46 walk, problem 2: a picked skill showed shortened words ("Research", "People say") that don't
 * name it. The bubble always shows the skill's whole name, at the phone widths the walk used.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class P46F1SkillNameTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val nav = Navigator()

    private fun words(n: SemanticsNode): List<String> =
        n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + n.children.flatMap { words(it) }

    private fun everySkillShowsItsWholeName() {
        val engine = testEngine(tmp.newFolder(), ready = true)
        compose.setContent { CompositionLocalProvider(LocalPhoneEngine provides engine) { MimirTheme(reduceMotion = true) { PhoneChat(engine, nav) } } }
        compose.waitForIdle()
        for (skill in Skill.entries) {
            compose.runOnIdle { nav.skill = skill }
            compose.waitForIdle()
            val shown = words(compose.onNodeWithTag(SKILL_CHIP_TAG, useUnmergedTree = true).fetchSemanticsNode())
            assertTrue("$skill shows $shown", shown == listOf("Skill: ${skill.title}") || shown == listOf(skill.title))
        }
    }

    @Config(qualifiers = "w384dp-h832dp-xxhdpi")
    @Test fun at384dpThePickedSkillShowsItsWholeName() = everySkillShowsItsWholeName()

    @Config(qualifiers = "w339dp-h734dp-xxxhdpi")
    @Test fun at339dpThePickedSkillShowsItsWholeName() = everySkillShowsItsWholeName()
}
