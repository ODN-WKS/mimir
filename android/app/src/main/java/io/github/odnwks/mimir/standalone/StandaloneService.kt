package io.github.odnwks.mimir.standalone

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
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeps the app running while standalone mode writes a reply or downloads a model, with a quiet
 * notification ("Writing a reply…"). Android may stop an app's work once it leaves the screen;
 * a foreground service with its notification is how it's allowed to finish.
 */
class StandaloneService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra("text") ?: "Working on this phone…"
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).putExtra("open", "standalone"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(io.github.odnwks.mimir.R.drawable.ic_notify)
            .setContentTitle("Mímir")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID, n)
        }
        // Only now may it stop: Android closes an app whose service, once asked for, stops
        // before showing its notification (a download that fails at once did that).
        running = this
        if (count.get() == 0) finish()
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
        private const val CHANNEL = "standalone"
        private const val ID = 7301
        private val count = AtomicInteger(0)

        /** The service once its notification is up (main thread only). */
        private var running: StandaloneService? = null

        /** Work that must finish started ([on]) or ended. */
        fun busy(context: Context, on: Boolean) {
            val now = if (on) count.incrementAndGet() else count.updateAndGet { (it - 1).coerceAtLeast(0) }
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Mímir on this phone", NotificationManager.IMPORTANCE_LOW))
            val intent = Intent(context, StandaloneService::class.java)
            if (now > 0) {
                runCatching { context.startForegroundService(intent.putExtra("text", "Writing a reply or downloading a model…")) }
            } else {
                // A service that has shown its notification stops here. One still starting is
                // never stopped from outside: it stops itself right after its notification.
                Handler(Looper.getMainLooper()).post { if (count.get() == 0) running?.finish() }
            }
        }
    }
}
