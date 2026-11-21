package io.github.odnwks.mimir.ui.adaptive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The three limits that stop a phone layout stretching across a tablet (tablet redesign, Section 9):
 * reading text 640 dp at most, buttons 120 to 320 dp, sheets 640 dp at most; a chat bubble 480 dp.
 * On a phone none of them is ever reached, so phone screens look the same with them applied.
 */
object Limits {
    val Reading: Dp = 640.dp
    /** The chat lane beside the always-open side menu. */
    val Lane: Dp = 720.dp
    val Bubble: Dp = 480.dp
    val ButtonMin: Dp = 120.dp
    val ButtonMax: Dp = 320.dp
    val Sheet: Dp = 640.dp
    val Dialog: Dp = 520.dp
    val ToPopup: Dp = 400.dp
    val Welcome: Dp = 560.dp
    /** Touch and mouse targets (no separate dense mode with a mouse). */
    val Target: Dp = 48.dp
}

/** As wide as its words plus padding, never under 120 dp or over 320 dp. */
fun Modifier.buttonLimit(): Modifier = this.widthIn(min = Limits.ButtonMin, max = Limits.ButtonMax)

/** A chat bubble is never wider than 480 dp. */
fun Modifier.bubbleLimit(): Modifier = this.widthIn(max = Limits.Bubble)

/** A sheet is never wider than 640 dp (centre it with [Centered]). */
fun Modifier.sheetLimit(): Modifier = this.widthIn(max = Limits.Sheet)

/**
 * Puts [content] in a column of at most [max] in the middle of the space it is given.
 * Narrower spaces (any phone) are filled as before.
 */
@Composable
fun Centered(max: Dp = Limits.Reading, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = max).fillMaxWidth(), content = content)
    }
}
