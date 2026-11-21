package io.github.odnwks.mimir.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** R23: which model answers, in plain words, and who the top bar names. */
class ClaudeWordsTest {
    @Test fun modelIdsBecomeFamilyAndVersion() {
        assertEquals("Sonnet 4.6", claudeModelWord("claude-sonnet-4-6-20250929"))
        assertEquals("Sonnet 4.6", claudeModelWord("Sonnet 4.6"))
        assertEquals("Opus 5.5", claudeModelWord("claude-opus-5-5"))
        assertEquals("Haiku", claudeModelWord("claude-haiku"))
        assertEquals("Fable", claudeModelWord("Fable"))
        assertEquals("Sonnet 4", claudeModelWord("claude-sonnet-4-20250514"))
        assertNull(claudeModelWord("Online · paid"))
        assertNull(claudeModelWord(null))
    }

    @Test fun theTopBarNamesWhoReallyAnswers() {
        assertEquals("Online", answererWord(false, false, answeringPc = true, claudeAnswers = true))
        assertEquals("Your PC", answererWord(false, false, answeringPc = true, claudeAnswers = false))
        assertEquals("This phone", answererWord(false, false, answeringPc = false, claudeAnswers = true))
        assertEquals("Online", answererWord(false, true, answeringPc = true, claudeAnswers = true))
        assertEquals("OpenRouter", answererWord(false, true, answeringPc = false, claudeAnswers = false, serviceName = "OpenRouter"))
        assertEquals("Whisper", answererWord(true, false, answeringPc = true, claudeAnswers = true))
        assertEquals("Auto-pick", claudeChipWord())
    }
}

/** R23: the chip's word with Claude answering. */
class ClaudeChipWordTest {
    @Test fun theChipSaysWhatIsPickedThenWhatAnsweredThenAutoPick() {
        assertEquals("Opus", claudeChipWordFor("Opus", "Sonnet 4.6"))
        assertEquals("Auto-pick", claudeChipWordFor(null, "Sonnet 4.6"))
        assertEquals("Auto-pick", claudeChipWordFor(null, null))
        assertEquals("Mímir picks the model itself", claudeAbout("auto", "Claude Code chooses"))
        assertEquals("Quick", claudeAbout("haiku", "Quick"))
    }
}
