package io.github.odnwks.mimir.ui

import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.odnwks.mimir.online.Source
import io.github.odnwks.mimir.ui.theme.ChatText
import io.github.odnwks.mimir.ui.theme.Palette
import io.github.odnwks.mimir.ui.theme.Type

/**
 * How a chat looks (the redesign, screen 4): the owner's words in a blue bubble on the right, the
 * answer as plain text across the whole lane with quiet buttons under it. Phone chat and PC chat
 * both use these, so they look the same.
 */
object ChatLook {
    val OwnBubble = Color(0xFF22355C)
    val ButtonBorder = Color(0xFF2B2F37)
    val LookedUpFill = Color(0xFF1A1D23)
    val ownShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = 6.dp, bottomStart = 20.dp)
    /** An answer's bubble: the owner's mirrored, its small corner bottom left. */
    val replyShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = 20.dp, bottomStart = 6.dp)
    val ReplyMaxWidth = 358.dp
    /**
     * The small Listen pill under a reply (P15, mock-up 6 option A; P18, owner 2026-11-25, mock-up 11): about 18 dp
     * high with 11 sp words, the size of the model, effort and time beside it; its touch area is still 48 dp.
     */
    val PillHeight = 18.dp
    val pillShape = RoundedCornerShape(9.dp)
    val PillText = TextStyle(fontSize = 11.sp, lineHeight = 14.sp)
    val MessageText = TextStyle(fontSize = 15.5.sp, lineHeight = 23.25.sp)
}

/** The words on the Listen pill under an answer, and in the long-press menu. */
const val LISTEN = "Listen"
const val STOP_LISTENING = "Stop"
const val COPY = "Copy"
const val TRY_AGAIN = "Try again"
const val SELECT_TEXT = "Select text"
const val SHARE = "Share"
const val LISTEN_ITEM = "Listen"
const val LOOKED_UP_TAG = "Sources:"
const val EDIT = "Edit"
/** In the bar over selected words: opens the whole message's actions. */
const val MORE_ITEM = "More"
const val YOU_STOPPED = "You stopped this answer."

/** What the chat says under a message that got no answer: the owner's own Stop, or a real interruption. */
fun noAnswerWords(stopped: Boolean, pc: Boolean): String = when {
    stopped -> YOU_STOPPED
    pc -> "Your PC didn't finish that answer. Try again."
    else -> "That answer didn't finish. Try again."
}

/** The line under a message that got no answer: the PC's own plain reason when it gave one, else the general words. */
fun interruptedWords(stopped: Boolean, pc: Boolean, why: String?): String =
    if (stopped || why.isNullOrBlank()) noAnswerWords(stopped, pc) else why

/** The owner's message: a right-aligned bubble, no "You" above it. [under] is what became of it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OwnMessage(
    text: String,
    modifier: Modifier = Modifier,
    bubble: Color = ChatLook.OwnBubble,
    dashed: Boolean = false,
    id: String = "mine-${text.hashCode()}",
    under: (@Composable () -> Unit)? = null,
    /** The small print right under the bubble (P11): the model and effort it went to, the time and the receipt circle. */
    meta: (@Composable () -> Unit)? = null,
    /** Edit and send again from here (null: this message can't be edited now). Gets the new words. */
    onEdit: ((String) -> Unit)? = null,
    /** A line under the edit box (what sending it will do). */
    editNote: String? = null,
    /** The reason the last edit was not sent: the box opens again with the words kept, and says why. */
    editFailure: String? = null,
    onEditCancel: () -> Unit = {},
) {
    var editing by rememberSaveable(id) { mutableStateOf(false) }
    var draft by rememberSaveable(id) { mutableStateOf(text) }
    LaunchedEffect(editFailure) { if (editFailure != null) editing = true }
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
      // The small print sits snug under the bubble's corner (P11, mock-up 5).
      Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (editing && onEdit != null) EditBox(
            draft, { draft = it }, bubble,
            onCancel = { editing = false; draft = text; onEditCancel() },
            onSend = { editing = false; onEdit(draft) },
            note = editFailure ?: editNote,
        ) else MessageMenuHost(plain = text, id = id, lookUp = null, onEdit = onEdit?.let { { draft = text; editing = true } }) { onHold ->
            ChatText {
                BubbleWords(
                    Modifier.widthIn(max = 320.dp).clip(ChatLook.ownShape).background(bubble)
                        .then(if (dashed) Modifier.dashedBorder(Palette.BlueLight, 20.dp) else Modifier),
                    PaddingValues(horizontal = 14.dp, vertical = 11.dp), onHold,
                ) { Text(text, style = ChatLook.MessageText, color = Palette.Text) }
            }
        }
        if (meta != null && !editing) Box(Modifier.padding(end = 6.dp)) { meta() }
      }
        if (under != null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                under()
            }
        }
    }
}

/** Editing one of the owner's messages in its own place: a box in the bubble's colour, with Cancel and Send. */
@Composable
fun EditBox(value: String, onChange: (String) -> Unit, bubble: Color, onCancel: () -> Unit, onSend: () -> Unit, note: String? = null) {
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    val hold = LocalKeyboardHold.current
    LaunchedEffect(Unit) { if (hold?.held != true) runCatching { focus.requestFocus() } }
    // QA2 #29: the box stays in view above the keyboard: when the keyboard opens or the text grows, scroll it in.
    val inView = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    val keyboard = androidx.compose.foundation.layout.WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current)
    LaunchedEffect(keyboard, value.length / 40) {
        // Let the list take its new height first.
        kotlinx.coroutines.delay(60)
        runCatching { inView.bringIntoView() }
    }
    Column(
        Modifier.fillMaxWidth().bringIntoViewRequester(inView).clip(ChatLook.ownShape).background(bubble).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // The caret starts at the END of the words, where a person goes on writing (it kept the start before).
        var field by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(value, androidx.compose.ui.text.TextRange(value.length))) }
        if (field.text != value) field = androidx.compose.ui.text.input.TextFieldValue(value, androidx.compose.ui.text.TextRange(value.length))
        androidx.compose.foundation.text.BasicTextField(
            field, { next -> field = next; if (next.text != value) onChange(next.text) }, textStyle = ChatLook.MessageText.copy(color = Palette.Text),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Palette.Text),
            modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("edit-box"),
        )
        if (note != null) Text(note, style = Type.Small, color = Palette.TextSoft, modifier = Modifier.testTag("edit-note"))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            QuietButton("Cancel", "Cancel editing", onClick = onCancel)
            Box(
                Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp)).background(Palette.Blue.copy(alpha = if (value.isNotBlank()) 1f else 0.4f))
                    .clickable(enabled = value.isNotBlank(), role = Role.Button, onClick = onSend)
                    .semantics { contentDescription = "Send the edited message" }
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center,
            ) { Text("Send", style = TextStyle(fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.Bold), color = Palette.White) }
        }
    }
}

/**
 * An answer: its words in a grey bubble on the left, the mirror of the owner's blue one (P13), then,
 * once it is written, the "Looked up" tag when the internet was used and one short row: a small Listen
 * pill, then the small print (P15). Copy, Share, Try again and the rest are in the long-press menu.
 * [dim]: still being written (in its bubble, no pill). [pc]: Listen plays the PC's voice instead.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Answer(
    plain: String,
    id: String,
    modifier: Modifier = Modifier,
    dim: Boolean = false,
    sources: List<Source> = emptyList(),
    openSources: Boolean = true,
    lookUp: (() -> Unit)? = null,
    onTryAgain: (() -> Unit)? = null,
    pc: (() -> Unit)? = null,
    /** Who answered and why, when it was not the usual model: one dim line (null: none). */
    note: String? = null,
    /** False while the answer is still being written: no Listen under half an answer (R20). */
    buttons: Boolean = true,
    /** Who wrote it, one dim line under the answer (null: not shown). */
    author: String? = null,
    /** The small print beside the Listen pill (P11, P15): the model, effort and time. */
    meta: (@Composable () -> Unit)? = null,
    /** A line under that row: why the model changed (P11), when it did. */
    metaUnder: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        MessageMenuHost(plain = plain, id = id, lookUp = lookUp.takeIf { !dim }, pc = pc, enabled = !dim && buttons, onTryAgain = onTryAgain) { onHold ->
            ChatText {
                BubbleWords(
                    Modifier.widthIn(max = ChatLook.ReplyMaxWidth).clip(ChatLook.replyShape).background(Palette.Card)
                        .border(1.dp, Palette.Line, ChatLook.replyShape)
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .testTag(REPLY_BUBBLE_TAG),
                    // While the answer is still being written its words can be selected, and there is no "More".
                    PaddingValues(horizontal = 14.dp, vertical = 11.dp), onHold.takeIf { !dim }, offerMore = buttons,
                ) { Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = content) }
            }
        }
        if (!dim && buttons) {
            // Who wrote it, the sources and a switch note keep their 8 dp from the bubble and from each other.
            if (!author.isNullOrBlank() || sources.isNotEmpty() || !note.isNullOrBlank()) Column(Modifier.padding(top = 3.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!author.isNullOrBlank()) Text(author, style = Type.Small, color = Palette.TextDim, modifier = Modifier.testTag("answer-author"))
                if (sources.isNotEmpty()) SourcesLine(sources, openSources)
                if (!note.isNullOrBlank()) Text(note, style = Type.Small, color = Palette.TextDim, modifier = Modifier.testTag("switch-note"))
            }
            // One short row, snug under the bubble (P15, the owner's pick): the Listen pill, then the model,
            // effort and time. With a narrow screen or large text the small print goes under the pill, whole.
            // Copy and Share are in the long-press menu only, with Select text, Try again and Look it up.
            Column(Modifier.padding(start = 2.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    ListenButton(id, plain, pc)
                    meta?.invoke()
                }
                metaUnder?.invoke()
            }
        }
    }
}

/** The test tag on an answer's grey bubble. */
const val REPLY_BUBBLE_TAG = "reply-bubble"

/** A quiet outlined text button: #2b2f37 border, 13sp words, an optional small mark before them, and a full 48 dp to touch. */
@Composable
fun QuietButton(text: String, description: String = text, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.clip(RoundedCornerShape(10.dp)).border(1.dp, ChatLook.ButtonBorder, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (icon != null) androidx.compose.material3.Icon(icon, null, tint = Palette.TextSoft, modifier = Modifier.size(11.dp))
            Text(text, style = TextStyle(fontSize = 13.sp, lineHeight = 17.sp), color = Palette.TextSoft)
        }
    }
}

/** The test tag on the Listen pill under a finished answer. */
const val LISTEN_PILL_TAG = "listen-pill"

/**
 * The small "Listen" pill with a play mark ("Stop" while it reads): 18 dp high in the row, with the full
 * 48 dp to touch around it. The first read may wait for the voice to load, and says so.
 */
@Composable
fun ListenButton(id: String, plain: String, pc: (() -> Unit)? = null) {
    val voice = rememberReadAloud()
    val getting by voice.gettingReady.collectAsStateWithLifecycle()
    val on = readingNow(voice, id)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            // No 48 dp of layout here (that made the row tall): a small clickable still gets its 48 dp touch area.
            Modifier.testTag(LISTEN_PILL_TAG).heightIn(min = ChatLook.PillHeight).clip(ChatLook.pillShape)
                .clickable(role = Role.Button) { tapRead(voice, id, plain, pc) }
                .semantics { contentDescription = if (on) "Stop reading" else "Read aloud" }
                .border(1.dp, ChatLook.ButtonBorder, ChatLook.pillShape).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            androidx.compose.material3.Icon(if (on) Glyphs.Stop else Glyphs.Play, null, tint = Palette.TextSoft, modifier = Modifier.size(8.dp))
            Text(if (on) STOP_LISTENING else LISTEN, style = ChatLook.PillText, color = Palette.TextSoft)
        }
        if (on && getting == id) Text(
            "Getting ${voice.voiceName ?: "the voice"} ready…", style = Type.Small, color = Palette.TextDim,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

/** Opens the phone's share sheet with the words as plain text. */
fun sharePlain(ctx: android.content.Context, plain: String) {
    runCatching {
        ctx.startActivity(
            Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, plain), SHARE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/**
 * The words of one bubble: a long press on them selects, and the phone's bar over the selection offers
 * Copy and Select all. [more] (null: none) adds "More" there, which opens the whole message's menu
 * (the same one a long press on the bubble's edge opens).
 */
@Composable
fun SelectableWords(more: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val open by rememberUpdatedState(more)
    SelectionContainer(
        if (more == null) Modifier else Modifier.appendTextContextMenuComponents {
            separator()
            item(MoreKey, MORE_ITEM) { close(); open?.invoke() }
        },
        content,
    )
}

private object MoreKey

/**
 * One bubble: its words can be selected ([SelectableWords]), and holding the bubble's edge, around the
 * words, opens the message's menu ([onHold]; null: no menu). A screen reader's long press on the whole
 * bubble opens the menu too. A tap on the bubble does nothing and goes no further, as before.
 */
@Composable
fun BubbleWords(
    modifier: Modifier, padding: PaddingValues, onHold: (() -> Unit)?, label: String = HOLD_LABEL, offerMore: Boolean = true,
    words: @Composable () -> Unit,
) {
    val held by rememberUpdatedState(onHold)
    Box(
        modifier.then(
            if (onHold == null) Modifier
            else Modifier.semantics(mergeDescendants = true) { onLongClick(label) { held?.invoke(); true } }
                .pointerInput(Unit) { detectTapGestures(onTap = {}) },
        ),
    ) {
        // Behind the words, so a long press on the words themselves never reaches it.
        if (onHold != null) Box(Modifier.matchParentSize().holdMenu({ held?.invoke() }, label).clearAndSetSemantics {})
        Box(Modifier.padding(padding)) { SelectableWords(more = if (onHold != null && offerMore) ({ held?.invoke() }) else null, content = words) }
    }
}

/** What a screen reader says a long press on a message does. */
private const val HOLD_LABEL = "More: copy, select, share, listen or try again"

/** What a message's long press holds: Copy, Select text, Share, Listen (and Look it up on the newest answer). */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.holdMenu(open: () -> Unit, label: String = HOLD_LABEL): Modifier =
    combinedClickable(onClickLabel = null, onLongClickLabel = label, onLongClick = open, onClick = {})

/**
 * Wraps one message: [content] gets the "hold" callback, and the menu (Copy, Select text, Share,
 * Listen) opens beside it. Deleting a single message isn't supported yet, so there is no Delete.
 */
@Composable
fun MessageMenuHost(
    plain: String, id: String, lookUp: (() -> Unit)?, pc: (() -> Unit)? = null, enabled: Boolean = true,
    onEdit: (() -> Unit)? = null,
    onTryAgain: (() -> Unit)? = null,
    content: @Composable (onHold: () -> Unit) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    val voice = rememberReadAloud()
    val on = readingNow(voice, id)
    val clip = LocalClipboardManager.current
    val ctx = LocalContext.current
    val listen = { tapRead(voice, id, plain, pc) }
    // TalkBack gets the same actions as custom actions on the message.
    val actions = if (!enabled) emptyList() else buildList {
        add(CustomAccessibilityAction(COPY) { clip.setText(AnnotatedString(plain)); true })
        add(CustomAccessibilityAction(SHARE) { sharePlain(ctx, plain); true })
        add(CustomAccessibilityAction(if (on) "Stop reading" else LISTEN_ITEM) { listen(); true })
        if (onTryAgain != null) add(CustomAccessibilityAction(TRY_AGAIN) { onTryAgain(); true })
    }
    Box(Modifier.semantics { customActions = actions }) {
        content { if (enabled) menu = true }
        if (enabled) DropdownMenu(menu, { menu = false }, containerColor = Palette.Card) {
            if (onEdit != null) DropdownMenuItem({ Text(EDIT) }, { menu = false; onEdit() })
            DropdownMenuItem({ Text(COPY) }, { menu = false; clip.setText(AnnotatedString(plain)) })
            DropdownMenuItem({ Text(SELECT_TEXT) }, { menu = false; selecting = true })
            DropdownMenuItem({ Text(SHARE) }, {
                menu = false
                sharePlain(ctx, plain)
            })
            DropdownMenuItem({ Text(if (on) "Stop reading" else LISTEN_ITEM) }, {
                menu = false
                tapRead(voice, id, plain, pc)
            })
            if (onTryAgain != null) DropdownMenuItem({ Text(TRY_AGAIN) }, { menu = false; onTryAgain() })
            if (lookUp != null) DropdownMenuItem({ Text(LOOK_IT_UP) }, { menu = false; lookUp() })
        }
        if (selecting) AlertDialog(
            onDismissRequest = { selecting = false },
            confirmButton = { TextButton({ selecting = false }) { Text("Done") } },
            text = { SelectionContainer { Text(plain, style = ChatLook.MessageText) } },
        )
    }
}

/** A whole chat as plain text for the clipboard: each turn under who said it. */
fun chatAsPlainText(turns: List<Pair<Boolean, String>>, answerer: String): String =
    turns.joinToString("\n\n") { (mine, text) -> (if (mine) "You: " else "$answerer: ") + text.trim() }
