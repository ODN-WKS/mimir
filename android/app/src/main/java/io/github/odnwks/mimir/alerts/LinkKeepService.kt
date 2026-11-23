package io.github.odnwks.mimir.alerts

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import io.github.odnwks.mimir.data.Stations

/**
 * Holds the phone's own encrypted link to the PC open while a task works or waits for the person
 * and for about 2 minutes after, so "finished" and "needs you" arrive within seconds with the app
 * closed. No Google push, no server. Type specialUse (never dataSync: its time limit).
 */
class LinkKeepService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val keep = Keep()
    private val tick = object : Runnable {
        override fun run() {
            val s = Stations.current()
            val more = s != null && s.paired && keep.tick(s.isConnected(), s.workingCount(), s.waitingCount())
            if (more) handler.postDelayed(this, Keep.CHECK_MS) else stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Never restarted by Android on its own (that would start it from the background). */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onCreate() {
        super.onCreate()
        running = true
        Notifier.channels(this)
        val n: Notification = NotificationCompat.Builder(this, Notifier.KEEP)
            .setSmallIcon(io.github.odnwks.mimir.R.drawable.ic_notify)
            .setContentTitle("Mímir is following your tasks")
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(Notifier.KEEP_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(Notifier.KEEP_ID, n)
        } catch (_: Exception) {
            // Android refused (started from the background): never crash; the 5-minute check covers it.
            running = false
            stopSelf()
            return
        }
        Stations.current()?.keepLink(true)
        handler.postDelayed(tick, Keep.CHECK_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        running = false
        Stations.current()?.keepLink(false)
        super.onDestroy()
    }

    companion object {
        @Volatile var running = false

        /** Starts the service when it should run and isn't (only while the app is visible, or Android refuses). */
        fun maybeStart(context: Context) {
            if (running) return
            val s = Stations.current() ?: return
            if (!Keep.shouldStart(s.paired, s.pcFinishedEvents(), s.workingCount(), s.waitingCount(), AlertRules.showWorking(AlertStore.get(context)))) return
            try {
                androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, LinkKeepService::class.java))
            } catch (_: Exception) {
                // Not allowed from the background: the 5-minute check still covers it.
            }
        }
    }
}
