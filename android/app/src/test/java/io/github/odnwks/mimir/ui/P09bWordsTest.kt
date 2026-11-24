package io.github.odnwks.mimir.ui

import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.09 review: short words that fit a narrow menu, and a Look and size line that names what is inside. */
class P09bWordsTest {
    @Test fun theMenuRowFitsANarrowMenu() = assertTrue(MENU_USAGE.length <= 16)

    @Test fun lookAndSizeNamesTheKeyboardAndStopButtonChoices() {
        assertTrue(SETTINGS_LOOK_LINE.contains("keyboard", ignoreCase = true))
        assertTrue(SETTINGS_LOOK_LINE.contains("stop button", ignoreCase = true))
    }
}
