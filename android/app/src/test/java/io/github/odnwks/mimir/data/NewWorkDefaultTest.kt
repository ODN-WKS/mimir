package io.github.odnwks.mimir.data

import io.github.odnwks.mimir.ui.optionsWords
import org.junit.Assert.assertEquals
import org.junit.Test

/** The PC's default for new work is what a new task's chip shows; a model this phone picked wins. */
class NewWorkDefaultTest {
    private fun models(chosen: String, default: String?) =
        Mapping.models(null, chosen).copy(defaultModel = default, defaultEffort = default?.let { "high" })

    @Test
    fun onAutoTheChipShowsThePcsDefault() {
        val m = models("auto", "auto-capable")
        assertEquals("auto-capable", m.usual)
        assertEquals("Auto, stronger · High", optionsWords(m.usualName, m.defaultEffort.orEmpty()))
    }

    @Test
    fun thisPhonesPickAndAnOlderPcAreUnchanged() {
        assertEquals("claude-opus-5-5", models("claude-opus-5-5", "auto-capable").usual)
        assertEquals("auto", models("auto", null).usual)
        assertEquals("auto", models("auto", "").usual)
    }
}
