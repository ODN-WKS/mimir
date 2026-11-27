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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.odnwks.mimir.nav.ChatAsk
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.nav.Tab
import io.github.odnwks.mimir.data.MessageReceipt
import io.github.odnwks.mimir.data.asksStatus
import io.github.odnwks.mimir.standalone.answererName
import io.github.odnwks.mimir.standalone.Catalog
import io.github.odnwks.mimir.standalone.Effort
import io.github.odnwks.mimir.standalone.Engine
import io.github.odnwks.mimir.standalone.ModelStatus
import io.github.odnwks.mimir.ui.theme.Motion
import io.github.odnwks.mimir.ui.theme.Palette
import io.github.odnwks.mimir.ui.theme.Type
import io.github.odnwks.mimir.ui.theme.motionSpec
import kotlinx.coroutines.launch
import androidx.compose.ui.semantics.liveRegion

/**
 * The one chat (Stage 1, section 2), paired or not. Before pairing it runs on this phone: the
 * first message brings one "Get Mímir" card (the one-time download), and the message waits and is
 * answered when it's ready. Paired, each message goes to the PC while it can be reached ([paired],
 * [pcOnline]) and this phone answers otherwise; a small mark under each answer says where it ran.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PhoneChat(
    engine: Engine?, nav: Navigator, paired: Boolean = false, pcOnline: Boolean = false, pcName: String = "Your PC", menuWaiting: Int = 0,
    /** For the home screen: what needs the owner, updates not yet heard, the PC's chosen model, and whether Claude answers there. */
    needsYou: Int = 0, unheard: Int = 0, pcModel: String? = null, claudeAnswers: Boolean = false,
    /** The PC offers Claude for chats (it has the "engines" setting), and how to pick who answers there: "free" or "paid". */
    claudeOffered: Boolean = false, owner: Boolean = true, onPickEngine: (String) -> Unit = {},
    /** False while the PC's choice of who answers has never been read on this phone: nothing is shown as chosen. */
    choiceKnown: Boolean = true,
    /** Which Claude model chats use on the PC, what it offers (empty: an older PC), and how to pick one. */
    claudeModel: String? = null, claudeChoices: List<io.github.odnwks.mimir.link.LChoice> = emptyList(), onPickClaudeModel: (String) -> Unit = {},
    /** The PC's tasks (a project card's state), and moving one to another project. */
    task: (String) -> io.github.odnwks.mimir.data.Task? = { null },
    onMoveTask: (String, String) -> Unit = { _, _ -> },
    /** Whether the PC has a free model for a Whisper (null: ask the link), and how to start one there with its first words (null: the link does). */
    pcWhisperFree: Boolean? = null,
    startPcWhisper: ((String) -> Boolean)? = null,
    /** Whether the PC has a project for a Whisper to run in (null: ask the link). */
    pcWhisperProjects: Boolean? = null,
    /** Whose facts the Status card shows (null: the link's). */
    station: io.github.odnwks.mimir.data.Station? = null,
) {
    if (engine == null) return CantRunHere(nav)
    val s by engine.state.collectAsStateWithLifecycle()
    // A phone that can't run a model still chats through its PC.
    // An online model (a key the owner pasted) lets a phone that can't run one chat on its own.
    val onlineOn = io.github.odnwks.mimir.online.OnlineModels.state.collectAsStateWithLifecycle().value.answering != null
    if (!s.supported && !paired && !onlineOn) return CantRunHere(nav)
    val model = Catalog.byId(s.model)
    // A Whisper's words are never put in the screen state Android keeps; an ordinary chat's box is.
    val inWhisper = remember { BooleanArray(1) }
    var draft by rememberSaveable(stateSaver = remember { androidx.compose.runtime.saveable.Saver<String, String>({ if (inWhisper[0]) "" else it }, { it }) }) { mutableStateOf("") }
    // New chat, Ask Claude and opening the app on a brand-new empty chat put the cursor in the box and bring the
    // keyboard up (Settings can turn it off). An existing chat opens without it, so it can be read.
    // It starts at 0 so returning to the tab or turning the phone never rings it.
    var focusTick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        if (nav.takeFirstChat() && engine.state.value.chat.let { it.turns.isEmpty() && it.queued.isEmpty() }) {
            nav.keyboard.emptyChatAtStart()
            focusTick++
        }
    }
    LaunchedEffect(nav.askTick) { if (nav.takeAsk()) focusTick++ }
    // The PC answers pings: a message is never "waiting" then, so Send stays on and no stale red line shows (QA2 #7, #15, #19).
    val pcReady = paired && pcOnline && (s.usePc || !s.supported) && !s.whisper
    val waiting = !s.ready && !pcReady && !s.writing && s.chat.turns.lastOrNull()?.user == true && s.stoppedChat != s.chat.id
    val st = s.status[model.id]
    val getting = st is ModelStatus.Downloading || st is ModelStatus.Checking || st is ModelStatus.Paused
    val ctx = LocalContext.current
    val readAloud = rememberReadAloud()
    val prefs = remember { ctx.getSharedPreferences("phone_chat", 0) }
    // The first hello shows until a message was ever sent on this phone; its "Connect it" hides for good with the ✕.
    var hasChatted by remember { mutableStateOf(prefs.getBoolean(HAS_CHATTED, false)) }
    var connectHidden by remember { mutableStateOf(prefs.getBoolean(CONNECT_HIDDEN, false)) }
    var pillDismissedFor by rememberSaveable { mutableStateOf(-1) }
    LaunchedEffect(needsYou) { if (needsYou == 0) pillDismissedFor = -1 }
    val markChatted = { if (!hasChatted) { hasChatted = true; prefs.edit().putBoolean(HAS_CHATTED, true).apply() } }
    LaunchedEffect(s.chat.turns.isNotEmpty(), s.earlier.isNotEmpty()) { if (s.chat.turns.isNotEmpty() || s.earlier.isNotEmpty()) markChatted() }
    // When the PC answers, pictures go to the PC: its model's sight counts, never this phone's (null: not the PC, or an older one).
    // The PC's own word is read from the link's state too, so a change on the PC (after a reconnect, or an answer from another model) redraws the Attach menu.
    val link = remember { io.github.odnwks.mimir.data.Stations.current() }
    val pcSees = link?.state?.collectAsStateWithLifecycle()?.value?.models?.seesPictures
    val pcSight = if (pcReady) engine.pcPictures().also { pcSees } else null
    // When the PC answers, any file goes to the PC to be read (PDFs, documents, sheets, recordings), never limited by this phone's model.
    val pcFiles = pcReady && engine.pcFiles()
    // Text files and pictures to go with the next message (pictures for a model that sees): one piece, shared with a task's reply box.
    val attach = rememberAttach(pcSight == true, pcFiles, key = "phone-chat")
    val files = attach.files
    val pictures = attach.pictures
    var attachNote by attach::note
    var attachFix by attach::fix
    // A Whisper on the PC waits for its first words here; they start it there and the Whisper's own screen opens.
    var pcWhisper by rememberSaveable { mutableStateOf(false) }
    // When it opened, as counted by the app leaving: leaving with "End Whispers when I leave the app" on ends it (P42 F3).
    var pcWhisperSince by rememberSaveable { mutableStateOf(0) }
    val whispersLeft by engine.whispersLeft.collectAsStateWithLifecycle()
    LaunchedEffect(whispersLeft, pcWhisper) { if (pcWhisper && whispersLeft != pcWhisperSince) pcWhisper = false }
    inWhisper[0] = s.whisper || pcWhisper
    // A picked skill belongs to the box it was picked at: another chat or a Whisper starts with none.
    LaunchedEffect(s.chat.id, pcWhisper) {
        val box = "${s.chat.id}:$pcWhisper"
        if (nav.skillBox != box) { nav.skillBox = box; nav.skill = null }
    }
    // Each chat keeps its own box, held by the engine (outside this screen): Settings or History in between loses nothing.
    val drafts = engine.drafts
    var boxChat by remember { mutableStateOf<String?>(null) }
    // Another chat (or coming back): its words and pictures come into the box. The old chat's were already kept, as they changed.
    LaunchedEffect(s.chat.id) {
        val next = drafts.get(s.chat.id)
        draft = next.text; files.clear(); files.addAll(next.files); pictures.clear(); pictures.addAll(next.pictures)
        attachNote = null; attachFix = null
        boxChat = s.chat.id
    }
    // Keep what is in the box for this chat as it changes (not before the chat's own draft is in the box).
    LaunchedEffect(draft, files.size, pictures.size, boxChat, s.chat.id) {
        if (boxChat == s.chat.id && !pcWhisper && !s.whisper) drafts.put(s.chat.id, io.github.odnwks.mimir.standalone.Draft(draft, files.toList(), pictures.toList()))
    }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { engine.flushDrafts() } }
    val sight = sightFor(pcSight, pcReady, model.id in s.sees, model.vision?.let { model.chipName })
    // The Whisper's own box, in memory only: never the open chat's draft, never written anywhere (QA16 Q20).
    var whisperDraft by remember { mutableStateOf("") }
    LaunchedEffect(pcWhisper) { whisperDraft = "" }
    val linkState = link?.state?.collectAsStateWithLifecycle()?.value
    val pcFree = pcWhisperFree ?: (linkState?.models?.local?.isNotEmpty() == true)
    // A Whisper on the PC runs in one of its projects; with none, or the PC away, it can't start (QA16 Q21).
    val pcProjects = pcWhisperProjects ?: (startPcWhisper != null || linkState?.projects?.isNotEmpty() != false)
    val pcWhisperBlock = when {
        !pcWhisper -> null
        !pcOnline -> WHISPER_PC_AWAY
        !pcFree -> WHISPER_PC_NO_MODEL
        !pcProjects -> WHISPER_NEEDS_PROJECT
        else -> null
    }
    val pcStart: ((String, Boolean) -> Boolean)? = startPcWhisper?.let { f -> { text: String, _: Boolean -> f(text) } }
        ?: remember(link) { link?.let { l -> { text: String, typed: Boolean -> l.startTask(text, "", incognito = true, typed = typed).isNotEmpty() } } }
    val pcWhisperGo = { text: String, typed: Boolean ->
        if (text.isNotBlank()) {
            // The PC's Whisper takes words only, so a skill goes with them (nothing is kept on either side).
            val words = io.github.odnwks.mimir.standalone.Skill.withWords(text, nav.skill)
            if (pcStart?.invoke(words, typed) == true) { nav.skill = null; whisperDraft = ""; pcWhisper = false; nav.open(Screen.IncognitoTask) }
            else attachNote = WHISPER_COULD_NOT_START
        }
    }
    // [typed]: from the message box, not a starter.
    val focusManager = LocalFocusManager.current
    val status = io.github.odnwks.mimir.data.StatusCards.shown?.takeIf { it.chat == "chat:${s.chat.id}" && !pcWhisper }
    // What the top bar names, as drawn: it is kept with a message sent under it.
    val named = remember { arrayOfNulls<String>(1) }
    val send = { text: String, typed: Boolean ->
        if (pcWhisper) pcWhisperGo(text, typed)
        // "status" is answered by the card, here on the phone: no model runs and the chat never keeps it.
        else if (asksStatus(text) && files.isEmpty() && pictures.isEmpty() && askStatus("chat:${s.chat.id}", s.chat.turns.lastOrNull()?.id.orEmpty(), station ?: link)) {
            draft = ""; focusManager.clearFocus()
        }
        else if (!waiting && (text.isNotBlank() || files.isNotEmpty() || pictures.isNotEmpty())) {
            // Pictures the answerer can't see stay in the box with a note; they are never dropped quietly.
            val held = picturesHeldNote(sight, pictures.size)
            if (held != null) attachNote = held
            // The box clears only when the message was taken; refused, it stays to try again.
            else if (engine.ask(text, files.toList(), pictures.toList(), typed, saw = named[0], skill = nav.skill)) { nav.skill = null; draft = ""; files.clear(); pictures.clear(); markChatted() }
        }
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // A Whisper turns the whole chat teal (the colour fades, nothing jumps).
    val ground by androidx.compose.animation.animateColorAsState(if (s.whisper || pcWhisper) WhisperTint.Ground else Palette.Ground, motionSpec(Motion.SCREEN_MS), label = "ground")
    var needsMimir by remember { mutableStateOf(false) }
    var endAsk by remember { mutableStateOf(false) }
    // What starts once the Whisper with messages is ended and forgotten (a new chat, or a new Whisper).
    var endThen by remember { mutableStateOf<(() -> Unit)?>(null) }
    // Paired, New Chat runs on the PC and "Phone-only chat" on this phone's model (owner, 2026-11-19:
    // a clear way to the phone's own model even with the PC there); each sets the place chip to match.
    val startOn = { pc: Boolean ->
        val place = { if (paired && s.supported && s.usePc != pc) engine.setUsePc(pc) }
        pcWhisper = false
        // New chat takes the Status card away, also from a still empty chat (which stays the same chat).
        if (!s.writing && !s.whisper && s.chat.turns.isEmpty()) io.github.odnwks.mimir.data.StatusCards.dismiss()
        // True only when a new chat really started (not while writing, nor while the question about ending a Whisper opens).
        when {
            s.writing -> false
            s.whisper && s.chat.turns.isNotEmpty() -> { endThen = place; endAsk = true; false }
            s.whisper -> { engine.endWhisper(); place(); true }
            else -> { engine.newChat(); place(); true }
        }
    }
    // New chat is asked for by the person, however it was reached: its keyboard comes (0.09 item 23).
    val newChat = { if (startOn(true)) { nav.keyboard.release(); focusTick++ }; Unit }
    // Where a Whisper starts: on the PC when paired with a free model and a project there, else on this phone (the PC when the phone can't).
    val whisperHere = {
        when (whisperStartFor(paired, pcFree, pcOnline, s.ready, s.supported, pcProjects)) {
            WhisperStart.Pc -> { pcWhisperSince = engine.whispersLeft.value; pcWhisper = true }
            WhisperStart.Phone -> engine.startWhisper()
            WhisperStart.NeedsModel -> needsMimir = true
            WhisperStart.Nowhere -> attachNote = WHISPER_NOWHERE
        }
        Unit
    }
    val newWhisper = {
        when {
            s.writing -> {}
            s.whisper && s.chat.turns.isNotEmpty() -> { endThen = { engine.startWhisper() }; endAsk = true }
            else -> whisperHere()
        }
        Unit
    }
    val newWebWhisper = {
        when {
            s.writing -> {}
            !s.supported -> attachNote = WHISPER_WEB_PHONE_ONLY
            !s.ready -> needsMimir = true
            s.whisper && s.chat.turns.isNotEmpty() -> { endThen = { engine.startWhisper(web = true) }; endAsk = true }
            else -> engine.startWhisper(web = true)
        }
        Unit
    }
    // "Use my rough location once" for the weather: Android's approximate location, asked only on that tap.
    val roughPlace = rememberRoughLocation(engine::answerRoughPlace) { attachNote = it }
    val lookUp = lookItUpFor(s, engine)
    var whoOpen by remember { mutableStateOf(false) }
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    val tapEye = {
        when {
            s.whisper && s.chat.turns.isNotEmpty() -> endAsk = true
            s.whisper -> engine.endWhisper()
            pcWhisper -> pcWhisper = false
            else -> whisperHere()
        }
        Unit
    }
    val topic = s.chat.topic
    // Paired with the PC answering: the privacy words say the PC, never "stays on this phone".
    val pcPrivate = paired && (s.usePc || !s.supported) && !s.whisper && !onlineOn
    var renaming by rememberSaveable(s.chat.id) { mutableStateOf(false) }
    var rulesOpen by rememberSaveable(s.chat.id) { mutableStateOf(false) }
    var sentOpen by rememberSaveable(s.chat.id) { mutableStateOf(false) }
    // Find in this chat: only the messages with the words show while it's open.
    var finding by rememberSaveable(s.chat.id) { mutableStateOf(false) }
    var find by rememberSaveable(s.chat.id) { mutableStateOf("") }
    val sought = find.trim().takeIf { finding && it.isNotEmpty() }
    val found = { text: String -> sought == null || text.contains(sought, ignoreCase = true) }
    LaunchedEffect(nav.taskAsk) {
        if (nav.taskAsk == FIND_CHAT && nav.current == Screen.Chat) { nav.taskAsk = null; finding = true }
    }
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(ground).swipeForChats(LocalOpenMenu.current)) {
        // Paired with the PC answering: the privacy words say the PC, never "stays on this phone".
        var deleted by remember { mutableStateOf<io.github.odnwks.mimir.standalone.Deleted?>(null) }
        var edited by remember { mutableStateOf<io.github.odnwks.mimir.standalone.Edited?>(null) }
        var asking by remember { mutableStateOf<io.github.odnwks.mimir.standalone.LocalChat?>(null) }
        // The project card whose "change" opened the picker: its choice comes back here.
        var changing by rememberSaveable { mutableStateOf<String?>(null) }
        LaunchedEffect(nav.pickedProject) {
            val p = nav.pickedProject ?: return@LaunchedEffect
            val t = changing ?: return@LaunchedEffect
            nav.pickedProject = null; changing = null
            if (p == JUST_CHAT) engine.justChat() else onMoveTask(t, p)
        }
        // While it's open, the topic's sheet says whether the app sent anything.
        WatchWhatThisChatSends(s.chat.id to s.whisper)
        // The topic's sheet hands back what was chosen there.
        LaunchedEffect(nav.chatAsk) {
            val r = nav.chatAsk ?: return@LaunchedEffect
            if (nav.current != Screen.Chat) return@LaunchedEffect
            nav.chatAsk = null
            when (r.ask) {
                ChatAsk.Rename -> renaming = true
                ChatAsk.Delete -> asking = s.chat
                ChatAsk.Whisper -> if (!s.writing) tapEye()
                // From Settings' search: starts one (never ends one already going).
                ChatAsk.StartWhisper -> if (!s.whisper && !s.writing && !pcWhisper) whisperHere()
                ChatAsk.Rules -> rulesOpen = true
                ChatAsk.Sent -> sentOpen = true
                // The side menu's New chat.
                ChatAsk.NewChat -> newChat()
                ChatAsk.StartWebWhisper -> if (!s.whisper && !s.writing) newWebWhisper()
                ChatAsk.CopyChat -> clip.setText(androidx.compose.ui.text.AnnotatedString(chatAsPlainText(s.chat.turns.map { it.user to markdownPlain(it.text) }, AI_NAME)))
            }
        }
        // Who answers (a tap changes it, and holds the chat's other choices), the menu button, and a new chat.
        // The phone takes over when the PC or its model is away: the bar says so at once, and why.
        val lastAnswer = s.chat.turns.lastOrNull { !it.user }
        // The last answer is evidence only if it came after the person's latest choice of who answers.
        var turnsAtChoice by remember { mutableStateOf(0) }
        var choiceSeen by remember { mutableStateOf(s.usePc) }
        if (choiceSeen != s.usePc) { choiceSeen = s.usePc; turnsAtChoice = s.chat.turns.size }
        // With Online chosen on the PC, an earlier answer from this phone changes nothing: the next message still
        // goes Online, so the bar keeps saying so.
        val now = whoNow(paired, s.usePc, s.supported && s.ready, pcOnline, lastAnswer?.ran.takeUnless { claudeAnswers }, lastAnswer?.switchNote, s.whisper, onlineOn, answeredAfterChoice = s.chat.turns.size > turnsAtChoice)
        val answeringPc = paired && (s.usePc || !s.supported) && !s.whisper && !onlineOn && !now.phoneTookOver
        named[0] = shownAnswerer(if (onlineOn) io.github.odnwks.mimir.online.OnlineModels.answering()?.let { io.github.odnwks.mimir.online.OnlineModels.serviceOf(it).name } ?: "Online" else null, answeringPc, claudeAnswers, choiceKnown)
        if (s.whisper || pcWhisper) WhisperTopBar(
            onEnd = { if (pcWhisper) pcWhisper = false else if (!s.writing) engine.endWhisper(told = true) },
            onHold = { draft = ""; files.clear(); pictures.clear(); emergencyExit(ctx, engine, readAloud) },
            waiting = menuWaiting,
        ) else MenuTopBar(
            waiting = menuWaiting,
            answerer = answererWord(s.whisper, onlineOn, answeringPc, claudeAnswers, io.github.odnwks.mimir.online.OnlineModels.answering()?.let { io.github.odnwks.mimir.online.OnlineModels.serviceOf(it).name }, known = choiceKnown),
            online = if (s.whisper) null else answeringPc && pcOnline,
            answererDescription = when { s.whisper && s.web -> END_WEB_WHISPER; s.whisper -> END_WHISPER; else -> whoLabel(if (onlineOn) (io.github.odnwks.mimir.online.OnlineModels.answering()?.let { io.github.odnwks.mimir.online.OnlineModels.serviceOf(it).name } ?: "Online") else if (answeringPc) (if (!choiceKnown) WHO_READING else if (claudeAnswers) "Online" else "Your PC") else "This phone") },
            onNewChat = newChat,
            trailing = if (s.whisper && s.web) ({ ViaTorChip() }) else null,
            onAnswererHold = if (s.whisper) ({ draft = ""; files.clear(); pictures.clear(); emergencyExit(ctx, engine, readAloud) }) else null,
            // A tap: who should answer (in a Whisper, one tap ends it, as its own End).
            onPick = if (s.whisper) ({ if (!s.writing) engine.endWhisper(told = true) }) else ({ whoOpen = true }),
            // Only while a chat with messages is open (R7); voice typing for the box is in it then.
            onMore = if (!s.whisper && s.chat.turns.isNotEmpty()) ({ nav.openSheet(chatOptions(s, pcPrivate, if (onlineOn) io.github.odnwks.mimir.online.OnlineModels.answering()?.let { io.github.odnwks.mimir.online.OnlineModels.serviceOf(it).company } else null).copy(talk = io.github.odnwks.mimir.nav.TALK_MIMIR)) }) else null,
        )
        now.why?.let { why -> Text(why, style = Type.Small, color = Palette.TextDim, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp).testTag("who-why").semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }) }
        if (!pcWhisper) ChatLiveLine(s)
        if (finding && !pcWhisper) FindBar(find, { find = it }, if (sought == null) 0 else s.chat.turns.count { found(it.text) } + s.chat.queued.count { found(it.text) }) { finding = false; find = "" }
        WhisperHygiene(s.whisper || pcWhisper)
        WhisperEndedNote(s.whisperEnded, engine::dismissWhisperEnded)
        EndWhisperQuestion(endAsk, onCancel = { endAsk = false; endThen = null }) { endAsk = false; engine.endWhisper(); endThen?.invoke(); endThen = null }
        WhisperNeedsMimir(needsMimir, model.sizeText, onGet = { needsMimir = false; nav.open(Screen.Models) }, onClose = { needsMimir = false })
        val answerer = when { onlineOn -> Answerer.Online; claudeAnswers && pcPrivate && !now.phoneTookOver -> Answerer.Claude; pcPrivate && !now.phoneTookOver -> Answerer.Pc; else -> Answerer.Phone }
        val showHome = !s.whisper && !pcWhisper && status == null && s.chat.turns.isEmpty() && s.chat.queued.isEmpty() && !s.writing && !getting && !renaming
        val first = isFirstOpen(hasChatted, s.earlier.isNotEmpty())
        // The startup animation belongs to the home screen: opening on a chat or a Whisper plays nothing; typing skips it.
        val startup = LocalEyeStartup.current
        LaunchedEffect(Unit) { if (!showHome) startup.skip() }
        LaunchedEffect(draft) { if (draft.isNotEmpty()) startup.skip() }

        if (showHome && !first) NeedsYouPill(needsYou, pillDismissedFor, { pillDismissedFor = it }, { nav.open(Screen.NeedsYou) })
        // Each chat has its own place in the list: another chat opens at its newest.
        val list = androidx.compose.runtime.key(s.chat.id) { rememberLazyListState() }
        LaunchedEffect(sought) { if (sought != null) list.scrollToItem(0) }
        val newestFound = if (sought == null) -1 else s.chat.turns.indexOfLast { found(it.text) }
        val focus = LocalFocusManager.current
        // Every line that can come in under the messages, so a new one is followed or waits behind "New lines".
        val lines = rememberNewLines(
            list, s.chat.id,
            listOf(s.chat.turns.size, s.chat.queued.size, s.writing, waiting, getting, s.askPlace, s.askSearch != null, s.lookupFailed),
            if (s.writing) s.partial.length else 0,
            s.chat.turns.count { it.user } + s.chat.queued.size,
        )
        LaunchedEffect(status?.asked) {
            val asked = status?.asked ?: return@LaunchedEffect
            if (asked > io.github.odnwks.mimir.data.StatusCards.scrolled) {
                kotlinx.coroutines.delay(100)
                list.animateScrollToItem(0)
                io.github.odnwks.mimir.data.StatusCards.scrolled = asked
            }
        }
        ChatTextSize {
        if (showHome) HomeEmpty(first, answerer, Modifier.weight(1f), online = io.github.odnwks.mimir.online.OnlineModels.answering()?.let(io.github.odnwks.mimir.online.OnlineModels::serviceOf))
        else androidx.compose.runtime.CompositionLocalProvider(LocalFound provides sought) { LazyColumn(
            Modifier.weight(1f).fillMaxWidth().pinchToZoomText(LocalAppSize.current).pointerInput(Unit) { detectTapGestures(onTap = { focus.clearFocus() }) },
            state = list,
            reverseLayout = true,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.Bottom),
        ) {
            // The list is upside down: the first items here sit at the bottom.
            // A Whisper on the PC starts empty: the open chat stays behind it, none of it shown or sent (QA16 Q20).
            if (pcWhisper) {
                pcWhisperBlock?.let { why -> item(key = "pc-whisper-block") {
                    WhisperCantStart(why, onMakeProject = if (why == WHISPER_NEEDS_PROJECT) ({ nav.openSheet(io.github.odnwks.mimir.nav.Sheet.ProjectPicker("", newProject = true, whisper = true)) }) else null)
                } }
                item(key = "pc-whisper") { WhisperCard(false, paired) }
                return@LazyColumn
            }
            // A message no one can answer now: said plainly, with the one thing to do.
            val stoppedHere = s.stoppedChat == s.chat.id
            // Messages waiting for the PC sit at the bottom, newest last: dim, with how they go and a way to take them back.
            items(s.chat.queued.reversed().filter { found(it.text) }, key = { "queued-${it.id}" }) { q ->
                // Gone back to waiting (the PC left on the way): the answer it was to replace stays under it.
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OwnMessage(
                    q.text.ifBlank { if (q.voice != null) "Voice message" else (q.files.map { it.name } + listOfNotNull(q.pictures.size.takeIf { it > 0 }?.let { n -> if (n == 1) "A picture" else "$n pictures" })).joinToString() },
                    Modifier.animateItem(fadeInSpec = motionSpec(Motion.NEW_ITEM_MS), placementSpec = motionSpec(Motion.NEW_ITEM_MS), fadeOutSpec = motionSpec(Motion.NEW_ITEM_MS)),
                    bubble = Palette.BlueBubble.copy(alpha = 0.55f), dashed = true, id = "queued-${q.id}",
                    meta = { ChatMeta(q.at, null, pcName, MessageReceipt.Sent, q.skill) },
                    under = {
                        val waits = when {
                            q.heldFor != null -> heldWords(q.saw, q.heldFor)
                            s.chat.queued.firstOrNull()?.heldFor != null -> QUEUED_BEHIND
                            paired && engine.pc?.cannotChat() == true -> QUEUED_NO_CHAT
                            pcOnline && paired -> QUEUED_SOON
                            else -> QUEUED_WORDS
                        }
                        Text(waits, style = Type.Small, color = Palette.TextDim, modifier = Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite })
                        if (q.heldFor != null) LinkText(SEND_HELD, { engine.sendHeld(q.id) })
                        IconTarget(Glyphs.Close, "Cancel this message: it won't be sent", { engine.cancelQueued(q.id) }, tint = Palette.TextDim)
                    },
                )
                s.chat.old[io.github.odnwks.mimir.standalone.answerIdOf(q)]?.lastOrNull()?.let { EarlierAnswer(it, q.text, !s.whisper, if (s.whisper) null else pcName) }
                }
            }
            if (waiting && !stoppedHere && paired && (!s.supported || !s.usePc)) item(key = "no-model") { NothingAnswers(s.supported, pcOnline, pcName) { engine.setUsePc(true); engine.answerIfWaiting() } }
            else if ((waiting && !stoppedHere) || getting) item(key = "get-ai") { GetYourAi(engine, s, model, waiting, paired) }
            if (s.web) item(key = "tor") { TorLines(s, engine) }
            // A question only a task on the PC can do offers it right there; picking a project puts the words in its box, unsent.
            if (!s.writing && !s.whisper && paired && owner && found("")) {
                val answer = s.chat.turns.lastOrNull()?.takeIf { !it.user && it.task == null }
                val question = s.chat.turns.dropLast(1).lastOrNull()?.takeIf { it.user }?.text.orEmpty()
                if (answer != null && question.isNotBlank() && needsProjectWork(question, answer.text)) item(key = "project-work") {
                    SmallButton(PROJECT_WORK, { nav.openSheet(io.github.odnwks.mimir.nav.Sheet.TakeToProject(question)) }, description = PROJECT_WORK)
                }
            }
            if (s.writing) item(key = "writing") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    WritingReply(s, openSources = !s.whisper)
                    // The link dropped mid-answer: the PC finishes anyway; or this phone answers now.
                    if (s.onPc && s.pcWaiting) {
                        // With no words yet the reply line says it; with some, it sits under them. Said once.
                        if (s.partial.isNotEmpty()) Text(PC_AWAY_WORDS, style = Type.Small, color = Palette.TextDim, modifier = Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite })
                        // Only a phone with a model ready can answer instead.
                        if (s.ready) SmallButton("Answer on this phone", engine::answerOnPhone, description = "Answer on this phone instead")
                    }
                }
            }
            if (s.askPlace && !s.writing) item(key = "place") {
                PlaceQuestion(engine::answerPlace, roughPlace, engine::skipPlace)
            }
            s.askSearch?.let { words -> if (!s.writing) item(key = "search") { SearchQuestion(words, engine::acceptSearch, engine::declineSearch) } }
            if (!s.writing && !s.askPlace && s.askSearch == null && (s.ready || stoppedHere || pcReady) && s.chat.turns.lastOrNull()?.user == true) item(key = "interrupted") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(interruptedWords(stoppedHere, answeringPc, s.problem?.takeIf { it != Engine.PC_COULD_NOT_ANSWER }), style = Type.Small, color = Palette.TextDim)
                    SmallButton("Try again", engine::retry, description = "Ask the last message again")
                }
            }
            // The last answer's lookup failed (no signal, the PC away, the site down): ask again, or answer without it.
            if (!s.writing && s.lookupFailed && s.chat.turns.lastOrNull()?.user == false) item(key = "lookup-again") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SmallButton("Try again", engine::lookupAgain, description = "Look it up again")
                    SmallButton("Answer without looking it up", engine::answerWithoutLookup, description = "Answer this without the internet")
                }
            }
            // A kept part of an answer the owner stopped: said once, under it.
            if (!s.writing && stoppedHere && s.chat.turns.lastOrNull()?.user == false) item(key = "stopped") {
                Text(YOU_STOPPED, style = Type.Small, color = Palette.TextDim)
            }
            // The Status card sits after the message that was last when it was asked for (the list runs upwards).
            val cut = status?.let { c ->
                if (c.after.isEmpty()) 0 else s.chat.turns.indexOfLast { it.id == c.after }.let { if (it < 0) s.chat.turns.size else it + 1 }
            } ?: 0
            val shownTurns = s.chat.turns.withIndex().reversed().filter { found(it.value.text) }
            val turnRow: @Composable androidx.compose.foundation.lazy.LazyItemScope.(IndexedValue<io.github.odnwks.mimir.standalone.Turn>) -> Unit = { (i, turn) ->
              androidx.compose.runtime.CompositionLocalProvider(LocalFindJump provides (i == newestFound)) {
                val m = Modifier.animateItem(fadeInSpec = motionSpec(Motion.NEW_ITEM_MS), placementSpec = motionSpec(Motion.NEW_ITEM_MS), fadeOutSpec = motionSpec(Motion.NEW_ITEM_MS))
                if (turn.user) Column(m, verticalArrangement = Arrangement.spacedBy(16.dp)) { OwnMessage(
                    turn.text.ifBlank { (turn.files.map { it.name } + listOfNotNull(turn.pictures.size.takeIf { it > 0 }?.let { if (it == 1) "A picture" else "$it pictures" })).joinToString() },
                    Modifier, bubble = if (s.whisper) WhisperTint.Bubble else Palette.BlueBubble,
                    meta = { ChatMeta(turn.at, null, pcName, chatReceipt(s.chat.turns, i, s.writing, s.partial.isNotEmpty()), turn.skill) },
                    id = turn.id.ifEmpty { "mine-${turn.text.hashCode()}" },
                    onEdit = if (!s.writing && turn.text.isNotBlank() && engine.canEdit(s.chat, i) && engine.canReplyNow()) ({ words -> engine.editMessage(turn.id, words)?.let { edited = it } }) else null,
                    under = readLinkFor(s, engine, turn, i).let { read ->
                        if (turn.pictures.isNotEmpty() || (turn.files.isNotEmpty() && turn.text.isNotBlank()) || turn.files.any { it.note.isNotEmpty() } || read != null) ({
                            turn.pictures.forEach { PictureChip(it) }
                            // With no words of its own, the message shows the file names; what the PC read still shows under them.
                            if (turn.text.isNotBlank()) turn.files.forEach { FileChip(it) } else turn.files.forEach { FileNoteLine(it) }
                            read?.invoke()
                        }) else null
                    },
                )
                // Being answered again (or it failed): the earlier answer stays until a new one is there.
                if (s.chat.turns.getOrNull(i + 1)?.user != false) s.chat.old[io.github.odnwks.mimir.standalone.answerIdOf(turn)]?.lastOrNull()?.let { EarlierAnswer(it, turn.text, !s.whisper, if (s.whisper) null else pcName) }
                } else if (turn.task != null) androidx.compose.foundation.layout.Box(m) {
                    // A message that named a project: one card that opens its task (Stage 1, 2.3).
                    ProjectCard(
                        turn, task(turn.task), newest = i == s.chat.turns.lastIndex,
                        onOpen = { nav.open(Screen.Task(turn.task)) },
                        onChange = { changing = turn.task; nav.openSheet(io.github.odnwks.mimir.nav.Sheet.ProjectPicker(projectWords(turn.project), justChat = true)) },
                    )
                } else androidx.compose.foundation.layout.Box(m) {
                    val before = s.chat.turns.getOrNull(i - 1)?.takeIf { it.user }
                    WithEarlier(turn.id, before?.let { s.chat.old[io.github.odnwks.mimir.standalone.answerIdOf(it)] }.orEmpty(), before?.text.orEmpty(), !s.whisper, if (s.whisper) null else pcName) {
                        Reply(
                            turn.text, dim = false, sources = turn.sources, note = turn.switchNote, lookUp = lookUp.takeIf { i == s.chat.turns.lastIndex && !s.lookupFailed }, openSources = !s.whisper,
                            onTryAgain = if (s.writing || s.whisper || i != s.chat.turns.lastIndex || s.lookupFailed) null else before?.let { { engine.tryAgain() } },
                            meta = { ChatMeta(turn.at, turn, pcName) },
                            fromMemory = fromMemory(turn, before),
                        )
                    }
                }
              }
            }
            val turnKey = { t: IndexedValue<io.github.odnwks.mimir.standalone.Turn> -> "${s.chat.id}-${t.index}" }
            if (status != null) {
                items(shownTurns.filter { it.index >= cut }, key = turnKey, itemContent = turnRow)
                item(key = "status-card") { StatusCardInChat(status, station ?: link, Modifier.animateItem(fadeInSpec = motionSpec(Motion.NEW_ITEM_MS))) { nav.open(Screen.Task(it)) } }
            }
            items(shownTurns.filter { status == null || it.index < cut }, key = turnKey, itemContent = turnRow)
            if (s.whisper) item(key = "whisper") {
                WhisperCard(s.web, paired, if (s.chat.turns.isEmpty() && !s.writing) ({ w: Boolean -> engine.startWhisper(web = w) }) else null)
            }
        }
} }
        if (!showHome) NewLinesButton(lines)
        s.note?.takeIf { !pcWhisper }?.let { note ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(note, style = Type.Small, color = Palette.TextSoft, modifier = Modifier.weight(1f))
                LinkText("Dismiss", engine::dismissNote)
            }
        }
        s.problem?.takeIf { !pcWhisper && it != Engine.PC_COULD_NOT_ANSWER && (s.writing || s.chat.turns.lastOrNull()?.user != true) }?.let { Text(it, style = Type.Small, color = Palette.Red, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }
        if (s.whisperChoice) Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (s.supported) SmallButton("Use This phone", { engine.stopOnlineFor(false) }, description = "Use this phone's model, not the online one")
            if (paired) SmallButton("Use your PC", { engine.stopOnlineFor(true) }, description = "Use your PC, not the online model")
        }
        UndoBar(deleted, onUndo = { deleted?.let(engine::restore); deleted = null }, onGone = { deleted = null; engine.forgetDeleted() })
        // After an edit: the cut turns stay in memory for a few seconds, so Undo can bring them back.
        UndoBar(edited, onUndo = { edited?.let(engine::undoEdit); edited = null }, onGone = { edited = null }, words = "Later messages removed", forMs = EDIT_UNDO_MS)
        DeleteChatQuestion(asking, onCancel = { asking = null }) { c -> asking = null; deleted = engine.deleteChat(c.id) }
        ForgetDeletedChatsWhenLeaving(engine)
        // The tabs' bar sits below (it keeps clear of the phone's own bar); the keyboard lifts the box.
        Column(Modifier.fillMaxWidth().imePadding()) {
        val voice = rememberVoiceInput(if (s.whisper || pcWhisper) null else link, whisper = s.whisper || pcWhisper, keep = { wav, secs -> engine.keepRecording(wav, secs) })
        VoicePanel(voice, heard = { if (pcWhisper) whisperDraft = withHeard(whisperDraft, it) else draft = withHeard(draft, it) }, Modifier.padding(top = 4.dp))
        attach.Note()
        // While the chat is in a project (its last card's task isn't done), a follow-up goes there; ✕ leaves it.
        val card = s.chat.projectCard?.takeIf { !s.whisper && !pcWhisper && it.task?.let(task)?.stateWords != "Done" }
        androidx.compose.animation.AnimatedVisibility(
            card != null,
            enter = androidx.compose.animation.expandVertically(motionSpec(Motion.NEEDS_YOU_BAR_MS)) + androidx.compose.animation.fadeIn(motionSpec(Motion.NEEDS_YOU_BAR_MS)),
            exit = androidx.compose.animation.shrinkVertically(motionSpec(Motion.NEEDS_YOU_BAR_MS)) + androidx.compose.animation.fadeOut(motionSpec(Motion.NEEDS_YOU_BAR_MS)),
        ) {
            val shown = remember { arrayOf("") }
            card?.let { shown[0] = projectWords(it.project) }
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("In ${shown[0]}", style = Type.Small, color = Palette.TextSoft, modifier = Modifier.weight(1f))
                IconTarget(Glyphs.Close, "Leave ${shown[0]}: the next message is a chat", engine::leaveTask, tint = Palette.TextDim)
            }
        }
        if (showHome) {
            val last = s.earlier.filter { it.topic != null }.maxByOrNull(io.github.odnwks.mimir.data.ListOrder::chat)
            StarterRow(
                startersFor(first, paired && owner, unheard, last?.title, last?.id, last?.let(io.github.odnwks.mimir.data.ListOrder::chat) ?: 0),
                { st -> if (startup.takeSwallowedTap()) return@StarterRow else when (val a = st.action) {
                    is StarterAction.Send -> send(a.text, false)
                    StarterAction.OpenUpdates -> nav.selectTab(Tab.Updates)
                    is StarterAction.OpenChat -> engine.open(a.id)
                } },
                Modifier.startupBottom(startup, on = first),
            )
        }
        // Claude use left this week, with the model choice: only once the PC has a reading.
        if (paired && claudeOffered && !s.whisper && !pcWhisper) link?.state?.collectAsStateWithLifecycle()?.value?.usage?.let(::usageLeftWords)?.let { words ->
            Text(words, style = Type.Small, color = Palette.TextDim, modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp).testTag("usage-left"))
        }
        // The three dots' "Talk (voice typing)", done here (the mic in the box does the same).
        val startVoice = rememberStartVoice(voice)
        LaunchedEffect(nav.taskAsk) {
            if (nav.taskAsk == io.github.odnwks.mimir.nav.TALK_MIMIR) { nav.taskAsk = null; startVoice() }
        }
        // The chip names the model that answers (who answers is in the top bar) and the effort; a tap opens the
        // same model and effort sheet as a task's, listing this chat's own models and efforts.
        val installed = Catalog.withOwn().filter { it.id in s.have }
        val more = { nav.closeSheet(); nav.open(Screen.Models) }
        val choices: io.github.odnwks.mimir.nav.ModelEffortChoices
        val modelWord: String?
        if (pcPrivate && !now.phoneTookOver) {
            val said = claudeModelWord(s.chat.turns.lastOrNull { !it.user && it.ran == "paid" }?.modelWords)
            val chosen = claudeChoices.firstOrNull { it.id == claudeModel && it.id != "auto" }
            val word = if (claudeAnswers) claudeChipWordFor(chosen?.name, said) else pcModelWord(pcModel).let { if (it == "Auto-pick") "Your PC's model" else it }
            val about = if (claudeAnswers) "$pcName picks the model itself; your messages go to Anthropic" else "On $pcName"
            val chosenId = claudeModel ?: "auto"
            val rows = if (claudeAnswers && claudeChoices.isNotEmpty()) claudeChoices.map { c ->
                io.github.odnwks.mimir.nav.SheetChoice(c.name, c.about.ifBlank { null }?.let { claudeAbout(c.id, it) }, c.id == chosenId) { if (c.id != chosenId) onPickClaudeModel(c.id) }
            } else listOf(io.github.odnwks.mimir.nav.SheetChoice(word, about, true) {})
            // Claude answering: a way back to the PC's own free model, right here.
            val free = if (claudeAnswers) listOf(io.github.odnwks.mimir.nav.SheetChoice("Your PC's free model", "Quick answers that stay on your PC", false) { onPickEngine("free") }) else emptyList()
            // Whole words on the chip ("Qwen 3.6" from "Qwen 3.6 35B-A3B"), never cut mid-word; no model is named
            // while the PC's choice of who answers has never been read.
            modelWord = chipShort(word, 16).takeIf { choiceKnown }
            choices = io.github.odnwks.mimir.nav.ModelEffortChoices(
                rows + free,
                listOfNotNull("Applies to all chats on this PC. $PC_PICKS".takeIf { claudeAnswers && claudeChoices.isNotEmpty() }, said?.takeIf { claudeAnswers }?.let { "Last answer: $it" }),
                more, emptyList(),
            )
        } else {
            modelWord = chipShort(model.chipWord, 16).takeIf { installed.isNotEmpty() }
            choices = io.github.odnwks.mimir.nav.ModelEffortChoices(
                installed.map { m ->
                    io.github.odnwks.mimir.nav.SheetChoice(m.chipName, m.chipWord + " · " + m.sizeText + if (m.refusalsRemoved) " · uncensored" else "", m.id == model.id) { if (m.id != model.id) engine.choose(m.id) }
                },
                emptyList(), more, emptyList(),
            )
        }
        val efforts = Effort.entries.map { e -> io.github.odnwks.mimir.nav.SheetChoice(e.words, effortAbout(e, model), e == s.effort) { if (e != s.effort) engine.setEffort(e) } }
        val chosenWords = listOfNotNull(modelWord, s.effort.words).joinToString(" · ")
        // Shorter whole words for less room, then the model alone: the chip always shows which model answers
        // (the owner watches it switch); the effort goes before the model if something must.
        val shortModel = modelWord?.let { if (it == "Your PC's model") "PC model" else chipShort(it) }
        val shortWords = listOfNotNull(listOfNotNull(shortModel, s.effort.words).joinToString(" · ").takeIf { shortModel != null }, shortModel ?: s.effort.words)
        androidx.compose.runtime.SideEffect { nav.chatChoices = io.github.odnwks.mimir.nav.ModelEffortChoices(choices.models, choices.notes, choices.more, efforts) }
        androidx.compose.runtime.DisposableEffect(Unit) { onDispose { nav.chatChoices = null } }
        // A Whisper on the PC that can't start offers no box and no Send: its card says what would make it work.
        if (pcWhisperBlock == null) NoKeyboardLearning(s.whisper || pcWhisper) {
        MimirComposer(
            if (pcWhisper) whisperDraft else draft,
            { if (pcWhisper) whisperDraft = it else { draft = it; if (it.isNotBlank()) engine.prepare() } },
            waiting && !pcWhisper, s.writing && !pcWhisper, voice, onSend = { send(if (pcWhisper) whisperDraft else draft, true) }, onStop = engine::stop, focusTick = focusTick, focusOk = !s.whisper && !pcWhisper && !endAsk && !whoOpen && !needsMimir && nav.sheet == null && !nav.menuOpen,
            whisper = s.whisper || pcWhisper, waitHint = !paired, modifier = Modifier.startupBottom(startup, on = showHome && first).padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 10.dp),
            // A Whisper on the PC takes words only: the open chat's pictures and files stay with that chat.
            hasFiles = !pcWhisper && (files.isNotEmpty() || pictures.isNotEmpty()),
            above = { if (!pcWhisper) attach.Chips() },
            attach = { if (!pcWhisper) attach.Button(sight, onModels = { nav.open(Screen.Models) }) },
            // At large text or on a narrow phone, with the mic beside Send, there is less room: shorter whole words, never cut off.
            chip = { OptionsChip(chosenWords, "Model and effort: $chosenWords. Change", shorter = shortWords) { nav.openSheet(io.github.odnwks.mimir.nav.Sheet.ChatModel) } },
            skill = { SkillChip(nav.skill) { nav.openSheet(io.github.odnwks.mimir.nav.Sheet.ChatSkill) } },
            skillNeeds = skillChipNeeds(nav.skill),
            chipNeeds = optionsChipNeeds(chosenWords, shortWords),
        )
        }
        if (showHome && first && !paired && !connectHidden) ConnectHint({ nav.open(Screen.Pair) }, { connectHidden = true; prefs.edit().putBoolean(CONNECT_HIDDEN, true).apply() })
        }
    }
    val pcAnswers = paired && (s.usePc || !s.supported) && !s.whisper
    WhoAnswersSheet(
        open = whoOpen && !s.whisper,
        chosen = when { pcAnswers && !choiceKnown -> null; claudeAnswers && pcAnswers -> Answerer.Claude; pcAnswers -> Answerer.Pc; else -> Answerer.Phone },
        phoneCan = s.supported, paired = paired, claudeOffered = claudeOffered, pcOnline = pcOnline,
        onChoose = { who ->
            whoOpen = false
            when (who) {
                Answerer.Phone -> if (paired && s.supported) { if (s.ready) engine.setUsePc(false) else nav.open(Screen.Models) }
                Answerer.Pc -> { engine.setUsePc(true); if (claudeAnswers) onPickEngine("free") }
                Answerer.Claude -> { engine.setUsePc(true); onPickEngine("paid") }
                Answerer.Online -> {}
            }
        },
        onConnect = { whoOpen = false; nav.open(Screen.Pair) },
        onClose = { whoOpen = false },
    )
    if (renaming) TopicEditor(topic.orEmpty(), onCancel = { renaming = false }) { engine.rename(it); renaming = false }
    if (rulesOpen) ChatRulesDialog(s.chat.rules, onSave = { engine.setChatRules(it); rulesOpen = false }, onClose = { rulesOpen = false })
    if (sentOpen) ChatSentDialog(s.chat, pcPrivate, if (onlineOn) io.github.odnwks.mimir.online.OnlineModels.answering()?.let { io.github.odnwks.mimir.online.OnlineModels.serviceOf(it).company } else null, onClose = { sentOpen = false })
    }
}

private const val HAS_CHATTED = "has_chatted"
private const val CONNECT_HIDDEN = "connect_hint_hidden"

/** The PC's chosen model in a few plain words for the box's chip ("Qwen 3.5 9B"). */
fun pcModelWord(name: String?): String = name?.substringBefore(',')?.substringBefore(" (")?.trim()?.takeIf { it.isNotEmpty() }?.let { if (it.equals("auto", true)) "Auto-pick" else it } ?: "Your PC's model"

/** A project's folder name as the chat says it ("Garden planner-Mod-Manager"). */
private fun projectWords(path: String?) = path.orEmpty().trimEnd('/').substringAfterLast('/')

/**
 * A message that named a project became a task there: one card, "Started in Garden planner-Mod-Manager ·
 * Working", that opens the task. The newest says "Filed under X · change" once, fading after 10 s.
 */
@Composable
private fun ProjectCard(turn: io.github.odnwks.mimir.standalone.Turn, task: io.github.odnwks.mimir.data.Task?, newest: Boolean, onOpen: () -> Unit, onChange: () -> Unit) {
    val project = projectWords(turn.project)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Panel(Modifier.fillMaxWidth(), border = Palette.BlueBorder, background = Palette.BlueCard, radius = 16.dp, spacing = 2.dp, onClick = onOpen, onClickLabel = "Open the task") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(turn.text, style = Type.BodyTight.copy(fontWeight = FontWeight.SemiBold), color = Palette.Text)
                    Text(task?.stateWords ?: "Working", style = Type.Small, color = Palette.TextSoft)
                }
                androidx.compose.material3.Icon(Glyphs.Next, null, tint = Palette.TextDim, modifier = Modifier.padding(2.dp))
            }
        }
        var filed by remember(turn.id) { mutableStateOf(newest && System.currentTimeMillis() - turn.at < FILED_NOTE_MS) }
        LaunchedEffect(turn.id, filed) { if (filed) { kotlinx.coroutines.delay((FILED_NOTE_MS - (System.currentTimeMillis() - turn.at)).coerceAtLeast(0)); filed = false } }
        androidx.compose.animation.AnimatedVisibility(filed, enter = androidx.compose.animation.fadeIn(motionSpec(Motion.NEW_ITEM_MS)), exit = androidx.compose.animation.fadeOut(motionSpec(Motion.SCREEN_MS))) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Filed under $project ·", style = Type.Small, color = Palette.TextDim)
                LinkText("change", onChange)
            }
        }
    }
}

/** How long "Filed under X · change" shows under a new project card. */
private const val FILED_NOTE_MS = 10_000L

/**
 * Paired, a message waits that nothing can answer now: this phone can't run a model (or "This phone
 * only" is chosen with none here). One plain card, and the switch to the PC in one tap.
 */
@Composable
private fun NothingAnswers(phoneCan: Boolean, pcOnline: Boolean, pcName: String, usePc: () -> Unit) {
    Panel(border = Palette.BlueBorder, background = Palette.BlueCard, radius = 16.dp, spacing = 10.dp, modifier = Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }) {
        Text(
            if (phoneCan) "There's no model on this phone yet, so \"This phone only\" has nothing to answer with." else "This phone can't run a model.",
            style = Type.BodyTight.copy(fontWeight = FontWeight.SemiBold), color = Palette.Text,
        )
        Text(
            if (phoneCan && pcOnline) "Switch to $pcName and it answers now." else "Your message is kept and $pcName answers it as soon as it's reachable.",
            style = Type.Label, color = Palette.TextSoft,
        )
        if (phoneCan) BigButton("Use $pcName", Look.Primary, usePc, height = 48.dp)
    }
}

/** The one card of the first run: the one-time download, its progress, and why it's needed. */
@Composable
private fun GetYourAi(engine: Engine, s: io.github.odnwks.mimir.standalone.LocalState, model: io.github.odnwks.mimir.standalone.LocalModel, waiting: Boolean, paired: Boolean = false) {
    Panel(border = Palette.BlueBorder, background = Palette.BlueCard, radius = 16.dp, spacing = 10.dp) {
        Text("Get $AI_NAME (${model.sizeText})", style = Type.Section, color = Palette.Text, modifier = Modifier.semantics { heading() })
        Text(
            "A one-time download of ${model.sizeText}, best on Wi-Fi. " +
                if (waiting) "Your message is answered as soon as it's ready." else "Then it works without internet.",
            style = Type.Label, color = Palette.TextSoft,
        )
        DownloadControls(engine, s, model)
    }
}

/** A phone that can't run an AI itself: say so plainly, and offer the PC. */
@Composable
private fun CantRunHere(nav: Navigator) {
    Column(Modifier.fillMaxSize().background(Palette.Ground)) {
        MenuTopBar(waiting = LocalMenuCount.current, answerer = "This phone", online = false, onNewChat = null, picker = null)
        Panel(Modifier.padding(16.dp), spacing = 10.dp) {
            Text("This phone can't run an AI by itself".forDevice(), style = Type.BodyTight.copy(fontWeight = FontWeight.SemiBold), color = Palette.Text)
            Text("It needs a phone from about 2021 on. If you have a PC with $AI_NAME, connect it and chat from here.".forDevice(), style = Type.Label, color = Palette.TextSoft)
            BigButton("Connect your PC", Look.Primary, { nav.open(Screen.Pair) }, height = 48.dp)
        }
    }
}

/** The topic sheet for the open chat: what it allows right now. */
fun chatOptions(s: io.github.odnwks.mimir.standalone.LocalState, pc: Boolean = false, online: String? = null) = io.github.odnwks.mimir.nav.Sheet.ChatOptions(
    topic = s.chat.topic,
    canRename = s.chat.turns.isNotEmpty() && !s.writing && !s.whisper,
    whisper = s.whisper,
    canWhisper = !s.writing,
    pc = pc,
    online = online,
    sentToPc = s.chat.sentToPc,
    lookups = s.chat.turns.sumOf { it.sources.size },
)

/** Said once when the PC went away while it was answering (R1): no "Waking…" or "Waiting for…" beside it. */
const val PC_AWAY_WORDS = "Your PC is away. Your message is kept and sends when it's back."

/** The line of the reply being written: one true story (R1: a PC that dropped says so, not what it said before). */
internal fun writingWords(s: io.github.odnwks.mimir.standalone.LocalState): String = when {
    s.looking != null -> s.looking!!
    s.loading -> "$AI_NAME is getting ready…"
    s.onPc && s.partial.isEmpty() && s.pcWaiting -> PC_AWAY_WORDS
    s.onPc && s.partial.isEmpty() && s.pcLine != null -> s.pcLine!!
    s.thinking -> "Thinking it through…"
    // Before the first word it is reading, not thinking (only real thinking says so).
    s.partial.isEmpty() -> when { s.onPc -> "Writing…"; s.readingFound -> "Reading it…"; else -> "Reading your message…" }
    else -> s.partial
}

/** Who the top bar names, as kept with a message typed under it (see Turn.saw). */
fun shownAnswerer(onlineName: String?, answeringPc: Boolean, claudeAnswers: Boolean, known: Boolean): String? = when {
    onlineName != null -> "own:$onlineName"
    !answeringPc -> "phone"
    !known -> null
    claudeAnswers -> "paid"
    else -> "pc"
}

/** Under a held message: why it wasn't sent. */
fun heldWords(saw: String?, heldFor: String): String = "Not sent: ${answererName(heldFor)} answers now" + (saw?.let { ", not ${answererName(it)}" } ?: "") + "."

const val SEND_HELD = "Send it again"

/** Under a message waiting behind a held one. */
const val QUEUED_BEHIND = "Waits for the message above"

/** Under a waiting message when the PC is connected but too old to answer chats. */
const val QUEUED_NO_CHAT = "Your PC can't answer chats yet. Update Mímir on your PC."

/** What a message waiting for the PC says under itself. */
const val QUEUED_WORDS = "Sends when your PC is back"

/** The same, while the PC is there (it is still saying who answers, or an earlier message is being answered). */
const val QUEUED_SOON = "Sends in a moment"


/** The line under the chat when a photo, the camera or a file can't be used: the words fill the width, the buttons wrap under them when they don't fit beside each other. */
@Composable
internal fun AttachNote(words: String, fix: Pair<String, () -> Unit>?, onDismiss: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 4.dp).semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(words, style = Type.Small, color = Palette.AmberText, modifier = Modifier.fillMaxWidth())
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            fix?.let { (label, run) -> SmallButton(label, { run() }) }
            LinkText("Dismiss", onDismiss)
        }
    }
}


/** The note shown when pictures are in the box but the answerer can't look at them now; null when they can go. */
internal fun picturesHeldNote(sight: Sight, count: Int): String? =
    if (count == 0 || sight == Sight.Sees) null
    else "Your pictures are kept here. The answerer can't look at pictures right now. Remove them to send just the words, or try again in a moment."
