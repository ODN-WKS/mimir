package io.github.odnwks.mimir.ui.adaptive

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.github.odnwks.mimir.ui.theme.LocalReduceMotion
import io.github.odnwks.mimir.ui.theme.Palette
import io.github.odnwks.mimir.ui.theme.Type
import kotlinx.coroutines.delay

/** Pointer highlight and focus ring fade (tablet redesign, Section 8). */
const val HOVER_MS = 100

/** Icon-only buttons show their name after this long under the pointer. */
const val HINT_AFTER_MS = 500L

/**
 * Everything that can be pressed lightens under the pointer, and shows a hand.
 * Costs nothing with a finger: no pointer, no hover.
 */
fun Modifier.hoverLight(shape: Shape = RoundedCornerShape(12.dp), hand: Boolean = true): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val reduce = LocalReduceMotion.current
    val alpha by animateFloatAsState(if (hovered) 0.08f else 0f, tween(if (reduce) 0 else HOVER_MS), label = "hover")
    this.hoverable(source)
        .then(if (hand) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
        .drawWithContent {
            drawContent()
            if (alpha > 0f) drawOutline(shape.createOutline(size, layoutDirection, this), Color.White.copy(alpha = alpha))
        }
}

/** The line between the panes shows a resize pointer. */
fun Modifier.resizePointer(): Modifier = this.pointerHoverIcon(PointerIcon(android.view.PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW))

/**
 * A right-click opens the same menu a long press does, at the pointer.
 * [onMenu] gets the place inside this element. A plain tap or click is left for the element itself.
 */
fun Modifier.menuAt(onMenu: (Offset) -> Unit): Modifier = this
    .pointerInput(onMenu) {
        // Right-click (mouse or trackpad), seen before the element's own click.
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                    val change = event.changes.first()
                    onMenu(change.position)
                    event.changes.forEach { it.consume() }
                }
            }
        }
    }
    .pointerInput(onMenu) {
        // Long press with a finger or pen.
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (down.type == PointerType.Mouse) return@awaitEachGesture
            val long = awaitLongPressOrCancellation(down.id)
            if (long != null) { onMenu(long.position); long.consume() }
        }
    }

/**
 * Wraps an icon-only button: its [name] shows after half a second under the pointer.
 * (The button's own spoken label is separate; this is not read out twice.)
 */
@Composable
fun HoverHint(name: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(hovered) {
        if (hovered) { delay(HINT_AFTER_MS); show = true } else show = false
    }
    Box(modifier.hoverable(source), contentAlignment = Alignment.Center) {
        content()
        if (show) {
            Popup(alignment = Alignment.TopCenter, offset = IntOffset(0, -96), properties = PopupProperties(focusable = false)) {
                Text(
                    name, color = Palette.Text, style = Type.Small,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Palette.Card)
                        .padding(horizontal = 10.dp, vertical = 6.dp).clearAndSetSemantics { },
                )
            }
        }
    }
}
