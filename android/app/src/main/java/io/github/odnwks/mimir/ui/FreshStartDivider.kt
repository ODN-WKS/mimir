package io.github.odnwks.mimir.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.odnwks.mimir.ui.theme.Palette
import io.github.odnwks.mimir.ui.theme.Type

const val FRESH_DIVIDER_TAG = "fresh-start-divider"

/** The PC's note where a task's chat carries on in a new session (crates/core's `fresh_starts`). */
private val FRESH_NOTE = Regex("^Fresh start at \\d{2}:\\d{2} · continues from the hand-off$")

fun isFreshStartNote(text: String): Boolean = FRESH_NOTE.matches(text)

/** A thin line across the chat with its words in the middle; the words carry the time. */
@Composable
fun FreshStartDivider(text: String) {
    Row(
        Modifier.fillMaxWidth().testTag(FRESH_DIVIDER_TAG),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Line()
        Text(text, style = Type.Small, color = Palette.TextDim, textAlign = TextAlign.Center, modifier = Modifier.weight(1f, fill = false))
        Line()
    }
}

@Composable
private fun Line() {
    Box(Modifier.width(24.dp).height(1.dp).background(Palette.TextDim.copy(alpha = 0.4f)))
}
