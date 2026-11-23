package io.github.odnwks.mimir.ui

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.odnwks.mimir.audio.PhoneVoice
import io.github.odnwks.mimir.audio.ReadAloud

/** The phone's read-aloud (checks put a stand-in here). */
val LocalReadAloud = staticCompositionLocalOf<ReadAloud?> { null }

/** True while the player is running (checks can override this). */
val LocalPlayerRuns = compositionLocalOf { false }

/** Whether [id] is being read now, by the phone's voice or the PC's. */
@Composable
fun readingNow(voice: ReadAloud, id: String): Boolean {
    val phone by voice.reading.collectAsStateWithLifecycle()
    val pcId by io.github.odnwks.mimir.audio.PcReading.id.collectAsStateWithLifecycle()
    return phone == id || pcId == id
}

/**
 * One tap on a Listen control: it stops [id] if it is being read (either voice, at once), else
 * starts it ([pc]'s voice when given). Taps in quick succession count once.
 */
fun tapRead(voice: ReadAloud, id: String, text: String, pc: (() -> Unit)?) {
    val pcr = io.github.odnwks.mimir.audio.PcReading
    if (voice.reading.value == id) { voice.toggle(id, text); return }
    if (pcr.id.value == id) { if (!pcr.tooSoon()) pcr.stop(); return }
    if (pc == null) { voice.toggle(id, text); return }
    if (pcr.tooSoon()) return
    voice.stop()
    pc()
}

@Composable
fun rememberReadAloud(): ReadAloud {
    val given = LocalReadAloud.current
    val ctx = LocalContext.current
    return given ?: remember { PhoneVoice.get(ctx) }
}

/**
 * The small speaker beside a reply: reads it aloud, and a second tap stops it. [pc] plays the PC's
 * voice instead when the PC is there to make it (never for Mímir or Whisper chats).
 */
@Composable
fun ReadAloudButton(id: String, text: String, pc: (() -> Unit)? = null) {
    val voice = rememberReadAloud()
    val getting by voice.gettingReady.collectAsStateWithLifecycle()
    val on = readingNow(voice, id)
    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        RoundButton(if (on) Glyphs.Stop else Glyphs.Speaker, if (on) "Stop reading" else "Read aloud", { tapRead(voice, id, text, pc) }, size = 48.dp, iconSize = 18.dp)
        // The first read after the app starts may wait for the voice to load: it says so, rather than going quiet.
        if (on && getting == id) androidx.compose.material3.Text(
            "Getting ${voice.voiceName ?: "the voice"} ready…", style = io.github.odnwks.mimir.ui.theme.Type.Small,
            color = io.github.odnwks.mimir.ui.theme.Palette.TextDim,
            modifier = androidx.compose.ui.Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
        )
    }
}

/** A long press on a reply: Read aloud (or Stop reading) and Copy. */
/** An answer's menu item: search the web for the owner's question, then answer again. */
const val LOOK_IT_UP = "Look it up"

@Composable
fun ReplyMenu(open: Boolean, onClose: () -> Unit, id: String, text: String, pc: (() -> Unit)? = null, lookUp: (() -> Unit)? = null) {
    val voice = rememberReadAloud()
    val on = readingNow(voice, id)
    val clip = LocalClipboardManager.current
    DropdownMenu(open, onClose) {
        DropdownMenuItem({ Text(if (on) "Stop reading" else "Read aloud") }, {
            onClose()
            tapRead(voice, id, text, pc)
        })
        DropdownMenuItem({ Text("Copy") }, { onClose(); clip.setText(AnnotatedString(text)) })
        // Normal mode: search the web for the question, and answer again from what's found.
        if (lookUp != null) DropdownMenuItem({ Text(LOOK_IT_UP) }, { onClose(); lookUp() })
    }
}
