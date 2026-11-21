package io.github.odnwks.mimir.online

import org.junit.Assert.assertNull
import org.junit.Test

/** R23: "What's running?" asks the PC about its own work; it is never searched for on the web. */
class PcQuestionsTest {
    @Test fun whatsRunningIsNotALookup() {
        for (q in listOf("What's running?", "what’s running", "What is running?", "What's going on?", "What's running on my PC?", "whats running")) {
            assertNull("'$q' is not looked up", Router.need(q))
        }
    }
}
