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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.unit.sp
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

/**
 * The task screen's top: one row of about 48 dp (Back, the name on one line, the state chip, the ⋯
 * button) and one thin line under it with the live sign, the project and the model. Stop and Find
 * are in the ⋯ menu; while work runs the button carries a small red dot, so a person can see Stop is in there.
 */
@Composable
fun TaskTopBar(task: io.github.odnwks.mimir.data.Task, onBack: () -> Unit, onOptions: () -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Column(modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (io.github.odnwks.mimir.ui.adaptive.LocalPaneBack.current) IconTarget(Glyphs.Back, "Back", onBack)
            else androidx.compose.foundation.layout.Spacer(Modifier.width(12.dp))
            // The name: one line, cut at a whole word with "…", never wrapped. A tap opens the options.
            OneLineTitle(
                task.title, Type.Label.copy(fontWeight = FontWeight.SemiBold),
                Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button, onClickLabel = "Task options", onClick = onOptions)
                    .semantics { heading() }.padding(horizontal = 8.dp),
            )
            val (fg, bg) = stateColours(task.stateWords)
            SmallTag(task.stateWords, fg, bg, Modifier.padding(end = 2.dp))
            val hasStop = task.showsStop && !task.stopPending
            androidx.compose.foundation.layout.Box(
                Modifier.size(48.dp).clip(androidx.compose.foundation.shape.CircleShape)
                    .clickable(role = Role.Button, onClickLabel = null, onClick = onOptions)
                    .semantics { contentDescription = TASK_OPTIONS; if (hasStop) stateDescription = "with Stop" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Glyphs.More, contentDescription = null, tint = Palette.Text, modifier = Modifier.size(24.dp))
                if (hasStop) androidx.compose.foundation.layout.Box(
                    Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 10.dp).size(8.dp)
                        .background(Palette.Red, androidx.compose.foundation.shape.CircleShape),
                )
            }
        }
        TaskSignLine(task, Modifier.padding(start = 16.dp, end = 16.dp))
    }
}

/** The thin line under the top row: the live sign (and its seconds), then project and model; one line, dim, cut with "…". */
@Composable
private fun TaskSignLine(task: io.github.odnwks.mimir.data.Task, modifier: Modifier) {
    var now by androidx.compose.runtime.remember { androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis()) }
    androidx.compose.runtime.LaunchedEffect(task.sign) { while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(1000) } }
    val shown = task.sign?.shown(now)
    val words = shown?.words ?: task.stateLine.takeIf { task.stateWords == "Working" && it != "Working on it" }.orEmpty()
    val timer = shown?.timer.orEmpty().trim().removePrefix("·").trim()
    val rest = listOf(task.project, if (task.plan) "Plan first" else "", task.model).filter { it.isNotBlank() }.joinToString(" · ")
    if (words.isEmpty() && timer.isEmpty() && rest.isEmpty() && task.riskyMode == null) return
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // A mode that doesn't ask first is always in sight.
        task.riskyMode?.let { if (task.yolo) SmallTag(it, Palette.Ground, Palette.Red, Modifier.padding(end = 6.dp)) else SmallTag(it, Palette.AmberInk, Palette.Amber, Modifier.padding(end = 6.dp)) }
        if (words.isNotEmpty()) Text(
            words, style = Type.Small, color = Palette.TextSoft, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
        )
        if (timer.isNotEmpty()) Text(" · $timer", style = Type.Small, color = Palette.TextSoft, maxLines = 1)
        if (rest.isNotEmpty()) Text(
            (if (words.isNotEmpty() || timer.isNotEmpty()) " · " else "") + rest, style = Type.Small, color = Palette.TextDim,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
        )
    }
}

/** The state chip in its small form. */
@Composable
fun SmallTag(text: String, fg: androidx.compose.ui.graphics.Color, bg: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    Text(
        text, style = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold), color = fg,
        maxLines = 1, softWrap = false,
        modifier = modifier.clip(RoundedCornerShape(10.dp)).background(bg).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** [text] on one line: cut after a whole word with "…" when it does not fit (a single long word is cut by the ellipsis). */
@Composable
fun OneLineTitle(text: String, style: androidx.compose.ui.text.TextStyle, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier, contentAlignment = Alignment.CenterStart) {
        val measurer = androidx.compose.ui.text.rememberTextMeasurer()
        val maxPx = constraints.maxWidth
        val fits = { t: String -> measurer.measure(t, style, maxLines = 1, softWrap = false).size.width <= maxPx }
        val shown = androidx.compose.runtime.remember(text, style, maxPx) { cutAtWord(text, fits) }
        Text(shown, style = style, color = Palette.Text, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
    }
}

/** The longest run of whole words of [text] that fits with "…" after it; the text itself when it fits. */
internal fun cutAtWord(text: String, fits: (String) -> Boolean): String {
    if (fits(text)) return text
    val words = text.trim().split(' ').filter { it.isNotEmpty() }
    for (n in words.size - 1 downTo 1) {
        val t = words.take(n).joinToString(" ") + "…"
        if (fits(t)) return t
    }
    return text // not even one whole word fits: the Text's own ellipsis cuts it
}
