package io.github.odnwks.mimir.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** The private note on a new chat stays short at text size 1.5; it doesn't become a tall column. */
class PrivacyNoteSizeTest {
    @Test fun atALargeTextSizeTheNoteIsThreeLinesUntilTapped() {
        assertEquals(3, privacyNoteLines(1.5f, expanded = false))
        assertEquals(3, privacyNoteLines(1.3f, expanded = false))
        assertEquals(Int.MAX_VALUE, privacyNoteLines(1.5f, expanded = true))
    }

    @Test fun atANormalTextSizeTheWholeNoteShows() {
        assertEquals(Int.MAX_VALUE, privacyNoteLines(1.0f, expanded = false))
        assertEquals(Int.MAX_VALUE, privacyNoteLines(1.1f, expanded = false))
    }
}
