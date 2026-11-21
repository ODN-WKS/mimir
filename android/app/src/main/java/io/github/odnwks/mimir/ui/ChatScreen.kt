package io.github.odnwks.mimir.ui

import io.github.odnwks.mimir.ui.theme.ChatTextSize
import io.github.odnwks.mimir.ui.theme.LocalAppSize
import io.github.odnwks.mimir.ui.theme.pinchToZoomText
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.odnwks.mimir.data.ChatEntry
import io.github.odnwks.mimir.data.FilingKind
import io.github.odnwks.mimir.data.count
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import io.github.odnwks.mimir.data.Station
import io.github.odnwks.mimir.data.StationState
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.ui.theme.LocalReduceMotion
import io.github.odnwks.mimir.ui.theme.Motion
import io.github.odnwks.mimir.ui.theme.Palette
import io.github.odnwks.mimir.ui.theme.Type
import io.github.odnwks.mimir.ui.theme.motionSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role

/** Chat (board 1), and the same screen with the PC asleep (board 18). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(state: StationState, station: Station, nav: Navigator, initialDraft: String = "") {
    val standIn = LocalStandIn.current
    val tap = LocalTapTracker.current
    var draft by rememberSaveable { mutableStateOf(initialDraft) }
    // The message whose "Move it" is open: its projects show under it.
    var moving by rememberSaveable { mutableStateOf<String?>(null) }
    val online = state.pc.online
    // The Whisper eye, as on this phone's own chat: a Whisper here (Mímir) or on the PC (its free model).
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val standaloneOk = LocalStandaloneOk.current
    val given = LocalPhoneEngine.current
    val engine = androidx.compose.runtime.remember(given, standaloneOk) { given ?: if (standaloneOk) io.github.odnwks.mimir.standalone.Engine.get(ctx) else null }
    val phone = engine?.state?.collectAsStateWithLifecycle()?.value
    val phoneCanRun = phone?.supported == true
    val places = whisperPlaces(phone = phoneCanRun && phone?.ready == true && !phone.writing, pc = state.models.local.isNotEmpty())
    val to = nav.composeTo
    var whereAsk by androidx.compose.runtime.remember { mutableStateOf(false) }
    var needsAsk by androidx.compose.runtime.remember { mutableStateOf(false) }
    var whoOpen by androidx.compose.runtime.remember { mutableStateOf(false) }
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    // While it's open, the chat menu says how much was sent.
    WatchWhatThisChatSends(true)
    val startOnPhone = {
        engine?.startWhisper()
        nav.open(Screen.Standalone)
    }
    val startOnPc = { nav.composeTo = nav.composeTo.copy(whisper = true) }
    val startWhisper = {
        when (places.size) {
            2 -> whereAsk = true
            1 -> if (places[0] == WhisperPlace.Phone) startOnPhone() else startOnPc()
            else -> needsAsk = true
        }
        Unit
    }
    // Settings' search ("whisper", "private") hands its Start a Whisper here.
    LaunchedEffect(nav.chatAsk) {
        val r = nav.chatAsk ?: return@LaunchedEffect
        if (nav.current != Screen.Chat) return@LaunchedEffect
        // The side menu's Whisper and New chat.
        when (r.ask) {
            io.github.odnwks.mimir.nav.ChatAsk.StartWhisper -> { nav.chatAsk = null; if (!to.whisper && state.incognito == null) startWhisper() }
            io.github.odnwks.mimir.nav.ChatAsk.NewChat -> { nav.chatAsk = null; if (to.whisper) nav.composeTo = to.copy(whisper = false); station.newChat() }
            // The Whisper row's long press: with internet, Mímir on this phone, through Tor (the PC is never used for it).
            io.github.odnwks.mimir.nav.ChatAsk.StartWebWhisper -> {
                nav.chatAsk = null
                if (phoneCanRun) {
                    if (phone?.ready == true && phone.writing == false) { nav.composeTo = to.copy(whisper = false); engine?.startWhisper(web = true); nav.open(Screen.Standalone) }
                    else needsAsk = true
                }
            }
            io.github.odnwks.mimir.nav.ChatAsk.CopyChat -> {
                nav.chatAsk = null
                val turns = state.chat.mapNotNull { e -> when (e) { is ChatEntry.Mine -> true to e.text; is ChatEntry.Reply -> false to markdownPlain(e.paragraphs.joinToString("\n\n")); else -> null } }
                clip.setText(androidx.compose.ui.text.AnnotatedString(chatAsPlainText(turns, "Mímir")))
            }
            else -> {}
        }
    }
    // A Whisper turns the whole chat teal (the colour fades, nothing jumps), as on this phone's own chat.
    val whispering = to.whisper || state.incognito != null
    val ground by androidx.compose.animation.animateColorAsState(if (to.whisper) Palette.GroundWhisper else Palette.Ground, motionSpec(Motion.SCREEN_MS), label = "ground")
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(ground).swipeForChats(LocalOpenMenu.current)) {
        // The menu button, who answers (the PC; a tap lists the chat's modes) and a new chat.
        MenuTopBar(
            waiting = menuCount(state), answerer = if (whispering) "Whisper" else "Your PC", online = if (whispering) null else state.pc.online,
            answererDescription = when { state.incognito != null -> OPEN_PC_WHISPER; to.whisper -> END_PC_WHISPER; else -> whoLabel("Your PC") },
            onNewChat = { if (to.whisper) nav.composeTo = to.copy(whisper = false); station.newChat() },
            instead = if (state.incognito != null) ({ nav.open(Screen.IncognitoTask, tap.last) }) else null,
            // A tap: who should answer (in a Whisper on the PC, it ends the Whisper).
            onPick = if (to.whisper) ({ nav.composeTo = to.copy(whisper = false) }) else ({ whoOpen = true }),
            onMore = if (!whispering && state.chat.any { it is ChatEntry.Mine || it is ChatEntry.Reply }) ({
                nav.openSheet(io.github.odnwks.mimir.nav.Sheet.ChatOptions(topic = null, canRename = false, whisper = false, canWhisper = false, pc = true))
            }) else null,
        )
        // Only while it matters (the PC away): one small line under the bar, so the bar itself never changes.
        if (!state.pc.online) Text(state.connectionLine, style = Type.Small, color = Palette.TextDim, maxLines = 2, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        WhisperWhere(whereAsk, onPhone = { whereAsk = false; startOnPhone() }, onPc = { whereAsk = false; startOnPc() }, onClose = { whereAsk = false })
        WhisperNeedsAModel(
            needsAsk, phoneCanRun, phone?.let { io.github.odnwks.mimir.standalone.Catalog.byId(it.model).sizeText },
            onGetMimir = { needsAsk = false; nav.open(Screen.Models) },
            onAddPc = { needsAsk = false; nav.open(Screen.Models) },
            onClose = { needsAsk = false },
        )
        ConnectionStrip(state, Modifier.padding(bottom = 4.dp), inner = false, onStandalone = if (LocalStandaloneOk.current) ({ nav.open(Screen.Standalone) }) else null)

        val list = rememberLazyListState()
        val reduce = LocalReduceMotion.current
        // A new message shows at once: the list follows the newest one.
        val newest = state.chat.lastOrNull()?.id
        LaunchedEffect(newest) {
            if (newest != null) { if (reduce) list.scrollToItem(0) else list.animateScrollToItem(0) }
        }
        // The keyboard opening shrinks the list: the newest message stays in view above the box.
        val keyboard = WindowInsets.isImeVisible
        LaunchedEffect(keyboard) {
            if (keyboard && list.firstVisibleItemIndex <= 1) {
                androidx.compose.runtime.withFrameNanos { }
                list.scrollToItem(0)
            }
        }
        // Newest at the bottom, the list starts there.
        val focus = LocalFocusManager.current
        ChatTextSize {
LazyColumn(
            // A tap on the conversation closes the keyboard.
            Modifier.weight(1f).fillMaxWidth().pinchToZoomText(LocalAppSize.current).pointerInput(Unit) { detectTapGestures(onTap = { focus.clearFocus() }) },
            state = list,
            reverseLayout = true,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.Bottom),
        ) {
            // Paired, nothing said yet: what this is for, and three ways to begin.
            if (state.paired && state.chat.isEmpty()) item(key = "empty") {
                EmptyState("Ask for anything. $AI_NAME files it in the right project.") {
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmallButton("What's the status?", { station.send("What's the status?") })
                        SmallButton("Fix…", { draft = "Fix " }, description = "Start a message: Fix")
                        SmallButton("Idea:…", { draft = "Idea: " }, description = "Start a message: Idea")
                    }
                }
            }
            items(state.chat.asReversed(), key = { it.id }) { entry ->
                val itemModifier = Modifier.animateItem(
                    fadeInSpec = motionSpec(Motion.NEW_ITEM_MS),
                    placementSpec = motionSpec(Motion.NEW_ITEM_MS),
                    fadeOutSpec = motionSpec(Motion.NEW_ITEM_MS),
                )
                when (entry) {
                    is ChatEntry.Mine -> OwnMessage(
                        entry.text, itemModifier,
                        bubble = if (entry.filing?.kind == FilingKind.Waiting) Palette.QueuedBubble else ChatLook.OwnBubble,
                        dashed = entry.filing?.kind == FilingKind.Waiting,
                        under = entry.filing?.let { f ->
                            {
                                val filedTask = f.taskId?.let { state.task(it) }
                                if (f.taskId != null && (f.kind == FilingKind.NewTask || f.kind == FilingKind.SameTask)) TaskLink(
                                    // Joined an open task: "Added to: Make the title bigger".
                                    if (f.kind == FilingKind.SameTask && filedTask != null) "Added to: ${filedTask.title}" else f.text,
                                    filedTask?.stateWords ?: if (state.pcAway) "" else "Getting it…",
                                ) { nav.open(Screen.Task(f.taskId), tap.last) }
                                else StatusWords(
                                    when (f.kind) {
                                        FilingKind.Waiting -> Glyphs.Clock
                                        FilingKind.Inbox, FilingKind.Failed -> Glyphs.Question
                                        else -> Glyphs.Check
                                    },
                                    // Waiting: say whose network is away, so the PC isn't blamed for the phone.
                                    if (f.kind == FilingKind.Waiting && !state.phoneOnline) "Waiting · sends when this phone is back online" else f.text,
                                    if (f.kind == FilingKind.Waiting || f.kind == FilingKind.Inbox || f.kind == FilingKind.Failed) Palette.TextDim else Palette.Green,
                                )
                                when (f.kind) {
                                    // The Inbox: the likeliest projects, one tap each.
                                    FilingKind.Inbox -> f.choices.forEach { p ->
                                        SmallButton(p, { station.fileInbox(entry.id, p) }, description = "File in $p: ${entry.text}")
                                    }
                                    FilingKind.NewTask, FilingKind.SameTask ->
                                        if (moving == entry.id) {
                                            state.projects.map { it.name }.filter { it != f.where }.forEach { p ->
                                                SmallButton(p, { station.moveMessage(entry.id, p); moving = null }, description = "Move to $p")
                                            }
                                            SmallButton("Cancel", { moving = null })
                                        } else {
                                            SmallButton(f.action, { moving = entry.id }, description = "${f.action}: ${entry.text}")
                                        }
                                    FilingKind.Failed -> {
                                        SmallButton("Try again", { station.retryMessage(entry.id) }, description = "Send again: ${entry.text}")
                                        SmallButton(f.action, onClick = { station.undoMessage(entry.id) }, description = "${f.action}: ${entry.text}")
                                    }
                                    else -> SmallButton(f.action, onClick = { station.undoMessage(entry.id) }, description = "${f.action}: ${entry.text}")
                                }
                            }
                        },
                    )
                    is ChatEntry.Reply -> {
                        val plain = markdownPlain(entry.paragraphs.joinToString("\n\n"))
                        // Try again: the lookup again when it failed, else the question before it, sent again.
                        val at = state.chat.indexOfFirst { it.id == entry.id }
                        val asked = state.chat.take(at.coerceAtLeast(0)).lastOrNull { it is ChatEntry.Mine } as? ChatEntry.Mine
                        Answer(
                            plain, "chat-${entry.id}", itemModifier,
                            onTryAgain = if (entry.retry) ({ station.retryAnswer(entry.id) }) else asked?.let { q -> { station.send(q.text) } },
                        ) {
                            entry.paragraphs.forEach { MarkdownText(it, style = ChatLook.MessageText, color = Palette.Text) }
                        }
                    }
                    is ChatEntry.IncognitoRunning -> Panel(
                        itemModifier, background = Palette.WhisperCard, border = Palette.WhisperBorder,
                        padding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                        onClick = { nav.open(Screen.IncognitoTask, tap.last) },
                    ) {
                        Row(Modifier.heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            MimirEye(EyeMode.Whisper, Modifier.size(34.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("Whisper task running · ${entry.project}", style = Type.Label.copy(fontWeight = FontWeight.SemiBold), color = Palette.Whisper)
                                Text("Stays on your PC. Its messages aren't kept in this chat.", style = Type.Small, color = Palette.TextDim)
                            }
                            Icon(Glyphs.Next, null, tint = Palette.TextDim, modifier = Modifier.size(20.dp))
                        }
                    }
                    is ChatEntry.Note -> if (entry.problem) Panel(
                        itemModifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                        background = Palette.Bar, border = Palette.Red, radius = 12.dp,
                        padding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Glyphs.Warning, null, tint = Palette.Red, modifier = Modifier.size(20.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("That didn't work", style = Type.Label.copy(fontWeight = FontWeight.SemiBold), color = Palette.Text)
                                Text(entry.text, style = Type.Label, color = Palette.TextSoft)
                                LinkText("OK", { station.dismissNote(entry.id) }, bold = true)
                            }
                        }
                    } else Row(
                        itemModifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center,
                    ) {
                        Row(
                            Modifier.clip(RoundedCornerShape(12.dp)).background(Palette.Bar).padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(Glyphs.Check, null, tint = Palette.Green, modifier = Modifier.size(16.dp))
                            Text(entry.text, style = Type.Small, color = Palette.TextSoft)
                        }
                    }
                }
            }
            // Read by TalkBack as the list's heading (hidden on the board too).
            item(key = "heading") { androidx.compose.foundation.layout.Box(Modifier.size(1.dp).semantics { heading(); contentDescription = "Messages" }) }
        }
}

        NeedsYouBar(state.needsYou.size, { nav.open(Screen.NeedsYou) }, Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp))
        val voice = rememberVoiceInput(station, whisper = nav.composeTo.whisper)
        VoicePanel(voice, heard = { draft = withHeard(draft, it) }, Modifier.padding(top = 4.dp))

        // The one composer: "To:" picks where it goes. Only it rides the keyboard (the list above
        // just gets shorter).
        Composer(
            draft, { draft = it },
            hint = when {
                to.whisper -> "Whisper to your PC's free model"
                state.pcAway -> "Message Mímir (${state.waitsUntil})"
                else -> "Message Mímir"
            },
            label = "Message Mímir", voice = voice, micLabel = "Talk (voice typing): speak a message",
            onSend = { if (sendFromChat(draft, state, station, nav, engine)) draft = "" },
            modifier = Modifier.imePadding().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 10.dp),
            chip = if (state.paired) to.chipWords() else null,
            chipLabel = "To: ${to.chipWords()}. Choose where this goes",
            onChip = { nav.openSheet(io.github.odnwks.mimir.nav.Sheet.WhereTo) },
            whisper = to.whisper,
        )
    }
    WhoAnswersSheet(
        open = whoOpen && !whispering,
        chosen = if (state.models.chatsEngine == "paid") Answerer.Claude else Answerer.Pc,
        phoneCan = phoneCanRun, paired = true, claudeOffered = state.models.chatsEngine != null,
        onChoose = { who ->
            whoOpen = false
            when (who) {
                Answerer.Phone -> if (phoneCanRun) nav.open(Screen.Standalone)
                Answerer.Pc -> if (state.models.chatsEngine == "paid") station.setEngines("free", null)
                Answerer.Claude -> station.setEngines("paid", null)
                Answerer.Online -> {}
            }
        },
        onConnect = { whoOpen = false; nav.open(Screen.Pair) },
        onClose = { whoOpen = false },
    )
    }
}

/** The line under a sent message that started or joined a task: it opens the task and says its state. */
@Composable
fun TaskLink(text: String, stateWords: String, open: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val (fg, bg) = stateColours(stateWords)
    Row(
        Modifier.heightIn(min = 48.dp).clip(shape).background(Palette.Bar).border16(Palette.Line, 12)
            .clickable(role = Role.Button, onClick = open)
            .semantics(mergeDescendants = true) { contentDescription = "$text. ${stateWords.ifEmpty { "Open" }}. Opens the task" }
            .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text, style = Type.Small, color = Palette.TextSoft, modifier = Modifier.weight(1f, fill = false))
        if (stateWords.isNotEmpty()) Tag(stateWords, fg, bg)
        Icon(Glyphs.Next, null, tint = Palette.TextDim, modifier = Modifier.size(18.dp))
    }
}

/** A state's tag colours, the same on Chat, Status and a task. */
fun stateColours(state: String) = when (state) {
    "Needs you" -> Palette.Ground to Palette.AmberText
    "Working", "Starting", "Live" -> Palette.Ground to Palette.BlueLight
    "Done" -> Palette.Ground to Palette.GreenText
    else -> Palette.Text to Palette.Track
}
