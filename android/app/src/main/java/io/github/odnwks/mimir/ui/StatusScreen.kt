package io.github.odnwks.mimir.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.selection.selectable
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import io.github.odnwks.mimir.data.CheckState
import io.github.odnwks.mimir.data.Project
import io.github.odnwks.mimir.data.StationState
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.ui.theme.LocalReduceMotion
import io.github.odnwks.mimir.ui.theme.Motion
import io.github.odnwks.mimir.ui.theme.Palette
import io.github.odnwks.mimir.ui.theme.Type

fun CheckState.color() = when (this) {
    CheckState.Pass -> Palette.Green
    CheckState.Fail -> Palette.Red
    CheckState.None -> Palette.TextDim
}


/** The serif title the redesigned screens share (about 21 sp, centred). */
val ScreenTitleStyle = Type.PageTitle

/**
 * The top bar of Needs you, Updates and Your PC: a centred serif title, with the menu button when
 * the screen is one of the menu's own places, else a back arrow (left out in a pane with nothing to go back to).
 */
@Composable
fun CenteredTitleBar(title: String, nav: Navigator) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        when {
            nav.current.tabPage -> MenuButton()
            io.github.odnwks.mimir.ui.adaptive.LocalPaneBack.current -> IconTarget(Glyphs.Back, "Back", nav::back)
            else -> Spacer(Modifier.size(48.dp))
        }
        Text(title, style = ScreenTitleStyle, color = Palette.Text, textAlign = TextAlign.Center, modifier = Modifier.weight(1f).semantics { heading() })
        Spacer(Modifier.size(48.dp))
    }
}

/** A small dim capital label over a list ("DOING NOW"). */
@Composable
fun CapsLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = Type.Small.copy(letterSpacing = 0.8.sp), color = Palette.TextDim, modifier = modifier.padding(start = 4.dp, top = 6.dp).semantics { heading() })
}

/** "Started 6 min ago" from a task's start time (seconds since 1970; 0 when not known). */
fun startedWords(createdUnix: Long, nowSeconds: Long = System.currentTimeMillis() / 1000): String = when {
    createdUnix <= 0 -> "Working now"
    else -> io.github.odnwks.mimir.data.Mapping.age((nowSeconds - createdUnix).coerceAtLeast(0)).let { if (it == "now") "Started just now" else "Started $it ago" }
}

const val YOUR_PC_STEPS_TITLE = "To connect your PC"
val YOUR_PC_STEPS = CONNECT_STEPS

/**
 * Your PC (board 8): is it connected, what is it doing, and (only when the phone is told) how it is
 * doing. Not paired, the same page shows the three steps and one button.
 */
@Composable
fun StatusScreen(state: StationState, station: io.github.odnwks.mimir.data.Station, nav: Navigator) {
    val tap = LocalTapTracker.current
    Column(Modifier.fillMaxSize().background(Palette.Ground)) {
        CenteredTitleBar("Your PC", nav)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!state.paired) {
                Panel(Modifier.semantics(mergeDescendants = true) {}, spacing = 12.dp) {
                    Text(YOUR_PC_STEPS_TITLE, style = Type.BodyTight.copy(fontWeight = FontWeight.SemiBold), color = Palette.Text)
                    YOUR_PC_STEPS.forEachIndexed { i, s ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("${i + 1}", style = Type.Label.copy(fontWeight = FontWeight.Bold), color = Palette.BlueText)
                            Text(connectStep(s), style = Type.Label, color = Palette.TextSoft)
                        }
                    }
                }
                BigButton("Connect your PC", Look.Primary, { nav.open(Screen.Pair, tap.last) }, height = 48.dp)
                Text("$AI_NAME on this phone works without a PC.", style = Type.Small, color = Palette.TextDim, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                return@Column
            }
            val on = state.pc.online
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.Card).border16(Palette.Line).padding(vertical = 16.dp, horizontal = 14.dp)
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Dot(if (on) Palette.Green else Palette.TextDim, hollow = !on)
                    Text(if (on) "Connected" else "Not connected", style = Type.BodyTight.copy(fontWeight = FontWeight.SemiBold), color = Palette.Text)
                }
                // Whether this is the home network is not something the phone is told, so it says only what is known.
                Text(if (on) "Private" else state.connectionLine, style = Type.Small, color = Palette.TextDim, textAlign = TextAlign.Center)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                CapsLabel("DOING NOW", Modifier.weight(1f))
                // Every task, with search and Archived, one tap from here.
                LinkText("All tasks", { nav.open(Screen.AllTasks()) })
            }
            val doing = state.tasks.filter { it.stateWords == "Working" || it.stateWords == "Starting" || it.stateWords == "Needs you" }
            if (doing.isEmpty()) Text(
                if (state.pcAway) "Nothing known (as of when your PC was last reached)." else "Nothing right now.",
                style = Type.Label, color = Palette.TextDim, modifier = Modifier.padding(horizontal = 4.dp),
            )
            else Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.Card).border16(Palette.Line)) {
                doing.forEachIndexed { i, t ->
                    if (i > 0) Spacer(Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(1.dp).background(Palette.Line))
                    DoingRow(t) { nav.open(Screen.Task(t.id), tap.last) }
                }
            }
            // HEALTH (Processor, Memory) is left out: the PC does not send those numbers to the phone yet.
            // Claude use this week, as one thin line; a tap opens Models. Only while a paid Claude choice is on.
            if (state.paidOn != false) Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClickLabel = "Models") { nav.open(Screen.Models, tap.last) }
                    .semantics(mergeDescendants = true) {}
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, itemVerticalAlignment = Alignment.CenterVertically) {
                    Text("Online use this week", style = Type.Label, color = Palette.TextSoft)
                    Text(listOf(state.usage.percentText, state.usage.resets).filter { it.isNotBlank() }.joinToString(" · "), style = Type.Small, color = Palette.TextDim)
                }
                Bar(state.usage.percent / 100f, 6.dp)
            }
            // The near-limit switch sits right under the use it's about (Models keeps a link here).
            if (state.models.local.isNotEmpty() && state.paidOn != false) SwitchRow(state.models.switchToFree, station::setSwitchToFree, Modifier.padding(horizontal = 4.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Switch to free near my limit", style = Type.BodyTight, color = Palette.Text)
                    Text("When online use passes ${state.usage.switchAt}% this week, small jobs use ${state.models.freeFallback} instead", style = Type.Small, color = Palette.TextDim)
                }
            }
        }
        if (state.paired) BigButton("Disconnect this phone", Look.Danger, { nav.openSheet(io.github.odnwks.mimir.nav.Sheet.Unpair) }, Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 22.dp), height = 48.dp)
    }
}

/** A task the PC is on: its title and, under it, the project and what it's doing (waiting ones in amber). */
@Composable
private fun DoingRow(t: io.github.odnwks.mimir.data.Task, open: () -> Unit) {
    val waiting = t.stateWords == "Needs you"
    val doing = if (waiting) "waiting for you" else t.steps.lastOrNull()?.trimStart('✓', '✕', '…', ' ')?.replaceFirstChar { it.lowercase() }
        ?: if (t.stateWords == "Starting") "starting" else "working"
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = open)
            .semantics(mergeDescendants = true) { contentDescription = "${t.title}, ${t.project}, $doing" }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(t.title, style = Type.BodyTight, color = if (waiting) Palette.AmberText else Palette.Text, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text("${t.project} · $doing", style = Type.Small, color = if (waiting) Palette.AmberNote else Palette.TextDim, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        Icon(Glyphs.Next, null, tint = Palette.TextDim, modifier = Modifier.size(20.dp))
    }
}

/**
 * The working state's dot: a few soft 1.6 s pulses when it shows, then a still dot (QA C4: nothing
 * redraws for hours while a task works); a still dot with animations removed.
 */
@Composable
fun WorkingDot() {
    val reduce = LocalReduceMotion.current
    val pulse = remember { androidx.compose.animation.core.Animatable(1f) }
    if (!reduce) LaunchedEffect(Unit) {
        repeat(WORKING_PULSES) {
            pulse.animateTo(0.55f, androidx.compose.animation.core.tween(800))
            pulse.animateTo(1f, androidx.compose.animation.core.tween(800))
        }
    }

    Box(
        Modifier.size(14.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Palette.BlueLight.copy(alpha = 0.25f)),
        contentAlignment = Alignment.Center,
    ) { Box(Modifier.size(8.dp).graphicsLayer { this.alpha = if (reduce) 1f else pulse.value }.clip(androidx.compose.foundation.shape.CircleShape).background(Palette.BlueLight)) }
}

/** How many times the working dot pulses when it shows, before it holds still. */
private const val WORKING_PULSES = 3

@Composable
fun Bar(fraction: Float, height: androidx.compose.ui.unit.Dp, track: Color = Palette.Track, fill: Color = Palette.BlueLight) {
    Box(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height / 2)).background(track)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(height / 2)).background(fill))
    }
}

/** A project's ring: sweeps up to its value once when Status opens, then stays still. */
@Composable
fun Gauge(state: CheckState, fraction: Float, size: androidx.compose.ui.unit.Dp = 48.dp) {
    val color = state.color()
    val reduce = LocalReduceMotion.current
    val sweep = remember { Animatable(if (reduce) fraction else 0f) }
    LaunchedEffect(fraction, reduce) {
        sweep.animateTo(fraction, Motion.spec(Motion.GAUGE_MS, reduce))
    }
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val unit = this.size.width / 52f
            val stroke = 6f * unit
            val r = 21f * unit
            val topLeft = androidx.compose.ui.geometry.Offset(center.x - r, center.y - r)
            val arcSize = androidx.compose.ui.geometry.Size(2 * r, 2 * r)
            // Marks and rings keep 3:1 against the card: "no checks" is drawn in the lighter grey.
            drawCircle(
                if (state == CheckState.None) Palette.Outline else Palette.TrackDim, radius = r,
                style = Stroke(stroke, pathEffect = if (state == CheckState.None) PathEffect.dashPathEffect(floatArrayOf(4f * unit, 5f * unit)) else null),
            )
            if (state != CheckState.None && sweep.value > 0f) {
                drawArc(color, -90f, 360f * sweep.value, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        val icon = when (state) {
            CheckState.Pass -> Glyphs.CheckBold
            CheckState.Fail -> Glyphs.Warning
            CheckState.None -> Glyphs.Minus
        }
        Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
    }
}

/**
 * All tasks (from Status or a magnifier): search over titles, projects and what was said, a
 * filter by state, and Archived, where each archived task can come back.
 */
@Composable
fun AllTasksScreen(state: StationState, station: io.github.odnwks.mimir.data.Station, nav: Navigator, archivedFirst: Boolean = false) {
    var query by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
    var only by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(if (archivedFirst) "Archived" else "All") }
    val order = listOf("Needs you", "Working", "Starting", "Live", "Paused", "Stopped")
    val archived = only == "Archived"
    val sorted = (if (archived) state.archived else state.tasks).sortedWith(
        compareBy<io.github.odnwks.mimir.data.Task> { order.indexOf(it.stateWords).let { i -> if (i < 0) order.size else i } }
            .thenByDescending { maxOf(it.activeUnix, it.createdUnix) },
    )
    val q = query.trim()
    val shown = sorted.filter {
        (archived || only == "All" || it.stateWords == only || (only == "Working" && it.stateWords == "Starting")) &&
            (q.isEmpty() || it.title.contains(q, true) || it.project.contains(q, true) || it.messages.any { m -> m.text.contains(q, true) })
    }
    Column(Modifier.fillMaxSize().background(Palette.Ground)) {
        BackHeader("All tasks", nav::back, subtitle = if (state.pcAway) state.connectionLine else null)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.tasks.isEmpty() && state.archived.isEmpty()) { EmptyState("No tasks yet. Ask for something in Chat and it starts one."); return@Column }
            MessageBox(query, { query = it }, "Search tasks", "Search tasks and messages", Modifier.fillMaxWidth())
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("All", "Needs you", "Working", "Paused", "Stopped", "Done", "Archived").forEach { f ->
                    val on = f == only
                    Box(
                        Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).background(if (on) Palette.BlueBubble else Color.Transparent)
                            .border16(if (on) Palette.BlueBorder else Palette.Outline, 12)
                            .selectable(on, role = Role.RadioButton) { only = f }
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(if (f == "Archived" && state.archived.isNotEmpty()) "Archived · ${state.archived.size}" else f, style = Type.Label, color = Palette.Text) }
                }
            }
            shown.forEach { t ->
                if (archived) ListRow(
                    t.title, t.project, { nav.open(Screen.Task(t.id)) },
                    trailing = { SmallButton("Unarchive", { station.unarchiveTask(t.id) }, description = "Unarchive ${t.title}") },
                ) else {
                    val (fg, bg) = stateColours(t.stateWords)
                    ListRow(t.title, t.project, { nav.open(Screen.Task(t.id)) }, trailing = { Tag(t.stateWords, fg, bg) })
                }
            }
            if (shown.isEmpty()) Text(
                when {
                    q.isNotEmpty() -> "No task matches \"$q\"."
                    archived -> "No archived tasks. A task archived from its ⋯ menu shows here and can come back."
                    else -> "No task is ${only.lowercase()}."
                },
                style = Type.Label, color = Palette.TextDim, modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}
