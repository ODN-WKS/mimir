package io.github.odnwks.mimir.data

import android.util.Log

/** What the link does, for `adb logcat -s MimirLink` (never message text). */
object LinkLog {
    const val TAG = "MimirLink"

    fun i(text: String) {
        runCatching { Log.i(TAG, text) }
    }
}
