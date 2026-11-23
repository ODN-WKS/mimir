package io.github.odnwks.mimir.ui

import io.github.odnwks.mimir.ui.theme.ChatTextSize
import io.github.odnwks.mimir.ui.theme.LocalAppSize
import io.github.odnwks.mimir.ui.theme.pinchToZoomText
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.remember
import kotlinx.coroutines.launch
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.odnwks.mimir.data.Answer
import io.github.odnwks.mimir.data.PermissionKind
import io.github.odnwks.mimir.data.count
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import io.github.odnwks.mimir.data.Station
import io.github.odnwks.mimir.data.StationState
import io.github.odnwks.mimir.data.Task
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.nav.Sheet
import io.github.odnwks.mimir.ui.theme.LocalReduceMotion
import io.github.odnwks.mimir.ui.theme.Motion
import io.github.odnwks.mimir.ui.theme.Palette
import io.github.odnwks.mimir.ui.theme.Type
import io.github.odnwks.mimir.ui.theme.motionSpec

@OptIn(ExperimentalLayoutApi::class)
/** A task (board 3): its steps, Claude's messages, a question, and a permission pinned at the bottom. */
@Composable
fun TaskScreen(taskId: String, state: StationState, station: Station, nav: Navigator) {
    // PHU-10: after 10 s with no reply while PC is online, show "This task was removed".
    var taskGoneTimedOut by rememberSaveable(taskId) { mutableStateOf(false) }
    val waitingForTask = state.task(taskId) == null &&
        (state.needsYou.any { it.taskId == taskId } || state.pc.online || taskId in state.startedTasks || taskId.startsWith("t-"))
    LaunchedEffect(taskId, waitingForTask) {
        if (waitingForTask) {
            kotlinx.coroutines.delay(10_000L)
            taskGoneTimedOut = true
        }
    }
    val task = state.task(taskId) ?: return if (state.startedTasks[taskId] == "") {
        MissingScreen("This task didn't start: the reason is in Chat", nav)
    } else if (!taskGoneTimedOut && waitingForTask) {
        // Opened from Needs you or a notice before the PC's copy of the task arrived: it shows
        // as soon as it does, instead of a dead end that sends the owner back.
        MissingScreen("Getting this task from your PC…", nav)
    } else if (taskGoneTimedOut) {
        MissingScreen("This task was removed", nav)
    } else MissingScreen("This task is gone", nav)
    val standIn = LocalStandIn.current
    var stepsOpen by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    // Why an edit was not sent (its box opens again with the words kept).
    var editFailure by rememberSaveable { mutableStateOf<String?>(null) }
    // Find in this task: only the messages with the words show while it's open.
    var finding by rememberSaveable { mutableStateOf(false) }
    var find by rememberSaveable { mutableStateOf("") }
    val words = find.trim().takeIf { finding && it.isNotEmpty() }
    val messages = if (words == null) task.messages else task.messages.filter { it.text.contains(words, ignoreCase = true) }
    // "Move to another project" in the options: the picker hands back where to.
    LaunchedEffect(nav.pickedProject) {
        nav.pickedProject?.let { p -> nav.pickedProject = null; if (p != task.project) station.moveTask(task.id, p) }
    }
    // The conversation follows its newest part: a message just sent, a reply arriving, a
    // question, or the keyboard opening (which shrinks the space above the reply box).
    val scroll = rememberScrollState()
    // A step was still running when Stop was tapped here.
    var cutOff by rememberSaveable(task.id) { mutableStateOf(false) }
    // A PC that lists its sessions: a task going on elsewhere is followed with a banner, Stop and Carry on here.
    val listsSessions = state.pcSessions != null
    // Known on the first frame: from the task itself, or from the PC's list (a session working somewhere else).
    var followed by rememberSaveable(task.id) { mutableStateOf(listsSessions && (task.liveElsewhere || followedElsewhere(state, task.id))) }
    var carried by rememberSaveable(task.id) { mutableStateOf(false) }
    if (listsSessions && task.liveElsewhere) followed = true
    val reduce = LocalReduceMotion.current
    val keyboard = WindowInsets.isImeVisible
    val focus = LocalFocusManager.current
    LaunchedEffect(task.messages.size, task.messages.lastOrNull()?.text?.length, task.question?.itemId, keyboard) {
        // Wait for the new part to be laid out, so the end is where it really is.
        androidx.compose.runtime.withFrameNanos { }
        androidx.compose.runtime.withFrameNanos { }
        if (reduce) scroll.scrollTo(scroll.maxValue) else scroll.animateScrollTo(scroll.maxValue)
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(Palette.Ground)) {
        val screenHeight = maxHeight
        Column(Modifier.fillMaxSize()) {
            BackHeader(
                task.title, nav::back, subtitle = listOf(task.project, task.model, task.age).filter { it.isNotBlank() }.joinToString(" · "), titleStyle = Type.TaskTitle,
                subtitleContent = {
                    // The state line: "Needs you · Mímir · 6 min" (the model is in the task's menu).
                    // Only the state stays; the project and age are a tap away (the chat area stays clean).
                    var more by rememberSaveable(task.id) { mutableStateOf(false) }
                    Row(
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 4.dp).heightIn(min = 32.dp).clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = if (more) "Hide the details" else "Show the project and age") { more = !more }
                            .semantics(mergeDescendants = true) { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
                    ) {
                        val (fg, bg) = stateColours(task.stateWords)
                        Tag(task.stateWords, fg, bg)
                        // A mode that doesn't ask first is always in sight.
                        task.riskyMode?.let { if (task.yolo) Tag(it, Palette.Ground, Palette.Red) else Tag(it, Palette.AmberInk, Palette.Amber) }
                        if (more) Text(listOf(task.project, if (task.plan) "Plan first" else "", task.age).filter { it.isNotBlank() }.joinToString(" · "), style = Type.Small, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    // What it's doing right now, in words.
                    if (task.stateWords == "Working" && task.stateLine != "Working on it") Text(task.stateLine, style = Type.Small, color = Palette.TextSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                action = {
                    IconTarget(Glyphs.Search, if (finding) "Close find" else "Find in this task", { finding = !finding; if (!finding) find = "" })
                },
                // A tap on the task's name: everything for this task in one sheet.
                onTitle = { nav.openSheet(Sheet.TaskOptions(task.id)) }, titleLabel = TASK_OPTIONS,
            )
            if (finding) Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MessageBox(find, { find = it }, "Find in this task", "Find in this task", Modifier.fillMaxWidth())
                if (words != null) Text(
                    when (messages.size) { 0 -> "No message has \"$words\""; 1 -> "1 message has \"$words\""; else -> "${messages.size} messages have \"$words\"" },
                    style = Type.Small, color = Palette.TextDim,
                )
            }
            ConnectionStrip(state, Modifier.padding(bottom = 4.dp))
            // Pinned above the conversation, so it stays in sight while it scrolls.
            if (listsSessions && task.liveElsewhere) Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) { FollowBanner(followedIn(state, task.id)) }
            ChatTextSize {
            Column(
                Modifier.weight(1f).fillMaxWidth()
                    // YOLO: a red edge round the chat while it's on, never hidden.
                    .then(if (task.yolo) Modifier.border(2.dp, Palette.Red, RoundedCornerShape(12.dp)) else Modifier)
                    .pinchToZoomText(LocalAppSize.current)
                    // A tap on the conversation puts the keyboard away.
                    .pointerInput(Unit) { detectTapGestures(onTap = { focus.clearFocus() }) }
                    .verticalScroll(scroll)
                    .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (task.steps.isNotEmpty()) Steps(task, stepsOpen) { stepsOpen = !stepsOpen }
                if (task.freeModel) Panel(background = Palette.Bar) {
                    Text(
                        if (task.outOfRoom) "This free model ran out of room. Mímir can carry on online from a summary of the work."
                        else "On a free model on your PC. Free models fit small, clear jobs.",
                        style = Type.Label, color = Palette.TextSoft,
                    )
                    if (task.refusalsRemoved) Text(
                        "No safety filter: it does what you ask, including things other models won't.",
                        style = Type.Small, color = Palette.AmberNote,
                    )
                    BigButton(if (task.outOfRoom) "Continue online" else "Answer online instead", Look.Outline, { station.continueOnClaude(task.id) }, height = 48.dp)
                }
                // The chat's own parts: an answer with Listen, Copy, Share and Try again, and the owner's
                // message with its hold menu and Edit. Both only once the task is quiet.
                val quiet = !task.showsStop && !task.busy
                // Nothing is queued behind it, and one tap only: Try again never sends an older prompt after a newer one.
                val nothingWaiting = task.messages.none { it.waiting }
                var tried by rememberSaveable(task.id) { mutableStateOf<String?>(null) }
                val lastAnswer = task.messages.lastOrNull { it.fromClaude }
                val lastOwn = task.messages.lastOrNull { !it.fromClaude && !it.waiting }
                messages.forEach { m ->
                    if (m.fromClaude) {
                        val writing = m.playLabel.isEmpty()
                        val again = lastOwn?.takeIf { quiet && nothingWaiting && tried != m.id && !writing && m.id == lastAnswer?.id && task.messages.indexOf(it) < task.messages.indexOf(m) }
                        Answer(
                            markdownPlain(m.text), "task-${task.id}-${m.id}",
                            dim = writing,
                            // Listen: the PC's voice while the PC is there, otherwise the phone's own.
                            pc = if (state.pc.online) ({ station.playMessage(task.id, m.id) }) else null,
                            onTryAgain = again?.let { o -> { tried = m.id; station.reply(task.id, o.text) } },
                            share = true,
                        ) {
                            MarkdownText(m.text, style = ChatLook.MessageText, color = Palette.Text)
                        }
                    } else if (m.waiting) OwnMessage(
                        m.text, bubble = Palette.QueuedBubble, dashed = true, id = "task-${task.id}-${m.id}",
                        under = {
                            StatusWords(
                                Glyphs.Clock,
                                if (!state.phoneOnline) "This phone is offline: sends when it's back" else "Waiting: sends when your PC is back",
                                Palette.TextDim,
                            )
                        },
                    ) else OwnMessage(
                        m.text, id = "task-${task.id}-${m.id}",
                        onEdit = if (quiet && m.id == lastOwn?.id && io.github.odnwks.mimir.link.Wire.entryIndex(task.id, m.id) != null) {
                            { words -> editFailure = null; station.editTaskMessage(task.id, m.id, words.trim()) { why -> editFailure = why } }
                        } else null,
                        editNote = EDIT_TASK_NOTE, editFailure = editFailure, onEditCancel = { editFailure = null },
                    )
                }
                // A task that hasn't answered yet says so, instead of a blank page.
                if (task.stateWords == "Starting") Panel(background = Palette.Bar) {
                    Text("Starting… The first reply shows here.", style = Type.Label, color = Palette.TextDim)
                }
                // Last, under the conversation it ended.
                // "Stopped · Continue", and whether a command was cut off by the stop.
                if (task.stateWords == "Stopped") Panel(background = Palette.Bar) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Stopped ·", style = Type.Label, color = Palette.TextDim)
                        LinkText("Continue", { station.continueTask(task.id); cutOff = false }, bold = true)
                    }
                    if (cutOff || task.steps.lastOrNull()?.startsWith("…") == true) Text("A command may have been cut off.", style = Type.Small, color = Palette.AmberNote)
                }
                task.question?.let { q ->
                    Panel(background = Palette.AmberCard, border = Palette.AmberBorder, spacing = 10.dp) {
                        SectionTitle("A question for you", color = Palette.AmberText, style = Type.Label.copy(fontWeight = FontWeight.Bold))
                        MarkdownText(q.text, style = Type.BodyTight, color = Palette.AmberSoft)
                        // Claude's offered options, one tap each, none picked for the owner; otherwise the reply box answers.
                        q.choices.forEachIndexed { i, c ->
                            BigButton(c, Look.AmberOutline, { station.answerChoice(q.itemId, i) }, height = 48.dp, fontSize = 15)
                        }
                        if (q.yes.isNotEmpty()) BigButton(q.yes, Look.Amber, { nav.openSheet(Sheet.Release(task.id)) }, height = 48.dp, fontSize = 15)
                        BigButton("Keep for later", Look.AmberOutline, { station.answer(q.itemId, Answer.KeepForLater) }, height = 48.dp, fontSize = 15)
                        Row(
                            Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp))
                                .clickable(role = Role.Button) {
                                    task.messages.lastOrNull { it.fromClaude }?.let { station.playMessage(task.id, it.id) }
                                }
                                .semantics(mergeDescendants = true) { contentDescription = "Play the question" }
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(Glyphs.Play, null, tint = Palette.AmberText, modifier = Modifier.size(16.dp))
                            Text("Play question", style = Type.Small, color = Palette.AmberText)
                        }
                    }
                }
            }
            }

            // After an answer, its receipt shows for 4 s in the dock's place, then the next ask (or nothing).
            var receipt by remember(task.id) { mutableStateOf<String?>(null) }
            LaunchedEffect(receipt) { if (receipt != null) { kotlinx.coroutines.delay(4000); receipt = null } }
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            AnimatedVisibility(
                visible = task.permission != null || receipt != null,
                enter = expandVertically(motionSpec(Motion.NEEDS_YOU_BAR_MS)) + fadeIn(motionSpec(Motion.NEEDS_YOU_BAR_MS)),
                exit = shrinkVertically(motionSpec(Motion.NEEDS_YOU_BAR_MS)) + fadeOut(motionSpec(Motion.NEEDS_YOU_BAR_MS)),
            ) {
                val p = task.permission
                androidx.compose.animation.AnimatedContent(
                    targetState = receipt ?: p?.itemId,
                    transitionSpec = {
                        (fadeIn(Motion.spec(Motion.NEEDS_YOU_BAR_MS, reduce)) + slideInVertically(Motion.spec(Motion.NEEDS_YOU_BAR_MS, reduce)) { it / 6 }) togetherWith
                            fadeOut(Motion.spec(Motion.RECEIPT_MS, reduce))
                    },
                    label = "dock",
                ) { shown ->
                    when {
                        shown == null -> Box(Modifier)
                        shown == receipt -> Receipt(shown)
                        p == null -> Box(Modifier)
                        else -> DecisionDock(
                            p, of = state.needsYou.count { it.taskId == task.id }.coerceAtLeast(1), compact = keyboard, tight = task.question != null,
                            allow = { receipt = receiptWords(p, true, state.pcAway); station.answer(p.itemId, Answer.Allow) },
                            dontAllow = { receipt = receiptWords(p, false, state.pcAway); station.answer(p.itemId, Answer.DontAllow) },
                            showChange = { nav.open(Screen.Change(task.id)) },
                            moreChoices = { nav.openSheet(Sheet.AlwaysAllow(task.id)) },
                            also = if (task.question != null) "There is also a question for you" else null,
                            seeAlso = { scope.launch { if (reduce) scroll.scrollTo(scroll.maxValue) else scroll.animateScrollTo(scroll.maxValue) } },
                        )
                    }
                }
            }

            val voice = rememberVoiceInput(station, task.id)
            // Going on in a terminal or the Claude app right now: replies go there (one from here
            // would start a second Claude on it), so a note, as on the PC, takes the box's place.
            if (task.waitingToStart) Panel(background = Palette.Bar, modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (task.failedWhy.isNotEmpty()) {
                        Text("This task didn't start: ${task.failedWhy} Your words are kept.", style = Type.Label, color = Palette.AmberNote)
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            LinkText("Try again", { station.retryWaitingTask(task.id) }, bold = true)
                            LinkText("Remove", { station.cancelWaitingTask(task.id); nav.back() }, bold = true)
                        }
                    } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (state.pcAway) "Waiting for your PC ·" else "Starting…", style = Type.Label, color = Palette.TextDim)
                        if (task.canCancel) LinkText("Cancel this task", { station.cancelWaitingTask(task.id); nav.back() }, bold = true)
                    }
                }
            } else androidx.compose.animation.AnimatedContent(
                task.liveElsewhere,
                transitionSpec = { fadeIn(Motion.spec(Motion.SCREEN_MS, reduce)) togetherWith fadeOut(Motion.spec(Motion.SCREEN_MS, reduce)) },
                label = "reply box",
            ) { elsewhere ->
                if (listsSessions && followed && !carried) FollowButtons(
                    live = elsewhere, onStop = { station.stopTask(task.id) }, onCarryOn = { carried = true },
                    modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 16.dp),
                )
                else if (elsewhere) ElsewhereNote(Modifier.windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 16.dp))
                else Column {
                    VoicePanel(voice, heard = { draft = withHeard(draft, it) }, Modifier.padding(top = 8.dp))
                    // Only the composer rides the keyboard; the dock above it goes up with it.
                    Composer(
                        draft, { draft = it },
                        hint = if (state.pcAway) "Reply to Mímir (${state.waitsUntil})" else "Reply to Mímir",
                        label = "Reply to this task", voice = voice, micLabel = "Talk (voice typing): speak a reply",
                        onSend = { station.reply(task.id, draft.trim()); draft = "" },
                        // While Claude works, an empty box's button is Stop.
                        working = task.showsStop,
                        onStop = { cutOff = task.steps.lastOrNull()?.startsWith("…") == true; station.stopTask(task.id) },
                        modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                            .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Steps(task: Task, open: Boolean, toggle: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(Palette.Bar).border16(Palette.Line)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = toggle)
                .semantics { stateDescription = if (open) "Shown" else "Hidden" }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val failed0 = task.steps.count { it.startsWith("✕") } + task.steps.count { it.startsWith("…") }
            val done = task.steps.size - failed0
            val failed = task.steps.count { it.startsWith("✕") }
            val going = task.steps.count { it.startsWith("…") }
            val words = listOfNotNull(
                if (task.busy || going > 0) "${count(done + going, "step")} so far" else "${count(done, "step")} done",
                if (going > 0 && !task.busy) "$going waiting" else null,
                if (failed > 0) "$failed not done" else null,
            ).joinToString(", ")
            when {
                failed > 0 -> Text("✕", style = Type.Label, color = Palette.Red)
                going > 0 -> Icon(Glyphs.Clock, null, tint = Palette.TextDim, modifier = Modifier.size(18.dp))
                else -> Icon(Glyphs.Check, null, tint = Palette.Green, modifier = Modifier.size(18.dp))
            }
            Text(words, style = Type.Label, color = Palette.Text, modifier = Modifier.weight(1f))
            Text(if (open) "Hide" else "Show", style = Type.Small, color = Palette.TextDim)
        }
        // The fold opens downward by height: what's below slides, never jumps.
        AnimatedVisibility(
            open,
            enter = expandVertically(motionSpec(Motion.NEEDS_YOU_BAR_MS)) + fadeIn(motionSpec(Motion.NEEDS_YOU_BAR_MS)),
            exit = shrinkVertically(motionSpec(Motion.NEEDS_YOU_BAR_MS)) + fadeOut(motionSpec(Motion.NEEDS_YOU_BAR_MS)),
        ) {
            Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                task.steps.forEachIndexed { i, s ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${i + 1}.", style = Type.Label.copy(lineHeight = Type.Label.fontSize * 1.5f), color = Palette.TextDim)
                        Text(s, style = Type.Label.copy(lineHeight = Type.Label.fontSize * 1.5f), color = Palette.TextDim)
                    }
                }
            }
        }
    }
}

/**
 * The decision dock: one permission, pinned above the reply box. Both answers are always whole
 * and in view: side by side, stacked only when the text is very large, and never inside a
 * scrolling box. While the keyboard is open it shrinks to its title and the two answers.
 * "1 of 2" counts only this task's asks; [also] is the second one in a line ("Claude also asks
 * you something · See it").
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DecisionDock(
    p: io.github.odnwks.mimir.data.Permission,
    of: Int,
    compact: Boolean,
    tight: Boolean,
    allow: () -> Unit,
    dontAllow: () -> Unit,
    showChange: () -> Unit,
    moreChoices: () -> Unit,
    also: String? = null,
    seeAlso: () -> Unit = {},
) {
    val bigText = androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.5f
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    Column(
        Modifier.padding(horizontal = 12.dp).fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)).background(Palette.BlueCard)
            .then(Modifier.border16(Palette.BlueBorder))
            // TalkBack hears the ask when the dock appears.
            .semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }
            .padding(start = 14.dp, end = 14.dp, top = if (compact) 8.dp else 12.dp, bottom = if (compact) 10.dp else 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(
                p.title, Modifier.weight(1f), style = Type.BodyTight.copy(fontWeight = FontWeight.SemiBold),
                maxLines = if (compact || bigText) 2 else 3,
            )
            if (of > 1) Text("1 of $of", style = Type.Small, color = Palette.BlueText, modifier = Modifier.semantics { contentDescription = "1 of $of this task asks" })
        }
        if (!compact) {
            if (p.kind == PermissionKind.Command) Text(
                p.command, style = Type.Label.copy(fontFamily = FontFamily.Monospace), color = Palette.Text,
                maxLines = if (bigText || tight) 2 else 4, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.Ground)
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .semantics { contentDescription = "Command: ${p.command}" },
            )
            if (p.kind == PermissionKind.Edit && !(bigText && tight)) androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LinkText("See the whole change", showChange, bold = true)
                LinkText("Always allow…", moreChoices)
            }
        }
        // Don't allow is never smaller, cut or scrolled away: it sits beside Allow.
        val yes = { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.Confirm); allow() }
        val no = { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.Reject); dontAllow() }
        if (bigText) {
            BigButton(p.allowWords, Look.Primary, yes, height = 48.dp)
            BigButton("Don't allow", Look.Outline, no, height = 48.dp)
        } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BigButton("Don't allow", Look.Outline, no, Modifier.weight(1f), height = if (compact) 48.dp else 52.dp, fontSize = 15)
            BigButton(p.allowWords, Look.Primary, yes, Modifier.weight(1f), height = if (compact) 48.dp else 52.dp, fontSize = 15)
        }
        if (also != null && !compact) Row(
            Modifier.fillMaxWidth().heightIn(min = 40.dp).clickable(role = Role.Button, onClick = seeAlso)
                .semantics(mergeDescendants = true) { contentDescription = "$also. See it" },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(also, style = Type.Small, color = Palette.TextSoft, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("See it", style = Type.Small.copy(fontWeight = FontWeight.SemiBold), color = Palette.BlueText)
        }
    }
}

/** What an answer did, in one line, for a few seconds after it's given (then it slides away). */
@Composable
fun Receipt(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier.padding(horizontal = 12.dp).fillMaxWidth().heightIn(min = 48.dp)
            .clip(RoundedCornerShape(16.dp)).background(Palette.Bar).border16(Palette.Line)
            .semantics(mergeDescendants = true) { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Glyphs.Check, null, tint = Palette.Green, modifier = Modifier.size(18.dp))
        Text(text, style = Type.Label, color = Palette.TextSoft)
    }
}

/** The receipt's words for an answer to [p]. */
fun receiptWords(p: io.github.odnwks.mimir.data.Permission, allowed: Boolean, pcAway: Boolean): String {
    val what = when (p.kind) { PermissionKind.Edit -> "this edit"; PermissionKind.Command -> "this command"; PermissionKind.Other -> "it" }
    val said = if (allowed) "You allowed $what" else "You didn't allow $what"
    return if (pcAway) "$said · sends when your PC is back" else said
}

/** Shown if a screen's data went away (e.g. the task was deleted on the PC). */
@Composable
fun MissingScreen(text: String, nav: Navigator) {
    Column(Modifier.fillMaxSize().background(Palette.Ground)) {
        BackHeader(text, nav::back)
        Box(Modifier.weight(1f))
    }
}

/** Under the edit box of a task message: what sending it does. */
const val EDIT_TASK_NOTE = "Sending puts changed files back as they were, then the task carries on with your new words."

/** The note while a conversation goes on elsewhere (the PC window shows its own version of it). */
const val FOLLOWS_ALONG = "Going on in a terminal or another app right now. Reply there; this view follows along live."

/** Where a task going on elsewhere runs: "the Claude app", "a terminal", or "another app" when the PC's list doesn't say. */
fun followedIn(state: StationState, taskId: String): String {
    val s = state.pcSessions?.firstOrNull { it.task == taskId } ?: return "another app"
    return if (s.app) "the Claude app" else "a terminal"
}

/** True when the PC's list says this task's session is working in a terminal or the Claude app. */
fun followedElsewhere(state: StationState, taskId: String): Boolean =
    state.pcSessions?.any { it.task == taskId && it.state == io.github.odnwks.mimir.data.PcSessionState.Working && !it.here } == true

/** The words of the green banner over a task that is going on elsewhere. */
fun followBannerText(where: String) = "Running in $where. You can follow it here."

const val FOLLOW_STOP = "Stop"
const val FOLLOW_CARRY_ON = "Carry on here"

@Composable
private fun FollowBanner(where: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.GreenCard).border(1.dp, Palette.GreenBorder, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 9.dp)
            .semantics(mergeDescendants = true) { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Dot(Palette.Green)
        Text(followBannerText(where), style = Type.Small, color = Palette.GreenText)
    }
}

/** In place of the reply box while a session from the PC is followed: Stop works, and Carry on here lights up once it stops. */
@Composable
private fun FollowButtons(live: Boolean, onStop: () -> Unit, onCarryOn: () -> Unit, modifier: Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f)) { if (live) BigButton("\u25a0 $FOLLOW_STOP", Look.Danger, onStop, height = 48.dp) else BigButton("\u25a0 $FOLLOW_STOP", Look.Outline, {}, height = 48.dp, enabled = false) }
        Box(Modifier.weight(1f)) { BigButton(FOLLOW_CARRY_ON, if (live) Look.Outline else Look.Primary, onCarryOn, height = 48.dp, enabled = !live) }
    }
}

/** In place of the reply box while the conversation goes on elsewhere. */
@Composable
private fun ElsewhereNote(modifier: Modifier) {
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    Panel(modifier.fillMaxWidth(), background = Palette.Bar, border = Palette.Outline) {
        Text(FOLLOWS_ALONG, style = Type.Label, color = Palette.TextSoft)
        LinkText("Open on claude.ai", { runCatching { uri.openUri("https://claude.ai/code") } }, bold = true)
    }
}
