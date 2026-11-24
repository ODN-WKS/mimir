package io.github.odnwks.mimir.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.odnwks.mimir.data.CheckState
import io.github.odnwks.mimir.data.Chip
import io.github.odnwks.mimir.data.count
import io.github.odnwks.mimir.data.Station
import io.github.odnwks.mimir.data.StationState
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.nav.Sheet
import io.github.odnwks.mimir.ui.theme.Palette
import io.github.odnwks.mimir.ui.theme.Type

/** A project's page (board 7): its checks and its tasks. */
@Composable
fun ProjectScreen(name: String, state: StationState, station: Station, nav: Navigator) {
    val p = state.project(name) ?: return MissingScreen(name, nav)
    val standIn = LocalStandIn.current
    val tap = LocalTapTracker.current
    val running = p.tasksRunning
    Column(Modifier.fillMaxSize().background(Palette.Ground)) {
        BackHeader(p.name, nav::back, backLabel = "Back to status",
            // The folder's full path means nothing on a phone: only a short description shows.
            subtitle = listOf(p.about.takeUnless { it.startsWith("/") || it.startsWith("~") }.orEmpty(),
                // While the PC is away the strip under the header says so; the header does not say it again.
                when { state.pcAway -> ""; running > 0 -> "${running} working"; else -> "nothing working" }).filter { it.isNotBlank() }.joinToString(" · "))
        ConnectionStrip(state, Modifier.padding(bottom = 6.dp))
        var refreshing by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
        androidx.compose.runtime.LaunchedEffect(refreshing) { if (refreshing) { kotlinx.coroutines.delay(1_200); refreshing = false } }
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(refreshing, { refreshing = true; station.refresh() }, Modifier.weight(1f).fillMaxWidth()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Checks only where the project has them (Stage 1, section 5).
            if (p.state != CheckState.None) Panel(spacing = 10.dp) {
                val title = when (p.state) {
                    CheckState.Fail -> "${p.total - p.passing} of ${p.total} checks failing"
                    CheckState.Pass -> "All ${p.total} checks passing"
                    CheckState.None -> "No checks yet"
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        when (p.state) { CheckState.Fail -> Glyphs.Warning; CheckState.Pass -> Glyphs.Check; CheckState.None -> Glyphs.Minus },
                        null, tint = p.state.color(), modifier = Modifier.size(20.dp),
                    )
                    SectionTitle(title, color = p.state.color())
                }
                if (p.checks.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    p.checks.forEach { c ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(if (c.ok) Glyphs.Check else Glyphs.Warning, if (c.ok) "Passing" else "Failing",
                                tint = if (c.ok) Palette.Green else Palette.Red, modifier = Modifier.padding(top = 1.dp).size(18.dp))
                            Text(c.name, style = Type.Label.copy(lineHeight = Type.Label.fontSize * 1.35f), color = if (c.ok) Palette.Text else Palette.RedSoft)
                        }
                    }
                }
                if (p.failingSince.isNotEmpty()) Text(p.failingSince, style = Type.Small, color = Palette.TextDim)
                val failing = state.needsYou.firstOrNull { it.kind == io.github.odnwks.mimir.data.NeedsKind.CheckFailing && it.project == p.name }
                if (p.state == CheckState.Fail && failing != null) {
                    BigButton("Ask to fix it", Look.Primary, { station.answer(failing.id, io.github.odnwks.mimir.data.Answer.AskClaudeToFix) }, height = 48.dp, fontSize = 15)
                }
            }
            SectionTitle("Tasks", Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp))
            if (p.tasks.isEmpty()) Text("No tasks yet. Type below to start one.", style = Type.Label, color = Palette.TextDim, modifier = Modifier.padding(horizontal = 4.dp))
            val undo = LocalUndo.current
            p.tasks.filter { it.taskId !in nav.hiddenTasks }.forEach { t ->
                val (fg, bg) = when (t.chip) {
                    Chip.Running -> Palette.Ground to Palette.BlueLight
                    Chip.Idea -> Palette.Text to Palette.Track
                    Chip.Done -> Palette.Ground to Palette.GreenText
                    Chip.Stopped -> Palette.Text to Palette.Track
                    Chip.StoppedUnexpectedly -> Palette.AmberInk to Palette.Amber
                    Chip.Waiting, Chip.Paused, Chip.Away, Chip.Archived -> Palette.Text to Palette.Track
                    Chip.Starting, Chip.Live -> Palette.Ground to Palette.BlueLight
                    Chip.NeedsYou -> stateColours(t.chip.label)
                }
                TaskRow(
                    t.taskId ?: t.name, t.name,
                    // The state shows once, in the tag; a line under the name only when it says more.
                    t.meta.takeIf { it.isNotBlank() && it != t.chip.label }, t.chip.label, fg, bg,
                    onOpen = t.taskId?.let { id -> { nav.open(Screen.Task(id), tap.last) } },
                    onArchive = {
                        t.taskId?.let { id ->
                            station.archiveTask(id)
                            undo(Undoable("Archived ${t.name}", undo = { station.unarchiveTask(id) }))
                        }
                    },
                    onDelete = {
                        val working = t.chip == Chip.Running || t.chip == Chip.Starting || t.chip == Chip.Live
                        t.taskId?.let { id -> if (working) standIn("Stop this task first, then delete it") else nav.openSheet(Sheet.DeleteTask(id)) }
                    },
                )
            }
        }
        }
        NeedsYouBar(state.needsYou.size, { nav.open(Screen.NeedsYou) }, Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp))
        // The composer, to this project: what's written starts a task here.
        var draft by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
        var problem by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
        if (problem.isNotEmpty()) Text(problem, style = Type.Small, color = Palette.AmberNote, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        val voice = rememberVoiceInput(station)
        VoicePanel(voice, heard = { draft = withHeard(draft, it) }, Modifier.padding(top = 4.dp))
        val attach = rememberAttach(bigPictures = true, pcReadsFiles = true, key = "project-${p.name}")
        var effort by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
        var starting by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
        val canOptions = station.canSetReplyOptions()
        Composer(
            draft, { draft = it },
            hint = if (state.pcAway) "Message Mímir (${state.waitsUntil})" else "New task in ${p.name}",
            label = "New task in ${p.name}", voice = voice, micLabel = "Talk (voice typing): speak a new task",
            onSend = {
                // The pictures are saved off the main thread; the box keeps its content until they are.
                if (!starting) {
                    starting = true
                    station.startTaskAsync(draft.trim(), p.name, incognito = false, pictures = attach.pictures.toList(), files = attach.files.toList(), effort = effort.ifEmpty { null }) { id ->
                        starting = false
                        if (id.isEmpty()) problem = station.startProblem().ifEmpty { "The task couldn't start." } + " Your words are kept here."
                        else { draft = ""; problem = ""; attach.clear(); effort = ""; nav.open(Screen.Task(id)) }
                    }
                }
            },
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 10.dp),
            chip = p.name, chipLabel = "To: ${p.name}",
            attach = attach.takeIf { station.canAttachToTasks() }, needsWords = true,
            optionsChip = ("Effort: " + TaskEffort.of(effort.ifEmpty { null }).let { if (it == TaskEffort.Usual) "usual" else it.words }).takeIf { canOptions },
            onOptions = { effort = nextEffort(effort) },
        )
    }
}
