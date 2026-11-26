package io.github.odnwks.mimir.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.odnwks.mimir.data.Question
import io.github.odnwks.mimir.ui.theme.Palette
import io.github.odnwks.mimir.ui.theme.Type

/**
 * What the person sent, without the PC's file path: the message's words, and the names of what was
 * attached ("Attached: /home/.../task-files/t-1/1-plan.pdf, ..." is how the PC tells Claude Code
 * where the files are; it means nothing on a phone). Same rule as the PC window's.
 */
object AttachedNames {
    private const val TAIL = "Attached: /"
    private const val LOCAL_ONLY = "local-only-"

    private val PICTURE_LINE = Regex("""\n\[\d+ pictures? attached]\s*$""")

    fun split(full: String): Pair<String, List<String>> {
        // The PC adds "[N picture(s) attached]" after the path line; the chips say it already.
        val text = full.replace(PICTURE_LINE, "")
        val at = text.lastIndexOf(TAIL)
        if (at < 0) return full to emptyList()
        if (at != 0 && !text.substring(0, at).endsWith("\n\n")) return full to emptyList()
        val tail = text.substring(at + "Attached: ".length)
        if (tail.contains('\n')) return full to emptyList()
        val names = tail.split(", /").map { p ->
            val file = p.trimStart('/').substringAfterLast('/')
            val digits = file.substringBefore('-', "")
            val bare = if (digits.isNotEmpty() && digits.all { it.isDigit() }) file.substringAfter('-') else file
            bare.removePrefix(LOCAL_ONLY)
        }
        return text.substring(0, at).trimEnd() to names
    }
}

/** Runs [content] with the words and, when something was attached, a [chips] row to show under them. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AttachedAware(text: String, content: @Composable (shown: String, chips: (@Composable () -> Unit)?) -> Unit) {
    val (shown, names) = AttachedNames.split(text)
    content(shown, if (names.isEmpty()) null else ({ AttachedChips(names) }))
}

/** One small named chip for each attached file or picture. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AttachedChips(names: List<String>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        names.forEach { n ->
            Row(
                Modifier.clip(RoundedCornerShape(10.dp)).background(Palette.Bar).border(1.dp, Palette.Line, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp).semantics(mergeDescendants = true) { contentDescription = "Sent with this message: $n" },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Glyphs.Document, null, tint = Palette.TextDim, modifier = Modifier.size(14.dp))
                Text(n, style = Type.Small, color = Palette.TextSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** The question in a few words: what Claude asked, shortened to one line's worth (the whole thing is a tap away). */
fun questionQuote(q: Question, lastAnswer: String?): String {
    val own = markdownPlain(q.text).trim()
    val from = own.ifBlank {
        markdownPlain(lastAnswer.orEmpty()).lines().map { it.trim() }.lastOrNull { it.isNotEmpty() }.orEmpty()
    }.replace(Regex("\\s+"), " ")
    // The question itself: the last sentence that ends in "?" (the rest is the lead-in, and the
    // one-line quote would cut the question off instead). No "?" at all: the whole text.
    val sentences = Regex("[^.!?]*\\?").findAll(from).map { it.value.trim() }.filter { it.length > 1 }.toList()
    val ask = sentences.lastOrNull() ?: from
    // Too long for one line: keep the end, where the question is.
    return if (ask.length > QUOTE_MAX) "…" + ask.takeLast(QUOTE_MAX - 1).trimStart() else ask
}

private const val QUOTE_MAX = 90

/**
 * "A question for you", slim: one line above the reply box that quotes the question; a tap shows
 * it whole with Claude's offered answers. It goes away when the person replies ([TaskScreen] hides
 * it for that question) or the question is gone.
 */
@Composable
fun QuestionLine(
    q: Question, quote: String, onChoice: (Int) -> Unit, onYes: () -> Unit, onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by rememberSaveable(q.itemId) { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier.padding(horizontal = 12.dp).padding(bottom = 4.dp).fillMaxWidth().clip(shape).background(Palette.AmberCard)
            .border(1.dp, Palette.AmberBorder, shape).animateContentSize(),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(role = Role.Button, onClickLabel = if (open) "Hide the question" else "Show the whole question") { open = !open }
                .semantics(mergeDescendants = true) { stateDescription = if (open) "Shown" else "Hidden" }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Glyphs.Question, null, tint = Palette.AmberText, modifier = Modifier.size(18.dp))
            Text(
                "“$quote”", style = Type.Small, color = Palette.AmberSoft, modifier = Modifier.weight(1f),
                maxLines = if (open) 8 else 1, overflow = TextOverflow.Ellipsis,
            )
            Text(if (open) "Hide" else "Show", style = Type.Small.copy(fontWeight = FontWeight.SemiBold), color = Palette.AmberText)
        }
        if (open) Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Answer in the box below, or tap one.", style = Type.Small, color = Palette.TextDim)
            q.choices.forEachIndexed { i, c -> BigButton(c, Look.AmberOutline, { onChoice(i) }, height = 44.dp, fontSize = 14) }
            if (q.yes.isNotEmpty()) BigButton(q.yes, Look.Amber, onYes, height = 44.dp, fontSize = 14)
            LinkText("Play the question", onPlay)
        }
    }
}
