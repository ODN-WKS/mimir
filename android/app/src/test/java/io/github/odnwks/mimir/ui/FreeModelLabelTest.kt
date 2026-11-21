package io.github.odnwks.mimir.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * B7 / PHU-17: free-model replies must not be labelled "Claude".
 * The label depends on the model type, not a hardcoded string.
 */
class FreeModelLabelTest {
    @Test fun freeModelReplyIsNotLabelledClaude() {
        val label = replyLabel(isFreeModel = true, modelName = "Qwen 3")
        assertFalse("free model reply must not be labelled 'Claude'", label == "Claude")
        assertEquals("Qwen 3", label)
    }

    @Test fun freeModelWithNoNameFallsBackToAppName() {
        val label = replyLabel(isFreeModel = true, modelName = null)
        assertFalse(label == "Claude")
        assertEquals(AI_NAME, label)
    }

    @Test fun paidModelReplyIsLabelledClaude() {
        val label = replyLabel(isFreeModel = false, modelName = "Claude Sonnet")
        assertEquals("Mímir", label)
    }
}
