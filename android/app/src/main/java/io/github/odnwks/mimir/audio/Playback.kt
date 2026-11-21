package io.github.odnwks.mimir.audio

import java.io.File

/** One update's audio, ready to play. */
data class Track(val id: String, val title: String, val artist: String, val file: File)

/** What the player is doing, for the screens (reported a few times a second while playing). */
data class PlayState(
    val trackId: String? = null,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val lengthMs: Long = 0,
    val speed: Float = 1f,
)

/**
 * Plays updates. The real one is [Speaker] (Android's media player with a media session, so the
 * lock screen and headset buttons work); checks use a stand-in.
 */
interface Playback {
    /** Plays [tracks] in order, starting with [startId]. */
    fun play(tracks: List<Track>, startId: String)
    fun toggle()
    fun next()
    fun back10()
    fun setSpeed(speed: Float)
    /** Called on every change (and while playing, about twice a second). */
    var onState: ((PlayState) -> Unit)?

    /** No sound at all (before the app is set up, and in checks). */
    class None : Playback {
        override fun play(tracks: List<Track>, startId: String) {}
        override fun toggle() {}
        override fun next() {}
        override fun back10() {}
        override fun setSpeed(speed: Float) {}
        override var onState: ((PlayState) -> Unit)? = null
    }
}
