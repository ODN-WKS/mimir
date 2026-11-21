package io.github.odnwks.mimir.audio

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Talking a message with no PC: the phone's own speech recognition turns it into words.
 * Words heard so far come to [Listener.partial]; the end comes once to [Listener.done], with the
 * words or a plain reason.
 */
interface SpeechSource {
    fun interface Listener {
        fun done(text: String?, error: String?)
        fun partial(text: String) {}
    }

    fun start(listener: Listener)
    /** Stop listening and turn what was said into words. */
    fun stop()
    /** Stop and forget what was said. */
    fun cancel()
}

/** What the phone can do for talking a message. */
enum class PhoneSpeechKind {
    /** Recognition that runs on the phone itself: nothing leaves it. */
    OnDevice,
    /** The phone's usual voice typing, asked to work offline where it can. */
    PreferOffline,
    None,
}

object PhoneSpeech {
    /** Why talking can't start in Whisper mode: only recognition on the phone itself is allowed there. */
    const val WHISPER_NEEDS_ON_DEVICE =
        "In Whisper mode, talking works only with speech recognition on this phone, and this phone doesn't have it, so nothing was sent. Type instead."
    const val NONE = "This phone has no voice typing. Type instead."

    fun kind(context: Context): PhoneSpeechKind = runCatching {
        when {
            Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context) -> PhoneSpeechKind.OnDevice
            SpeechRecognizer.isRecognitionAvailable(context) -> PhoneSpeechKind.PreferOffline
            else -> PhoneSpeechKind.None
        }
    }.getOrDefault(PhoneSpeechKind.None)

    /**
     * A recognizer for this phone, or null with the reason: with [onDeviceOnly] (Whisper mode)
     * only one that runs on the phone itself.
     */
    fun create(context: Context, onDeviceOnly: Boolean): Pair<SpeechSource?, String?> = when (kind(context)) {
        PhoneSpeechKind.OnDevice -> AndroidSpeech(context, onDevice = true) to null
        PhoneSpeechKind.PreferOffline -> if (onDeviceOnly) null to WHISPER_NEEDS_ON_DEVICE else AndroidSpeech(context, onDevice = false) to null
        PhoneSpeechKind.None -> null to if (onDeviceOnly) WHISPER_NEEDS_ON_DEVICE else NONE
    }

    /** A recognizer's error in plain words. */
    fun problem(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Nothing was heard. Try again a little closer."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
            "Voice typing needs the internet unless this phone's offline speech is installed (Android settings › Voice input). Type instead."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "The microphone isn't allowed for this app."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Voice typing is busy in another app. Try again in a moment."
        ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE ->
            "Offline speech for your language isn't on this phone yet. Type instead."
        else -> "Voice typing stopped. Try again."
    }

    // The language errors (Android 12 and later), by number so older phones compile the same.
    private const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
    private const val ERROR_LANGUAGE_UNAVAILABLE = 13
}

/** Android's SpeechRecognizer; used on the main thread. */
private class AndroidSpeech(private val context: Context, private val onDevice: Boolean) : SpeechSource {
    private var recognizer: SpeechRecognizer? = null

    override fun start(listener: SpeechSource.Listener) {
        val r = if (onDevice && Build.VERSION.SDK_INT >= 31) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        else SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        var finished = false
        fun finish(text: String?, error: String?) {
            if (finished) return
            finished = true
            release()
            listener.done(text, error)
        }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) {
                best(partialResults)?.let(listener::partial)
            }
            override fun onResults(results: Bundle?) {
                val text = best(results)
                if (text.isNullOrBlank()) finish(null, PhoneSpeech.problem(SpeechRecognizer.ERROR_NO_MATCH)) else finish(text, null)
            }
            override fun onError(error: Int) = finish(null, PhoneSpeech.problem(error))
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        runCatching { r.startListening(intent) }.onFailure { finish(null, PhoneSpeech.NONE) }
    }

    override fun stop() { runCatching { recognizer?.stopListening() } }

    override fun cancel() {
        runCatching { recognizer?.cancel() }
        release()
    }

    private fun release() {
        val r = recognizer ?: return
        recognizer = null
        runCatching { r.destroy() }
    }

    private fun best(b: Bundle?): String? = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
}
