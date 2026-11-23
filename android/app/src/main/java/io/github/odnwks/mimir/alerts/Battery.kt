package io.github.odnwks.mimir.alerts

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings

/** The one-time plain offer to allow unrestricted battery use; the person chooses, never silently. */
object Battery {
    private fun prefs(c: Context) = c.getSharedPreferences("battery_offer", Context.MODE_PRIVATE)

    fun unrestricted(c: Context): Boolean =
        c.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(c.packageName) == true

    /** Show the offer until the person has answered it (opened the settings, or said not now) or already allowed it. */
    fun shouldOffer(c: Context): Boolean = !unrestricted(c) && !prefs(c).getBoolean("answered", false)

    fun answered(c: Context) { prefs(c).edit().putBoolean("answered", true).apply() }

    /** The system's own list, where the person picks Mímir and allows it. */
    fun settings(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
