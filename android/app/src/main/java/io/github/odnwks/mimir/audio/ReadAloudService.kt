package io.github.odnwks.mimir.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import io.github.odnwks.mimir.MainActivity

/**
 * Keeps the app at full speed while Ingrid reads aloud, with a quiet notification ("Reading
 * aloud"). Without it, Samsung slows an app that is off screen to a crawl after a few seconds:
 * the voice then makes a phrase in far more time than the phrase lasts, and the reading stops.
 */
class ReadAloudService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(io.github.odnwks.mimir.R.drawable.ic_notify)
            .setContentTitle("Reading aloud")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) else startForeground(ID, n)
        // Only now may it stop: Android closes an app whose service stops before its notification shows.
        running = this
        if (!wanted) finish()
        return START_NOT_STICKY
    }

    private fun finish() {
        running = null
        stopSelf()
    }

    override fun onDestroy() {
        if (running === this) running = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "readaloud"
        private const val ID = 7302
        @Volatile private var wanted = false
        private var running: ReadAloudService? = null

        /** Reading started ([on]) or ended. */
        fun busy(context: Context, on: Boolean) {
            wanted = on
            if (on) {
                val nm = context.getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Reading aloud", NotificationManager.IMPORTANCE_LOW))
                runCatching { context.startForegroundService(Intent(context, ReadAloudService::class.java)) }
            } else {
                Handler(Looper.getMainLooper()).post { if (!wanted) running?.finish() }
            }
        }
    }
}
