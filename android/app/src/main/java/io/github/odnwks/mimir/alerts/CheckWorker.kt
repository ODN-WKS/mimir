package io.github.odnwks.mimir.alerts

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import io.github.odnwks.mimir.data.Stations

/**
 * One check with the PC: the link connects (or is up already), the needs-you list arrives, and
 * the notifications follow it. Started by the 5-minute alarm and by a wake-up push, as an
 * expedited job (on Android 11 and older that's a short foreground job with a quiet notification).
 */
class CheckWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val station = Stations.get(applicationContext)
        if (!station.paired) return Result.success()
        if (station.awaitSynced(25_000)) Notifier.show(applicationContext, station.currentItems())
        return Result.success()
    }

    override fun getForegroundInfo(): ForegroundInfo {
        val n: Notification = NotificationCompat.Builder(applicationContext, Notifier.CONNECTION)
            .setSmallIcon(io.github.odnwks.mimir.R.drawable.ic_notify)
            .setContentTitle("Checking with your PC")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(Notifier.CHECK_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(Notifier.CHECK_ID, n)
        }
    }

    companion object {
        fun now(context: Context) {
            val req = OneTimeWorkRequestBuilder<CheckWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("check", ExistingWorkPolicy.KEEP, req)
        }
    }
}
