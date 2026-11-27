package io.github.odnwks.mimir.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.odnwks.mimir.standalone.Turn
import io.github.odnwks.mimir.ui.theme.Palette
import io.github.odnwks.mimir.ui.theme.Type

const val EARLIER_ANSWER = "Earlier answer"
const val EARLIER_STEP = "Show the earlier answer"
const val NEWER_STEP = "Show the newer answer"

/** An earlier answer, as it was: its words and sources, to read, copy or hear. */
@Composable
internal fun EarlierAnswer(old: Turn, askedNow: String, openSources: Boolean, pcName: String?) {
    val asked = old.asked?.takeIf { it != askedNow }?.let { ". You had asked: $it" }.orEmpty()
    Reply(
        old.text, dim = false, sources = old.sources, openSources = openSources, note = EARLIER_ANSWER + asked,
        author = pcName?.let { answerAuthor(old, it) }, fromMemory = fromMemory(old, null),
    )
}

/**
 * The newest answer ([current]) with the ones it replaced a step away, "2 of 2" under it: "Try again",
 * "Look it up" and an edit never lose an answer. [pcName] null: authors aren't named (a Whisper).
 */
@Composable
internal fun WithEarlier(id: String, earlier: List<Turn>, askedNow: String, openSources: Boolean, pcName: String?, current: @Composable () -> Unit) {
    if (earlier.isEmpty()) { current(); return }
    // A new answer (one more earlier one) is shown first again. Not saved with the screen: a saved place
    // would come back over the new answer.
    var shown by remember(id, earlier.size, earlier.lastOrNull()?.version) { mutableStateOf(earlier.size) }
    val at = shown.coerceIn(0, earlier.size)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (at < earlier.size) EarlierAnswer(earlier[at], askedNow, openSources, pcName) else current()
        Row(Modifier.testTag("answer-versions"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (at > 0) QuietButton("‹ Earlier", EARLIER_STEP) { shown = at - 1 }
            Text("${at + 1} of ${earlier.size + 1}", style = Type.Small, color = Palette.TextDim)
            if (at < earlier.size) QuietButton("Newer ›", NEWER_STEP) { shown = at + 1 }
        }
    }
}
