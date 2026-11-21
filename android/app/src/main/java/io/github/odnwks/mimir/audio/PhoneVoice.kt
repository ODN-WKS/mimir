package io.github.odnwks.mimir.audio

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reads a message aloud on the phone. With no PC (or in Mímir and Whisper chats, which never
 * leave the phone) the phone's own voice speaks it, so read aloud never just fails.
 */
interface ReadAloud {
    /** The message being read now, or null. */
    val reading: StateFlow<String?>

    /** Reads [text] (id [id]) aloud, or stops if that message is being read. */
    fun toggle(id: String, text: String)

    fun stop()

    /** The read-aloud speed, shared with the PC's voice (Settings › Voice). */
    var speed: Float

    /** In words, which voice reads on this phone ("Ingrid", "English (United Kingdom)"), or null before it's ready. */
    val voiceName: String?

    /** The message whose voice is still getting ready (no sound yet), or null. */
    val gettingReady: StateFlow<String?> get() = NOBODY_GETTING_READY
}

private val NOBODY_GETTING_READY: StateFlow<String?> = MutableStateFlow(null)

/**
 * The built-in voice and the reader that speaks with it, one for the app. Settings › Voice
 * shows [store]'s choice; [actions] are its buttons.
 */
class Voices(val store: VoiceStore, val reader: NaturalReader) {
    /** The Voices cards' buttons; [sample] plays through the app's read aloud. */
    fun actions(sample: (String) -> Unit): VoiceActions = object : VoiceActions {
        override fun choose(id: String?) { store.choose(id); if (id != null) onScreen() }
        override fun sample(id: String) = sample(id)
    }

    /**
     * The app is on screen: the chosen voice loads in the background (unless it's the phone's
     * own) and stays loaded, so a tap on read aloud is heard at once.
     */
    fun onScreen() {
        val v = store.state.value.active ?: return
        reader.keep(v, store.folder(v)) { store.ready(v) }
    }

    /** The app left the screen: the voice is let go after a while. */
    fun offScreen() = reader.letGo()

    companion object {
        @Volatile private var one: Voices? = null
        /** The voices if anything has used them yet (nothing is made). */
        fun made(): Voices? = one
        fun get(context: Context): Voices = one ?: synchronized(this) {
            one ?: run {
                val app = context.applicationContext
                val reader = NaturalReader({ v, dir -> SherpaSynth(v, dir, app.assets) })
                val store = VoiceStore(
                    File(app.filesDir, "voices"),
                    object : Carried {
                        override fun open(path: String) = app.assets.open(path)
                        override fun list(path: String) = app.assets.list(path)?.toList().orEmpty()
                    },
                    release = reader::release,
                )
                Voices(store, reader).also { one = it }
            }
        }
    }
}

/**
 * Read aloud on the phone: Ingrid, built into the app (as on the PC), unless Settings › Voice
 * chose the phone's own voice: Android's text-to-speech, in British English when the
 * phone has it. A voice that won't load falls back to Android's for that message. Speaking goes
 * on with the screen locked; a tap on the same message stops it.
 */
class PhoneVoice internal constructor(context: Context, private val voices: Voices) : ReadAloud {
    private val app = context.applicationContext
    @Volatile private var holding = false
    /** Keeps the app at full speed while Ingrid reads (a notification shows); false lets go. */
    private fun hold(on: Boolean) {
        if (holding == on) return
        holding = on
        ReadAloudService.busy(app, on)
    }
    private val prefs = context.applicationContext.getSharedPreferences("voice", 0)
    private var systemName: String? = null
    private val _reading = MutableStateFlow<String?>(null)
    override val reading: StateFlow<String?> = _reading.asStateFlow()
    private val _gettingReady = MutableStateFlow<String?>(null)
    override val gettingReady: StateFlow<String?> = _gettingReady.asStateFlow()
    private var ready = false
    private var pending: Pair<String, String>? = null
    override val voiceName: String?
        get() = voices.store.state.value.active?.name ?: systemName

    // Kept as one of the slider's steps; an older speed (0.75×, 2×) is moved to the nearest one.
    override var speed: Float = ReadSpeed.snap(prefs.getFloat("speed", 1f))
        set(v) { field = ReadSpeed.snap(v); prefs.edit().putFloat("speed", field).apply() }

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status != TextToSpeech.SUCCESS) return@TextToSpeech
        ready = true
        pickVoice()
        pending?.let { (id, text) -> pending = null; sayWithPhone(id, text) }
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { if (_reading.value == id) _reading.value = null }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { if (_reading.value == id) _reading.value = null }
            override fun onStop(id: String?, interrupted: Boolean) { if (_reading.value == id) _reading.value = null }
        })
    }

    private fun pickVoice() {
        val uk = Locale.UK
        val l = if (tts.isLanguageAvailable(uk) >= TextToSpeech.LANG_AVAILABLE) uk else Locale.getDefault()
        tts.language = l
        systemName = l.getDisplayName(Locale.UK)
    }

    override fun toggle(id: String, text: String) {
        if (_reading.value == id) { stop(); return }
        say(id, text)
    }

    /** Plays (or stops) [voiceId]'s sample, even before it's the chosen voice. */
    fun sample(voiceId: String) {
        val id = "sample-$voiceId"
        if (_reading.value == id) { stop(); return }
        val v = voices.store.byId(voiceId) ?: return
        speakNatural(id, v.sample, v)
    }

    private fun say(id: String, text: String) {
        val natural = voices.store.state.value.active
        if (natural != null) { speakNatural(id, text, natural); return }
        sayWithPhone(id, text)
    }

    private fun speakNatural(id: String, text: String, v: NaturalVoice) {
        tts.stop()
        pending = null
        _reading.value = id
        hold(true)
        val store = voices.store
        if (id.startsWith("sample-")) store.setSampling(v.id)
        _gettingReady.value = if (voices.reader.isReady(v.id)) null else id
        val heard = { _gettingReady.compareAndSet(id, null); Unit }
        voices.reader.speak(
            text, v, store.folder(v), speed, prepare = { store.ready(v) },
            started = heard,
            ended = { heard(); hold(false); if (_reading.value == id) _reading.value = null; store.setSampling(null) },
            failed = { heard(); hold(false); store.setSampling(null); if (_reading.value == id) sayWithPhone(id, text) },
        )
    }

    private fun sayWithPhone(id: String, text: String) {
        _reading.value = id
        if (!ready) { pending = id to text; return }
        tts.setSpeechRate(speed)
        tts.speak(text.take(TextToSpeech.getMaxSpeechInputLength() - 1), TextToSpeech.QUEUE_FLUSH, Bundle(), id)
    }

    override fun stop() {
        pending = null
        _gettingReady.value = null
        tts.stop()
        voices.reader.stop()
        hold(false)
        voices.store.setSampling(null)
        _reading.value = null
    }

    companion object {
        @Volatile private var one: PhoneVoice? = null
        fun get(context: Context): PhoneVoice = one ?: synchronized(this) { one ?: PhoneVoice(context, Voices.get(context)).also { one = it } }
    }
}
