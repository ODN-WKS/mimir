package io.github.odnwks.mimir.ui

import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle

/** The words "Find in this chat" is looking for (null: not finding). */
val LocalFound = compositionLocalOf<String?> { null }

/** How a found word is marked in a message. */
val FOUND_MARK = SpanStyle(background = Color(0xFFF2C46D), color = Color(0xFF16181D))

/** This text with each place that says [words] marked, whatever its capitals. */
fun AnnotatedString.marking(words: String?): AnnotatedString {
    if (words.isNullOrEmpty()) return this
    var at = text.indexOf(words, ignoreCase = true)
    if (at < 0) return this
    return AnnotatedString.Builder(this).apply {
        while (at >= 0) {
            addStyle(FOUND_MARK, at, at + words.length)
            at = text.indexOf(words, at + words.length, ignoreCase = true)
        }
    }.toAnnotatedString()
}

/** True in the newest message with the words: Find brings the place into view, however long the message is. */
val LocalFindJump = compositionLocalOf { false }

/** A message's words, with the found ones marked. */
@Composable
fun FoundText(text: AnnotatedString, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    val words = LocalFound.current
    val jump = LocalFindJump.current
    val requester = remember { BringIntoViewRequester() }
    var place by remember(text, words) { mutableStateOf<Rect?>(null) }
    LaunchedEffect(place, jump) {
        val at = place.takeIf { jump } ?: return@LaunchedEffect
        // After the list has settled on its newest.
        repeat(2) { withFrameNanos { } }
        requester.bringIntoView(at)
    }
    Text(
        text.marking(words), style = style, color = color, modifier = modifier.bringIntoViewRequester(requester),
        onTextLayout = { layout ->
            val i = if (words.isNullOrEmpty()) -1 else text.text.lastIndexOf(words, ignoreCase = true)
            if (i >= 0 && i < layout.layoutInput.text.length) place = layout.getBoundingBox(i)
        },
    )
}
