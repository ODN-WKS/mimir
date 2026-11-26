package io.github.odnwks.mimir.ui

import io.github.odnwks.mimir.ui.adaptive.forDevice
import io.github.odnwks.mimir.ui.theme.ChatTextSize
import io.github.odnwks.mimir.ui.theme.LocalAppSize
import io.github.odnwks.mimir.ui.theme.pinchToZoomText
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.standalone.Catalog
import io.github.odnwks.mimir.standalone.Engine
import io.github.odnwks.mimir.standalone.LocalModel
import io.github.odnwks.mimir.standalone.LocalState
import io.github.odnwks.mimir.standalone.ModelStatus
import io.github.odnwks.mimir.ui.theme.LocalReduceMotion
import io.github.odnwks.mimir.ui.theme.Motion
import io.github.odnwks.mimir.ui.theme.Palette
import io.github.odnwks.mimir.ui.theme.Type
import io.github.odnwks.mimir.ui.theme.motionSpec

/** Standalone mode: a chat with a model running on this phone, with no PC and no internet. */
@Composable
fun StandaloneScreen(engine: Engine, nav: Navigator) {
    val s by engine.state.collectAsStateWithLifecycle()
    val model = Catalog.byId(s.model)
    var draft by rememberSaveable { mutableStateOf("") }
    var deleted by androidx.compose.runtime.remember { mutableStateOf<io.github.odnwks.mimir.standalone.Deleted?>(null) }
    var asking by androidx.compose.runtime.remember { mutableStateOf<io.github.odnwks.mimir.standalone.LocalChat?>(null) }
    var renaming by rememberSaveable(s.chat.id) { mutableStateOf(false) }
    var rulesOpen by rememberSaveable(s.chat.id) { mutableStateOf(false) }
    var sentOpen by rememberSaveable(s.chat.id) { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val reader = rememberReadAloud()
    var endAsk by androidx.compose.runtime.remember { mutableStateOf(false) }
    var needsMimir by androidx.compose.runtime.remember { mutableStateOf(false) }
    val ground by androidx.compose.animation.animateColorAsState(if (s.whisper) Palette.GroundWhisper else Palette.Ground, label = "ground")
    var endThen by androidx.compose.runtime.remember { mutableStateOf<(() -> Unit)?>(null) }
    var finding by rememberSaveable(s.chat.id) { mutableStateOf(false) }
    var find by rememberSaveable(s.chat.id) { mutableStateOf("") }
    val words = find.trim().takeIf { finding && it.isNotEmpty() }
    androidx.compose.runtime.LaunchedEffect(nav.taskAsk) {
        if (nav.taskAsk == FIND_CHAT && nav.current == Screen.Standalone) { nav.taskAsk = null; finding = true }
    }
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(ground).swipeForChats(LocalOpenMenu.current)) {
        val newChat = {
            when {
                s.writing -> {}
                s.whisper && s.chat.turns.isNotEmpty() -> { endThen = null; endAsk = true }
                s.whisper -> engine.endWhisper()
                s.chat.turns.isNotEmpty() -> engine.newChat()
            }
        }
        val newWhisper = {
            when {
                s.writing -> {}
                !s.ready -> needsMimir = true
                s.whisper && s.chat.turns.isNotEmpty() -> { endThen = { engine.startWhisper() }; endAsk = true }
                else -> engine.startWhisper()
            }
        }
        val newWebWhisper = {
            when {
                s.writing -> {}
                !s.ready -> needsMimir = true
                s.whisper && s.chat.turns.isNotEmpty() -> { endThen = { engine.startWhisper(web = true) }; endAsk = true }
                else -> engine.startWhisper(web = true)
            }
        }
        val tapEye = {
            when {
                s.writing -> {}
                s.whisper && s.chat.turns.isNotEmpty() -> endAsk = true
                s.whisper -> engine.endWhisper()
                !s.ready -> needsMimir = true
                else -> engine.startWhisper()
            }
        }
        // While it's open, the topic's sheet says whether the app sent anything.
        WatchWhatThisChatSends(s.chat.id to s.whisper)
        // The topic's sheet hands back what was chosen there.
        androidx.compose.runtime.LaunchedEffect(nav.chatAsk) {
            val r = nav.chatAsk ?: return@LaunchedEffect
            // Only the screen that is shown takes the request (a screen leaving must not consume it).
            if (nav.current != io.github.odnwks.mimir.nav.Screen.Standalone) return@LaunchedEffect
            nav.chatAsk = null
            when (r.ask) {
                io.github.odnwks.mimir.nav.ChatAsk.Rename -> renaming = true
                io.github.odnwks.mimir.nav.ChatAsk.Delete -> asking = s.chat
                io.github.odnwks.mimir.nav.ChatAsk.Whisper -> tapEye()
                io.github.odnwks.mimir.nav.ChatAsk.StartWhisper -> if (!s.whisper && !s.writing) { if (s.ready) engine.startWhisper() else needsMimir = true }
                io.github.odnwks.mimir.nav.ChatAsk.NewChat -> newChat()
                io.github.odnwks.mimir.nav.ChatAsk.StartWebWhisper -> if (!s.whisper && !s.writing) newWebWhisper()
                io.github.odnwks.mimir.nav.ChatAsk.Rules -> rulesOpen = true
                io.github.odnwks.mimir.nav.ChatAsk.Sent -> sentOpen = true
                io.github.odnwks.mimir.nav.ChatAsk.CopyChat -> clip.setText(androidx.compose.ui.text.AnnotatedString(chatAsPlainText(s.chat.turns.map { it.user to markdownPlain(it.text) }, AI_NAME)))
            }
        }
        BackHeader(
            s.chat.topic.orEmpty(), nav::back,
            // A tap on the topic: Rename, Delete, read-aloud speed, Whisper and privacy, in one sheet.
            onTitle = { nav.openSheet(chatOptions(s)) }, titleLabel = CHAT_OPTIONS,
            action = {
                // The eye is always here: the chat's mode, and Chats, New chat and New Whisper.
                EyeMenu(
                    s.whisper, when { s.whisper && s.web -> END_WEB_WHISPER; s.whisper -> END_WHISPER; else -> START_WHISPER.forDevice() },
                    { nav.open(Screen.Chats) }, newChat, newWhisper, onNewWebWhisper = newWebWhisper, web = s.web,
                    onEndWhisper = { engine.endWhisper(told = true) },
                    onEmergency = { draft = ""; emergencyExit(ctx, engine, reader) },
                )
            },
        )
        ChatLiveLine(s)
        if (finding) FindBar(find, { find = it }, s.chat.turns.count { words != null && it.text.contains(words, ignoreCase = true) }) { finding = false; find = "" }
        WhisperHygiene(s.whisper)
        WhisperEndedNote(s.whisperEnded, engine::dismissWhisperEnded)
        EndWhisperQuestion(endAsk, onCancel = { endAsk = false; endThen = null }) { endAsk = false; engine.endWhisper(); endThen?.invoke(); endThen = null }
        WhisperNeedsMimir(needsMimir, model.sizeText, onGet = { needsMimir = false; nav.open(Screen.Models) }, onClose = { needsMimir = false })
        // The model in use, one tap from changing it (nothing to pick on a phone that can't run one).
        // Only on an empty chat: once there are messages the chat shows just the conversation.
        val reduce = LocalReduceMotion.current
        androidx.compose.animation.AnimatedVisibility(
            s.supported && s.chat.turns.isEmpty() && !s.writing,
            enter = if (reduce) androidx.compose.animation.EnterTransition.None else androidx.compose.animation.fadeIn(motionSpec(Motion.SCREEN_MS)) + androidx.compose.animation.expandVertically(motionSpec(Motion.SCREEN_MS)),
            exit = if (reduce) androidx.compose.animation.ExitTransition.None else androidx.compose.animation.fadeOut(motionSpec(Motion.SCREEN_MS)) + androidx.compose.animation.shrinkVertically(motionSpec(Motion.SCREEN_MS)),
        ) { ListRow(
            model.plainName,
            when (val st = s.status[model.id]) {
                ModelStatus.Ready -> model.details
                ModelStatus.Outdated -> "A newer version is offered · tap to update"
                else -> statusWords(st, model)
            },
            { nav.open(Screen.StandaloneModels) },
            Modifier.padding(horizontal = 12.dp),
            leading = { Icon(Glyphs.Models, null, tint = Palette.BlueText, modifier = Modifier.size(22.dp)) },
        ) }
        s.note?.let { note ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(note, style = Type.Small, color = Palette.TextSoft, modifier = Modifier.weight(1f))
                LinkText("OK", engine::dismissNote)
            }
        }
        if (s.supported && model.thinking && s.model in s.have) {
            SwitchRow(s.think, engine::setThink, Modifier.padding(horizontal = 16.dp)) {
                Text("Think first", style = Type.BodyTight, color = Palette.Text, modifier = Modifier.weight(1f))
            }
        }
        when {
            !s.supported -> Notice("This phone is too old to run a model".forDevice(), "It needs a phone from about 2021 on. Chat with your PC works as usual.".forDevice())
            !s.ready -> SetUp(engine, s, model, Modifier.weight(1f))
            else -> {
                val rough = rememberRoughLocation(engine::answerRoughPlace) { engine.say(it) }
                Conversation(
                    s, Modifier.weight(1f), engine::retry, engine::open, { id -> asking = (s.earlier + s.chat).firstOrNull { it.id == id } }, { n, id -> engine.rename(n, id) },
                    placeQuestion = { PlaceQuestion(engine::answerPlace, rough, engine::skipPlace) },
                    searchQuestion = { SearchQuestion(it, engine::acceptSearch, engine::declineSearch) },
                    lookUp = lookItUpFor(s, engine),
                    readLink = { turn, i -> readLinkFor(s, engine, turn, i) },
                    tor = { TorLines(s, engine) },
                    lookupAgain = engine::lookupAgain,
                    answerWithoutLookup = engine::answerWithoutLookup,
                    askAgain = { engine.tryAgain() },
                    words = words,
                )
            }
        }
        s.problem?.let { Text(it, style = Type.Small, color = Palette.Red, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }
        UndoBar(deleted, onUndo = { deleted?.let(engine::restore); deleted = null }, onGone = { deleted = null; engine.forgetDeleted() })
        DeleteChatQuestion(asking, onCancel = { asking = null }) { c -> asking = null; deleted = engine.deleteChat(c.id) }
        ForgetDeletedChatsWhenLeaving(engine)
        if (s.supported && s.ready) {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 10.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NoKeyboardLearning(s.whisper) {
                    MessageBox(draft, { draft = it }, "Message $AI_NAME", "Message $AI_NAME", Modifier.weight(1f), keyboardOptions = whisperKeyboard(s.whisper))
                }
                if (s.writing) {
                    StopControl(engine::stop, shape = StopShape.Round, size = SMALL_BUTTON, fill = Palette.RedBorder, description = "Stop the reply")
                } else {
                    RoundButton(Glyphs.Send, "Send", { if (engine.send(draft)) draft = "" }, filled = draft.isNotBlank())
                }
            }
        }
    }
    if (renaming) TopicEditor(s.chat.topic.orEmpty(), onCancel = { renaming = false }) { engine.rename(it); renaming = false }
    if (rulesOpen) ChatRulesDialog(s.chat.rules, onSave = { engine.setChatRules(it); rulesOpen = false }, onClose = { rulesOpen = false })
    if (sentOpen) ChatSentDialog(s.chat, false, null, onClose = { sentOpen = false })
    }
}

@Composable
private fun Conversation(
    s: LocalState, modifier: Modifier, retry: () -> Unit, open: (String) -> Unit, onDelete: (String) -> Unit, onRename: (String, String) -> Unit,
    placeQuestion: @Composable () -> Unit = {}, searchQuestion: @Composable (String) -> Unit = {}, lookUp: (() -> Unit)? = null,
    /** "Read example.com?" under a message with a link (the latest one, normal mode). */
    readLink: (@Composable (io.github.odnwks.mimir.standalone.Turn, Int) -> (@Composable () -> Unit)?)? = null,
    /** A Whisper with internet's notice and Cancel. */
    tor: @Composable () -> Unit = {},
    /** "Try again" under an answer whose lookup failed. */
    lookupAgain: () -> Unit = {},
    answerWithoutLookup: () -> Unit = {},
    /** "Try again" under an answer: its question is sent again. */
    askAgain: (io.github.odnwks.mimir.standalone.Turn) -> Unit = {},
    /** Find: only the messages with these words show (null: all). */
    words: String? = null,
) {
    val list = rememberLazyListState()
    val focus = LocalFocusManager.current
    val lines = rememberNewLines(list, s.chat.id, s.chat.turns.size to s.writing, if (s.writing) s.partial.length else 0, s.chat.turns.count { it.user })
    Column(modifier) {
    ChatTextSize {
LazyColumn(
        Modifier.weight(1f).fillMaxWidth().pinchToZoomText(LocalAppSize.current).pointerInput(Unit) { detectTapGestures(onTap = { focus.clearFocus() }) },
        state = list,
        reverseLayout = true,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.Bottom),
    ) {
        // The list is upside down: the tip shows under the reply being written.
        if (s.writing) item(key = "keep-open") {
            Text("Keep this screen open while it writes. In the background the phone slows it right down.", style = Type.Small, color = Palette.TextDim)
        }
        if (s.web) item(key = "tor") { tor() }
        if (s.writing) item(key = "writing") { WritingReply(s) }
        // The last message has no answer (the app closed mid-reply, or it failed): ask again.
        if (s.askPlace && !s.writing) item(key = "place") { placeQuestion() }
        if (s.askSearch != null && !s.writing) item(key = "search") { searchQuestion(s.askSearch) }
        if (!s.writing && !s.askPlace && s.askSearch == null && s.chat.turns.lastOrNull()?.user == true) item(key = "interrupted") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(s.problem?.takeIf { it != Engine.PC_COULD_NOT_ANSWER } ?: noAnswerWords(s.stoppedChat == s.chat.id, false), style = Type.Small, color = Palette.TextDim)
                SmallButton("Try again", retry, description = "Ask the last message again")
            }
        }
        // The last answer's lookup failed (no signal, the PC away, the site down): ask again.
        if (!s.writing && s.lookupFailed && s.chat.turns.lastOrNull()?.user == false) item(key = "lookup-again") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SmallButton("Try again", lookupAgain, description = "Look it up again")
                SmallButton("Answer without looking it up", answerWithoutLookup, description = "Answer this without the internet")
            }
        }
        if (!s.writing) s.speed?.let { speed ->
            item(key = "speed") { Text(speed, style = Type.Small, color = Palette.TextDim) }
        }
        items(s.chat.turns.withIndex().reversed().filter { words == null || it.value.text.contains(words, ignoreCase = true) }, key = { "${s.chat.id}-${it.index}" }) { (i, turn) ->
            val m = Modifier.animateItem(fadeInSpec = motionSpec(Motion.NEW_ITEM_MS), placementSpec = motionSpec(Motion.NEW_ITEM_MS), fadeOutSpec = motionSpec(Motion.NEW_ITEM_MS))
            if (turn.user) OwnMessage(
                turn.text, m, id = turn.id.ifEmpty { "mine-${turn.text.hashCode()}" }, under = readLink?.invoke(turn, i),
                meta = { ChatMeta(turn.at, answerTo(s.chat.turns, i), "Your PC") },
            ) else Box(m) {
                val before = s.chat.turns.getOrNull(i - 1)?.takeIf { it.user }
                Reply(
                    turn.text, dim = false, sources = turn.sources, note = turn.switchNote, lookUp = lookUp.takeIf { i == s.chat.turns.lastIndex }, openSources = !s.whisper,
                    author = if (s.whisper) null else answerAuthor(turn, "Your PC"),
                    onTryAgain = if (s.whisper || i != s.chat.turns.lastIndex || s.lookupFailed) null else before?.let { q -> { askAgain(q) } },
                    meta = { ChatMeta(turn.at, turn, "Your PC") },
                )
            }
        }
        // Earlier chats sit on the chat screen itself, under a new chat (the list is upside down).
        if (s.whisper && s.chat.turns.isEmpty() && !s.writing) item(key = "whisper") { WhisperHello(s.web) }
        if (!s.whisper && s.chat.turns.isEmpty() && !s.writing && s.earlier.isNotEmpty()) {
            items(s.earlier, key = { "earlier-${it.id}" }) { c -> ChatRow(c, { open(c.id) }, { onDelete(c.id) }, { onRename(it, c.id) }) }
            item(key = "earlier") { SectionTitle("Earlier chats", Modifier.padding(top = 8.dp)) }
        }
        if (s.chat.turns.isEmpty() && !s.writing) item(key = "empty") {
            Text(
                "Ask anything. It runs on this phone only: nothing is sent anywhere, and it works in airplane mode.",
                style = Type.Label, color = Palette.TextDim,
            )
        }
    }
}
    NewLinesButton(lines)
    }
}

/** The answer a chat's message got (the one right after it), whose model and effort it went to. */
internal fun answerTo(turns: List<io.github.odnwks.mimir.standalone.Turn>, i: Int) = turns.getOrNull(i + 1)?.takeIf { !it.user && it.task == null }

/**
 * The reply being written: three dots until its words begin (as in a task), then the words. What it is
 * doing is said by the live line at the top ([ChatLiveLine]).
 */
@Composable
internal fun WritingReply(s: LocalState, openSources: Boolean = true) {
    when {
        s.partial.isNotEmpty() -> Reply(s.partial, dim = s.loading || s.thinking, openSources = openSources, buttons = false)
        // The PC dropped before its first word: the one true line, where the answer will be.
        s.onPc && s.pcWaiting -> Reply(PC_AWAY_WORDS, dim = true, openSources = openSources, buttons = false)
        else -> ThinkingBubble()
    }
}

/** When the answer being written was first seen, so its time counts on through a turned phone or another screen. */
private object WritingSince {
    private var of = ""
    private var secs = 0L
    fun of(message: String): Long {
        if (of != message) { of = message; secs = System.currentTimeMillis() / 1000 }
        return secs
    }
}

/** The task chat's live line, for a chat: what it is doing now and how long it has been at it. */
@Composable
internal fun ChatLiveLine(s: LocalState) {
    // A PC that dropped says so where the answer will be: said once.
    if (!s.writing || (s.onPc && s.pcWaiting)) return
    val asked = s.chat.turns.lastOrNull { it.user }
    val doing = if (s.partial.isNotEmpty() && s.looking == null && !s.loading && !s.thinking) "Writing…" else writingWords(s)
    val since = WritingSince.of("${s.chat.id}/${asked?.id}/${asked?.at}")
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("chat-live")) {
        LiveSignLine(io.github.odnwks.mimir.data.LiveSign(doing = doing, turnUnix = since))
    }
}

/** The model's words, as they are written: plain text, and under a finished answer Listen, Copy and Try again. */
@Composable
internal fun Reply(
    text: String, dim: Boolean, sources: List<io.github.odnwks.mimir.online.Source> = emptyList(),
    /** "Look it up" in the answer's menu (normal mode, the latest answer). */
    lookUp: (() -> Unit)? = null,
    /** Sources can be opened (not in a Whisper with internet: the browser wouldn't go through Tor). */
    openSources: Boolean = true,
    /** "Try again": the question before this answer is asked again (null: not offered). */
    onTryAgain: (() -> Unit)? = null,
    /** "Answered by … because …" when another model than the usual one wrote it. */
    note: String? = null,
    /** False while it is still being written (R20). */
    buttons: Boolean = true,
    /** Who wrote it: "This phone · Qwen 3.5", "Your PC · …", "Claude · …" (null: not known). */
    author: String? = null,
    /** The small print beside the Listen pill: the model, effort and time. */
    meta: (@Composable () -> Unit)? = null,
) {
    // Mímir's replies are read by the phone's own voice: nothing leaves the phone.
    val id = "mimir-${text.hashCode()}"
    // Listen and Copy get the words without the Markdown marks.
    val plain = androidx.compose.runtime.remember(text) { markdownPlain(text) }
    Answer(plain, id, dim = dim, sources = sources, openSources = openSources, lookUp = lookUp, onTryAgain = onTryAgain, note = note, buttons = buttons, author = author, meta = meta) {
        MarkdownText(text, style = ChatLook.MessageText, color = if (dim) Palette.TextDim else Palette.Text, linksOpen = openSources)
    }
}

/** "Look it up" for a chat's latest answer: normal mode, with lookups and searching on. */
@Composable
internal fun lookItUpFor(s: LocalState, engine: Engine): (() -> Unit)? {
    val o by io.github.odnwks.mimir.online.Online.settings.collectAsStateWithLifecycle()
    return if (!s.whisper && o.lookups && o.search && !s.writing) engine::lookItUp else null
}

/** Opens a page in the app's own browser (set by the app; checks leave it empty). */
val LocalOpenPage = androidx.compose.runtime.staticCompositionLocalOf<((String) -> Unit)?> { null }

const val OPEN_RESULTS = "Open the results"

/**
 * Under an answer that used a lookup: one small grey line, "From open-meteo.com", each name a link.
 * A blocked search instead offers its results page, one tap, in the app's own browser.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun SourcesLine(sources: List<io.github.odnwks.mimir.online.Source>, open: Boolean = true) {
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    val inApp = LocalOpenPage.current
    val pages = sources.filter { !it.results }.distinctBy { it.site }
    sources.firstOrNull { it.results }?.let { r ->
        if (open && inApp != null) SmallButton(OPEN_RESULTS, { inApp(r.url) }, description = "Open the search results for ${r.words} in the app's browser")
    }
    if (pages.isEmpty()) return
    // Found through the PC: one more small line says so, with the engines it used.
    val via = pages.firstOrNull { it.via.isNotEmpty() }?.via
    // "Looked up: open-meteo.com": a small dim tag; each name opens its page.
    androidx.compose.foundation.layout.FlowRow(
        Modifier.clip(RoundedCornerShape(10.dp)).background(ChatLook.LookedUpFill).padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.Center, horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(LOOKED_UP_TAG, style = TextStyle(fontSize = 12.5.sp, lineHeight = 16.sp), color = Palette.TextDim, modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp))
        pages.forEach { src ->
            Text(
                src.site, style = TextStyle(fontSize = 12.5.sp, lineHeight = 16.sp, textDecoration = if (open) androidx.compose.ui.text.style.TextDecoration.Underline else null), color = Palette.TextDim,
                modifier = Modifier.clip(RoundedCornerShape(6.dp))
                    .then(if (open) Modifier.clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = "Open ${src.site}") { runCatching { inApp?.invoke(src.url) ?: uri.openUri(src.url) } } else Modifier)
                    .padding(horizontal = 4.dp, vertical = 6.dp)
                    .semantics { contentDescription = "Source: ${src.site}" + if (src.words.isNotEmpty()) ", looked up: ${src.words}" else "" },
            )
        }
    }
    if (via != null) Text("via $via", style = Type.Small, color = Palette.TextDim, modifier = Modifier.padding(start = 8.dp).semantics { contentDescription = "Searched through your PC" + via.substringAfter("your PC", "").let { e -> if (e.isNotEmpty()) " with" + e.removePrefix(":") else "" } })
}

/**
 * Under the latest message, when it holds a link: "Read example.com?" (a pasted link may be a
 * personal tracking link, so it's never opened unasked). A plain http link is offered in the app's
 * browser instead. Normal mode with lookups on only; null when there's nothing to offer.
 */
@Composable
internal fun readLinkFor(s: LocalState, engine: Engine, turn: io.github.odnwks.mimir.standalone.Turn, index: Int): (@Composable () -> Unit)? {
    val o by io.github.odnwks.mimir.online.Online.settings.collectAsStateWithLifecycle()
    if (!turn.user || s.whisper || !o.lookups || s.writing) return null
    // Only the newest message of the owner's (the one an answer is for).
    if (s.chat.turns.withIndex().lastOrNull { it.value.user }?.index != index) return null
    val link = io.github.odnwks.mimir.online.PageText.linkIn(turn.text) ?: return null
    // Already read for this answer: the answer's source line says so.
    if (s.chat.turns.getOrNull(index + 1)?.sources?.any { it.url == link } == true) return null
    val site = io.github.odnwks.mimir.online.Net.hostOf(link)
    val open = LocalOpenPage.current
    return {
        if (link.startsWith("https://")) SmallButton(readWords(site), { engine.readPage(link) }, description = "Have $AI_NAME read $site")
        else if (open != null) SmallButton("Open in browser", { open(link) }, description = "Open $site in the app's browser (it isn't secure, so $AI_NAME doesn't read it)")
    }
}

/** "Read example.com?" */
fun readWords(site: String) = "Read $site?"

const val READ_THIS_PAGE = "Have $AI_NAME read this page"

/** The first search: DuckDuckGo's rules, said plainly, accepted once. */
@Composable
internal fun SearchQuestion(words: String, onYes: () -> Unit, onNo: () -> Unit) {
    Panel(spacing = 10.dp) {
        Text(SEARCH_QUESTION, style = Type.Section, color = Palette.Text, modifier = Modifier.semantics { heading() })
        Text(io.github.odnwks.mimir.online.Search.WARNING, style = Type.Label, color = Palette.TextSoft)
        Text("It would look up: \"$words\". Only those few words are sent. You can turn searching off in Settings › Who answers › Looking things up.", style = Type.Small, color = Palette.TextDim)
        BigButton(SEARCH_YES, Look.Primary, onYes, Modifier.fillMaxWidth(), height = 48.dp)
        LinkText("Not now", onNo)
    }
}

const val SEARCH_QUESTION = "Look it up online?"
const val SEARCH_YES = "Use it anyway"

/** "Weather for where?": asked once, where the answer will be; the place is kept in Settings. */
@Composable
internal fun PlaceQuestion(onPlace: (String) -> Unit, onRough: () -> Unit, onSkip: () -> Unit) {
    var place by rememberSaveable { mutableStateOf("") }
    Panel(spacing = 10.dp) {
        Text(PLACE_QUESTION, style = Type.Section, color = Palette.Text, modifier = Modifier.semantics { heading() })
        Text("Type a town or city. It's kept for next time (Settings › Who answers › Looking things up). Only its name is sent, to a weather service.", style = Type.Small, color = Palette.TextSoft)
        MessageBox(place, { place = it }, "Town or city for the weather", "Town or city", Modifier.fillMaxWidth())
        BigButton("Use this place", Look.Primary, { onPlace(place) }, Modifier.fillMaxWidth(), height = 48.dp, enabled = place.isNotBlank())
        BigButton(ROUGH_ONCE, Look.Outline, onRough, Modifier.fillMaxWidth(), height = 48.dp)
        LinkText("Not now", onSkip)
    }
}

const val PLACE_QUESTION = "Weather for where?"
const val ROUGH_ONCE = "Use my rough location once"

/** No model yet: what it is, and one button to get it. */
@Composable
private fun SetUp(engine: Engine, s: LocalState, model: LocalModel, modifier: Modifier) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel {
            Text("Get $AI_NAME (${model.sizeText})", style = Type.Section, color = Palette.Text)
            Text(
                "No PC, no internet, nothing leaves the phone. It's good for chat and quick answers; your projects stay on the PC.",
                style = Type.Label, color = Palette.TextSoft,
            )
            Text("${model.plainName}: ${model.details} to download, once. Wi-Fi is best.", style = Type.Small, color = Palette.TextDim)
            // The one safety note, said once, as grey text with a mark.
            if (model.refusalsRemoved) SafetyNote()
        }
        DownloadControls(engine, s, model)
    }
}

/** "No safety filter…", said once where a model is chosen: grey text with a mark. */
@Composable
private fun SafetyNote() {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Glyphs.Warning, null, tint = Palette.TextDim, modifier = Modifier.padding(top = 1.dp).size(16.dp))
        Text(Catalog.REFUSALS_NOTE, style = Type.Small, color = Palette.TextDim)
    }
}

@Composable
internal fun DownloadControls(engine: Engine, s: LocalState, model: LocalModel) {
    // A phone that can't run a model is never offered a download.
    if (!s.supported) return
    when (val st = s.status[model.id]) {
        is ModelStatus.Downloading, is ModelStatus.Checking -> {
            Text(statusWords(st, model), style = Type.Small, color = Palette.TextSoft)
            val (done, total) = when (st) {
                is ModelStatus.Downloading -> st.done to st.total
                is ModelStatus.Checking -> st.done to st.total
                else -> 0L to 1L
            }
            LinearProgressIndicator(
                progress = { (done.toFloat() / total).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = Palette.BlueLight, trackColor = Palette.Track,
            )
            BigButton("Cancel", Look.Outline, engine::cancelDownload, height = 48.dp)
        }
        is ModelStatus.Paused -> {
            Text(statusWords(st, model), style = Type.Small, color = Palette.TextSoft)
            BigButton("Resume download", Look.Primary, { engine.choose(model.id); engine.download(model.id) }, height = 48.dp)
            BigButton("Remove the part download (%.1f GB)".format(st.done / 1e9), Look.Outline, { engine.removePartial(model.id) }, height = 48.dp)
        }
        ModelStatus.Ready -> if (model.id != s.model) BigButton("Use it", Look.Primary, { engine.choose(model.id) }, height = 48.dp)
        ModelStatus.Outdated -> {
            BigButton("Update model (${model.sizeText})", Look.Primary, { engine.download(model.id) }, height = 48.dp)
            if (model.id != s.model) BigButton("Use it", Look.Outline, { engine.choose(model.id) }, height = 48.dp)
        }
        else -> {
            if (st is ModelStatus.Failed) Text("Download stopped. ${st.why}", style = Type.Small, color = Palette.Red)
            BigButton(
                if (st is ModelStatus.Failed) "Try again" else "Download (${model.sizeText})",
                Look.Primary, { engine.choose(model.id); engine.download(model.id) }, height = 48.dp,
            )
        }
    }
}

internal fun statusWords(st: ModelStatus?, m: LocalModel): String = when (st) {
    is ModelStatus.Downloading -> "Downloading: %.1f of %s".format(st.done / 1e9, m.sizeText)
    is ModelStatus.Checking -> "Checking the download: %d%%".format((st.done * 100 / st.total.coerceAtLeast(1)))
    is ModelStatus.Paused -> "Paused: %.1f of %s downloaded".format(st.done / 1e9, m.sizeText)
    ModelStatus.Ready -> "Downloaded · ${m.sizeText}"
    ModelStatus.Outdated -> "Downloaded · a newer version is offered"
    is ModelStatus.Failed -> "Download stopped"
    else -> "Not downloaded · ${m.sizeText}"
}

@Composable
private fun Notice(title: String, body: String) {
    Panel(Modifier.padding(16.dp)) {
        Text(title, style = Type.BodyTight.copy(fontWeight = FontWeight.SemiBold), color = Palette.Text)
        Text(body, style = Type.Label, color = Palette.TextSoft)
    }
}

/** The models standalone mode can use, and earlier chats. */
@Composable
fun StandaloneModelsScreen(engine: Engine, nav: Navigator) {
    val s by engine.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().background(Palette.Ground)) {
        BackHeader("Models on this phone", nav::back, subtitle = "Only the one in use is kept; unused for 14 days, it's removed")
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!s.supported) Text("This phone is too old to run these. Chat with your PC works as usual.".forDevice(), style = Type.Label, color = Palette.TextSoft)
            if (Catalog.models.any { it.refusalsRemoved }) SafetyNote()
            Catalog.models.forEach { m ->
                val chosen = m.id == s.model
                Panel(border = if (chosen) Palette.BlueBorder else Palette.Line, background = if (chosen) Palette.BlueCard else Palette.Card) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(m.plainName, style = Type.BodyTight.copy(fontWeight = FontWeight.SemiBold), color = Palette.Text)
                            Text(m.note, style = Type.Small, color = Palette.TextSoft)
                            Text(m.details + " · " + statusWords(s.status[m.id], m).substringBefore(" · "), style = Type.Small, color = Palette.TextDim)
                            if (m.refusalsRemoved) Row(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                m.chip?.let { Tag(it, Palette.AmberText, Palette.Track) }
                                NoFilterTag()
                            }
                        }
                        if (chosen) Tag("In use", Palette.BlueText, Palette.BlueIcon)
                    }
                    DownloadControls(engine, s, m)
                    if (m.thinking) Text("Can think before answering (Think first).", style = Type.Small, color = Palette.TextDim)
                    if (m.id in s.have && !(chosen && s.writing)) {
                        var sure by rememberSaveable(m.id) { mutableStateOf(false) }
                        if (!sure) LinkText("Delete to free ${m.sizeText}…", { sure = true }, color = Palette.Red)
                        else Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Delete ${m.plainName}? It downloads again if you pick it.", style = Type.Small, color = Palette.TextSoft, modifier = Modifier.weight(1f))
                            SmallButton("Delete", { engine.delete(m.id); sure = false }, description = "Delete ${m.plainName}")
                            SmallButton("Keep", { sure = false })
                        }
                    }
                }
            }
            Box(Modifier.height(16.dp))
        }
    }
}
