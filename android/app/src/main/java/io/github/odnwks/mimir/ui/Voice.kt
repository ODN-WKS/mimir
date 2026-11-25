package io.github.odnwks.mimir.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.odnwks.mimir.audio.PhoneSpeech
import io.github.odnwks.mimir.audio.Recorder
import io.github.odnwks.mimir.audio.SpeechSource
import io.github.odnwks.mimir.data.LISTEN_NOT_SENT
import io.github.odnwks.mimir.data.Station
import io.github.odnwks.mimir.ui.theme.Motion
import io.github.odnwks.mimir.ui.theme.Palette
import io.github.odnwks.mimir.ui.theme.motionSpec
import io.github.odnwks.mimir.ui.theme.Type

/** Where speaking a message stands. */
sealed interface Speaking {
    data object Idle : Speaking
    data object Listening : Speaking
    data object Hearing : Speaking
    /** What was heard: it goes into the message box for the owner to read and send (never by itself). */
    data class Heard(val text: String) : Speaking
    data class Failed(val why: String) : Speaking
}

/**
 * Speaking a message: tap the mic, talk (a tone and a buzz mark the start), tap again. The PC turns
 * it into text, which goes into the message box: nothing is sent until the owner taps Send.
 * With no PC (Mímir, or the PC away) the phone's own speech recognition does it, on the phone
 * itself where it can; in Whisper mode only on the phone itself, or not at all.
 */
@Stable
class VoiceInput(
    private val context: Context,
    /** The PC; null for Mímir, which has none. */
    private val station: Station?,
    private val taskId: String? = null,
    /** Whisper mode: speech may be turned into words only on this phone. */
    private val whisper: Boolean = false,
    private val phoneSpeech: (onDeviceOnly: Boolean) -> Pair<SpeechSource?, String?> = { PhoneSpeech.create(context, it) },
    /** Where a recording made while the PC is away is kept (null: the station keeps it). It goes when the PC is back. */
    private val keep: ((wav: ByteArray, seconds: Int) -> Unit)? = null,
) {
    var state by mutableStateOf<Speaking>(Speaking.Idle)
        private set
    /** True while the phone (not the PC) is turning speech into words. */
    var onPhone by mutableStateOf(false)
        private set
    /** The words the phone has heard so far, while listening. */
    var heardSoFar by mutableStateOf("")
        private set
    private var recorder: Recorder? = null
    private var phone: SpeechSource? = null

    fun start(): Boolean {
        if (station == null || station.state.value.pcAway) {
            val (source, why) = phoneSpeech(whisper)
            if (source != null) return startOnPhone(source)
            // Mímir has no PC to fall back on; with a PC away, the recording waits for it instead.
            if (station == null) {
                state = Speaking.Failed(why ?: PhoneSpeech.NONE)
                return false
            }
        }
        onPhone = false
        val r = Recorder()
        if (!r.start()) {
            state = Speaking.Failed("The microphone couldn't be opened. Close other apps that use it, then try again.")
            return false
        }
        recorder = r
        state = Speaking.Listening
        cue()
        return true
    }

    private fun startOnPhone(source: SpeechSource): Boolean {
        phone = source
        onPhone = true
        heardSoFar = ""
        state = Speaking.Listening
        cue()
        source.start(object : SpeechSource.Listener {
            override fun partial(text: String) { if (phone === source) heardSoFar = text }
            override fun done(text: String?, error: String?) {
                if (phone !== source) return
                phone = null
                heardSoFar = ""
                state = when {
                    error != null -> Speaking.Failed(error)
                    text.isNullOrBlank() -> Speaking.Failed("Nothing was heard. Try again a little closer.")
                    else -> Speaking.Heard(text)
                }
            }
        })
        return true
    }

    fun stop() {
        phone?.let {
            if (state == Speaking.Listening) { state = Speaking.Hearing; cue() }
            it.stop()
            return
        }
        val r = recorder ?: return
        recorder = null
        val wav = r.stop()
        cue()
        recorded(wav)
    }

    /** A finished recording for the PC: kept while it's away, else turned into words there. */
    @androidx.annotation.VisibleForTesting
    fun recorded(wav: ByteArray) {
        val station = station ?: run { state = Speaking.Idle; return }
        if (wav.size < 44 + Recorder.RATE / 2) { // under a quarter of a second
            state = Speaking.Idle
            return
        }
        val seconds = (wav.size - 44) / (Recorder.RATE * 2)
        if (station.state.value.pcAway) {
            // The PC turns speech into words: while it's away the recording is kept on this phone and goes when it's back.
            state = Speaking.Idle
            kept(station, wav, seconds)
            return
        }
        state = Speaking.Hearing
        station.listen(wav) { text, err ->
            state = when {
                err == LISTEN_NOT_SENT -> { kept(station, wav, seconds); Speaking.Idle }
                err != null -> Speaking.Failed(err)
                text.isNullOrBlank() -> Speaking.Failed("Nothing was heard. Try again a little closer.")
                else -> Speaking.Heard(text)
            }
        }
    }

    private fun kept(station: Station, wav: ByteArray, seconds: Int) {
        val k = keep
        if (k != null) k(wav, seconds) else station.keepRecording(taskId, wav, seconds)
    }

    /** The words heard, handed to the message box once; the mic goes back to waiting. */
    fun take(): String? {
        val s = state as? Speaking.Heard ?: return null
        state = Speaking.Idle
        return s.text
    }

    /** Stops the phone's recognizer, if it's listening, and forgets what it heard. */
    fun stopPhone() {
        if (phone == null) return
        cancel()
    }

    fun cancel() {
        phone?.cancel()
        phone = null
        heardSoFar = ""
        recorder?.stop()
        recorder = null
        state = Speaking.Idle
    }

    /** A short tone and a buzz: listening started or ended. */
    private fun cue() {
        runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 60).startTone(ToneGenerator.TONE_PROP_BEEP, 120) }
        runCatching {
            val v = context.getSystemService(Vibrator::class.java)
            if (Build.VERSION.SDK_INT >= 26) v?.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }
}

@Composable
fun rememberVoiceInput(station: Station?, taskId: String? = null, whisper: Boolean = false, keep: ((ByteArray, Int) -> Unit)? = null): VoiceInput {
    val context = LocalContext.current
    val voice = remember(station, taskId, whisper) { VoiceInput(context.applicationContext, station, taskId, whisper, keep = keep) }
    // Leaving the screen, or Whisper mode turning on or off, stops the phone listening.
    DisposableEffect(voice) { onDispose { voice.stopPhone() } }
    return voice
}

/** The mic button: asks for the microphone the first time, then listens until tapped again. */
@Composable
fun MicButton(voice: VoiceInput, label: String, filled: Boolean, surface: androidx.compose.ui.graphics.Color, size: androidx.compose.ui.unit.Dp = 48.dp, outline: Boolean = true, onDenied: (() -> Unit)? = null) {
    val context = LocalContext.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) voice.start() else onDenied?.invoke()
    }
    val listening = voice.state == Speaking.Listening
    RoundButton(
        if (listening) Glyphs.Stop else Glyphs.Mic,
        if (listening) "Stop listening" else label,
        {
            when {
                listening -> voice.stop()
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> voice.start()
                else -> ask.launch(Manifest.permission.RECORD_AUDIO)
            }
        },
        filled = filled || listening,
        fill = if (listening) Palette.Red else Palette.Blue,
        surface = surface,
        size = size,
        iconSize = if (size < 48.dp) 20.dp else 22.dp,
        outline = outline,
    )
}

/** Words heard by the mic, added to what's already in the box (on a new word, not glued on). */
fun withHeard(draft: String, heard: String): String =
    if (draft.isBlank()) heard.trim() else draft.trimEnd() + " " + heard.trim()

/**
 * What the mic is doing, above the message box: listening, or being heard on the PC. What's heard
 * goes into the box through [heard], for the owner to read and send: speech never sends itself.
 */
@Composable
fun VoicePanel(voice: VoiceInput, heard: (String) -> Unit, modifier: Modifier = Modifier) {
    val s = voice.state
    LaunchedEffect(s) { if (s is Speaking.Heard) voice.take()?.let(heard) }
    // It grows and shrinks by height, so the box below never jumps; while it shrinks away it
    // keeps showing what it said last.
    val last = remember { arrayOf(s) }
    val shows = s != Speaking.Idle && s !is Speaking.Heard
    if (shows) last[0] = s
    androidx.compose.animation.AnimatedVisibility(
        visible = shows,
        enter = androidx.compose.animation.expandVertically(motionSpec(Motion.NEEDS_YOU_BAR_MS)) + androidx.compose.animation.fadeIn(motionSpec(Motion.NEEDS_YOU_BAR_MS)),
        exit = androidx.compose.animation.shrinkVertically(motionSpec(Motion.NEEDS_YOU_BAR_MS)) + androidx.compose.animation.fadeOut(motionSpec(Motion.NEEDS_YOU_BAR_MS)),
    ) {
        val shown = last[0]
        val (line, color) = when (shown) {
            Speaking.Idle, is Speaking.Heard -> "" to Palette.TextSoft
            Speaking.Listening -> voice.heardSoFar.takeIf { it.isNotEmpty() }?.let { "“$it”…" to Palette.Text }
                ?: ("Listening… tap the red button when you're done" to Palette.Red)
            Speaking.Hearing -> (if (voice.onPhone) "Turning it into words on this phone…" else "Turning it into words on your PC…") to Palette.TextSoft
            is Speaking.Failed -> shown.why to Palette.AmberText
        }
        Panel(
            modifier.padding(horizontal = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
            background = Palette.Bar, border = if (shown is Speaking.Failed) Palette.Red else Palette.Outline, radius = 16.dp,
            padding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(line, style = Type.Label.copy(fontWeight = FontWeight.SemiBold), color = color)
            when (shown) {
                is Speaking.Failed -> LinkText("OK", { voice.cancel() })
                else -> {}
            }
        }
    }
}

/** Starts [voice] from somewhere other than the mic button (a menu): asks for the microphone first if needed. */
@Composable
fun rememberStartVoice(voice: VoiceInput): () -> Unit {
    val context = LocalContext.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) voice.start() }
    return {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) voice.start()
        else ask.launch(Manifest.permission.RECORD_AUDIO)
    }
}
