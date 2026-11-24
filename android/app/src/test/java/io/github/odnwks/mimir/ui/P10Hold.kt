package io.github.odnwks.mimir.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.junit4.ComposeContentTestRule

/** P10: the Listen, Copy, Share and Try again of an answer sit behind a long press; these open it and pick one. */
fun ComposeContentTestRule.holdAnswerAndPick(answerText: String, item: String) {
    onAllNodesWithText(answerText, substring = true).onLast().performSemanticsAction(SemanticsActions.OnLongClick)
    waitForIdle()
    onAllNodesWithText(item).onLast().performClick()
    waitForIdle()
}
