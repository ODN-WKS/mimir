package io.github.odnwks.mimir.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import io.github.odnwks.mimir.nav.KeyboardHold

/** The app's [KeyboardHold]; null where a box is drawn on its own. */
val LocalKeyboardHold = compositionLocalOf<KeyboardHold?> { null }

/**
 * Puts the keyboard away (a tapped Send, the app leaving the screen): the box lets go of the cursor,
 * so nothing brings the keyboard back, and no box takes it by itself until the person does something.
 * The words in the box are not touched.
 */
@Composable
fun rememberPutKeyboardAway(hold: KeyboardHold? = LocalKeyboardHold.current): () -> Unit {
    val focus = LocalFocusManager.current
    val keys = LocalSoftwareKeyboardController.current
    return {
        hold?.hold()
        keys?.hide()
        focus.clearFocus(force = true)
    }
}
