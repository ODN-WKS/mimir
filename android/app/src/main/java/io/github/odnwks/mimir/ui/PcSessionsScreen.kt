package io.github.odnwks.mimir.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.odnwks.mimir.data.PcSession
import io.github.odnwks.mimir.data.PcSessionState
import io.github.odnwks.mimir.data.Station
import io.github.odnwks.mimir.data.StationState
import io.github.odnwks.mimir.data.pcAgo
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.ui.theme.Palette
import io.github.odnwks.mimir.ui.theme.Type

const val PC_RUNNING_TAB = "Running"
const val PC_ALL_TAB = "All"
const val PC_SETTINGS_ROW = "PC settings"
const val PC_SETTINGS_HINT = "name, reach from anywhere"
const val PC_NOTHING_RUNNING = "Nothing is running on your PC right now."
const val PC_NOTHING_YET = "Nothing here yet"
/** How often the times in the rows ("2m", "1h") are worked out again while the screen is open. */
const val PC_TIME_REFRESH_MS = 30_000L

/** Under the tabs while the PC is away: the list is from earlier, so nothing in it is "running". */
fun pcLastSeenLine(lastSeen: String) =
    if (lastSeen.isBlank() || lastSeen == "not connected" || lastSeen == "now") "Your PC is away. This list is from earlier." else "Your PC is away. Last seen $lastSeen."

/** The tab's label: "Running · 2". */
fun pcRunningLabel(n: Int) = "$PC_RUNNING_TAB · $n"

/** The dim line under Running: how many others moved earlier today ("2 more from earlier today"). */
fun pcMoreLine(n: Int) = "$n more from earlier today · "

/** What Running lists (the working ones) and how many other sessions moved since [dayStartUnix]. */
fun pcRunningSplit(all: List<PcSession>, dayStartUnix: Long): Pair<List<PcSession>, Int> =
    all.filter { it.state == PcSessionState.Working } to all.count { it.state != PcSessionState.Working && it.lastUnix >= dayStartUnix }

private fun startOfToday(nowUnix: Long): Long {
    val c = java.util.Calendar.getInstance().apply { timeInMillis = nowUnix * 1000; set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0) }
    return c.timeInMillis / 1000
}

/**
 * Your PC, on a PC that lists its Claude Code sessions: every one, wherever it was started. Running
 * shows the working ones; All adds the last 30. A tap opens one as a task. The PC's own settings sit
 * behind one row at the bottom.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PcSessionsScreen(
    state: StationState, station: Station, nav: Navigator, nowUnix: Long? = null,
    clock: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    var tick by remember { mutableLongStateOf(clock()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(PC_TIME_REFRESH_MS); tick = clock() } }
    val now = nowUnix ?: tick
    val away = !state.pc.online
    // One open at a time: a second tap while the PC is still answering would stack a second screen.
    var opening by remember { mutableStateOf(false) }
    val sessions = state.pcSessions.orEmpty()
    val (working, earlier) = pcRunningSplit(sessions, startOfToday(now))
    var showAll by rememberSaveable { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(refreshing) { if (refreshing) { kotlinx.coroutines.delay(900); refreshing = false } }
    Column(Modifier.fillMaxSize().background(Palette.Ground)) {
        BackHeader("Your PC", nav::back, backLabel = "Back")
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Dot(if (state.pc.online) Palette.Green else Palette.TextDim, hollow = !state.pc.online)
            Text(
                "  " + if (state.pc.online) "Connected" else state.connectionLine,
                style = Type.Small, color = Palette.TextDim,
            )
        }
        // Under the connection line, where it is seen first (not below a long list).
        Row(
            Modifier.fillMaxWidth().background(Palette.Ground).clickable(role = Role.Button) { nav.open(Screen.PcSettings) }.heightIn(min = 52.dp).padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(PC_SETTINGS_ROW, style = Type.BodyTight, color = Palette.Text, maxLines = 1, softWrap = false)
            Spacer(Modifier.width(12.dp))
            // The hint takes what is left on one line (cut with "…"), the arrow stays at the right edge.
            Text(PC_SETTINGS_HINT, style = Type.Small, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.weight(1f))
            Text(" ›", style = Type.Small, color = Palette.TextDim, maxLines = 1, softWrap = false)
        }
        Row(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.Card).border(1.dp, Palette.Line, RoundedCornerShape(14.dp)).padding(3.dp),
        ) {
            Seg(if (away) PC_RUNNING_TAB else pcRunningLabel(working.size), !showAll, Modifier.weight(1f)) { showAll = false }
            Seg(PC_ALL_TAB, showAll, Modifier.weight(1f)) { showAll = true }
        }
        Spacer(Modifier.size(8.dp))
        PullToRefreshBox(refreshing, { refreshing = true; station.refreshPcSessions() }, Modifier.weight(1f).fillMaxWidth()) {
            val shown = if (showAll) sessions else working
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                if (away) item("away") { Text(pcLastSeenLine(state.pc.lastSeen), style = Type.Small, color = Palette.TextDim, modifier = Modifier.padding(top = 4.dp, start = 6.dp, bottom = 4.dp)) }
                items(shown, key = { it.id }) { s -> SessionRow(s, now, away) { if (!opening) { opening = true; open(station, nav, s) { opening = false } } } }
                if (showAll && sessions.isEmpty()) item("empty") { Text(PC_NOTHING_YET, style = Type.Small, color = Palette.TextDim, modifier = Modifier.padding(top = 12.dp, start = 6.dp)) }
                if (!showAll && !away) {
                    if (working.isEmpty()) item("none") { Text(PC_NOTHING_RUNNING, style = Type.Small, color = Palette.TextDim, modifier = Modifier.padding(top = 12.dp, start = 6.dp)) }
                    if (earlier > 0) item("more") {
                        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.Center) {
                            Text(pcMoreLine(earlier), style = Type.Small, color = Palette.TextDim)
                            Text(PC_ALL_TAB, style = Type.Small, color = Palette.BlueLight, modifier = Modifier.clickable(role = Role.Button) { showAll = true })
                        }
                    }
                }
            }
        }
        NeedsYouBar(state.needsYou.size, { nav.open(Screen.NeedsYou) }, Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 4.dp))
    }
}

/** Opens a session: an idle or ended one becomes a task, one working elsewhere is followed live. */
private fun open(station: Station, nav: Navigator, s: PcSession, finished: () -> Unit) {
    station.openPcSession(s.id) { id ->
        android.os.Handler(android.os.Looper.getMainLooper()).post { finished(); if (id != null) nav.open(Screen.Task(id)) }
    }
}

@Composable
private fun Seg(text: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(11.dp)).background(if (on) Palette.Track else Palette.Card)
            .clickable(role = Role.Tab, onClick = onClick).semantics { selected = on },
        contentAlignment = Alignment.Center,
    ) { Text(text, style = Type.Small, color = if (on) Palette.Text else Palette.TextDim, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal) }
}

@Composable
private fun SessionRow(s: PcSession, nowUnix: Long, away: Boolean, onClick: () -> Unit) {
    // While the PC is away the row is from earlier: no green dot, dimmed.
    val working = s.state == PcSessionState.Working && !away
    val time = pcAgo(s.lastUnix, nowUnix)
    Row(
        Modifier.fillMaxWidth().alpha(if (away) 0.55f else 1f).clickable(role = Role.Button, onClick = onClick).heightIn(min = 56.dp).padding(horizontal = 6.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) { contentDescription = "${s.title}, ${s.line}, ${if (working) "working now" else time}" },
        horizontalArrangement = Arrangement.spacedBy(11.dp), verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.padding(top = 7.dp)) { Dot(if (working) Palette.Green else Palette.Outline, hollow = !working) }
        Column(Modifier.weight(1f)) {
            Text(s.title, style = Type.BodyTight, color = Palette.Text, maxLines = 2)
            Text(s.line, style = Type.Small, color = Palette.TextDim, maxLines = 1)
        }
        Text(time, style = Type.Small, color = Palette.TextDim, maxLines = 1)
    }
}
