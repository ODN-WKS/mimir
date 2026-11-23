package io.github.odnwks.mimir.alerts

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import io.github.odnwks.mimir.data.StayMode
import io.github.odnwks.mimir.data.StayStore

/** The staying-in-touch mode, kept in the app's settings. */
class PrefsStayStore(private val context: Context) : StayStore {
    private val prefs get() = context.getSharedPreferences("stay", Context.MODE_PRIVATE)

    override fun mode(): StayMode =
        runCatching { StayMode.valueOf(prefs.getString("mode", StayMode.Every5.name)!!) }.getOrDefault(StayMode.Every5)

    override fun save(mode: StayMode) {
        prefs.edit().putString("mode", mode.name).apply()
        Stay.arm(context)
    }
}

/**
 * "Check every 5 minutes": an alarm that may fire while the phone dozes (no special permission),
 * started again after each check and after a restart. Android lets it fire about every 8 to 9
 * minutes in deep sleep. While a task works, the link-keeping service holds the link instead.
 */
object Stay {
    const val EVERY_MS = 5 * 60 * 1000L

    /**
     * PHD-13: Whether the alarm should be armed.  Requires the phone to be paired (identity
     * non-null) so a fresh-install phone never starts the 5-minute alarm.
     */
    fun shouldArm(mode: StayMode, paired: Boolean): Boolean =
        mode == StayMode.Every5 && paired

    private fun intent(context: Context) = PendingIntent.getBroadcast(
        context, 0, Intent(context, CheckReceiver::class.java).setAction(CheckReceiver.ACTION),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Sets the next check, or none in Battery saver or while unpaired (PHD-13). */
    fun arm(context: Context, paired: Boolean = io.github.odnwks.mimir.data.Stations.current()?.paired ?: false) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = intent(context)
        if (!shouldArm(PrefsStayStore(context).mode(), paired)) {
            am.cancel(pi)
            return
        }
        am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + EVERY_MS, pi)
    }
}

/** The alarm, and the phone starting up: the check runs, and the next one is set. */
class CheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION -> CheckWorker.now(context)
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {}
            else -> return
        }
        Stay.arm(context)
    }

    companion object {
        const val ACTION = "io.github.odnwks.mimir.CHECK"
    }
}

/** After a restart or an update of the app: the next check is set again. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) Stay.arm(context)
    }
}
