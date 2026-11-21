package io.github.odnwks.mimir.ui.adaptive

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.dp
import io.github.odnwks.mimir.ui.theme.LocalReduceMotion
import io.github.odnwks.mimir.ui.theme.Motion
import io.github.odnwks.mimir.ui.theme.Palette

/** Times from the tablet redesign's motion table (Section 8). */
object PaneMotion {
    /** Rail to another section: the panes cross-fade with a 16 dp rise. */
    const val SECTION_MS = 180
    const val SECTION_RISE_DP = 16
    /** Something opens in the right pane: it fades and slides in 24 dp from the right. */
    const val OPEN_RIGHT_MS = 220
    const val OPEN_RIGHT_SLIDE_DP = 24
    /** "Remove animations" turns every move into a short fade. */
    const val REDUCED_MS = 100
}

/**
 * The frame of the app at every size.
 *
 * Under 600 dp: [left] above [bottomBar], exactly the phone's layout.
 * From 600 dp: [rail] on the left and [left] beside it; no bottom bar.
 * From 840 dp (when both panes fit): [rail], [left] at its width, a line, and [right].
 *
 * [right] is drawn only when there are two panes; with one pane the caller shows the same thing through
 * its own navigation (so turning the tablet keeps what was open). Reading order for a screen reader is
 * rail, left pane, right pane.
 */
@Composable
fun AdaptiveFrame(
    shape: WindowShape,
    rail: @Composable () -> Unit,
    bottomBar: @Composable () -> Unit,
    leftTitle: String,
    left: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    rightTitle: String = "",
    right: (@Composable () -> Unit)? = null,
) {
    val panes = shape.panes
    if (!shape.rail) {
        Column(modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth().pane(leftTitle, 1f)) { left() }
            bottomBar()
        }
        return
    }
    Row(modifier.fillMaxSize()) {
        Box(Modifier.semantics { isTraversalGroup = true; traversalIndex = 0f }) { rail() }
        if (panes != null && right != null) {
            Box(Modifier.width(panes.left.dp).fillMaxHeight().pane(leftTitle, 1f).imeWhenFocused()) { left() }
            Spacer(Modifier.width(PaneRules.DIVIDER.dp).fillMaxHeight().background(Palette.Line))
            Box(Modifier.weight(1f).fillMaxHeight().pane(rightTitle, 2f).imeWhenFocused()) { right() }
        } else {
            Box(Modifier.weight(1f).fillMaxHeight().pane(leftTitle, 1f).imeWhenFocused()) { left() }
        }
    }
}

/** A named region ("Chat", "Needs you", "Task: …"), read as one group in its turn. */
private fun Modifier.pane(title: String, index: Float): Modifier = this.semantics {
    if (title.isNotEmpty()) paneTitle = title
    isTraversalGroup = true
    traversalIndex = index
}

/**
 * Only the pane with the cursor makes room for the keyboard; the other keeps its full height.
 * (The frame must not also be padded for the keyboard by its parent.)
 */
fun Modifier.imeWhenFocused(): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val bottom = with(LocalDensity.current) { WindowInsets.ime.getBottom(this).toDp() }
    this.onFocusChanged { focused = it.hasFocus }.padding(bottom = if (focused) bottom else 0.dp)
}

/**
 * Shows what [key] names inside a pane, moving only inside that pane.
 * [opensFromRight] is for something picked on the left opening on the right (fade and a 24 dp slide);
 * otherwise it is a section change (cross-fade with a 16 dp rise). With animations removed: a 100 ms fade.
 */
@Composable
fun <T> PaneContent(
    key: T,
    modifier: Modifier = Modifier,
    opensFromRight: Boolean = false,
    content: @Composable (T) -> Unit,
) {
    val reduce = LocalReduceMotion.current
    val density = LocalDensity.current
    AnimatedContent(
        targetState = key,
        modifier = modifier,
        transitionSpec = { paneTransition(opensFromRight, reduce, density.density) },
        label = "pane",
    ) { content(it) }
}

internal fun paneTransition(opensFromRight: Boolean, reduce: Boolean, density: Float): ContentTransform {
    if (reduce) return fadeIn(tween(PaneMotion.REDUCED_MS)) togetherWith fadeOut(tween(PaneMotion.REDUCED_MS))
    return if (opensFromRight) {
        val ms = PaneMotion.OPEN_RIGHT_MS
        val px = (PaneMotion.OPEN_RIGHT_SLIDE_DP * density).toInt()
        (fadeIn(tween(ms, easing = Motion.Ease)) + slideInHorizontally(tween(ms, easing = Motion.Ease)) { px }) togetherWith
            fadeOut(tween(ms / 2, easing = Motion.Ease))
    } else {
        val ms = PaneMotion.SECTION_MS
        val px = (PaneMotion.SECTION_RISE_DP * density).toInt()
        (fadeIn(tween(ms, easing = Motion.Ease)) + slideInVertically(tween(ms, easing = Motion.Ease)) { px }) togetherWith
            fadeOut(tween(ms / 2, easing = Motion.Ease))
    }
}
