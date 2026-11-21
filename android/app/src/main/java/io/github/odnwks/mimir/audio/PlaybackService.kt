package io.github.odnwks.mimir.audio

import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/** Puts the update player in a media session: lock-screen, notification and headset controls. */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        session = MediaSession.Builder(this, Speaker.get(this).player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        session?.run { release() }
        session = null
        super.onDestroy()
    }
}
