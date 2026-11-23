package io.github.odnwks.mimir.audio

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken

/**
 * Android's media player for updates. [PlaybackService] puts it in a media session, so the lock
 * screen, the notification and headset buttons pause and skip. Speed keeps the voice's pitch.
 * Everything runs on the main thread, as the player requires.
 */
class Speaker private constructor(context: Context) : Playback {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    val player: ExoPlayer = ExoPlayer.Builder(app)
        .setLooper(Looper.getMainLooper())
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
            /* handleAudioFocus = */ true,
        )
        .setHandleAudioBecomingNoisy(true)
        .build()
    private var controller: MediaController? = null
    private var binding = false

    /** Lets go of the media session and its notification (nothing plays; it may start again later). */
    private val release = Runnable {
        if (player.isPlaying) return@Runnable
        player.stop()
        player.clearMediaItems()
        controller?.release()
        controller = null
        binding = false
        app.stopService(android.content.Intent(app, PlaybackService::class.java))
        report()
    }

    private fun scheduleRelease() {
        main.removeCallbacks(release)
        val delay = ReleasePolicy.delayMs(player.playbackState, player.playWhenReady, player.mediaItemCount > 0) ?: return
        // Nothing was ever played: nothing to let go of.
        if (controller == null && !binding) return
        main.postDelayed(release, delay)
    }

    /** Binds a controller, which starts the media session's service (lock screen controls). */
    private fun bind() {
        if (controller != null || binding) return
        binding = true
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val f = MediaController.Builder(app, token).setApplicationLooper(Looper.getMainLooper()).buildAsync()
        f.addListener({ controller = runCatching { f.get() }.getOrNull(); scheduleRelease() }, { main.post(it) })
    }
    override var onState: ((PlayState) -> Unit)? = null

    private val tick = object : Runnable {
        override fun run() {
            report()
            if (player.isPlaying) main.postDelayed(this, 500)
        }
    }

    init {
        player.addListener(object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                report()
                main.removeCallbacks(tick)
                if (p.isPlaying) main.postDelayed(tick, 500)
                scheduleRelease()
            }
        })
    }

    private fun report() {
        val id = player.currentMediaItem?.mediaId
        onState?.invoke(
            PlayState(
                trackId = id,
                playing = player.isPlaying || (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING),
                positionMs = player.currentPosition.coerceAtLeast(0),
                lengthMs = player.duration.takeIf { it != C.TIME_UNSET } ?: 0,
                speed = player.playbackParameters.speed,
            ),
        )
    }

    private fun onMain(f: () -> Unit) = if (Looper.myLooper() == Looper.getMainLooper()) f() else { main.post(f); Unit }

    override fun play(tracks: List<Track>, startId: String) = onMain {
        main.removeCallbacks(release)
        bind()
        val items = tracks.map { t ->
            MediaItem.Builder().setMediaId(t.id).setUri(t.file.toURI().toString())
                .setMediaMetadata(MediaMetadata.Builder().setTitle(t.title).setArtist(t.artist).build())
                .build()
        }
        val start = tracks.indexOfFirst { it.id == startId }.coerceAtLeast(0)
        player.setMediaItems(items, start, 0)
        player.prepare()
        player.play()
    }

    override fun toggle() = onMain { if (player.isPlaying) player.pause() else player.play() }
    override fun next() = onMain { if (player.hasNextMediaItem()) player.seekToNextMediaItem() else player.pause() }
    override fun back10() = onMain { player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0)) }
    override fun setSpeed(speed: Float) = onMain { player.playbackParameters = PlaybackParameters(speed, 1f) }

    companion object {
        @Volatile private var one: Speaker? = null

        /** The app's one player (made on the main thread the first time). */
        fun get(context: Context): Speaker = one ?: synchronized(this) { one ?: Speaker(context).also { one = it } }
    }
}
