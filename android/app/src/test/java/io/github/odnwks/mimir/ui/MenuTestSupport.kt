package io.github.odnwks.mimir.ui

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTouchInput

/** Opens the side menu from the chat's menu button (nothing to do when it is always open on a wide window). */
fun ComposeTestRule.openMenu() {
    if (onAllNodesWithContentDescription(MENU_BUTTON, substring = true).fetchSemanticsNodes().isNotEmpty()) { onNodeWithContentDescription(MENU_BUTTON, substring = true).performClick(); waitForIdle() }
}

/** One row of the side menu: opens the menu if needed, then taps the row. */
fun ComposeTestRule.menuRow(text: String) {
    openMenu()
    // The drawer is drawn after the screen, so its row is the last one with these words.
    onAllNodesWithText(text).onLast().performClick()
    waitForIdle()
}

/** Settings, from the bottom of the side menu. */
fun ComposeTestRule.openSettings() = menuRow(MENU_SETTINGS)

/** Press and hold one row of the side menu (a chat: its own menu; Whisper: a Whisper with internet). */
fun ComposeTestRule.menuRowHold(text: String, substring: Boolean = false) {
    openMenu()
    onAllNodesWithText(text, substring = substring).onLast().performTouchInput { longClick() }
    waitForIdle()
}

/** The open chat's menu (Rename, Copy the whole chat, Rules, What was sent, Delete): the "⋯" at the top right. */
fun ComposeTestRule.openChatOptions() {
    onNodeWithContentDescription(CHAT_MENU).performClick()
    waitForIdle()
}

/** "Who should answer?": a tap on the top bar's who-answers label. */
fun ComposeTestRule.openWhoAnswers(name: String = "Your PC") {
    onNodeWithContentDescription(whoLabel(name)).performClick()
    waitForIdle()
}
