package io.github.odnwks.mimir.ui

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.odnwks.mimir.ui.theme.Palette
import io.github.odnwks.mimir.ui.theme.Type
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * How a Stop button protects against a slip (owner, 2026-11-23: "I thought I asked for a confirmation"):
 * a 2 second hold, a second tap that says "Stop?", or nothing. Picked in Settings; Confirm is the default.
 */
enum class StopMode(val wire: String, val title: String, val about: String) {
    Hold("hold", "Hold 2 seconds", "Press and hold Stop for 2 seconds (in a task, hold the Stop row in its ⋯ menu). A quick tap does nothing."),
    Confirm("confirm", "Confirm", "Stop is in the ⋯ menu of a task: open it and tap Stop. Other Stop buttons ask for a second tap while they say “Stop?”."),
    Off("off", "Off", "Stop works with one tap."),
    ;

    companion object {
        val DEFAULT = Confirm
        fun of(wire: String?) = entries.firstOrNull { it.wire == wire } ?: DEFAULT
    }
}

const val STOP_HOLD_MS = 2_000
/** How long "Stop?" waits for the second tap. */
const val STOP_CONFIRM_MS = 4_000L

/** What a tap on a Stop button does: stop now, ask for the second tap, or say how to hold. */
enum class StopAction { Fire, Arm, Hint }

/** The pure rule behind every Stop button (the screens only draw it). */
class StopGuard(private val mode: StopMode) {
    private var armedUntil = 0L

    /** A tap at [now] (milliseconds). [accessible]: from TalkBack, which can't hold, so Hold takes the two-tap way. */
    fun tap(now: Long, accessible: Boolean = false): StopAction = when {
        mode == StopMode.Off -> StopAction.Fire
        mode == StopMode.Hold && !accessible -> StopAction.Hint
        now < armedUntil -> { armedUntil = 0L; StopAction.Fire }
        else -> { armedUntil = now + STOP_CONFIRM_MS; StopAction.Arm }
    }
}

/** The saved choice (Settings › Look and size › Stop buttons). */
object StopPrefs {
    private const val KEY = "stop_mode"
    fun get(ctx: Context): StopMode = StopMode.of(ctx.getSharedPreferences(PHONE_CHAT_PREFS, 0).getString(KEY, null))
    fun set(ctx: Context, mode: StopMode) { ctx.getSharedPreferences(PHONE_CHAT_PREFS, 0).edit().putString(KEY, mode.wire).apply() }
}

@Composable
fun rememberStopMode(): StopMode {
    val ctx = LocalContext.current
    // Read each time the button is drawn, so a change in Settings counts at once.
    return StopPrefs.get(ctx)
}

enum class StopShape { Round, Pill, Wide }

/**
 * One Stop button for the whole app. Idle it reads "Stop"; with Confirm a first tap makes it say
 * "Stop?" for a few seconds (the second tap stops); with Hold it fills while it is held and stops
 * after 2 seconds (a quick tap says "Hold to stop"); with Off one tap stops.
 */
@Composable
fun StopControl(
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    shape: StopShape = StopShape.Pill,
    size: Dp = 48.dp,
    fill: Color = Palette.Red,
    description: String = "Stop",
    stateText: String? = null,
    mode: StopMode = rememberStopMode(),
    /** The label on a pill or wide button (the round one only has the square). */
    label: String = "Stop",
) {
    val guard = remember(mode) { StopGuard(mode) }
    var armed by remember(mode) { mutableStateOf(false) }
    var hint by remember(mode) { mutableStateOf(false) }
    val progress = remember(mode) { Animatable(0f) }
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val now = { System.currentTimeMillis() }
    // A hold that ran its 2 seconds already stopped the work; letting go must not count as a tap.
    var heldDone by remember(mode) { mutableStateOf(false) }
    LaunchedEffect(armed) { if (armed) { delay(STOP_CONFIRM_MS); armed = false } }
    LaunchedEffect(hint) { if (hint) { delay(2_500); hint = false } }
    val fire = { armed = false; hint = false; haptic.performHapticFeedback(HapticFeedbackType.LongPress); onStop() }
    val act = { a: StopAction ->
        when (a) {
            StopAction.Fire -> fire()
            StopAction.Arm -> { armed = true; haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
            StopAction.Hint -> hint = true
        }
    }
    val words = when { armed -> "Stop?"; hint -> "Hold to stop"; else -> label }
    val shapeClip = if (shape == StopShape.Round && !armed && !hint) CircleShape else RoundedCornerShape(if (shape == StopShape.Wide) 12.dp else 24.dp)
    val base = when (shape) {
        StopShape.Round -> if (!armed && !hint) Modifier.size(size) else Modifier.heightIn(min = size).widthIn(min = size)
        StopShape.Pill -> Modifier.heightIn(min = 40.dp).widthIn(min = 48.dp)
        StopShape.Wide -> Modifier.fillMaxWidth().heightIn(min = 48.dp)
    }
    Box(
        modifier.then(base).clip(shapeClip).background(fill)
            .pointerInput(mode) {
                detectTapGestures(
                    onPress = {
                        if (mode == StopMode.Hold) {
                            heldDone = false
                            val job = scope.launch {
                                progress.snapTo(0f)
                                progress.animateTo(1f, tween(STOP_HOLD_MS, easing = LinearEasing))
                                progress.snapTo(0f)
                                heldDone = true
                                fire()
                            }
                            tryAwaitRelease()
                            if (job.isActive) { job.cancel(); scope.launch { progress.snapTo(0f) } }
                        }
                    },
                    onTap = { if (heldDone) heldDone = false else act(guard.tap(now())) },
                )
            }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = description
                if (stateText != null) { stateDescription = stateText; liveRegion = LiveRegionMode.Polite }
                else if (armed) { stateDescription = "Tap again to stop"; liveRegion = LiveRegionMode.Polite }
                onClick(label = if (mode == StopMode.Hold) "Stop (asks again)" else "Stop") { act(guard.tap(now(), accessible = true)); true }
            },
        contentAlignment = Alignment.Center,
    ) {
        // The hold's fill: grows from the left while the finger stays down.
        // (Sized from the button's own box: a plain fill-height would take the whole bar's height.)
        if (mode == StopMode.Hold && progress.value > 0f) Box(Modifier.matchParentSize()) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(progress.value).background(Color.Black.copy(alpha = 0.28f)).align(Alignment.CenterStart))
        }
        if (shape == StopShape.Round && !armed && !hint) Icon(Glyphs.Stop, null, tint = Palette.White, modifier = Modifier.size(20.dp))
        else Row(
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            if (!armed && !hint) Icon(Glyphs.Stop, null, tint = Palette.White, modifier = Modifier.size(18.dp))
            Text(words, style = Type.Label.copy(fontWeight = FontWeight.Bold), color = Palette.White)
        }
    }
}

/** Settings › Look and size › Stop buttons: Hold 2 seconds, Confirm (the default) or Off. */
@Composable
fun StopSetting() {
    val ctx = LocalContext.current
    var mode by remember { mutableStateOf(StopPrefs.get(ctx)) }
    Panel(spacing = 2.dp) {
        Text(STOP_SETTING_ABOUT, style = Type.Small, color = Palette.TextDim, modifier = Modifier.padding(bottom = 4.dp))
        StopMode.entries.forEach { m ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.RadioButton) { mode = m; StopPrefs.set(ctx, m) },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RadioMark(mode == m)
                Column(Modifier.weight(1f)) {
                    Text(m.title + if (m == StopMode.DEFAULT) " (usual)" else "", style = Type.BodyTight, color = Palette.Text)
                    Text(m.about, style = Type.Small, color = Palette.TextDim)
                }
            }
        }
    }
}

const val STOP_SETTING_TITLE = "STOP BUTTONS"
const val STOP_SETTING_ABOUT = "So a slip never stops work. This is for every Stop button in the app."
