package io.github.odnwks.mimir.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.odnwks.mimir.data.STATUS_FINISHED_SHOWN
import io.github.odnwks.mimir.data.Station
import io.github.odnwks.mimir.data.StatusAsk
import io.github.odnwks.mimir.data.StatusCards
import io.github.odnwks.mimir.data.StatusNeed
import io.github.odnwks.mimir.data.StatusRow
import io.github.odnwks.mimir.data.StatusSnapshot
import io.github.odnwks.mimir.data.statusSnapshot
import io.github.odnwks.mimir.ui.theme.Palette
import io.github.odnwks.mimir.ui.theme.Type

const val STATUS_CARD_TAG = "status-card"

/** How long the PC gets to send fresh facts after an ask or Update before the card takes them in. */
private const val FRESH_MS = 1_500L

/** Shows the card for [chat] (false: this phone has no PC to ask about, so the words go on as a message). */
fun askStatus(chat: String, after: String, station: Station?): Boolean {
    val state = station?.state?.value?.takeIf { it.paired } ?: return false
    station.refresh()
    StatusCards.ask(chat, after, statusSnapshot(state, System.currentTimeMillis()))
    return true
}

/** The card as it sits in a chat. The facts it asked the PC for come in once; after that only Update changes it. */
@Composable
fun StatusCardInChat(card: StatusAsk, station: Station?, modifier: Modifier = Modifier, onOpen: (String) -> Unit) {
    fun take() { station?.state?.value?.let { StatusCards.update(statusSnapshot(it, System.currentTimeMillis())) } }
    val tick = androidx.compose.runtime.remember(card.asked) { androidx.compose.runtime.mutableIntStateOf(0) }
    LaunchedEffect(card.asked, tick.intValue) {
        // An old card coming back into view keeps its facts: only a new ask or Update takes new ones.
        if (tick.intValue > 0 || StatusCards.fresh == card.asked) {
            kotlinx.coroutines.delay(FRESH_MS)
            take()
            StatusCards.fresh = 0
        }
    }
    StatusCardView(card.snapshot, modifier, onOpen, onUpdate = { station?.refresh(); take(); tick.intValue++ })
}

@Composable
fun StatusCardView(s: StatusSnapshot, modifier: Modifier = Modifier, onOpen: (String) -> Unit, onUpdate: () -> Unit) {
    Panel(modifier.testTag(STATUS_CARD_TAG), background = Palette.BlueCard, border = Palette.BlueBorder, spacing = 6.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Status", style = Type.SheetTitle, color = Palette.Text, modifier = Modifier.weight(1f).semantics { heading() })
            if (s.asOfMs > 0) Text("as of ${messageTime(s.asOfMs)}", style = Type.Small, color = Palette.TextDim)
        }
        if (s.away) Text(
            if (s.asOfMs > 0) "Can't reach your PC. This is what it said last; it updates once the PC connects." else "Can't reach your PC yet. It shows here once the PC connects.",
            style = Type.Label, color = Palette.AmberNote,
        )
        Section(if (s.away) "RUNNING THEN" else "RUNNING", s.running.size)
        if (s.running.isEmpty()) Text("Nothing is running.", style = Type.Label, color = Palette.TextSoft)
        s.running.forEach { r -> Job(r, finished = false, onOpen) }
        Section("NEEDS YOU", s.needsYou.size)
        if (s.needsYou.isEmpty()) Text("Nothing.", style = Type.Label, color = Palette.TextSoft)
        s.needsYou.forEach { n -> Need(n, onOpen) }
        if (s.finished.isNotEmpty()) {
            Section("FINISHED TODAY", s.finished.size)
            s.finished.take(STATUS_FINISHED_SHOWN).forEach { r -> Job(r, finished = true, onOpen) }
            val more = s.finished.size - STATUS_FINISHED_SHOWN
            if (more > 0) Text("and $more more", style = Type.Small, color = Palette.TextDim)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Made by Mímir. The AI never reads it.", style = Type.Small, color = Palette.TextDim, modifier = Modifier.weight(1f))
            LinkText("Update", onUpdate, bold = true)
        }
    }
}

@Composable
private fun Section(name: String, count: Int) {
    Text(
        if (count > 0) "$name · $count" else name, style = Type.Small.copy(fontWeight = FontWeight.Bold), color = Palette.TextDim,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun Job(r: StatusRow, finished: Boolean, onOpen: (String) -> Unit) {
    val open = r.taskId?.let { id -> Modifier.clickableRow("Open ${r.title}") { onOpen(id) } } ?: Modifier
    Column(Modifier.fillMaxWidth().then(open), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(r.title, style = Type.Label.copy(fontWeight = FontWeight.SemiBold), color = Palette.Text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(
                if (finished) "done" else r.percent?.let { "$it%" } ?: if (r.timeUnix > 0 || !r.where.startsWith("Not started yet")) "working" else "waiting",
                style = Type.Label, color = if (finished) Palette.GreenText else Palette.BlueLight,
            )
        }
        val time = if (r.timeUnix > 0) (if (finished) "finished " else "started ") + messageTime(r.timeUnix * 1000) else ""
        Text(listOf(r.where, time, r.step).filter { it.isNotEmpty() }.joinToString(" · "), style = Type.Small, color = Palette.TextDim)
        if (r.next.isNotEmpty()) Text("Next: ${r.next}", style = Type.Small, color = Palette.TextSoft, maxLines = 2, overflow = TextOverflow.Ellipsis)
        r.percent?.let { p ->
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Palette.Track)) {
                Box(Modifier.fillMaxWidth(p / 100f).fillMaxHeight().background(Palette.BlueLight))
            }
        }
    }
}

@Composable
private fun Need(n: StatusNeed, onOpen: (String) -> Unit) {
    val id = n.taskId
    Panel(
        background = Palette.AmberCard, border = Palette.AmberBorder, radius = 10.dp, padding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        onClick = id?.let { { onOpen(it) } }, onClickLabel = id?.let { "Open it" },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(n.words, style = Type.Label, color = Palette.AmberSoft, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (id != null) Text("Open ›", style = Type.Label.copy(fontWeight = FontWeight.SemiBold), color = Palette.AmberText)
        }
    }
}

private fun Modifier.clickableRow(label: String, onClick: () -> Unit): Modifier =
    this.clickable(onClickLabel = label, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
