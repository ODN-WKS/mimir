package io.github.odnwks.mimir.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Turns one phrase into sound (sherpa-onnx on the phone, a stand-in in checks). */
interface Synth {
    val sampleRate: Int
    fun make(text: String): FloatArray
    fun close()
}

/** Where the sound goes (the phone's speaker, a stand-in in checks). */
interface Sink {
    /** [speed] plays it faster without raising its pitch. */
    fun start(sampleRate: Int, speed: Float)
    fun write(samples: FloatArray)
    /** Waits until everything written has been heard. */
    fun drain()
    fun stop()
}

/**
 * sherpa-onnx 1.13.8, as the PC's say-update sets it up (silence scale 0.2, one sentence at a
 * time). The model and its tokens are read from inside the app ([assets]); espeak's data from
 * [dir], where the voice store copied it.
 */
class SherpaSynth(voice: NaturalVoice, dir: File, assets: android.content.res.AssetManager) : Synth {
    private val tts: OfflineTts

    init {
        check(io.github.odnwks.mimir.NativeGuard.available(io.github.odnwks.mimir.NativeGuard.VOICE)) { "the voice library isn't on this phone" }
        val model = OfflineTtsModelConfig(
            vits = OfflineTtsVitsModelConfig(
                model = voice.model, tokens = voice.tokens, dataDir = File(dir, "espeak-ng-data").path,
                noiseScale = 0.667f, noiseScaleW = 0.8f, lengthScale = 1f,
            ),
            numThreads = voice.threads, debug = false, provider = "cpu",
        )
        tts = OfflineTts(assetManager = assets, config = OfflineTtsConfig(model = model, maxNumSentences = 1, silenceScale = 0.2f))
    }

    override val sampleRate: Int get() = tts.sampleRate()
    override fun make(text: String): FloatArray = tts.generate(text, 0, 1f).samples
    override fun close() = tts.release()
}

/** The phone's speaker: a stream of float samples, sped up by the player itself (pitch kept). */
class TrackSink : Sink {
    private var track: AudioTrack? = null
    private var rate = 24000
    private var written = 0L

    @Synchronized override fun start(sampleRate: Int, speed: Float) {
        rate = sampleRate
        val min = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(min, sampleRate * 4 / 2))
            .build().also { t ->
                if (speed != 1f) runCatching { t.playbackParams = PlaybackParams().setSpeed(speed).setPitch(1f) }
                t.play()
            }
    }

    override fun write(samples: FloatArray) {
        val t = synchronized(this) { track } ?: return
        var at = 0
        while (at < samples.size) {
            val n = t.write(samples, at, samples.size - at, AudioTrack.WRITE_BLOCKING)
            if (n <= 0) return
            at += n
        }
        synchronized(this) { written += samples.size }
    }

    override fun drain() {
        // A short silence so the last word isn't cut, then wait while the head moves to the end.
        // (Stopping the track first resets the head on some phones: the S8 then waited seconds.)
        write(FloatArray(rate * 15 / 100))
        val t = synchronized(this) { track } ?: return
        var last = -1L
        var moved = SystemClock.elapsedRealtime()
        while (synchronized(this) { track } != null) {
            val head = runCatching { t.playbackHeadPosition.toLong() and 0xffffffffL }.getOrDefault(written)
            if (head >= written) return
            val now = SystemClock.elapsedRealtime()
            if (head != last) { last = head; moved = now } else if (now - moved > 1500) return
            SystemClock.sleep(20)
        }
    }

    @Synchronized override fun stop() {
        track?.let { runCatching { it.pause(); it.flush(); it.release() } }
        track = null
    }
}

/**
 * Reads aloud with the built-in voice: phrase by phrase, each played as soon as it's made while
 * the next one is made, with the PC's pauses between them. The voice stays loaded for a little
 * while after it stops (the next message starts at once), then it's let go to free the memory.
 */
class NaturalReader(
    private val synth: (NaturalVoice, File) -> Synth,
    private val sink: () -> Sink = ::TrackSink,
    private val idleMs: Long = 90_000,
    /** How long a read may go without sound before the phone's own voice reads it instead. */
    private val soundLimitMs: Long = SOUND_LIMIT_MS,
) {
    @Volatile private var workerThread: Thread? = null
    private val worker = ScheduledThreadPoolExecutor(1) { Thread(it, "voice-make").apply { isDaemon = true; workerThread = this } }
    /** Watches that sound starts in time (a separate thread, so a stuck load can't stop it). */
    private val watch = ScheduledThreadPoolExecutor(1) { Thread(it, "voice-watch").apply { isDaemon = true } }
    private val session = AtomicInteger()
    @Volatile private var out: Sink? = null
    private var loaded: Pair<String, Synth>? = null
    /** The voice that's loaded, readable from any thread. */
    @Volatile private var readyId: String? = null
    /** Kept loaded while the app is on screen ([keep]). */
    @Volatile private var kept = false
    private var idle: ScheduledFuture<*>? = null

    /** How long the last message took to start, in milliseconds (the S8 check reports it). */
    @Volatile var lastStartMs: Long = 0
        private set

    /** Whether [voiceId] is loaded, so a read starts at once. */
    fun isReady(voiceId: String): Boolean = readyId == voiceId

    /**
     * Loads [voice] ahead of the first read (in the background, at low priority) and keeps it
     * while the app is on screen, so a tap is heard at once.
     */
    fun keep(voice: NaturalVoice, dir: File, prepare: () -> Boolean = { true }) {
        kept = true
        worker.execute {
            idle?.cancel(false)
            if (loaded?.first == voice.id) return@execute
            val t0 = SystemClock.elapsedRealtime()
            val me = android.os.Process.myTid()
            val was = runCatching { android.os.Process.getThreadPriority(me) }.getOrDefault(0)
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
            try {
                check(prepare()) { "the voice's files aren't ready" }
                engineFor(voice, dir)
                Log.i(TAG, "${voice.id}: loaded ahead in ${SystemClock.elapsedRealtime() - t0} ms")
            } catch (e: Throwable) {
                Log.e(TAG, "${voice.id} wouldn't load ahead; a read will try again", e)
            } finally {
                runCatching { android.os.Process.setThreadPriority(was) }
            }
            if (!kept) scheduleIdle()
        }
    }

    /** The app left the screen: the voice is let go after a while unless it reads again. */
    fun letGo() {
        kept = false
        worker.execute { scheduleIdle() }
    }

    /**
     * Reads [text] with [voice] (its files in [dir]) at [speed] times the voice's own pace.
     * [started] is called when the first sound plays; [ended] when it has been read or was
     * stopped; [failed] when the voice wouldn't load or no sound came within the limit (the
     * caller then uses the phone's own voice, and [ended] isn't called).
     */
    fun speak(
        text: String, voice: NaturalVoice, dir: File, speed: Float,
        ended: () -> Unit, failed: () -> Unit,
        /** Readies the voice's files first (off the main thread); false: it can't be used. */
        prepare: () -> Boolean = { true },
        started: () -> Unit = {},
    ) {
        val mine = session.incrementAndGet()
        out?.stop()
        val asked = SystemClock.elapsedRealtime()
        // Waiting for the first sound, then either sounding (or done) or handed to the phone's voice.
        val state = AtomicInteger(WAITING)
        val giveUp = watch.schedule({
            if (session.get() == mine && state.compareAndSet(WAITING, HANDED_OVER)) {
                Log.e(TAG, "no sound from ${voice.id} after $soundLimitMs ms; reading with the phone's own voice")
                failed()
            }
        }, soundLimitMs, TimeUnit.MILLISECONDS)
        worker.execute {
            if (session.get() != mine) { giveUp.cancel(false); return@execute }
            idle?.cancel(false)
            val t0 = SystemClock.elapsedRealtime()
            var tFiles = t0
            val engine = runCatching { check(prepare()) { "the voice's files aren't ready" }; tFiles = SystemClock.elapsedRealtime(); engineFor(voice, dir) }
                .onFailure { Log.e(TAG, "${voice.id} wouldn't load; reading with the phone's own voice", it) }.getOrNull()
            if (engine == null) {
                giveUp.cancel(false)
                if (session.get() == mine && state.compareAndSet(WAITING, HANDED_OVER)) failed()
                return@execute
            }
            Log.i(TAG, "${voice.id}: ready after ${SystemClock.elapsedRealtime() - asked} ms (files ${tFiles - t0} ms, voice ${SystemClock.elapsedRealtime() - tFiles} ms)")
            if (state.get() == HANDED_OVER || session.get() != mine) { scheduleIdle(); return@execute }
            val parts = Phrases.of(text)
            val queue = LinkedBlockingQueue<FloatArray>()
            val end = FloatArray(0)
            val s = sink()
            out = s
            val player = Thread({
                var first = true
                try {
                    s.start(engine.sampleRate, voice.tempo * speed)
                    while (session.get() == mine) {
                        val a = queue.take()
                        if (a === end || session.get() != mine) break
                        if (first) {
                            first = false
                            // Too late: the phone's voice has it already.
                            if (!state.compareAndSet(WAITING, SOUNDING)) break
                            giveUp.cancel(false)
                            lastStartMs = SystemClock.elapsedRealtime() - asked
                            Log.i(TAG, "${voice.id}: first sound after $lastStartMs ms")
                            started()
                        }
                        s.write(a)
                    }
                    if (session.get() == mine && state.get() != HANDED_OVER) { s.drain(); Log.i(TAG, "${voice.id}: read in ${SystemClock.elapsedRealtime() - asked} ms") }
                } catch (e: Throwable) {
                    Log.w(TAG, "playing stopped", e)
                } finally {
                    s.stop()
                    // Nothing to say (or stopped) before any sound: done all the same.
                    if (state.compareAndSet(WAITING, SOUNDING)) giveUp.cancel(false)
                    if (session.get() == mine && state.get() != HANDED_OVER) ended()
                }
            }, "voice-play")
            player.start()
            try {
                for (p in parts) {
                    if (session.get() != mine || state.get() == HANDED_OVER) break
                    val made = SystemClock.elapsedRealtime()
                    val sound = engine.make(p.text)
                    if (p === parts.first()) Log.i(TAG, "${voice.id}: first phrase made in ${SystemClock.elapsedRealtime() - made} ms")
                    val silence = engine.sampleRate * p.pauseMs / 1000
                    queue.put(if (silence > 0) sound.copyOf(sound.size + silence) else sound)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "making speech stopped", e)
            } finally {
                queue.put(end)
                scheduleIdle()
            }
        }
    }

    /** Stops reading at once. */
    fun stop() {
        session.incrementAndGet()
        out?.stop()
    }

    /**
     * Lets go of [voiceId]'s files (before they're deleted or replaced). The voice store calls it
     * while it readies the files, which happens on the reader's own thread: there it lets go at
     * once, since waiting for that thread from itself would wait forever (Ingrid was silent so).
     */
    fun release(voiceId: String) {
        val drop = { if (loaded?.first == voiceId) { loaded?.second?.close(); loaded = null; readyId = null } }
        if (Thread.currentThread() === workerThread) { drop(); return }
        stop()
        worker.submit { drop() }.get()
    }

    private fun engineFor(voice: NaturalVoice, dir: File): Synth {
        loaded?.let { (id, e) -> if (id == voice.id) return e; e.close(); loaded = null; readyId = null }
        val e = synth(voice, dir)
        loaded = voice.id to e
        readyId = voice.id
        return e
    }

    private fun scheduleIdle() {
        idle?.cancel(false)
        if (kept) return
        idle = worker.schedule({ if (!kept) { loaded?.second?.close(); loaded = null; readyId = null } }, idleMs, TimeUnit.MILLISECONDS)
    }

    /** Waits for the voice to finish what it was asked (checks). */
    fun settle() { worker.submit {}.get() }

    /** Whether a voice is loaded (checks: it's let go when idle). */
    val isLoaded: Boolean get() = worker.submit<Boolean> { loaded != null }.get()

    companion object {
        private const val TAG = "NaturalReader"
        /**
         * How long a read may go without sound before the phone's own voice takes over. The first read
         * after the app starts may have to wait for the voice to finish loading (the S8 takes about 10 s).
         */
        const val SOUND_LIMIT_MS = 12_000L
        private const val WAITING = 0
        private const val SOUNDING = 1
        private const val HANDED_OVER = 2
    }
}
