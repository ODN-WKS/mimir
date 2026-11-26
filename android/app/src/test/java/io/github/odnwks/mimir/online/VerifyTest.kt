package io.github.odnwks.mimir.online

import org.junit.Assert.assertEquals
import org.junit.Test

/** A source number the evidence doesn't have is taken out of the answer; nothing else changes. */
class VerifyTest {
    @Test fun numbersOutsideTheSourcesAreRemoved() {
        assertEquals("It opened in 1932 [2]. It is 503 m long.", Verify.citations("It opened in 1932 [2]. It is 503 m long [9].", 3))
        assertEquals("Both say so [1].", Verify.citations("Both say so [1, 7].", 3))
        assertEquals("Kept [1] [3].", Verify.citations("Kept [1] [3].", 3))
        assertEquals("No sources.", Verify.citations("No sources [1].", 0))
        assertEquals("Plain text, nothing cited.", Verify.citations("Plain text, nothing cited.", 3))
    }

    @Test fun codeYearsAndLargeNumbersAreLeftAlone() {
        // Review 2026-11-25: brackets that are not source numbers must survive.
        assertEquals("Use `arr[0]` and `x[7]` [1].", Verify.citations("Use `arr[0]` and `x[7]` [1].", 2))
        assertEquals("```\nval a = b[9]\n```\nDone [1].", Verify.citations("```\nval a = b[9]\n```\nDone [1].", 1))
        assertEquals("The [2024] report [1].", Verify.citations("The [2024] report [1].", 1))
        assertEquals("Item [42] stays.", Verify.citations("Item [42] stays.", 3))
    }
}
