package io.github.odnwks.mimir.ui.adaptive

import android.app.Activity
import android.os.Build
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * One device word, chosen once at start: "tablet" when the display's shortest side is 600 dp or more,
 * otherwise "phone" (tablet redesign, Section 6). Turning the device or splitting the screen never changes it.
 */
enum class Device(val word: String) {
    Phone("phone"),
    Tablet("tablet");

    val capital: String get() = word.replaceFirstChar { it.uppercase() }

    /** "this phone" / "this tablet". */
    val thisDevice: String get() = "this $word"
    val thisDeviceCapital: String get() = "This $word"

    /** The settings page about pairing: "Phones", the same word the PC uses. */
    val andPc: String get() = "Phones"

    /** The model that runs on the device itself: "On this phone" / "On this tablet". */
    val onThis: String get() = "On this $word"
    val offline: String get() = "This $word is offline"
    val unpair: String get() = "Unpair this $word…"
    val scanWith: String get() = "Scan it with this $word."

    /** Why the on-device model isn't offered here. */
    val modelNotAvailable: String
        get() = "Not available on this $word. Its processor can't run the model. Everything with your PC works."

    /**
     * Rewords a sentence written for a phone. On a phone it comes back unchanged. On a tablet the device
     * itself becomes "tablet", and lists that can hold phones and tablets say "device". The PC's own
     * button ("Pair a phone") is quoted exactly, so it is left alone.
     */
    fun reword(text: String): String {
        if (this == Phone) return text
        var out = text
        for ((from, to) in listWords) out = out.replace(from, to)
        // Keep the PC's button as the PC shows it.
        val keep = "\u0000PAIR\u0000"
        out = out.replace("Pair a phone", keep)
        for ((from, to) in ownWords) out = out.replace(from, to)
        return out.replace(keep, "Pair a phone")
    }

    companion object {
        const val TABLET_FROM_DP = 600

        /** [smallestWidthDp] is the display's shortest side (Configuration.smallestScreenWidthDp). */
        fun of(smallestWidthDp: Int): Device = if (smallestWidthDp >= TABLET_FROM_DP) Tablet else Phone

        /**
         * The device [activity] runs on, from the whole display rather than the app's window, so a tablet
         * showing two apps side by side still calls itself a tablet.
         */
        fun of(activity: Activity): Device {
            if (Build.VERSION.SDK_INT >= 30) {
                val b = activity.windowManager.maximumWindowMetrics.bounds
                val density = activity.resources.displayMetrics.density
                if (b.width() > 0 && b.height() > 0) return of((minOf(b.width(), b.height()) / density).toInt())
            }
            return of(activity.resources.configuration.smallestScreenWidthDp)
        }

        private val listWords = listOf(
            "Phones that can reach it" to "Devices that can reach this PC",
            "Pair another phone" to "Pair another device…",
            "Lost a phone?" to "Lost a device?",
        )
        private val ownWords = listOf(
            "a phone from about 2021" to "a tablet from about 2021",
            "this phone" to "this tablet",
            "This phone" to "This tablet",
            "the phone's" to "the tablet's",
            "The phone" to "The tablet",
            "the phone" to "the tablet",
            "Run a command on this PC" to "Run a command on your PC",
        )
    }
}

/** The device the app runs on; Phone unless the activity says otherwise. */
val LocalDevice = staticCompositionLocalOf { Device.Phone }

/** A sentence written for a phone, in this device's words ("This tablet…" on a tablet). */
@androidx.compose.runtime.Composable
fun String.forDevice(): String = LocalDevice.current.reword(this)
