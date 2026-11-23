package io.github.odnwks.mimir.audio

import androidx.media3.common.Player

/**
 * When Listen lets go of Android's media player and its notification: a few seconds after the
 * reading ended or stopped, and 10 minutes after it was paused. While it plays, never. Pure
 * rules, so they are tested without a phone.
 */
object ReleasePolicy {
    /** After the reading ended, was stopped or was replaced by nothing. */
    const val ENDED_MS = 3_000L
    /** After a pause. */
    const val PAUSED_MS = 10 * 60_000L

    /**
     * How long to wait before letting go, or null to keep the player (it plays, or loads).
     * [playbackState] is the player's own (Player.STATE_*), [playWhenReady] whether it is meant to play.
     */
    fun delayMs(playbackState: Int, playWhenReady: Boolean, hasItems: Boolean): Long? = when {
        !hasItems -> ENDED_MS
        playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE -> ENDED_MS
        playbackState == Player.STATE_BUFFERING -> if (playWhenReady) null else PAUSED_MS
        playbackState == Player.STATE_READY -> if (playWhenReady) null else PAUSED_MS
        else -> null
    }
}
