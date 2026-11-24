package io.github.odnwks.mimir.ui

import io.github.odnwks.mimir.ui.adaptive.forDevice
import androidx.lifecycle.compose.collectAsStateWithLifecycle

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.odnwks.mimir.data.Mapping
import io.github.odnwks.mimir.data.Answer
import io.github.odnwks.mimir.data.Station
import io.github.odnwks.mimir.data.StationState
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.nav.Sheet
import io.github.odnwks.mimir.ui.theme.Motion
import io.github.odnwks.mimir.ui.theme.Palette
import io.github.odnwks.mimir.ui.theme.Type
import io.github.odnwks.mimir.ui.theme.motionSpec

/**
 * The choices that ask first (board 5). Each slides up from the bottom, near the thumb;
 * the safe choice is always the bottom button.
 */
@Composable
fun SheetHost(state: StationState, station: Station, nav: Navigator) {
    var shown by remember { mutableStateOf<Sheet?>(null) }
    nav.sheet?.let { shown = it }
    val open = nav.sheet != null
    // A sheet puts the keyboard away first, so it doesn't slide down under the finger as the
    // keyboard leaves (a tap then lands beside it and just closes it).
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    androidx.compose.runtime.LaunchedEffect(open) { if (open) { keyboard?.hide(); focus.clearFocus() } }
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(open, enter = fadeIn(motionSpec(Motion.SHEET_MS)), exit = fadeOut(motionSpec(Motion.SHEET_MS))) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = "Close") { nav.closeSheet() }
                    .semantics { contentDescription = "Close" },
            )
        }
        // From 600 dp of width a sheet is a small dialog in the middle (640 dp at most), fading in.
        val dialog = io.github.odnwks.mimir.ui.adaptive.rememberWindowShape().dialogs
        val corners = if (dialog) RoundedCornerShape(24.dp) else RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        AnimatedVisibility(
            open, Modifier.align(if (dialog) Alignment.Center else Alignment.BottomCenter).padding(if (dialog) 24.dp else 0.dp),
            enter = if (dialog) fadeIn(motionSpec(Motion.SHEET_MS)) + androidx.compose.animation.scaleIn(motionSpec(Motion.SHEET_MS), initialScale = 0.96f)
                else slideInVertically(motionSpec(Motion.SHEET_MS)) { it },
            exit = if (dialog) fadeOut(motionSpec(Motion.SHEET_MS)) else slideOutVertically(motionSpec(Motion.SHEET_MS)) { it },
        ) {
            val sheet = shown ?: return@AnimatedVisibility
            BoxWithConstraints {
                val max = maxHeight * 0.9f
                Column(
                    Modifier.fillMaxWidth().then(if (dialog) Modifier.widthIn(max = io.github.odnwks.mimir.ui.adaptive.Limits.Sheet) else Modifier).heightIn(max = max)
                        .clip(corners).background(Palette.Card)
                        .border16(Palette.LineStrong, 24)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                        .semantics { paneTitle = sheet.title(state); isTraversalGroup = true; traversalIndex = -1f }
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .verticalScroll(rememberScrollState()),
                ) {
                    SheetContent(sheet, state, station, nav)
                }
            }
        }
    }
}

private fun Sheet.title(state: StationState) = when (this) {
    is Sheet.TaskOptions, Sheet.IncognitoOptions -> "Task options"
    is Sheet.StopTask -> "Stop this task?"
    is Sheet.AlwaysAllowed -> "Always allowed in this task"
    is Sheet.AlwaysAllow -> "Always allow edits to ${state.task(taskId)?.permission?.file ?: "this file"}?"
    is Sheet.Release -> "Release $AI_NAME ${state.task(taskId)?.question?.releaseVersion ?: ""}?"
    Sheet.EndIncognito -> "End and forget?"
    Sheet.Unpair -> "Unpair this phone?"
    is Sheet.ProjectPicker -> "Project"
    Sheet.WhereTo -> "Where should this go?"
    Sheet.ComposeModel -> "Model for this task"
    Sheet.ComposeEffort -> "Effort for this task"
    is Sheet.RenameTask -> "Rename this task"
    is Sheet.TaskModel -> "Model for this task"
    is Sheet.Yolo -> "Go ahead without asking in this task?"
    is Sheet.ChatOptions -> topic ?: "This chat"
}

@Composable
private fun SheetContent(sheet: Sheet, state: StationState, station: Station, nav: Navigator) {
    val standIn = LocalStandIn.current
    val undo = LocalUndo.current
    when (sheet) {
        is Sheet.TaskOptions -> Menu(sheet.title(state)) {
            val task = state.task(sheet.taskId)
            val id = sheet.taskId
            // Going on in a terminal or the Claude app: it is not Mímir's task to steer, only to follow, rename or put away.
            val elsewhere = task != null && !task.stopped && (task.liveElsewhere || followedElsewhere(state, id))
            if (task?.change != null) MenuRow(Glyphs.NewDocument, "Show the change") { nav.open(Screen.Change(id)) }
            // Continue only where there is something to carry on: a finished task has the reply box.
            if (!elsewhere) when (task?.stateWords) {
                "Working" -> MenuRow(Glyphs.Pause, "Pause") { station.pauseTask(id); nav.closeSheet() }
                "Paused", "Stopped", "Stopped unexpectedly" -> MenuRow(Glyphs.Play, "Continue") { station.continueTask(id); nav.closeSheet() }
                else -> {}
            }
            MenuRow(Glyphs.Steps, "Rename…") { nav.openSheet(Sheet.RenameTask(id)) }
            // Last seen working and the PC is away: Pause and Stop need it, and the menu says why they are missing.
            if (task?.stateWords == "Was working (PC away)") Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 12.dp).semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(Glyphs.Stop, null, tint = Palette.TextDim, modifier = Modifier.size(22.dp))
                Text("Pause and Stop need your PC. It's away.", style = Type.BodyTight, color = Palette.TextDim, modifier = Modifier.weight(1f))
            }
            if (!elsewhere) {
                // A task moves with its whole conversation, but never while Claude works on it.
                if (task?.status == "Working") {
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 12.dp).semantics(mergeDescendants = true) {},
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Icon(Glyphs.Move, null, tint = Palette.TextDim, modifier = Modifier.size(22.dp))
                        Text("Move to another project (when it stops)", style = Type.BodyTight, color = Palette.TextDim, modifier = Modifier.weight(1f))
                    }
                } else MenuRow(Glyphs.Move, "Move to another project…") { nav.openSheet(Sheet.ProjectPicker(task?.project.orEmpty())) }
                MenuRow(Glyphs.Models, "Change the model…") { nav.openSheet(Sheet.TaskModel(id)) }
                // The mode in use carries a tick.
                MenuRow(Glyphs.Steps, "Ask me first before changes", checked = task?.mode == "Ask") { station.setTaskPlan(id, false); nav.closeSheet() }
                MenuRow(Glyphs.Steps, "Plan first, change nothing yet", checked = task?.plan == true) { station.setTaskPlan(id, true); nav.closeSheet() }
                // YOLO: never in Whisper; a full warning every time, then the phone's unlock.
                if (task?.whisper != true) MenuRow(Glyphs.Steps, "Go ahead without asking (this task)…", checked = task?.yolo == true) {
                    if (task?.yolo != true) nav.openSheet(Sheet.Yolo(id)) else nav.closeSheet()
                }
                MenuRow(Glyphs.Shield, "Always allowed in this task…") { nav.openSheet(Sheet.AlwaysAllowed(id)) }
            }
            MenuRow(Glyphs.Folder, "Archive") {
                station.archiveTask(id); nav.closeSheet(); nav.back()
                undo(Undoable("Archived ${task?.title ?: "the task"}", undo = { station.unarchiveTask(id) }))
            }
            // Stop is for work that runs: the Stop button is on the task itself while it does.
            if (task != null && task.showsStop && !task.stopPending && (!elsewhere || state.pcSessions == null)) MenuRow(Glyphs.Stop, "Stop this task…", Palette.Red) { nav.openSheet(Sheet.StopTask(id)) }
            if (!elsewhere) PrivacyLine("Your PC does this task. This phone and your PC talk privately.")
        }
        is Sheet.ChatOptions -> Menu(sheet.title(state)) {
            // Only things about this chat (the voice settings are in Settings › Voice).
            val ask = { a: io.github.odnwks.mimir.nav.ChatAsk -> nav.closeSheet(); nav.askChat(a) }
            if (sheet.canRename) ChatMenuRow(CHAT_RENAME) { ask(io.github.odnwks.mimir.nav.ChatAsk.Rename) }
            ChatMenuRow(CHAT_COPY) { ask(io.github.odnwks.mimir.nav.ChatAsk.CopyChat) }
            ChatMenuRow(CHAT_RULES, CHAT_RULES_ABOUT) { ask(io.github.odnwks.mimir.nav.ChatAsk.Rules) }
            val start = OpenChat.sentAtOpen
            var sent by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0L) }
            androidx.compose.runtime.LaunchedEffect(start) {
                while (start != null) {
                    sent = io.github.odnwks.mimir.data.NetUse.sentNow()?.let { now -> if (start < 0 || now < start) 0L else now - start } ?: 0L
                    kotlinx.coroutines.delay(2_000L)
                }
            }
            // This chat's own numbers when it counts them; otherwise what the app sent since it opened.
            val line = if (sheet.sentToPc >= 0) chatSentLine(sheet.sentToPc, sheet.lookups, sheet.online) else sentWords(sent, sheet.pc, sheet.online)
            ChatMenuRow(CHAT_SENT, line) { if (sheet.sentToPc >= 0) ask(io.github.odnwks.mimir.nav.ChatAsk.Sent) else { nav.closeSheet(); nav.open(Screen.PrivacyCheck) } }
            if (sheet.canRename) ChatMenuRow(CHAT_DELETE, CHAT_DELETE_ABOUT, Palette.Red) { ask(io.github.odnwks.mimir.nav.ChatAsk.Delete) }
        }
        is Sheet.AlwaysAllowed -> {
            // Asked of the PC each time the sheet opens; each file can be taken back.
            var files by androidx.compose.runtime.remember(sheet.taskId) { androidx.compose.runtime.mutableStateOf<List<String>?>(null) }
            var failed by androidx.compose.runtime.remember(sheet.taskId) { androidx.compose.runtime.mutableStateOf(false) }
            androidx.compose.runtime.LaunchedEffect(sheet.taskId) {
                station.alwaysAllowed(sheet.taskId) { got -> if (got == null) failed = true else files = got }
            }
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Handle() }
                SectionTitle(sheet.title(state), style = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold))
                val list = files
                when {
                    failed -> Text("Your PC is away: it says what's allowed when it's back.", style = Type.Label, color = Palette.TextSoft)
                    list == null -> Text("Asking your PC…", style = Type.Label, color = Palette.TextDim)
                    list.isEmpty() -> Text("Nothing is always allowed in this task. When Mímir asks to change a file, Always allow… lets it change that file for the rest of the task.", style = Type.Label, color = Palette.TextSoft)
                    else -> {
                        Text("Mímir changes these files without asking, for the rest of this task:", style = Type.Label, color = Palette.TextSoft)
                        list.forEach { f ->
                            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(f.substringAfterLast('/'), style = Type.BodyTight, color = Palette.Text, modifier = Modifier.weight(1f))
                                SmallButton("Stop allowing", { station.stopAllowing(sheet.taskId, f); files = list - f }, description = "Stop allowing ${f.substringAfterLast('/')}")
                            }
                        }
                    }
                }
                BigButton("Done", Look.Outline, { nav.closeSheet() }, height = 48.dp)
            }
        }
        is Sheet.RenameTask -> {
            var title by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(state.task(sheet.taskId)?.title.orEmpty()) }
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Handle() }
                SectionTitle(sheet.title(state), style = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold))
                MessageBox(title, { title = it }, "Task name", "Task name", Modifier.fillMaxWidth(), autoFocus = true)
                BigButton("Rename", Look.Primary, { station.renameTask(sheet.taskId, title); nav.closeSheet() }, height = 48.dp, enabled = title.isNotBlank())
                BigButton("Cancel", Look.Outline, { nav.closeSheet() }, height = 48.dp)
            }
        }
        is Sheet.TaskModel -> Menu(sheet.title(state)) {
            val now = state.task(sheet.taskId)?.model.orEmpty()
            (state.models.claude + state.models.local).forEach { m ->
                val current = now == Mapping.modelName(m.id) || now == m.name || now == m.id.removePrefix("ollama:") + " (free)" || (m.id == "auto" && now == Mapping.modelName(null))
                // Online ones go to Anthropic: marked, so it is plain which ones leave the PC.
                MenuRow(Glyphs.Models, plainModelName(m.name), checked = current, note = if (m.free) "On your PC" else "Online") { station.setTaskModel(sheet.taskId, m.id); nav.closeSheet() }
            }
            // Effort for this task from now on: only a PC that takes it with a message (task_reply_options).
            if (station.canSetReplyOptions()) {
                val effortNow = TaskEffort.of(state.task(sheet.taskId)?.effort?.ifEmpty { null })
                Text("Effort, from now on", style = Type.Small, color = Palette.TextDim, modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 2.dp))
                TaskEffort.entries.forEach { e ->
                    MenuRow(Glyphs.Models, e.words, checked = e == effortNow, note = e.about, tick = "Effort chosen") { station.setTaskEffort(sheet.taskId, e.wire); nav.closeSheet() }
                }
            } else Text(UPDATE_PC_FOR_OPTIONS, style = Type.Small, color = Palette.TextDim, modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 2.dp))
        }
        Sheet.IncognitoOptions -> Menu(sheet.title(state)) {
            PrivacyLine("Whisper mode: the internet is cut off for this task, nothing leaves your PC, and it's read aloud by this phone's voice only.")
            MenuRow(Glyphs.Stop, "End and forget…", Palette.Red) { nav.openSheet(Sheet.EndIncognito) }
        }
        is Sheet.Yolo -> YoloSheet(sheet.title(state), sheet.taskId, state, station, nav)
        is Sheet.StopTask -> Ask(
            sheet.title(state),
            "Work stops where it is. Changes so far stay on the PC and nothing is released. You can continue it later.",
            "Stop task", Look.Danger, { station.stopTask(sheet.taskId); nav.closeSheet() },
            "Keep going", { nav.closeSheet() },
        )
        is Sheet.AlwaysAllow -> {
            val p = state.task(sheet.taskId)?.permission
            Ask(
                sheet.title(state),
                "For this task only. Mímir won't ask again before changing this one file. Other files and commands still ask. It ends when the task ends.",
                (if (p?.kind == io.github.odnwks.mimir.data.PermissionKind.Command) "Always allow this command in this task" else "Always allow this file in this task"), Look.Outline, { p?.let { station.answer(it.itemId, Answer.AlwaysAllowFile) }; nav.closeSheet() },
                "Cancel", { nav.closeSheet() },
            )
        }
        is Sheet.Release -> {
            val q = state.task(sheet.taskId)?.question
            Ask(
                sheet.title(state),
                "It goes up on GitHub after the checks pass. Anyone who updates gets it. You get 10 seconds to undo.",
                "Release ${q?.releaseVersion ?: ""}".trim(), Look.ReleaseOutline, {
                    // Sent only after the ten seconds to undo.
                    q?.let { undo(Undoable("Releasing ${it.releaseVersion}".trim(), later = { station.answer(it.itemId, Answer.Release) })) }
                    nav.closeSheet()
                },
                "Not now", { nav.closeSheet() },
            )
        }
        Sheet.EndIncognito -> Ask(
            sheet.title(state),
            "Ending the Whisper forgets it: nothing of it is kept on the PC or this phone. Changed files stay changed.",
            "End and forget", Look.Danger, { station.endIncognito(); nav.resetTo(Screen.Chat) },
            "Keep going", { nav.closeSheet() },
        )
        is Sheet.ProjectPicker -> ProjectPicker(sheet.current, state, station, nav, sheet.newProject, sheet.back, sheet.open, sheet.justChat)
        Sheet.WhereTo -> WhereToSheet(state, nav, canEffort = station.canSetReplyOptions())
        Sheet.ComposeModel -> ComposeModelSheet(state, nav)
        Sheet.ComposeEffort -> ComposeEffortSheet(nav)
        Sheet.Unpair -> Ask(
            sheet.title(state),
            "This phone forgets your PC at once. To cut it off on the PC side too, remove it in $AI_NAME on the PC (Settings › Phones). You can pair again later with a new code.",
            "Unpair this phone", Look.Danger, { nav.closeSheet(); station.removePhone("this"); nav.resetTo(Screen.Chat) },
            "Cancel", { nav.closeSheet() },
        )
    }
}

/** The chat menu's rows, word for word. */
const val CHAT_RENAME = "Rename"
const val CHAT_COPY = "Copy the whole chat"
const val CHAT_RULES = "Rules for this chat"
const val CHAT_RULES_ABOUT = "Things $AI_NAME should always do here"
const val CHAT_SENT = "What was sent"
const val CHAT_DELETE = "Delete this chat"
const val CHAT_DELETE_ABOUT = "You can undo for a few seconds"

/** The small line under "What was sent": how much left this phone during the chat, and where to. */
fun sentWords(sent: Long, pc: Boolean, online: String? = null): String = when {
    online != null -> "your messages go to $online"
    pc -> "${sizeWords(sent)}, only to your PC"
    sent == 0L -> "nothing left this phone"
    else -> "${sizeWords(sent)}, only the words looked up"
}

/** "What was sent" for one chat in a few words: what went to the PC, how many lookups, or that nothing left. */
fun chatSentLine(sentToPc: Long, lookups: Int, online: String? = null): String = when {
    online != null -> "your messages go to $online"
    sentToPc > 0 -> "${bytesWords(sentToPc)} to your PC" + if (lookups > 0) ", ${lookupsWords(lookups)}" else ""
    lookups > 0 -> "${lookupsWords(lookups)}, only the words looked up"
    else -> "nothing left this phone"
}

private fun lookupsWords(n: Int) = if (n == 1) "1 lookup" else "$n lookups"

/** A small count of bytes in plain words ("under 1 KB", "4 KB"). */
fun bytesWords(b: Long): String = if (b < 1000) "under 1 KB" else sizeWords(b)

/** What one chat sent, line by line: to the PC, to an online model, and each lookup with the words searched. */
fun chatSentLines(chat: io.github.odnwks.mimir.standalone.LocalChat, pcAnswers: Boolean, online: String? = null): List<String> = buildList {
    when {
        online != null -> add("Your messages in this chat go to $online.")
        chat.sentToPc > 0 || pcAnswers -> add("Sent to your PC for this chat: ${bytesWords(chat.sentToPc)}. Only your PC gets it.")
        else -> add("Nothing from this chat has left this phone.")
    }
    val looked = chat.turns.filter { !it.user }.flatMap { it.sources }
    if (looked.isEmpty()) add("No lookups were made in this chat.")
    else {
        add(if (looked.size == 1) "1 lookup was made in this chat:" else "${looked.size} lookups were made in this chat:")
        looked.forEach { add("• ${it.site}: " + (it.words.ifBlank { "the page you opened" })) }
    }
}

/**
 * A small sheet over the chat (a dim background and a panel at the bottom, lifted by the keyboard): [title], what
 * [content] says, and its buttons. A tap outside closes it. Drawn in the screen itself, not in a window of its own.
 */
@Composable
fun ChatSheet(title: String, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = "Close", onClick = onClose)
                .semantics { contentDescription = "Close" },
        )
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = 560.dp)
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(Palette.Card)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .semantics { paneTitle = title }
                .windowInsetsPadding(WindowInsets.navigationBars).imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = Type.Section, color = Palette.Text, modifier = Modifier.semantics { heading() })
            content()
        }
    }
}


/** The small sheet behind "What was sent": this chat's numbers only. */
@Composable
fun ChatSentDialog(chat: io.github.odnwks.mimir.standalone.LocalChat, pcAnswers: Boolean, online: String?, onClose: () -> Unit) {
    ChatSheet(CHAT_SENT, onClose) {
        chatSentLines(chat, pcAnswers, online).forEach { Text(it, style = Type.Label, color = Palette.TextSoft) }
        BigButton("Done", Look.Outline, onClose, height = 48.dp)
    }
}

/** The small sheet behind "Rules for this chat": a text the model is told first, in this chat only. */
@Composable
fun ChatRulesDialog(current: String, onSave: (String) -> Unit, onClose: () -> Unit) {
    var text by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(current) }
    ChatSheet(CHAT_RULES, onClose) {
        Text("$AI_NAME is told these first, in this chat only. Your rules for every chat still apply.", style = Type.Label, color = Palette.TextSoft)
        MessageBox(text, { text = it }, "Rules for this chat", "For example: answer in French", Modifier.fillMaxWidth(), autoFocus = true)
        BigButton("Save", Look.Primary, { onSave(text) }, height = 48.dp)
        BigButton("Cancel", Look.Outline, onClose, height = 48.dp)
    }
}

/** One row of the chat menu: its words, and a small line under them. */
@Composable
private fun ChatMenuRow(title: String, small: String? = null, color: Color = Palette.Text, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = Type.BodyTight, color = color)
        if (small != null) Text(small.forDevice(), style = Type.Small, color = Palette.TextDim)
    }
}

/** How this chat or task stays private, in one small line with a lock. */
@Composable
private fun PrivacyLine(text: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(Glyphs.Lock, null, tint = Palette.TextDim, modifier = Modifier.size(22.dp))
        Text(text.forDevice(), style = Type.Small, color = Palette.TextSoft, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Handle() {
    Spacer(Modifier.size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Palette.Outline))
}

@Composable
private fun Menu(title: String, rows: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 10.dp, bottom = 12.dp)) {
        Box(Modifier.fillMaxWidth().padding(bottom = 8.dp), contentAlignment = Alignment.Center) { Handle() }
        SectionTitle(title, Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp),
            style = Type.SheetTitle, maxLines = 2)
        rows()
    }
}

@Composable
private fun MenuRow(icon: ImageVector, text: String, color: Color = Palette.Text, checked: Boolean? = null, note: String? = null, tick: String = "In use", onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(12.dp))
            .then(
                if (checked != null) Modifier.selectable(checked, role = Role.RadioButton, onClick = onClick)
                else Modifier.clickable(role = Role.Button, onClick = onClick),
            )
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f).padding(vertical = if (note != null) 6.dp else 0.dp)) {
            Text(text, style = Type.BodyTight, color = color)
            if (note != null) Text(note, style = Type.Small, color = Palette.TextDim)
        }
        if (checked == true) Icon(Glyphs.Check, tick, tint = Palette.BlueText, modifier = Modifier.size(20.dp))
    }
}

/**
 * Turning on YOLO for a task: the warning, then the phone's unlock, then the PC's answer, shown
 * here. It never fails silently: with "Let my phone turn on Go ahead without asking" off on the PC, the sheet says so
 * and where to switch it on, and any other refusal is said in the sheet too.
 */
@Composable
private fun YoloSheet(title: String, taskId: String, state: StationState, station: Station, nav: Navigator) {
    val confirm = LocalUnlockConfirm.current
    var asking by androidx.compose.runtime.remember(taskId) { androidx.compose.runtime.mutableStateOf(false) }
    var problem by androidx.compose.runtime.remember(taskId) { androidx.compose.runtime.mutableStateOf<String?>(null) }
    val off = state.yoloFromPhone == false
    Column(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Handle() }
        SectionTitle(title, style = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold))
        Text(if (off) YOLO_OFF_ON_PC else YOLO_WARNING, style = Type.Label.copy(lineHeight = Type.Label.fontSize * 1.45f),
            color = if (off) Palette.AmberText else Palette.TextDim)
        problem?.takeIf { !off }?.let { Text(it, style = Type.Label, color = Palette.Red, modifier = Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }) }
        BigButton(
            when { asking -> "Asking your PC…"; off -> "Try again"; else -> "Unlock to go ahead without asking" },
            Look.Outline,
            {
                confirm("Go ahead without asking") {
                    asking = true; problem = null
                    station.turnOnYolo(taskId) { err ->
                        asking = false
                        if (err == null) nav.closeSheet() else problem = err
                    }
                }
            },
            bold = false, enabled = !asking,
        )
        BigButton("Keep asking first", Look.Primary, { nav.closeSheet() })
    }
}

/** Said in the YOLO sheet while the PC doesn't allow YOLO from the phone. */
const val YOLO_OFF_ON_PC = "Go ahead without asking is off for this phone on your PC, so it stays off here. " +
    "To allow it: on your PC, open Mímir › Settings › Phones and switch on \u201cLet my phone turn on Go ahead without asking\u201d."

@Composable
private fun Ask(
    title: String, body: String,
    action: String, actionLook: Look, onAction: () -> Unit,
    safe: String, onSafe: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Handle() }
        SectionTitle(title, style = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold))
        Text(body.forDevice(), style = Type.Label.copy(lineHeight = Type.Label.fontSize * 1.45f), color = Palette.TextDim)
        BigButton(action, actionLook, onAction, bold = false)
        BigButton(safe, Look.Primary, onSafe)
    }
}

/** What YOLO lets Claude do, shown in full every time before it turns on. */
const val YOLO_WARNING = "Mímir will not ask first. On your PC it can change and delete files, run programs and use the internet, all without asking.\n\n" +
    "Anyone holding this phone unlocked could do the same.\n\n" +
    "This task only. It goes back to Ask me first after 5 hours of work or 24 hours idle.\n\n" +
    "Your PC must allow it: Mímir › Settings › Phones."
