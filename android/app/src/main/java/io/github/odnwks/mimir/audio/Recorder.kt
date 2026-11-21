package io.github.odnwks.mimir.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Records what the owner says as 16 kHz mono 16-bit WAV, the format the PC's listening reads.
 * Stops by itself after [MAX_SECONDS].
 */
class Recorder {
    @Volatile private var running = false
    private var thread: Thread? = null
    private val pcm = ByteArrayOutputStream()
    /** Loudness of the newest bit (0 to 1), for the mic's pulse. */
    @Volatile var level = 0f
        private set

    /** Starts recording; false if the microphone couldn't be opened. */
    @SuppressLint("MissingPermission") // asked for before this is called
    fun start(): Boolean {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false
        val rec = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 4)
        }.getOrNull() ?: return false
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return false }
        synchronized(pcm) { pcm.reset() }
        running = true
        rec.startRecording()
        thread = Thread({
            val buf = ShortArray(min)
            val bytes = ByteBuffer.allocate(min * 2).order(ByteOrder.LITTLE_ENDIAN)
            var total = 0
            while (running && total < RATE * MAX_SECONDS) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                bytes.clear()
                var peak = 0
                for (i in 0 until n) { bytes.putShort(buf[i]); peak = maxOf(peak, kotlin.math.abs(buf[i].toInt())) }
                level = peak / 32768f
                synchronized(pcm) { pcm.write(bytes.array(), 0, n * 2) }
                total += n
            }
            rec.stop()
            rec.release()
            running = false
        }, "recorder").apply { start() }
        return true
    }

    val recording: Boolean get() = running

    /** Stops and gives the WAV file's bytes. */
    fun stop(): ByteArray {
        running = false
        thread?.join(2000)
        val data = synchronized(pcm) { pcm.toByteArray() }
        return wav(data)
    }

    companion object {
        const val RATE = 16_000
        const val MAX_SECONDS = 60

        fun wav(pcm: ByteArray): ByteArray {
            val b = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
            b.put("RIFF".toByteArray()); b.putInt(36 + pcm.size); b.put("WAVEfmt ".toByteArray())
            b.putInt(16); b.putShort(1); b.putShort(1); b.putInt(RATE); b.putInt(RATE * 2)
            b.putShort(2); b.putShort(16); b.put("data".toByteArray()); b.putInt(pcm.size); b.put(pcm)
            return b.array()
        }
    }
}
