package io.github.odnwks.mimir.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.odnwks.mimir.ui.adaptive.messageBoxKeys
import io.github.odnwks.mimir.ui.theme.Motion
import io.github.odnwks.mimir.ui.theme.Palette
import io.github.odnwks.mimir.ui.theme.motionSpec
import io.github.odnwks.mimir.ui.theme.Type

/**
 * Mímir's message box: the one reply box (owner, 2026-11-25, the task screen's box). The words on
 * top (one line that grows to six), and under them one row ([ReplyRow]): + on the left, the model
 * and effort chip in the centre, then the mic and Send (dimmed until there are words) or Stop on
 * the right. The mic is in every chat, in the same place (voice typing is in the three dots too).
 */
@Composable
fun MimirComposer(
    value: String,
    onValue: (String) -> Unit,
    /** A message already waits for Mímir to be ready: the next one can't go yet. */
    waiting: Boolean,
    writing: Boolean,
    voice: VoiceInput,
    onSend: () -> Unit,
    onStop: () -> Unit,
    /** Whisper mode: the box is teal and says so. */
    whisper: Boolean = false,
    /** Say "Answered when Mímir is ready" while a message waits (not when it waits for the PC, which answers when it connects). */
    waitHint: Boolean = true,
    modifier: Modifier = Modifier,
    /** Files to go with the message: it can be sent with no words. */
    hasFiles: Boolean = false,
    /** Each rise puts the cursor in the box and opens the keyboard (a new chat, opening the app), when Settings allows it. */
    focusTick: Int = 0,
    /** False while a Whisper, a sheet, a dialog or the side menu is up: the keyboard waits (and comes when they go). */
    focusOk: Boolean = true,
    /** Shown at the top of the box: the attached files. */
    above: (@Composable () -> Unit)? = null,
    /** The + at the start of the row. */
    attach: @Composable () -> Unit = {},
    /** The model and effort chip in the centre of the row. */
    chip: @Composable () -> Unit = {},
) {
    val ready = (value.isNotBlank() || hasFiles) && !waiting
    val shape = RoundedCornerShape(22.dp)
    val label = if (whisper) "Whisper to $AI_NAME" else "Message $AI_NAME"
    val hint = if (waiting && waitHint) "Answered when $AI_NAME is ready" else label
    // The microphone was refused: say so in plain words, with the way to allow it.
    var micDenied by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Column(
        modifier.fillMaxWidth().testTag(COMPOSER_TAG).clip(shape).background(if (whisper) WhisperTint.Card else BoxFill).border(1.dp, if (whisper) Palette.Outline else BoxBorder, shape)
            .padding(start = 6.dp, end = 6.dp, bottom = 2.dp),
    ) {
        if (micDenied) MicRefusedCard { micDenied = false }
        if (above != null) androidx.compose.animation.AnimatedVisibility(
            hasFiles,
            enter = androidx.compose.animation.expandVertically(motionSpec(Motion.NEEDS_YOU_BAR_MS)) + androidx.compose.animation.fadeIn(motionSpec(Motion.NEEDS_YOU_BAR_MS)),
            exit = androidx.compose.animation.shrinkVertically(motionSpec(Motion.NEEDS_YOU_BAR_MS)) + androidx.compose.animation.fadeOut(motionSpec(Motion.NEEDS_YOU_BAR_MS)),
        ) { Box(Modifier.padding(start = 4.dp, top = 8.dp)) { above() } }
        // The box keeps the keyboard's whole state (words, cursor and the part still being composed, as voice
        // typing and word suggestions do), and hands the parent only the words: nothing is rewritten while the
        // keyboard works. Only a change from outside (cleared after Send, words heard) replaces the text.
        var field by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(value)) }
        if (field.text != value) field = androidx.compose.ui.text.input.TextFieldValue(value, androidx.compose.ui.text.TextRange(value.length))
        val focus = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val keys = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
        var risen by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0) }
        val hold = LocalKeyboardHold.current
        val putKeyboardAway = rememberPutKeyboardAway()
        androidx.compose.runtime.LaunchedEffect(focusTick, focusOk) {
            if (focusTick > risen && focusOk && keyboardAutoOn(ctx)) {
                risen = focusTick
                androidx.compose.runtime.withFrameNanos { }
                // A rise still waiting when the app was left is dropped, not rung on the way back.
                if (hold?.held != true) runCatching { focus.requestFocus(); keys?.show() }
            }
        }
        BasicTextField(
            value = field,
            onValueChange = { next -> field = next; if (next.text != value) onValue(next.text) },
            // A Whisper's box doesn't auto-correct (and the keyboard is told not to learn: see NoKeyboardLearning).
            keyboardOptions = whisperKeyboard(whisper),
            textStyle = Type.Body.copy(color = Palette.Text, lineHeight = 16.sp * 1.4f),
            cursorBrush = SolidColor(Palette.BlueLight),
            maxLines = 6,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).focusRequester(focus).semantics { contentDescription = label }
                .padding(horizontal = 8.dp)
                .messageBoxKeys { if (ready) onSend() },
            decorationBox = { inner ->
                Box(Modifier.padding(top = 12.dp, bottom = 4.dp), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(hint, style = Type.Body.copy(lineHeight = 16.sp * 1.4f), color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                }
            },
        )
        ReplyRow(
            plus = attach,
            chip = chip,
            send = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // What the mic hears goes into the box; in a Whisper it stays on this phone.
                    MicButton(voice, "Talk instead of typing", filled = false, surface = androidx.compose.ui.graphics.Color.Transparent, size = SEND_SIZE, outline = false, onDenied = { micDenied = true })
                    if (writing) StopControl(onStop, shape = StopShape.Round, size = SEND_SIZE, fill = Palette.RedBorder, description = "Stop the reply")
                    else SendButton(ready, fill = if (whisper) WhisperTint.Send else Palette.Blue, onSend = { putKeyboardAway(); onSend() })
                }
            },
        )
    }
}

/** The microphone was refused: the words fill the width and the buttons sit under them, like the camera's card. */
@Composable
internal fun MicRefusedCard(onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    AttachNote(MIC_REFUSED, "Open settings" to { openAppSettings(context) }, onDismiss)
}

/** What the box says when the owner refused the microphone. */
const val MIC_REFUSED = "$AI_NAME can't hear you: allow the microphone in Android Settings › Apps › $AI_NAME › Permissions."

/** Android's page for this app, where its permissions are. */
fun openAppSettings(context: android.content.Context) {
    runCatching {
        context.startActivity(
            android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private val BoxFill = androidx.compose.ui.graphics.Color(0xFF1A1D23)
private val BoxBorder = androidx.compose.ui.graphics.Color(0xFF2B2F37)

/** The test tag on a message box (Chat's and a task's reply box). */
const val COMPOSER_TAG = "composer"

/** The small buttons on the message box's bottom row. */
val SMALL_BUTTON = 36.dp

/** The + and mic: a little smaller, to leave room for the chips. */
val TINY_BUTTON = 30.dp
