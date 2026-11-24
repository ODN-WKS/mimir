package io.github.odnwks.mimir.ui

import io.github.odnwks.mimir.ui.adaptive.forDevice
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.odnwks.mimir.ui.theme.LocalReduceMotion
import io.github.odnwks.mimir.ui.theme.Palette
import io.github.odnwks.mimir.ui.theme.Type

/** The private AI on this phone has a name (owner's pick): Mímir, the well of knowledge. */
const val AI_NAME = "Mímir"

/** The privacy line, shown once on a new chat and always in the chat's topic sheet. */
const val PRIVACY_LINE = "Private: $AI_NAME runs on this phone and your chats never leave it. When it looks something up for you (like the weather), only the words looked up are sent."

/** The same, with "Mímir may look things up online" off. */
const val PRIVACY_LINE_OFFLINE = "Private: $AI_NAME runs on this phone. Nothing you type leaves it, and it works without internet."

/** The same, while the paired PC answers the chat (Stage 1): it never says the chat stays on the phone. */
const val PRIVACY_LINE_PC = "Private: answered by your PC, and nothing goes to any company. When $AI_NAME looks something up (like the weather), only the words looked up are sent."

/** The privacy line as things are now; [pc]: the paired PC answers this chat. */
fun privacyLine(pc: Boolean = false): String = when {
    pc -> PRIVACY_LINE_PC
    io.github.odnwks.mimir.online.Online.settings.value.lookups -> PRIVACY_LINE
    else -> PRIVACY_LINE_OFFLINE
}

/**
 * The chat's slim top bar: never the app's name. The chat's topic (once it has a first message)
 * with a small down-chevron: a tap opens everything for this chat. Then Whisper, New chat, Settings.
 */
@Composable
fun SlimChatHeader(
    topic: String?, onTopic: (() -> Unit)?, onSettings: () -> Unit,
    /** The eye: the chat's mode, and its menu (Chats, New chat, New Whisper). */
    whisper: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 8.dp, end = 8.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            if (onTopic != null) TopicButton(topic, CHAT_OPTIONS, onTopic, Type.BodyTight.copy(fontWeight = FontWeight.SemiBold))
            else if (topic != null) Text(topic, style = Type.BodyTight.copy(fontWeight = FontWeight.SemiBold), color = Palette.Text, maxLines = 1, modifier = Modifier.padding(horizontal = 12.dp).semantics { heading() })
        }
        // The eye always sits right beside Settings, and nothing else shares the bar, so it never moves.
        whisper?.invoke()
        IconTarget(Glyphs.Settings, "Settings", onSettings)
    }
}

/** What the topic's chevron says to TalkBack (and to anyone looking for the chat's options). */
const val CHAT_OPTIONS = "Chat options"
const val TASK_OPTIONS = "Task options"

/**
 * A chat's or task's name with a small down-chevron: one tap opens everything for it (rename,
 * delete, read-aloud speed, Whisper, privacy). With no name yet, the chevron alone.
 */
@Composable
fun TopicButton(text: String?, label: String, onClick: () -> Unit, style: androidx.compose.ui.text.TextStyle, color: androidx.compose.ui.graphics.Color = Palette.Text, maxLines: Int = 1, modifier: Modifier = Modifier, start: androidx.compose.ui.unit.Dp = 12.dp) {
    Row(
        // P13: a small corner and some air above and below, so the first letter of a last line (a name of
        // three lines starts at the very edge) is never cut by the rounded corner.
        modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .padding(start = start, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (text != null) Text(
            text, style = style, color = color, maxLines = maxLines,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).semantics { heading() },
        )
        Icon(Glyphs.Down, label, tint = Palette.TextDim, modifier = Modifier.size(18.dp))
    }
}

/** Renaming a chat: a small sheet with the name filled in; Save keeps it (empty: the name made from the first words). */
@Composable
fun TopicEditor(current: String, onCancel: () -> Unit = {}, onDone: (String) -> Unit) {
    var text by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(current) }
    ChatSheet("Rename this chat", onCancel) {
        MessageBox(text, { text = it }, "Chat name", "Chat name", Modifier.fillMaxWidth(), autoFocus = true)
        BigButton("Done", Look.Primary, { onDone(text) }, height = 48.dp)
        BigButton("Cancel", Look.Outline, onCancel, height = 48.dp)
    }
}

/** A slim top bar for a conversation: a small name (with an optional dot) and its icons. */
@Composable
fun SlimTopBar(title: String, dot: (@Composable () -> Unit)? = null, line: String? = null, actions: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // No words for the screen's name (TalkBack still hears it); only the PC's dot.
            Box(Modifier.semantics { heading(); contentDescription = title }) { dot?.invoke() }
            // Only while it matters (the PC away): one small line beside the name.
            if (line != null) Text(line, style = Type.Small, color = Palette.TextDim, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        actions()
    }
}

/** How many lines the privacy note shows at a large text size (a tap shows the rest). */
const val PRIVACY_NOTE_BIG_LINES = 3

/** The lines the private note may take: all of them at a normal text size, three at a large one until it is tapped. */
fun privacyNoteLines(fontScale: Float, expanded: Boolean): Int = if (fontScale > 1.15f && !expanded) PRIVACY_NOTE_BIG_LINES else Int.MAX_VALUE

/** The one-time privacy note on a new chat: small, with OK, gone once dismissed (the topic's sheet has it after). */
@Composable
fun PrivacyNote(visible: Boolean, onDismiss: () -> Unit, pc: Boolean = false) {
    val reduce = LocalReduceMotion.current
    AnimatedVisibility(
        visible,
        enter = if (reduce) EnterTransition.None else fadeIn() + expandVertically(),
        exit = if (reduce) ExitTransition.None else fadeOut() + shrinkVertically(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 4.dp)
                .clip(RoundedCornerShape(12.dp)).border(1.dp, Palette.Line, RoundedCornerShape(12.dp))
                .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Glyphs.Lock, null, tint = Palette.TextDim, modifier = Modifier.size(16.dp))
            // At a large text size the long line would become a tall column: it shows three lines, and a tap shows the rest.
            val scale = androidx.compose.ui.platform.LocalDensity.current.fontScale
            var all by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
            val big = privacyNoteLines(scale, false) != Int.MAX_VALUE
            Text(
                privacyLine(pc).forDevice(), style = Type.Small, color = Palette.TextSoft,
                maxLines = privacyNoteLines(scale, all),
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
                    .then(if (big && !all) Modifier.clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = "Show all of it") { all = true } else Modifier)
                    .padding(vertical = 8.dp),
            )
            LinkText("OK", onDismiss)
        }
    }
}
