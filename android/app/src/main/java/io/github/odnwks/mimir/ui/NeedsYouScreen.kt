package io.github.odnwks.mimir.ui

import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.odnwks.mimir.data.Answer
import io.github.odnwks.mimir.data.NeedsKind
import io.github.odnwks.mimir.data.NeedsYouItem
import io.github.odnwks.mimir.data.Station
import io.github.odnwks.mimir.data.StationState
import io.github.odnwks.mimir.data.count
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.ui.theme.Motion
import io.github.odnwks.mimir.ui.theme.Palette
import io.github.odnwks.mimir.ui.theme.Type
import io.github.odnwks.mimir.ui.theme.motionSpec

private data class KindLook(val bg: Color, val border: Color, val accent: Color, val body: Color, val icon: ImageVector)

private fun NeedsKind.look() = when (this) {
    NeedsKind.Permission -> KindLook(Palette.BlueCard, Palette.BlueBorder, Palette.BlueText, Palette.Text, Glyphs.Lock)
    NeedsKind.Question -> KindLook(Palette.AmberCard, Palette.AmberBorder, Palette.AmberText, Palette.AmberSoft, Glyphs.Question)
    NeedsKind.CheckFix -> KindLook(Palette.GreenCard, Palette.GreenBorder, Palette.GreenText, Palette.Text, Glyphs.Check)
    NeedsKind.Approve -> KindLook(Palette.Card, Palette.LineStrong, Palette.Purple, Palette.Text, Glyphs.Document)
    NeedsKind.Choose -> KindLook(Palette.Card, Palette.LineStrong, Palette.BlueText, Palette.Text, Glyphs.Question)
    NeedsKind.Release -> KindLook(Palette.AmberCard, Palette.AmberBorder, Palette.AmberText, Palette.AmberSoft, Glyphs.Document)
    NeedsKind.CheckFailing -> KindLook(Palette.Card, Palette.LineStrong, Palette.AmberText, Palette.Text, Glyphs.Question)
}

/** One answer button on a question card: its words, and what it does when tapped. */
class AskButton(val label: String, val act: () -> Unit)

/** "waiting 12 min" (or "waiting just now"), from the item's age. */
fun waitingWords(age: String): String = when {
    age.isBlank() -> "waiting"
    age == "now" -> "waiting just now"
    else -> "waiting $age"
}

/**
 * The answers a card offers, each in words that say what happens. Where the PC gives its own
 * choices those are used; the first is the main one.
 */
fun askButtons(item: NeedsYouItem, answer: (Answer) -> Unit, choose: (Int) -> Unit, release: () -> Unit = { answer(Answer.Release) }): List<AskButton> = when (item.kind) {
    NeedsKind.CheckFix -> listOf(AskButton("It works") { answer(Answer.ItWorks) }, AskButton("Still broken") { answer(Answer.StillBroken) })
    NeedsKind.Release -> listOf(AskButton("Release") { release() }, AskButton("Keep for later") { answer(Answer.KeepForLater) })
    NeedsKind.CheckFailing -> listOf(AskButton("Ask to fix it") { answer(Answer.AskClaudeToFix) }, AskButton("Keep for later") { answer(Answer.KeepForLater) })
    NeedsKind.Approve -> item.choices.take(1).map { AskButton(it) { answer(Answer.Approve) } }
    NeedsKind.Permission -> when {
        // Allow, Don't allow, Always allow, Answer on the PC: Always allow writes a lasting rule, so it is given only on the PC.
        item.choices.size == 4 -> listOf(AskButton(item.choices[0]) { choose(0) }, AskButton(item.choices[1]) { choose(1) }, AskButton(item.choices[3]) { choose(3) })
        item.choices.isNotEmpty() -> item.choices.mapIndexed { i, c -> AskButton(c) { choose(i) } }
        else -> listOf(AskButton("Allow") { answer(Answer.Allow) }, AskButton("Don't allow") { answer(Answer.DontAllow) })
    }
    else -> item.choices.mapIndexed { i, c -> AskButton(c) { choose(i) } }
}

/** Needs you (board 6): one amber card per question with buttons that say what happens, then what works without you. */
@Composable
fun NeedsYouScreen(state: StationState, station: Station, nav: Navigator) {
    val tap = LocalTapTracker.current
    Column(Modifier.fillMaxSize().background(Palette.Ground)) {
        CenteredTitleBar("Needs you", nav)
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Answered while the PC was away: said plainly, so an answer never just vanishes.
            if (state.answersWaiting > 0) item(key = "held") {
                Text(
                    "${count(state.answersWaiting, "answer")} waiting on this phone · " +
                        (if (state.phoneOnline) "sent when your PC is back" else "sent when this phone is back online"),
                    style = Type.Label, color = Palette.AmberNote,
                    modifier = Modifier.padding(4.dp).semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            val working = state.tasks.filter { it.stateWords == "Working" || it.stateWords == "Starting" }
            if (!state.paired) item(key = "none") {
                EmptyState("Questions for you show up here when Mímir needs an answer on your PC.", action = "Connect your PC", onAction = { nav.open(Screen.Pair) })
            } else if (state.needsYou.isEmpty()) item(key = "none") {
                Text(
                    "Nothing needs you right now.", style = Type.Body, color = Palette.TextSoft, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp).semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            items(state.needsYou, key = { it.id }) { item ->
                val undo = LocalUndo.current
                AskCard(
                    item,
                    Modifier.animateItem(motionSpec(Motion.NEW_ITEM_MS), motionSpec(Motion.NEW_ITEM_MS), motionSpec(Motion.NEW_ITEM_MS)),
                    buttons = askButtons(item, { station.answer(item.id, it) }, { station.answerChoice(item.id, it) },
                        release = { undo(Undoable("Releasing · ${item.project}", later = { station.answer(item.id, Answer.Release) })) }),
                    sayMore = item.taskId?.let { id -> { nav.open(Screen.Task(id), tap.last) } },
                )
            }
            if (state.paired && working.isNotEmpty()) {
                item(key = "workhead") { CapsLabel("WORKING WITHOUT YOU", Modifier.padding(top = 10.dp)) }
                item(key = "working") {
                    Column(Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).background(Palette.Card).border16(Palette.Line)) {
                        working.forEachIndexed { i, t ->
                            if (i > 0) androidx.compose.foundation.layout.Spacer(Modifier.fillMaxWidth().padding(horizontal = 14.dp).heightIn(min = 1.dp).background(Palette.Line))
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = androidx.compose.ui.semantics.Role.Button) { nav.open(Screen.Task(t.id), tap.last) }
                                    .semantics(mergeDescendants = true) { contentDescription = t.title + ", " + startedWords(t.createdUnix) + ", going well" }
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(t.title, style = Type.BodyTight, color = Palette.Text, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                    Text("${startedWords(t.createdUnix)} · going well", style = Type.Small, color = Palette.TextDim)
                                }
                                Icon(Glyphs.Next, null, tint = Palette.TextDim, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

private val AskFill = Color(0xFF241D10)
private val AskBorder = Color(0xFF6B5420)

/** One question: the amber line (project and how long), the question, its buttons, and "Say something else…". */
@Composable
fun AskCard(item: NeedsYouItem, modifier: Modifier, buttons: List<AskButton>, sayMore: (() -> Unit)?) {
    Panel(
        modifier.semantics { contentDescription = "${item.kind.label}, ${item.project}" },
        background = AskFill, border = AskBorder, radius = 18.dp, spacing = 10.dp,
    ) {
        Text("${item.project} · ${waitingWords(item.age)}", style = Type.Small, color = Palette.AmberText)
        MarkdownText(item.text, style = Type.BodyTight.copy(fontSize = 15.5.sp), color = Palette.AmberSoft)
        if (buttons.isNotEmpty()) {
            val side = buttons.size == 2
            if (side) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                buttons.forEachIndexed { i, b -> BigButton(b.label, if (i == 0) Look.Primary else Look.Outline, b.act, Modifier.weight(1f), height = 48.dp, fontSize = 15) }
            } else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                buttons.forEachIndexed { i, b -> BigButton(b.label, if (i == 0) Look.Primary else Look.Outline, b.act, height = 48.dp, fontSize = 15) }
            }
        }
        if (sayMore != null) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { LinkText("Say something else…", sayMore, color = Palette.TextDim) }
    }
}

@Composable
fun NeedsCard(item: NeedsYouItem, modifier: Modifier, open: () -> Unit, answer: (Answer) -> Unit, choose: (Int) -> Unit = {}) {
    val look = item.kind.look()
    val head = "${item.kind.label} · ${item.project} · ${item.age}"
    // Items from a task open it; the others are answered right here.
    val inline = item.taskId == null && (item.kind != NeedsKind.Approve || item.choices.isNotEmpty())
    Panel(
        modifier.then(if (inline) Modifier.semantics { contentDescription = "${item.kind.label}, ${item.project}" } else Modifier),
        background = look.bg, border = look.border,
        onClick = if (inline) null else open,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(look.icon, null, tint = look.accent, modifier = Modifier.size(18.dp))
            Text(head, style = Type.Small.copy(fontWeight = FontWeight.SemiBold), color = look.accent)
        }
        if (item.kind == NeedsKind.Question) MarkdownText(item.text, style = Type.BodyTight, color = look.body)
        else Text(item.text, style = Type.BodyTight, color = look.body)
        if (inline) when (item.kind) {
            NeedsKind.CheckFix -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BigButton("It works", Look.Primary, { answer(Answer.ItWorks) }, Modifier.weight(1f), height = 48.dp, fontSize = 15)
                BigButton("Still broken", Look.Outline, { answer(Answer.StillBroken) }, Modifier.weight(1f), height = 48.dp, fontSize = 15)
            }
            NeedsKind.Release -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val undo = LocalUndo.current
                BigButton("Release", Look.Amber, { undo(Undoable("Releasing · ${item.project}", later = { answer(Answer.Release) })) }, Modifier.weight(1f), height = 48.dp, fontSize = 15)
                BigButton("Keep for later", Look.AmberOutline, { answer(Answer.KeepForLater) }, Modifier.weight(1f), height = 48.dp, fontSize = 15)
            }
            NeedsKind.CheckFailing -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BigButton("Ask to fix it", Look.Primary, { answer(Answer.AskClaudeToFix) }, height = 48.dp, fontSize = 15)
                BigButton("Keep for later", Look.Outline, { answer(Answer.KeepForLater) }, height = 48.dp, fontSize = 15)
            }
            NeedsKind.Approve -> BigButton(item.choices.first(), Look.Primary, { answer(Answer.Approve) }, height = 48.dp, fontSize = 15)
            // Another Claude session on the PC asks (a terminal, claude.ai): the PC sends
            // Allow, Don't allow, Always allow, Answer on the PC, answered by their number.
            // Always allow writes a lasting rule, so it is given only on the PC.
            NeedsKind.Permission -> if (item.choices.size == 4) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // The same order as the task's dock: Don't allow on the left, Allow on the right.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BigButton(item.choices[1], Look.Outline, { choose(1) }, Modifier.weight(1f), height = 48.dp, fontSize = 15)
                    BigButton(item.choices[0], Look.Primary, { choose(0) }, Modifier.weight(1f), height = 48.dp, fontSize = 15)
                }
                BigButton(item.choices[3], Look.Outline, { choose(3) }, height = 48.dp, fontSize = 15)
            } else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item.choices.forEachIndexed { i, c ->
                    BigButton(c, if (i == 0) Look.Primary else Look.Outline, { choose(i) }, height = 48.dp, fontSize = 15)
                }
            }
            else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item.choices.forEachIndexed { i, c ->
                    BigButton(c, if (i == 0) Look.Primary else Look.Outline, { choose(i) }, height = 48.dp, fontSize = 15)
                }
            }
        } else if (item.kind == NeedsKind.Permission) {
            // Answered right here; the card still opens the task for the details.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // The same order as the task's dock: Don't allow on the left, Allow on the right.
                BigButton("Don't allow", Look.Outline, { answer(Answer.DontAllow) }, Modifier.weight(1f), height = 48.dp, fontSize = 15)
                BigButton("Allow", Look.Primary, { answer(Answer.Allow) }, Modifier.weight(1f), height = 48.dp, fontSize = 15)
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Open the task", style = Type.Label.copy(fontWeight = FontWeight.SemiBold), color = look.accent, modifier = Modifier.weight(1f))
                Icon(Glyphs.Next, null, tint = look.accent, modifier = Modifier.size(20.dp))
            }
        } else {
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (item.kind == NeedsKind.Approve) "Read it" else "Open the task",
                    style = Type.Label.copy(fontWeight = FontWeight.SemiBold), color = look.accent, modifier = Modifier.weight(1f),
                )
                Icon(Glyphs.Next, null, tint = look.accent, modifier = Modifier.size(20.dp))
            }
        }
    }
}
