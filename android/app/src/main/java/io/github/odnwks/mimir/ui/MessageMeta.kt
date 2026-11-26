package io.github.odnwks.mimir.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.odnwks.mimir.data.MessageReceipt
import io.github.odnwks.mimir.data.TaskMessage
import io.github.odnwks.mimir.ui.theme.LocalReduceMotion
import io.github.odnwks.mimir.ui.theme.Palette
import io.github.odnwks.mimir.ui.theme.Type
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * P11: the small print under every message, like Messenger: when it was sent ("5:41 PM", "Tue 5:41 PM",
 * "Nov 18, 5:41 PM"), the model and effort that answered (or that the owner's message went to), and on the
 * owner's own messages a receipt circle: Sent (empty), Delivered (with a check), Read (filled).
 */
object MetaLook {
    /** The circle's ring and the check (mock-up 5). */
    val Ring = Color(0xFF9AA3AD)
    val Check = Color(0xFFC9D0D8)
    /** Read: a filled circle. */
    val ReadFill = Color(0xFF8FB4FF)
    /** A switched model: amber, with ⇄ before it. */
    val Switch = Color(0xFFF2C46D)
    /** About 8 dp across the radius (owner, 17:45): a little bigger than the time's letters, so the check reads clearly. */
    val Mark = 16.dp
    /** The grey "thinking" bubble (mock-up 4). */
    val ThinkingWidth = 54.dp
    val ThinkingHeight = 28.dp
    val Dot = 7.dp
    /** One rise and fall of all three dots. */
    const val DOTS_MS = 1200
}

const val SWITCH_MARK = "⇄"
const val THINKING_TAG = "thinking-bubble"
const val META_TAG = "message-meta"
const val SWITCH_LINE_TAG = "switch-line"
const val NOT_READ_TAG = "not-read-yet"
/** How long a message may sit at Sent or Delivered while the task works before the chat says why. */
const val NOT_READ_AFTER_MS = 60_000L

/** The test tag on a receipt circle ("receipt-Sent", "receipt-Delivered", "receipt-Read"). */
fun receiptTag(r: MessageReceipt) = "receipt-${r.name}"

/** What a receipt circle says to TalkBack. */
fun receiptWords(r: MessageReceipt): String = when (r) {
    MessageReceipt.Sent -> "Sent"
    MessageReceipt.Delivered -> "Delivered"
    MessageReceipt.Read -> "Read"
}

private val clock12 = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
private val weekday = DateTimeFormatter.ofPattern("EEE", Locale.US)
private val monthDay = DateTimeFormatter.ofPattern("MMM d", Locale.US)
private val monthDayYear = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)

/**
 * A message's time in 12-hour words: today "5:41 PM", in the last week "Tue 5:41 PM", older "Nov 18, 5:41 PM"
 * (a year before this one also names the year). A time a little ahead of the phone's clock counts as today.
 */
fun messageTime(atMs: Long, nowMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
    val at = Instant.ofEpochMilli(atMs).atZone(zone)
    val now = Instant.ofEpochMilli(nowMs).atZone(zone)
    val time = clock12.format(at)
    val days = ChronoUnit.DAYS.between(at.toLocalDate(), now.toLocalDate())
    return when {
        days <= 0L -> time
        days < 7L -> "${weekday.format(at)} $time"
        at.year == now.year -> "${monthDay.format(at)}, $time"
        else -> "${monthDayYear.format(at)}, $time"
    }
}

/** An effort in the menu's words ("high" is "High", none is "Default Effort"); one the phone doesn't know keeps its own name. */
fun effortWords(effort: String?): String =
    TaskEffort.entries.firstOrNull { it.wire == effort?.ifBlank { null } }?.words ?: effort!!.replaceFirstChar { it.uppercase() }

/** "Opus 5.5 · High": a model id and effort in words; null without a model. */
fun modelEffortWords(model: String?, effort: String?): String? {
    if (model.isNullOrBlank()) return null
    return "${claudeModelWord(model) ?: plainModelName(model)} · ${effortWords(effort)}"
}

/** The line under a switched reply: from what, and why in a few words ("reason not reported" when nobody said). */
fun switchLineWords(m: TaskMessage, before: TaskMessage?): String {
    val from = before?.let { modelEffortWords(it.model, it.effort) }
    val why = m.switched?.ifBlank { null } ?: REASON_NOT_REPORTED
    return (if (from != null) "Switched from $from: $why." else "Switched: $why.") + " Tap for more."
}

const val REASON_NOT_REPORTED = "reason not reported"
/** The full reason when the PC gave none (the same sentence as the PC's own). */
const val NOT_REPORTED_SENTENCE = "Claude Code did not say why the model or effort changed, and Mímir did not change it."

/** The reply before [m] (in [all]) that names its model: what a switch is from. */
fun replyBefore(all: List<TaskMessage>, m: TaskMessage): TaskMessage? {
    val i = all.indexOfFirst { it.id == m.id }
    if (i <= 0) return null
    return all.subList(0, i).lastOrNull { it.fromClaude && !it.model.isNullOrBlank() }
}

/** Why the owner's message is not read yet, in words, after a minute at Sent or Delivered while the task works. */
fun notReadYetWords(r: MessageReceipt, busyWithStep: Boolean): String = when {
    r == MessageReceipt.Sent -> "Not read yet: your PC has it and hands it to Claude when Claude is ready"
    busyWithStep -> "Not read yet: Claude is busy with a step"
    else -> "Not read yet: Claude takes it in when it finishes what it is doing"
}

/** The receipt circle: Sent an empty circle, Delivered a circle with a check, Read a filled circle. */
@Composable
fun ReceiptMark(r: MessageReceipt, modifier: Modifier = Modifier) {
    Canvas(modifier.size(MetaLook.Mark).testTag(receiptTag(r)).semantics { contentDescription = receiptWords(r) }) {
        val stroke = 1.5.dp.toPx()
        val radius = size.minDimension / 2 - stroke / 2
        if (r == MessageReceipt.Read) drawCircle(MetaLook.ReadFill, radius + stroke / 2)
        else drawCircle(MetaLook.Ring, radius, style = Stroke(stroke))
        if (r == MessageReceipt.Delivered) {
            val w = size.width
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(w * 0.29f, w * 0.52f)
                lineTo(w * 0.44f, w * 0.67f)
                lineTo(w * 0.72f, w * 0.36f)
            }
            drawPath(path, MetaLook.Check, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        }
    }
}

/**
 * The small print in one dim line: "Opus 5.5 · Default Effort · 5:41 PM", then [receipt]'s circle. A [switched]
 * model is amber with ⇄ before it. Nothing at all when nothing is known (an old task: no time rather than a wrong one).
 */
@Composable
fun MetaLine(
    model: String?,
    effort: String?,
    atMs: Long?,
    modifier: Modifier = Modifier,
    receipt: MessageReceipt? = null,
    switched: Boolean = false,
    nowMs: Long = System.currentTimeMillis(),
) {
    val who = modelEffortWords(model, effort)
    val time = atMs?.let { messageTime(it, nowMs) }
    if (who == null && time == null && receipt == null) return
    Row(modifier.testTag(META_TAG), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        if (who != null || time != null) Text(
            buildAnnotatedString {
                if (who != null) {
                    if (switched) withStyle(SpanStyle(color = MetaLook.Switch)) { append("$SWITCH_MARK $who") } else append(who)
                    if (time != null) append(" · ")
                }
                if (time != null) append(time)
            },
            style = Type.Small, color = Palette.TextDim.copy(alpha = 0.75f),
            modifier = Modifier.weight(1f, fill = false),
        )
        if (receipt != null) ReceiptMark(receipt)
    }
}

/** A reply's small print, for the end of its Listen, Copy, Share row: a tap on a switched one gives the full reason. */
@Composable
fun ReplyMeta(m: TaskMessage, onWhy: (() -> Unit)?) {
    // No extra touch height here: it would push the chat apart; the switch line under it takes the same tap.
    val tap = if (m.switched != null && onWhy != null) Modifier.clickable(role = Role.Button, onClickLabel = "Why the model changed", onClick = onWhy) else Modifier
    Box(tap, contentAlignment = Alignment.CenterStart) {
        MetaLine(m.model, m.effort, m.atMs, switched = m.switched != null)
    }
}

/** The amber line under a switched reply ("Switched from Opus 5.5 · Default Effort: usage limit. Tap for more."). */
@Composable
fun SwitchLine(m: TaskMessage, before: TaskMessage?, onWhy: () -> Unit) {
    Text(
        switchLineWords(m, before), style = Type.Small, color = MetaLook.Switch,
        modifier = Modifier.testTag(SWITCH_LINE_TAG).clickable(role = Role.Button, onClickLabel = "Why the model changed", onClick = onWhy),
    )
}

/** The full reason a reply's model or effort changed, on a tap. */
@Composable
fun SwitchWhyDialog(m: TaskMessage, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Why the model changed", style = Type.Section) },
        text = { Text(m.switchedWhy?.ifBlank { null } ?: NOT_REPORTED_SENTENCE, style = Type.Label) },
        confirmButton = { TextButton(onClick = onClose) { Text("OK") } },
    )
}

/**
 * A minute at Sent or Delivered while the task works: why it is not read yet, in words (never a guess left to the owner).
 * [atMs] is the message's time; nothing shows without one.
 */
@Composable
fun NotReadYetLine(atMs: Long?, receipt: MessageReceipt?, working: Boolean, busyWithStep: Boolean) {
    if (atMs == null || receipt == null || receipt == MessageReceipt.Read || !working) return
    var due by remember(atMs, receipt) { mutableStateOf(System.currentTimeMillis() - atMs >= NOT_READ_AFTER_MS) }
    LaunchedEffect(atMs, receipt) {
        if (!due) { kotlinx.coroutines.delay((atMs + NOT_READ_AFTER_MS - System.currentTimeMillis()).coerceAtLeast(0)); due = true }
    }
    if (due) Text(
        notReadYetWords(receipt, busyWithStep), style = Type.Small, color = Palette.AmberNote,
        textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.testTag(NOT_READ_TAG),
    )
}

/**
 * The AI's side while it thinks: a grey bubble with three dots rising and falling in turn (Messenger's typing
 * bubble). Read at draw time, so the dots move at full frame rate without redrawing the chat; with reduce
 * motion they fade in turn instead of moving.
 */
@Composable
fun ThinkingBubble(modifier: Modifier = Modifier) {
    val reduce = LocalReduceMotion.current
    val t by rememberInfiniteTransition(label = "thinking").animateFloat(
        0f, 1f, infiniteRepeatable(tween(MetaLook.DOTS_MS, easing = LinearEasing), RepeatMode.Restart), label = "dots",
    )
    Row(
        modifier.size(MetaLook.ThinkingWidth, MetaLook.ThinkingHeight).clip(ChatLook.replyShape).background(Palette.Card)
            .border(1.dp, Palette.Line, ChatLook.replyShape)
            .testTag(THINKING_TAG).semantics { contentDescription = "Claude is thinking" },
        horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { i ->
            Box(
                Modifier.size(MetaLook.Dot).graphicsLayer {
                    val lift = dotLift(t, i)
                    if (reduce) alpha = 0.35f + 0.65f * lift
                    else { translationY = -3.5.dp.toPx() * lift; alpha = 0.55f + 0.45f * lift }
                }.background(Color(0xFFCFD3D8), CircleShape),
            )
        }
    }
}

/** How high dot [i] is at [t] (0 to 1 of a cycle): each rises and falls in turn, then all rest a moment. */
fun dotLift(t: Float, i: Int): Float {
    val p = ((t - i * 0.18f) % 1f + 1f) % 1f
    return if (p < 0.4f) kotlin.math.sin(p / 0.4f * Math.PI.toFloat()) else 0f
}
