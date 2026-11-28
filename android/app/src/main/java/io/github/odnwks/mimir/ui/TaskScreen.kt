package io.github.odnwks.mimir.ui

import io.github.odnwks.mimir.data.asksStatus
import io.github.odnwks.mimir.link.LongNotice
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
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.text.withStyle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.selection.DisableSelection
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
    var draft by nav.taskDraft(taskId)
    // Why an edit was not sent (its box opens again with the words kept).
    var editFailure by rememberSaveable { mutableStateOf<String?>(null) }
    // Find in this task: only the messages with the words show while it's open.
    var finding by rememberSaveable { mutableStateOf(false) }
    var find by rememberSaveable { mutableStateOf("") }
    // A PC that lists its sessions: a task going on elsewhere is followed with a banner, Stop and Carry on here.
    val listsSessions = state.pcSessions != null
    // Known on the first frame: from the task itself, or from the PC's list (a session working somewhere else).
    var followed by rememberSaveable(task.id) { mutableStateOf(listsSessions && (task.liveElsewhere || followedElsewhere(state, task.id))) }
    var carried by rememberSaveable(task.id) { mutableStateOf(false) }
    if (listsSessions && task.liveElsewhere) followed = true
    // Still going on outside Mímir right now (a stopped one is not): said once, and only Stop is offered.
    val liveNow = listsSessions && !task.stopped && (task.liveElsewhere || followedElsewhere(state, task.id))
    // The PC's list says it works elsewhere: the follow buttons with Stop show at once.
    if (listsSessions && liveNow) followed = true
    val words = find.trim().takeIf { finding && it.isNotEmpty() }
    // The PC's own notes about a followed session say what the line and buttons below already say.
    val shownMessages = task.messages.filterNot { m ->
        m.note && ((followed && isFollowNote(m.text)) ||
            // The words the PC sends Claude to carry on: the "Carried on from a short summary" note already says it.
            m.text.trim() == io.github.odnwks.mimir.data.CARRY_ON_TEXT ||
            // Nothing runs on after a stop or a pause.
            (m.text == BACKGROUND_NOTE && task.stateWords in setOf("Stopped", "Stopped unexpectedly", "Paused")) ||
            // The box below says it, with Continue.
            (task.stateWords == "Paused" && m.text.startsWith("Paused. Press Continue")))
    }
    val messages = if (words == null) shownMessages else shownMessages.filter { it.text.contains(words, ignoreCase = true) }
    // "Move to another project" in the options: the picker hands back where to.
    LaunchedEffect(nav.pickedProject) {
        nav.pickedProject?.let { p -> nav.pickedProject = null; if (p != task.project) station.moveTask(task.id, p) { m -> android.os.Handler(android.os.Looper.getMainLooper()).post { standIn(m) } } }
    }
    // The conversation follows its newest part: a message just sent, a reply arriving, a
    // question, or the keyboard opening (which shrinks the space above the reply box).
    val scroll = rememberScrollState()
    // A step was still running when Stop was tapped here.
    var cutOff by rememberSaveable(task.id) { mutableStateOf(false) }
    val reduce = LocalReduceMotion.current
    val keyboard = WindowInsets.isImeVisible
    val focus = LocalFocusManager.current
    // N3: the view follows the newest line only while the person is at the bottom (as chat apps do);
    // reading above it, new lines wait behind a "New lines" chip.
    var atBottom by remember(task.id) { mutableStateOf(true) }
    var unseen by remember(task.id) { mutableStateOf(false) }
    val atBottomPx = with(androidx.compose.ui.platform.LocalDensity.current) { 48.dp.roundToPx() }
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.isScrollInProgress }.collect { moving ->
            if (!moving) { atBottom = scroll.value >= scroll.maxValue - atBottomPx; if (atBottom) unseen = false }
        }
    }
    // The space above the reply box getting smaller (the keyboard still sliding, something docking
    // there) must not leave the end half out of sight: at the bottom, it stays at the bottom.
    LaunchedEffect(scroll, task.id) {
        var before = 0
        snapshotFlow { scroll.viewportSize }.collect { size ->
            if (size < before && atBottom) scroll.scrollTo(scroll.maxValue)
            before = size
        }
    }
    LaunchedEffect(task.messages.size, task.messages.lastOrNull()?.text?.length, task.question?.itemId, keyboard) {
        // Wait for the new part to be laid out, so the end is where it really is.
        androidx.compose.runtime.withFrameNanos { }
        androidx.compose.runtime.withFrameNanos { }
        if (followsBottom(atBottom, task.messages.lastOrNull()?.fromClaude == false, keyboard)) {
            if (reduce) scroll.scrollTo(scroll.maxValue) else scroll.animateScrollTo(scroll.maxValue)
            atBottom = true; unseen = false
        } else unseen = true
    }
    // A newly asked Status card is brought into view, once.
    val asked = io.github.odnwks.mimir.data.StatusCards.shown?.takeIf { it.chat == "task:${task.id}" }?.asked ?: 0
    LaunchedEffect(asked) {
        if (asked > io.github.odnwks.mimir.data.StatusCards.scrolled) {
            kotlinx.coroutines.delay(250)
            scroll.animateScrollTo(scroll.maxValue)
            io.github.odnwks.mimir.data.StatusCards.scrolled = asked
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(Palette.Ground)) {
        val screenHeight = maxHeight
        Column(Modifier.fillMaxSize()) {
            // The top is one small line (owner, 2026-11-27): the state at the very left, the mode, the project,
            // a small Stop while it works, and the three dots. Back is the phone's own; Find is in the menu.
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                var more by rememberSaveable(task.id) { mutableStateOf(false) }
                Column(
                    Modifier.weight(1f).heightIn(min = 40.dp)
                        .clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = if (more) "Hide the details" else "Show more about this task") { more = !more }
                        .semantics(mergeDescendants = true) { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
                    verticalArrangement = Arrangement.Center,
                ) {
                    TaskFacts(
                        tags = {
                            val (fg, bg) = stateColours(task.stateWords)
                            Tag(task.stateWords, fg, bg)
                            // A mode that doesn't ask first is always in sight.
                            task.riskyMode?.let { if (task.yolo) Tag(it, Palette.Ground, Palette.Red) else Tag(it, Palette.AmberInk, Palette.Amber) }
                        },
                        project = { if (task.project.isNotBlank()) Text(task.project, style = Type.Small, color = Palette.BlueText, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    ) {
                        // What it's doing right now, on the same line: the words first, the time after them while it fits.
                        Box {
                            LiveSignLine(task.sign, oneLine = true) {
                                if (task.stateWords == "Working" && task.stateLine != "Working on it") Text(task.stateLine, style = Type.Small, color = Palette.TextSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    if (more) Text(listOf(task.title, task.project, if (task.plan) "Plan first" else "", task.age).filter { it.isNotBlank() }.joinToString(" · "), style = Type.Small, color = Palette.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                }
                // Stop: small and round; its confirmation is a ring that unwinds (a followed session has its own Stop below).
                if (task.showsStop && !task.stopPending && !(listsSessions && followed && !carried)) StopControl(
                    { cutOff = task.steps.lastOrNull()?.startsWith("…") == true; station.stopTask(task.id) },
                    shape = StopShape.Round, size = 26.dp, stateText = "Task is working",
                )
                // Find, voice typing, Archive, Delete, Rename, Move and the rest.
                IconTarget(Glyphs.More, TASK_OPTIONS, { nav.openSheet(Sheet.TaskOptions(task.id)) }, size = 40.dp)
            }
            // The menu's Find and Talk, done here.
            val voice = rememberVoiceInput(station, task.id)
            val startVoice = rememberStartVoice(voice)
            LaunchedEffect(nav.taskAsk) {
                when (nav.taskAsk) {
                    io.github.odnwks.mimir.nav.TASK_ASK_FIND -> { nav.taskAsk = null; finding = true }
                    io.github.odnwks.mimir.nav.TASK_ASK_TALK -> { nav.taskAsk = null; startVoice() }
                }
            }
            if (finding) Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    MessageBox(find, { find = it }, "Find in this task", "Find in this task", Modifier.weight(1f), autoFocus = true)
                    IconTarget(Glyphs.Close, "Close find", { finding = false; find = "" })
                }
                if (words != null) Text(
                    when (messages.size) { 0 -> "No message has \"$words\""; 1 -> "1 message has \"$words\""; else -> "${messages.size} messages have \"$words\"" },
                    style = Type.Small, color = Palette.TextDim,
                )
            }
            ConnectionStrip(state, Modifier.padding(bottom = 4.dp))
            // Pinned above the conversation, so it stays in sight while it scrolls.
            if (liveNow) Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) { FollowBanner(followedIn(state, task)) }
            ChatTextSize {
            Column(
                Modifier.weight(1f).fillMaxWidth()
                    // YOLO: a red edge round the chat while it's on, never hidden.
                    .then(if (task.yolo) Modifier.border(2.dp, Palette.Red, RoundedCornerShape(12.dp)) else Modifier)
                    .pinchToZoomText(LocalAppSize.current)
                    // A tap on the conversation puts the keyboard away.
                    .pointerInput(Unit) { detectTapGestures(onTap = { focus.clearFocus() }) }
                    .verticalScroll(scroll)
                    .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // A long conversation: one calm line at the top of the conversation (it scrolls, so it never squeezes the reply box).
                // Dismissed for this task, it comes back at "very long".
                task.long?.let { n ->
                    var dismissed by rememberSaveable(task.id) { mutableStateOf<Boolean?>(null) }
                    var starting by rememberSaveable(task.id) { mutableStateOf(false) }
                    if (!longHidden(dismissed, n)) LongLine(n, starting, {
                        if (!starting) {
                            starting = true
                            // The new task opens when the PC says it is made; a refusal re-enables the button.
                            station.startFresh(task.id) { id ->
                                android.os.Handler(android.os.Looper.getMainLooper()).post { if (id != null) nav.open(Screen.Task(id)) else starting = false }
                            }
                        }
                    }, { dismissed = n.very })
                }
                // The steps sit under the person's last message, which they answer; at the top only when none is shown.
                val stepsAfter = messages.lastOrNull { !it.fromClaude && !it.note && !it.waiting }?.id
                if (task.steps.isNotEmpty() && stepsAfter == null) Steps(task, stepsOpen) { stepsOpen = !stepsOpen }
                if (task.freeModel) Panel(background = Palette.Bar) {
                    Text(
                        if (task.outOfRoom) "Your PC's model ran out of room. Mímir can carry on online from a summary of the work."
                        else "On your PC's model. It fits small, clear jobs.",
                        style = Type.Label, color = Palette.TextSoft,
                    )
                    if (task.refusalsRemoved) Text(
                        "Uncensored: it does what you ask, including things other models won't.",
                        style = Type.Small, color = Palette.AmberNote,
                    )
                    BigButton(if (task.outOfRoom) "Continue online" else "Answer online instead", Look.Outline, { station.continueOnClaude(task.id) }, height = 48.dp)
                }
                // The chat's own parts: an answer with Listen, Copy, Share and Try again, and the owner's
                // message with its hold menu and Edit. Both only once the task is quiet.
                val quiet = !task.showsStop && !task.busy && !liveNow
                // Nothing is queued behind it, and one tap only: Try again never sends an older prompt after a newer one.
                val nothingWaiting = task.messages.none { it.waiting }
                var tried by rememberSaveable(task.id) { mutableStateOf<String?>(null) }
                val lastAnswer = task.messages.lastOrNull { it.fromClaude }
                val lastOwn = task.messages.lastOrNull { !it.fromClaude && !it.waiting && !it.note }
                // P11: the small print under each message, and why a reply's model changed on a tap.
                val working = task.stateWords == "Working" || task.stateWords == "Starting"
                val busyWithStep = task.steps.lastOrNull()?.startsWith("…") == true
                var whyOf by remember(task.id) { mutableStateOf<io.github.odnwks.mimir.data.TaskMessage?>(null) }
                whyOf?.let { SwitchWhyDialog(it) { whyOf = null } }
                val ownMeta: @Composable (io.github.odnwks.mimir.data.TaskMessage) -> Unit = { m ->
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        MetaLine(m.model, m.effort, m.atMs, receipt = m.receipt)
                        if (!m.waiting) NotReadYetLine(m.atMs, m.receipt, working, busyWithStep)
                    }
                }
                // The Status card asked for here sits after the message that was last then, and scrolls with it.
                val status = io.github.odnwks.mimir.data.StatusCards.shown?.takeIf { it.chat == "task:${task.id}" }
                val statusAfter = status?.let { c -> if (messages.any { it.id == c.after }) c.after else messages.lastOrNull()?.id }
                val statusCard: @Composable () -> Unit = { status?.let { c -> StatusCardInChat(c, station) { id -> if (id != task.id) nav.open(Screen.Task(id)) } } }
                if (messages.isEmpty()) statusCard()
                messages.forEach { m ->
                    if (m.note && isFreshStartNote(m.text)) FreshStartDivider(m.text)
                    else if (m.note) Text(
                        if (m.atMs == null) androidx.compose.ui.text.AnnotatedString(m.text) else androidx.compose.ui.text.buildAnnotatedString {
                            append(m.text)
                            withStyle(androidx.compose.ui.text.SpanStyle(color = Palette.TextDim.copy(alpha = 0.75f))) { append(" · " + messageTime(m.atMs)) }
                        },
                        style = Type.Small, color = Palette.TextDim,
                    )
                    else if (m.fromClaude) {
                        val writing = m.playLabel.isEmpty()
                        val again = lastOwn?.takeIf { quiet && nothingWaiting && tried != m.id && !writing && m.id == lastAnswer?.id && task.messages.indexOf(it) < task.messages.indexOf(m) }
                        Answer(
                            markdownPlain(m.text), "task-${task.id}-${m.id}",
                            dim = writing,
                            // Listen: the PC's voice while the PC is there, otherwise the phone's own.
                            pc = if (state.pc.online) ({ station.playMessage(task.id, m.id) }) else null,
                            onTryAgain = again?.let { o -> { tried = m.id; station.reply(task.id, o.text) } },
                            meta = { ReplyMeta(m) { whyOf = m } },
                            metaUnder = if (m.switched != null) ({ SwitchLine(m, replyBefore(task.messages, m)) { whyOf = m } }) else null,
                        ) {
                            MarkdownText(m.text, style = ChatLook.MessageText, color = Palette.Text)
                        }
                    } else if (m.waiting) OwnMessage(
                        m.text, bubble = Palette.QueuedBubble, dashed = true, id = "task-${task.id}-${m.id}",
                        meta = { ownMeta(m) },
                        under = {
                            if (m.failedWhy.isNotEmpty()) Column {
                                StatusWords(Glyphs.Clock, "Didn't send: ${m.failedWhy} Your words are kept.", Palette.AmberNote)
                                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    LinkText("Try again", { station.retryReply(m.id) }, bold = true)
                                    LinkText("Remove it", { station.removeReply(m.id) })
                                }
                            } else StatusWords(
                                Glyphs.Clock,
                                if (!state.phoneOnline) "This phone is offline: sends when it's back" else if (task.sign?.sending == true) "Sending…" else "Waiting: sends when your PC is back",
                                Palette.TextDim,
                            )
                        },
                    ) else AttachedAware(m.text) { shown, chips ->
                      if (shown.isNotBlank()) OwnMessage(
                        shown, id = "task-${task.id}-${m.id}",
                        meta = { ownMeta(m) },
                        under = chips,
                        onEdit = if (quiet && m.id == lastOwn?.id && io.github.odnwks.mimir.link.Wire.entryIndex(task.id, m.id) != null) {
                            { words -> editFailure = null; station.editTaskMessage(task.id, m.id, words.trim()) { why -> editFailure = why } }
                        } else null,
                        editNote = EDIT_TASK_NOTE, editFailure = editFailure, onEditCancel = { editFailure = null },
                      ) else Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) { chips?.invoke(); ownMeta(m) }
                    }
                    if (task.steps.isNotEmpty() && m.id == stepsAfter) Steps(task, stepsOpen) { stepsOpen = !stepsOpen }
                    if (m.id == statusAfter) statusCard()
                }
                // Thinking: three dots on the AI's side under the owner's last message, until the answer's words begin.
                if (working && task.permission == null && task.question == null &&
                    task.messages.lastOrNull { !it.note }?.fromClaude == false
                ) ThinkingBubble()
                // Carried on in a fresh task: a way to open it, under the note that says so.
                if (task.messages.lastOrNull()?.let { it.note && it.text == CARRIED_ON_NOTE } == true) {
                    val next = state.tasks.filter { it.project == task.project && it.title == "${task.title.trim()} (continued)" }.lastOrNull()
                    if (next != null) LinkText("Open the new task", { nav.open(Screen.Task(next.id)) }, bold = true)
                }
                // A task that hasn't answered yet says so, instead of a blank page.
                if (task.stateWords == "Starting") Panel(background = Palette.Bar) {
                    Text("Starting… The first reply shows here.", style = Type.Label, color = Palette.TextDim)
                }
                // Last, under the conversation it ended.
                // "Stopped · Continue", and whether a command was cut off by the stop.
                if (task.stopPending) StatusWords(Glyphs.Clock, STOP_WAITING, Palette.TextDim)
                // A followed session has its own buttons (Carry on here): one way to carry on, not two.
                if (task.stateWords in setOf("Stopped", "Stopped unexpectedly", "Paused") && !(listsSessions && followed && !carried)) Panel(background = Palette.Bar) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (task.stateWords == "Stopped unexpectedly") "Stopped unexpectedly ·" else "${task.stateWords} ·", style = Type.Label, color = if (task.stateWords == "Stopped unexpectedly") Palette.AmberNote else Palette.TextDim)
                        LinkText("Continue", { station.continueTask(task.id); cutOff = false }, bold = true)
                    }
                    if (cutOff || task.steps.lastOrNull()?.startsWith("…") == true) Text("A command may have been cut off.", style = Type.Small, color = Palette.AmberNote)
                }
            }
            }

            if (unseen) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                val toEnd = androidx.compose.runtime.rememberCoroutineScope()
                QuietButton("New lines ↓", "Go to the newest lines") { toEnd.launch { scroll.animateScrollTo(scroll.maxValue); atBottom = true; unseen = false } }
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

            // The question, slim: one quoted line just above the reply box; a reply makes it go away.
            var answeredQ by rememberSaveable(task.id) { mutableStateOf<String?>(null) }
            task.question?.takeIf { it.itemId != answeredQ && !task.waitingToStart }?.let { q ->
                val quote = questionQuote(q, task.messages.lastOrNull { it.fromClaude }?.text)
                if (quote.isNotBlank()) QuestionLine(
                    q, quote,
                    onChoice = { i -> answeredQ = q.itemId; station.answerChoice(q.itemId, i) },
                    onYes = { nav.openSheet(Sheet.Release(task.id)) },
                    onPlay = { task.messages.lastOrNull { it.fromClaude }?.let { station.playMessage(task.id, it.id) } },
                )
            }
            // Going on in a terminal or the Claude app right now: replies go there (one from here
            // would start a second Claude on it), so a note, as on the PC, takes the box's place.
            if (task.waitingToStart) Panel(background = Palette.Bar, modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (task.failedWhy.isNotEmpty()) {
                        Text("This task didn't start: ${task.failedWhy} Your words are kept.", style = Type.Label, color = Palette.AmberNote)
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            LinkText("Try again", { station.retryWaitingTask(task.id) }, bold = true)
                            LinkText("Remove", { station.cancelWaitingTask(task.id); nav.closeScreen() }, bold = true)
                        }
                    } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (state.pcAway) "Waiting for your PC ·" else "Starting…", style = Type.Label, color = Palette.TextDim)
                        if (task.canCancel) LinkText("Cancel this task", { station.cancelWaitingTask(task.id); nav.closeScreen() }, bold = true)
                    }
                }
            } else androidx.compose.animation.AnimatedContent(
                if (listsSessions) liveNow else task.liveElsewhere,
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
                    // Pictures and files go to the PC with the reply (a PC that takes them); the model and effort are chosen beside the box.
                    val attach = rememberAttach(bigPictures = true, pcReadsFiles = true, key = "task-${task.id}")
                    var sendingReply by remember { mutableStateOf(false) }
                    val canAttach = station.canAttachToTasks()
                    val canOptions = station.canSetReplyOptions()
                    Composer(
                        draft, { draft = it },
                        attach = attach.takeIf { canAttach },
                        // An old PC takes a model but no effort: the chip names the model only, the note says why.
                        optionsChip = optionsWords(task.model, task.effort, withEffort = canOptions),
                        optionsShort = optionsShorter(task.model, task.effort, withEffort = canOptions),
                        onOptions = { nav.openSheet(Sheet.TaskModel(task.id)) },
                        optionsNote = UPDATE_PC_FOR_OPTIONS.takeIf { !canOptions && state.paired },
                        hint = if (state.pcAway) "Reply (${state.waitsUntil})" else "Reply to Mímir",
                        label = "Reply to this task", voice = voice, micLabel = "Talk (voice typing): speak a reply",
                        onSend = {
                            // "status" is answered by the card, here on the phone: nothing goes to Claude.
                            if (asksStatus(draft) && attach.pictures.isEmpty() && attach.files.isEmpty() &&
                                // After a message whose id stays: a reply still being written, or waiting here, gets a new one.
                                askStatus("task:${task.id}", task.messages.lastOrNull { !it.waiting && !it.id.endsWith("-live") }?.id.orEmpty(), station)
                            ) {
                                draft = ""; focus.clearFocus()
                            } else
                            // The pictures are saved off the main thread; the box keeps its content until they are.
                            if (!sendingReply) {
                                sendingReply = true
                                station.replyAsync(task.id, draft.trim(), attach.pictures.toList(), attach.files.toList(), typed = true) { taken ->
                                    sendingReply = false
                                    if (taken) { draft = ""; attach.clear(); answeredQ = task.question?.itemId ?: answeredQ }
                                }
                            }
                        },
                        // Stop is at the top right, beside the title; the reply box keeps only Send.
                        modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                            .padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 8.dp),
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
            // A task last seen working while the PC is away is not finished: no cross for what may still be going.
            val working = task.busy || task.stateWords == "Was working (PC away)"
            val words = listOfNotNull(
                // While it works nothing is called "not done": a step that failed may well be tried again.
                if (working) "${count(task.steps.size, "step")} so far" else if (going > 0) "${count(done + going, "step")} so far" else "${count(done, "step")} done",
                if (going > 0 && !working) "$going waiting" else null,
                if (failed > 0 && !working) "$failed not done" else null,
            ).joinToString(", ")
            when {
                // A red cross is for work that ended with something not done, not for progress that is still going.
                failed > 0 && !working -> Text("✕", style = Type.Label, color = Palette.Red)
                going > 0 || working || failed > 0 -> Icon(Glyphs.Clock, null, tint = Palette.TextDim, modifier = Modifier.size(18.dp))
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
            // The steps' words can be selected and copied; their numbers are not part of the words.
            SelectableWords {
                Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    task.steps.forEachIndexed { i, s ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DisableSelection { Text("${i + 1}.", style = Type.Label.copy(lineHeight = Type.Label.fontSize * 1.5f), color = Palette.TextDim) }
                            Text(s, style = Type.Label.copy(lineHeight = Type.Label.fontSize * 1.5f), color = Palette.TextDim)
                        }
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

/** N3: whether a new line pulls the view down: at the bottom already, the person's own message, or the keyboard opening. */
internal fun followsBottom(atBottom: Boolean, lastIsOwn: Boolean, keyboard: Boolean): Boolean = atBottom || lastIsOwn || keyboard

/** Under the edit box of a task message: what sending it does. */
const val EDIT_TASK_NOTE = "Your edited message is sent as a new message; earlier replies stay."

/** The note while a conversation goes on elsewhere, when this PC can't list its sessions (the PC window shows its own version). */
const val FOLLOWS_ALONG = "Still running on your PC. This view follows along live."

/** Where a task going on elsewhere runs: "the Claude app" or "a terminal"; null when the PC doesn't say. */
fun followedIn(state: StationState, task: io.github.odnwks.mimir.data.Task): String? {
    when (task.elsewhereIn) { "app" -> return "the Claude app"; "terminal" -> return "a terminal" }
    val s = state.pcSessions?.firstOrNull { it.task == task.id } ?: return null
    return if (s.app) "the Claude app" else "a terminal"
}

/** True when the PC's list says this task's session is working in a terminal or the Claude app. */
fun followedElsewhere(state: StationState, taskId: String): Boolean =
    state.pcSessions?.any { it.task == taskId && it.state == io.github.odnwks.mimir.data.PcSessionState.Working && !it.here } == true

/** The one line over a task that is going on elsewhere (a PC that doesn't say where: "your PC"). */
fun followBannerText(where: String?) =
    if (where == null) "Still running on your PC. Stop it to carry on here." else "Still running in $where. Stop it to carry on here."

/** The PC's note that a command it started may still run. */
const val BACKGROUND_NOTE = "Still running something it started in the background."

/** The PC's own notes in a followed session, which the line above and the buttons below replace. */
fun isFollowNote(text: String) =
    text == "Running in another app; you can follow it here." || text == "Opened from an earlier session. Send a message to carry on."

/** What the PC says under a task that was carried on in a fresh one. */
const val CARRIED_ON_NOTE = "Carried on in a fresh task."

/** Under a task whose Stop could not reach the PC (as under a reply that waits). */
const val STOP_WAITING = "Stop waiting: sends when your PC is back"

const val FOLLOW_STOP = "Stop"
const val FOLLOW_CARRY_ON = "Carry on here"

/** A dismissal holds until the conversation turns very long, once; one made at very long holds for good. */
internal fun longHidden(dismissedAtVery: Boolean?, n: LongNotice): Boolean = dismissedAtVery != null && (dismissedAtVery || !n.very)

/** "This conversation is long…": the line, its size in book pages, and the one button (none while it goes on elsewhere). */
@Composable
internal fun LongLine(n: LongNotice, starting: Boolean, onStartFresh: () -> Unit, onDismiss: () -> Unit) {
    Panel(background = Palette.Bar, border = if (n.very) Palette.AmberBorder else Palette.Line, spacing = 6.dp, padding = PaddingValues(12.dp)) {
        Text(n.line, style = Type.Label, color = if (n.very) Palette.AmberText else Palette.TextSoft)
        n.size?.let { Text(it, style = Type.Small, color = Palette.TextDim) }
        if (!n.elsewhere) BigButton(if (starting) "Starting a fresh task…" else "Start fresh with a summary", Look.Outline, onStartFresh, height = 48.dp, enabled = !starting)
        QuietButton("Not now", "Hide this line", onClick = onDismiss)
    }
}

@Composable
private fun FollowBanner(where: String?) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.GreenCard).border(1.dp, Palette.GreenBorder, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 9.dp)
            .semantics(mergeDescendants = true) { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Dot(Palette.Green)
        Text(followBannerText(where), style = Type.Small, color = Palette.GreenText)
    }
}

/** In place of the reply box while a session from the PC is followed: Stop while it runs, Carry on here (only) once it has stopped. */
@Composable
private fun FollowButtons(live: Boolean, onStop: () -> Unit, onCarryOn: () -> Unit, modifier: Modifier) {
    Row(modifier.fillMaxWidth()) {
        if (live) StopControl(onStop, shape = StopShape.Wide, fill = Palette.RedBorder, label = FOLLOW_STOP)
        else BigButton(FOLLOW_CARRY_ON, Look.Primary, onCarryOn, height = 48.dp)
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

/**
 * The task top's line: state, mode, project, then what it's doing. The state and mode tags are measured
 * first, so they always show whole; the project name takes what is left (up to 96 dp) and is shortened
 * with "…", and the doing-now line gets the rest.
 */
@Composable
private fun TaskFacts(tags: @Composable () -> Unit, project: @Composable () -> Unit, now: @Composable () -> Unit) {
    androidx.compose.ui.layout.Layout(listOf(tags, project, now)) { (tagged, projects, doing), limits ->
        val gap = 6.dp.roundToPx()
        var left = limits.maxWidth
        fun take(m: androidx.compose.ui.layout.Measurable, most: Int = left) =
            m.measure(androidx.compose.ui.unit.Constraints(maxWidth = minOf(most, left).coerceAtLeast(0))).also { if (it.width > 0) left -= it.width + gap }
        val shownTags = tagged.map { take(it) }
        val name = projects.map { take(it, 96.dp.roundToPx()) }
        val line = doing.map { take(it) }
        val all = (shownTags + name + line).filter { it.width > 0 }
        val height = all.maxOfOrNull { it.height } ?: 0
        layout(limits.maxWidth, height) {
            var x = 0
            all.forEach { it.placeRelative(x, (height - it.height) / 2); x += it.width + gap }
        }
    }
}
