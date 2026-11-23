package io.github.odnwks.mimir.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** The PC's speed note reads the same plain way on the phone as in the PC window. */
class PlainTestNoteTest {
    @Test fun theCodingCheckIsSaidInPlainWords() {
        assertEquals("Passed our coding check in 30 s · fastest", plainTestNote("Fixed both test bugs in 30 s · fastest"))
        assertEquals("Fixed both in 32 s", plainTestNote("Fixed both in 32 s"))
    }
}
