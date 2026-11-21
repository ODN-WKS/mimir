package io.github.odnwks.mimir.ui.adaptive

import android.content.res.Configuration
import android.view.KeyEvent as AndroidKeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.unit.dp
import io.github.odnwks.mimir.ui.theme.Palette

// ---------- The message box ----------

/** What a key does in a message box (tablet redesign, Section 7). */
enum class BoxKey { Send, NewLine, Next, Previous, None }

/**
 * Enter sends; Shift+Enter is a new line; Tab and Shift+Tab always leave the box.
 * Only for a real keyboard: the on-screen keyboard's Enter never arrives here as a key.
 */
fun messageBoxKey(key: Key, shift: Boolean, down: Boolean): BoxKey = when (key) {
    Key.Enter, Key.NumPadEnter -> when {
        shift -> BoxKey.NewLine
        down -> BoxKey.Send
        // The key going up again is swallowed with its press, so no stray new line appears.
        else -> BoxKey.Send
    }
    Key.Tab -> if (shift) BoxKey.Previous else BoxKey.Next
    else -> BoxKey.None
}

/** Put on a message box's text field. [onSend] runs on Enter; an empty box should make it do nothing. */
fun Modifier.messageBoxKeys(onSend: () -> Unit): Modifier = composed {
    val focus = LocalFocusManager.current
    this.onPreviewKeyEvent { e ->
        val down = e.type == KeyEventType.KeyDown
        when (messageBoxKey(e.key, e.isShiftPressed, down)) {
            BoxKey.Send -> { if (down && !e.isCtrlPressed) onSend(); !e.isCtrlPressed }
            BoxKey.Next -> { if (down) focus.moveFocus(FocusDirection.Next); true }
            BoxKey.Previous -> { if (down) focus.moveFocus(FocusDirection.Previous); true }
            BoxKey.NewLine, BoxKey.None -> false
        }
    }
}

/** Esc closes or goes back, and nothing else. Menus and dialogs above get the key first. */
fun Modifier.escBack(enabled: Boolean = true, onBack: () -> Unit): Modifier = this.onKeyEvent { e ->
    if (enabled && e.key == Key.Escape && e.type == KeyEventType.KeyUp) { onBack(); true } else false
}

// ---------- The shortcut set ----------

/** Every shortcut, with the words Ctrl+/ and Android's own shortcut screen list. */
enum class Shortcut(val keys: String, val does: String, internal val code: Int, internal val shift: Boolean = false, internal val ctrl: Boolean = true) {
    Chat("Ctrl+1", "Chat", AndroidKeyEvent.KEYCODE_1),
    Status("Ctrl+2", "Your PC", AndroidKeyEvent.KEYCODE_2),
    Updates("Ctrl+3", "Updates", AndroidKeyEvent.KEYCODE_3),
    Settings("Ctrl+,", "Settings", AndroidKeyEvent.KEYCODE_COMMA),
    AskClaude("Ctrl+N", "Ask", AndroidKeyEvent.KEYCODE_N),
    Search("Ctrl+K", "Search tasks and projects", AndroidKeyEvent.KEYCODE_K),
    NextNeedsYou("Ctrl+J", "The next thing that needs you", AndroidKeyEvent.KEYCODE_J),
    Allow("Ctrl+Enter", "Allow, while a card is asking", AndroidKeyEvent.KEYCODE_ENTER),
    DontAllow("Ctrl+D", "Don't allow, while a card is asking", AndroidKeyEvent.KEYCODE_D),
    Pause("Ctrl+.", "Pause this task", AndroidKeyEvent.KEYCODE_PERIOD),
    MessageBox("Ctrl+L", "Go to the message box", AndroidKeyEvent.KEYCODE_L),
    Talk("Ctrl+M", "Talk instead of type", AndroidKeyEvent.KEYCODE_M),
    Keys("Ctrl+/", "The list of keys", AndroidKeyEvent.KEYCODE_SLASH),
    /** Ctrl+Enter when no card is asking: the message box sends. */
    Send("Ctrl+Enter", "Send", AndroidKeyEvent.KEYCODE_ENTER),
    ;

    companion object {
        /** The shortcuts a person can look up (Send shares its keys with Allow, so it isn't listed twice). */
        val listed: List<Shortcut> get() = entries.filter { it != Send }
    }
}

/**
 * The shortcut for a key press, or null. Safety, as on the PC: a bare Enter, a bare letter and Esc
 * never answer a card, so nothing here fires without Ctrl; Allow and Don't allow only while
 * [cardAsking]; otherwise Ctrl+Enter sends and Ctrl+D does nothing.
 */
fun shortcutFor(key: Key, ctrl: Boolean, shift: Boolean, alt: Boolean, cardAsking: Boolean): Shortcut? {
    if (!ctrl || alt || shift) return null
    return when (key) {
        Key.One -> Shortcut.Chat
        Key.Two -> Shortcut.Status
        Key.Three -> Shortcut.Updates
        Key.Comma -> Shortcut.Settings
        Key.N -> Shortcut.AskClaude
        Key.K -> Shortcut.Search
        Key.J -> Shortcut.NextNeedsYou
        Key.Enter, Key.NumPadEnter -> if (cardAsking) Shortcut.Allow else Shortcut.Send
        Key.D -> if (cardAsking) Shortcut.DontAllow else null
        Key.Period -> Shortcut.Pause
        Key.L -> Shortcut.MessageBox
        Key.M -> Shortcut.Talk
        Key.Slash -> Shortcut.Keys
        else -> null
    }
}

/**
 * Put once at the root. [onShortcut] returns true when it acted on the shortcut.
 * A press is swallowed with its release, so the key never also types a letter.
 */
fun Modifier.shortcuts(cardAsking: () -> Boolean, onShortcut: (Shortcut) -> Boolean): Modifier = this.onPreviewKeyEvent { e ->
    val alt = e.nativeKeyEvent.isAltPressed
    val s = shortcutFor(e.key, e.isCtrlPressed, e.isShiftPressed, alt, cardAsking()) ?: return@onPreviewKeyEvent false
    if (e.type == KeyEventType.KeyDown) onShortcut(s) else true
}

/** The same list for Android's own shortcut screen (Activity.onProvideKeyboardShortcuts). */
fun shortcutGroup(appName: String): KeyboardShortcutGroup = KeyboardShortcutGroup(
    appName,
    Shortcut.listed.map { KeyboardShortcutInfo(it.does, it.code, AndroidKeyEvent.META_CTRL_ON) },
)

/** True while a real keyboard is attached; key hints show only then. */
@Composable
fun keyboardAttached(): Boolean {
    val c = LocalConfiguration.current
    return c.keyboard == Configuration.KEYBOARD_QWERTY && c.hardKeyboardHidden != Configuration.HARDKEYBOARDHIDDEN_YES
}

// ---------- The focus ring ----------

/**
 * A 3 dp light-blue ring, 3 dp off the control, while the keyboard's focus is on it.
 * Touch never shows it: it is drawn only in keyboard mode.
 */
fun Modifier.focusRing(shape: Shape = RoundedCornerShape(12.dp)): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val keyboardMode = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    this.onFocusChanged { focused = it.isFocused }.drawWithContent {
        drawContent()
        if (focused && keyboardMode) {
            val gap = 3.dp.toPx()
            val width = 3.dp.toPx()
            val grow = gap + width / 2
            val outline = shape.createOutline(Size(size.width + 2 * grow, size.height + 2 * grow), layoutDirection, this)
            translate(-grow, -grow) {
                when (outline) {
                    is Outline.Rectangle, is Outline.Rounded, is Outline.Generic ->
                        drawOutline(outline, Palette.BlueLight, style = Stroke(width))
                }
            }
        }
    }
}
