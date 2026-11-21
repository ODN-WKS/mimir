package io.github.odnwks.mimir.ui.theme

import android.content.SharedPreferences
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import kotlin.math.roundToInt

/**
 * How big the app is drawn, on top of the phone's own display size and font size (both kept):
 * [scale] zooms the whole app, like the PC's zoom (Settings › General › Size); [chatText] is the
 * text size in conversations, changed by pinching with two fingers. Both are remembered.
 */
class AppSize(private val prefs: SharedPreferences?) {
    var scale by mutableFloatStateOf(prefs?.getFloat(SCALE, 1f)?.let(::clampScale) ?: 1f)
        private set
    /** The phone's own density and font size, under the app's size. */
    internal var baseDensity = 1f
    internal var baseFontScale = 1f
    internal var baseWidthDp = 0f

    /**
     * The size actually drawn: [scale], except that the app never gets narrower than about
     * 220 dp of room (less with the phone's own large font), where words would break apart.
     */
    val shown: Float get() {
        if (scale <= 1f || baseWidthDp <= 0f) return scale
        val most = baseWidthDp / (220f * maxOf(1f, baseFontScale / 1.3f))
        return minOf(scale, maxOf(1f, most))
    }

    /** What [shown] would be at [value] (for the slider's preview and its note). */
    fun shownAt(value: Float): Float {
        if (value <= 1f || baseWidthDp <= 0f) return value
        return minOf(value, maxOf(1f, baseWidthDp / (220f * maxOf(1f, baseFontScale / 1.3f))))
    }
    var chatText by mutableFloatStateOf(prefs?.getFloat(CHAT_TEXT, 1f)?.coerceIn(CHAT_MIN, CHAT_MAX) ?: 1f)
        private set

    fun chooseScale(value: Float) {
        scale = clampScale(value)
        prefs?.edit()?.putFloat(SCALE, scale)?.apply()
    }

    /** While the fingers move; [saveChatText] keeps it when they lift. */
    fun zoomChatText(by: Float) { chatText = (chatText * by).coerceIn(CHAT_MIN, CHAT_MAX) }
    fun chooseChatText(value: Float) { chatText = value.coerceIn(CHAT_MIN, CHAT_MAX); saveChatText() }
    fun saveChatText() { prefs?.edit()?.putFloat(CHAT_TEXT, chatText)?.apply() }

    companion object {
        const val PREFS = "display"
        const val SCALE = "app_scale"
        const val CHAT_TEXT = "chat_text"
        const val MIN = 0.5f
        const val MAX = 1.5f
        /** 50% to 150% in steps of 10: nine stops between the ends. */
        const val STEPS = 9
        const val CHAT_MIN = 0.7f
        const val CHAT_MAX = 2.5f
        fun clampScale(v: Float) = ((v.coerceIn(MIN, MAX) * 10).roundToInt() / 10f)
        fun percent(v: Float) = "${(v * 100).roundToInt()}%"
    }
}

val LocalAppSize = staticCompositionLocalOf { AppSize(null) }

/** The whole app at [AppSize.scale], keeping the phone's own density and font size underneath. */
@Composable
fun ScaledApp(size: AppSize, content: @Composable () -> Unit) {
    val d = LocalDensity.current
    size.baseDensity = d.density
    size.baseFontScale = d.fontScale
    size.baseWidthDp = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.toFloat()
    CompositionLocalProvider(
        LocalAppSize provides size,
        LocalDensity provides Density(d.density * size.shown, d.fontScale),
        content = content,
    )
}

/** Set inside a conversation: its message texts follow the pinched size. */
val LocalInChat = staticCompositionLocalOf { false }

/** Marks a conversation: the messages in it ([ChatText]) take the pinched text size. */
@Composable
fun ChatTextSize(content: @Composable () -> Unit) = CompositionLocalProvider(LocalInChat provides true, content = content)

/**
 * One message's text at the pinched size. It reads the sizes itself (not the density handed
 * down), so a message in a scrolling list follows the fingers too.
 */
@Composable
fun ChatText(content: @Composable () -> Unit) {
    if (!LocalInChat.current) return content()
    val size = LocalAppSize.current
    CompositionLocalProvider(
        LocalDensity provides Density(size.baseDensity * size.shown, size.baseFontScale * size.chatText),
        content = content,
    )
}

/**
 * Two fingers pinch the conversation's text bigger or smaller, following the fingers; one finger
 * still scrolls and taps as before. The size is kept when the fingers lift.
 */
fun Modifier.pinchToZoomText(size: AppSize): Modifier = pointerInput(size) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var zoomed = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.count { it.pressed } >= 2) {
                val z = event.calculateZoom()
                if (z != 1f) { size.zoomChatText(z); zoomed = true }
                // Two fingers belong to the pinch, not to the list's scroll.
                event.changes.forEach { it.consume() }
            }
            if (event.changes.none { it.pressed }) break
        }
        if (zoomed) size.saveChatText()
    }
}
