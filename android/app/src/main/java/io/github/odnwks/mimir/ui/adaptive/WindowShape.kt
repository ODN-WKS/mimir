package io.github.odnwks.mimir.ui.adaptive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The layout the window gets, decided by its own size (so a tablet showing two apps side by side gets
 * the layout that fits its half). From the tablet redesign, Section 2:
 * under 600 dp wide is the phone layout; from 600 a rail and one pane; from 840 (and at least 480 tall) two panes.
 */
enum class WidthClass { Compact, Medium, Expanded }

/** The widths of the two panes side by side, in dp. */
data class PaneWidths(val left: Float, val right: Float)

object PaneRules {
    const val RAIL = 88f
    /** The side menu, always open on the left from [TWO_PANES_FROM]. */
    const val MENU = 330f
    /** The chat lane beside the menu never grows wider than this. */
    const val LANE_MAX = 720f
    const val RAIL_FROM = 600f
    const val TWO_PANES_FROM = 840f
    const val TWO_PANES_MIN_HEIGHT = 480f
    /** From this width the side menu stays open on the left in either orientation (a tablet held upright too). */
    const val PERMANENT_MENU_FROM = 720f
    const val LEFT_MIN = 400f
    const val LEFT_MAX = 540f
    const val LEFT_SHARE = 0.40f
    const val RIGHT_MIN = 480f
    /** The line between the panes. */
    const val DIVIDER = 1f
}

/** A window's size in dp and its text size (1 is normal, 2 is the largest). */
data class WindowShape(val widthDp: Float, val heightDp: Float, val fontScale: Float = 1f) {
    val widthClass: WidthClass
        get() = when {
            widthDp < PaneRules.RAIL_FROM -> WidthClass.Compact
            widthDp < PaneRules.TWO_PANES_FROM -> WidthClass.Medium
            else -> WidthClass.Expanded
        }

    /**
     * From 600 dp of width dialogs, a reading lane and key hints apply (the menu is a drawer until 840). Width alone decides: the keyboard opening
     * (which only makes the window shorter) never brings the bottom bar back.
     */
    val rail: Boolean get() = widthDp >= PaneRules.RAIL_FROM

    /**
     * The side menu stays open on the left (no menu button) wherever there is room: a window at least 720 dp wide,
     * upright or on its side, or a tablet on its side from 600 dp. It must be tall enough not to be a phone turned
     * sideways. On a phone it is a drawer.
     */
    val permanentMenu: Boolean get() = heightDp >= PaneRules.TWO_PANES_MIN_HEIGHT &&
        (widthDp >= PaneRules.PERMANENT_MENU_FROM || (widthDp >= PaneRules.RAIL_FROM && widthDp > heightDp))

    /** The open menu's width. */
    val menuWidth: Float get() = PaneRules.MENU

    /** Sheets become menus and centred dialogs from the same width. */
    val dialogs: Boolean get() = rail

    /**
     * Two panes when both fit beside the rail: the left is about 40% (400 to 540 dp), the right takes
     * the rest and is never under 480 dp. The limits grow with the text size, so very large text gets
     * one wide pane instead of two cramped ones. Null means one pane.
     */
    val panes: PaneWidths?
        get() {
            if (widthDp < PaneRules.TWO_PANES_FROM || heightDp < PaneRules.TWO_PANES_MIN_HEIGHT) return null
            val scale = fontScale.coerceAtLeast(1f)
            val room = widthDp - PaneRules.MENU - PaneRules.DIVIDER
            val left = (room * PaneRules.LEFT_SHARE).coerceIn(PaneRules.LEFT_MIN * scale, PaneRules.LEFT_MAX * scale)
            val right = room - left
            return if (right >= PaneRules.RIGHT_MIN * scale) PaneWidths(left, right) else null
        }

    val twoPanes: Boolean get() = panes != null
}

/** The current window's shape. In a split screen this is the app's half, not the whole display. */
@Composable
fun rememberWindowShape(): WindowShape {
    val drawn = LocalDensity.current
    // Settings › General › Size below 100% shows more, but a phone keeps its phone layout.
    val appSize = io.github.odnwks.mimir.ui.theme.LocalAppSize.current
    val density = if (drawn.density < appSize.baseDensity) androidx.compose.ui.unit.Density(appSize.baseDensity, drawn.fontScale) else drawn
    val size = LocalWindowInfo.current.containerSize
    val config = LocalConfiguration.current
    return remember(size, density.density, density.fontScale, config.screenWidthDp, config.screenHeightDp) {
        // The container size is 0 before the first layout (and on some test hosts): fall back to the configuration.
        val w = if (size.width > 0) size.width / density.density else config.screenWidthDp.toFloat()
        val h = if (size.height > 0) size.height / density.density else config.screenHeightDp.toFloat()
        WindowShape(w, h, density.fontScale)
    }
}

internal val Float.asDp: Dp get() = this.dp
