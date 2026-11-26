package io.github.odnwks.mimir.ui.adaptive

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.odnwks.mimir.standalone.Catalog
import io.github.odnwks.mimir.standalone.Effort
import io.github.odnwks.mimir.ui.AttachButton
import io.github.odnwks.mimir.ui.MimirComposer
import io.github.odnwks.mimir.ui.OptionsChip
import io.github.odnwks.mimir.ui.chipWord
import io.github.odnwks.mimir.ui.Sight
import io.github.odnwks.mimir.ui.rememberVoiceInput
import io.github.odnwks.mimir.ui.theme.MimirTheme
import io.github.odnwks.mimir.ui.theme.Motion
import io.github.odnwks.mimir.ui.theme.Palette

/**
 * Check build only: Mímir's real message box (the one an empty chat has) on a screen of its own,
 * with the real chips and stand-in state, so the phone gate can check its layout at any text size
 * on a phone that can't run a model or isn't paired. Opened over adb:
 * `am start -n io.github.odnwks.mimir.dev/io.github.odnwks.mimir.ui.adaptive.CheckComposerActivity --es mode unpaired|paired [--ez whisper true]`
 */
class CheckComposerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val device = Device.of(this)
        val reduce = Motion.systemRemovesAnimations(this)
        val paired = intent.getStringExtra("mode") == "paired"
        val whisper = intent.getBooleanExtra("whisper", false)
        setContent {
            MimirTheme(reduceMotion = reduce) {
                CompositionLocalProvider(LocalDevice provides device) { CheckComposer(paired, whisper) }
            }
        }
    }
}

@Composable
private fun CheckComposer(paired: Boolean, whisper: Boolean) {
    var draft by remember { mutableStateOf("") }
    val model = Catalog.default
    val effort = Effort.Balanced
    val voice = rememberVoiceInput(null, whisper = whisper)
    Box(Modifier.fillMaxSize().background(Palette.Ground).windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.navigationBars)), contentAlignment = Alignment.BottomCenter) {
        Column {
            // The one-time private note a new chat has, above the box: the gate checks it stays short at text size 1.5.
            io.github.odnwks.mimir.ui.PrivacyNote(true, {}, pc = paired && !whisper)
            // As PhoneChat holds it: the same padding, the same pieces, nothing changed.
            MimirComposer(
                draft, { draft = it }, waiting = false, writing = false, voice = voice, onSend = {}, onStop = {},
                whisper = whisper, modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 10.dp),
                attach = { AttachButton(Sight.Cannot, filesFull = false, picturesFull = false, onFile = {}, onPhotos = {}, onCamera = {}, onModels = {}) },
                chip = {
                    val words = (if (paired && !whisper) "Your PC's model" else model.chipWord) + " · " + effort.words
                    val shorter = (if (paired && !whisper) "PC model" else io.github.odnwks.mimir.ui.chipShort(model.chipWord)) + " · " + effort.words
                    OptionsChip(words, "Model and effort: $words. Change", shorter = shorter) {}
                },
            )
        }
    }
}
