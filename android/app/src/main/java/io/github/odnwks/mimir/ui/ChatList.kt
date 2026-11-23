package io.github.odnwks.mimir.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import io.github.odnwks.mimir.ui.theme.Motion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.odnwks.mimir.standalone.Deleted
import io.github.odnwks.mimir.standalone.LocalChat
import io.github.odnwks.mimir.ui.theme.LocalReduceMotion
import io.github.odnwks.mimir.ui.theme.Palette
import io.github.odnwks.mimir.ui.theme.Type
import kotlinx.coroutines.delay

/**
 * One earlier chat in the list: tap opens it; pulling it left only uncovers a red Delete button
 * (nothing is deleted by the swipe itself); a long press gives Rename and Delete (TalkBack has
 * both as actions). Every Delete asks first ([DeleteChatQuestion]).
 */
@Composable
fun ChatRow(chat: LocalChat, onOpen: () -> Unit, onDelete: () -> Unit, onRename: (String) -> Unit, modifier: Modifier = Modifier) =
    ChatRow(chat.id, chat.title, messagesWords(chat.turns.size), onOpen, onDelete, onRename, modifier)

/** "1 message", "5 messages". */
fun messagesWords(n: Int) = if (n == 1) "1 message" else "$n messages"

/** One conversation in a list: its [title] and a small line under it ([sub]). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatRow(id: String, title: String, sub: String, onOpen: () -> Unit, onDelete: () -> Unit, onRename: (String) -> Unit, modifier: Modifier = Modifier) {
    var menu by rememberSaveable(id) { mutableStateOf(false) }
    var renaming by rememberSaveable(id) { mutableStateOf(false) }
    val reduce = LocalReduceMotion.current
    val revealDp = 104.dp
    val reveal = with(LocalDensity.current) { revealDp.toPx() }
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val settle = { to: Float -> scope.launch { if (reduce) offset.snapTo(to) else offset.animateTo(to, tween(Motion.CARD_OPEN_MS, easing = Motion.Ease)) } }
    if (renaming) TopicEditor(title, onCancel = { renaming = false }) { onRename(it); renaming = false }
    Box(modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        // Behind the row, uncovered by pulling it left: the red Delete button (it asks first).
        if (offset.value < -1f) Box(Modifier.matchParentSize(), contentAlignment = Alignment.CenterEnd) {
            Box(
                Modifier.width(revealDp - 8.dp).fillMaxHeight().clip(RoundedCornerShape(16.dp)).background(Palette.RedBorder)
                    .clickable(role = Role.Button, onClickLabel = null) { settle(0f); onDelete() }
                    .semantics { contentDescription = "Delete $title" },
                contentAlignment = Alignment.Center,
            ) { Text("Delete", style = Type.BodyTight.copy(fontWeight = FontWeight.SemiBold), color = Palette.Text, modifier = Modifier.clearAndSetSemantics {}) }
        }
        // Pulled left, the row narrows from its right end instead of sliding away, so its name
        // stays readable beside the Delete button (the name is cut short with "…" if it must be).
        val density = LocalDensity.current
        Box(
            Modifier.padding(end = with(density) { (-offset.value).toDp() })
                .draggable(
                    rememberDraggableState { d -> scope.launch { offset.snapTo((offset.value + d).coerceIn(-reveal, 0f)) } },
                    Orientation.Horizontal,
                    onDragStopped = { v -> settle(if (offset.value < -reveal / 2 || v < -800f) -reveal else 0f) },
                ),
        ) {
            Column(
                Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(16.dp)).background(Palette.Card)
                    .border(1.dp, Palette.Line, RoundedCornerShape(16.dp))
                    .combinedClickable(
                        role = Role.Button, onLongClickLabel = "Rename or delete", onLongClick = { menu = true },
                        onClick = { if (offset.value < -1f) settle(0f) else onOpen() },
                    )
                    .semantics {
                        customActions = listOf(
                            CustomAccessibilityAction("Rename") { renaming = true; true },
                            CustomAccessibilityAction("Delete") { onDelete(); true },
                        )
                    }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
            ) {
                Text(title, style = Type.BodyTight.copy(fontWeight = FontWeight.SemiBold), color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(sub, style = Type.Small, color = Palette.TextDim)
            }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text("Rename") }, { menu = false; renaming = true })
                DropdownMenuItem({ Text("Delete", color = Palette.Red) }, { menu = false; onDelete() })
            }
        }
    }
}

/** The one question before a chat is deleted. Cancel is the safe default. */
@Composable
fun DeleteChatQuestion(chat: LocalChat?, onCancel: () -> Unit, onDelete: (LocalChat) -> Unit) {
    if (chat == null) return
    DeleteChatQuestion(chat.title, onCancel) { onDelete(chat) }
}

/** The one question before the conversation named [title] is deleted. Cancel is the safe default. */
@Composable
fun DeleteChatQuestion(title: String, onCancel: () -> Unit, onDelete: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = Palette.Card,
        title = { Text("Delete \u201c$title\u201d?", style = Type.Section, color = Palette.Text) },
        // Undo shows for a few seconds after, so the question says so.
        text = { Text(UNDO_LINE, style = Type.BodyTight, color = Palette.TextSoft) },
        dismissButton = { SmallButton("Delete", onDelete, description = "Delete $title") },
        confirmButton = { BigButton("Cancel", Look.Primary, onCancel, height = 48.dp) },
    )
}

/** Chats deleted moments ago leave the phone's storage when this screen goes, or the app goes to the background. */
@Composable
fun ForgetDeletedChatsWhenLeaving(engine: io.github.odnwks.mimir.standalone.Engine) {
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(engine, owner) {
        val watch = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP) engine.forgetDeleted() }
        owner.lifecycle.addObserver(watch)
        onDispose { owner.lifecycle.removeObserver(watch); engine.forgetDeleted() }
    }
}

/** "Chat deleted · Undo", for a few seconds after a delete. */
@Composable
fun UndoBar(deleted: Deleted?, onUndo: () -> Unit, onGone: () -> Unit) = UndoBar(deleted as Any?, onUndo, onGone)

/** "Chat deleted · Undo" while [deleted] (anything deleted moments ago) isn't null. */
@Composable
fun UndoBar(deleted: Any?, onUndo: () -> Unit, onGone: () -> Unit, words: String = "Chat deleted", forMs: Long = UNDO_MS) {
    val reduce = LocalReduceMotion.current
    LaunchedEffect(deleted) { if (deleted != null) { delay(forMs); onGone() } }
    AnimatedVisibility(
        deleted != null,
        enter = if (reduce) EnterTransition.None else fadeIn() + expandVertically(),
        exit = if (reduce) ExitTransition.None else fadeOut() + shrinkVertically(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).clip(RoundedCornerShape(12.dp)).background(Palette.Bar)
                .padding(start = 16.dp, end = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(words, style = Type.BodyTight, color = Palette.Text, modifier = Modifier.weight(1f))
            LinkText("Undo", onUndo, bold = true)
        }
    }
}

// PHU-25: 8-second undo (was 6).
const val UNDO_MS = 8_000L
/** An edit's "Later messages removed · Undo" stays this long (and while the chat is open): a slower hand still finds it. */
const val EDIT_UNDO_MS = 30_000L
const val UNDO_LINE = "You can undo for a few seconds."
