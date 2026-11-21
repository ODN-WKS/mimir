package io.github.odnwks.mimir.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * B7 / PHU-17: Claude must NOT be first in the model list when the phone
 * is not paired with a PC. When unpaired, the phone's own models are the
 * ones that actually work, so they appear first.
 */
class ModelOrderTest {
    @Test fun claudeIsNotFirstWhenUnpaired() {
        val order = modelGroupOrder(paired = false)
        assertFalse(
            "Claude must not be first when unpaired, got: $order",
            order.first() == "claude"
        )
        // Phone models should be first when unpaired.
        assertEquals("phone", order.first())
    }

    @Test fun claudeIsFirstWhenPaired() {
        val order = modelGroupOrder(paired = true)
        assertEquals("claude should lead the list when paired", "claude", order.first())
    }
}
