package io.github.odnwks.mimir.data

import android.content.Context
import android.os.Build

/**
 * The name this device shows on the PC's Phones list (QA2 #4): the name the owner gave it in Android
 * if there is one, else maker and model tidied up, else "Android tablet" / "Android phone".
 * Never a serial number.
 */
object DeviceName {
    private val generic = Regex("treble|gsi|gapps|generic|aosp|emulator|sdk_|^unknown|unknown$", RegexOption.IGNORE_CASE)

    /** Pure: [given] is Android's device name setting (may be null), [tablet] picks the last-resort word. */
    fun friendly(given: String?, manufacturer: String, model: String, tablet: Boolean): String {
        val last = if (tablet) "Android tablet" else "Android phone"
        given?.trim()?.takeIf { it.isNotEmpty() && it.length <= 40 && !generic.containsMatchIn(it) }?.let { return it }
        val maker = manufacturer.trim().takeUnless { it.equals("unknown", true) || it.isEmpty() }
        val m = model.trim()
        if (m.isEmpty() || generic.containsMatchIn(m)) return maker?.let { nice(it) }?.let { "$it ${if (tablet) "tablet" else "phone"}" } ?: last
        val name = if (maker != null && !m.startsWith(maker, ignoreCase = true)) "${nice(maker)} $m" else m
        return name.take(40)
    }

    private fun nice(maker: String) = if (maker.length > 3) maker.lowercase().replaceFirstChar { it.uppercase() } else maker

    fun of(context: Context): String {
        val given = runCatching { android.provider.Settings.Global.getString(context.contentResolver, "device_name") }.getOrNull()
        val tablet = context.resources.configuration.smallestScreenWidthDp >= 600
        return friendly(given, Build.MANUFACTURER, Build.MODEL, tablet)
    }
}
