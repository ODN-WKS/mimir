package io.github.odnwks.mimir.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.odnwks.mimir.data.Station
import io.github.odnwks.mimir.data.StationState
import io.github.odnwks.mimir.data.seenWords
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.nav.Tab
import io.github.odnwks.mimir.standalone.Engine
import io.github.odnwks.mimir.ui.theme.Motion
import io.github.odnwks.mimir.ui.theme.Palette
import io.github.odnwks.mimir.ui.theme.Type
import io.github.odnwks.mimir.ui.theme.motionSpec
import kotlin.math.abs

/*
 * The chat's eye is also its menu (owner, 2026-11-19): a tap opens Chats (every earlier
 * conversation), New chat and New Whisper, with the chat's mode marked. A swipe in from the
 * screen's left edge opens Chats too.
 */

/** Screenshots only: the eye's menu shows open from the start. */
val LocalEyeMenuOpen = androidx.compose.runtime.staticCompositionLocalOf { false }

const val EYE_CHATS = "Chat History"
const val EYE_NEW_CHAT = "New Chat"
const val EYE_NEW_WHISPER = "New Whisper"
const val EYE_NEW_WEB_WHISPER = "New Whisper-O"
/** Paired: a new chat that runs on this phone's own model, not the PC (owner, 2026-11-19). */
const val EYE_PHONE_CHAT = "Phone-only chat"

/** The short name of a Whisper with internet (Whisper online, through Tor). */
const val WHISPER_O = "Whisper-O"

/** What each row is, on a long press (and in Settings › Help › The eye). */
const val EYE_CHATS_ABOUT = "Your earlier conversations"
const val EYE_NEW_CHAT_ABOUT = "Normal mode: kept, with lookups"
const val EYE_NEW_WHISPER_ABOUT = "Private and offline, nothing kept"
const val EYE_NEW_WEB_WHISPER_ABOUT = "Whisper online: lookups by a hidden route, slower, nothing kept"
const val EYE_PHONE_CHAT_ABOUT = "A new chat that runs on this phone's own model, even with your PC there"
/** Why "Phone-only chat" is greyed: no model downloaded yet (a tap opens Settings › Who answers), or none can run here. */
const val EYE_PHONE_NEEDS_MODEL = "No model yet · Get one"
const val EYE_PHONE_CANT = "Can't run a model here"

/**
 * The eye menu's "Phone-only chat" row (paired only). [off] says why it can't start one (null:
 * it can); [current]: the open chat runs on this phone. [getModel] opens Settings › Who answers.
 */
class PhoneChatRow(val current: Boolean, val off: String?, val go: () -> Unit, val getModel: () -> Unit)

/**
 * The eye with its menu: [whisper] says the chat's mode (the eye shows it, and the menu marks it).
 * Each choice is handed back; switching mode starts the new chat in that mode.
 */
@Composable
fun EyeMenu(
    whisper: Boolean, label: String,
    onChats: () -> Unit, onNewChat: () -> Unit, onNewWhisper: () -> Unit,
    enabled: Boolean = true,
    /** "New Whisper-O": a Whisper online, its lookups through Tor (null: not offered here). */
    onNewWebWhisper: (() -> Unit)? = null,
    /** The chat is a Whisper with internet. */
    web: Boolean = false,
    /** Instead of the menu, the eye does this (a Whisper running on the PC: it opens it). */
    instead: (() -> Unit)? = null,
    /** "Phone-only chat" (null: not offered, e.g. not paired). */
    phoneChat: PhoneChatRow? = null,
    /** In a Whisper: "End Whisper" at the top, one tap and it's gone (null: not offered here). */
    onEndWhisper: (() -> Unit)? = null,
    /** In a Whisper: a long press on the eye, the emergency exit (null: none here). */
    onEmergency: (() -> Unit)? = null,
) {
    val startOpen = LocalEyeMenuOpen.current
    var open by remember { mutableStateOf(startOpen) }
    Box {
        androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            if (whisper && web) ViaTorChip()
            WhisperButton(whisper, { if (instead != null) instead() else if (enabled) open = true }, label = label, web = web, onHold = onEmergency)
        }
        // Compact (owner, 2026-11-19: "less cluttered"): one short line per row, as wide as its words;
        // what each row is shows on a long press and in Settings › Help › The eye.
        DropdownMenu(open, { open = false }, containerColor = Palette.Card) {
            EyeMenuRows(
                whisper, web,
                onChats = { open = false; onChats() }, onNewChat = { open = false; onNewChat() },
                onNewWhisper = { open = false; onNewWhisper() },
                onNewWebWhisper = onNewWebWhisper?.let { go -> { open = false; go() } },
                phoneChat = phoneChat?.let { r -> PhoneChatRow(r.current, r.off, { open = false; r.go() }, { open = false; r.getModel() }) },
                onEndWhisper = onEndWhisper?.let { go -> { open = false; go() } },
            )
        }
    }
}

/** The eye menu's rows: Chat History, a thin line, then the three modes (the current one checked). */
@Composable
fun EyeMenuRows(
    whisper: Boolean, web: Boolean,
    onChats: () -> Unit, onNewChat: () -> Unit, onNewWhisper: () -> Unit, onNewWebWhisper: (() -> Unit)?,
    phoneChat: PhoneChatRow? = null,
    onEndWhisper: (() -> Unit)? = null,
) {
    if (whisper && onEndWhisper != null) {
        EndWhisperRow(if (web) END_WEB_WHISPER_ROW else END_WHISPER_ROW, onEndWhisper)
        androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 4.dp), thickness = 1.dp, color = Palette.Line)
    }
    EyeChoice(EYE_CHATS, EYE_CHATS_ABOUT, null, null, onChats)
    androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 4.dp), thickness = 1.dp, color = Palette.Line)
    EyeChoice(EYE_NEW_CHAT, EYE_NEW_CHAT_ABOUT, EyeMode.Normal, !whisper && phoneChat?.current != true, onNewChat)
    // Paired: the clear way to the phone's own model. Greyed, with why and a way to a model, when it can't.
    if (phoneChat != null) {
        val off = phoneChat.off
        EyeChoice(EYE_PHONE_CHAT, EYE_PHONE_CHAT_ABOUT, null, !whisper && phoneChat.current, if (off == null) phoneChat.go else phoneChat.getModel, icon = Glyphs.Phone, dim = off != null)
        if (off != null) EyeReason(off, phoneChat.getModel)
    }
    EyeChoice(EYE_NEW_WHISPER, EYE_NEW_WHISPER_ABOUT, EyeMode.Whisper, whisper && !web, onNewWhisper)
    if (onNewWebWhisper != null) EyeChoice(EYE_NEW_WEB_WHISPER, EYE_NEW_WEB_WHISPER_ABOUT, EyeMode.WhisperWeb, whisper && web, onNewWebWhisper)
}

/**
 * One line of the eye's menu: its icon (all the same size), a short label that never wraps, and a
 * small check on the chat's mode now ([current]; null: not a mode). A long press says what it is.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun EyeChoice(
    title: String, about: String, eye: EyeMode?, current: Boolean?, onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector = Glyphs.Chat, dim: Boolean = false,
) {
    androidx.compose.material3.TooltipBox(
        positionProvider = androidx.compose.material3.TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(about) } },
        state = androidx.compose.material3.rememberTooltipState(),
    ) {
        // A row of our own (not DropdownMenuItem): it measures its words in full, so the menu is
        // exactly as wide as its longest label and nothing is cut off.
        androidx.compose.foundation.layout.Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
                .padding(start = 12.dp, end = 14.dp)
                .semantics(mergeDescendants = true) {
                    if (current == true) stateDescription = "Current mode"
                    if (dim) stateDescription = "Not available yet"
                    contentDescription = "$title. $about"
                },
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(EYE_ROW_ICON), contentAlignment = androidx.compose.ui.Alignment.Center) {
                if (eye == null) Icon(icon, null, tint = if (dim) Palette.Outline else Palette.TextSoft, modifier = Modifier.size(20.dp))
                else MimirEye(eye, Modifier.size(EYE_ROW_ICON))
            }
            Text(title, style = Type.BodyTight, color = if (dim) Palette.Outline else Palette.Text, maxLines = 1, softWrap = false, modifier = Modifier.weight(1f))
            // The check's place is kept on every row, so the rows line up.
            Box(Modifier.size(16.dp)) {
                if (current == true) Icon(Glyphs.Check, null, tint = Palette.Green, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** The first row of the menu in a Whisper: a clear red "End Whisper". */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun EndWhisperRow(title: String, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .padding(start = 12.dp, end = 14.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$title. $END_WHISPER_ABOUT" },
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(EYE_ROW_ICON), contentAlignment = androidx.compose.ui.Alignment.Center) {
            Icon(Glyphs.Close, null, tint = Palette.Red, modifier = Modifier.size(20.dp))
        }
        Text(title, style = Type.BodyTight.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = Palette.Red, maxLines = 1, softWrap = false, modifier = Modifier.weight(1f))
        Box(Modifier.size(16.dp))
    }
}

/** Under a greyed row: one short line saying why, a tap opens Settings › Who answers. */
@Composable
private fun EyeReason(words: String, onClick: () -> Unit) {
    // "Get one" (after the dot) shows as a link.
    val shown = androidx.compose.ui.text.buildAnnotatedString {
        append(words.substringBefore(" · "))
        if (" · " in words) {
            append(" · ")
            pushStyle(androidx.compose.ui.text.SpanStyle(color = Palette.BlueLight)); append(words.substringAfter(" · ")); pop()
        }
    }
    Text(
        shown, style = Type.Small, color = Palette.TextDim, maxLines = 1, softWrap = false,
        modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp)
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .padding(start = 12.dp, end = 14.dp)
            .wrapContentHeight(androidx.compose.ui.Alignment.CenterVertically),
    )
}

/** The icon box on every row of the eye's menu. */
private val EYE_ROW_ICON = 26.dp

/**
 * A swipe in from the left edge of a chat opens Chats (a common Android pattern). The edge's middle
 * is kept from the system's Back swipe there, as a side drawer does.
 */
fun Modifier.swipeForChats(open: () -> Unit): Modifier = composed {
    val d = LocalDensity.current
    val edge = with(d) { 28.dp.toPx() }
    val far = with(d) { 64.dp.toPx() }
    val band = with(d) { 100.dp.toPx() }
    this.systemGestureExclusion { c: androidx.compose.ui.layout.LayoutCoordinates -> Rect(0f, c.size.height / 2f - band, edge, c.size.height / 2f + band) }
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (down.position.x > edge) return@awaitEachGesture
                while (true) {
                    val e = awaitPointerEvent(PointerEventPass.Initial)
                    val c = e.changes.firstOrNull { it.id == down.id } ?: break
                    if (!c.pressed) break
                    val dx = c.position.x - down.position.x
                    val dy = c.position.y - down.position.y
                    if (dx > far && dx > 2 * abs(dy)) { c.consume(); open(); break }
                    if (abs(dy) > far || dx < -far) break
                }
            }
        }
}

/** One conversation in Chats. */
private data class Conversation(val key: String, val id: String, val title: String, val at: Long, val messages: Int, val words: String, val mimir: Boolean, val open: Boolean, val ran: String? = null)

/**
 * Chats: every kept conversation, newest first, Mímir's on this phone and (paired) those with the
 * PC, each labelled; search, rename and delete (asked first, with Undo). A Whisper is never kept,
 * so never listed.
 */
@Composable
fun ChatsScreen(state: StationState, station: Station, engine: Engine?, nav: Navigator) {
    val local = engine?.state?.collectAsStateWithLifecycle()?.value
    var query by rememberSaveable { mutableStateOf("") }
    var asking by remember { mutableStateOf<Conversation?>(null) }
    var undo by remember { mutableStateOf<(() -> Unit)?>(null) }
    val now = System.currentTimeMillis()
    // One chat (Stage 1, 2.5): every chat is the phone's own, with the PC's history synced in.
    val one = !state.paired || state.pcTalks
    val all = buildList {
        if (local != null) {
            if (!local.whisper && local.chat.turns.isNotEmpty()) add(local.chat.let { Conversation("m-${it.id}", it.id, it.title, it.at, it.turns.size, it.turns.joinToString(" ") { t -> t.text }, mimir = true, open = true, ran = it.lastRan) })
            local.earlier.forEach { c -> add(Conversation("m-${c.id}", c.id, c.title, c.at, c.turns.size, c.turns.joinToString(" ") { it.text }, mimir = true, open = false, ran = c.lastRan)) }
        }
        if (state.paired && !one) {
            val open = state.chat.filter { it is io.github.odnwks.mimir.data.ChatEntry.Mine || it is io.github.odnwks.mimir.data.ChatEntry.Reply }
            if (open.isNotEmpty()) add(Conversation(
                "p-open", "", open.firstNotNullOfOrNull { (it as? io.github.odnwks.mimir.data.ChatEntry.Mine)?.text?.let(io.github.odnwks.mimir.standalone.LocalChat::topicOf) } ?: "Chat",
                state.chatAt, open.size, "", mimir = false, open = true,
            ))
            state.pastChats.forEach { p -> add(Conversation("p-${p.id}", p.id, p.title, p.at, p.messages, p.words, mimir = false, open = false)) }
        }
    }.sortedByDescending { if (it.open && it.at <= 0) now else it.at }
    val q = query.trim().lowercase()
    val shown = if (q.isEmpty()) all else all.filter { q in it.title.lowercase() || q in it.words.lowercase() }

    fun goTo(screen: Screen) {
        val below = nav.stack.dropLast(1).lastOrNull()?.screen
        when {
            below == screen -> nav.back()
            screen == Screen.Chat -> nav.selectTab(Tab.Chat)
            else -> nav.replace(screen)
        }
    }
    fun openIt(c: Conversation) {
        if (c.mimir) {
            if (!c.open) engine?.open(c.id)
            goTo(if (state.paired && !one) Screen.Standalone else Screen.Chat)
        } else {
            if (!c.open) station.openPastChat(c.id)
            goTo(Screen.Chat)
        }
    }
    Column(Modifier.fillMaxSize()) {
        BackHeader(EYE_CHATS, nav::back)
        MessageBox(query, { query = it }, "Search chats", "Search chats", Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
        UndoBar(undo, onUndo = { undo?.invoke(); undo = null }, onGone = { undo = null; engine?.forgetDeleted() })
        // Once, after the first sync: the chats that were with the PC are here now.
        if (one && local?.movedNote == true) {
            Text("Your earlier PC chats are here now.", style = Type.Small, color = Palette.TextSoft, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            androidx.compose.runtime.DisposableEffect(Unit) { onDispose { engine?.movedNoteSeen() } }
        }
        if (engine != null) ForgetDeletedChatsWhenLeaving(engine)
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (shown.isEmpty()) item(key = "none") {
                Text(
                    if (q.isEmpty()) "No earlier chats yet. A new chat from the eye keeps the one before here." else "No chat has “${query.trim()}”.",
                    style = Type.Label, color = Palette.TextSoft, modifier = Modifier.padding(top = 16.dp),
                )
            }
            items(shown, key = { it.key }) { c ->
                val sub = listOfNotNull(
                    // Each row says where it last ran.
                    if (one && state.paired) when (c.ran) { "pc" -> state.pc.name; "paid" -> "Online"; "phone" -> "This phone"; else -> AI_NAME }
                    else if (c.mimir) AI_NAME else "With your PC",
                    "open now".takeIf { c.open },
                    messagesWords(c.messages),
                    seenWords(c.at, now).ifEmpty { null },
                ).joinToString(" · ")
                ChatRow(
                    c.key, c.title, sub, { openIt(c) }, { asking = c },
                    { name -> if (c.mimir) engine?.rename(name, c.id) else if (!c.open) station.renamePastChat(c.id, name) },
                    Modifier.animateItem(fadeInSpec = motionSpec(Motion.NEW_ITEM_MS), placementSpec = motionSpec(Motion.NEW_ITEM_MS), fadeOutSpec = motionSpec(Motion.NEW_ITEM_MS)),
                )
            }
        }
    }
    asking?.let { c ->
        DeleteChatQuestion(c.title, onCancel = { asking = null }) {
            asking = null
            if (c.mimir) engine?.deleteChat(c.id)?.let { d -> undo = { engine.restore(d) } }
            // The open one goes to the list (as New chat does), then from it.
            else if (c.open) { station.newChat(); deleteNewest(station)?.let { k -> undo = { station.restorePastChat(k) } } }
            else station.deletePastChat(c.id)?.let { k -> undo = { station.restorePastChat(k) } }
        }
    }
}

/** The open conversation just put in the list by New chat, deleted from it. */
private fun deleteNewest(station: Station) = station.state.value.pastChats.firstOrNull()?.let { station.deletePastChat(it.id) }
